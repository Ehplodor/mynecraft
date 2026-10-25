package net.mynecraft.world;

import java.util.HashSet;

/**
 * Vanilla-like flowing water and lava, driven by scheduled ticks (20 per second).
 *
 * <p>A fluid cell is re-evaluated a fixed delay after something changes next to it (water 5 ticks,
 * lava 30): a flowing cell takes the level its neighbours give it (or disappears), then the fluid
 * falls if it can, otherwise spreads sideways losing one level per block (lava two). Two water
 * sources next to each other over solid ground create a third. Water meeting lava makes obsidian
 * (source) or cobblestone (flowing); lava flowing into water makes stone.
 *
 * <p>Each fluid type has a constant delay, so a FIFO per type is already ordered by due tick.
 * Only cells near a change are ever simulated; a per-tick budget bounds the cost of big floods.
 */
public final class FluidSimulator {
    /** Called for every block the simulation changes (the caller re-meshes). */
    public interface Listener {
        void changed(int x, int y, int z);
    }

    private static final int WATER_DELAY = 5, LAVA_DELAY = 30, BUDGET = 4096;
    private static final int[] HX = {-1, 1, 0, 0}, HZ = {0, 0, -1, 1};

    private final World world;
    private final Listener listener;
    private final LightEngine.LongQueue[] queues = {null, new LightEngine.LongQueue(), new LightEngine.LongQueue()};
    private final HashSet<Long> scheduled = new HashSet<>();
    // Pre-computation: updates that run without waiting (fluids of newly loaded land), and
    // everything they set off while they run.
    private final LightEngine.LongQueue fast = new LightEngine.LongQueue();
    private boolean settling;
    private long tick;
    /** Fluid updates run so far (for progress and rate displays). */
    public long updates;

    public FluidSimulator(World world, Listener listener) {
        this.world = world;
        this.listener = listener;
    }

