package net.mynecraft.world;

/**
 * Block registry. Ids are bytes (0 = air); properties live in flat arrays indexed by id so hot
 * loops (meshing, light, collisions) stay branch-light and allocation-free.
 */
public final class Blocks {
    public static final byte AIR = 0, STONE = 1, DIRT = 2, GRASS = 3, SAND = 4, BEDROCK = 5, WATER = 6,
        OAK_LOG = 7, OAK_LEAVES = 8, OAK_PLANKS = 9, COBBLESTONE = 10, GLASS = 11, SHORT_GRASS = 12,
        POPPY = 13, GLOWSTONE = 14, GRAVEL = 15, OAK_LOG_X = 16, OAK_LOG_Z = 17, OAK_SLAB = 18,
        OAK_SLAB_TOP = 19, OAK_SLAB_DOUBLE = 20, GRANITE = 21, DIORITE = 22, ANDESITE = 23, DEEPSLATE = 24,
        COAL_ORE = 25, IRON_ORE = 26, GOLD_ORE = 27, DIAMOND_ORE = 28, BIRCH_LOG = 29, BIRCH_LOG_X = 30,
        BIRCH_LOG_Z = 31, BIRCH_PLANKS = 32, BIRCH_LEAVES = 33, SPRUCE_LOG = 34, SPRUCE_LOG_X = 35,
        SPRUCE_LOG_Z = 36, SPRUCE_PLANKS = 37, SPRUCE_LEAVES = 38,
        WATER_FLOW_1 = 39, /* ... WATER_FLOW_7 = 45 */ WATER_FALLING = 46,
        LAVA = 47, LAVA_FLOW_2 = 48, LAVA_FLOW_4 = 49, LAVA_FLOW_6 = 50, LAVA_FALLING = 51, OBSIDIAN = 52;
    public static final int COUNT = 53; // must stay below 128: the mesher uses bit 7 of the id as a flag

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
        "lava", "lava", "lava", "lava", "lava", "obsidian"};
    public static final String[] STATE = {"", "", "", "", "", "", "", "axis=y", "", "", "", "", "", "", "", "",
        "axis=x", "axis=z", "type=bottom", "type=top", "type=double", "", "", "", "axis=y",
        "", "", "", "", "axis=y", "axis=x", "axis=z", "",
        "", "axis=y", "axis=x", "axis=z", "", "",
        "level=1", "level=2", "level=3", "level=4", "level=5", "level=6", "level=7", "level=8",
        "level=0", "level=2", "level=4", "level=6", "level=8", ""};

    public static final byte LAYER_OPAQUE = 0, LAYER_CUTOUT = 1, LAYER_TRANSLUCENT = 2;
    public static final byte SHAPE_NONE = 0, SHAPE_CUBE = 1, SHAPE_CROSS = 2, SHAPE_SLAB_BOTTOM = 3, SHAPE_SLAB_TOP = 4;
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
    public static final byte[] DROP = new byte[COUNT];
    public static final byte[] DROP_COUNT = new byte[COUNT];

    /** Families whose members hide faces between each other (each fluid, glass, each leaves). */
    private static final byte[] FAMILY = new byte[COUNT];

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
        for (byte leaves : new byte[] {OAK_LEAVES, BIRCH_LEAVES, SPRUCE_LEAVES}) {
            cutout(leaves, 1, true);
            TINT[leaves] = TINT_FOLIAGE; // vanilla gives birch and spruce fixed colours; biome foliage here
        }
        cutout(GLASS, 0, true);
        plant(SHORT_GRASS);
        TINT[SHORT_GRASS] = TINT_GRASS;
        plant(POPPY);
        for (int i = 0; i < COUNT; i++) FAMILY[i] = (byte) i;
        for (int i = WATER_FLOW_1 - 1; i <= WATER_FALLING; i++) {
            byte w = (byte) (i == WATER_FLOW_1 - 1 ? WATER : i);
            fluid(w, FLUID_WATER, LAYER_TRANSLUCENT, 1, w == WATER ? 0 : w == WATER_FALLING ? 8 : w - WATER_FLOW_1 + 1);
            TINT[w] = TINT_WATER;
            FAMILY[w] = WATER;
        }
        byte[] lava = {LAVA, LAVA_FLOW_2, LAVA_FLOW_4, LAVA_FLOW_6, LAVA_FALLING};
        for (int i = 0; i < lava.length; i++) {
            fluid(lava[i], FLUID_LAVA, LAYER_OPAQUE, 15, i == 4 ? 8 : i * 2);
            EMISSION[lava[i]] = 15;
            FAMILY[lava[i]] = LAVA;
        }
        EMISSION[GLOWSTONE] = 15;
        for (int i = 0; i < COUNT; i++) BOX_MAX[i] = 16;
        SHAPE[OAK_SLAB] = SHAPE_SLAB_BOTTOM;
        SHAPE[OAK_SLAB_TOP] = SHAPE_SLAB_TOP;
        OPACITY[OAK_SLAB] = OPACITY[OAK_SLAB_TOP] = 0;
        BOX_MAX[OAK_SLAB] = 8;
        BOX_MIN[OAK_SLAB_TOP] = 8;
        initSurvival();
        // Fluids are never full cubes: their surface is lowered, so neighbours keep their faces.
        for (int i = 1; i < COUNT; i++) FULL_OPAQUE[i] = LAYER[i] == LAYER_OPAQUE && SHAPE[i] == SHAPE_CUBE && FLUID[i] == FLUID_NONE;
    }

    private static void cutout(byte id, int opacity, boolean cullsSelf) {
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
        hardness(-1f, BEDROCK);
        DROP[STONE] = COBBLESTONE;
        DROP[GRASS] = DIRT;
        DROP_COUNT[OAK_SLAB_DOUBLE] = 2;
        for (byte none : new byte[] {OAK_LEAVES, BIRCH_LEAVES, SPRUCE_LEAVES, GLASS, SHORT_GRASS, BEDROCK}) DROP[none] = AIR;
        for (int i = 1; i < COUNT; i++) {
            if (FLUID[i] != FLUID_NONE) { DROP[i] = AIR; HARDNESS[i] = -1; }
        }
    }

    private static void hardness(float h, byte... ids) {
        for (byte id : ids) HARDNESS[id] = h;
    }

    /** Seconds to break a block by hand (vanilla: hardness x 1.5); infinite if unbreakable. */
    public static double breakSeconds(byte id) {
        float h = HARDNESS[id & 0xFF];
        return h < 0 ? Double.POSITIVE_INFINITY : h * 1.5;
    }

    private static void fluid(byte id, byte type, byte layer, int opacity, int level) {
        FLUID[id] = type;
        LEVEL[id] = (byte) level;
        LAYER[id] = layer;
        OPACITY[id] = (byte) opacity;
        SOLID[id] = false;
        CULLS_SELF[id] = true;
    }

    /** Surface height of a fluid in sixteenths when nothing of the same fluid sits above it. */
    public static int fluidHeight(byte id) {
        int level = LEVEL[id & 0xFF];
        if (level == 0 || level == 8) return 14;
        return Math.max(2, 14 - level * 12 / 8); // shallower further from the source
    }

    public static boolean isFluid(byte id) {
        return FLUID[id & 0xFF] != FLUID_NONE;
    }

    /** The state of a fluid type at a level (0 source, 1..7 flowing, 8 falling); lava levels round up to even. */
    public static byte fluidState(byte type, int level) {
        if (type == FLUID_WATER) return level == 0 ? WATER : level >= 8 ? WATER_FALLING : (byte) (WATER_FLOW_1 + level - 1);
        if (level == 0) return LAVA;
        if (level >= 8) return LAVA_FALLING;
        return level <= 2 ? LAVA_FLOW_2 : level <= 4 ? LAVA_FLOW_4 : LAVA_FLOW_6;
    }

    private static void plant(byte id) {
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
    public static byte fromState(String name, java.util.Map<String, String> properties) {
        byte first = -1;
        for (int i = 0; i < COUNT; i++) {
            if (!NAMES[i].equals(name)) continue;
            if (first < 0) first = (byte) i;
            if (matches(STATE[i], properties)) return (byte) i;
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
    public static byte[] logStates(byte id) {
        String n = NAMES[id & 0xFF];
        if (!n.endsWith("_log")) return null;
        byte y = fromState(n, java.util.Map.of("axis", "y")), x = fromState(n, java.util.Map.of("axis", "x"));
        return new byte[] {y, x, fromState(n, java.util.Map.of("axis", "z"))};
    }

    public static boolean isSlab(byte id) {
        byte s = SHAPE[id & 0xFF];
        return s == SHAPE_SLAB_BOTTOM || s == SHAPE_SLAB_TOP;
    }

    /** Hides neighbouring faces and casts ambient occlusion. */
    public static boolean isOpaque(byte id) {
        return FULL_OPAQUE[id & 0xFF];
    }

    /** Blocks movement. */
    public static boolean isSolid(byte id) {
        return SOLID[id & 0xFF];
    }

    /** Can be aimed at (everything but air and fluids). */
    public static boolean isTargetable(byte id) {
        return id != AIR && !isFluid(id);
    }

    /** Whether the face of {@code self} against {@code neighbour} must be drawn. */
    public static boolean faceVisible(byte self, byte neighbour) {
        return !FULL_OPAQUE[neighbour & 0xFF]
            && !(FAMILY[neighbour & 0xFF] == FAMILY[self & 0xFF] && CULLS_SELF[self & 0xFF]);
    }
}
