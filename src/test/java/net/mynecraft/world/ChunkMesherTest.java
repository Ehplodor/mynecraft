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

    @Test
    void singleBlockHasSixFaces() {
        World w = emptyWorld();
        Chunk c = put(w, 0, 0);
        c.set(3, 4, 5, Blocks.STONE);
        int[] v = new ChunkMesher().mesh(w, c, 0);
        assertEquals(6, ChunkMesher.quadCount(v));
    }

    @Test
    void fullLayerMergesIntoSixQuads() {
        World w = emptyWorld();
        Chunk c = put(w, 0, 0);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) c.set(x, 2, z, Blocks.GRASS);
        int[] v = new ChunkMesher().mesh(w, c, 0);
        assertEquals(6, ChunkMesher.quadCount(v));
    }

    @Test
    void facesAgainstNeighbourChunkAreCulled() {
        World w = emptyWorld();
        Chunk a = put(w, 0, 0);
        Chunk b = put(w, 1, 0);
        a.set(15, 0, 0, Blocks.STONE);
        b.set(0, 0, 0, Blocks.STONE);
        assertEquals(5, ChunkMesher.quadCount(new ChunkMesher().mesh(w, a, 0)));
        assertEquals(5, ChunkMesher.quadCount(new ChunkMesher().mesh(w, b, 0)));
    }

    @Test
    void differentBlocksAreNotMerged() {
        World w = emptyWorld();
        Chunk c = put(w, 0, 0);
        c.set(0, 0, 0, Blocks.STONE);
        c.set(1, 0, 0, Blocks.DIRT);
        // 2 blocks side by side: 10 exposed faces, none mergeable across ids.
        assertEquals(10, ChunkMesher.quadCount(new ChunkMesher().mesh(w, c, 0)));
    }

    @Test
    void packedVerticesStayInRange() {
        World w = new World(42);
        Chunk c = w.getOrGenerate(0, 0);
        ChunkMesher m = new ChunkMesher();
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
            for (int p : m.mesh(w, c, sy)) {
                assertTrue((p & 31) <= 16 && ((p >> 5) & 31) <= 16 && ((p >> 10) & 31) <= 16);
                assertTrue(((p >> 15) & 7) < 6);
                int block = (p >> 18) & 255;
                assertTrue(block > 0 && block < Blocks.COUNT);
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
                quads += ChunkMesher.quadCount(m.mesh(w, c, sy));
                sections++;
            }
        }
        long t2 = System.nanoTime();
        int chunks = (2 * r + 1) * (2 * r + 1);
        System.out.printf("gen: %.3f ms/chunk | mesh: %.3f ms/section (%d sections) | %.1f quads/section%n",
            (t1 - t0) / 1e6 / ((2 * r + 3) * (2 * r + 3)), (t2 - t1) / 1e6 / sections, sections, quads / (double) sections);
        assertTrue(chunks > 0);
    }
}
