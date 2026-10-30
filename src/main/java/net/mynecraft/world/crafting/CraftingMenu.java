package net.mynecraft.world.crafting;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Inventory;

/**
 * Inventory screen logic, vanilla click rules: slots 0..35 are the inventory (0..8 hotbar), then the
 * crafting grid (2x2 in the inventory, 3x3 at a crafting table), then the result slot.
 * <ul>
 *   <li>left click: pick up a stack, put it down, merge into the same item, or swap;</li>
 *   <li>right click: pick up half a stack, or put one item down;</li>
 *   <li>shift click: move a stack between hotbar and main inventory, or out of the grid;
 *       on the result, craft as many as fit.</li>
 * </ul>
 * Pure logic (no GL) so it can be tested; the screen only maps mouse positions to slots.
 */
public final class CraftingMenu {
    public final Inventory inventory;
    public final RecipeBook recipes;
    public final int gridSize;
    public final short[] gridIds;
    public final int[] gridCounts;
    /** The stack held by the mouse. */
    public short cursorId = Blocks.AIR;
    public int cursorCount;

    public CraftingMenu(Inventory inventory, RecipeBook recipes, int gridSize) {
        this.inventory = inventory;
        this.recipes = recipes;
        this.gridSize = gridSize;
        this.gridIds = new short[gridSize * gridSize];
        this.gridCounts = new int[gridSize * gridSize];
    }

    public int gridStart() { return Inventory.SIZE; }

    public int resultSlot() { return Inventory.SIZE + gridIds.length; }

    public int slotCount() { return resultSlot() + 1; }

    public RecipeBook.Result result() {
        return recipes.match(gridIds, gridSize);
    }

    public short id(int slot) {
        if (slot < Inventory.SIZE) return inventory.id(slot);
        if (slot < resultSlot()) return gridCounts[slot - Inventory.SIZE] > 0 ? gridIds[slot - Inventory.SIZE] : Blocks.AIR;
        RecipeBook.Result r = result();
        return r == null ? Blocks.AIR : r.block();
    }

    public int count(int slot) {
        if (slot < Inventory.SIZE) return inventory.counts[slot];
        if (slot < resultSlot()) return gridCounts[slot - Inventory.SIZE];
        RecipeBook.Result r = result();
        return r == null ? 0 : r.count();
    }

    private void set(int slot, short id, int count) {
        if (count <= 0) { id = Blocks.AIR; count = 0; }
        if (slot < Inventory.SIZE) {
            inventory.ids[slot] = id;
            inventory.counts[slot] = count;
        } else {
            gridIds[slot - Inventory.SIZE] = id;
            gridCounts[slot - Inventory.SIZE] = count;
        }
    }

    public void click(int slot, boolean right, boolean shift) {
        if (slot < 0 || slot >= slotCount()) return;
        if (slot == resultSlot()) {
            if (shift) { while (craftInto(true)) { } } else craftInto(false);
            return;
        }
        if (shift) { quickMove(slot); return; }
        short id = id(slot);
        int n = count(slot);
        if (cursorCount == 0) {
            if (n == 0) return;
            int take = right ? (n + 1) / 2 : n;
            cursorId = id;
            cursorCount = take;
            set(slot, id, n - take);
        } else if (n == 0 || id == cursorId) {
            int room = Inventory.MAX_STACK - n;
            int put = Math.min(right ? 1 : cursorCount, room);
            if (put <= 0) return;
            set(slot, cursorId, n + put);
            cursorCount -= put;
            if (cursorCount == 0) cursorId = Blocks.AIR;
        } else {
            // Different items: swap the held stack with the slot.
            short heldId = cursorId;
            int held = cursorCount;
            cursorId = id;
            cursorCount = n;
            set(slot, heldId, held);
        }
    }

    /** Takes the result once (onto the cursor, or into the inventory for shift clicks). */
    private boolean craftInto(boolean toInventory) {
        RecipeBook.Result r = result();
        if (r == null) return false;
        if (toInventory) {
            Inventory probe = copy(inventory);
            if (probe.add(r.block(), r.count()) > 0) return false; // would not fit
            inventory.add(r.block(), r.count());
        } else {
            if (cursorCount > 0 && (cursorId != r.block() || cursorCount + r.count() > Inventory.MAX_STACK)) return false;
            cursorId = r.block();
            cursorCount += r.count();
        }
        for (int i = 0; i < gridIds.length; i++) {
            if (gridCounts[i] > 0 && --gridCounts[i] == 0) gridIds[i] = Blocks.AIR;
        }
        return true;
    }

    private static Inventory copy(Inventory inv) {
        Inventory c = new Inventory();
        System.arraycopy(inv.ids, 0, c.ids, 0, Inventory.SIZE);
        System.arraycopy(inv.counts, 0, c.counts, 0, Inventory.SIZE);
        return c;
    }

