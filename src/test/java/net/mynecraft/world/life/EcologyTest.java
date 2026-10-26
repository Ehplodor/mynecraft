package net.mynecraft.world.life;

import net.mynecraft.world.Landscape;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The community model of one patch (or a few), on a uniform made-up landscape. */
class EcologyTest {
    private static final int RABBIT = Species.RABBIT.ordinal(), FOX = Species.FOX.ordinal(), DEER = Species.DEER.ordinal(),
        LANTERN = Species.LANTERN.ordinal(), HERON = Species.HERON.ordinal(), MOSS = Species.MOSSBACK.ordinal();

    private static Ecology land(byte biome, int water) {
        Landscape.Column col = new Landscape.Column(70, biome, water, 0, 0.5f);
        return new Ecology(42, new Ecosystem(), (x, z) -> col);
    }

    /** Mean of a species over {@code years}, sampled daily. */
    private static double mean(Ecology e, Ecology.Patch p, int s, int years) {
        double sum = 0;
        int days = years * Ecosystem.YEAR_DAYS;
        for (int d = 0; d < days; d++) {
            e.advance(1);
            sum += p.pop[s];
        }
        return sum / days;
    }

    @Test
    void predatorsAndPreyCoexistAndStayBounded() {
        Ecology e = land(Landscape.BIRCH_FOREST, 0);
        Ecology.Patch p = e.patch(0, 0);
        double minR = 1e9, minF = 1e9, maxR = 0;
        for (int d = 0; d < 10 * Ecosystem.YEAR_DAYS; d++) {
            e.advance(1);
            minR = Math.min(minR, p.pop[RABBIT]);
            minF = Math.min(minF, p.pop[FOX]);
            maxR = Math.max(maxR, p.pop[RABBIT]);
        }
        assertTrue(minR > 0.05 && minF > 0.01, "extinction: rabbits " + minR + ", foxes " + minF);
        assertTrue(maxR < 30, "rabbits exploded: " + maxR);
        assertTrue(p.plants > 0.02 && p.plants <= 1.0001, "plants " + p.plants);
        assertTrue(p.soil >= 0 && p.soil <= 1);
    }

    @Test
    void predatorsHoldTheirPreyDown() {
        Ecology with = land(Landscape.BIRCH_FOREST, 0), without = land(Landscape.BIRCH_FOREST, 0);
        Ecology.Patch a = with.patch(0, 0), b = without.patch(0, 0);
        b.pop[FOX] = 0;
        double rabbitsWith = mean(with, a, RABBIT, 4), rabbitsWithout = mean(without, b, RABBIT, 4);
        assertTrue(rabbitsWithout > rabbitsWith * 1.15, "foxes made no difference: " + rabbitsWith + " vs " + rabbitsWithout);
        // ...and the plants pay for it (a trophic cascade).
        assertTrue(b.plants <= a.plants + 0.02, "plants: " + a.plants + " with foxes, " + b.plants + " without");
    }

    @Test
    void plantsFollowTheSeasons() {
        Ecology e = land(Landscape.PLAINS, 0);
        Ecology.Patch p = e.patch(0, 0);
        double summer = 0, winter = 0;
        for (int d = 0; d < 3 * Ecosystem.YEAR_DAYS; d++) {
            e.advance(1);
            double phase = Ecosystem.seasonAt(e.day()).phase();
            if (Math.abs(phase - 0.45) < 0.03) summer = p.plants;
            if (Math.abs(phase - 0.95) < 0.03) winter = p.plants;
        }
        assertTrue(summer > winter * 1.5, "summer " + summer + ", winter " + winter);
    }

    @Test
    void heronsMigrateAndMossbacksHibernate() {
        Ecology e = land(Landscape.FOREST, 75); // a forest with water
        Ecology.Patch p = e.patch(0, 0);
        double heronSummer = 0, heronWinter = 1e9, mossWinter = 1e9, mossSummer = 0;
        for (int d = 0; d < 2 * Ecosystem.YEAR_DAYS; d++) {
            e.advance(1);
            double phase = Ecosystem.seasonAt(e.day()).phase();
            if (phase > 0.3 && phase < 0.45) { heronSummer = Math.max(heronSummer, p.pop[HERON]); mossSummer = Math.max(mossSummer, p.pop[MOSS]); }
            if (phase > 0.9) { heronWinter = Math.min(heronWinter, p.pop[HERON]); mossWinter = Math.min(mossWinter, p.pop[MOSS]); }
        }
        assertTrue(heronSummer > 0.2 && heronWinter < heronSummer * 0.3, "herons " + heronSummer + " / " + heronWinter);
        assertTrue(mossSummer > 0.05, "no mossbacks");
        assertTrue(mossWinter < mossSummer * 0.3 && p.dormant[MOSS] + p.pop[MOSS] > 0, "mossbacks awake in winter: " + mossWinter);
    }

    @Test
    void lanternsAndFlowersHelpEachOther() {
        Ecology with = land(Landscape.FOREST, 75), without = land(Landscape.FOREST, 75);
        Ecology.Patch a = with.patch(0, 0), b = without.patch(0, 0);
        assertTrue(a.pop[LANTERN] + a.dormant[LANTERN] > 0.1, "no lanterns to begin with");
        b.pop[LANTERN] = 0;
        b.dormant[LANTERN] = 0;
        double fa = 0, fb = 0;
        for (int d = 0; d < 2 * Ecosystem.YEAR_DAYS; d++) {
            with.advance(1);
            without.advance(1);
            fa += a.flowers;
            fb += b.flowers;
        }
        assertTrue(fa > fb, "pollinators bring no more flowers: " + fa + " vs " + fb);
    }

    @Test
    void carcassesFeedTheSoil() {
        Ecology e = land(Landscape.PLAINS, 0);
        Ecology.Patch p = e.patch(0, 0);
        double before = p.detritus;
        for (int i = 0; i < 10; i++) e.died(Species.DEER, 10, 10);
        assertTrue(p.detritus > before + 0.4);
        p.soil = 0.3;
        double soil = p.soil;
        p.plants = 0.02; // nothing to take it up: the detritus decomposes into the soil
        e.advance(3);
        assertTrue(p.soil > soil, "soil " + soil + " -> " + p.soil);
    }

    @Test
    void crowdedHerdsFallSick() {
        Ecology e = land(Landscape.PLAINS, 0);
        Ecology.Patch p = e.patch(0, 0);
        p.pop[DEER] = 12;
        p.plants = 1;
        p.sick[DEER] = 0.01;
        e.advance(8);
        assertTrue(p.sick[DEER] > 0.1, "no outbreak: " + p.sick[DEER]);
        assertTrue(e.links(p).stream().anyMatch(l -> l.kind() == Ecology.Interaction.PARASITISM));
    }

    @Test
    void savedAndLoaded() throws Exception {
        Ecology e = land(Landscape.FOREST, 0);
        e.advance(3);
        Ecology.Patch p = e.patch(2, -1);
        Path file = Files.createTempDirectory("eco").resolve("ecology.bin");
        e.save(file);
        Ecology back = land(Landscape.FOREST, 0);
        assertTrue(back.load(file));
        Ecology.Patch q = back.existing(2 * 64 + 1, -64 + 1);
        assertNotNull(q);
        assertEquals(p.plants, q.plants, 1e-12);
        assertArrayEquals(p.pop, q.pop, 1e-12);
        assertEquals(e.day(), back.day(), 1e-12);
    }
}
