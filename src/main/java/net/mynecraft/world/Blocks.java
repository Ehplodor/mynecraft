package net.mynecraft.world;

/**
 * Block registry. Ids are shorts (0 = air); properties live in flat arrays indexed by id so hot
 * loops (meshing, light, collisions) stay branch-light and allocation-free.
 */
public final class Blocks {
    public static final short AIR = 0, STONE = 1, DIRT = 2, GRASS = 3, SAND = 4, BEDROCK = 5, WATER = 6,
        OAK_LOG = 7, OAK_LEAVES = 8, OAK_PLANKS = 9, COBBLESTONE = 10, GLASS = 11, SHORT_GRASS = 12,
        POPPY = 13, GLOWSTONE = 14, GRAVEL = 15, OAK_LOG_X = 16, OAK_LOG_Z = 17, OAK_SLAB = 18,
        OAK_SLAB_TOP = 19, OAK_SLAB_DOUBLE = 20, GRANITE = 21, DIORITE = 22, ANDESITE = 23, DEEPSLATE = 24,
        COAL_ORE = 25, IRON_ORE = 26, GOLD_ORE = 27, DIAMOND_ORE = 28, BIRCH_LOG = 29, BIRCH_LOG_X = 30,
        BIRCH_LOG_Z = 31, BIRCH_PLANKS = 32, BIRCH_LEAVES = 33, SPRUCE_LOG = 34, SPRUCE_LOG_X = 35,
        SPRUCE_LOG_Z = 36, SPRUCE_PLANKS = 37, SPRUCE_LEAVES = 38,
        WATER_FLOW_1 = 39, /* ... WATER_FLOW_7 = 45 */ WATER_FALLING = 46,
        LAVA = 47, LAVA_FLOW_2 = 48, LAVA_FLOW_4 = 49, LAVA_FLOW_6 = 50, LAVA_FALLING = 51, OBSIDIAN = 52,
        CRAFTING_TABLE = 53, SNOW_BLOCK = 54, ICE = 55, PACKED_ICE = 56,
        SNOW_1 = 57, /* ... SNOW_8 = 64: snow layers, 2 to 16 sixteenths high */ GENTIAN = 65, EDELWEISS = 66;
    public static final int COUNT = 67; // ids are shorts: up to 32 767 states

    /**
     * Each id is one block state: a resource name (namespace minecraft) plus its properties, as in
     * blockstate files and saves ("axis=x", "type=top"). Empty properties = the block's only state.
     */
    public static final String[] NAMES = {"air", "stone", "dirt", "grass_block", "sand", "bedrock", "water",
        "oak_log", "oak_leaves", "oak_planks", "cobblestone", "glass", "short_grass", "poppy", "glowstone", "gravel",
        "oak_log", "oak_log", "oak_slab", "oak_slab", "oak_slab", "granite", "diorite", "andesite", "deepslate",
        "coal_ore", "iron_ore", "gold_ore", "diamond_ore", "birch_log", "birch_log", "birch_log", "birch_planks",
        "birch_leaves", "spruce_log", "spruce_log", "spruce_log", "spruce_planks", "spruce_leaves",
        "water", "water", "water", "water", "water", "water", "water", "water",
        "lava", "lava", "lava", "lava", "lava", "obsidian", "crafting_table", "snow_block", "ice", "packed_ice",
        "snow", "snow", "snow", "snow", "snow", "snow", "snow", "snow", "gentian", "edelweiss"};
    public static final String[] STATE = {"", "", "", "", "", "", "", "axis=y", "", "", "", "", "", "", "", "",
        "axis=x", "axis=z", "type=bottom", "type=top", "type=double", "", "", "", "axis=y",
        "", "", "", "", "axis=y", "axis=x", "axis=z", "",
        "", "axis=y", "axis=x", "axis=z", "", "",
        "level=1", "level=2", "level=3", "level=4", "level=5", "level=6", "level=7", "level=8",
        "level=0", "level=2", "level=4", "level=6", "level=8", "", "", "", "", "",
        "layers=1", "layers=2", "layers=3", "layers=4", "layers=5", "layers=6", "layers=7", "layers=8", "", ""};

    public static final byte LAYER_OPAQUE = 0, LAYER_CUTOUT = 1, LAYER_TRANSLUCENT = 2;
    public static final byte SHAPE_NONE = 0, SHAPE_CUBE = 1, SHAPE_CROSS = 2, SHAPE_SLAB_BOTTOM = 3, SHAPE_SLAB_TOP = 4,
        SHAPE_LAYER = 5;
    public static final byte TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2, TINT_WATER = 3;

