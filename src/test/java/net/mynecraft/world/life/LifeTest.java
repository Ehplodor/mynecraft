package net.mynecraft.world.life;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Animal behaviour on a small hand-built world: a grass field with a pond in the middle. */
class LifeTest {
    private static final int FLOOR = 63, POND = 8; // pond: x, z in [-POND, POND), 3 deep

    /** Written straight into the chunks: no light needed here, and lighting 200 000 edits is slow. */
    private static void put(World w, int x, int y, int z, short b) {
        w.chunk(x >> 4, z >> 4).set(x & 15, y, z & 15, b);
    }

    private static World field() {
        World w = new World(7);
        for (int cx = -5; cx < 5; cx++)
            for (int cz = -5; cz < 5; cz++) w.getOrGenerateEmpty(cx, cz);
        for (int x = -80; x < 80; x++)
            for (int z = -80; z < 80; z++) {
                for (int y = 55; y < FLOOR; y++) put(w, x, y, z, Blocks.STONE);
                boolean pond = x >= -POND && x < POND && z >= -POND && z < POND;
                if (pond) for (int y = FLOOR - 3; y <= FLOOR; y++) put(w, x, y, z, Blocks.WATER);
                else put(w, x, FLOOR, z, Blocks.GRASS);
            }
        return w;
    }

    /** Behaviour only: a plains climate everywhere and no newcomers. */
    private static Life life(World w, long seed) {
        Life life = new Life(w, seed);
        net.mynecraft.world.Landscape.Column plains = new net.mynecraft.world.Landscape.Column(FLOOR, net.mynecraft.world.Landscape.PLAINS, 0, 0, 0.5f);
        life.columns = (x, z) -> plains;
        life.spawning = false;
        return life;
    }

    private static void run(Life life, double seconds, double px, double pz) {
        for (double t = 0; t < seconds; t += 0.05) life.tick(0.05, px, FLOOR + 1, pz, false);
    }

    @Test
    void landAnimalsStayOnLandAndOnTheGround() {
        World w = field();
        Life life = life(w, 1);
        Creature deer = life.spawn(Species.DEER, 20.5, FLOOR + 1, 0.5);
        Creature rabbit = life.spawn(Species.RABBIT, -20.5, FLOOR + 1, 3.5);
        int wet = 0;
        for (int i = 0; i < 1200; i++) { // a minute, player far away
            life.tick(0.05, 70, FLOOR + 1, 70, false);
            for (Creature c : new Creature[] {deer, rabbit}) {
                boolean inPond = c.x >= -POND && c.x < POND && c.z >= -POND && c.z < POND;
                if (inPond && c.y < FLOOR + 0.5) wet++;
                assertTrue(c.y > FLOOR - 4, c.species + " fell through the ground");
            }
        }
        assertTrue(wet < 40, "land animals walked into the pond: " + wet + " steps");
        assertTrue(Math.hypot(deer.x - 20.5, deer.z - 0.5) > 0.5 || deer.state == Creature.State.GRAZE
            || deer.state == Creature.State.IDLE, "the deer never moved");
    }

    @Test
    void fishStayInTheWater() {
        World w = field();
        Life life = life(w, 2);
        Creature[] fish = new Creature[4];
        for (int i = 0; i < fish.length; i++) fish[i] = life.spawn(Species.TROUT, -3.5 + i * 2, FLOOR - 1.5, 0.5);
        run(life, 60, 70, 70);
        for (Creature f : fish) {
            assertFalse(f.dead || f.dying > 0, "a trout died");
            assertTrue(f.inWater, "a trout left the water at " + f.x + " " + f.y + " " + f.z);
        }
    }

    @Test
    void shyAnimalsFleeThePlayer() {
        World w = field();
        Life life = life(w, 3);
        Creature deer = life.spawn(Species.DEER, 30.5, FLOOR + 1, 30.5);
        double before = Math.hypot(deer.x - 26, deer.z - 26);
        run(life, 3, 26, 26);
        assertTrue(Math.hypot(deer.x - 26, deer.z - 26) > before + 5, "the deer did not run away");
    }

    @Test
    void foxesHuntRabbits() {
        World w = field();
        Life life = life(w, 4);
        Creature fox = life.spawn(Species.FOX, 30.5, FLOOR + 1, -30.5);
        Creature rabbit = life.spawn(Species.RABBIT, 36.5, FLOOR + 1, -30.5);
        fox.hunger = 0.9;
        life.eco.update(18000, 0.1f, 24000); // night: the fox is out
        run(life, 30, 30.5, 0); // the player 30 blocks away: unnoticed
        assertTrue(rabbit.dead || rabbit.dying > 0, "the rabbit escaped");
        assertFalse(fox.dead, "the fox is still there");
        assertTrue(fox.hunger < 0.2, "the fox did not eat");
        assertEquals(1, life.eaten);
    }

    @Test
    void fedAdultsRaiseYoung() {
        World w = field();
        Life life = life(w, 5);
        Creature a = life.spawn(Species.RABBIT, -40.5, FLOOR + 1, 40.5), b = life.spawn(Species.RABBIT, -39.5, FLOOR + 1, 40.5);
        a.breedCooldown = b.breedCooldown = 0;
        a.hunger = b.hunger = 0;
        run(life, 2, -40.5, 10);
        assertTrue(life.born >= 1, "no young");
        Creature young = life.creatures.stream().filter(c -> !c.adult()).findFirst().orElseThrow();
        assertTrue(young.scale < 0.7f, "the young are smaller");
        assertTrue(a.breedCooldown > 0, "parents rest between litters");
    }

    @Test
    void theStruckAnimalDiesAndIsRemoved() {
        World w = field();
        Life life = life(w, 6);
        Creature rabbit = life.spawn(Species.RABBIT, 40.5, FLOOR + 1, 40.5);
        assertSame(rabbit, life.raycast(40.5, FLOOR + 1.3, 36, 0, 0, 1, 6));
        life.strike(rabbit, 100, 40.5, 36);
        run(life, 3, 70, 70);
        assertTrue(rabbit.dead);
        assertFalse(life.creatures.contains(rabbit));
    }

    @Test
    void seasonsAndHabitats() {
        Ecosystem eco = new Ecosystem();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int d = 0; d < Ecosystem.YEAR_DAYS; d++) names.add(Ecosystem.seasonAt(d).name());
        assertEquals(4, names.size());
        assertTrue(Ecosystem.seasonAt(Ecosystem.YEAR_DAYS * 0.8).vegetation() < Ecosystem.seasonAt(Ecosystem.YEAR_DAYS * 0.3).vegetation(),
            "less to eat in winter than in summer");
        assertEquals(0, eco.capacity(Species.TROUT, net.mynecraft.world.Landscape.PLAINS, 0.5f));
        assertTrue(eco.capacity(Species.RABBIT, net.mynecraft.world.Landscape.PLAINS, 0.5f) > eco.capacity(Species.FOX,
            net.mynecraft.world.Landscape.FOREST, 0.5f), "prey outnumber predators");
        assertEquals(0xEDEFF2, Species.RABBIT.coat(0.1f, 1, 0.5), "white mountain hare in the snow");
    }
}
