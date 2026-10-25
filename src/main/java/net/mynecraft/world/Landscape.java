package net.mynecraft.world;

/**
 * Terrain shape and biomes, in the spirit of vanilla 1.18+ but much simpler. Large, slow fields
 * decide the kind of land; small ones add relief:
 * <ul>
 *   <li>continentalness: deep ocean, coast, then land rising gently inland;</li>
 *   <li>erosion: high = flat plains (the "stable" places), low = mountains shaped by ridged noise;</li>
 *   <li>plateaus: flat-topped highlands with steep edges where a plateau field is high;</li>
 *   <li>rivers: thin lines of a noise field near zero, carved below sea level so they reach the sea;</li>
 *   <li>lakes: basins in the lowlands at sea level, with a guaranteed rim;</li>
 *   <li>ravines: narrow, deep cuts (applied by the generator, see {@link #ravineDepth}).</li>
 * </ul>
 * The slow fields are sampled on a 4-block grid aligned to world coordinates and interpolated, so
 * a column gets the same height whether it is generated with its chunk or asked alone (spawn
 * search). Biomes come from temperature and humidity, cooled by altitude.
 */
public final class Landscape {
    public static final int SEA = TerrainGenerator.SEA_LEVEL;

    // Biomes (also used to pick surface blocks, trees and plants).
    public static final byte OCEAN = 0, FROZEN_OCEAN = 1, BEACH = 2, RIVER = 3, FROZEN_RIVER = 4, LAKE = 5,
        PLAINS = 6, SNOWY_PLAINS = 7, FOREST = 8, BIRCH_FOREST = 9, DARK_FOREST = 10, TAIGA = 11, SNOWY_TAIGA = 12,
        STONY_PEAKS = 13, SNOWY_PEAKS = 14, GLACIER = 15, PLATEAU = 16, DRY_PLAINS = 17;
    public static final String[] BIOME_NAMES = {"océan", "océan gelé", "plage", "rivière", "rivière gelée", "lac",
        "plaine", "plaine enneigée", "forêt", "forêt de bouleaux", "forêt sombre", "taïga", "taïga enneigée",
        "pics rocheux", "pics enneigés", "glacier", "plateau", "plaine sèche"};

    /** One column: surface height, biome, and the water level above it (or below it if dry). */
    public record Column(int height, byte biome, int water, float river, float temperature) {}

    private static final int GRID = 4;
    private final Hydrology hydrology;
    private final Noise continental, erosion, ridges, plateau, river, lake, detail, roughness, ravine, ravineMask,
        temperature, downfall, forestKind;

    public Landscape(long seed) {
        continental = new Noise(seed + 11);
        erosion = new Noise(seed + 22);
        ridges = new Noise(seed + 505);
        plateau = new Noise(seed + 33);
        river = new Noise(seed + 44);
        lake = new Noise(seed + 55);
        detail = new Noise(seed);
        roughness = new Noise(seed + 66);
        ravine = new Noise(seed + 77);
        ravineMask = new Noise(seed + 88);
        temperature = new Noise(seed ^ 0x5DEECE66DL);
        downfall = new Noise(seed * 31 + 17);
        forestKind = new Noise(seed + 99);
        hydrology = new Hydrology(new Hydrology.Ground() {
            public float height(int wx, int wz) { return baseHeight(wx, wz); }

            public float downfall(int wx, int wz) { return Landscape.this.downfall(wx, wz); }
        }, seed, SEA);
    }

    public Hydrology hydrology() {
        return hydrology;
    }

    public float temperature(int wx, int wz) {
        return clamp01(0.5f + temperature.fbm(wx / 600f, wz / 600f, 3) * 1.1f);
    }

    public float downfall(int wx, int wz) {
        return clamp01(0.5f + downfall.fbm(wx / 500f, wz / 500f, 3) * 1.1f);
    }

