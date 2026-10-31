package net.mynecraft.world;

/**
 * 36 slots of item stacks, vanilla layout: slots 0..8 are the hotbar. Stacks hold up to the item's
 * own limit (64, 1 for tools); a tool's slot also keeps its wear (uses spent).
 */
public final class Inventory {
    public static final int SIZE = 36, HOTBAR = 9, MAX_STACK = 64;
    public final short[] ids = new short[SIZE];
    public final int[] counts = new int[SIZE];
    /** Uses spent of the tool in each slot. */
    public final int[] wear = new int[SIZE];

    public static int maxStack(short id) {
        return Items.MAX_STACK[id & 0xFFFF];
    }

    /** Adds items, topping up existing stacks first (hotbar first), then empty slots. Returns what did not fit. */
    public int add(short id, int count) {
        if (id == Blocks.AIR || count <= 0) return 0;
        int max = maxStack(id);
        for (int i = 0; i < SIZE && count > 0; i++) {
            if (ids[i] == id && counts[i] > 0 && counts[i] < max) {
                int n = Math.min(count, max - counts[i]);
                counts[i] += n;
                count -= n;
            }
        }
        for (int i = 0; i < SIZE && count > 0; i++) {
            if (counts[i] == 0) {
                int n = Math.min(count, max);
                ids[i] = id;
                counts[i] = n;
                wear[i] = 0;
                count -= n;
            }
        }
        return count;
    }

    /** Removes one item from a slot; returns false if it was empty. */
    public boolean takeOne(int slot) {
        if (counts[slot] <= 0) return false;
        if (--counts[slot] == 0) { ids[slot] = Blocks.AIR; wear[slot] = 0; }
        return true;
    }

    public short id(int slot) {
        return counts[slot] > 0 ? ids[slot] : Blocks.AIR;
    }

    /** How many of an item the inventory holds. */
    public int count(short id) {
        int n = 0;
        for (int i = 0; i < SIZE; i++) if (counts[i] > 0 && ids[i] == id) n += counts[i];
        return n;
    }

    /** Removes up to {@code n} of an item; returns how many were removed. */
    public int remove(short id, int n) {
        int done = 0;
        for (int i = SIZE - 1; i >= 0 && done < n; i--) {
            if (counts[i] <= 0 || ids[i] != id) continue;
            int t = Math.min(n - done, counts[i]);
            counts[i] -= t;
            done += t;
            if (counts[i] == 0) { ids[i] = Blocks.AIR; wear[i] = 0; }
        }
        return done;
    }

    /** First slot holding a tool of this kind (best tier first), or -1. */
    public int findTool(byte kind) {
        int best = -1;
        for (int i = 0; i < SIZE; i++)
            if (counts[i] > 0 && Items.TOOL[ids[i]] == kind && (best < 0 || Items.TIER[ids[i]] > Items.TIER[ids[best]])) best = i;
        return best;
    }

    /** One use of the tool in a slot; returns true when it breaks (and is gone). */
    public boolean wearOut(int slot) {
        short id = id(slot);
        int max = Items.DURABILITY[id & 0xFFFF];
        if (max <= 0) return false;
        if (++wear[slot] < max) return false;
        counts[slot] = 0;
        ids[slot] = Blocks.AIR;
        wear[slot] = 0;
        return true;
    }

    public void clear() {
        java.util.Arrays.fill(ids, Blocks.AIR);
        java.util.Arrays.fill(counts, 0);
        java.util.Arrays.fill(wear, 0);
    }
}
