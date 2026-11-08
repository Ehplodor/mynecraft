package net.mynecraft.world;

import net.mynecraft.world.life.Ecology;
import net.mynecraft.world.life.Ecosystem;
import net.mynecraft.world.life.Riverbank;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Sharing the water (E6): the sluice gate stops or lets the water through, a channel irrigates, a held river slows the wheels downstream. */
class SluiceTest {
    /** A stone channel two walls wide along x, water at its head (x = 0), a gate at x = 2. */
    private static World channel() {
        World w = new World(0);
        for (int cx = -2; cx <= 2; cx++) for (int cz = -2; cz <= 2; cz++) w.getOrGenerateEmpty(cx, cz);
        for (int x = -4; x < 16; x++) {
            w.set(x, 9, 0, Blocks.STONE_CHANNEL);
            w.set(x, 10, -1, Blocks.STONE);
            w.set(x, 10, 1, Blocks.STONE);
        }
        w.set(-1, 10, 0, Blocks.STONE);
        w.set(2, 10, 0, Blocks.SLUICE);
        return w;
    }

    private static void run(FluidSimulator f, int ticks) {
        for (int i = 0; i < ticks; i++) f.tick();
    }

    @Test
    void theGateHoldsTheWaterOrLetsItThrough() {
        World w = channel();
        FluidSimulator f = new FluidSimulator(w, (x, y, z) -> { });
        w.set(0, 10, 0, Blocks.WATER);
        f.blockChanged(0, 10, 0);
        run(f, 200);
        assertEquals(Blocks.FLUID_WATER, Blocks.FLUID[w.get(1, 10, 0)]);
        assertEquals(Blocks.AIR, w.get(3, 10, 0), "water past a closed gate");
        w.set(2, 10, 0, Blocks.SLUICE_OPEN);
        f.blockChanged(2, 10, 0);
        run(f, 300);
        assertEquals(Blocks.SLUICE_OPEN, w.get(2, 10, 0), "the gate itself is not washed away");
        assertEquals(Blocks.FLUID_WATER, Blocks.FLUID[w.get(3, 10, 0)], "no water through the open gate");
        assertEquals(Blocks.FLUID_WATER, Blocks.FLUID[w.get(6, 10, 0)], "the channel does not run on");
        w.set(2, 10, 0, Blocks.SLUICE);
        f.blockChanged(2, 10, 0);
        run(f, 400);
        assertEquals(Blocks.AIR, w.get(3, 10, 0), "closed again, the channel runs dry");
        assertEquals(Blocks.AIR, w.get(6, 10, 0));
    }

    @Test
    void aChannelIrrigatesTheFieldBesideIt() {
        World w = channel();
        w.set(5, 10, 0, Blocks.WATER);
        w.set(5, 10, 3, Blocks.FARMLAND);
        w.set(5, 10, 8, Blocks.FARMLAND);
        assertTrue(Growth.nearWater(w, 5, 10, 3));
        assertFalse(Growth.nearWater(w, 5, 10, 8));
    }

    @Test
    void closedGatesHoldTheRiverBackDownstream() {
        Landscape.Column col = new Landscape.Column(70, Landscape.FOREST, 75, 1, 0.5f);
        Ecology e = new Ecology(42, new Ecosystem(), (x, z) -> col);
        Ecology.Patch up = e.patch(0, 0), down = e.patch(1, 0), farther = e.patch(5, 0), above = e.patch(-1, 0);
        up.sluices = 1;
        Riverbank.hold(e, (x, z) -> new double[] {x + 64, z}); // the river runs east
        assertEquals(Riverbank.HELD_PER_GATE, down.held, 1e-9);
        assertEquals(0, farther.held, 1e-9, "more than three patches down");
        assertEquals(0, above.held, 1e-9, "upstream");
        assertEquals(0, up.held, 1e-9);
        up.sluices = 5;
        Riverbank.hold(e, (x, z) -> new double[] {x + 64, z});
        assertEquals(Riverbank.HELD_MAX, down.held, 1e-9);
        up.sluices = 0;
        Riverbank.hold(e, (x, z) -> new double[] {x + 64, z});
        assertEquals(0, down.held, 1e-9, "opened again");
    }
}
