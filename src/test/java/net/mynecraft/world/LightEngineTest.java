package net.mynecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LightEngineTest {
    private static int sky(World w, int x, int y, int z) { return w.getLight(x, y, z) >> 4; }

    private static int block(World w, int x, int y, int z) { return w.getLight(x, y, z) & 15; }

    /** 3x3 chunks, stone floor up to y = 9, lit as if freshly loaded. */
    private static World flat() {
        World w = new World(0);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) {
            Chunk c = w.getOrGenerateEmpty(cx, cz);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 10; y++) c.set(x, y, z, Blocks.STONE);
            LightEngine.initChunk(c);
        }
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) w.light.chunkJoined(w.chunk(cx, cz));
        return w;
    }

    @Test
    void openSkyIsFullAndGroundIsDark() {
        World w = flat();
        assertEquals(15, sky(w, 3, 10, 3));
        assertEquals(0, sky(w, 3, 5, 3));
    }

    @Test
    void roofCastsShadowThatFadesInward() {
        World w = flat();
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) w.set(x, 13, z, Blocks.STONE);
        assertEquals(15, sky(w, 4, 14, 4), "above the roof");
        // Under a 9x9 roof the centre is 5 steps from open sky at the edges: 15 - 5.
        assertEquals(10, sky(w, 4, 12, 4));
        assertEquals(14, sky(w, 0, 12, 4), "edge cell next to open sky");
        w.set(4, 13, 4, Blocks.AIR);
        assertEquals(15, sky(w, 4, 10, 4), "a hole lets a full sunlight column down");
    }

    @Test
    void glowstoneLightsAndUnlights() {
        World w = flat();
        w.set(0, 10, 0, Blocks.GLOWSTONE);
        assertEquals(15, block(w, 0, 10, 0));
        assertEquals(14, block(w, 1, 10, 0));
        assertEquals(10, block(w, 5, 10, 0));
        assertEquals(12, block(w, -3, 10, 0), "crosses chunk borders");
        w.set(0, 10, 0, Blocks.AIR);
        assertEquals(0, block(w, 1, 10, 0));
        assertEquals(0, block(w, -3, 10, 0));
    }

    @Test
    void lightCrossesIntoLaterLoadedChunk() {
        World w = new World(0);
        Chunk a = w.getOrGenerateEmpty(0, 0);
        a.set(15, 10, 8, Blocks.GLOWSTONE);
        LightEngine.initChunk(a);
        w.light.chunkJoined(a);
        Chunk b = w.getOrGenerateEmpty(1, 0);
        LightEngine.initChunk(b);
        w.light.chunkJoined(b);
        assertEquals(13, block(w, 17, 10, 8));
    }

    @Test
    void editsReportTouchedSections() {
        World w = flat();
        w.light.drainTouched(k -> {});
        w.set(4, 12, 4, Blocks.GLOWSTONE);
        int[] n = {0};
        w.light.drainTouched(k -> n[0]++);
        assertTrue(n[0] > 0);
    }
}
