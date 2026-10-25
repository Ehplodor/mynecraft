package net.mynecraft.world;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mountain streams traced at generation time instead of simulated: from a spring, the water goes
 * down the steepest way on the landscape's height map until it reaches the sea, a river or a lake
 * (or gets caught in a hollow). The trace only reads {@link Landscape#column}, so it is the same
 * whichever chunk asks: every chunk a stream crosses carves its own part, and the stream is
 * continuous across chunk borders without generating the neighbours. Paths are cached (LRU).
 */
final class Streams {
    static final int MAX_STEPS = 200;
    private static final int REACH = MAX_STEPS / 16 + 1; // chunks around a chunk whose springs can reach it
    private static final int[] NONE = new int[0];
    private static final int[] DX = {-1, 1, 0, 0}, DZ = {0, 0, -1, 1};

    private final Landscape land;
    private final long seed;
    private final Map<Long, int[]> cache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, int[]> e) {
            return size() > 16384;
        }
    });

    Streams(Landscape land, long seed) {
        this.land = land;
        this.seed = seed;
    }

    private int hash(int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ 0x57E4L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (int) Math.floorMod(h, 1000L);
    }

    /**
     * The stream springing in chunk (cx, cz): {minX, minZ, maxX, maxZ, then x, z, y per step},
     * or an empty array when that chunk has no spring.
     */
    int[] path(int cx, int cz) {
        long key = World.key(cx, cz);
        int[] p = cache.get(key);
        if (p == null) {
            p = trace(cx, cz);
            cache.put(key, p);
        }
        return p;
    }

    private int[] trace(int cx, int cz) {
        int wx0 = cx << 4, wz0 = cz << 4;
        int r = hash(wx0, wz0);
        int x = wx0 + 2 + r % 12, z = wz0 + 2 + (r / 12) % 12;
        Landscape.Column col = land.column(x, z);
        int h = col.height();
        if (h < TerrainGenerator.SEA_LEVEL + 30 || col.biome() == Landscape.GLACIER) return NONE;
        if (hash(wx0 + 5, wz0 - 9) >= 120 + 500 * land.downfall(x, z)) return NONE; // wetter climate, more springs
        int[] out = new int[4 + 3 * MAX_STEPS];
        int n = 4, minX = x, minZ = z, maxX = x, maxZ = z;
        HashSet<Long> seen = new HashSet<>();
        int flat = 0;
        float accX = 0, accZ = 0;
        for (int step = 0; step < MAX_STEPS; step++) {
            out[n++] = x;
            out[n++] = z;
            out[n++] = h;
            seen.add(World.key(x, z));
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            if (h <= TerrainGenerator.SEA_LEVEL) break; // joined the sea, a river or a lake
            // Follow the smoothed slope (central differences 4 blocks apart), turned a little by a slow
            // meander, spread over x and z steps like a line drawing; fall back to any lower or level
            // neighbour when the preferred one climbs.
            float gx = land.column(x - 4, z).height() - land.column(x + 4, z).height();
            float gz = land.column(x, z - 4).height() - land.column(x, z + 4).height();
            float turn = (hash(x >> 3, z >> 3) - 500) / 1000f;
            float fx = gx - gz * turn, fz = gz + gx * turn;
            if (fx == 0 && fz == 0) fx = hash(x, z) < 500 ? 1 : -1;
            accX += Math.abs(fx);
            accZ += Math.abs(fz);
            int[] order = accX >= accZ ? new int[] {fx > 0 ? 1 : 0, fz > 0 ? 3 : 2} : new int[] {fz > 0 ? 3 : 2, fx > 0 ? 1 : 0};
            int bx = 0, bz = 0, bh = Integer.MAX_VALUE;
            for (int k = 0; k < 6 && bh > h; k++) {
                int i = k < 2 ? order[k] : k - 2;
                int nx = x + DX[i], nz = z + DZ[i];
                if (seen.contains(World.key(nx, nz))) continue;
                int nh = land.column(nx, nz).height();
                if (nh <= h) { bh = nh; bx = nx; bz = nz; }
            }
            if (bh <= h) {
                if (bx != x) accX = Math.max(0, accX - accZ - Math.abs(fx)); else accZ = Math.max(0, accZ - accX - Math.abs(fz));
            }
            if (bh > h) break; // a hollow: the stream ends in a little pool
            flat = bh == h ? flat + 1 : 0;
            if (flat > 24) break; // lost on a flat: soaks in
            x = bx;
            z = bz;
            h = bh;
        }
        if (n < 4 + 3 * 4) return NONE; // a spring that goes nowhere
        out[0] = minX; out[1] = minZ; out[2] = maxX; out[3] = maxZ;
        return java.util.Arrays.copyOf(out, n);
    }

    /**
     * Lays the water of every stream crossing this chunk as a contained bed of still sources: the
     * water sits one block into the ground, a drop is filled with a stack of sources up to the step
     * above (a cascade), and any open side gets a bank, so nothing spills or needs simulating.
     * Banks are worked out from the whole path, so both chunks of a border agree.
     */
    void carve(Chunk c) {
        int x0 = c.cx << 4, z0 = c.cz << 4;
        java.util.HashSet<Long> water = new java.util.HashSet<>();
        java.util.ArrayList<int[]> cells = new java.util.ArrayList<>();
        for (int dz = -REACH; dz <= REACH; dz++)
            for (int dx = -REACH; dx <= REACH; dx++) {
                int[] p = path(c.cx + dx, c.cz + dz);
                if (p.length == 0 || p[2] < x0 - 1 || p[0] > x0 + 16 || p[3] < z0 - 1 || p[1] > z0 + 16) continue;
                int prevTop = Integer.MIN_VALUE;
                for (int i = 4; i < p.length; i += 3) {
                    int x = p[i], z = p[i + 1], bed = p[i + 2] - 1;
                    int top = Math.max(bed, prevTop);
                    prevTop = bed;
                    if (x < x0 - 1 || x > x0 + 16 || z < z0 - 1 || z > z0 + 16) continue;
                    for (int y = Math.max(bed, TerrainGenerator.SEA_LEVEL + 1); y <= top; y++) {
                        if (water.add(LightEngine.pack(x, y, z))) cells.add(new int[] {x, y, z});
                    }
                }
            }
        for (int[] w : cells) {
            int x = w[0] - x0, y = w[1], z = w[2] - z0;
            if (x >= 0 && x < 16 && z >= 0 && z < 16) {
                c.set(x, y, z, Blocks.WATER);
                byte above = c.get(x, y + 1, z);
                if (!water.contains(LightEngine.pack(w[0], y + 1, w[2])) && !Blocks.isSolid(above) && !Blocks.isFluid(above))
                    c.set(x, y + 1, z, Blocks.AIR); // no plant standing in the stream
            }
            for (int i = 0; i < 4; i++) { // banks
                int nx = x + DX[i], nz = z + DZ[i];
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || water.contains(LightEngine.pack(w[0] + DX[i], y, w[2] + DZ[i]))) continue;
                byte b = c.get(nx, y, nz);
                if (Blocks.isSolid(b) || Blocks.isFluid(b)) continue;
                boolean open = !Blocks.isSolid(c.get(nx, y + 1, nz));
                c.set(nx, y, nz, y > TerrainGenerator.ROCK_LINE ? Blocks.STONE : open ? Blocks.GRASS : Blocks.DIRT);
            }
            if (x >= 0 && x < 16 && z >= 0 && z < 16 && !Blocks.isSolid(c.get(x, y - 1, z))
                && !water.contains(LightEngine.pack(w[0], y - 1, w[2])))
                c.set(x, y - 1, z, Blocks.DIRT); // a floor under the bed (caves, overhangs)
        }
    }
}
