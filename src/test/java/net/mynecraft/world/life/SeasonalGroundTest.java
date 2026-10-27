package net.mynecraft.world.life;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Landscape;
import net.mynecraft.world.TerrainGenerator;
import net.mynecraft.world.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The plants' local calendar ({@link Vegetation}) and the season's frost on the ground ({@link Frost}). */
class SeasonalGroundTest {
    private static final int FLOOR = 63, POND = 8;
    private static final double SPRING = 0.1, SUMMER = 0.375, WINTER = 0.875;

    @Test
    void mountainsGrowLaterAndShorterThanValleys() {
        double valley = Vegetation.climate(0.5f, TerrainGenerator.SEA_LEVEL + 5);
        double mountain = Vegetation.climate(0.5f, TerrainGenerator.SEA_LEVEL + 900);
        double[] v = Vegetation.growingSeason(valley), m = Vegetation.growingSeason(mountain);
        assertTrue(m[0] > v[0] && m[1] < v[1], "the heights wake later and sleep earlier");
        // Early spring: the valley grows, the pasture is still asleep; both grow in midsummer.
        assertTrue(Vegetation.growth(valley, 0.6, 0.2) > 0.5, "valley in late spring");
        assertTrue(Vegetation.growth(mountain, 0.6, 0.2) < 0.2, "pasture in late spring");
        assertTrue(Vegetation.growth(mountain, 0.6, SUMMER) > 0.5, "pasture in summer");
        assertTrue(Vegetation.growth(valley, 0.6, WINTER) < 0.1, "valley in winter");
        // Flowers: the valley's in late spring, the pasture's in summer.
        assertTrue(Vegetation.bloom(valley, 0.2) > Vegetation.bloom(mountain, 0.2));
        assertTrue(Vegetation.bloom(mountain, 0.45) > Vegetation.bloom(valley, 0.45));
    }

    @Test
    void theLocalCalendarOfAMeadowIsTheYearItself() {
        for (double phase = 0; phase < 1; phase += 0.05)
            assertEquals(phase, Vegetation.localPhase(Vegetation.REFERENCE, phase), 1e-9);
        // Elsewhere it still runs round the year once, without going back.
        double climate = Vegetation.climate(0.4f, TerrainGenerator.SEA_LEVEL + 500), last = Vegetation.localPhase(climate, 0), turned = 0;
        for (double phase = 0.01; phase <= 1.0001; phase += 0.01) {
            double l = Vegetation.localPhase(climate, phase), step = Vegetation.wrap(l - last);
            assertTrue(step < 0.2, "jump at " + phase);
            turned += step;
            last = l;
        }
        assertEquals(1, turned, 1e-6);
    }

    @Test
    void hotDrySummersParchTheGrass() {
        double desert = Vegetation.climate(0.95f, TerrainGenerator.SEA_LEVEL + 5);
        assertTrue(Vegetation.drought(desert, 0.1, SUMMER) > 0.5);
        assertEquals(0, Vegetation.drought(desert, 0.9, SUMMER), 1e-9, "rain keeps it green");
        assertTrue(Vegetation.growth(desert, 0.1, SUMMER) < Vegetation.growth(desert, 0.1, SPRING));
    }

    // ------------------------------------------------------------------------------- frost

    private static void put(World w, int x, int y, int z, byte b) {
        w.chunk(x >> 4, z >> 4).set(x & 15, y, z & 15, b);
    }

    /** A grass field with a pond in the middle and tufts of grass all round it. */
    private static World field() {
        World w = new World(3);
        for (int cx = -3; cx < 3; cx++)
            for (int cz = -3; cz < 3; cz++) w.getOrGenerateEmpty(cx, cz);
        for (int x = -40; x < 40; x++)
            for (int z = -40; z < 40; z++) {
                for (int y = 55; y < FLOOR; y++) put(w, x, y, z, Blocks.STONE);
                boolean pond = x >= -POND && x < POND && z >= -POND && z < POND;
                if (pond) for (int y = FLOOR - 3; y <= FLOOR; y++) put(w, x, y, z, Blocks.WATER);
                else {
                    put(w, x, FLOOR, z, Blocks.GRASS);
                    put(w, x, FLOOR + 1, z, Blocks.SHORT_GRASS);
                }
            }
        return w;
    }

    private static Frost frost(World w) {
        Landscape.Column plains = new Landscape.Column(FLOOR, Landscape.PLAINS, 0, 0, 0.5f); // about 6 deg C a year round
        return new Frost(w, (x, z) -> plains, 11);
    }

    private static int ice(World w, int ring) {
        int n = 0;
        for (int x = -POND; x < POND; x++)
            for (int z = -POND; z < POND; z++) {
                int edge = Math.min(Math.min(x + POND, POND - 1 - x), Math.min(z + POND, POND - 1 - z));
                if ((ring < 0 || edge == ring) && w.get(x, FLOOR, z) == Blocks.ICE) n++;
            }
        return n;
    }

    /** Visits every column of the pond and its banks, in random order, for a number of passes. */
    private static void passes(Frost f, int n, double phase) {
        Ecosystem.Season season = Ecosystem.seasonAt(phase * Ecosystem.YEAR_DAYS);
        java.util.Random r = new java.util.Random(5);
        for (int i = 0; i < n * 400; i++) f.visit(r.nextInt(40) - 20, r.nextInt(40) - 20, season, e -> {});
    }

    @Test
    void pondsFreezeFromTheShoreInWinterAndThawInSummer() {
        World w = field();
        Frost f = frost(w);
        passes(f, 3, SUMMER);
        assertEquals(0, ice(w, -1), "no ice in summer");
        passes(f, 3, WINTER);
        assertTrue(ice(w, 0) > ice(w, POND - 1) * 4 + 4, "ice starts at the shore: rim " + ice(w, 0) + ", centre " + ice(w, POND - 1));
        passes(f, 60, WINTER);
        assertEquals(4 * POND * POND, ice(w, -1), "the whole pond is iced over by midwinter");
        assertEquals(Blocks.WATER, w.get(0, FLOOR - 1, 0), "only the top freezes");
        passes(f, 60, SUMMER);
        assertEquals(0, ice(w, -1), "open water again in summer");
        assertTrue(f.frozen >= 4 * POND * POND && f.thawed >= 4 * POND * POND);
    }

    @Test
    void hardFrostKillsTheTufts() {
        World w = field();
        Frost f = frost(w);
        passes(f, 5, SPRING);
        assertEquals(Blocks.SHORT_GRASS, w.get(15, FLOOR + 1, 15), "spring is mild enough");
        passes(f, 20, WINTER);
        assertTrue(f.nipped > 300, "frost nipped " + f.nipped);
        assertEquals(Blocks.AIR, w.get(15, FLOOR + 1, 15));
    }
}
