package net.mynecraft.world.life;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.Section;
import net.mynecraft.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Counts the water's cleaners on the banks (E3.4): reeds, one each, and willows, a crown of leaves
 * counting like five reeds (one per six leaves). Each loaded chunk is counted when it comes in and
 * again when it was edited; a patch's cleaners are the sum over its 4 x 4 chunks, which the ecology
 * reads to filter waste water and clean the patch faster.
 */
public final class Riverbank {
    /** Willow leaves per reed-equivalent: a grown willow (about thirty leaves) cleans like five reeds. */
    public static final double LEAVES_PER_REED = 6;
    /** Chunks recounted per call at most (a chunk is about 100,000 reads). */
    static final int BUDGET = 6;

    private final Map<Long, double[]> chunks = new HashMap<>(); // chunk key -> {edits seen, cleaners}

    /** Cleaners in one chunk. */
    public static double count(Chunk c) {
        int reeds = 0, leaves = 0;
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
            Section s = c.section(sy);
            if (s == null || s.isEmpty()) continue;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        short b = s.get(x, y, z);
                        if (b == Blocks.REEDS) reeds++;
                        else if (b == Blocks.WILLOW_LEAVES) leaves++;
                    }
        }
        return reeds + leaves / LEAVES_PER_REED;
    }

    /** Recounts the chunks that are new or were edited (a few per call) and refreshes their patches. */
    public void update(World w, Ecology eco) {
        int done = 0;
        java.util.Set<Long> touched = new java.util.HashSet<>();
        for (Chunk c : w.loadedChunks()) {
            long k = World.key(c.cx, c.cz);
            double[] seen = chunks.get(k);
            if (seen != null && seen[0] == c.edits) continue;
            if (done++ >= BUDGET) break;
            double n = count(c);
            if (seen == null) chunks.put(k, new double[] {c.edits, n});
            else { seen[0] = c.edits; seen[1] = n; }
            touched.add(Ecology.key(Math.floorDiv(c.cx * 16, Ecology.PATCH), Math.floorDiv(c.cz * 16, Ecology.PATCH)));
        }
        for (long pk : touched) refresh(w, eco, pk);
    }

    private void refresh(World w, Ecology eco, long patchKey) {
        int per = Ecology.PATCH / 16;
        int px = (int) (patchKey >> 32), pz = (int) patchKey;
        Ecology.Patch p = eco.at(px * Ecology.PATCH + 1, pz * Ecology.PATCH + 1);
        double sum = 0;
        for (int dz = 0; dz < per; dz++)
            for (int dx = 0; dx < per; dx++) {
                double[] c = chunks.get(World.key(px * per + dx, pz * per + dz));
                if (c != null) sum += c[1];
            }
        p.cleaners = sum;
    }

    /** Forgets an unloaded chunk (its patch keeps the last count until the chunk comes back). */
    public void forget(int cx, int cz) {
        chunks.remove(World.key(cx, cz));
    }
}
