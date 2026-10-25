package net.mynecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTest {
    /** 3x3 chunks of empty world with a stone floor whose top is at y = 10. */
    private static World flat() {
        World w = new World(0);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) w.getOrGenerateEmpty(cx, cz);
        for (int x = -16; x < 32; x++) for (int z = -16; z < 32; z++) w.set(x, 9, z, Blocks.STONE);
        return w;
    }

    private static void run(Player p, World w, double seconds) {
        for (int i = 0; i < seconds * 120; i++) p.step(w, 1 / 120.0);
    }

    @Test
    void fallsAndLandsOnFloor() {
        World w = flat();
        Player p = new Player();
        p.x = 5.5; p.y = 20; p.z = 5.5;
        run(p, w, 2);
        assertEquals(10.0, p.y, 1e-9);
        assertTrue(p.onGround);
        assertEquals(0, p.vy);
    }

    @Test
    void wallStopsHorizontalMovement() {
        World w = flat();
        for (int y = 10; y < 13; y++) for (int z = 0; z < 16; z++) w.set(8, y, z, Blocks.STONE);
        Player p = new Player();
        p.x = 5.5; p.y = 10; p.z = 5.5;
        for (int i = 0; i < 240; i++) { p.vx = 5; p.step(w, 1 / 120.0); }
        assertEquals(8 - Player.HALF_WIDTH, p.x, 1e-9);
    }

    @Test
    void jumpClearsOneBlockButNotTwo() {
        World w = flat();
        Player p = new Player();
        p.x = 5.5; p.y = 10; p.z = 5.5;
        run(p, w, 0.1);
        p.jump();
        double peak = p.y;
        for (int i = 0; i < 120; i++) { p.step(w, 1 / 120.0); peak = Math.max(peak, p.y); }
        assertTrue(peak - 10 > 1.0 && peak - 10 < 2.0, "jump height " + (peak - 10));
    }

    @Test
    void ceilingStopsJump() {
        World w = flat();
        w.set(5, 12, 5, Blocks.STONE); // head would hit at 12 - 1.8 = 10.2
        Player p = new Player();
        p.x = 5.5; p.y = 10; p.z = 5.5;
        run(p, w, 0.1);
        p.jump();
        double peak = 0;
        for (int i = 0; i < 60; i++) { p.step(w, 1 / 120.0); peak = Math.max(peak, p.y); }
        assertEquals(12 - Player.HEIGHT, peak, 1e-9);
    }

    @Test
    void walksUpSlabsButNotFullBlocks() {
        World w = flat();
        for (int z = 0; z < 16; z++) w.set(8, 10, z, Blocks.OAK_SLAB);
        Player p = new Player();
        p.x = 5.5; p.y = 10; p.z = 5.5;
        run(p, w, 0.1);
        for (int i = 0; i < 240; i++) { p.vx = 4; p.step(w, 1 / 120.0); }
        assertTrue(p.x > 8.5, "climbed onto the slab and kept walking: x = " + p.x);

        World w2 = flat();
        for (int z = 0; z < 16; z++) w2.set(8, 10, z, Blocks.STONE);
        Player q = new Player();
        q.x = 5.5; q.y = 10; q.z = 5.5;
        run(q, w2, 0.1);
        for (int i = 0; i < 240; i++) { q.vx = 4; q.step(w2, 1 / 120.0); }
        assertEquals(8 - Player.HALF_WIDTH, q.x, 1e-6, "a full block needs a jump");
        assertEquals(10, q.y, 1e-9);
    }

    @Test
    void standsOnSlabHalfway() {
        World w = flat();
        w.set(5, 10, 5, Blocks.OAK_SLAB);
        Player p = new Player();
        p.x = 5.5; p.y = 13; p.z = 5.5;
        run(p, w, 1);
        assertEquals(10.5, p.y, 1e-9);
        assertTrue(p.onGround);
    }

    @Test
    void raycastFindsBlockAndFace() {
        World w = flat();
        Raycast.Hit hit = Raycast.cast(w, 5.5, 12, 5.5, 0, -1, 0, 8);
        assertNotNull(hit);
        assertEquals(new Raycast.Hit(5, 9, 5, 0, 1, 0), hit);

        w.set(9, 10, 5, Blocks.DIRT);
        hit = Raycast.cast(w, 5.5, 10.5, 5.5, 1, 0, 0, 8);
        assertEquals(new Raycast.Hit(9, 10, 5, -1, 0, 0), hit);

        assertNull(Raycast.cast(w, 5.5, 10.5, 5.5, 0, 1, 0, 8));
        assertNull(Raycast.cast(w, 5.5, 10.5, 5.5, -1, 0, 0, 2));
    }

    @Test
    void raycastHitsSlabShapeOnly() {
        World w = flat();
        w.set(9, 10, 5, Blocks.OAK_SLAB);
        w.set(12, 10, 5, Blocks.DIRT);
        // At height 10.75 the ray passes over the bottom slab and reaches the dirt behind it.
        assertEquals(new Raycast.Hit(12, 10, 5, -1, 0, 0), Raycast.cast(w, 5.5, 10.75, 5.5, 1, 0, 0, 10));
        // At 10.25 it hits the slab's side.
        assertEquals(new Raycast.Hit(9, 10, 5, -1, 0, 0), Raycast.cast(w, 5.5, 10.25, 5.5, 1, 0, 0, 10));
        // From above: the slab's top face at y = 10.5.
        assertEquals(new Raycast.Hit(9, 10, 5, 0, 1, 0), Raycast.cast(w, 9.5, 13, 5.5, 0, -1, 0, 10));
    }

    @Test
    void intersectsDetectsOverlap() {
        Player p = new Player();
        p.x = 5.5; p.y = 10; p.z = 5.5;
        assertTrue(p.intersects(5, 10, 5));
        assertTrue(p.intersects(5, 11, 5));
        assertFalse(p.intersects(5, 12, 5));
        assertFalse(p.intersects(6, 10, 5));
    }
}
