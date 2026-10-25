package net.mynecraft.world;

/** 16x16x16 block cube, flat array indexed (y << 8) | (z << 4) | x. */
public final class Section {
    public static final int SIZE = 16;
    public static final int VOLUME = SIZE * SIZE * SIZE;

    final byte[] blocks = new byte[VOLUME];
    int nonAir;

    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public byte get(int x, int y, int z) {
        return blocks[index(x, y, z)];
    }

    public void set(int x, int y, int z, byte id) {
        int i = index(x, y, z);
        byte old = blocks[i];
        if (old == id) return;
        if (old == Blocks.AIR) nonAir++;
        else if (id == Blocks.AIR) nonAir--;
        blocks[i] = id;
    }

    public boolean isEmpty() {
        return nonAir == 0;
    }
}