    // ---- fluids ----
    public static final byte FLUID_NONE = 0, FLUID_WATER = 1, FLUID_LAVA = 2;
    /** Fluid type of each state. */
    public static final byte[] FLUID = new byte[COUNT];
    /**
     * Vanilla fluid level: 0 source, 1..7 flowing (higher = further from the source), 8 falling.
     * Lava in the overworld only uses even levels (it loses 2 per block).
     */
    public static final byte[] LEVEL = new byte[COUNT];
    // ---- survival ----
    /** Vanilla hardness (seconds by hand = hardness x 1.5); negative = unbreakable. */
    public static final float[] HARDNESS = new float[COUNT];
    /** Item dropped when broken (a block id; AIR = nothing) and how many. */
    public static final short[] DROP = new short[COUNT];
    public static final byte[] DROP_COUNT = new byte[COUNT];

    /** Families whose members hide faces between each other (each fluid, glass, each leaves). */
    private static final short[] FAMILY = new short[COUNT];

    public static final byte[] LAYER = new byte[COUNT];
    public static final byte[] SHAPE = new byte[COUNT];
    /** Light attenuation: 0 transparent, 15 blocks light entirely. */
    public static final byte[] OPACITY = new byte[COUNT];
    public static final byte[] EMISSION = new byte[COUNT];
    /** Biome tint applied to faces whose model sets a tintindex. */
    public static final byte[] TINT = new byte[COUNT];
    /** Collision box height range in sixteenths (full cube 0..16); meaningful for solid blocks only. */
    public static final byte[] BOX_MIN = new byte[COUNT], BOX_MAX = new byte[COUNT];
    private static final boolean[] SOLID = new boolean[COUNT];
    private static final boolean[] CULLS_SELF = new boolean[COUNT];
    private static final boolean[] FULL_OPAQUE = new boolean[COUNT];

    static {
        for (int i = 1; i < COUNT; i++) {
            LAYER[i] = LAYER_OPAQUE;
            SHAPE[i] = SHAPE_CUBE;
            OPACITY[i] = 15;
            SOLID[i] = true;
        }
        TINT[GRASS] = TINT_GRASS;
        for (short leaves : new short[] {OAK_LEAVES, BIRCH_LEAVES, SPRUCE_LEAVES}) {
            cutout(leaves, 1, true);
            TINT[leaves] = TINT_FOLIAGE; // vanilla gives birch and spruce fixed colours; biome foliage here
        }
        cutout(GLASS, 0, true);
        plant(SHORT_GRASS);
        TINT[SHORT_GRASS] = TINT_GRASS;
        plant(POPPY);
        plant(GENTIAN);
        plant(EDELWEISS);
        for (int i = 0; i < COUNT; i++) FAMILY[i] = (short) i;
        for (int i = WATER_FLOW_1 - 1; i <= WATER_FALLING; i++) {
            short w = (short) (i == WATER_FLOW_1 - 1 ? WATER : i);
            fluid(w, FLUID_WATER, LAYER_TRANSLUCENT, 1, w == WATER ? 0 : w == WATER_FALLING ? 8 : w - WATER_FLOW_1 + 1);
            TINT[w] = TINT_WATER;
            FAMILY[w] = WATER;
        }
        short[] lava = {LAVA, LAVA_FLOW_2, LAVA_FLOW_4, LAVA_FLOW_6, LAVA_FALLING};
        for (int i = 0; i < lava.length; i++) {
            fluid(lava[i], FLUID_LAVA, LAYER_OPAQUE, 15, i == 4 ? 8 : i * 2);
            EMISSION[lava[i]] = 15;
            FAMILY[lava[i]] = LAVA;
        }
        EMISSION[GLOWSTONE] = 15;
        // Ice is see-through like glass but tinted; it dims light a little, like water.
        LAYER[ICE] = LAYER_TRANSLUCENT;
        OPACITY[ICE] = 2;
        CULLS_SELF[ICE] = true;
        for (int i = 0; i < COUNT; i++) BOX_MAX[i] = 16;
        SHAPE[OAK_SLAB] = SHAPE_SLAB_BOTTOM;
        SHAPE[OAK_SLAB_TOP] = SHAPE_SLAB_TOP;
        OPACITY[OAK_SLAB] = OPACITY[OAK_SLAB_TOP] = 0;
        BOX_MAX[OAK_SLAB] = 8;
        BOX_MIN[OAK_SLAB_TOP] = 8;
        // Snow layers: drawn 2..16 sixteenths high, one layer less to stand on (feet sink in a little).
        for (int n = 1; n <= 8; n++) {
            short s = (short) (SNOW_1 + n - 1);
            SHAPE[s] = n == 8 ? SHAPE_CUBE : SHAPE_LAYER;
            OPACITY[s] = (byte) (n == 8 ? 15 : 0);
            BOX_MAX[s] = (byte) (2 * (n - 1));
            FAMILY[s] = SNOW_1;
        }
        initSurvival();
        // Fluids are never full cubes: their surface is lowered, so neighbours keep their faces.
        for (int i = 1; i < COUNT; i++) FULL_OPAQUE[i] = LAYER[i] == LAYER_OPAQUE && SHAPE[i] == SHAPE_CUBE && FLUID[i] == FLUID_NONE;
    }

