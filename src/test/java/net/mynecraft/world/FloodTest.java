package net.mynecraft.world;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The flood: heavy rain swells the river, the water rises on the low bank and withdraws, leaving its marks. */
class FloodTest {
    /** A river along z (x in 0..2, water at y 10) between low banks (ground y 10 on the east up to x 6, a slope rising after). */
    private static World valley() {
        World w = new World(0);
        for (int cx = -2; cx <= 2; cx++) for (int cz = -2; cz <= 2; cz++) w.getOrGenerateEmpty(cx, cz);
        for (int z = -20; z < 20; z++)
            for (int x = -10; x < 24; x++) {
                int g = x <= 2 ? 8 : x <= 6 ? 10 : 10 + (x - 6); // the bed, the low bank, the hillside
                for (int y = 0; y <= g; y++) w.set(x, y, z, y == g && x > 2 ? Blocks.GRASS : Blocks.DIRT);
                if (x <= 2 && x >= 0) { w.set(x, 9, z, Blocks.WATER); w.set(x, 10, z, Blocks.WATER); }
                if (x < 0) for (int y = 9; y <= 14; y++) w.set(x, y, z, Blocks.STONE); // the west bank: a cliff
            }
        return w;
    }

    private static final Flood.River RIVER = (x, z) -> x >= 0 && x <= 2;

    @Test
    void heavyRainStartsAFloodModerateRainDoesNot() {
        Flood light = new Flood(), heavy = new Flood();
        boolean started = false;
        for (int i = 0; i < 100; i++) started |= light.update(0.05, 0.4);
        assertFalse(started, "moderate rain flooded: " + light.rain);
        for (int i = 0; i < 100 && !started; i++) started = heavy.update(0.05, 1); // about three days of downpour
        assertTrue(started && heavy.active());
        assertFalse(heavy.update(0.05, 1), "a second flood at once");
    }

    @Test
    void theWaterRisesWithdrawsAndLeavesItsMarks() {
        World w = valley();
        Flood f = new Flood();
        f.since = 0;
        f.since = Flood.DAYS / 2; // the crest: two blocks
        f.apply(w, 0, 0, 12, RIVER);
        assertEquals(Blocks.WATER, w.get(4, 11, 0), "the low bank is under water");
        assertEquals(Blocks.WATER, w.get(4, 12, 0));
        assertEquals(Blocks.AIR, w.get(4, 13, 0));
        assertEquals(Blocks.AIR, w.get(10, 15, 0), "the hillside stays dry");
        assertTrue(f.flooded() > 50);
        f.since = Flood.DAYS * 0.9; // falling
        f.apply(w, 0, 0, 12, RIVER);
        assertEquals(Blocks.AIR, w.get(4, 12, 0), "the water went down");
        f.since = Flood.DAYS; // over
        f.apply(w, 0, 0, 12, RIVER);
        f.apply(w, 0, 0, 12, RIVER);
        assertFalse(f.active());
        assertEquals(0, f.flooded());
        int water = 0, silt = 0, drift = 0;
        for (int z = -12; z <= 12; z++)
            for (int x = 3; x <= 12; x++)
                for (int y = 10; y <= 16; y++) {
                    short b = w.get(x, y, z);
                    if (Blocks.FLUID[b & 0xFFFF] != Blocks.FLUID_NONE) water++;
                    if (b == Blocks.SILT) silt++;
                    if (b == Blocks.DRIFTWOOD) drift++;
                }
        assertEquals(0, water, "water left behind on the bank");
        assertTrue(silt > 10, "silt " + silt);
        assertTrue(drift > 2, "driftwood " + drift);
        assertEquals(Blocks.WATER, w.get(1, 10, 0), "the river itself is untouched");
    }

    @Test
    void willowsHoldTheirBank() {
        World w = valley();
        for (int x = 3; x <= 8; x++) for (int z = 3; z <= 8; z++) w.set(x, 15, z, Blocks.WILLOW_LEAVES);
        Flood f = new Flood();
        f.since = Flood.DAYS / 2;
        f.apply(w, 0, 0, 12, RIVER);
        assertEquals(Blocks.AIR, w.get(5, 11, 5), "flooded under the willow");
        assertEquals(Blocks.WATER, w.get(5, 11, -8), "not flooded away from it");
    }

    @Test
    void anUnfinishedFloodIsSavedSoItsWaterIsTakenBack() throws Exception {
        World w = valley();
        Flood f = new Flood();
        f.since = Flood.DAYS / 2;
        f.apply(w, 0, 0, 12, RIVER);
        Path file = Files.createTempDirectory("flood").resolve("flood.txt");
        f.save(file);
        Flood back = new Flood();
        back.load(file);
        assertEquals(f.flooded(), back.flooded());
        back.since = Flood.DAYS;
        back.apply(w, 0, 0, 12, RIVER);
        assertEquals(Blocks.AIR, w.get(4, 11, 0));
    }
}
