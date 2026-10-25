package net.mynecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HydrologyTest {
    @Test
    void riversAreCarvedAboveTheSeaAndStayInTheirBeds() {
        TerrainGenerator gen = new TerrainGenerator(7);
        int upland = 0, unsettled = 0;
        for (int cz = -12; cz < 0; cz++)
            for (int cx = -40; cx < -28; cx++) {
                Chunk c = gen.generate(cx, cz);
                int[] u = FluidSimulator.unsettled(c);
                if (u != null) unsettled += u.length / 3;
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        Landscape.Column col = gen.column((cx << 4) + x, (cz << 4) + z);
                        if (col.river() > 0 && col.water() > TerrainGenerator.SEA_LEVEL + 5) upland++;
                    }
            }
        assertTrue(upland > 200, "only " + upland + " river columns above the sea");
        assertTrue(unsettled < 400, unsettled + " fluid blocks would still move"); // only rapids
    }

    @Test
    void riversWidenDownstream() {
        Landscape land = new Landscape(7);
        int narrow = 0, wide = 0;
        for (int z = -1000; z < 600; z += 50) {
            int run = 0;
            for (int x = -1200; x < 400; x++) {
                if (land.column(x, z).river() > 0) run++;
                else {
                    if (run > 0 && run <= 4) narrow++;
                    if (run >= 12) wide++;
                    run = 0;
                }
            }
        }
        assertTrue(narrow >= 8 && wide > 3, narrow + " narrow, " + wide + " wide crossings");
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

    @Test
    void riverBanksCanBeClimbedFromTheWater() {
        Landscape land = new Landscape(7);
        int edges = 0, easy = 0;
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int z = -1000; z < 400; z += 6)
            for (int x = -1200; x < 200; x += 6) {
                Landscape.Column c = land.column(x, z);
                if (c.river() <= 0) continue;
                for (int[] o : d) {
                    Landscape.Column n = land.column(x + o[0], z + o[1]);
                    if (Math.max(Landscape.SEA, n.water()) > n.height()) continue;
                    edges++;
                    if (n.height() - c.water() <= 1) easy++; // one step up from the surface
                }
            }
        assertTrue(easy > edges * 0.72, easy + " of " + edges + " river edges are one block or less above the water");
    }

    @Test
    void aSwimmerCanClimbOntoABankOneBlockAboveTheWater() {
        World w = new World(1);
        Chunk c = new Chunk(0, 0);
        c.fillSection(0, Blocks.STONE);
        w.put(c);
        int y = Chunk.MIN_Y + 16; // water layer two deep, bank top one block above the water
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                if (x < 8) { c.set(x, y, z, Blocks.WATER); c.set(x, y + 1, z, Blocks.WATER); }
                else { c.set(x, y, z, Blocks.STONE); c.set(x, y + 1, z, Blocks.STONE); c.set(x, y + 2, z, Blocks.STONE); }
            }
        Player p = new Player();
        p.x = 6.5; p.y = y + 1.2; p.z = 8.5;
        for (int i = 0; i < 300; i++) { // 3 s pushing towards the bank
            p.vx = 4.3;
            p.vz = 0;
            p.step(w, 0.01);
        }
        assertTrue(p.x > 8.5 && p.y >= y + 3 - 1e-6, "still in the water at " + p.x + ", " + p.y);
    }

    @Test
    void aWaterfallJoinsThePoolBelowWithoutASheetOnTop() {
        World w = new World(1);
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                Chunk n = new Chunk(dx, dz);
                n.fillSection(0, Blocks.STONE);
                w.put(n);
            }
        int y = Chunk.MIN_Y + 16;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) w.set(x, y, z, Blocks.WATER); // the pool
        for (int k = 1; k <= 4; k++) w.set(1, y + k, 8, Blocks.STONE);
        w.set(1, y + 5, 8, Blocks.WATER); // a source on a pillar, spilling over its edges
        FluidSimulator f = new FluidSimulator(w, (a, b, c) -> {});
        f.blockChanged(1, y + 5, 8);
        for (int i = 0; i < 400; i++) f.tick();
        int sheet = 0;
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) if (Blocks.isFluid(w.get(x, y + 1, z)) && Blocks.LEVEL[w.get(x, y + 1, z)] < 8) sheet++;
        assertEquals(0, sheet, "falling water lands in the pool and stops there");
        assertEquals(8, Blocks.LEVEL[w.get(2, y + 1, 8)], "a falling column reaches the pool");
    }
}
