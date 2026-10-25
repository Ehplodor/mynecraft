package net.mynecraft.world;

/** 36 slots of block stacks (up to 64), vanilla layout: slots 0..8 are the hotbar. */
public final class Inventory {
    public static final int SIZE = 36, HOTBAR = 9, MAX_STACK = 64;
    public final byte[] ids = new byte[SIZE];
    public final int[] counts = new int[SIZE];

    /** Adds items, topping up existing stacks first (hotbar first), then empty slots. Returns what did not fit. */
    public int add(byte id, int count) {
        if (id == Blocks.AIR || count <= 0) return 0;
        for (int i = 0; i < SIZE && count > 0; i++) {
            if (ids[i] == id && counts[i] < MAX_STACK) {
                int n = Math.min(count, MAX_STACK - counts[i]);
                counts[i] += n;
                count -= n;
            }
        }
        for (int i = 0; i < SIZE && count > 0; i++) {
            if (counts[i] == 0) {
                int n = Math.min(count, MAX_STACK);
                ids[i] = id;
                counts[i] = n;
                count -= n;
            }
        }
        return count;
    }

    /** Removes one item from a slot; returns false if it was empty. */
    public boolean takeOne(int slot) {
        if (counts[slot] <= 0) return false;
        if (--counts[slot] == 0) ids[slot] = Blocks.AIR;
        return true;
    }

    public byte id(int slot) {
        return counts[slot] > 0 ? ids[slot] : Blocks.AIR;
    }

    public void clear() {
        java.util.Arrays.fill(ids, Blocks.AIR);
        java.util.Arrays.fill(counts, 0);
    }
}
