package net.mynecraft.world.life;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.Landscape;
import net.mynecraft.world.World;

import java.util.SplittableRandom;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * The season's cold on the ground near the player, read from the climate of each column and the
 * time of year (not from the passing weather, which {@code Weather} already handles): still water
 * ices over in winter, from the shores inward, and opens again in spring from its edges; a hard
 * frost kills the tufts of grass and the flowers standing in the open.
 */
final class Frost {
    /** Columns looked at per second, and how far from the player. */
    static final int VISITS = 48, RADIUS = 48;
    /** Mean temperatures (deg C) below which still water freezes, above which ice melts, below which plants die. */
    static final double FREEZE = -1, THAW = 1.5, KILLING = -2;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final World world;
    private final BiFunction<Integer, Integer, Landscape.Column> columns;
    private final SplittableRandom rng;
    private double clock;
    /** Blocks iced over, thawed, and plants killed by the frost, for the notebook and tests. */
    int frozen, thawed, nipped;

    Frost(World world, BiFunction<Integer, Integer, Landscape.Column> columns, long seed) {
        this.world = world;
        this.columns = columns;
        this.rng = new SplittableRandom(seed ^ 0xF205_7L);
    }

    /** Mean temperature of a column now (deg C), from its climate at its ground height. */
    double celsius(int x, int z, int y, Ecosystem.Season season) {
        return Vegetation.celsius(Vegetation.climate(columns.apply(x, z).temperature(), y), season.phase());
    }

    void tick(double dt, double px, double pz, Ecosystem.Season season, Consumer<int[]> changed) {
        clock += dt * VISITS;
        while (clock >= 1) {
            clock -= 1;
            int x = (int) Math.floor(px + (rng.nextDouble() * 2 - 1) * RADIUS), z = (int) Math.floor(pz + (rng.nextDouble() * 2 - 1) * RADIUS);
            visit(x, z, season, changed);
        }
    }

    /** One column: freeze, thaw or nip what stands at its surface. */
    void visit(int x, int z, Ecosystem.Season season, Consumer<int[]> changed) {
        if (world.chunk(x >> 4, z >> 4) == null) return;
        int y = top(x, z);
        if (y == Integer.MIN_VALUE) return;
        short b = world.get(x, y, z);
        double t = celsius(x, z, y, season);
        if (b == Blocks.WATER && world.get(x, y + 1, z) == Blocks.AIR && t < FREEZE) {
            // Ice forms first along the shore and spreads from the ice already there.
            if (touches(x, y, z, true) && rng.nextDouble() < Math.min(1, (FREEZE - t) / 5)) {
                if (set(x, y, z, Blocks.ICE, changed)) frozen++;
            }
        } else if (b == Blocks.ICE) {
            if (t < FREEZE) { // the ice front creeps over a neighbouring stretch of open water
                int[] d = SIDES[rng.nextInt(4)];
                int nx = x + d[0], nz = z + d[1];
                if (world.get(nx, y, nz) == Blocks.WATER && world.get(nx, y + 1, nz) == Blocks.AIR
                    && rng.nextDouble() < Math.min(1, (FREEZE - t) / 5) && set(nx, y, nz, Blocks.ICE, changed)) frozen++;
            } else if (t > THAW && touches(x, y, z, false) && rng.nextDouble() < Math.min(1, (t - THAW) / 4)) {
                // It opens from the edges: along the warming shore and around the open water.
                if (set(x, y, z, Blocks.WATER, changed)) thawed++;
            }
        } else if (t < KILLING) {
            short above = world.get(x, y + 1, z);
            if ((above == Blocks.SHORT_GRASS || above == Blocks.POPPY) && rng.nextDouble() < Math.min(1, (KILLING - t) / 4)) {
                if (set(x, y + 1, z, Blocks.AIR, changed)) nipped++;
            }
        }
    }

    /**
     * Whether a surface block borders the shore (solid ground beside it) or, when freezing, ice
     * already there, when thawing, open water.
     */
    private boolean touches(int x, int y, int z, boolean freezing) {
        for (int[] d : SIDES) {
            short n = world.get(x + d[0], y, z + d[1]);
            if (Blocks.isSolid(n) && n != Blocks.ICE) return true;
            if (freezing ? n == Blocks.ICE : Blocks.isFluid(n)) return true;
        }
        return false;
    }

    private boolean set(int x, int y, int z, short b, Consumer<int[]> changed) {
        if (!world.set(x, y, z, b)) return false;
        changed.accept(new int[] {x, y, z});
        return true;
    }

    /** The surface of a column: the top solid block, water or ice (plants are looked through). */
    private int top(int x, int z) {
        int h = columns.apply(x, z).height();
        for (int y = Math.min(Chunk.MAX_Y - 2, h + 12); y > Math.max(Chunk.MIN_Y, h - 12); y--) {
            short b = world.get(x, y, z);
            if (b == Blocks.AIR || b == Blocks.SHORT_GRASS || b == Blocks.POPPY) continue;
            return Blocks.isSolid(b) || b == Blocks.WATER ? y : Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }
}
