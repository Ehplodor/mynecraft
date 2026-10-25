package net.mynecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HydrologyTest {
    @Test
    void mountainsHaveSpringsThatRunDownhill() {
        TerrainGenerator gen = new TerrainGenerator(7);
        int springs = 0;
        for (int cz = -40; cz < 0; cz++)
            for (int cx = -40; cx < 0; cx++) {
                if (gen.height(cx * 16 + 8, cz * 16 + 8) < TerrainGenerator.SEA_LEVEL + 30) continue;
                Chunk c = gen.generate(cx, cz);
                int[] s = FluidSimulator.unsettled(c);
                if (s == null) continue;
                for (int i = 0; i < s.length; i += 3) {
                    byte b = c.get(s[i] & 15, s[i + 1], s[i + 2] & 15);
                    if (b == Blocks.WATER && s[i + 1] >= TerrainGenerator.SEA_LEVEL + 30) { springs++; break; }
                }
            }
        assertTrue(springs >= 5, "only " + springs + " springs");
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
