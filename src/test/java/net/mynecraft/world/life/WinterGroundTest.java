package net.mynecraft.world.life;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.ChunkMesher;
import net.mynecraft.world.Landscape;
import net.mynecraft.world.TerrainGenerator;
import net.mynecraft.world.World;
import net.mynecraft.world.weather.Atmosphere;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** M26: snow lying in layers ({@link SnowCover}), thick and thin ice ({@link Frost}), alpine flowers, tracks. */
class WinterGroundTest {
    private static final int FLOOR = 63;
    private static final double SUMMER = 0.375, WINTER = 0.875;
    private static final Landscape.Column PLAINS = new Landscape.Column(FLOOR, Landscape.PLAINS, 0, 0, 0.5f);

    private static Ecosystem.Season at(double phase) {
        return Ecosystem.seasonAt(phase * Ecosystem.YEAR_DAYS);
    }

    private static World meadow() {
        World w = new World(3);
        for (int cx = -2; cx < 2; cx++)
            for (int cz = -2; cz < 2; cz++) w.getOrGenerateEmpty(cx, cz);
        for (int x = -32; x < 32; x++)
            for (int z = -32; z < 32; z++) {
                w.chunk(x >> 4, z >> 4).set(x & 15, FLOOR, z & 15, Blocks.GRASS);
                w.chunk(x >> 4, z >> 4).set(x & 15, FLOOR + 1, z & 15, Blocks.SHORT_GRASS);
            }
        return w;
    }

    private static Atmosphere.Sample weather(Atmosphere.Precipitation type, float rate, float celsius) {
        return new Atmosphere.Sample(celsius, 0.5f, 1, 1.3f, rate, type, 0, 0, 0, 120, 0, 0);
    }

