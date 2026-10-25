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

    /** Resource names (namespace minecraft), used to look up blockstates in resource packs. */
    public static final String[] NAMES = {"air", "stone", "dirt", "grass_block", "sand", "bedrock"};

    private Blocks() {}

    public static boolean isOpaque(byte id) {
        return id != AIR;
    }
}
