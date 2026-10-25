package net.mynecraft.world;

/**
 * Block registry. Ids are bytes (0 = air); properties live in flat arrays indexed by id so hot
 * loops (meshing, light, collisions) stay branch-light and allocation-free.
 */
public final class Blocks {
    public static final byte AIR = 0, STONE = 1, DIRT = 2, GRASS = 3, SAND = 4, BEDROCK = 5, WATER = 6,
        OAK_LOG = 7, OAK_LEAVES = 8, OAK_PLANKS = 9, COBBLESTONE = 10, GLASS = 11, SHORT_GRASS = 12,
        POPPY = 13, GLOWSTONE = 14, GRAVEL = 15, OAK_LOG_X = 16, OAK_LOG_Z = 17, OAK_SLAB = 18,
        OAK_SLAB_TOP = 19, OAK_SLAB_DOUBLE = 20;
    public static final int COUNT = 21; // must stay below 128: the mesher uses bit 7 of the id as a flag

    /**
     * Each id is one block state: a resource name (namespace minecraft) plus its properties, as in
     * blockstate files and saves ("axis=x", "type=top"). Empty properties = the block's only state.
     */
    public static final String[] NAMES = {"air", "stone", "dirt", "grass_block", "sand", "bedrock", "water",
        "oak_log", "oak_leaves", "oak_planks", "cobblestone", "glass", "short_grass", "poppy", "glowstone", "gravel",
        "oak_log", "oak_log", "oak_slab", "oak_slab", "oak_slab"};
    public static final String[] STATE = {"", "", "", "", "", "", "", "axis=y", "", "", "", "", "", "", "", "",
        "axis=x", "axis=z", "type=bottom", "type=top", "type=double"};

    public static final byte LAYER_OPAQUE = 0, LAYER_CUTOUT = 1, LAYER_TRANSLUCENT = 2;
    public static final byte SHAPE_NONE = 0, SHAPE_CUBE = 1, SHAPE_CROSS = 2, SHAPE_SLAB_BOTTOM = 3, SHAPE_SLAB_TOP = 4;
    public static final byte TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2, TINT_WATER = 3;

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
        cutout(OAK_LEAVES, 1, true);
        TINT[OAK_LEAVES] = TINT_FOLIAGE;
        cutout(GLASS, 0, true);
        plant(SHORT_GRASS);
        TINT[SHORT_GRASS] = TINT_GRASS;
        plant(POPPY);
        LAYER[WATER] = LAYER_TRANSLUCENT;
        OPACITY[WATER] = 1;
        SOLID[WATER] = false;
        CULLS_SELF[WATER] = true;
        TINT[WATER] = TINT_WATER;
        EMISSION[GLOWSTONE] = 15;
        for (int i = 0; i < COUNT; i++) BOX_MAX[i] = 16;
        SHAPE[OAK_SLAB] = SHAPE_SLAB_BOTTOM;
        SHAPE[OAK_SLAB_TOP] = SHAPE_SLAB_TOP;
        OPACITY[OAK_SLAB] = OPACITY[OAK_SLAB_TOP] = 0;
        BOX_MAX[OAK_SLAB] = 8;
        BOX_MIN[OAK_SLAB_TOP] = 8;
        for (int i = 1; i < COUNT; i++) FULL_OPAQUE[i] = LAYER[i] == LAYER_OPAQUE && SHAPE[i] == SHAPE_CUBE;
    }

    private static void cutout(byte id, int opacity, boolean cullsSelf) {
        LAYER[id] = LAYER_CUTOUT;
        OPACITY[id] = (byte) opacity;
        CULLS_SELF[id] = cullsSelf;
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
        return id != AIR && id != WATER;
    }

    /** Whether the face of {@code self} against {@code neighbour} must be drawn. */
    public static boolean faceVisible(byte self, byte neighbour) {
        return !FULL_OPAQUE[neighbour & 0xFF] && !(neighbour == self && CULLS_SELF[self & 0xFF]);
    }
}
