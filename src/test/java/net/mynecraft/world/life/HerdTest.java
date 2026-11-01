package net.mynecraft.world.life;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Growth;
import net.mynecraft.world.Inventory;
import net.mynecraft.world.Items;
import net.mynecraft.world.World;
import net.mynecraft.world.actor.Actor;
import net.mynecraft.world.actor.Gestures;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/** Husbandry on a flat grass field: pens, keeping animals in, breeding by the grass, laying, the hive. */
class HerdTest {
    private static final int FLOOR = 63;

    private static void put(World w, int x, int y, int z, short b) {
        w.chunk(x >> 4, z >> 4).set(x & 15, y, z & 15, b);
    }

    private static World field() {
        World w = new World(7);
        for (int cx = -4; cx < 4; cx++)
            for (int cz = -4; cz < 4; cz++) w.getOrGenerateEmpty(cx, cz);
        for (int x = -64; x < 64; x++)
            for (int z = -64; z < 64; z++) {
                for (int y = 58; y < FLOOR; y++) put(w, x, y, z, Blocks.STONE);
                put(w, x, FLOOR, z, Blocks.GRASS);
            }
        return w;
    }

    /** A square of fence around [x0, x1] x [z0, z1] (the inside), one block high on the grass. */
    private static void pen(World w, int x0, int z0, int x1, int z1) {
        for (int x = x0 - 1; x <= x1 + 1; x++) { put(w, x, FLOOR + 1, z0 - 1, Blocks.OAK_FENCE); put(w, x, FLOOR + 1, z1 + 1, Blocks.OAK_FENCE); }
        for (int z = z0 - 1; z <= z1 + 1; z++) { put(w, x0 - 1, FLOOR + 1, z, Blocks.OAK_FENCE); put(w, x1 + 1, FLOOR + 1, z, Blocks.OAK_FENCE); }
    }

    private static Life life(World w) {
        Life life = new Life(w, 3);
        var plains = new net.mynecraft.world.Landscape.Column(FLOOR, net.mynecraft.world.Landscape.PLAINS, 0, 0, 0.5f);
        life.columns = (x, z) -> plains;
        life.spawning = false;
        return life;
    }

    private static void run(Life life, double seconds) {
        for (double t = 0; t < seconds; t += 0.05) life.tick(0.05, 50, FLOOR + 1, 50, false);
    }

    @Test
    void aFencedSquareIsAPenAndOpenLandIsNot() {
        World w = field();
        pen(w, 0, 0, 5, 5);
        Pasture.Pen p = Pasture.measure(w, 2.5, FLOOR + 1, 2.5);
        assertNotNull(p, "a closed fence should make a pen");
        assertEquals(36, p.cells());
        assertEquals(36, p.grass());
        assertNull(Pasture.measure(w, 20.5, FLOOR + 1, 20.5), "the open field is no pen");
        put(w, 0, FLOOR + 1, -1, Blocks.OAK_DOOR_OPEN); // an open gate lets the flock out
        assertNull(Pasture.measure(w, 2.5, FLOOR + 1, 2.5));
        put(w, 0, FLOOR + 1, -1, Blocks.OAK_DOOR);
        assertNotNull(Pasture.measure(w, 2.5, FLOOR + 1, 2.5), "a closed gate shuts the pen again");
    }

    @Test
    void keptAnimalsStayInTheirPenAndAreNotLeftBehind() {
        World w = field();
        pen(w, 0, 0, 5, 5);
        Life life = life(w);
        Creature sheep = life.keepNew(Species.SHEEP, 2.5, FLOOR + 1, 2.5, "joueur", false);
        Creature goat = life.keepNew(Species.GOAT, 3.5, FLOOR + 1, 3.5, "joueur", false);
        for (int i = 0; i < 2400; i++) { // two minutes, the player far beyond the distance wild animals are kept within
            life.tick(0.05, 2000, FLOOR + 1, 2000, false);
            for (Creature c : new Creature[] {sheep, goat})
                assertTrue(c.x > -0.1 && c.x < 6.1 && c.z > -0.1 && c.z < 6.1, c.name() + " left the pen at " + c.x + ", " + c.z);
        }
        assertFalse(sheep.dead || goat.dead, "kept animals are never dropped for being far from the player");
        assertEquals(2, life.kept().size());
    }

    @Test
    void breedingWaitsForGrassForOneMore() {
        World w = field();
        pen(w, 0, 0, 3, 3); // 16 cells of grass: two sheep (8 each) and no room for a lamb
        Life life = life(w);
        Creature a = life.keepNew(Species.SHEEP, 1.5, FLOOR + 1, 1.5, "joueur", false);
        Creature b = life.keepNew(Species.SHEEP, 2.5, FLOOR + 1, 2.5, "joueur", false);
        run(life, 1);
        a.love = b.love = 30;
        a.breedCooldown = b.breedCooldown = 0;
        run(life, 5);
        assertEquals(2, life.kept().size(), "a lamb was born in a pen without the grass for it");
        assertTrue(life.notes.stream().anyMatch(n -> n.contains("herbe")), "the player is told why");

        World big = field();
        pen(big, 0, 0, 5, 5); // 36 cells: room for three
        Life life2 = life(big);
        Creature c = life2.keepNew(Species.SHEEP, 1.5, FLOOR + 1, 1.5, "joueur", false);
        Creature d = life2.keepNew(Species.SHEEP, 2.5, FLOOR + 1, 2.5, "joueur", false);
        run(life2, 1);
        c.love = d.love = 30;
        c.breedCooldown = d.breedCooldown = 0;
        run(life2, 5);
        assertEquals(3, life2.kept().size(), "fed adults in a roomy pen should have a lamb");
        Creature lamb = life2.kept().stream().filter(x -> !x.adult()).findFirst().orElseThrow();
        assertTrue(lamb.tame && "joueur".equals(lamb.owner));
    }

