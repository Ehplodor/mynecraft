package net.mynecraft.world;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FluidSimulatorTest {
    /** 5x5 chunks of air over a stone floor whose top is y = 9. */
    private static World flat() {
        World w = new World(0);
        for (int cx = -2; cx <= 2; cx++) for (int cz = -2; cz <= 2; cz++) w.getOrGenerateEmpty(cx, cz);
        for (int x = -32; x < 48; x++) for (int z = -32; z < 48; z++) w.set(x, 9, z, Blocks.STONE);
        return w;
    }

    private static void run(FluidSimulator f, int ticks) {
        for (int i = 0; i < ticks; i++) f.tick();
    }

    private static FluidSimulator place(World w, int x, int y, int z, short b, Set<Long> changed) {
        FluidSimulator f = new FluidSimulator(w, (cx, cy, cz) -> changed.add(LightEngine.pack(cx, cy, cz)));
        w.set(x, y, z, b);
        f.blockChanged(x, y, z);
        return f;
    }

    @Test
    void waterSpreadsSevenBlocksAndLosesALevelPerBlock() {
        World w = flat();
        Set<Long> changed = new HashSet<>();
        FluidSimulator f = place(w, 0, 10, 0, Blocks.WATER, changed);
        run(f, 200);
        assertEquals(Blocks.WATER, w.get(0, 10, 0));
        for (int d = 1; d <= 7; d++) assertEquals(d, Blocks.LEVEL[w.get(d, 10, 0)], "level at distance " + d);
        assertEquals(Blocks.AIR, w.get(8, 10, 0), "water stops after 7 blocks");
        assertEquals(Blocks.FLUID_WATER, Blocks.FLUID[w.get(3, 10, 3)], "diamond shape: |3|+|3| = 6 <= 7");
        assertEquals(Blocks.AIR, w.get(4, 10, 4));
        assertFalse(changed.isEmpty());
        assertEquals(0, f.pending(), "the flow settles");
    }

    @Test
    void flowDriesUpWhenTheSourceIsRemoved() {
        World w = flat();
        FluidSimulator f = place(w, 0, 10, 0, Blocks.WATER, new HashSet<>());
        run(f, 200);
        w.set(0, 10, 0, Blocks.AIR);
        f.blockChanged(0, 10, 0);
        run(f, 400);
        for (int d = 0; d <= 7; d++) assertEquals(Blocks.AIR, w.get(d, 10, 0), "dry at " + d);
    }

    @Test
    void waterFallsThenSpreadsOnLanding() {
        World w = flat();
        w.set(0, 15, 0, Blocks.STONE); // a ledge with the source on top, open air below its side
        FluidSimulator f = place(w, 0, 16, 0, Blocks.WATER, new HashSet<>());
        run(f, 300);
        assertEquals(1, Blocks.LEVEL[w.get(1, 16, 0)]);
        // From (1,16,0) the water falls down to the floor: falling states all the way.
        for (int y = 10; y <= 15; y++) assertEquals(Blocks.WATER_FALLING, w.get(1, y, 0), "falling at y=" + y);
        // Where it lands it spreads again as if from a source.
        assertEquals(1, Blocks.LEVEL[w.get(2, 10, 0)]);
    }

    @Test
    void twoSourcesMakeAThird() {
        World w = flat();
        FluidSimulator f = new FluidSimulator(w, (x, y, z) -> {});
        w.set(0, 10, 0, Blocks.WATER);
        w.set(2, 10, 0, Blocks.WATER);
        f.blockChanged(0, 10, 0);
        f.blockChanged(2, 10, 0);
        run(f, 100);
        assertEquals(Blocks.WATER, w.get(1, 10, 0));
    }

    @Test
    void lavaIsSlowerShorterAndMakesStoneWithWater() {
        World w = flat();
        FluidSimulator f = place(w, 0, 10, 0, Blocks.LAVA, new HashSet<>());
        run(f, 25);
        assertEquals(Blocks.AIR, w.get(1, 10, 0), "lava waits 30 ticks");
        run(f, 400);
        assertEquals(Blocks.LAVA_FLOW_6, w.get(3, 10, 0));
        assertEquals(Blocks.AIR, w.get(4, 10, 0), "lava reaches 3 blocks");

        // Water flowing into the lava: obsidian on the source, cobblestone on flowing lava.
        w.set(0, 10, 2, Blocks.WATER);
        f.blockChanged(0, 10, 2);
        run(f, 100);
        assertEquals(Blocks.COBBLESTONE, w.get(0, 10, 1));
        w.set(0, 11, 0, Blocks.WATER);
        f.blockChanged(0, 11, 0);
        run(f, 100);
        assertEquals(Blocks.OBSIDIAN, w.get(0, 10, 0));
    }

    @Test
    void lavaGlows() {
        World w = flat();
        w.set(0, 10, 0, Blocks.LAVA);
        assertEquals(15, w.getLight(0, 10, 0) & 15);
        assertEquals(14, w.getLight(1, 10, 0) & 15);
    }
}
