package net.mynecraft.world;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Stairs, walls and windows: their boxes, how they follow their neighbours, collisions, aiming, placing, meshing. */
class ShapesTest {
    private static final short OAK_N = Blocks.stair(Blocks.OAK_STAIRS, Shapes.NORTH, false);

    /** 3x3 chunks of empty world with a stone floor whose top is at y = 10. */
    private static World flat() {
        World w = new World(0);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) w.getOrGenerateEmpty(cx, cz);
        for (int x = -16; x < 32; x++) for (int z = -16; z < 32; z++) w.set(x, 9, z, Blocks.STONE);
        return w;
    }

    private static int[] box(int b) {
        return new int[] {Shapes.min(b, 0), Shapes.min(b, 1), Shapes.min(b, 2), Shapes.max(b, 0), Shapes.max(b, 1), Shapes.max(b, 2)};
    }

    /** Volume in cubic sixteenths (boxes of one shape do not overlap except walls' posts and arms). */
    private static int volume(int[] boxes) {
        int v = 0;
        for (int b : boxes) {
            int[] c = box(b);
            v += (c[3] - c[0]) * (c[4] - c[1]) * (c[5] - c[2]);
        }
        return v;
    }

    @Test
    void statesAndNames() {
        assertEquals(Blocks.COUNT, Blocks.NAMES.length);
        assertEquals(Blocks.COUNT, Blocks.STATE.length);
        assertEquals(Blocks.OAK_STAIRS + 1, Blocks.stair(Blocks.OAK_STAIRS, Shapes.SOUTH, false));
        assertEquals(Blocks.OAK_STAIRS_TOP + 3, Blocks.stair(Blocks.OAK_STAIRS, Shapes.EAST, true));
        assertEquals(Blocks.COBBLESTONE_STAIRS_TOP, Blocks.stair((short) (Blocks.COBBLESTONE_STAIRS + 2), Shapes.NORTH, true));
        // An old save knew only "facing": a bottom stair facing that way.
        assertEquals(Blocks.OAK_STAIRS + 1, Blocks.fromState("oak_stairs", Map.of("facing", "south")));
        // A vanilla save: half and shape (the shape is worked out from the neighbours here).
        assertEquals(Blocks.OAK_STAIRS_TOP + 2, Blocks.fromState("oak_stairs", Map.of("facing", "west", "half", "top", "shape", "outer_left")));
        assertEquals(Blocks.OAK_WINDOW + 1, Blocks.fromState("oak_window", Map.of("axis", "z")));
        assertEquals(Blocks.COBBLESTONE_WALL, Blocks.fromState("cobblestone_wall", Map.of("north", "low", "up", "true")));
        assertEquals(Blocks.OAK_STAIRS, Blocks.DROP[Blocks.OAK_STAIRS_TOP + 3], "any stair drops the item");
        assertEquals(Blocks.OAK_WINDOW, Blocks.DROP[Blocks.OAK_WINDOW + 1]);
        assertEquals(Blocks.TOOL_PICKAXE, Blocks.TOOL[Blocks.COBBLESTONE_STAIRS_TOP]);
        assertEquals(Blocks.TOOL_AXE, Blocks.TOOL[Blocks.OAK_STAIRS]);
        for (short b : new short[] {OAK_N, Blocks.COBBLESTONE_WALL, Blocks.OAK_WINDOW}) {
            assertTrue(Blocks.hasBoxes(b) && Blocks.isSolid(b) && !Blocks.isOpaque(b));
            assertEquals(0, Blocks.OPACITY[b], "light goes through");
        }
    }

    @Test
    void aStairIsASlabAndAStepOnItsHighSide() {
        int[] b = Shapes.boxes(OAK_N, Shapes.STRAIGHT);
        assertArrayEquals(new int[] {0, 0, 0, 16, 8, 16}, box(b[0]));
        assertArrayEquals(new int[] {0, 8, 0, 16, 16, 8}, box(b[1]), "the step on the north side");
        int[] up = Shapes.boxes(Blocks.stair(Blocks.OAK_STAIRS, Shapes.EAST, true), Shapes.STRAIGHT);
        assertArrayEquals(new int[] {0, 8, 0, 16, 16, 16}, box(up[0]), "upside down: the slab on top");
        assertArrayEquals(new int[] {8, 0, 0, 16, 8, 16}, box(up[1]), "and the step under it, east");
        assertEquals(16 * 16 * 16 * 3 / 4, volume(b));
        assertEquals(16 * 16 * 16 * 5 / 8, volume(Shapes.boxes(OAK_N, Shapes.OUTER_LEFT)));
        assertEquals(16 * 16 * 16 * 7 / 8, volume(Shapes.boxes(OAK_N, Shapes.INNER_RIGHT)));
    }

    @Test
    void stairsTurnCornersLikeVanilla() {
        World w = flat();
        short south = Blocks.stair(Blocks.OAK_STAIRS, Shapes.SOUTH, false), east = Blocks.stair(Blocks.OAK_STAIRS, Shapes.EAST, false);
        short west = Blocks.stair(Blocks.OAK_STAIRS, Shapes.WEST, false);
        short[] n = new short[6];
        // Ring of a stepped pyramid, north-west corner: faces south, the stair in front of it faces east.
        w.set(0, 10, 0, south);
        w.set(0, 10, 1, east);
        w.set(1, 10, 0, south);
        assertEquals(Shapes.OUTER_LEFT, Shapes.variantAt(w, 0, 10, 0, south, n));
        int[] step = box(Shapes.boxes(south, Shapes.OUTER_LEFT)[1]);
        assertArrayEquals(new int[] {8, 8, 8, 16, 16, 16}, step, "a quarter step towards the middle (south-east)");
        assertEquals(Shapes.STRAIGHT, Shapes.variantAt(w, 1, 10, 0, south, n), "the rest of the flight stays straight");
        // Seats climbing into a corner: faces north, the stair behind it faces west.
        w.set(5, 10, 5, OAK_N);
        w.set(5, 10, 6, west);
        assertEquals(Shapes.INNER_LEFT, Shapes.variantAt(w, 5, 10, 5, OAK_N, n));
        // A stair of the other half does not turn it.
        w.set(5, 10, 6, Blocks.stair(Blocks.OAK_STAIRS, Shapes.WEST, true));
        assertEquals(Shapes.STRAIGHT, Shapes.variantAt(w, 5, 10, 5, OAK_N, n));
    }

    @Test
    void wallsReachToTheirNeighboursAndKeepPostsAtEnds() {
        World w = flat();
        short[] n = new short[6];
        w.set(0, 10, 0, Blocks.COBBLESTONE_WALL);
        assertEquals(Shapes.WALL_POST, Shapes.variantAt(w, 0, 10, 0, Blocks.COBBLESTONE_WALL, n), "alone: a post");
        for (int x = 1; x <= 3; x++) w.set(x, 10, 0, Blocks.COBBLESTONE_WALL);
        w.set(4, 10, 0, Blocks.STONE);
        int run = Shapes.variantAt(w, 2, 10, 0, Blocks.COBBLESTONE_WALL, n);
        assertEquals(1 << Shapes.WEST | 1 << Shapes.EAST, run, "a straight run: no post");
        assertArrayEquals(new int[] {0, 0, 5, 16, 14, 11}, box(Shapes.boxes(Blocks.COBBLESTONE_WALL, run)[0]));
        assertEquals(1 << Shapes.WEST | 1 << Shapes.EAST, Shapes.variantAt(w, 3, 10, 0, Blocks.COBBLESTONE_WALL, n), "joins the stone");
        assertEquals(1 << Shapes.EAST | Shapes.WALL_POST, Shapes.variantAt(w, 0, 10, 0, Blocks.COBBLESTONE_WALL, n), "the end keeps its post");
        w.set(2, 10, 1, Blocks.COBBLESTONE_WALL);
        assertTrue((Shapes.variantAt(w, 2, 10, 0, Blocks.COBBLESTONE_WALL, n) & Shapes.WALL_POST) != 0, "a branch: post again");
        w.set(1, 11, 0, Blocks.COBBLESTONE);
        int under = Shapes.variantAt(w, 1, 10, 0, Blocks.COBBLESTONE_WALL, n);
        assertTrue((under & Shapes.WALL_TALL) != 0);
        assertEquals(16, Shapes.max(Shapes.boxes(Blocks.COBBLESTONE_WALL, under)[0], 1), "full height under a block");
    }

    @Test
    void windowsDropTheirFrameTowardsTheNextPane() {
        int[] alone = Shapes.boxes(Blocks.OAK_WINDOW, 0);
        int[] joined = Shapes.boxes(Blocks.OAK_WINDOW, Shapes.WINDOW_HIGH | Shapes.WINDOW_UP);
        assertTrue(joined.length < alone.length);
        int glass = 0;
        for (int b : alone) {
            if (Shapes.part(b) == 1) { glass++; assertArrayEquals(new int[] {0, 0, 7, 16, 16, 9}, box(b), "a pane along x"); }
            else assertTrue(Shapes.min(b, 2) == 6 && Shapes.max(b, 2) == 10, "the frame is 4/16 deep");
        }
        assertEquals(1, glass);
        assertEquals(Blocks.GLASS, Shapes.partBlock(Blocks.OAK_WINDOW, 1));
        assertEquals(Blocks.OAK_PLANKS, Shapes.partBlock(Blocks.OAK_WINDOW, 0));
        int maxX = 0;
        for (int b : joined) if (Shapes.part(b) == 0 && Shapes.min(b, 0) >= 14) maxX++;
        assertEquals(0, maxX, "no upright on the east side");
        World w = flat();
        short[] n = new short[6];
        w.set(0, 10, 0, Blocks.OAK_WINDOW);
        w.set(1, 10, 0, Blocks.OAK_WINDOW);
        w.set(0, 11, 0, (short) (Blocks.OAK_WINDOW + 1)); // across: not the same glazing
        assertEquals(Shapes.WINDOW_HIGH, Shapes.variantAt(w, 0, 10, 0, Blocks.OAK_WINDOW, n));
        assertEquals(Shapes.WINDOW_LOW, Shapes.variantAt(w, 1, 10, 0, Blocks.OAK_WINDOW, n));
        int[] along = Shapes.boxes((short) (Blocks.OAK_WINDOW + 1), 0);
        assertArrayEquals(new int[] {7, 0, 0, 9, 16, 16}, box(along[0]), "a pane along z");
    }

    private static void run(Player p, World w, double seconds, double vx, double vz) {
        for (int i = 0; i < seconds * 120; i++) { p.vx = vx; p.vz = vz; p.step(w, 1 / 120.0); }
    }

    @Test
    void stairsAreWalkedUpWithoutJumping() {
        World w = flat();
        for (int i = 0; i < 3; i++) {
            w.set(5, 10 + i, 5 - i, OAK_N);
            for (int j = 0; j < i; j++) w.set(5, 10 + j, 5 - i, Blocks.OAK_PLANKS);
        }
        for (int z = 0; z <= 2; z++) for (int y = 10; y <= 12; y++) w.set(5, y, z, Blocks.OAK_PLANKS); // the landing
        Player p = new Player();
        p.x = 5.5; p.y = 10; p.z = 8.5;
        run(p, w, 0.1, 0, 0);
        run(p, w, 2.5, 0, -3);
        assertEquals(13, p.y, 1e-9, "up the three stairs to the landing");
        assertTrue(p.z < 2.5);
        // Halfway up a step the feet stand at the half: the slab is where one stands.
        Player q = new Player();
        q.x = 5.5; q.y = 11; q.z = 5.85; // over the low half of the first stair
        run(q, w, 1, 0, 0);
        assertEquals(10.5, q.y, 1e-9);
    }

    @Test
    void aMuretIsJumpedNotSteppedAndAWindowStopsTheBody() {
        World w = flat();
        for (int z = 0; z < 16; z++) w.set(8, 10, z, Blocks.COBBLESTONE_WALL);
        Player p = new Player();
        p.x = 5.5; p.y = 10; p.z = 5.5;
        run(p, w, 2, 5, 0);
        assertEquals(8 + 5 / 16.0 - Player.HALF_WIDTH, p.x, 1e-9, "stopped by the wall's side, not the cell's");
        for (int z = 0; z < 16; z++) { w.set(3, 10, z, (short) (Blocks.OAK_WINDOW + 1)); w.set(3, 11, z, (short) (Blocks.OAK_WINDOW + 1)); }
        run(p, w, 2, -5, 0);
        assertEquals(3 + 10 / 16.0 + Player.HALF_WIDTH, p.x, 1e-9, "against the window's frame");
    }

    @Test
    void aimingFindsTheBoxesNotTheCell() {
        World w = flat();
        w.set(5, 10, 5, OAK_N);
        // Level with the low half, going north: passes over the slab, hits the step's south face.
        Raycast.Hit h = Raycast.cast(w, 5.5, 10.75, 8, 0, 0, -1, 10);
        assertNotNull(h);
        assertEquals(5, h.z());
        assertEquals(1, h.nz());
        // Grazing over the slab from the south at a height above the step: misses the stair entirely.
        Raycast.Hit miss = Raycast.cast(w, 5.5, 10.6, 5.99, 0, 1, 0, 0.3);
        assertNull(miss);
        Raycast.Hit top = Raycast.cast(w, 5.5, 12, 5.75, 0, -1, 0, 10);
        assertEquals(10, top.y());
        assertEquals(1, top.ny(), "on the slab's top");
    }

    @Test
    void placingTurnsStairsAwayAndWindowsAcross() {
        World w = flat();
        Player p = new Player();
        p.x = 5.5; p.y = 10; p.z = 9.5;
        int[] at = Placement.place(w, p, new Raycast.Hit(5, 9, 5, 0, 1, 0), 1, Blocks.COBBLESTONE_STAIRS);
        assertArrayEquals(new int[] {5, 10, 5}, at);
        assertEquals(Blocks.stair(Blocks.COBBLESTONE_STAIRS, Shapes.NORTH, false), w.get(5, 10, 5), "climbs away from the player");
        w.set(5, 13, 3, Blocks.STONE);
        Placement.place(w, p, new Raycast.Hit(5, 13, 3, 0, -1, 0), 0, Blocks.OAK_STAIRS);
        assertEquals(Blocks.stair(Blocks.OAK_STAIRS, Shapes.NORTH, true), w.get(5, 12, 3), "under a ceiling: upside down");
        Placement.place(w, p, new Raycast.Hit(7, 9, 5, 0, 1, 0), 1, Blocks.OAK_WINDOW);
        assertEquals(Blocks.OAK_WINDOW, w.get(7, 10, 5), "seen from the south: the pane runs along x");
        p.x = 12.5; p.z = 6.5;
        Placement.place(w, p, new Raycast.Hit(8, 9, 6, 0, 1, 0), 1, Blocks.OAK_WINDOW);
        assertEquals(Blocks.OAK_WINDOW + 1, w.get(8, 10, 6), "seen from the east: along z");
    }

    @Test
    void meshedBoxByBoxAndHiddenWhereNeighboursCoverThem() {
        World w = new World(0);
        Chunk c = w.getOrGenerateEmpty(0, 0);
        c.set(5, 5, 5, OAK_N);
        ChunkMesher m = new ChunkMesher();
        ChunkMesher.Result r = m.mesh(w, c, Chunk.sectionIndex(5));
        assertEquals(12, r.quads(), "two boxes, six faces each");
        for (int q = 0; q < r.quads(); q++) assertEquals(ChunkMesher.SPECIAL_BOX, ChunkMesher.special(r.data(), q));
        assertEquals(r.quads(), r.opaqueQuads());
        c.set(5, 4, 5, Blocks.STONE); // the bottom face is hidden by the stone
        c.set(5, 5, 4, Blocks.STONE); // and the back (north) of the slab and the step
        r = m.mesh(w, c, Chunk.sectionIndex(5));
        assertEquals(12 - 3, r.quads() - countStone(r), "the slab's bottom and back and the step's back are culled");
        // Two windows side by side: the glass and frame faces between them are not drawn.
        Chunk d = w.getOrGenerateEmpty(1, 0);
        d.set(5, 5, 5, Blocks.OAK_WINDOW);
        int one = m.mesh(w, d, Chunk.sectionIndex(5)).quads();
        d.set(6, 5, 5, Blocks.OAK_WINDOW);
        ChunkMesher.Result two = m.mesh(w, d, Chunk.sectionIndex(5));
        assertTrue(two.quads() < 2 * one - 4, two.quads() + " vs " + one);
        assertTrue(two.cutoutQuads() > 0 && two.opaqueQuads() > 0, "glass is cutout, the oak frame opaque");
    }

    /** Quads of the stone blocks in a stair test mesh (not SPECIAL_BOX). */
    private static int countStone(ChunkMesher.Result r) {
        int n = 0;
        for (int q = 0; q < r.quads(); q++) if (ChunkMesher.special(r.data(), q) != ChunkMesher.SPECIAL_BOX) n++;
        return n;
    }
}
