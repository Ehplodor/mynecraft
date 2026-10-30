package net.mynecraft.world.life;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.Landscape;
import net.mynecraft.world.World;
import net.mynecraft.world.weather.Atmosphere;

import java.util.SplittableRandom;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Snow lying on the ground, in layers of 2/16 of a block. Near the player it falls where the weather
 * snows and melts where it is mild, more quickly under rain; plants it covers are buried. A chunk
 * that comes into view is first given the snowpack its climate holds at this time of year
 * ({@link #pack}), and its still water the ice the season's cold has made ({@link Frost#thickness}),
 * so the far landscape is white in winter and not only a bubble around the player.
 */
final class SnowCover {
    /** Columns looked at per second, and how far from the player. */
    static final int VISITS = 64, RADIUS = 48;
    /** Snow lies on leaves only this thin; it slides off the rest. */
    private static final int ON_LEAVES = 2;

    /** Weather at a place: precipitation type, rate and air temperature. */
    interface Sky {
        Atmosphere.Sample at(int x, int z, int ground);
    }

    private final World world;
    private final BiFunction<Integer, Integer, Landscape.Column> columns;
    private final SplittableRandom rng;
    private double clock;
    Sky sky;
    /** Layers fallen and melted, for the notebook and tests. */
    int fallen, melted;

    SnowCover(World world, BiFunction<Integer, Integer, Landscape.Column> columns, long seed) {
        this.world = world;
        this.columns = columns;
        this.rng = new SplittableRandom(seed ^ 0x5_0011L);
    }

    /**
     * Layers of snow (0..7) a place with this climate holds at this moment of the year: the pack
     * builds through the cold months and lags the temperature by about a week.
     */
    static int pack(double climate, double phase) {
        double t = Vegetation.celsius(climate, phase - 0.04);
        return (int) Math.max(0, Math.min(7, Math.floor((0.5 - t) * 0.55)));
    }

    void tick(double dt, double px, double pz, Ecosystem.Season season, Consumer<int[]> changed) {
        clock += dt * VISITS;
        while (clock >= 1) {
            clock -= 1;
            int x = (int) Math.floor(px + (rng.nextDouble() * 2 - 1) * RADIUS), z = (int) Math.floor(pz + (rng.nextDouble() * 2 - 1) * RADIUS);
            visit(x, z, season, changed);
        }
    }

    /** One column: a layer more if it snows and freezes here, a layer less if it is mild. */
    void visit(int x, int z, Ecosystem.Season season, Consumer<int[]> changed) {
        if (world.chunk(x >> 4, z >> 4) == null) return;
        Landscape.Column col = columns.apply(x, z);
        int y = top(world, x, z, col.height());
        if (y == Integer.MIN_VALUE) return;
        short ground = world.get(x, y, z), above = world.get(x, y + 1, z);
        int layers = Blocks.snowLayers(ground);
        int at = layers > 0 ? y : y + 1; // where the snow is (or would settle)
        if (layers == 0 && (!holds(ground) || above != Blocks.AIR && !Blocks.isPlant(above))) return;
        double climate = Vegetation.climate(col.temperature(), y);
        double t = Vegetation.celsius(climate, season.phase());
        Atmosphere.Sample w = sky == null ? null : sky.at(x, z, y);
        if (w != null) t = w.temperature();
        boolean snowing = w != null && w.type() == Atmosphere.Precipitation.SNOW && w.precipitation() > 0;
        boolean raining = w != null && w.precipitation() > 0 && w.type() != Atmosphere.Precipitation.SNOW;
        int cap = isLeaves(world.get(x, at - 1, z)) ? ON_LEAVES : 8;
        if (snowing && t < 1 && layers < cap) {
            if (rng.nextDouble() < Math.min(1, 0.3 + w.precipitation() * 6)) {
                if (set(x, at, z, Blocks.snow(layers + 1), changed)) fallen++;
            }
        } else if (layers > 0 && t > 0.5) {
            double rate = Math.min(1, (t - 0.5) / 3) * (raining ? 2 : 1);
            if (rng.nextDouble() < rate && set(x, at, z, layers == 1 ? Blocks.AIR : Blocks.snow(layers - 1), changed)) melted++;
        }
    }

    /**
     * Brings a newly loaded chunk to the season: its snowpack (snow already there within a layer of it
     * stays, so what fell or was trodden near the player is kept), and the ice on its still water.
     * Runs on a loader thread, before the chunk joins the world.
     */
    void settle(Chunk c, Ecosystem.Season season) {
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                Landscape.Column col = columns.apply(c.cx * 16 + lx, c.cz * 16 + lz);
                int h = col.height();
                int y = Integer.MIN_VALUE;
                for (int yy = Math.min(Chunk.MAX_Y - 2, h + 16); yy > Math.max(Chunk.MIN_Y, h - 12); yy--) {
                    short b = c.get(lx, yy, lz);
                    if (b == Blocks.AIR || Blocks.isPlant(b) || Blocks.snowLayers(b) > 0) continue;
                    y = yy;
                    break;
                }
                if (y == Integer.MIN_VALUE) continue;
                double climate = Vegetation.climate(col.temperature(), y), t = Vegetation.celsius(climate, season.phase());
                int at = y + 1, layers = Blocks.snowLayers(c.get(lx, at, lz));
                short b = c.get(lx, y, lz);
                if (b == Blocks.WATER || b == Blocks.ICE) freeze(c, lx, y, lz, Frost.thickness(t), layers > 0);
                if (!holds(c.get(lx, y, lz))) {
                    if (layers > 0) { // the ice under it has gone
                        c.set(lx, at, lz, Blocks.AIR);
                        c.markDirty();
                    }
                    continue;
                }
                int want = pack(climate, season.phase());
                if (isLeaves(c.get(lx, at - 1, lz))) want = Math.min(want, ON_LEAVES);
                if (want == layers || layers > 0 && Math.abs(want - layers) <= 1 && !(want == 0 && t > 3)) continue;
                c.set(lx, at, lz, want == 0 ? Blocks.AIR : Blocks.snow(want));
                c.markDirty();
            }
    }

    /** Sets the ice of a still-water column to {@code thick} blocks (never down to the bed). */
    private static void freeze(Chunk c, int lx, int y, int lz, int thick, boolean snowed) {
        if (c.get(lx, y + 1, lz) != Blocks.AIR && !snowed) return;
        for (int i = 0; i < 3; i++) {
            short b = c.get(lx, y - i, lz);
            if (b != Blocks.WATER && b != Blocks.ICE) break;
            boolean deep = c.get(lx, y - i - 1, lz) == Blocks.WATER || c.get(lx, y - i - 1, lz) == Blocks.ICE;
            short want = i < thick && deep ? Blocks.ICE : Blocks.WATER;
            if (b != want) {
                c.set(lx, y - i, lz, want);
                c.markDirty();
            }
        }
    }

    /** Ground snow can settle on: full solid blocks (ice and leaves included), not water or slabs. */
    static boolean holds(short b) {
        return Blocks.isSolid(b) && Blocks.SHAPE[b & 0xFFFF] == Blocks.SHAPE_CUBE && Blocks.snowLayers(b) == 0;
    }

    private static boolean isLeaves(short b) {
        return b == Blocks.OAK_LEAVES || b == Blocks.BIRCH_LEAVES || b == Blocks.SPRUCE_LEAVES;
    }

    private boolean set(int x, int y, int z, short b, Consumer<int[]> changed) {
        if (!world.set(x, y, z, b)) return false;
        changed.accept(new int[] {x, y, z});
        return true;
    }

    /** The top of a column: snow, or the highest block that is not air or a plant (leaves included). */
    static int top(World world, int x, int z, int h) {
        for (int y = Math.min(Chunk.MAX_Y - 2, h + 16); y > Math.max(Chunk.MIN_Y, h - 12); y--) {
            short b = world.get(x, y, z);
            if (b == Blocks.AIR || Blocks.isPlant(b)) continue;
            return Blocks.isFluid(b) ? Integer.MIN_VALUE : y;
        }
        return Integer.MIN_VALUE;
    }
}
