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
                int[] s = c.springs;
                if (s == null) continue;
                springs++;
                assertEquals(Blocks.WATER, c.get(s[0] & 15, s[1], s[2] & 15));
                assertTrue(s[1] >= TerrainGenerator.SEA_LEVEL + 30, "springs are high up");
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
}