    private static void cutout(short id, int opacity, boolean cullsSelf) {
        LAYER[id] = LAYER_CUTOUT;
        OPACITY[id] = (byte) opacity;
        CULLS_SELF[id] = cullsSelf;
    }

    private static void initSurvival() {
        for (int i = 1; i < COUNT; i++) {
            // Default: drops its own default state (a horizontal log drops a log, a top slab a slab).
            DROP[i] = fromState(NAMES[i], java.util.Map.of());
            DROP_COUNT[i] = 1;
            HARDNESS[i] = 1.5f;
        }
        hardness(0.5f, DIRT, SAND);
        hardness(0.6f, GRASS, GRAVEL);
        hardness(2f, OAK_LOG, OAK_LOG_X, OAK_LOG_Z, BIRCH_LOG, BIRCH_LOG_X, BIRCH_LOG_Z, SPRUCE_LOG, SPRUCE_LOG_X,
            SPRUCE_LOG_Z, OAK_PLANKS, BIRCH_PLANKS, SPRUCE_PLANKS, COBBLESTONE, OAK_SLAB, OAK_SLAB_TOP, OAK_SLAB_DOUBLE);
        hardness(0.2f, OAK_LEAVES, BIRCH_LEAVES, SPRUCE_LEAVES);
        hardness(0.3f, GLASS, GLOWSTONE);
        hardness(0f, SHORT_GRASS, POPPY);
        hardness(3f, COAL_ORE, IRON_ORE, GOLD_ORE, DIAMOND_ORE, DEEPSLATE);
        hardness(50f, OBSIDIAN);
        hardness(2.5f, CRAFTING_TABLE);
        hardness(0.2f, SNOW_BLOCK);
        for (int n = 0; n < 8; n++) {
            HARDNESS[SNOW_1 + n] = 0.1f;
            DROP[SNOW_1 + n] = AIR; // snowballs are not items yet
        }
        hardness(0f, GENTIAN, EDELWEISS);
        hardness(0.5f, ICE, PACKED_ICE);
        DROP[ICE] = AIR; // vanilla: ice breaks into nothing (water in survival, simplified)
        hardness(-1f, BEDROCK);
        DROP[STONE] = COBBLESTONE;
        DROP[GRASS] = DIRT;
        DROP_COUNT[OAK_SLAB_DOUBLE] = 2;
        for (short none : new short[] {OAK_LEAVES, BIRCH_LEAVES, SPRUCE_LEAVES, GLASS, SHORT_GRASS, BEDROCK}) DROP[none] = AIR;
        for (int i = 1; i < COUNT; i++) {
            if (FLUID[i] != FLUID_NONE) { DROP[i] = AIR; HARDNESS[i] = -1; }
        }
    }

    private static void hardness(float h, short... ids) {
        for (short id : ids) HARDNESS[id] = h;
    }

    /** Seconds to break a block by hand (vanilla: hardness x 1.5); infinite if unbreakable. */
    public static double breakSeconds(short id) {
        float h = HARDNESS[id & 0xFFFF];
        return h < 0 ? Double.POSITIVE_INFINITY : h * 1.5;
    }

    private static void fluid(short id, byte type, byte layer, int opacity, int level) {
        FLUID[id] = type;
        LEVEL[id] = (byte) level;
        LAYER[id] = layer;
        OPACITY[id] = (byte) opacity;
        SOLID[id] = false;
        CULLS_SELF[id] = true;
    }

