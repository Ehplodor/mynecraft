package net.mynecraft.world;

/** Block ids. Stored as one byte per block; 0 is always air. */
public final class Blocks {
    public static final byte AIR = 0;
    public static final byte STONE = 1;
    public static final byte DIRT = 2;
    public static final byte GRASS = 3;
    public static final byte SAND = 4;
    public static final byte BEDROCK = 5;
    public static final int COUNT = 6;

    /** Linear RGB per block id, used by the M0 renderer until textures land. */
    public static final float[] COLORS = {
        0f, 0f, 0f,
        0.45f, 0.45f, 0.47f,
        0.47f, 0.33f, 0.22f,
        0.30f, 0.62f, 0.22f,
        0.86f, 0.80f, 0.55f,
        0.15f, 0.15f, 0.15f,
    };

    private Blocks() {}

    public static boolean isOpaque(byte id) {
        return id != AIR;
    }
}
