package net.mynecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SurvivalTest {
    private static World flat() {
        World w = new World(0);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) w.getOrGenerateEmpty(cx, cz);
        for (int x = -16; x < 32; x++) for (int z = -16; z < 32; z++) w.set(x, 9, z, Blocks.STONE);
        return w;
    }

    /** Runs physics at 120 Hz and survival at 20 Hz for the given time. */
    private static void live(Player p, Survival s, World w, double seconds) {
        for (int i = 0; i < seconds * 120; i++) {
            p.step(w, 1 / 120.0);
            if (i % 6 == 0) s.tick(p, w, false);
        }
    }

    private static Player at(double x, double y, double z) {
        Player p = new Player();
        p.x = x; p.y = y; p.z = z;
        return p;
    }

    @Test
    void fallDamageIsBlocksBeyondThree() {
        World w = flat();
        Survival s = new Survival();
        live(at(5.5, 20, 5.5), s, w, 3); // falls 10 blocks: 7 damage
        assertEquals(Survival.MAX_HEALTH - 7, s.health);

        Survival short_ = new Survival();
        live(at(5.5, 13, 5.5), short_, w, 2); // 3 blocks: nothing
        assertEquals(Survival.MAX_HEALTH, short_.health);
    }

    @Test
    void waterBreaksTheFall() {
        World w = flat();
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) for (int y = 10; y < 12; y++) w.set(x, y, z, Blocks.WATER);
        Survival s = new Survival();
        live(at(5.5, 40, 5.5), s, w, 4);
        assertEquals(Survival.MAX_HEALTH, s.health);
    }

    @Test
    void drowningStartsWhenAirRunsOut() {
        World w = flat();
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) for (int y = 10; y < 20; y++) w.set(x, y, z, Blocks.WATER);
        Survival s = new Survival();
        Player p = at(5.5, 10, 5.5);
        live(p, s, w, 10);
        assertTrue(s.air < Survival.MAX_AIR && s.air > 0, "air left after 10 s: " + s.air);
        assertEquals(Survival.MAX_HEALTH, s.health);
        live(p, s, w, 10); // 300 ticks of air = 15 s, then 2 damage per second
        assertTrue(s.health <= Survival.MAX_HEALTH - 8, "health " + s.health);
    }

    @Test
    void lavaKillsAndRespawnRestores() {
        World w = flat();
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) w.set(x, 10, z, Blocks.LAVA);
        Survival s = new Survival();
        live(at(5.5, 10, 5.5), s, w, 5);
        assertTrue(s.dead);
        assertEquals(0, s.health);
        s.respawn();
        assertFalse(s.dead);
        assertEquals(Survival.MAX_HEALTH, s.health);
    }

    @Test
    void creativeTakesNoDamage() {
        World w = flat();
        Survival s = new Survival();
        Player p = at(5.5, 40, 5.5);
        for (int i = 0; i < 600; i++) { p.step(w, 1 / 120.0); if (i % 6 == 0) s.tick(p, w, true); }
        assertEquals(Survival.MAX_HEALTH, s.health);
    }

    @Test
    void inventoryStacksAndDrops() {
        Inventory inv = new Inventory();
        assertEquals(0, inv.add(Blocks.DIRT, 100));
        assertEquals(64, inv.counts[0]);
        assertEquals(36, inv.counts[1]);
        inv.add(Blocks.STONE, 1);
        assertEquals(Blocks.STONE, inv.id(2));
        assertTrue(inv.takeOne(2));
        assertEquals(Blocks.AIR, inv.id(2));
        assertFalse(inv.takeOne(2));
        for (int i = 0; i < Inventory.SIZE; i++) inv.add(Blocks.GLASS, 64);
        assertEquals(10, inv.add(Blocks.GLASS, 10), "full inventory returns the rest");

        assertEquals(Blocks.COBBLESTONE, Blocks.DROP[Blocks.STONE]);
        assertEquals(Blocks.DIRT, Blocks.DROP[Blocks.GRASS]);
        assertEquals(Blocks.OAK_LOG, Blocks.DROP[Blocks.OAK_LOG_X]);
        assertEquals(Blocks.OAK_SLAB, Blocks.DROP[Blocks.OAK_SLAB_TOP]);
        assertEquals(2, Blocks.DROP_COUNT[Blocks.OAK_SLAB_DOUBLE]);
        assertEquals(Blocks.AIR, Blocks.DROP[Blocks.OAK_LEAVES]);
        assertEquals(0.75, Blocks.breakSeconds(Blocks.DIRT), 1e-6);
        assertTrue(Double.isInfinite(Blocks.breakSeconds(Blocks.BEDROCK)));
    }
}
