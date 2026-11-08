package net.mynecraft.world.life;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Growth;
import net.mynecraft.world.Landscape;
import net.mynecraft.world.World;
import net.mynecraft.world.actor.Flows;
import net.mynecraft.world.actor.Matter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The water slice: pollution poured, carried downstream, filtered by reeds, cleaned with time; the trout's answer. */
class WaterSliceTest {
    private static final int TROUT = Species.TROUT.ordinal();

    private static Ecology river() {
        Landscape.Column col = new Landscape.Column(70, Landscape.FOREST, 75, 1, 0.5f);
        return new Ecology(42, new Ecosystem(), (x, z) -> col);
    }

    @Test
    void pollutionCleansItselfInDays() {
        Ecology e = river();
        Ecology.Patch p = e.patch(0, 0);
        p.pollution = 0.8;
        e.advance(3);
        assertTrue(p.pollution < 0.6 && p.pollution > 0.1, "after three days: " + p.pollution);
        e.advance(30);
        assertTrue(p.pollution < 0.02, "after a month: " + p.pollution);
    }

    @Test
    void reedsCleanFasterAndFilterWhatIsPoured() {
        Ecology bare = river(), reedy = river();
        Ecology.Patch a = bare.patch(0, 0), b = reedy.patch(0, 0);
        b.cleaners = 20;
        a.pollution = b.pollution = 0.6;
        bare.advance(2);
        reedy.advance(2);
        assertTrue(b.pollution < a.pollution * 0.8, "reeds made no difference: " + a.pollution + " vs " + b.pollution);
        // Ten reeds let half the waste water through downstream.
        Ecology.Patch c = reedy.patch(1, 0);
        c.cleaners = 10;
        assertEquals(0.5, Ecology.pollute(c, 1) / 1, 1e-9);
        Ecology.Patch d = reedy.patch(2, 0);
        assertEquals(1, Ecology.pollute(d, 1), 1e-9);
    }

    @Test
    void wasteWaterFoulsThePatchAndGoesDownstream() {
        Ecology e = river();
        Ecology.Patch here = e.patch(0, 0), below = e.patch(1, 0);
        Flows flows = new Flows(e);
        flows.river = (x, z) -> 64 - x / 64.0; // runs east
        flows.day = 4;
        for (int i = 0; i < 5; i++) flows.give(Matter.WASTEWATER, 1, 10, 10, "Moi");
        assertTrue(here.pollution > 0.3, "the tannery's patch: " + here.pollution);
        assertTrue(below.pollution > 0.05 && below.pollution < here.pollution, "downstream: " + below.pollution);
        Flows.Source s = flows.pollutedBy(70, 10);
        assertNotNull(s);
        assertEquals("Moi", s.who());
        assertEquals(10, s.x(), 1e-9);
        assertEquals(4, s.day(), 1e-9);
    }

    @Test
    void troutStopSpawningInFouledWaterAndComeBackWhenItClears() {
        Ecology e = river();
        Ecology.Patch p = e.patch(0, 0);
        e.patch(-1, 0); // clean water upstream and downstream, where trout can come back from
        e.patch(1, 0);
        e.advance(Ecosystem.YEAR_DAYS); // a settled river
        double before = p.pop[TROUT];
        assertTrue(before > 1, "no trout to begin with: " + before);
        for (int d = 0; d < 20; d++) { // a tannery pouring every day
            Ecology.pollute(p, 0.4);
            e.advance(1);
            assertTrue(p.pollution < 1.0001);
        }
        assertTrue(p.spawnStopped, "the trout still spawn at " + p.pollution);
        double fouled = p.pop[TROUT];
        assertTrue(fouled < before * 0.6, "trout unharmed: " + before + " -> " + fouled);
        e.advance(10);
        assertFalse(p.spawnStopped, "still no spawning at " + p.pollution);
        e.advance(Ecosystem.YEAR_DAYS);
        assertTrue(p.pop[TROUT] > fouled * 1.5, "trout did not come back: " + fouled + " -> " + p.pop[TROUT]);
    }

    @Test
    void pollutionIsSavedAndOldSavesStillLoad() throws Exception {
        Ecology e = river();
        Ecology.Patch p = e.patch(0, 0);
        p.pollution = 0.42;
        p.spawnStopped = true;
        Path dir = Files.createTempDirectory("water");
        e.save(dir.resolve("ecology.bin"));
        Ecology back = river();
        assertTrue(back.load(dir.resolve("ecology.bin")));
        Ecology.Patch q = back.existing(1, 1);
        assertEquals(0.42, q.pollution, 1e-12);
        assertTrue(q.spawnStopped);

        Flows f = new Flows(e);
        f.give(Matter.WASTEWATER, 1, 1, 1, "Corentin");
        f.save(dir.resolve("flows.bin"));
        Flows g = new Flows(null);
        g.load(dir.resolve("flows.bin"));
        assertEquals("Corentin", g.pollutedBy(5, 5).who());
        assertEquals(1, g.existing(0, 0).returned[Matter.WASTEWATER.ordinal()], 1e-12);
    }

