package net.mynecraft.world;

public final class TerrainGenerator {
    public static final int SEA_LEVEL = 63;
    private final Noise noise, temperatureNoise, downfallNoise;
    private final long seed;

    public TerrainGenerator(long seed) {
        this.noise = new Noise(seed);
        this.temperatureNoise = new Noise(seed ^ 0x5DEECE66DL);
        this.downfallNoise = new Noise(seed * 31 + 17);
        this.seed = seed;
    }

    /** Climate in [0, 1], smooth over hundreds of blocks; drives grass and foliage colours. */
    public float temperature(int wx, int wz) {
        return clamp01(0.5f + temperatureNoise.fbm(wx / 600f, wz / 600f, 3) * 1.1f);
    }

    public float downfall(int wx, int wz) {
        return clamp01(0.5f + downfallNoise.fbm(wx / 500f, wz / 500f, 3) * 1.1f);
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    public int height(int wx, int wz) {
        float n = noise.fbm(wx / 128f, wz / 128f, 5);
        int h = SEA_LEVEL + 10 + (int) (n * 45);
        return Math.max(Chunk.MIN_Y + 1, Math.min(Chunk.MAX_Y - 12, h));
    }

    /** Deterministic per-column random in [0, 1000). */
    private int hash(int wx, int wz) {
        long h = seed ^ (wx * 0x9E3779B97F4A7C15L) ^ (wz * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (int) Math.floorMod(h, 1000L);
    }

    public Chunk generate(int cx, int cz) {
        Chunk c = new Chunk(cx, cz);
        int[] heights = new int[256];
        int minH = Integer.MAX_VALUE;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = height((cx << 4) + x, (cz << 4) + z);
                heights[z * 16 + x] = h;
                minH = Math.min(minH, h);
            }
        }
        // Sections entirely below every column's stone line are filled in one go.
        int stoneFrom = Chunk.MIN_Y + 1;
        for (int sy = 0; Chunk.sectionMinY(sy) + 15 < minH - 3; sy++) {
            c.fillSection(sy, Blocks.STONE);
            stoneFrom = Chunk.sectionMinY(sy) + 16;
        }
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = heights[z * 16 + x];
                boolean beach = h <= SEA_LEVEL + 1;
                byte top = h < SEA_LEVEL - 4 ? Blocks.GRAVEL : beach ? Blocks.SAND : Blocks.GRASS;
                byte under = beach ? top : Blocks.DIRT;
                c.set(x, Chunk.MIN_Y, z, Blocks.BEDROCK);
                for (int y = stoneFrom; y < h - 3; y++) c.set(x, y, z, Blocks.STONE);
                for (int y = Math.max(Chunk.MIN_Y + 1, h - 3); y < h; y++) c.set(x, y, z, under);
                c.set(x, h, z, top);
                for (int y = h + 1; y <= SEA_LEVEL; y++) c.set(x, y, z, Blocks.WATER);
            }
        }
        // Decorations stay inside the chunk so generation never depends on neighbours.
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = heights[z * 16 + x];
                if (c.get(x, h, z) != Blocks.GRASS) continue;
                int r = hash((cx << 4) + x, (cz << 4) + z);
                if (r < 8 && x >= 2 && x <= 13 && z >= 2 && z <= 13) tree(c, x, h + 1, z, 4 + r % 3);
                else if (r < 150 && c.get(x, h + 1, z) == Blocks.AIR) c.set(x, h + 1, z, Blocks.SHORT_GRASS);
                else if (r < 165 && c.get(x, h + 1, z) == Blocks.AIR) c.set(x, h + 1, z, Blocks.POPPY);
            }
        }
        c.compact();
        LightEngine.initChunk(c);
        return c;
    }

    private static void tree(Chunk c, int x, int y, int z, int trunk) {
        int top = y + trunk;
        for (int ly = top - 2; ly <= top + 1; ly++) {
            int r = ly >= top ? 1 : 2;
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) continue; // round the corners
                    if (ly == top + 1 && Math.abs(dx) + Math.abs(dz) > 1) continue;
                    byte cur = c.get(x + dx, ly, z + dz);
                    if (cur == Blocks.AIR || cur == Blocks.SHORT_GRASS || cur == Blocks.POPPY) {
                        c.set(x + dx, ly, z + dz, Blocks.OAK_LEAVES);
                    }
                }
        }
        for (int ty = y; ty < top; ty++) c.set(x, ty, z, Blocks.OAK_LOG);
        c.set(x, y - 1, z, Blocks.DIRT);
    }
}
