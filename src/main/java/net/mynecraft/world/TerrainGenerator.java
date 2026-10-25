package net.mynecraft.world;

public final class TerrainGenerator {
    public static final int SEA_LEVEL = 63;
    /** Above this height the surface is bare rock. */
    public static final int ROCK_LINE = 135;
    private final Noise tunnelA, tunnelB, cavern;
    private final Landscape landscape;
    private final Streams streams;
    private final long seed;

    public TerrainGenerator(long seed) {
        this.tunnelA = new Noise(seed + 101);
        this.tunnelB = new Noise(seed + 202);
        this.cavern = new Noise(seed + 303);
        this.landscape = new Landscape(seed);
        this.streams = new Streams(landscape, seed);
        this.seed = seed;
    }

    /** Climate in [0, 1], smooth over hundreds of blocks; drives grass and foliage colours. */
    public float temperature(int wx, int wz) {
        return landscape.temperature(wx, wz);
    }

    public float downfall(int wx, int wz) {
        return landscape.downfall(wx, wz);
    }

    public int height(int wx, int wz) {
        return landscape.column(wx, wz).height();
    }

    public Landscape.Column column(int wx, int wz) {
        return landscape.column(wx, wz);
    }

    /** A dry, non-snowy land column near (x, z) to spawn on, searched in a growing square. */
    public int[] findSpawn(int x, int z) {
        for (int r = 0; r < 4096; r += 16) {
            for (int dz = -r; dz <= r; dz += 16)
                for (int dx = -r; dx <= r; dx += 16) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    Landscape.Column c = landscape.column(x + dx, z + dz);
                    byte b = c.biome();
                    if (c.height() > SEA_LEVEL + 1 && c.height() < ROCK_LINE && b != Landscape.BEACH
                        && b != Landscape.GLACIER && b != Landscape.SNOWY_PEAKS) {
                        return new int[] {x + dx, c.height() + 1, z + dz};
                    }
                }
        }
        return new int[] {x, height(x, z) + 1, z};
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
        Landscape.Column[] cols = landscape.chunkColumns(cx, cz);
        int[] heights = new int[256];
        byte[] biomes = new byte[256];
        int minH = Integer.MAX_VALUE;
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                Landscape.Column col = cols[(z + 1) * 18 + x + 1];
                heights[z * 16 + x] = col.height();
                biomes[z * 16 + x] = col.biome();
                minH = Math.min(minH, col.height());
            }
        // Sections entirely below every column's stone line are filled in one go.
        int stoneFrom = Chunk.MIN_Y + 1;
        for (int sy = 0; Chunk.sectionMinY(sy) + 15 < minH - 4; sy++) {
            c.fillSection(sy, Chunk.sectionMinY(sy) < DEEPSLATE_TOP ? Blocks.DEEPSLATE : Blocks.STONE);
            stoneFrom = Chunk.sectionMinY(sy) + 16;
        }
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = heights[z * 16 + x];
                byte biome = biomes[z * 16 + x];
                // Steep ground shows bare rock (cliffs, plateau edges, ravine walls).
                int steep = 0;
                for (int[] o : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                    steep = Math.max(steep, h - cols[(z + 1 + o[1]) * 18 + x + 1 + o[0]].height());
                }
                byte[] surface = surface(biome, h, steep > 3);
                c.set(x, Chunk.MIN_Y, z, Blocks.BEDROCK);
                for (int y = stoneFrom; y < h - 3; y++) c.set(x, y, z, y < DEEPSLATE_TOP ? Blocks.DEEPSLATE : Blocks.STONE);
                for (int y = Math.max(Chunk.MIN_Y + 1, h - 3); y < h; y++) c.set(x, y, z, surface[1]);
                c.set(x, h, z, surface[0]);
                if (biome == Landscape.GLACIER) { // an ice cap on top of the rock
                    int cap = 3 + hash((cx << 4) + x, (cz << 4) + z) % 4;
                    for (int y = h - cap + 1; y <= h; y++) c.set(x, y, z, Blocks.PACKED_ICE);
                }
                boolean frozen = biome == Landscape.FROZEN_OCEAN || biome == Landscape.FROZEN_RIVER;
                for (int y = h + 1; y <= SEA_LEVEL; y++) c.set(x, y, z, frozen && y == SEA_LEVEL ? Blocks.ICE : Blocks.WATER);
            }
        }
        carveRavines(c, cols, heights);
        carveCaves(c, heights);
        ores(c, heights);
        decorate(c, heights, biomes);
        streams.carve(c);
        c.compact();
        LightEngine.initChunk(c);
        return c;
    }

    /** Top block and the 3 blocks below it for a biome. */
    private static byte[] surface(byte biome, int h, boolean steep) {
        switch (biome) {
            case Landscape.OCEAN, Landscape.FROZEN_OCEAN:
                return h < SEA_LEVEL - 12 ? new byte[] {Blocks.GRAVEL, Blocks.GRAVEL} : new byte[] {Blocks.SAND, Blocks.SAND};
            case Landscape.RIVER, Landscape.FROZEN_RIVER, Landscape.LAKE:
                return new byte[] {Blocks.SAND, Blocks.DIRT};
            case Landscape.BEACH:
                return new byte[] {Blocks.SAND, Blocks.SAND};
            case Landscape.STONY_PEAKS:
                return new byte[] {Blocks.STONE, Blocks.STONE};
            case Landscape.SNOWY_PEAKS:
                return new byte[] {steep ? Blocks.STONE : Blocks.SNOW_BLOCK, Blocks.STONE};
            case Landscape.GLACIER:
                return new byte[] {Blocks.PACKED_ICE, Blocks.STONE};
            case Landscape.SNOWY_PLAINS, Landscape.SNOWY_TAIGA:
                return new byte[] {steep ? Blocks.STONE : Blocks.SNOW_BLOCK, Blocks.DIRT};
            default:
                return steep ? new byte[] {Blocks.STONE, Blocks.STONE} : new byte[] {Blocks.GRASS, Blocks.DIRT};
        }
    }

    /** Ravines: narrow vertical cuts, carved per column down from the surface. */
    private void carveRavines(Chunk c, Landscape.Column[] cols, int[] heights) {
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                Landscape.Column col = cols[(z + 1) * 18 + x + 1];
                int depth = landscape.ravineDepth((c.cx << 4) + x, (c.cz << 4) + z, col);
                if (depth <= 0) continue;
                int h = heights[z * 16 + x];
                for (int y = h; y > h - depth && y > Chunk.MIN_Y + 5; y--) c.set(x, y, z, Blocks.AIR);
                heights[z * 16 + x] = h - depth; // decorations go on the ravine floor (none: no grass there)
            }
    }

    /** Trees and plants, with densities and species per biome. Trees stay inside the chunk. */
    private void decorate(Chunk c, int[] heights, byte[] biomes) {
        int cx = c.cx, cz = c.cz;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = heights[z * 16 + x];
                byte ground = c.get(x, h, z);
                if (ground != Blocks.GRASS && ground != Blocks.SNOW_BLOCK) continue;
                if (c.get(x, h + 1, z) != Blocks.AIR) continue;
                byte biome = biomes[z * 16 + x];
                int r = hash((cx << 4) + x, (cz << 4) + z);
                int trees = switch (biome) {
                    case Landscape.DARK_FOREST -> 140;
                    case Landscape.FOREST, Landscape.BIRCH_FOREST -> 70;
                    case Landscape.TAIGA -> 60;
                    case Landscape.SNOWY_TAIGA -> 35;
                    case Landscape.PLATEAU -> 12;
                    case Landscape.PLAINS, Landscape.DRY_PLAINS -> 4;
                    case Landscape.SNOWY_PLAINS -> 3;
                    default -> 0;
                };
                if (r < trees && x >= 3 && x <= 12 && z >= 3 && z <= 12) {
                    switch (biome) {
                        case Landscape.TAIGA, Landscape.SNOWY_TAIGA, Landscape.SNOWY_PLAINS -> spruce(c, x, h + 1, z, 6 + r % 4);
                        case Landscape.BIRCH_FOREST -> tree(c, x, h + 1, z, 5 + r % 3, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
                        case Landscape.DARK_FOREST -> {
                            if (r % 3 == 0) bigOak(c, x, h + 1, z, 7 + r % 3);
                            else tree(c, x, h + 1, z, 5 + r % 2, r % 3 == 1 ? Blocks.OAK_LOG : Blocks.BIRCH_LOG,
                                r % 3 == 1 ? Blocks.OAK_LEAVES : Blocks.BIRCH_LEAVES);
                        }
                        case Landscape.FOREST -> {
                            if (r % 5 == 0) tree(c, x, h + 1, z, 5, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
                            else tree(c, x, h + 1, z, 4 + r % 3, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
                        }
                        default -> tree(c, x, h + 1, z, 4 + r % 3, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
                    }
                    continue;
                }
                if (ground != Blocks.GRASS) continue;
                int grass = biome == Landscape.PLAINS || biome == Landscape.DRY_PLAINS ? 250 : 120;
                if (r >= 300 && r < 300 + grass) c.set(x, h + 1, z, Blocks.SHORT_GRASS);
                else if (r >= 700 && r < (biome == Landscape.PLAINS ? 730 : 712)) c.set(x, h + 1, z, Blocks.POPPY);
            }
        }
    }

    /** Big oak: tall trunk and a wide two-layer crown (dense forests). */
    private static void bigOak(Chunk c, int x, int y, int z, int trunk) {
        int top = y + trunk;
        for (int ly = top - 3; ly <= top + 1; ly++) {
            int r = ly >= top ? 2 : 3;
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (dx * dx + dz * dz > r * r + 1) continue;
                    if (ly == top + 1 && dx * dx + dz * dz > 2) continue;
                    if (c.get(x + dx, ly, z + dz) == Blocks.AIR) c.set(x + dx, ly, z + dz, Blocks.OAK_LEAVES);
                }
        }
        for (int ty = y; ty < top; ty++) c.set(x, ty, z, Blocks.OAK_LOG);
        c.set(x, y - 1, z, Blocks.DIRT);
    }

    // ---- caves ----

    private static final int CAVE_FLOOR = Chunk.MIN_Y + 5;   // keep the bedrock floor intact
    private static final int GRID_XZ = 4, GRID_Y = 8;        // noise sample spacing, interpolated between

    /**
     * Carves tunnels ("spaghetti": where two thin noise fields are both near zero) and, deeper down,
     * caverns ("cheese": where a third field is high). Noise is sampled on a coarse 4x8x4 grid and
     * trilinearly interpolated, which is ~30 times cheaper than evaluating it per block. Columns
     * under water or beaches are left alone near their surface: there is no fluid flow to fill
     * what a cave would expose.
     */
    private void carveCaves(Chunk c, int[] heights) {
        int maxH = Chunk.MIN_Y;
        for (int h : heights) maxH = Math.max(maxH, h);
        int ny = (maxH - CAVE_FLOOR) / GRID_Y + 2;
        int n = 16 / GRID_XZ + 1;
        float[] a = new float[n * n * ny], b = new float[n * n * ny], k = new float[n * n * ny];
        int bx = c.cx << 4, bz = c.cz << 4;
        for (int iy = 0; iy < ny; iy++) {
            int y = CAVE_FLOOR + iy * GRID_Y;
            for (int iz = 0; iz < n; iz++)
                for (int ix = 0; ix < n; ix++) {
                    float wx = bx + ix * GRID_XZ, wz = bz + iz * GRID_XZ;
                    int i = (iy * n + iz) * n + ix;
                    a[i] = tunnelA.noise3(wx / 48f, y / 32f, wz / 48f);
                    b[i] = tunnelB.noise3(wx / 48f, y / 32f, wz / 48f);
                    k[i] = cavern.noise3(wx / 80f, y / 40f, wz / 80f);
                }
        }
        // Per column: interpolate the grid in x/z once per level, then only along y per block.
        float[] colA = new float[ny], colB = new float[ny], colK = new float[ny];
        for (int z = 0; z < 16; z++) {
            int iz = z / GRID_XZ;
            float fz = (z % GRID_XZ) / (float) GRID_XZ;
            for (int x = 0; x < 16; x++) {
                int ix = x / GRID_XZ;
                float fx = (x % GRID_XZ) / (float) GRID_XZ;
                int h = heights[z * 16 + x];
                boolean wet = h <= SEA_LEVEL + 2;
                int top = wet ? Math.min(h - 8, SEA_LEVEL - 10) : h;
                if (top < CAVE_FLOOR) continue;
                int levels = Math.min(ny, (top - CAVE_FLOOR) / GRID_Y + 2);
                for (int iy = 0; iy < levels; iy++) {
                    int i = (iy * n + iz) * n + ix;
                    colA[iy] = bilinear(a, i, n, fx, fz);
                    colB[iy] = bilinear(b, i, n, fx, fz);
                    colK[iy] = bilinear(k, i, n, fx, fz);
                }
                for (int y = CAVE_FLOOR; y <= top; y++) {
                    int iy = (y - CAVE_FLOOR) / GRID_Y;
                    float fy = ((y - CAVE_FLOOR) % GRID_Y) / (float) GRID_Y;
                    float ta = colA[iy] + (colA[iy + 1] - colA[iy]) * fy;
                    boolean carve = false;
                    if (ta * ta < TUNNEL) { // cheap reject before the second field
                        float tb = colB[iy] + (colB[iy + 1] - colB[iy]) * fy;
                        carve = ta * ta + tb * tb < TUNNEL;
                    }
                    if (!carve && y < 20) {
                        carve = colK[iy] + (colK[iy + 1] - colK[iy]) * fy > 0.5f + Math.max(0, y) * 0.004f;
                    }
                    if (carve) c.set(x, y, z, y <= LAVA_LEVEL ? Blocks.LAVA : Blocks.AIR);
                }
            }
        }
    }

    private static final float TUNNEL = 0.0025f; // squared radius of a tunnel in noise units
    /** Cave air at or below this height is lava, as in vanilla (y -55 and below). */
    public static final int LAVA_LEVEL = -55;

    private static float bilinear(float[] v, int i, int n, float fx, float fz) {
        float x0 = v[i] + (v[i + 1] - v[i]) * fx;
        float x1 = v[i + n] + (v[i + n + 1] - v[i + n]) * fx;
        return x0 + (x1 - x0) * fz;
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