    @Test
    void reedsGrowOnWetBanksAndCountAsCleaners() {
        World w = new World(0);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) w.getOrGenerateEmpty(cx, cz);
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) w.set(x, 60, z, Blocks.GRASS);
        w.set(0, 60, 0, Blocks.WATER);
        assertTrue(Growth.reedGround(w, 1, 60, 0));
        assertFalse(Growth.reedGround(w, 8, 60, 8), "dry ground far from the water");
        w.set(1, 61, 0, Blocks.REEDS);
        for (int i = 0; i < 12; i++) w.set(2 + i % 4, 64 + i / 4, 5, Blocks.WILLOW_LEAVES);
        assertEquals(1 + 12 / Riverbank.LEAVES_PER_REED, Riverbank.count(w.chunk(0, 0)), 1e-9);
        Ecology e = river();
        Riverbank bank = new Riverbank();
        for (int i = 0; i < 3; i++) bank.update(w, e);
        assertEquals(3, e.patch(0, 0).cleaners, 1e-9);
        w.set(-2, 61, 0, Blocks.REEDS); // another chunk, another patch: counted again once edited
        bank.update(w, e);
        assertEquals(1, e.patch(-1, 0).cleaners, 1e-9);
    }

    @Test
    void theRiversProjectIsAccomplishedAfterThreeCleanDays() throws Exception {
        net.mynecraft.world.actor.Projects pr = new net.mynecraft.world.actor.Projects();
        Ecology e = river();
        Ecology.Patch p = e.patch(0, 0);
        p.pop[TROUT] = 5;
        assertNotNull(pr.proposeRiver(10, 10));
        assertNull(pr.proposeRiver(10, 10), "proposed twice");
        assertFalse(pr.river(p, 5), "followed before being accepted");
        assertNotNull(pr.acceptNext());
        p.pollution = 0.3;
        assertFalse(pr.river(p, 5));
        p.pollution = 0.02;
        assertFalse(pr.river(p, 2));
        p.pop[TROUT] = 0.5; // the trout gone: the streak starts again
        assertFalse(pr.river(p, 2));
        p.pop[TROUT] = 5;
        assertFalse(pr.river(p, 2));
        assertTrue(pr.river(p, 1.5));
        assertEquals(net.mynecraft.world.actor.Projects.State.DONE, pr.get("A2").state);
        Path f = Files.createTempDirectory("proj").resolve("projects.json");
        pr.save(f);
        net.mynecraft.world.actor.Projects back = new net.mynecraft.world.actor.Projects();
        back.load(f);
        assertEquals(net.mynecraft.world.actor.Projects.State.DONE, back.get("A2").state);
        assertEquals(10, back.get("A2").x, 1e-9);
    }

    @Test
    void thePurestRiverIsTheCleanestAndLiveliest() {
        Ecology e = river();
        Ecology.Patch a = e.patch(0, 0), b = e.patch(1, 0), c = e.patch(2, 0);
        a.aquatic = b.aquatic = c.aquatic = 0.8;
        a.pop[TROUT] = 6; b.pop[TROUT] = 2; c.pop[TROUT] = 8;
        c.pollution = 0.3; // the liveliest, but fouled
        assertSame(a, e.purest());
        a.pollution = b.pollution = c.pollution = 0.5;
        assertNull(e.purest());
    }

    @Test
    void theWindCarriesTheScent() {
        // Wind blowing east at 5 m/s: an animal east of the player (downwind) smells it from farther.
        double down = Life.scent(0, 0, 10, 0, 5, 0), up = Life.scent(0, 0, -10, 0, 5, 0), side = Life.scent(0, 0, 0, 10, 5, 0);
        assertEquals(1.8, down, 1e-9);
        assertEquals(0.7, up, 1e-9);
        assertEquals(1, side, 1e-9);
        assertEquals(1, Life.scent(0, 0, 10, 0, 0, 0), 1e-9); // calm
        assertEquals(1.4, Life.scent(0, 0, 10, 0, 2, 0), 1e-9); // a breeze counts half
    }
}
