package net.mynecraft.world.storage;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class StorageTest {
    private static void assertSameBlocks(Chunk a, Chunk b) {
        for (int y = Chunk.MIN_Y; y < Chunk.MAX_Y; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    assertEquals(a.get(x, y, z), b.get(x, y, z), "at " + x + "," + y + "," + z);
    }

    @Test
    void nbtRoundTripsAllTagTypes() throws IOException {
        Map<String, Object> root = Nbt.compound();
        root.put("b", (byte) -3);
        root.put("s", (short) 300);
        root.put("i", 123456);
        root.put("l", Long.MIN_VALUE);
        root.put("f", 1.5f);
        root.put("d", -2.25);
        root.put("ba", new byte[] {1, 2});
        root.put("str", "héllo");
        root.put("ia", new int[] {7, 8});
        root.put("la", new long[] {9L, -1L});
        Nbt.NbtList list = new Nbt.NbtList(Nbt.COMPOUND);
        Map<String, Object> inner = Nbt.compound();
        inner.put("x", 1);
        list.values().add(inner);
        root.put("list", list);
        root.put("empty", new Nbt.NbtList(Nbt.INT));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Nbt.writeGzip(out, root);
        Map<String, Object> back = Nbt.readGzip(new java.io.ByteArrayInputStream(out.toByteArray()));
        assertEquals((byte) -3, back.get("b"));
        assertEquals((short) 300, back.get("s"));
        assertEquals(Long.MIN_VALUE, back.get("l"));
        assertEquals("héllo", back.get("str"));
        assertArrayEquals(new long[] {9L, -1L}, (long[]) back.get("la"));
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) Nbt.getList(back, "list").get(0);
        assertEquals(1, Nbt.getInt(first, "x", -1));
        assertEquals(Nbt.INT, ((Nbt.NbtList) back.get("empty")).elementType());
    }

    @Test
    void chunkCodecRoundTripsGeneratedTerrain() throws IOException {
        World w = new World(99);
        Chunk c = w.getOrGenerate(3, -7);
        Chunk back = ChunkCodec.decode(Nbt.read(Nbt.toBytes(ChunkCodec.encode(c))), 3, -7);
        assertSameBlocks(c, back);
    }

    @Test
    void codecHandlesManyPaletteEntriesAndVanillaNames() {
        // Hand-built 1.18+ section with a 17-entry palette -> 5 bits per entry, 12 entries per long.
        Map<String, Object> root = Nbt.compound();
        Nbt.NbtList sections = new Nbt.NbtList(Nbt.COMPOUND);
        Map<String, Object> sec = Nbt.compound();
        sec.put("Y", (byte) 4);
        Nbt.NbtList palette = new Nbt.NbtList(Nbt.COMPOUND);
        String[] names = {"air", "stone", "dirt", "grass_block", "sand", "bedrock", "water", "red_sand",
            "deepslate", "oak_log", "poppy", "podzol", "andesite", "torch", "sandstone", "granite", "cave_air"};
        for (String n : names) { Map<String, Object> e = Nbt.compound(); e.put("Name", "minecraft:" + n); palette.values().add(e); }
        long[] data = new long[(4096 + 11) / 12];
        for (int i = 0; i < 4096; i++) data[i / 12] |= (long) (i % names.length) << ((i % 12) * 5);
        Map<String, Object> bs = Nbt.compound();
        bs.put("palette", palette);
        bs.put("data", data);
        sec.put("block_states", bs);
        sections.values().add(sec);
        root.put("sections", sections);

        Chunk c = ChunkCodec.decode(root, 0, 0);
        byte[] expected = {Blocks.AIR, Blocks.STONE, Blocks.DIRT, Blocks.GRASS, Blocks.SAND, Blocks.BEDROCK,
            Blocks.WATER, Blocks.SAND, Blocks.STONE, Blocks.OAK_LOG, Blocks.POPPY, Blocks.DIRT, Blocks.STONE,
            Blocks.AIR, Blocks.STONE, Blocks.STONE, Blocks.AIR};
        for (int i = 0; i < 4096; i++) {
            assertEquals(expected[i % names.length], c.get(i & 15, 64 + (i >> 8), (i >> 4) & 15));
        }
    }

    @Test
    void regionFileStoresGrowsAndRelocatesChunks(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.0.0.mca");
        Random rnd = new Random(1);
        byte[] small = new byte[100], big = new byte[20_000];
        rnd.nextBytes(small);
        rnd.nextBytes(big); // incompressible: needs several sectors
        try (RegionFile r = new RegionFile(p, false)) {
            r.write(0, 0, small);
            r.write(31, 31, small);
            r.write(0, 0, big); // grows: must move without overwriting 31,31
        }
        try (RegionFile r = new RegionFile(p, true)) {
            assertArrayEquals(big, read(r, 0, 0));
            assertArrayEquals(small, read(r, 31, 31));
            assertNull(r.read(5, 5));
        }
    }

    private static byte[] read(RegionFile r, int x, int z) throws IOException {
        try (InputStream in = r.read(x, z)) { return in.readAllBytes(); }
    }

    @Test
    void worldSavesOnlyDirtyChunksAndReloads(@TempDir Path dir) throws IOException {
        WorldStorage s = WorldStorage.open(dir.resolve("w"), 42, false);
        assertFalse(s.readOnly);
        World w = new World(s.seed);
        Chunk edited = w.getOrGenerate(40, -3);
        Chunk untouched = w.getOrGenerate(41, -3);
        w.set(40 * 16 + 5, 100, -3 * 16 + 5, Blocks.BEDROCK);
        assertTrue(s.enqueueSave(edited));
        assertFalse(s.enqueueSave(untouched));
        s.flush(edited);
        s.saveLevel(new double[] {1.5, 70, -2.5});
        s.close();

        WorldStorage s2 = WorldStorage.open(dir.resolve("w"), 0, false);
        assertEquals(42, s2.seed);
        assertArrayEquals(new double[] {1.5, 70, -2.5}, s2.playerPos);
        Chunk back = s2.load(40, -3);
        assertNotNull(back);
        assertSameBlocks(edited, back);
        assertNull(s2.load(41, -3));
        s2.close();
    }

    @Test
    void foreignWorldOpensReadOnly(@TempDir Path dir) throws IOException {
        Map<String, Object> data = Nbt.compound();
        Map<String, Object> wgs = Nbt.compound();
        wgs.put("seed", 1234L);
        data.put("WorldGenSettings", wgs);
        Map<String, Object> root = Nbt.compound();
        root.put("Data", data);
        Files.createDirectories(dir);
        try (var out = Files.newOutputStream(dir.resolve("level.dat"))) { Nbt.writeGzip(out, root); }
        byte[] before = Files.readAllBytes(dir.resolve("level.dat"));

        WorldStorage s = WorldStorage.open(dir, 0, false);
        assertTrue(s.readOnly);
        assertEquals(1234L, s.seed);
        World w = new World(s.seed);
        Chunk c = w.getOrGenerate(0, 0);
        c.markDirty();
        assertFalse(s.enqueueSave(c));
        s.saveLevel(new double[] {0, 0, 0});
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("level.dat")));
        assertFalse(Files.exists(dir.resolve("region")));
    }
}