    /** Surface height of a fluid in sixteenths when nothing of the same fluid sits above it. */
    public static int fluidHeight(short id) {
        int level = LEVEL[id & 0xFFFF];
        if (level == 0 || level == 8) return 14;
        return Math.max(2, 14 - level * 12 / 8); // shallower further from the source
    }

    public static boolean isFluid(short id) {
        return FLUID[id & 0xFFFF] != FLUID_NONE;
    }

    /** The state of a fluid type at a level (0 source, 1..7 flowing, 8 falling); lava levels round up to even. */
    public static short fluidState(int type, int level) {
        if (type == FLUID_WATER) return level == 0 ? WATER : level >= 8 ? WATER_FALLING : (short) (WATER_FLOW_1 + level - 1);
        if (level == 0) return LAVA;
        if (level >= 8) return LAVA_FALLING;
        return level <= 2 ? LAVA_FLOW_2 : level <= 4 ? LAVA_FLOW_4 : LAVA_FLOW_6;
    }

    private static void plant(short id) {
        LAYER[id] = LAYER_CUTOUT;
        SHAPE[id] = SHAPE_CROSS;
        OPACITY[id] = 0;
        SOLID[id] = false;
    }

    private Blocks() {}

    /**
     * Id of the state of {@code name} whose properties all appear in {@code properties}, else the
     * block's default state (its first id), else -1 for an unknown name.
     */
    public static short fromState(String name, java.util.Map<String, String> properties) {
        short first = -1;
        for (int i = 0; i < COUNT; i++) {
            if (!NAMES[i].equals(name)) continue;
            if (first < 0) first = (short) i;
            if (matches(STATE[i], properties)) return (short) i;
        }
        return first;
    }

    /** True if every "k=v" of {@code state} is present in {@code properties}. */
    public static boolean matches(String state, java.util.Map<String, String> properties) {
        if (state.isEmpty()) return true;
        for (String kv : state.split(",")) {
            int eq = kv.indexOf('=');
            if (!kv.substring(eq + 1).equals(properties.get(kv.substring(0, eq)))) return false;
        }
        return true;
    }

    /** The three axis states of a log family, in y, x, z order, or null. */
    public static short[] logStates(short id) {
        String n = NAMES[id & 0xFFFF];
        if (!n.endsWith("_log")) return null;
        short y = fromState(n, java.util.Map.of("axis", "y")), x = fromState(n, java.util.Map.of("axis", "x"));
        return new short[] {y, x, fromState(n, java.util.Map.of("axis", "z"))};
    }

    /** Snow layers on this state (1..8), 0 if it is not snow. */
    public static int snowLayers(short id) {
        int i = (id & 0xFFFF) - SNOW_1;
        return i >= 0 && i < 8 ? i + 1 : 0;
    }

    /** The snow state with this many layers (clamped to 1..8). */
    public static short snow(int layers) {
        return (short) (SNOW_1 + Math.max(1, Math.min(8, layers)) - 1);
    }

    /** Low plants that stand in the open and are buried by snow, nipped by frost or eaten by grazers. */
    public static boolean isPlant(short id) {
        return id == SHORT_GRASS || id == POPPY || id == GENTIAN || id == EDELWEISS;
    }

    public static boolean isSlab(short id) {
        byte s = SHAPE[id & 0xFFFF];
        return s == SHAPE_SLAB_BOTTOM || s == SHAPE_SLAB_TOP;
    }

    /** Hides neighbouring faces and casts ambient occlusion. */
    public static boolean isOpaque(short id) {
        return FULL_OPAQUE[id & 0xFFFF];
    }

    /** Blocks movement. */
    public static boolean isSolid(short id) {
        return SOLID[id & 0xFFFF];
    }

    /** Can be aimed at (everything but air and fluids). */
    public static boolean isTargetable(short id) {
        return id != AIR && !isFluid(id);
    }

    /** Whether the face of {@code self} against {@code neighbour} must be drawn. */
    public static boolean faceVisible(short self, short neighbour) {
        return !FULL_OPAQUE[neighbour & 0xFFFF]
            && !(FAMILY[neighbour & 0xFFFF] == FAMILY[self & 0xFFFF] && CULLS_SELF[self & 0xFFFF]);
    }
}
