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
            c.fillSection(sy, Chunk.sectionMinY(sy) < DEEPSLATE_TOP ? Blocks.DEEPSLATE : Blocks.STONE);
            stoneFrom = Chunk.sectionMinY(sy) + 16;
        }
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = heights[z * 16 + x];
                boolean beach = h <= SEA_LEVEL + 1;
                byte top = h < SEA_LEVEL - 4 ? Blocks.GRAVEL : beach ? Blocks.SAND : Blocks.GRASS;
                byte under = beach ? top : Blocks.DIRT;
                c.set(x, Chunk.MIN_Y, z, Blocks.BEDROCK);
                for (int y = stoneFrom; y < h - 3; y++) c.set(x, y, z, y < DEEPSLATE_TOP ? Blocks.DEEPSLATE : Blocks.STONE);
                for (int y = Math.max(Chunk.MIN_Y + 1, h - 3); y < h; y++) c.set(x, y, z, under);
                c.set(x, h, z, top);
                for (int y = h + 1; y <= SEA_LEVEL; y++) c.set(x, y, z, Blocks.WATER);
            }
        }
        ores(c, heights);
        // Decorations stay inside the chunk so generation never depends on neighbours.
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = heights[z * 16 + x];
                if (c.get(x, h, z) != Blocks.GRASS) continue;
                int r = hash((cx << 4) + x, (cz << 4) + z);
                if (r < 8 && x >= 2 && x <= 13 && z >= 2 && z <= 13) {
                    int wx = (cx << 4) + x, wz = (cz << 4) + z;
                    float t = temperature(wx, wz), d = downfall(wx, wz);
                    if (t < 0.35f) spruce(c, x, h + 1, z, 6 + r % 3);
                    else if (d > 0.55f) tree(c, x, h + 1, z, 5 + r % 2, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
                    else tree(c, x, h + 1, z, 4 + r % 3, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
                }
                else if (r < 150 && c.get(x, h + 1, z) == Blocks.AIR) c.set(x, h + 1, z, Blocks.SHORT_GRASS);
                else if (r < 165 && c.get(x, h + 1, z) == Blocks.AIR) c.set(x, h + 1, z, Blocks.POPPY);
            }
        }
        c.compact();
        LightEngine.initChunk(c);
        return c;
    }

    /** Deepslate replaces stone below this height, as in 1.18+. */
    public static final int DEEPSLATE_TOP = 0;

    /** Vein kind: block, attempts per chunk, blob radius, height range. */
    private record Vein(byte block, int count, double radius, int minY, int maxY) {}

    private static final Vein[] VEINS = {
        new Vein(Blocks.GRANITE, 3, 3.5, 0, 60), new Vein(Blocks.DIORITE, 3, 3.5, 0, 60),
        new Vein(Blocks.ANDESITE, 3, 3.5, 0, 60), new Vein(Blocks.COAL_ORE, 18, 1.6, 0, 128),
        new Vein(Blocks.IRON_ORE, 10, 1.3, -24, 56), new Vein(Blocks.GOLD_ORE, 4, 1.2, -64, 32),
        new Vein(Blocks.DIAMOND_ORE, 2, 1.1, -64, 16)};

    /**
     * Ore and stone-variant blobs replacing stone or deepslate, from a per-chunk random so the result
     * only depends on the seed and chunk position. Blobs are clipped to the chunk.
     */
    private void ores(Chunk c, int[] heights) {
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(seed ^ (c.cx * 341873128712L) ^ (c.cz * 132897987541L));
        for (Vein v : VEINS) {
            for (int i = 0; i < v.count; i++) {
                int x = rnd.nextInt(16), z = rnd.nextInt(16);
                int y = v.minY + rnd.nextInt(v.maxY - v.minY + 1);
                if (y >= heights[z * 16 + x] - 3) continue; // stay underground
                int r = (int) Math.ceil(v.radius);
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++)
                        for (int dx = -r; dx <= r; dx++) {
                            int bx = x + dx, by = y + dy, bz = z + dz;
                            if (bx < 0 || bx > 15 || bz < 0 || bz > 15 || by <= Chunk.MIN_Y || by >= Chunk.MAX_Y) continue;
                            if (dx * dx + dy * dy + dz * dz > v.radius * v.radius * (0.6 + 0.4 * rnd.nextDouble())) continue;
                            byte cur = c.get(bx, by, bz);
                            if (cur == Blocks.STONE || cur == Blocks.DEEPSLATE) c.set(bx, by, bz, v.block);
                        }
            }
        }
    }

    /** Conical spruce: rings of leaves shrinking towards the top. */
    private static void spruce(Chunk c, int x, int y, int z, int trunk) {
        int top = y + trunk;
        for (int ly = y + 2; ly <= top + 1; ly++) {
            int r = ly > top - 1 ? (ly == top + 1 ? 0 : 1) : ((top - ly) % 2 == 0 ? 1 : 2);
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    if (c.get(x + dx, ly, z + dz) == Blocks.AIR) c.set(x + dx, ly, z + dz, Blocks.SPRUCE_LEAVES);
                }
        }
        for (int ty = y; ty < top; ty++) c.set(x, ty, z, Blocks.SPRUCE_LOG);
        c.set(x, y - 1, z, Blocks.DIRT);
    }

    private static void tree(Chunk c, int x, int y, int z, int trunk, byte log, byte leaves) {
        int top = y + trunk;
        for (int ly = top - 2; ly <= top + 1; ly++) {
            int r = ly >= top ? 1 : 2;
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) continue; // round the corners
                    if (ly == top + 1 && Math.abs(dx) + Math.abs(dz) > 1) continue;
                    byte cur = c.get(x + dx, ly, z + dz);
                    if (cur == Blocks.AIR || cur == Blocks.SHORT_GRASS || cur == Blocks.POPPY) {
                        c.set(x + dx, ly, z + dz, leaves);
                    }
                }
        }
        for (int ty = y; ty < top; ty++) c.set(x, ty, z, log);
        c.set(x, y - 1, z, Blocks.DIRT);
    }
}