    /** Shift click: hotbar to main inventory and back; grid to inventory. */
    private void quickMove(int slot) {
        short id = id(slot);
        int n = count(slot);
        if (n == 0) return;
        int from, to;
        if (slot >= Inventory.SIZE) { from = 0; to = Inventory.SIZE; }        // grid -> anywhere
        else if (slot < Inventory.HOTBAR) { from = Inventory.HOTBAR; to = Inventory.SIZE; } // hotbar -> main
        else { from = 0; to = Inventory.HOTBAR; }                            // main -> hotbar
        for (int pass = 0; pass < 2 && n > 0; pass++) { // first merge into same items, then empty slots
            for (int i = from; i < to && n > 0; i++) {
                int c = inventory.counts[i];
                boolean ok = pass == 0 ? c > 0 && inventory.ids[i] == id && c < Inventory.MAX_STACK : c == 0;
                if (!ok) continue;
                int put = Math.min(n, Inventory.MAX_STACK - c);
                inventory.ids[i] = id;
                inventory.counts[i] = c + put;
                n -= put;
            }
        }
        set(slot, id, n);
    }

    /**
     * Sorts the main inventory (not the hotbar, which the player arranges): same items merged into
     * full stacks, ordered by block, empty slots at the end.
     */
    public void sort() {
        java.util.TreeMap<Short, Integer> total = new java.util.TreeMap<>();
        for (int i = Inventory.HOTBAR; i < Inventory.SIZE; i++)
            if (inventory.counts[i] > 0) total.merge(inventory.ids[i], inventory.counts[i], Integer::sum);
        int slot = Inventory.HOTBAR;
        for (var e : total.entrySet()) {
            int n = e.getValue();
            while (n > 0) {
                int put = Math.min(n, Inventory.MAX_STACK);
                inventory.ids[slot] = e.getKey();
                inventory.counts[slot] = put;
                n -= put;
                slot++;
            }
        }
        for (; slot < Inventory.SIZE; slot++) set(slot, Blocks.AIR, 0);
    }

    /** Double click with a stack held: gathers the same item from the inventory onto the cursor (up to 64). */
    public void collect() {
        if (cursorCount == 0) return;
        for (int pass = 0; pass < 2 && cursorCount < Inventory.MAX_STACK; pass++) // part stacks first, like vanilla
            for (int i = 0; i < Inventory.SIZE && cursorCount < Inventory.MAX_STACK; i++) {
                int c = inventory.counts[i];
                if (c == 0 || inventory.ids[i] != cursorId || (pass == 0 && c == Inventory.MAX_STACK)) continue;
                int take = Math.min(c, Inventory.MAX_STACK - cursorCount);
                cursorCount += take;
                set(i, cursorId, c - take);
            }
    }

    /** Number key over a slot: swaps it with that hotbar slot (0..8). */
    public void swapWithHotbar(int slot, int hotbar) {
        if (slot < 0 || slot >= resultSlot() || hotbar < 0 || hotbar >= Inventory.HOTBAR || slot == hotbar) return;
        short a = id(slot), b = id(hotbar);
        int na = count(slot), nb = count(hotbar);
        set(slot, b, nb);
        set(hotbar, a, na);
    }

    /**
     * Dragging the held stack over several slots: left button spreads it evenly over them (the
     * remainder stays held), right button drops one item in each. Slots holding another item or
     * the result slot are skipped.
     */
    public void spread(int[] slots, boolean oneEach) {
        java.util.List<Integer> ok = new java.util.ArrayList<>();
        for (int s : slots)
            if (s >= 0 && s < resultSlot() && !ok.contains(s) && (count(s) == 0 || id(s) == cursorId) && count(s) < Inventory.MAX_STACK) ok.add(s);
        if (cursorCount == 0 || ok.isEmpty()) return;
        int each = oneEach ? 1 : cursorCount / ok.size();
        if (each == 0) each = 1;
        for (int s : ok) {
            if (cursorCount == 0) break;
            int put = Math.min(Math.min(each, cursorCount), Inventory.MAX_STACK - count(s));
            set(s, cursorId, count(s) + put);
            cursorCount -= put;
        }
        if (cursorCount == 0) cursorId = Blocks.AIR;
    }

    /** Closing the screen: whatever is held or left in the grid goes back to the inventory. Returns what was lost. */
    public int close() {
        int lost = inventory.add(cursorId, cursorCount);
        cursorId = Blocks.AIR;
        cursorCount = 0;
        for (int i = 0; i < gridIds.length; i++) {
            lost += inventory.add(gridIds[i], gridCounts[i]);
            gridIds[i] = Blocks.AIR;
            gridCounts[i] = 0;
        }
        return lost;
    }
}