    @Test
    void anOverstockedPenIsGrazedBare() {
        World w = field();
        pen(w, 0, 0, 2, 2); // 9 cells for four sheep
        Life life = life(w);
        for (int i = 0; i < 4; i++) life.keepNew(Species.SHEEP, 0.5 + i % 3, FLOOR + 1, 0.5 + i / 3, "joueur", false).hunger = 0.9;
        run(life, 240);
        int bare = 0;
        for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) if (w.get(x, FLOOR, z) == Blocks.DIRT) bare++;
        assertTrue(bare > 0, "too many sheep should trample the grass to earth");
    }

    @Test
    void aHenLaysInTheNestAndTheEggHatches() {
        World w = field();
        pen(w, 0, 0, 5, 5);
        put(w, 4, FLOOR + 1, 4, Blocks.NEST);
        Life life = life(w);
        Creature hen = life.keepNew(Species.HEN, 1.5, FLOOR + 1, 1.5, "joueur", false);
        hen.produce = 1;
        hen.hunger = 0;
        run(life, 40);
        assertEquals(Blocks.NEST_EGG, w.get(4, FLOOR + 1, 4), "the hen should have laid in the nest");
        assertEquals(0, hen.produce, 1e-9);
        Growth g = new Growth();
        g.planted(4, FLOOR + 1, 4);
        g.grow(w, life.ecology, null, 0.4, 2.1);
        assertEquals(Blocks.NEST, w.get(4, FLOOR + 1, 4));
        assertEquals(1, g.hatched.size(), "a brooded egg hatches in two days");
    }

    @Test
    void theHamletsHensLayForItsStore() {
        World w = field();
        Life life = life(w);
        Creature hen = life.keepNew(Species.HEN, 10.5, FLOOR + 1, 10.5, "hameau", false);
        hen.produce = 1;
        run(life, 1);
        assertEquals(1, life.hamletEggs);
    }

    @Test
    void tamingTakesTheAnimalFromTheWildAndTheHerdIsSaved(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        World w = field();
        Life life = life(w);
        Ecology.Patch p = life.ecology.patch(0, 0);
        p.pop[Species.SHEEP.ordinal()] = 3;
        Creature mouflon = life.spawn(Species.SHEEP, 10.5, FLOOR + 1, 10.5);
        assertFalse(mouflon.tame);
        assertEquals("Mouflon", mouflon.name());
        life.tame(mouflon, "joueur");
        assertEquals("Mouton", mouflon.name());
        assertEquals(2, p.pop[Species.SHEEP.ordinal()], 1e-9, "one less mouflon in the wild");
        mouflon.shorn = true;
        mouflon.produce = 0.4;
        life.saveHerd(dir.resolve("herd.json"));
        Life again = life(w);
        again.loadHerd(dir.resolve("herd.json"));
        assertEquals(1, again.kept().size());
        Creature back = again.kept().get(0);
        assertEquals(Species.SHEEP, back.species);
        assertTrue(back.shorn);
        assertEquals(0.4, back.produce, 1e-9);
        assertEquals(10.5, back.x, 1e-9);
    }

    @Test
    void aHiveDrawsASwarmMakesHoneyAndWinterHarvestKillsIt() {
        World w = field();
        Life life = life(w);
        put(w, 5, FLOOR + 1, 5, Blocks.BEEHIVE);
        Ecology.Patch p = life.ecology.patch(0, 0);
        p.flowers = 0.5;
        Growth g = new Growth();
        g.planted(5, FLOOR + 1, 5);
        g.grow(w, life.ecology, null, 0.4, 1.2);
        assertEquals(Blocks.BEEHIVE_BEES, w.get(5, FLOOR + 1, 5), "flowers draw a wild swarm");
        p.flowers = 0.5;
        g.grow(w, life.ecology, null, 0.4, 3.5);
        assertEquals(Blocks.BEEHIVE_HONEY, w.get(5, FLOOR + 1, 5), "the colony makes honey from the flowers");
        Actor me = new Actor("Moi", new Inventory());
        assertEquals(Gestures.Use.HIVE_LOST, Gestures.use(me, w, 0, 5, FLOOR + 1, 5, false, false, null, g, new SplittableRandom(1), 0.85));
        assertEquals(Blocks.BEEHIVE, w.get(5, FLOOR + 1, 5), "honey taken in winter starves the bees");
        assertTrue(me.inventory.count(Items.HONEY) > 0);
    }
}
