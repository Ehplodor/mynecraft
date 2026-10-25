package net.mynecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HydrologyTest {
    @Test
    void mountainStreamsArePrecomputedAndStill() {
        TerrainGenerator gen = new TerrainGenerator(7);
        int streams = 0, unsettled = 0;
        for (int cz = -30; cz < -10; cz++)
            for (int cx = -30; cx < -10; cx++) {
                Chunk c = gen.generate(cx, cz);
                int[] u = FluidSimulator.unsettled(c);
                if (u != null) unsettled += u.length / 3;
                found:
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        int h = gen.height((cx << 4) + x, (cz << 4) + z);
                        if (h >= TerrainGenerator.SEA_LEVEL + 30 && c.get(x, h - 1, z) == Blocks.WATER) { streams++; break found; }
                    }
            }
        assertTrue(streams >= 20, "only " + streams + " chunks with streams");
        assertTrue(unsettled < 100, unsettled + " fluid blocks would still move"); // beds are contained
    }

    @Test
    void riversWidenTowardsTheSea() {
        Landscape land = new Landscape(7);
        // Mean river width (columns per crossing) inland vs near the coast, along many lines.
        int[] cells = new int[2], crossings = new int[2];
        for (int z = -4000; z < 4000; z += 80) {
            boolean in = false;
            for (int x = -4000; x < 4000; x += 2) {
                Landscape.Column col = land.column(x, z);
                boolean river = col.biome() == Landscape.RIVER || col.biome() == Landscape.FROZEN_RIVER;
                if (river) {
                    float c = land.continentalness(x, z);
                    int band = c < 0.05f ? 1 : c > 0.4f ? 0 : -1;
                    if (band < 0) { in = true; continue; }
                    cells[band]++;
                    if (!in) crossings[band]++;
                }
                in = river;
            }
        }
        float inland = cells[0] / (float) Math.max(1, crossings[0]), coast = cells[1] / (float) Math.max(1, crossings[1]);
        assertTrue(coast > inland * 1.3f, "coast " + coast + " vs inland " + inland);
    }

    @Test
    void reloadedFlowingWaterAndHangingWaterResume() {
        Chunk c = new Chunk(2, -1);
        c.fillSection(0, Blocks.STONE);
        c.set(3, Chunk.MIN_Y + 16, 3, Blocks.WATER_FLOW_1);  // saved mid-flow, on the ground
        c.set(8, Chunk.MIN_Y + 20, 8, Blocks.WATER);         // source hanging in the air
        c.set(12, Chunk.MIN_Y + 15, 12, Blocks.WATER);       // source enclosed in stone: settled
        int[] u = FluidSimulator.unsettled(c);
        assertNotNull(u);
        assertEquals(6, u.length);
        assertArrayEquals(new int[] {35, Chunk.MIN_Y + 16, -13, 40, Chunk.MIN_Y + 20, -8}, u);
    }

    @Test
    void shallowSheetOfWaterIsWadedNotSwum() {
        World w = new World(1);
        Chunk c = new Chunk(0, 0);
        c.fillSection(0, Blocks.STONE);
        w.put(c);
        int y = Chunk.MIN_Y + 16;
        Player p = new Player();
        p.x = 5.5; p.y = y; p.z = 5.5;
        c.set(5, y, 5, (byte) (Blocks.WATER_FLOW_1 + 5)); // level 6: a thin sheet
        p.step(w, 0.01);
        assertEquals(Blocks.FLUID_NONE, p.fluid);
        c.set(5, y, 5, Blocks.WATER);
        p.step(w, 0.01);
        assertEquals(Blocks.FLUID_WATER, p.fluid);
    }
}
