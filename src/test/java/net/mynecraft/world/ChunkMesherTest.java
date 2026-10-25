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
        return new ChunkMesher().mesh(w, c, Chunk.sectionIndex(0)); // the section holding y 0..15
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
        for (int q = 0; q < r.quads(); q++) for (int k = 0; k < 4; k++) darkest = Math.min(darkest, ChunkMesher.ao(d, q, k));
        assertEquals(1, darkest, "floor corner touching the bump on one side + its diagonal");
    }

    @Test
    void smoothLightAveragesAroundVertices() {
        World w = emptyWorld();
        Chunk c = put(w, 0, 0);
        c.set(4, 4, 4, Blocks.STONE);
        c.setLight(5, 4, 4, 0xFF); // sky 15 + block light 15 east of the stone; elsewhere sky only
        ChunkMesher.Result r = mesh(w, c);
        int[] d = r.data();
        int lit = 0;
        for (int q = 0; q < r.quads(); q++) {
            for (int k = 0; k < 4; k++) {
                int l = ChunkMesher.light(d, q, k);
                if ((l >> 6) != 0) {
                    lit++;
                    // East face corners: the lit front cell + 3 dark samples = sum 15 (average 3.75).
                    assertEquals(15, l >> 6);
                    assertEquals(1, ChunkMesher.face(d, q), "only the east face sees the lit cell");
                }
                assertEquals(60, l & 63, "full sky everywhere: 4 x 15");
            }
        }
        assertEquals(4, lit);
    }

    @Test
    void waterSurfaceIsLowered() {
        World w = emptyWorld();
        Chunk c = put(w, 0, 0);
        c.set(3, 3, 3, Blocks.WATER);
        c.set(3, 2, 3, Blocks.WATER);
        ChunkMesher.Result r = mesh(w, c);
        int[] d = r.data();
        int surface = 0;
        for (int q = 0; q < r.quads(); q++) {
            if (ChunkMesher.special(d, q) == ChunkMesher.SPECIAL_WATER) {
                surface++;
                int f = ChunkMesher.face(d, q);
                assertTrue(f != 2, "the bottom face is never lowered");
                // The top face sits on y = 4 (block 3 + 1); side quads of the top block start at y = 3.
                assertEquals(f == 3 ? 4 : 3, ChunkMesher.originY(d, q));
            }
        }
        assertEquals(1 + 4, surface, "top face + the 4 sides of the top block");
    }

    private static int countSpecial(ChunkMesher.Result r, int special) {
        int n = 0;
        for (int q = 0; q < r.quads(); q++) if (ChunkMesher.special(r.data(), q) == special) n++;
        return n;
    }

    @Test
    void slabsAreHalfHeight() {
        World w = emptyWorld();
        Chunk c = put(w, 0, 0);
        c.set(2, 2, 2, Blocks.OAK_SLAB);
        c.set(8, 2, 8, Blocks.OAK_SLAB_TOP);
        ChunkMesher.Result r = mesh(w, c);
        assertEquals(12, r.opaqueQuads());
        assertEquals(6, countSpecial(r, ChunkMesher.SPECIAL_SLAB_BOTTOM));
        assertEquals(6, countSpecial(r, ChunkMesher.SPECIAL_SLAB_TOP));

        // A slab on stone hides the stone's top? No: the slab is not a full cube, so the stone keeps it.
        c.set(2, 1, 2, Blocks.STONE);
        assertEquals(12 + 6 - 1, mesh(w, c).opaqueQuads(), "only the slab's bottom face is hidden");
    }

    @Test
    void packedVerticesStayInRange() {
        World w = new World(42);
        Chunk c = w.getOrGenerate(64, -64); // coordinates are stored mod 64
        ChunkMesher m = new ChunkMesher();
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
            ChunkMesher.Result r = m.mesh(w, c, sy);
            int[] d = r.data();
            assertEquals(r.quads() * ChunkMesher.INTS_PER_QUAD, d.length);
            for (int q = 0; q < r.quads(); q++) {
                int w0 = d[q * 4];
                assertTrue((w0 & 31) <= 16 && ((w0 >> 5) & 31) <= 16 && ((w0 >> 10) & 31) <= 16);
                assertTrue(ChunkMesher.face(d, q) < 7);
                int special = ChunkMesher.special(d, q);
                assertTrue(special != ChunkMesher.SPECIAL_SLAB_BOTTOM && special != ChunkMesher.SPECIAL_SLAB_TOP,
                    "no slabs in generated terrain");
                int layer = ChunkMesher.layer(d, q);
                assertTrue(layer > 0 && layer < Blocks.COUNT);
                assertEquals(0, w0 >>> 30);
                int sw = ChunkMesher.sectionWord(d, q);
                assertEquals(0, (sw >> 12) & 63, "section x mod 64");
                assertEquals(0, (sw >> 18) & 63, "section z mod 64");
                assertEquals(sy, (sw >> 24) & 31, "section y");
                assertEquals(0, sw >>> 29);
                for (int k = 0; k < 4; k++) {
                    int l = ChunkMesher.light(d, q, k);
                    assertTrue((l & 63) <= 60 && (l >> 6) <= 60, "smooth light sums");
                }
            }
        }
    }

    @Test
    void generationHasDeepslateOresAndSeveralTreeSpecies() {
        World w = new World(7);
        int[] counts = new int[Blocks.COUNT];
        for (int cx = -12; cx < 12; cx++)
            for (int cz = -12; cz < 12; cz++) {
                Chunk c = w.generator.generate(cx, cz);
                for (int y = Chunk.MIN_Y; y < Chunk.MAX_Y; y++)
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) counts[c.get(x, y, z)]++;
                byte deep = c.get(3, -20, 3);
                assertTrue(deep == Blocks.DEEPSLATE || deep == Blocks.AIR || Blocks.NAMES[deep].endsWith("_ore"),
                    "below y=0: deepslate, ore or cave");
            }
        for (byte b : new byte[] {Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.GOLD_ORE, Blocks.DIAMOND_ORE, Blocks.GRANITE,
            Blocks.DIORITE, Blocks.ANDESITE, Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG}) {
            assertTrue(counts[b] > 0, "no " + Blocks.NAMES[b] + " generated");
        }
        assertTrue(counts[Blocks.COAL_ORE] > counts[Blocks.DIAMOND_ORE]);
        // Caves: a few percent of the deep volume is air.
        long deepAir = 0;
        for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 4; cz++) {
            Chunk c = w.generator.generate(cx, cz);
            for (int y = -50; y < 0; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (c.get(x, y, z) == Blocks.AIR) deepAir++;
        }
        assertTrue(deepAir > 0, "no caves");
    }

    @Test
    void benchmarkGenerateJoinAndMesh() {
        int r = 8, n = (2 * r + 3) * (2 * r + 3);
        World w = new World(1337);
        Chunk[] fresh = new Chunk[n];
        // Warm-up pass so the JIT does not dominate the numbers.
        for (int i = 0; i < 64; i++) w.generator.generate(100 + i, 100);

        long t0 = System.nanoTime();
        int k = 0;
        for (int x = -r - 1; x <= r + 1; x++) for (int z = -r - 1; z <= r + 1; z++) fresh[k++] = w.generator.generate(x, z);
        long t1 = System.nanoTime();
        for (Chunk c : fresh) { w.put(c); w.light.chunkJoined(c); } // main-thread cross-border light
        long t2 = System.nanoTime();
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
        long t3 = System.nanoTime();
        System.out.printf("gen+light: %.3f ms/chunk | join: %.3f ms/chunk | mesh: %.3f ms/section (%d sections) | %.1f quads/section%n",
            (t1 - t0) / 1e6 / n, (t2 - t1) / 1e6 / n, (t3 - t2) / 1e6 / sections, sections, quads / (double) sections);
        assertTrue(sections > 0);
    }
}
