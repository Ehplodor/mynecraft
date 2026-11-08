package net.mynecraft.world.life;

import net.mynecraft.world.Landscape;
import net.mynecraft.world.actor.Flows;
import net.mynecraft.world.actor.Matter;
import net.mynecraft.world.actor.Projects;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E9.1, the water slice's balance on a simulated game: a tannery working a season (8 days, three hides
 * a day) upstream of the fisher hurts the river without killing it, and bringing it back to life takes
 * one to two seasons, sooner with reeds.
 */
class WaterBalanceTest {
    private static final int TROUT = Species.TROUT.ordinal();

    /** {min trout during the season, spawning stopped (1/0), days to accomplish A2}. */
    private static double[] season(double reeds) {
        Landscape.Column col = new Landscape.Column(70, Landscape.FOREST, 75, 1, 0.5f);
        Ecology e = new Ecology(42, new Ecosystem(), (x, z) -> col);
        for (int i = -3; i <= 3; i++) e.patch(i, 0);
        e.advance(Ecosystem.YEAR_DAYS);
        Ecology.Patch fisher = e.patch(1, 0); // downstream of the tannery
        Flows f = new Flows(e);
        f.river = (x, z) -> 64 - x / 64.0;
        double min = 1e9;
        boolean stopped = false;
        for (int d = 0; d < 8; d++) {
            for (int k = 0; k < 3; k++) f.give(Matter.WASTEWATER, 1, 10, 10, "Moi");
            e.advance(1);
            min = Math.min(min, fisher.pop[TROUT]);
            stopped |= fisher.spawnStopped;
        }
        Projects pr = new Projects();
        pr.proposeRiver(70, 10);
        pr.acceptNext();
        fisher.cleaners = reeds;
        int day = 1;
        while (day < 64 && !pr.river(fisher, 1)) { e.advance(1); day++; }
        return new double[] {min, stopped ? 1 : 0, day};
    }

    @Test
    void aSeasonOfTanningHurtsAndOneOrTwoSeasonsHeal() {
        double[] bare = season(0), reedy = season(10);
        assertEquals(1, bare[1], "the trout kept spawning");
        assertTrue(bare[0] > 1, "the trout died out: " + bare[0]);
        int season = Ecosystem.YEAR_DAYS / 4;
        assertTrue(reedy[2] >= season && reedy[2] <= 2 * season, "healed in " + reedy[2] + " days with reeds");
        assertTrue(bare[2] <= 3 * season, "never healed without reeds: " + bare[2]);
        assertTrue(reedy[2] < bare[2], "the reeds did not help: " + reedy[2] + " vs " + bare[2]);
    }
}
