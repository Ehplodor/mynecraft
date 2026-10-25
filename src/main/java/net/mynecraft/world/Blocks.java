package net.mynecraft.world;

/**
 * Block registry. Ids are bytes (0 = air); properties live in flat arrays indexed by id so hot
 * loops (meshing, light, collisions) stay branch-light and allocation-free.
 */
public final class Blocks {
    public static final byte AIR = 0, STONE = 1, DIRT = 2, GRASS = 3, SAND = 4, BEDROCK = 5, WATER = 6,
        OAK_LOG = 7, OAK_LEAVES = 8, OAK_PLANKS = 9, COBBLESTONE = 10, GLASS = 11, SHORT_GRASS = 12,
        POPPY = 13, GLOWSTONE = 14, GRAVEL = 15;
    public static final int COUNT = 16; // must stay below 128: the mesher uses bit 7 of the id as a flag

    /** Resource names (namespace minecraft), used for resource packs and saves. */
    public static final String[] NAMES = {"air", "stone", "dirt", "grass_block", "sand", "bedrock", "water",
        "oak_log", "oak_leaves", "oak_planks", "cobblestone", "glass", "short_grass", "poppy", "glowstone", "gravel"};

    public static final byte LAYER_OPAQUE = 0, LAYER_CUTOUT = 1, LAYER_TRANSLUCENT = 2;
    public static final byte SHAPE_NONE = 0, SHAPE_CUBE = 1, SHAPE_CROSS = 2;
    public static final byte TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2, TINT_WATER = 3;

    public static final byte[] LAYER = new byte[COUNT];
    public static final byte[] SHAPE = new byte[COUNT];
    /** Light attenuation: 0 transparent, 15 blocks light entirely. */
    public static final byte[] OPACITY = new byte[COUNT];
    public static final byte[] EMISSION = new byte[COUNT];
    /** Biome tint applied to faces whose model sets a tintindex. */
    public static final byte[] TINT = new byte[COUNT];
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
