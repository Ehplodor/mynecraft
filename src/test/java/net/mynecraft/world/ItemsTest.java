package net.mynecraft.world;

import net.mynecraft.resource.PackSource;
import net.mynecraft.world.crafting.RecipeBook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ItemsTest {
    @Test
    void blockItemsKeepTheirBlockIdAndPureItemsFollow() {
        assertEquals(Blocks.STONE, Items.fromName("minecraft:stone"));
        assertTrue(Items.isBlock(Blocks.STONE));
        assertEquals(Items.STICK, Items.fromName("stick"));
        assertFalse(Items.isBlock(Items.STICK));
        assertTrue(Items.STICK >= Blocks.COUNT);
        assertEquals("stick", Items.name(Items.STICK));
        assertEquals(-1, Items.fromName("minecraft:nothing_like_this"));
        assertEquals(-1, Items.fromName("othermod:stick"));
    }

    @Test
    void planksMakeSticks() {
        RecipeBook book = RecipeBook.load(List.of(PackSource.classpath("defaultdata")));
        assertEquals(List.of(), book.warnings);
        short a = Blocks.AIR, p = Blocks.OAK_PLANKS;
        assertEquals(new RecipeBook.Result(Items.STICK, 4), book.match(new short[] {p, a, p, a}, 2));
    }

    @Test
    void registryFreezes() {
        Registry r = new Registry("test");
        assertEquals(0, r.register("a"));
        assertEquals(1, r.register("b"));
        assertEquals(0, r.register("a"));
        r.freeze();
        assertEquals(1, r.id("b"));
        assertThrows(IllegalStateException.class, () -> r.register("c"));
    }
}
