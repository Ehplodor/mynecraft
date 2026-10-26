package net.mynecraft.world.crafting;

import net.mynecraft.resource.PackSource;
import net.mynecraft.world.Blocks;
import net.mynecraft.world.Inventory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecipeViewerLogicTest {
    private static final RecipeBook BOOK = RecipeBook.load(List.of(PackSource.classpath("defaultdata")));
    private static final RecipeIndex INDEX = new RecipeIndex(BOOK);

    private static RecipeBook.Recipe first(byte result) {
        return INDEX.recipesFor(result).get(0);
    }

    @Test
    void findsWhatMakesAnItemAndWhatItIsUsedFor() {
        assertFalse(INDEX.recipesFor(Blocks.OAK_PLANKS).isEmpty());
        assertTrue(INDEX.usesOf(Blocks.OAK_PLANKS).stream().anyMatch(r -> r.result() == Blocks.CRAFTING_TABLE));
        assertTrue(INDEX.usesOf(Blocks.OAK_LOG).stream().anyMatch(r -> r.result() == Blocks.OAK_PLANKS));
        assertEquals(2, RecipeIndex.gridNeeded(first(Blocks.CRAFTING_TABLE)));
        assertEquals(3, RecipeIndex.gridNeeded(first(Blocks.OAK_SLAB)));
    }

    @Test
    void countsHowManyTimesARecipeCanBeMade() {
        Inventory inv = new Inventory();
        inv.add(Blocks.OAK_PLANKS, 9);
        assertEquals(2, RecipeIndex.craftable(first(Blocks.CRAFTING_TABLE), inv.ids, inv.counts)); // 4 planks each
        inv.clear();
        assertEquals(0, RecipeIndex.craftable(first(Blocks.CRAFTING_TABLE), inv.ids, inv.counts));
    }

    @Test
    void fillsTheGridFromTheInventory() {
        Inventory inv = new Inventory();
        inv.add(Blocks.OAK_PLANKS, 10);
        CraftingMenu menu = new CraftingMenu(inv, BOOK, 2);
        assertTrue(RecipeIndex.fill(menu, first(Blocks.CRAFTING_TABLE), 2));
        assertEquals(new RecipeBook.Result(Blocks.CRAFTING_TABLE, 1), menu.result());
        assertEquals(2, inv.counts[0], "8 planks moved to the grid");
        assertFalse(RecipeIndex.fill(menu, first(Blocks.OAK_SLAB), 1), "a 3-wide recipe needs a crafting table");
        menu.close();
        assertEquals(10, inv.counts[0], "closing gives everything back");
    }

    @Test
    void sortsMergesCollectsSwapsAndSpreads() {
        Inventory inv = new Inventory();
        inv.ids[20] = Blocks.STONE; inv.counts[20] = 10;
        inv.ids[12] = Blocks.DIRT; inv.counts[12] = 5;
        inv.ids[30] = Blocks.STONE; inv.counts[30] = 60;
        CraftingMenu menu = new CraftingMenu(inv, BOOK, 2);
        menu.sort();
        assertEquals(Blocks.STONE, inv.ids[9]);
        assertEquals(64, inv.counts[9]);
        assertEquals(6, inv.counts[10]);
        assertEquals(Blocks.DIRT, inv.ids[11]);
        assertEquals(0, inv.counts[12]);

        menu.click(10, false, false); // hold the 6 stones
        menu.collect();               // and gather the rest
        assertEquals(64, menu.cursorCount);
        assertEquals(6, inv.counts[9]);

        menu.spread(new int[] {20, 21, 22, 23}, false); // 64 over 4 empty slots
        assertEquals(16, inv.counts[20]);
        assertEquals(0, menu.cursorCount);

        menu.swapWithHotbar(11, 0);
        assertEquals(Blocks.DIRT, inv.ids[0]);
        assertEquals(0, inv.counts[11]);
    }
}
