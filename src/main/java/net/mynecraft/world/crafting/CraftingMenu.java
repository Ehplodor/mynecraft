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
    public int cursorCount, cursorWear;
    /** The crafter's skills (gates recipes needing a level, and learns from each craft); null: no rules. */
    public net.mynecraft.world.actor.Skills skills;
    private final int[] gridWear;

    public CraftingMenu(Inventory inventory, RecipeBook recipes, int gridSize) {
        this.inventory = inventory;
        this.recipes = recipes;
        this.gridSize = gridSize;
        this.gridIds = new short[gridSize * gridSize];
        this.gridCounts = new int[gridSize * gridSize];
        this.gridWear = new int[gridSize * gridSize];
    }

    public int gridStart() { return Inventory.SIZE; }

    public int resultSlot() { return Inventory.SIZE + gridIds.length; }

    public int slotCount() { return resultSlot() + 1; }

    public RecipeBook.Result result() {
        int repair = repairSlot();
        if (repair >= 0) return new RecipeBook.Result(gridIds[repair], 1);
        RecipeBook.Recipe r = recipes.matchRecipe(gridIds, gridSize);
        if (r == null || (skills != null && skills.level(r.rules().skill()) < r.rules().level())) return null;
        return new RecipeBook.Result(r.result(), r.count());
    }

    /**
     * Repairing (M27): a worn tool alone in the grid with its repair material (any planks for
     * wooden tools); each material piece takes back a quarter of its durability. Returns the tool's
     * grid cell, or -1.
     */
    private int repairSlot() {
        int tool = -1, material = 0;
        for (int i = 0; i < gridIds.length; i++) {
            if (gridCounts[i] == 0) continue;
            short id = gridIds[i];
            if (net.mynecraft.world.Items.DURABILITY[id] > 0 && net.mynecraft.world.Items.REPAIR[id] != Blocks.AIR) {
                if (tool >= 0) return -1;
                tool = i;
            } else material++;
        }
        if (tool < 0 || material == 0 || gridWear[tool] == 0) return -1;
        short need = net.mynecraft.world.Items.REPAIR[gridIds[tool]];
        for (int i = 0; i < gridIds.length; i++)
            if (i != tool && gridCounts[i] > 0 && gridIds[i] != need && !(need == Blocks.OAK_PLANKS && Blocks.NAMES[gridIds[i]].endsWith("_planks")))
                return -1;
        return tool;
    }

    private int repairedWear(int tool) {
        int pieces = 0;
        for (int i = 0; i < gridIds.length; i++) if (i != tool && gridCounts[i] > 0) pieces++;
        return Math.max(0, gridWear[tool] - pieces * net.mynecraft.world.Items.DURABILITY[gridIds[tool]] / 4);
    }

    /** Uses spent of the tool in a slot (inventory or grid). */
    public int wear(int slot) {
        if (slot < Inventory.SIZE) return inventory.wear[slot];
        if (slot < resultSlot()) return gridWear[slot - Inventory.SIZE];
        int t = repairSlot();
        return t >= 0 ? repairedWear(t) : 0;
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
        set(slot, id, count, wear(slot));
    }

    private void set(int slot, short id, int count, int wear) {
        if (count <= 0) { id = Blocks.AIR; count = 0; wear = 0; }
        if (slot < Inventory.SIZE) {
            inventory.ids[slot] = id;
            inventory.counts[slot] = count;
            inventory.wear[slot] = wear;
        } else {
            gridIds[slot - Inventory.SIZE] = id;
            gridCounts[slot - Inventory.SIZE] = count;
            gridWear[slot - Inventory.SIZE] = wear;
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
            cursorWear = wear(slot);
            set(slot, id, n - take);
        } else if (n == 0 || (id == cursorId && Inventory.maxStack(id) > 1)) {
            int room = Inventory.maxStack(cursorId) - n;
            int put = Math.min(right ? 1 : cursorCount, room);
            if (put <= 0) return;
            set(slot, cursorId, n + put, cursorWear);
            cursorCount -= put;
            if (cursorCount == 0) { cursorId = Blocks.AIR; cursorWear = 0; }
        } else {
            // Different items: swap the held stack with the slot.
            short heldId = cursorId;
            int held = cursorCount, heldWear = cursorWear;
            cursorId = id;
            cursorCount = n;
            cursorWear = wear(slot);
            set(slot, heldId, held, heldWear);
        }
    }

    /** Takes the result once (onto the cursor, or into the inventory for shift clicks). */
    private boolean craftInto(boolean toInventory) {
        RecipeBook.Result r = result();
        if (r == null) return false;
        int repair = repairSlot(), wear = repair >= 0 ? repairedWear(repair) : 0;
        if (toInventory) {
            Inventory probe = copy(inventory);
            if (probe.add(r.block(), r.count()) > 0) return false; // would not fit
            int before = -1;
            for (int i = 0; i < Inventory.SIZE && before < 0; i++) if (inventory.counts[i] == 0) before = i;
            inventory.add(r.block(), r.count());
            if (repair >= 0 && before >= 0) inventory.wear[before] = wear;
        } else {
            if (cursorCount > 0 && (cursorId != r.block() || cursorCount + r.count() > Inventory.maxStack(r.block()))) return false;
            cursorId = r.block();
            cursorCount += r.count();
            cursorWear = wear;
        }
        if (skills != null) {
            RecipeBook.Recipe recipe = repair >= 0 ? null : recipes.matchRecipe(gridIds, gridSize);
            skills.practice(recipe == null ? net.mynecraft.world.actor.Skill.CRAFTING : recipe.rules().skill(),
                1 + (recipe == null ? 1 : recipe.rules().level()));
        }
        for (int i = 0; i < gridIds.length; i++) {
            if (repair == i) { gridCounts[i] = 0; gridIds[i] = Blocks.AIR; gridWear[i] = 0; continue; }
            if (gridCounts[i] > 0 && --gridCounts[i] == 0) gridIds[i] = Blocks.AIR;
        }
        return true;
    }

    private static Inventory copy(Inventory inv) {
        Inventory c = new Inventory();
        System.arraycopy(inv.ids, 0, c.ids, 0, Inventory.SIZE);
        System.arraycopy(inv.counts, 0, c.counts, 0, Inventory.SIZE);
        System.arraycopy(inv.wear, 0, c.wear, 0, Inventory.SIZE);
        return c;
    }

    /** Shift click: hotbar to main inventory and back; grid to inventory. */
    private void quickMove(int slot) {
        short id = id(slot);
        int n = count(slot), w = wear(slot), max = Inventory.maxStack(id);
        if (n == 0) return;
        int from, to;
        if (slot >= Inventory.SIZE) { from = 0; to = Inventory.SIZE; }        // grid -> anywhere
        else if (slot < Inventory.HOTBAR) { from = Inventory.HOTBAR; to = Inventory.SIZE; } // hotbar -> main
        else { from = 0; to = Inventory.HOTBAR; }                            // main -> hotbar
        for (int pass = 0; pass < 2 && n > 0; pass++) { // first merge into same items, then empty slots
            for (int i = from; i < to && n > 0; i++) {
                int c = inventory.counts[i];
                boolean ok = pass == 0 ? c > 0 && inventory.ids[i] == id && c < max : c == 0;
                if (!ok) continue;
                int put = Math.min(n, max - c);
                inventory.ids[i] = id;
                inventory.counts[i] = c + put;
                if (c == 0) inventory.wear[i] = w;
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
        java.util.List<int[]> single = new java.util.ArrayList<>(); // tools keep their own wear
        for (int i = Inventory.HOTBAR; i < Inventory.SIZE; i++) {
            if (inventory.counts[i] <= 0) continue;
            if (Inventory.maxStack(inventory.ids[i]) == 1) single.add(new int[] {inventory.ids[i], inventory.wear[i]});
            else total.merge(inventory.ids[i], inventory.counts[i], Integer::sum);
        }
        single.sort(java.util.Comparator.comparingInt(a -> a[0]));
        int slot = Inventory.HOTBAR;
        for (int[] t : single) set(slot++, (short) t[0], 1, t[1]);
        for (var e : total.entrySet()) {
            int n = e.getValue();
            while (n > 0) {
                int put = Math.min(n, Inventory.maxStack(e.getKey()));
                set(slot, e.getKey(), put, 0);
                n -= put;
                slot++;
            }
        }
        for (; slot < Inventory.SIZE; slot++) set(slot, Blocks.AIR, 0);
    }

    /** Double click with a stack held: gathers the same item from the inventory onto the cursor (up to 64). */
    public void collect() {
        int max = Inventory.maxStack(cursorId);
        if (cursorCount == 0 || max == 1) return;
        for (int pass = 0; pass < 2 && cursorCount < max; pass++) // part stacks first, like vanilla
            for (int i = 0; i < Inventory.SIZE && cursorCount < max; i++) {
                int c = inventory.counts[i];
                if (c == 0 || inventory.ids[i] != cursorId || (pass == 0 && c == max)) continue;
                int take = Math.min(c, max - cursorCount);
                cursorCount += take;
                set(i, cursorId, c - take);
            }
    }

    /** Number key over a slot: swaps it with that hotbar slot (0..8). */
    public void swapWithHotbar(int slot, int hotbar) {
        if (slot < 0 || slot >= resultSlot() || hotbar < 0 || hotbar >= Inventory.HOTBAR || slot == hotbar) return;
        short a = id(slot), b = id(hotbar);
        int na = count(slot), nb = count(hotbar), wa = wear(slot), wb = wear(hotbar);
        set(slot, b, nb, wb);
        set(hotbar, a, na, wa);
    }

    /**
     * Dragging the held stack over several slots: left button spreads it evenly over them (the
     * remainder stays held), right button drops one item in each. Slots holding another item or
     * the result slot are skipped.
     */
    public void spread(int[] slots, boolean oneEach) {
        java.util.List<Integer> ok = new java.util.ArrayList<>();
        for (int s : slots)
            if (s >= 0 && s < resultSlot() && !ok.contains(s) && (count(s) == 0 || id(s) == cursorId) && count(s) < Inventory.maxStack(cursorId)) ok.add(s);
        if (cursorCount == 0 || ok.isEmpty()) return;
        int each = oneEach ? 1 : cursorCount / ok.size();
        if (each == 0) each = 1;
        for (int s : ok) {
            if (cursorCount == 0) break;
            int put = Math.min(Math.min(each, cursorCount), Inventory.maxStack(cursorId) - count(s));
            set(s, cursorId, count(s) + put, cursorWear);
            cursorCount -= put;
        }
        if (cursorCount == 0) cursorId = Blocks.AIR;
    }

    private int giveBack(short id, int count, int wear) {
        if (count <= 0) return 0;
        int empty = -1;
        for (int i = 0; i < Inventory.SIZE && empty < 0; i++) if (inventory.counts[i] == 0) empty = i;
        int lost = inventory.add(id, count);
        if (wear > 0 && empty >= 0 && inventory.ids[empty] == id) inventory.wear[empty] = wear;
        return lost;
    }

    /** Closing the screen: whatever is held or left in the grid goes back to the inventory. Returns what was lost. */
    public int close() {
        int lost = giveBack(cursorId, cursorCount, cursorWear);
        cursorId = Blocks.AIR;
        cursorCount = 0;
        cursorWear = 0;
        for (int i = 0; i < gridIds.length; i++) {
            lost += giveBack(gridIds[i], gridCounts[i], gridWear[i]);
            gridIds[i] = Blocks.AIR;
            gridCounts[i] = 0;
            gridWear[i] = 0;
        }
        return lost;
    }
}
