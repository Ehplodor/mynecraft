package net.mynecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkMesherTest {
    private static World emptyWorld() {
        return new World(0);
    }

    private static Chunk put(World w, int cx, int cz) {
        return w.getOrGenerateEmpty(cx, cz);
    }

    private static ChunkMesher.Result mesh(World w, Chunk c) {
        return new ChunkMesher().mesh(w, c, 0);
    }

    @Test
    void singleBlockHasSixFaces() {
        World w = emptyWorld();
        Chunk c = put(w, 0, 0);
        c.set(3, 4, 5, Blocks.STONE);
        assertEquals(6, mesh(w, c).opaqueQuads());
    }

    @Test
    void fullLayerMergesIntoSixQuads() {
        World w = emptyWorld();
        Chunk c = put(w, 0, 0);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) c.set(x, 2, z, Blocks.GRASS);
        assertEquals(6, mesh(w, c).quads());
    }

    @Test
    void facesAgainstNeighbourChunkAreCulled() {
        World w = emptyWorld();
        Chunk a = put(w, 0, 0);
        Chunk b = put(w, 1, 0);
        a.set(15, 0, 0, Blocks.STONE);
        b.set(0, 0, 0, Blocks.STONE);
        assertEquals(5, mesh(w, a).quads());
        assertEquals(5, mesh(w, b).quads());
    }

    @Test
    void differentBlocksAreNotMerged() {
        World w = emptyWorld();
        Chunk c = put(w, 0, 0);
        c.set(0, 0, 0, Blocks.STONE);
        c.set(1, 0, 0, Blocks.DIRT);
        // 2 blocks side by side: 10 exposed faces, none mergeable across ids.
        assertEquals(10, mesh(w, c).quads());
    }

    @Test
    void layersAreSeparatedAndTransparentNeighboursKeepFaces() {
        World w = emptyWorld();
        Chunk c = put(w, 0, 0);
        c.set(5, 5, 5, Blocks.STONE);
        c.set(6, 5, 5, Blocks.GLASS);
        c.set(10, 5, 10, Blocks.GLASS);
        c.set(11, 5, 10, Blocks.GLASS);
        c.set(5, 5, 12, Blocks.WATER);
        ChunkMesher.Result r = mesh(w, c);
        assertEquals(6, r.opaqueQuads(), "stone keeps its face against glass");
        // Glass next to stone hides that face (5); the glass pair merges and hides its shared faces (6).
        assertEquals(11, r.cutoutQuads());
        assertEquals(6, r.translucentQuads());
    }

    @Test
    void plantsAreFourDoubleSidedQuads() {
        World w = emptyWorld();
        Chunk c = put(w, 0, 0);
        c.set(4, 1, 4, Blocks.SHORT_GRASS);
        c.set(4, 0, 4, Blocks.GRASS);
        ChunkMesher.Result r = mesh(w, c);
        assertEquals(4, r.cutoutQuads());
        assertEquals(6, r.opaqueQuads(), "a plant does not hide the top of the block below");
    }

    @Test
    void ambientOcclusionDarkensCornersAndSplitsMerging() {
        World w = emptyWorld();
        Chunk c = put(w, 0, 0);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) c.set(x, 2, z, Blocks.STONE);
        c.set(8, 3, 8, Blocks.STONE); // a bump: its neighbours on the floor get AO
        ChunkMesher.Result r = mesh(w, c);
        assertTrue(r.quads() > 6 + 5);
        int darkest = 3;
        int[] d = r.data();
        for (int i = 1; i < d.length; i += ChunkMesher.INTS_PER_VERTEX) darkest = Math.min(darkest, (d[i] >> 8) & 3);
        assertEquals(1, darkest, "floor corner touching the bump on one side + its diagonal");
    }

    @Test
    void packedVerticesStayInRange() {
        World w = new World(42);
        Chunk c = w.getOrGenerate(64, -64); // coordinates are stored mod 64
        ChunkMesher m = new ChunkMesher();
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
            int[] d = m.mesh(w, c, sy).data();
            for (int i = 0; i < d.length; i += ChunkMesher.INTS_PER_VERTEX) {
                int p = d[i];
                assertTrue((p & 31) <= 16 && ((p >> 5) & 31) <= 16 && ((p >> 10) & 31) <= 16);
                assertTrue(((p >> 15) & 7) < 7);
                int layer = (p >> 18) & 1023;
                assertTrue(layer > 0 && layer < Blocks.COUNT);
                assertEquals(0, p >>> 30);
                int l = d[i + 1];
                assertEquals(0, (l >> 10) & 63, "section x mod 64");
                assertEquals(0, (l >> 16) & 63, "section z mod 64");
                assertEquals(sy, (l >> 22) & 31, "section y");
                assertEquals(0, l >>> 27);
            }
        }
    }

    @Test
    void benchmarkGenerateAndMesh() {
        World w = new World(1337);
        int r = 8;
        long t0 = System.nanoTime();
        for (int x = -r - 1; x <= r + 1; x++) for (int z = -r - 1; z <= r + 1; z++) w.getOrGenerate(x, z);
        long t1 = System.nanoTime();
        ChunkMesher m = new ChunkMesher();
        long quads = 0;
        int sections = 0;
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            Chunk c = w.chunk(x, z);
            for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
                if (c.section(sy) == null) continue;
                quads += m.mesh(w, c, sy).quads();
                sections++;
            }
        }
        long t2 = System.nanoTime();
        System.out.printf("gen+light: %.3f ms/chunk | mesh: %.3f ms/section (%d sections) | %.1f quads/section%n",
            (t1 - t0) / 1e6 / ((2 * r + 3) * (2 * r + 3)), (t2 - t1) / 1e6 / sections, sections, quads / (double) sections);
        assertTrue(sections > 0);
    }
}