    /**
     * Fluid blocks of a fresh chunk that will not stay as they are: flowing levels (their pending
     * ticks are not saved) and fluid with nothing under it. A natural source next to open air at its
     * own level is left alone: that is a river stepping down its bed, drawn as a small cascade; letting
     * it spread like a vanilla source would create water from nothing and flood the hillside.
     * Runs on loader threads; null when the chunk is settled.
     */
    public static int[] unsettled(Chunk c) {
        int[] out = new int[48];
        int n = 0;
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
            if (c.section(sy) == null) continue;
            int y0 = Chunk.sectionMinY(sy);
            for (int y = y0; y < y0 + 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        byte b = c.get(x, y, z);
                        if (Blocks.FLUID[b & 0xFF] == Blocks.FLUID_NONE) continue;
                        boolean open = Blocks.LEVEL[b & 0xFF] != 0 || (y > Chunk.MIN_Y && free(c.get(x, y - 1, z)));
                        if (!open) continue;
                        if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
                        out[n++] = (c.cx << 4) + x;
                        out[n++] = y;
                        out[n++] = (c.cz << 4) + z;
                    }
        }
        return n == 0 ? null : java.util.Arrays.copyOf(out, n);
    }

    private static boolean free(byte b) {
        return b == Blocks.AIR || isPlant(b);
    }

    public int pending() {
        return scheduled.size();
    }

    /** A block changed: the fluids at and around it must be re-evaluated. */
    public void blockChanged(int x, int y, int z) {
        schedule(x, y, z);
        schedule(x, y + 1, z);
        schedule(x, y - 1, z);
        for (int i = 0; i < 4; i++) schedule(x + HX[i], y, z + HZ[i]);
    }

    /**
     * Like {@link #blockChanged}, but the flow it starts is pre-computed by {@link #settleStep}: it
     * reaches its final shape without the 5-tick wait per block (unseen land, just loaded).
     */
    public void settle(int x, int y, int z) {
        boolean was = settling;
        settling = true;
        blockChanged(x, y, z);
        settling = was;
    }

    /** Runs pre-computed flow for at most {@code nanos}; returns the updates done. */
    public int settleStep(long nanos) {
        long end = System.nanoTime() + nanos;
        int done = 0;
        settling = true;
        try {
            while (!fast.isEmpty() && System.nanoTime() < end) {
                for (int k = 0; k < 256 && !fast.isEmpty(); k++) {
                    long p = fast.pop();
                    scheduled.remove(p);
                    updates++;
                    done++;
                    update(LightEngine.unpackX(p), LightEngine.unpackY(p), LightEngine.unpackZ(p));
                }
            }
        } finally {
            settling = false;
        }
        return done;
    }

    private void schedule(int x, int y, int z) {
        byte type = Blocks.FLUID[world.get(x, y, z) & 0xFF];
        if (type == Blocks.FLUID_NONE) return;
        long p = LightEngine.pack(x, y, z);
        if (!scheduled.add(p)) return;
        if (settling) {
            fast.push(p, 0);
            return;
        }
        queues[type].push(p, (int) (tick + (type == Blocks.FLUID_WATER ? WATER_DELAY : LAVA_DELAY)));
    }

    /**
     * Runs game ticks back to back, as fast as the CPU allows, for at most {@code nanos}: the flows
     * reach their final shape without waiting 5 ticks per block. Returns the ticks run.
     */
    public int fastForward(long nanos) {
        long end = System.nanoTime() + nanos;
        int ticks = 0;
        while (!scheduled.isEmpty() && System.nanoTime() < end) {
            settleStep(end - System.nanoTime());
            tick();
            ticks++;
        }
        return ticks;
    }

    /** Advances one game tick and runs the updates that are due (up to the budget). */
    public void tick() {
        tick++;
        int budget = BUDGET;
        for (int type = 1; type <= 2; type++) {
            LightEngine.LongQueue q = queues[type];
            while (!q.isEmpty() && q.peekPayload() <= tick && budget-- > 0) {
                long p = q.pop();
                scheduled.remove(p);
                updates++;
                update(LightEngine.unpackX(p), LightEngine.unpackY(p), LightEngine.unpackZ(p));
            }
        }
    }

    private void update(int x, int y, int z) {
        byte b = world.get(x, y, z);
        byte type = Blocks.FLUID[b & 0xFF];
        if (type == Blocks.FLUID_NONE) return;
        int step = type == Blocks.FLUID_LAVA ? 2 : 1, max = type == Blocks.FLUID_LAVA ? 6 : 7;

        if (Blocks.LEVEL[b & 0xFF] != 0) {
            int level = supportedLevel(x, y, z, type, step, max);
            if (level == DRY) {
                set(x, y, z, Blocks.AIR);
                return;
            }
            byte state = Blocks.fluidState(type, level);
            if (state != b) {
                set(x, y, z, state);
                b = state;
            }
        }

        byte below = world.get(x, y - 1, z);
        if (flowInto(x, y - 1, z, below, type, 8, true)) return; // falling takes priority
        if (Blocks.FLUID[below & 0xFF] == type && Blocks.LEVEL[below & 0xFF] != 0) return; // feeding a column
        int level = Blocks.LEVEL[b & 0xFF];
        int next = (level >= 8 ? 0 : level) + step;
        if (next > max) return;
        for (int i = 0; i < 4; i++) {
            int nx = x + HX[i], nz = z + HZ[i];
            flowInto(nx, y, nz, world.get(nx, y, nz), type, next, false);
        }
    }

    /**
     * The level a flowing cell gets from its surroundings: falling if the same fluid is above,
     * a new source between two water sources over solid ground, else one step past its best
     * horizontal neighbour (a landed falling column counts as a source); DRY past the maximum level.
     */
    private static final int FALLING = 8, DRY = Integer.MAX_VALUE;

    private int supportedLevel(int x, int y, int z, byte type, int step, int max) {
        if (Blocks.FLUID[world.get(x, y + 1, z) & 0xFF] == type) return FALLING;
        int best = Integer.MAX_VALUE, sources = 0;
        for (int i = 0; i < 4; i++) {
            int nx = x + HX[i], nz = z + HZ[i];
            byte n = world.get(nx, y, nz);
            if (Blocks.FLUID[n & 0xFF] != type) continue;
            int lvl = Blocks.LEVEL[n & 0xFF];
            if (lvl == 0) sources++;
            if (lvl >= 8) {
                if (!stopsFall(world.get(nx, y - 1, nz), type)) continue; // still falling past us
                lvl = 0;
            }
            best = Math.min(best, lvl + step);
        }
        if (type == Blocks.FLUID_WATER && sources >= 2) {
            byte below = world.get(x, y - 1, z);
            if (Blocks.isSolid(below) || below == Blocks.WATER) return 0;
        }
        // Too far from any source: dry. (Must not reach 8, which would read as "falling".)
        return best > max ? DRY : best;
    }

    /** True if a falling fluid lands on this block (anything it cannot flow into). */
    private static boolean stopsFall(byte below, byte type) {
        return !(below == Blocks.AIR || isPlant(below) || (Blocks.FLUID[below & 0xFF] == type && Blocks.LEVEL[below & 0xFF] != 0));
    }

    private static boolean isPlant(byte b) {
        return Blocks.SHAPE[b & 0xFF] == Blocks.SHAPE_CROSS;
    }

    /** Tries to put fluid of {@code type} at {@code level} into a cell; returns true if it did something. */
    private boolean flowInto(int x, int y, int z, byte target, byte type, int level, boolean down) {
        if (y < Chunk.MIN_Y || world.chunk(x >> 4, z >> 4) == null) return false; // never into unloaded land
        byte targetType = Blocks.FLUID[target & 0xFF];
        if (targetType != Blocks.FLUID_NONE && targetType != type) {
            // Water and lava meet.
            if (type == Blocks.FLUID_WATER) set(x, y, z, Blocks.LEVEL[target & 0xFF] == 0 ? Blocks.OBSIDIAN : Blocks.COBBLESTONE);
            else set(x, y, z, Blocks.STONE);
            return true;
        }
        if (target == Blocks.AIR || isPlant(target)) {
            set(x, y, z, Blocks.fluidState(type, level));
            return true;
        }
        if (targetType == type && Blocks.LEVEL[target & 0xFF] != 0) {
            int current = Blocks.LEVEL[target & 0xFF];
            boolean better = down ? current != 8 : current < 8 && current > level;
            if (better) {
                set(x, y, z, Blocks.fluidState(type, level));
                return true;
            }
        }
        return false;
    }

    private void set(int x, int y, int z, byte b) {
        if (!world.set(x, y, z, b)) return;
        listener.changed(x, y, z);
        blockChanged(x, y, z);
    }
}