    static float smoothstep(float e0, float e1, float v) {
        float t = clamp01((v - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    // ---- slow fields on the grid ----

    private static final int F_CONT = 0, F_EROS = 1, F_RIDGE = 2, F_PLAT = 3, F_RIVER = 4, F_LAKE = 5, FIELDS = 6;

    /** The slow fields at a grid point (world coordinates, multiples of GRID). */
    private void sample(int wx, int wz, float[] out, int o) {
        out[o + F_CONT] = continental.fbm(wx / 1400f, wz / 1400f, 4) * 1.6f;
        out[o + F_EROS] = erosion.fbm(wx / 650f, wz / 650f, 3) * 1.5f;
        out[o + F_RIDGE] = 1 - Math.abs(ridges.fbm(wx / 220f, wz / 220f, 4));
        out[o + F_PLAT] = plateau.fbm(wx / 420f, wz / 420f, 3) * 1.5f;
        out[o + F_RIVER] = Math.abs(river.fbm(wx / 520f, wz / 520f, 3));
        out[o + F_LAKE] = lake.fbm(wx / 160f, wz / 160f, 2) * 1.5f;
    }

    /** Fields interpolated at a column from the 4 surrounding grid points. */
    private float[] fields(int wx, int wz) {
        int gx = Math.floorDiv(wx, GRID) * GRID, gz = Math.floorDiv(wz, GRID) * GRID;
        float[] g = new float[FIELDS * 4];
        sample(gx, gz, g, 0);
        sample(gx + GRID, gz, g, FIELDS);
        sample(gx, gz + GRID, g, 2 * FIELDS);
        sample(gx + GRID, gz + GRID, g, 3 * FIELDS);
        return interpolate(g, (wx - gx) / (float) GRID, (wz - gz) / (float) GRID);
    }

    private static float[] interpolate(float[] g, float fx, float fz) {
        float[] f = new float[FIELDS];
        for (int i = 0; i < FIELDS; i++) {
            float a = g[i] + (g[FIELDS + i] - g[i]) * fx, b = g[2 * FIELDS + i] + (g[3 * FIELDS + i] - g[2 * FIELDS + i]) * fx;
            f[i] = a + (b - a) * fz;
        }
        return f;
    }

    /** How far inland a point is: below about -0.25 open sea, around 0 the coast, high far inland. */
    public float continentalness(int wx, int wz) {
        return fields(wx, wz)[F_CONT];
    }

    // ---- columns ----

    public Column column(int wx, int wz) {
        return column(wx, wz, fields(wx, wz));
    }

    /** Columns of a chunk plus a 1-block halo (18 x 18, index (z + 1) * 18 + x + 1), sampling the grid once. */
    Column[] chunkColumns(int cx, int cz) {
        int x0 = (cx << 4) - GRID, z0 = (cz << 4) - GRID, n = 16 / GRID + 3; // grid points from -4 to +20
        float[] grid = new float[n * n * FIELDS];
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++) sample(x0 + i * GRID, z0 + j * GRID, grid, (j * n + i) * FIELDS);
        Column[] out = new Column[18 * 18];
        float[] g = new float[FIELDS * 4];
        for (int z = -1; z <= 16; z++) {
            for (int x = -1; x <= 16; x++) {
                int wx = (cx << 4) + x, wz = (cz << 4) + z;
                int i = Math.floorDiv(wx - x0, GRID), j = Math.floorDiv(wz - z0, GRID);
                for (int k = 0; k < 4; k++) {
                    int ii = i + (k & 1), jj = j + (k >> 1);
                    System.arraycopy(grid, (jj * n + ii) * FIELDS, g, k * FIELDS, FIELDS);
                }
                float fx = Math.floorMod(wx, GRID) / (float) GRID, fz = Math.floorMod(wz, GRID) / (float) GRID;
                out[(z + 1) * 18 + x + 1] = column(wx, wz, interpolate(g, fx, fz));
            }
        }
        // Banks: dry ground next to a river is raised to its water level, so the water stays in its
        // bed. Each column looks at its 4 neighbours only, so chunks on both sides of a border agree
        // (the halo columns are left as they are: they are only read for slopes).
        Column[] banked = out.clone();
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                Column col = out[(z + 1) * 18 + x + 1];
                if (col.river() > 0 || col.height() < SEA) continue;
                int need = Integer.MIN_VALUE, low = Integer.MAX_VALUE, wet = 0, sides = 0;
                for (int k = 0; k < 4; k++) {
                    int nx = x + (k == 0 ? -1 : k == 1 ? 1 : 0), nz = z + (k == 2 ? -1 : k == 3 ? 1 : 0);
                    Column nb = out[(nz + 1) * 18 + nx + 1];
                    if (nb.river() > 0) {
                        need = Math.max(need, nb.water());
                        low = Math.min(low, nb.water());
                        wet++;
                        sides |= 1 << k;
                    }
                }
                if (wet == 0) continue;
                int i = (z + 1) * 18 + x + 1;
                byte river = col.temperature() < 0.22f ? FROZEN_RIVER : RIVER;
                boolean across = (sides & 3) == 3 || (sides & 12) == 12; // water on two opposite sides
                if ((wet >= 3 || across) && col.height() <= need + 3) {
                    // A post or a sliver of land standing in the water: part of the bed, at the lower
                    // level around it; any higher water runs down into it (rapids).
                    banked[i] = new Column(Math.min(col.height(), low - 1), river, low, 1, col.temperature());
                } else if (col.height() < need && col.height() >= need - 2) {
                    banked[i] = new Column(col.height(), river, need, 1, col.temperature()); // a shallow hollow: flooded
                } else if (col.height() < need + 1) {
                    // A bank one block above the water: what spills over a step in the river runs on
                    // down the bed instead of over the land (and a swimmer can still climb out).
                    banked[i] = new Column(need + 1, col.biome(), col.water(), col.river(), col.temperature());
                }
            }
        return banked;
    }

    private Column column(int wx, int wz, float[] f) {
        return column(wx, wz, f, false);
    }

    /** Terrain height before the rivers are carved (what the droplets roll on). */
    float baseHeight(int wx, int wz) {
        return column(wx, wz, fields(wx, wz), true).river();
    }

    private Column column(int wx, int wz, float[] f, boolean base) {
        float c = f[F_CONT], e = f[F_EROS];
        float flat = smoothstep(-0.15f, 0.45f, e);              // 1 = plains
        float mountain = 1 - smoothstep(-0.55f, -0.05f, e);     // 1 = mountain range
        float inland = smoothstep(-0.25f, -0.05f, c);           // 0 at sea, 1 inland

        // Base: ocean floor, coast, then land rising gently inland.
        float h;
        if (c < -0.25f) {
            h = SEA - 5 - 25 * smoothstep(-0.25f, -0.75f, c);
        } else {
            float land = SEA + 2 + 28 * smoothstep(-0.05f, 0.7f, c);
            h = SEA - 5 + (land - (SEA - 5)) * inland;
        }
        // Relief: gentle on plains, hilly elsewhere; rough mountains where erosion is low.
        float hills = detail.fbm(wx / 110f, wz / 110f, 4) * (3 + 15 * (1 - flat)) * inland;
        float rough = roughness.fbm(wx / 40f, wz / 40f, 2) * 3 * (1 - flat) * inland;
        float r = f[F_RIDGE];
        h += hills + rough + mountain * r * r * 115 * inland;

        // Plateaus: highlands with a flat top and steep edges (narrow smoothstep = cliffs).
        float plat = smoothstep(0.33f, 0.37f, f[F_PLAT]) * inland * (1 - mountain);
        if (plat > 0) h = h + (SEA + 48 + hills * 0.15f - h) * plat;

        // Lakes: lowland basins at sea level; the rim never drops below the water.
        float lowland = 1 - smoothstep(SEA + 10f, SEA + 20f, h);
        float lakeMask = smoothstep(0.32f, 0.45f, f[F_LAKE]) * inland * lowland * (0.4f + 0.6f * flat) * (1 - plat);
        boolean lake = false;
        if (lakeMask > 0.35f) {
            h = SEA - 1 - 6 * (lakeMask - 0.35f) / 0.65f;
            lake = true;
        } else if (lakeMask > 0) {
            h = Math.max(h, SEA + 1);
        }

        if (base) return new Column(0, (byte) 0, 0, h, 0); // the height before rivers, for the hydrology

        // Rivers carved by the hydrology: the ground is dug or filled where the water rolled, and
        // where it gathered into a stream the bed is cut under a still water surface.
        int water = SEA;
        float riverMask = 0;
        if (hydrology != null && !lake && h > SEA) {
            h += hydrology.carve(wx, wz);
            float[] w = hydrology.water(wx, wz);
            if (w != null && w[0] > SEA) {
                water = Math.round(w[0]);
                h = Math.min(h, water - w[1]);
                riverMask = 1;
            } else {
                h = Math.min(h, hydrology.bank(wx, wz)); // valley sides you can climb out of
                if (h < SEA + 1) h = SEA + 1; // carved below the sea without a river: keep it dry land
            }
        }

        int height = Math.max(Chunk.MIN_Y + 1, Math.min(Chunk.MAX_Y - 12, Math.round(h)));
        float t = temperature(wx, wz) - Math.max(0, height - 100) / 160f; // colder with altitude
        byte biome = riverMask > 0 ? (t < 0.22f ? FROZEN_RIVER : RIVER)
            : biome(wx, wz, height, t, downfall(wx, wz), c, 0, lake, plat, mountain);
        return new Column(height, biome, water, riverMask, t);
    }

    private byte biome(int wx, int wz, int h, float t, float d, float c, float river, boolean lake, float plat,
                       float mountain) {
        boolean cold = t < 0.22f;
        if (h < SEA) {
            if (lake) return cold ? FROZEN_RIVER : LAKE;
            if (river > 0.5f) return cold ? FROZEN_RIVER : RIVER;
            return cold ? FROZEN_OCEAN : OCEAN;
        }
        if (h <= SEA + 2 && c < -0.12f && river < 0.3f) return BEACH; // only on the real coast
        if (h > TerrainGenerator.ROCK_LINE) {
            if (t < 0.15f && mountain > 0.4f) return GLACIER;
            return t < 0.3f || h > 175 ? SNOWY_PEAKS : STONY_PEAKS;
        }
        if (plat > 0.6f && t < 0.18f) return GLACIER;
        if (plat > 0.6f) return PLATEAU;
        if (cold) return d > 0.5f ? SNOWY_TAIGA : SNOWY_PLAINS;
        if (t < 0.4f) return d > 0.35f ? TAIGA : PLAINS;
        if (t > 0.75f && d < 0.3f) return DRY_PLAINS;
        if (d > 0.72f) return DARK_FOREST;
        if (d > 0.5f) return forestKind.noise(wx / 300f, wz / 300f) > 0.1f ? BIRCH_FOREST : FOREST;
        return PLAINS;
    }

    private static final float RAVINE_WIDTH = 0.03f; // in noise units: about 8 to 10 blocks across

    /** Ravine depth at a column (0 = none): narrow cuts through dry land, away from water. */
    public int ravineDepth(int wx, int wz, Column col) {
        if (col.height() < SEA + 6 || col.river() > 0 || col.biome() == BEACH) return 0;
        if (ravineMask.noise(wx / 500f, wz / 500f) < 0.25f) return 0;
        float v = Math.abs(ravine.fbm(wx / 150f, wz / 150f, 2));
        if (v >= RAVINE_WIDTH) return 0;
        float profile = (float) Math.sqrt(1 - v / RAVINE_WIDTH);   // deepest along the centre line
        return Math.min(col.height() - (SEA - 20), Math.round(40 * profile));
    }
}
