package net.mynecraft.world.crafting;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lookups for the recipe viewer: the recipes that make an item, the recipes an item goes into, how
 * many times a recipe can be crafted from what the player carries, and moving the ingredients into
 * a crafting grid in one click (vanilla's recipe book "fill"). Pure logic, tested without a screen.
 */
public final class RecipeIndex {
    private final RecipeBook book;
    private final Map<Short, List<RecipeBook.Recipe>> makes = new HashMap<>(), uses = new HashMap<>();

    public RecipeIndex(RecipeBook book) {
        this.book = book;
        for (RecipeBook.Recipe r : book.recipes) {
            makes.computeIfAbsent(r.result(), k -> new ArrayList<>()).add(r);
            java.util.Set<Short> seen = new java.util.HashSet<>();
            for (RecipeBook.Ingredient ing : r.cells())
                if (ing != null) for (short b : ing.accepts()) if (seen.add(b)) uses.computeIfAbsent(b, k -> new ArrayList<>()).add(r);
        }
    }

    /** Recipes whose result is this item. */
    public List<RecipeBook.Recipe> recipesFor(short item) {
        return makes.getOrDefault(item, List.of());
    }

    /** Recipes that take this item as an ingredient. */
    public List<RecipeBook.Recipe> usesOf(short item) {
        return uses.getOrDefault(item, List.of());
    }

    /** Smallest square grid a recipe needs (2 for the inventory, 3 for a crafting table). */
    public static int gridNeeded(RecipeBook.Recipe r) {
        return r.width() > 0 ? Math.max(r.width(), r.height()) : r.cells().length <= 4 ? 2 : 3;
    }

    /** Recipe cells laid out on a grid of {@code size} (shapeless ones in reading order), null = empty. */
    public static RecipeBook.Ingredient[] layout(RecipeBook.Recipe r, int size) {
        RecipeBook.Ingredient[] out = new RecipeBook.Ingredient[size * size];
        if (r.width() > 0) {
            for (int y = 0; y < r.height(); y++)
                for (int x = 0; x < r.width(); x++) out[y * size + x] = r.cells()[y * r.width() + x];
        } else {
            for (int i = 0; i < r.cells().length; i++) out[(i / size) * size + i % size] = r.cells()[i];
        }
        return out;
    }

    /**
     * How many times the recipe can be made from these stacks (the inventory, plus what already
     * lies in the grid): each cell takes one item, the same stack serving several cells.
     */
    public static int craftable(RecipeBook.Recipe r, short[] ids, int[] counts) {
        int[] left = counts.clone();
        int times = 0;
        while (times < 64 && take(r, ids, left)) times++;
        return times;
    }

    /** Takes one item per cell from the stacks (preferring the fullest), or returns false if one is missing. */
    private static boolean take(RecipeBook.Recipe r, short[] ids, int[] left) {
        int[] trial = left.clone();
        for (RecipeBook.Ingredient ing : r.cells()) {
            if (ing == null) continue;
            int best = -1;
            for (int i = 0; i < ids.length; i++)
                if (trial[i] > 0 && ing.accepts().contains(ids[i]) && (best < 0 || trial[i] > trial[best])) best = i;
            if (best < 0) return false;
            trial[best]--;
        }
        System.arraycopy(trial, 0, left, 0, left.length);
        return true;
    }

    /**
     * Fills the crafting grid of {@code menu} for {@code times} crafts of the recipe, taking the
     * ingredients from the inventory (what was in the grid goes back first). Returns false, and
     * changes nothing, if the grid is too small or the ingredients are missing.
     */
    public static boolean fill(CraftingMenu menu, RecipeBook.Recipe r, int times) {
        if (gridNeeded(r) > menu.gridSize || times <= 0) return false;
        Inventory inv = menu.inventory;
        // Grid contents back into the inventory (all or nothing).
        short[] ids = inv.ids.clone();
        int[] counts = inv.counts.clone();
        Inventory trial = new Inventory();
        System.arraycopy(ids, 0, trial.ids, 0, Inventory.SIZE);
        System.arraycopy(counts, 0, trial.counts, 0, Inventory.SIZE);
        for (int i = 0; i < menu.gridIds.length; i++)
            if (menu.gridCounts[i] > 0 && trial.add(menu.gridIds[i], menu.gridCounts[i]) > 0) return false;
        RecipeBook.Ingredient[] cells = layout(r, menu.gridSize);
        times = Math.min(times, craftable(r, trial.ids, trial.counts));
        if (times == 0) return false;
        short[] gridIds = new short[cells.length];
        int[] gridCounts = new int[cells.length];
        for (int c = 0; c < cells.length; c++) {
            if (cells[c] == null) continue;
            for (int k = 0; k < times; k++) { // one item per craft, from the fullest stack that fits the cell
                int best = -1;
                for (int i = 0; i < Inventory.SIZE; i++)
                    if (trial.counts[i] > 0 && cells[c].accepts().contains(trial.ids[i])
                        && (gridCounts[c] == 0 || trial.ids[i] == gridIds[c])
                        && (best < 0 || trial.counts[i] > trial.counts[best])) best = i;
                if (best < 0) return false;
                gridIds[c] = trial.ids[best];
                gridCounts[c]++;
                if (--trial.counts[best] == 0) trial.ids[best] = Blocks.AIR;
            }
        }
        System.arraycopy(trial.ids, 0, inv.ids, 0, Inventory.SIZE);
        System.arraycopy(trial.counts, 0, inv.counts, 0, Inventory.SIZE);
        System.arraycopy(gridIds, 0, menu.gridIds, 0, cells.length);
        System.arraycopy(gridCounts, 0, menu.gridCounts, 0, cells.length);
        return true;
    }

    public RecipeBook book() {
        return book;
    }
}
