package net.mynecraft.world.life;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.World;

import java.util.SplittableRandom;

/**
 * The plant cover one sees, kept in step with the {@link Ecology}: a few random columns near the
 * player are visited every second; tufts of grass sprout where the patch's plants are thriving and
 * the season grows, poppies open where it is in flower, and both wither in autumn and winter or
 * where the grazers have eaten the patch down.
 */
final class Flora {
    /** Columns looked at per second, and how far from the player. */
    static final int VISITS = 24, RADIUS = 48;

    private final World world;
    private final Ecology ecology;
    private final SplittableRandom rng;
    private double clock;
    /** Tufts grown and withered, for the notebook and tests. */
    int grown, withered;

    Flora(World world, Ecology ecology, long seed) {
        this.world = world;
        this.ecology = ecology;
        this.rng = new SplittableRandom(seed ^ 0xF10AL);
    }

    void tick(double dt, double px, double pz, Ecosystem.Season season, java.util.function.Consumer<int[]> changed) {
        clock += dt * VISITS;
        while (clock >= 1) {
            clock -= 1;
            int x = (int) Math.floor(px + (rng.nextDouble() * 2 - 1) * RADIUS), z = (int) Math.floor(pz + (rng.nextDouble() * 2 - 1) * RADIUS);
            if (world.chunk(x >> 4, z >> 4) == null) continue;
            Ecology.Patch p = ecology.existing(x, z);
            if (p == null) continue;
            int y = surface(x, z);
            if (y == Integer.MIN_VALUE) continue;
            byte ground = world.get(x, y, z), above = world.get(x, y + 1, z);
            boolean growing = season.vegetation() > 0.5 && Ecology.temperature(p, season) > 3;
            if (ground == Blocks.GRASS && above == Blocks.AIR && growing) {
                if (rng.nextDouble() < p.flowers * 0.25) set(x, y + 1, z, Blocks.POPPY, changed);
                else if (rng.nextDouble() < p.plants * 0.3) set(x, y + 1, z, Blocks.SHORT_GRASS, changed);
            } else if (above == Blocks.SHORT_GRASS || above == Blocks.POPPY) {
                double wither = above == Blocks.POPPY ? (season.phase() > 0.55 ? 0.5 : 0.25 * (1 - p.flowers))
                    : (season.vegetation() < 0.4 ? 0.3 : 0.4 * Math.max(0, 0.35 - p.plants));
                if (rng.nextDouble() < wither) {
                    set(x, y + 1, z, Blocks.AIR, changed);
                    withered++;
                    grown--;
                }
            }
        }
    }

    private void set(int x, int y, int z, byte b, java.util.function.Consumer<int[]> changed) {
        if (world.set(x, y, z, b)) {
            if (b != Blocks.AIR) grown++;
            changed.accept(new int[] {x, y, z});
        }
    }

    /** The top solid block of a column (looked for from the generator's height). */
    private int surface(int x, int z) {
        int h = ecology.columns.apply(x, z).height();
        for (int y = Math.min(Chunk.MAX_Y - 2, h + 12); y > h - 12; y--) {
            byte b = world.get(x, y, z);
            if (b == Blocks.AIR || b == Blocks.SHORT_GRASS || b == Blocks.POPPY) continue;
            return Blocks.isSolid(b) ? y : Integer.MIN_VALUE; // leaves, water...: not a meadow
        }
        return Integer.MIN_VALUE;
    }
}