    @Test
    void snowLayersAreDrawnAtTheirHeightAndHideOnlyLowerSnow() {
        assertEquals(3, Blocks.snowLayers(Blocks.snow(3)));
        assertEquals(Blocks.snow(8), Blocks.fromState("snow", java.util.Map.of("layers", "8")));
        assertEquals(4, Blocks.BOX_MAX[Blocks.snow(3)], "feet sink a layer into the snow");
        World w = new World(1);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) w.getOrGenerateEmpty(cx, cz);
        Chunk c = w.chunk(0, 0);
        c.set(4, 10, 4, Blocks.STONE);
        c.set(4, 11, 4, Blocks.snow(3));
        c.set(5, 10, 4, Blocks.STONE);
        c.set(5, 11, 4, Blocks.snow(3)); // same depth beside it: the shared side is hidden
        int[] d = new ChunkMesher().mesh(w, c, (10 - Chunk.MIN_Y) >> 4).data();
        int snowQuads = 0, tops = 0;
        for (int q = 0; q < d.length / 4; q++) {
            if (ChunkMesher.layer(d, q) != Blocks.snow(3)) continue;
            snowQuads++;
            assertEquals(ChunkMesher.SPECIAL_FLUID, ChunkMesher.special(d, q));
            assertEquals(6, ChunkMesher.fluidHeight(d, q), "3 layers = 6/16");
            if (ChunkMesher.face(d, q) == 3) tops++;
        }
        assertEquals(2, tops);
        assertEquals(2 + 6, snowQuads, "two tops, three outer sides each, no shared side, no bottoms on stone");
    }

    @Test
    void theSnowpackFollowsTheColdOfThePlace() {
        double valley = Vegetation.climate(0.5f, TerrainGenerator.SEA_LEVEL + 5);
        double alp = Vegetation.climate(0.5f, TerrainGenerator.SEA_LEVEL + 1500);
        assertEquals(0, SnowCover.pack(valley, SUMMER));
        assertTrue(SnowCover.pack(valley, WINTER) >= 1, "some snow in the valley at midwinter");
        assertTrue(SnowCover.pack(alp, WINTER) > SnowCover.pack(valley, WINTER) + 2, "much deeper on the heights");
        assertTrue(SnowCover.pack(alp, 0.05) > 0, "still lying on the heights in early spring");
    }

    @Test
    void snowFallsOnTheMeadowBuriesTheTuftsAndMeltsInTheMild() {
        World w = meadow();
        SnowCover s = new SnowCover(w, (x, z) -> PLAINS, 3);
        s.sky = (x, z, g) -> weather(Atmosphere.Precipitation.SNOW, 0.05f, -4);
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < 20000; i++) s.visit(r.nextInt(32) - 16, r.nextInt(32) - 16, at(WINTER), e -> {});
        int deep = Blocks.snowLayers(w.get(0, FLOOR + 1, 0));
        assertTrue(deep >= 4, "a few hours of snowfall: " + deep + " layers");
        assertFalse(Blocks.isPlant(w.get(3, FLOOR + 1, 3)), "the tufts are buried");
        s.sky = (x, z, g) -> weather(Atmosphere.Precipitation.RAIN, 0.05f, 6);
        for (int i = 0; i < 40000; i++) s.visit(r.nextInt(32) - 16, r.nextInt(32) - 16, at(SUMMER), e -> {});
        assertEquals(Blocks.AIR, w.get(0, FLOOR + 1, 0), "rain on a mild day melts it all");
        assertTrue(s.fallen > 0 && s.melted >= s.fallen - 5);
    }

    @Test
    void aChunkComesInWithTheSeasonsSnowAndIce() {
        World w = new World(1);
        Chunk c = w.getOrGenerateEmpty(0, 0);
        Landscape.Column cold = new Landscape.Column(FLOOR, Landscape.PLAINS, 0, 0, 0.3f); // about -2 deg C a year
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                boolean pond = x < 8;
                for (int y = FLOOR - 4; y <= FLOOR; y++) c.set(x, y, z, pond && y > FLOOR - 4 ? Blocks.WATER : Blocks.STONE);
                if (!pond) c.set(x, FLOOR, z, Blocks.GRASS);
            }
        SnowCover s = new SnowCover(w, (x, z) -> cold, 1);
        s.settle(c, at(WINTER));
        int want = SnowCover.pack(Vegetation.climate(0.3f, FLOOR), WINTER);
        assertTrue(want >= 3);
        assertEquals(Blocks.snow(want), c.get(12, FLOOR + 1, 4), "the pack lies on the land");
        assertEquals(Blocks.ICE, c.get(2, FLOOR, 2));
        assertEquals(Blocks.ICE, c.get(2, FLOOR - 2, 2), "hard cold: three blocks of ice");
        assertEquals(Blocks.WATER, c.get(2, FLOOR - 3, 2), "but not down to the bed");
        assertEquals(Blocks.snow(want), c.get(2, FLOOR + 1, 2), "snow on the frozen pond too");
        s.settle(c, at(SUMMER));
        assertEquals(Blocks.AIR, c.get(12, FLOOR + 1, 4), "gone in summer");
        assertEquals(Blocks.WATER, c.get(2, FLOOR, 2), "open water in summer");
    }

    @Test
    void thinIceCreaksThenGivesWayAndCrouchingHoldsLonger() {
        World w = new World(1);
        Chunk c = w.getOrGenerateEmpty(0, 0);
        for (int y = FLOOR - 3; y < FLOOR; y++) c.set(5, y, 5, Blocks.WATER);
        c.set(5, FLOOR, 5, Blocks.ICE);
        Landscape.Column mild = new Landscape.Column(FLOOR, Landscape.PLAINS, 0, 0, 0.5f);
        Frost f = new Frost(w, (x, z) -> mild, 2);
        Ecosystem.Season lateWinter = at(0.05); // about -1 deg C: a fresh, thin sheet
        int creaks = 0, broke = -1;
        for (int i = 0; i < 100 && broke < 0; i++) {
            int r = f.bear(5.5, FLOOR + 1, 5.5, false, 0.05, lateWinter, e -> {});
            if (r == 1) creaks++;
            if (r == 2) broke = i;
        }
        assertTrue(creaks >= 2, "it creaks first");
        assertTrue(broke > 0 && broke * 0.05 < Frost.HOLDS + 0.1, "then gives way");
        assertEquals(Blocks.WATER, w.get(5, FLOOR, 5));
        c.set(5, FLOOR, 5, Blocks.ICE);
        for (int i = 0; i < 60; i++) assertNotEquals(2, f.bear(5.5, FLOOR + 1, 5.5, true, 0.05, lateWinter, e -> {}), "crouched: holds 3 s");
        c.set(5, FLOOR - 1, 5, Blocks.ICE); // two blocks thick: bears anyone
        for (int i = 0; i < 200; i++) assertEquals(0, f.bear(5.5, FLOOR + 1, 5.5, false, 0.05, lateWinter, e -> {}));
    }

    @Test
    void footstepsPressTracksIntoDeepSnow() {
        World w = meadow();
        for (int x = 0; x < 10; x++) w.set(x, FLOOR + 1, 0, Blocks.snow(4));
        Life life = new Life(w, 1);
        life.columns = (x, z) -> PLAINS;
        life.spawning = false;
        for (int x = 0; x < 10; x++) life.tick(0.05, x + 0.5, FLOOR + 1 + 6 / 16.0, 0.5, false);
        life.tick(0.05, 9.6, FLOOR + 1 + 6 / 16.0, 0.5, false); // still in the same column: one track only
        for (int x = 0; x < 10; x++) assertEquals(Blocks.snow(3), w.get(x, FLOOR + 1, 0), "a track at " + x);
    }

    @Test
    void alpineFlowersStandTheFrostThatKillsPoppies() {
        World w = meadow();
        w.set(1, FLOOR + 1, 1, Blocks.GENTIAN);
        w.set(2, FLOOR + 1, 1, Blocks.POPPY);
        Frost f = new Frost(w, (x, z) -> PLAINS, 4);
        Ecosystem.Season winter = at(WINTER); // about -5 deg C
        for (int i = 0; i < 200; i++) {
            f.visit(1, 1, winter, e -> {});
            f.visit(2, 1, winter, e -> {});
        }
        assertEquals(Blocks.AIR, w.get(2, FLOOR + 1, 1));
        assertTrue(Blocks.isPlant(w.get(1, FLOOR + 1, 1)), "the gentian is still there");
    }
}
