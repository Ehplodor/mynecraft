package net.mynecraft.world.actor;

import net.mynecraft.resource.PackSource;
import net.mynecraft.world.Blocks;
import net.mynecraft.world.Drops;
import net.mynecraft.world.Inventory;
import net.mynecraft.world.Items;
import net.mynecraft.world.World;
import net.mynecraft.world.build.Blueprint;
import net.mynecraft.world.crafting.CraftingMenu;
import net.mynecraft.world.crafting.RecipeBook;
import net.mynecraft.world.crafting.Workshop;
import net.mynecraft.world.people.PathFinder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/** The common foundations of M27..M34: skills, needs, tools, the recipe grammar, flows, plans, routes. */
class LivelihoodTest {
    private static final RecipeBook BOOK = RecipeBook.load(List.of(PackSource.classpath("defaultdata")));

    private static World flat() {
        World w = new World(0);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) w.getOrGenerateEmpty(cx, cz);
        for (int x = -16; x < 32; x++) for (int z = -16; z < 32; z++) w.set(x, 9, z, Blocks.STONE);
        return w;
    }

    @Test
    void theDataPackLoadsCleanlyWithStationRecipes() {
        assertEquals(List.of(), BOOK.warnings);
        assertFalse(BOOK.at("campfire").isEmpty());
        assertFalse(BOOK.at("millstone").isEmpty());
        assertFalse(BOOK.at("crafting_table").isEmpty());
    }

    @Test
    void practiceRaisesLevelsTitlesAndAbilitiesAndIdleSkillsBlunt() {
        Skills s = new Skills();
        assertEquals("", s.roleTitle());
        for (int i = 0; i < 100; i++) s.practice(Skill.COOKING, 1);
        assertEquals(4, s.level(Skill.COOKING)); // sqrt(100 / 5)
        assertEquals("compagnon cuisinier", s.roleTitle());
        assertTrue(s.ability[Ability.PATIENCE.ordinal()] > 0.3);
        double before = s.xp[Skill.COOKING.ordinal()];
        s.rust(10);
        assertTrue(s.xp[Skill.COOKING.ordinal()] < before);
        s.taughtUntil[Skill.FISHING.ordinal()] = 11;
        s.practice(Skill.FISHING, 1);
        assertEquals(2, s.xp[Skill.FISHING.ordinal()], 1e-9, "a lesson doubles practice");
    }

    @Test
    void variedMealsNourishMoreThanTheSameDish() {
        Needs a = new Needs(), b = new Needs();
        a.food = b.food = 0;
        for (int i = 0; i < 4; i++) a.eat(Items.COOKED_TROUT);
        b.eat(Items.COOKED_TROUT);
        b.eat(Items.APPLE);
        b.eat(Items.BREAD);
        b.eat(Items.COOKED_VENISON);
        assertTrue(a.nourishment(Items.COOKED_TROUT) < b.nourishment(Items.COOKED_TROUT));
        assertTrue(b.variety() > a.variety());
        Needs c = new Needs();
        c.food = 0;
        assertTrue(c.tick(30, 0, 15, false) >= 0);
        int hurt = 0;
        for (int i = 0; i < 10; i++) hurt += c.tick(1, 0, 15, false);
        assertTrue(hurt > 0, "starving hurts");
    }

    @Test
    void toolsSpeedUpAndHarvestAndWearOut() {
        Actor a = new Actor("t", new Inventory());
        double hand = Gestures.breakSeconds(a, Blocks.STONE, Blocks.AIR);
        double pick = Gestures.breakSeconds(a, Blocks.STONE, Items.STONE_PICKAXE);
        assertTrue(pick < hand / 3);
        assertFalse(Items.canHarvest(Blocks.AIR, Blocks.STONE), "stone by hand gives nothing, like vanilla");
        assertFalse(Items.canHarvest(Items.WOODEN_PICKAXE, Blocks.IRON_ORE));
        assertTrue(Items.canHarvest(Items.STONE_PICKAXE, Blocks.IRON_ORE));
        a.inventory.add(Items.WOODEN_PICKAXE, 1);
        Flows flows = new Flows(null);
        List<Drops.Drop> d = Gestures.broke(a, Blocks.STONE, 0, 0, 0, 0, flows, new SplittableRandom(1));
        assertEquals(List.of(new Drops.Drop(Blocks.COBBLESTONE, 1)), d);
        assertEquals(1, flows.at(0, 0).taken[Matter.STONE.ordinal()], 1e-9);
        assertEquals(1, a.inventory.wear[0]);
        for (int i = 0; i < Items.DURABILITY[Items.WOODEN_PICKAXE]; i++) a.inventory.wearOut(0);
        assertEquals(Blocks.AIR, a.inventory.id(0), "a worn-out tool breaks");
        assertTrue(a.skills.xp[Skill.MINING.ordinal()] > 0);
    }

    @Test
    void aWornToolIsRepairedWithItsMaterial() {
        Inventory inv = new Inventory();
        CraftingMenu m = new CraftingMenu(inv, BOOK, 2);
        inv.add(Items.WOODEN_PICKAXE, 1);
        inv.wear[0] = 50;
        inv.add(Blocks.BIRCH_PLANKS, 2);
        m.click(0, false, false);
        m.click(m.gridStart(), false, false);
        m.click(1, false, false);
        m.click(m.gridStart() + 1, true, false); // one plank
        assertEquals(Items.WOODEN_PICKAXE, m.id(m.resultSlot()));
        assertEquals(35, m.wear(m.resultSlot())); // 50 - 60 / 4
        m.click(m.resultSlot(), false, true);
        assertEquals(1, inv.count(Items.WOODEN_PICKAXE));
    }

    @Test
    void cookingTakesFuelReturnsAshAndTrains() {
        Actor a = new Actor("t", new Inventory());
        Flows flows = new Flows(null);
        RecipeBook.Process roast = BOOK.at("campfire").stream().filter(p -> p.id().endsWith("cooked_venison")).findFirst().orElseThrow();
        a.inventory.add(Items.VENISON, 1);
        assertEquals("combustible", Workshop.missing(a, roast, 0));
        a.inventory.add(Items.STICK, 3);
        assertNull(Workshop.missing(a, roast, 0));
        assertTrue(Workshop.make(a, roast, 0, flows));
        assertEquals(1, a.inventory.count(Items.COOKED_VENISON));
        assertEquals(1, a.inventory.count(Items.STICK)); // two sticks of heat
        assertTrue(flows.at(0, 0).returned[Matter.ASH.ordinal()] > 0);
        assertTrue(a.skills.xp[Skill.COOKING.ordinal()] > 0);
        RecipeBook.Process flour = BOOK.at("millstone").get(0);
        assertTrue(Workshop.seconds(a, flour, 0) > Workshop.seconds(a, flour, 5) * 2.5, "by hand is slower than by a wheel");
    }

    @Test
    void aPlanIsTracedBuiltStepByStepAndSaved() {
        World w = flat();
        Blueprint hut = Blueprint.hut();
        assertTrue(hut.materials().get(Blocks.OAK_PLANKS) > 10);
        int[] step = hut.next(w, 0, 10, 0);
        assertArrayEquals(new int[] {0, 10, 0, Blocks.COBBLESTONE}, step);
        for (int i = 0; i < 500 && (step = hut.next(w, 0, 10, 0)) != null; i++) w.set(step[0], step[1], step[2], (short) step[3]);
        assertNull(hut.next(w, 0, 10, 0), "built");
        Blueprint traced = Blueprint.trace("copie", w, 0, 10, 0, 4, 14, 5);
        assertEquals(hut.materials(), traced.materials());
        Blueprint back = Blueprint.fromJson(traced.toJson());
        assertArrayEquals(traced.cells, back.cells);
    }

    @Test
    void routesStepUpAndGoAroundWalls() {
        World w = flat();
        for (int z = -16; z < 32; z++) w.set(5, 10, z, Blocks.STONE); // a step across the whole floor
        for (int z = -5; z < 6; z++) for (int y = 10; y < 13; y++) w.set(10, y, z, Blocks.STONE); // a wall to go round
        List<int[]> path = PathFinder.find(w, 0, 10, 0, 14, 10, 0, 4000);
        assertFalse(path.isEmpty());
        int[] end = path.get(path.size() - 1);
        assertEquals(14, end[0]);
        assertEquals(0, end[2]);
        assertTrue(path.stream().anyMatch(c -> c[1] == 11), "climbs the step");
        assertTrue(path.stream().anyMatch(c -> Math.abs(c[2]) >= 6), "goes round the wall");
    }
}
