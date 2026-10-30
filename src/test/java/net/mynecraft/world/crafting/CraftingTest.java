package net.mynecraft.world.crafting;

import net.mynecraft.resource.PackSource;
import net.mynecraft.world.Blocks;
import net.mynecraft.world.Inventory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CraftingTest {
    private static final short A = Blocks.AIR, LOG = Blocks.OAK_LOG, OAK = Blocks.OAK_PLANKS, BIRCH = Blocks.BIRCH_PLANKS;

    private static RecipeBook builtIn() {
        return RecipeBook.load(List.of(PackSource.classpath("defaultdata")));
    }

    /** A data pack held in memory, as a world's datapacks/ folder would provide. */
    private static PackSource memory(Map<String, String> files) {
        return new PackSource() {
            public byte[] read(String p) {
                String s = files.get(p);
                return s == null ? null : s.getBytes(StandardCharsets.UTF_8);
            }
            public List<String> list(String prefix) {
                List<String> out = new ArrayList<>();
                for (String k : files.keySet()) if (k.startsWith(prefix)) out.add(k);
                return out;
            }
            public String name() { return "memory"; }
        };
    }

    @Test
    void builtInRecipesMatchAnywhere() {
        RecipeBook book = builtIn();
        assertEquals(List.of(), book.warnings);
        // A log anywhere in a 2x2 grid gives 4 planks (shapeless).
        assertEquals(new RecipeBook.Result(OAK, 4), book.match(new short[] {A, A, A, LOG}, 2));
        // Each wood gives its own planks (items are default states: a broken horizontal log drops a plain log).
        assertEquals(new RecipeBook.Result(Blocks.BIRCH_PLANKS, 4), book.match(new short[] {Blocks.BIRCH_LOG, A, A, A}, 2));
        assertEquals(Blocks.BIRCH_LOG, Blocks.DROP[Blocks.BIRCH_LOG_X]);
    }

    @Test
    void tagsAndShapes() {
        RecipeBook book = builtIn();
        // 4 planks of any wood (tag #planks) make a crafting table.
        assertEquals(new RecipeBook.Result(Blocks.CRAFTING_TABLE, 1), book.match(new short[] {OAK, BIRCH, BIRCH, OAK}, 2));
        // 3 planks in a row need a 3-wide grid; the same row anywhere in a 3x3 works.
        assertEquals(new RecipeBook.Result(Blocks.OAK_SLAB, 6), book.match(new short[] {A, A, A, A, A, A, OAK, OAK, OAK}, 3));
        assertNull(book.match(new short[] {OAK, OAK, OAK, A}, 2));
        assertNull(book.match(new short[] {OAK, A, A, OAK}, 2), "diagonal is not a recipe");
        assertNull(book.match(new short[] {A, A, A, A}, 2));
    }

    @Test
    void externalPackOlderFormatAndOverride() {
        RecipeBook book = RecipeBook.load(List.of(PackSource.classpath("defaultdata"), memory(Map.of(
            // pre-1.21 folders and object syntax; asymmetric shape to check mirroring
            "data/minecraft/tags/items/stones.json", "{\"values\":[\"minecraft:stone\",\"minecraft:granite\"]}",
            "data/custom/recipes/glow.json", "{\"type\":\"crafting_shaped\",\"pattern\":[\"#G\",\" #\"],"
                + "\"key\":{\"#\":{\"tag\":\"minecraft:stones\"},\"G\":{\"item\":\"minecraft:glass\"}},"
                + "\"result\":{\"item\":\"minecraft:glowstone\",\"count\":2}}",
            // a data pack replacing a built-in recipe by id
            "data/minecraft/recipe/oak_slab.json", "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"##\"],"
                + "\"key\":{\"#\":\"minecraft:oak_planks\"},\"result\":{\"id\":\"minecraft:oak_slab\",\"count\":1}}",
            "data/custom/recipe/diamond_pickaxe.json", "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"#\"],"
                + "\"key\":{\"#\":\"minecraft:diamond\"},\"result\":{\"id\":\"minecraft:diamond_pickaxe\"}}"))));
        short S = Blocks.STONE, G = Blocks.GLASS, R = Blocks.GRANITE;
        assertEquals(new RecipeBook.Result(Blocks.GLOWSTONE, 2), book.match(new short[] {S, G, A, R}, 2));
        assertEquals(new RecipeBook.Result(Blocks.GLOWSTONE, 2), book.match(new short[] {G, S, R, A}, 2), "mirrored");
        assertEquals(new RecipeBook.Result(Blocks.OAK_SLAB, 1), book.match(new short[] {OAK, OAK, A, A}, 2), "overridden");
        assertNull(book.match(new short[] {A, A, A, A, A, A, OAK, OAK, OAK}, 3), "the old 3-plank slab recipe is gone");
        assertTrue(book.warnings.stream().anyMatch(w -> w.contains("diamond_pickaxe")), "non-block items are reported");
    }

    @Test
    void dataPackFolderIsListed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws java.io.IOException {
        java.nio.file.Path recipe = dir.resolve("data/custom/recipe/stone_from_dirt.json");
        java.nio.file.Files.createDirectories(recipe.getParent());
        java.nio.file.Files.writeString(recipe, "{\"type\":\"minecraft:crafting_shapeless\","
            + "\"ingredients\":[\"minecraft:dirt\",\"minecraft:dirt\"],\"result\":{\"id\":\"minecraft:stone\"}}");
        RecipeBook book = RecipeBook.load(List.of(PackSource.open(dir)));
        assertEquals(new RecipeBook.Result(Blocks.STONE, 1), book.match(new short[] {Blocks.DIRT, A, A, Blocks.DIRT}, 2));
    }

    @Test
    void menuClicksFollowVanillaRules() {
        Inventory inv = new Inventory();
        inv.add(Blocks.DIRT, 10);   // slot 0
        inv.add(Blocks.STONE, 5);   // slot 1
        CraftingMenu m = new CraftingMenu(inv, builtIn(), 2);
        m.click(0, true, false);    // right: half of 10
        assertEquals(5, m.cursorCount);
        assertEquals(5, inv.counts[0]);
        m.click(20, true, false);   // right on an empty slot: put one
        assertEquals(1, inv.counts[20]);
        m.click(0, false, false);   // left on the same item: merge back
        assertEquals(9, inv.counts[0]);
        m.click(20, false, false);  // pick up the single dirt
        m.click(1, false, false);   // left on stone: swap
        assertEquals(Blocks.DIRT, inv.id(1));
        assertEquals(Blocks.STONE, m.cursorId);
        m.click(1, false, false);   // swap back
        m.click(20, false, false);  // put the dirt down
        assertEquals(0, m.cursorCount);
        m.click(1, false, true);    // shift: hotbar -> main inventory
        assertEquals(0, inv.counts[1]);
        assertEquals(Blocks.STONE, inv.id(9));
    }

    @Test
    void craftingConsumesTheGridAndShiftCraftsAll() {
        Inventory inv = new Inventory();
        inv.add(LOG, 3);
        CraftingMenu m = new CraftingMenu(inv, builtIn(), 2);
        m.click(0, false, false);              // pick up 3 logs
        m.click(m.gridStart() + 3, false, false); // into the grid
        assertEquals(OAK, m.id(m.resultSlot()));
        m.click(m.resultSlot(), false, false); // take 4 planks on the cursor
        assertEquals(4, m.cursorCount);
        assertEquals(2, m.count(m.gridStart() + 3));
        m.click(m.resultSlot(), false, true);  // shift: craft the rest into the inventory
        assertEquals(0, m.count(m.gridStart() + 3));
        assertEquals(Blocks.AIR, m.id(m.resultSlot()));
        assertEquals(8, inv.counts[0]);
        assertEquals(0, m.close());            // the held 4 planks go back
        assertEquals(12, inv.counts[0]);
    }
}
