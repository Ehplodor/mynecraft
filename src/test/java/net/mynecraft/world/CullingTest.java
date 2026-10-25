package net.mynecraft.world;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CullingTest {
    private static final int WEST = 0, EAST = 1, DOWN = 2, UP = 3, NORTH = 4, SOUTH = 5;

    @Test
    void visibilityOfEmptySolidAndSplitSections() {
        SectionVisibility v = new SectionVisibility();
        assertEquals(SectionVisibility.ALL, v.compute(null));
        assertEquals(SectionVisibility.ALL, v.compute(new Section()));

        Section solid = new Section();
        for (int i = 0; i < Section.VOLUME; i++) solid.set(i & 15, i >> 8, (i >> 4) & 15, Blocks.STONE);
        assertEquals(0, v.compute(solid));

        // Horizontal stone floor at y = 7: the top half and the bottom half no longer see each other.
        Section floor = new Section();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) floor.set(x, 7, z, Blocks.STONE);
        int vis = v.compute(floor);
        assertFalse(SectionVisibility.connected(vis, DOWN, UP));
        assertTrue(SectionVisibility.connected(vis, UP, EAST));
        assertTrue(SectionVisibility.connected(vis, DOWN, WEST));
        assertTrue(SectionVisibility.connected(vis, NORTH, SOUTH));

        // Glass and leaves do not block sight.
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) floor.set(x, 7, z, Blocks.GLASS);
        assertTrue(SectionVisibility.connected(v.compute(floor), DOWN, UP));
    }

    /** Synthetic world: sections at sy <= groundTop are solid, everything above is open. */
    private static OcclusionCuller.Graph ground(int groundTop) {
        return new OcclusionCuller.Graph() {
            public int visibility(int cx, int sy, int cz) { return sy <= groundTop ? 0 : SectionVisibility.ALL; }
            public boolean inFrustum(int cx, int sy, int cz) { return true; }
        };
    }

    @Test
    void solidGroundHidesEverythingBelowIt() {
        OcclusionCuller c = new OcclusionCuller(4);
        Set<Long> seen = new HashSet<>();
        int n = c.cull(0, 5, 0, ground(2), (cx, sy, cz) -> seen.add(World.sectionKey(cx, sy, cz)));
        // Visible: open sections 3..top of every column, plus the solid top layer sy = 2 (its faces show).
        assertEquals(9 * 9 * (Chunk.SECTIONS - 2), n);
        assertTrue(seen.contains(World.sectionKey(3, 2, -1)));
        assertFalse(seen.contains(World.sectionKey(0, 1, 0)));
        assertFalse(seen.contains(World.sectionKey(4, 0, 4)));
    }

    @Test
    void wallHidesWhatIsBehindIt() {
        OcclusionCuller c = new OcclusionCuller(6);
        // A wall of solid sections at cx = 2, full height, everything else open.
        Set<Long> seen = new HashSet<>();
        c.cull(0, 3, 0, new OcclusionCuller.Graph() {
            public int visibility(int cx, int sy, int cz) { return cx == 2 && Math.abs(cz) <= 6 ? 0 : SectionVisibility.ALL; }
            public boolean inFrustum(int cx, int sy, int cz) { return true; }
        }, (cx, sy, cz) -> seen.add(World.sectionKey(cx, sy, cz)));
        assertTrue(seen.contains(World.sectionKey(2, 3, 0)), "the wall itself is visible");
        assertFalse(seen.contains(World.sectionKey(4, 3, 0)), "behind the wall");
        assertTrue(seen.contains(World.sectionKey(-4, 3, 0)));
    }

    @Test
    void frustumAndUnloadedSectionsStopTheWalk() {
        OcclusionCuller c = new OcclusionCuller(4);
        int n = c.cull(0, 3, 0, new OcclusionCuller.Graph() {
            public int visibility(int cx, int sy, int cz) { return cx < -1 ? -1 : SectionVisibility.ALL; }
            public boolean inFrustum(int cx, int sy, int cz) { return cz >= 0; }
        }, (cx, sy, cz) -> assertTrue(cx >= -1 && cz >= 0));
        assertTrue(n > 0);
    }

    @Test
    void allocatorReusesAndCoalesces() {
        QuadAllocator a = new QuadAllocator(100);
        int x = a.allocate(30), y = a.allocate(30), z = a.allocate(30);
        assertEquals(0, x);
        assertEquals(30, y);
        assertEquals(60, z);
        assertEquals(-1, a.allocate(20));
        a.release(x, 30);
        a.release(y, 30); // coalesces with x's block into 60 free quads
        assertEquals(0, a.allocate(55));
        a.grow(200);
        assertEquals(90, a.allocate(110)); // trailing 10 free + 100 grown, merged
        assertEquals(30 + 55 + 110, a.used());
    }
}
