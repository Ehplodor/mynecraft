package net.mynecraft.world.storage;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.Section;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts chunks to and from Java Edition chunk NBT.
 *
 * <p>Reads 1.18+ ("sections" / "block_states") and 1.16–1.17 ("Level" / "Palette" + "BlockStates").
 * Block states are a palette plus a packed long[] where entries never straddle two longs, with
 * max(4, ceil(log2(palette))) bits each, indexed (y << 8) | (z << 4) | x, the same order as ours.
 * Only sections Y 0..7 fit our 0..127 height; foreign blocks are mapped to the closest known block.
 */
public final class ChunkCodec {
    public static final int DATA_VERSION = 3953; // 1.21

    private static final Set<String> NON_SOLID = Set.of(
        "air", "cave_air", "void_air", "water", "lava", "short_grass", "grass", "tall_grass", "fern",
        "large_fern", "dandelion", "poppy", "seagrass", "tall_seagrass", "kelp", "kelp_plant", "snow",
        "vine", "dead_bush", "bubble_column", "light", "structure_void");

    private ChunkCodec() {}

    public static byte blockFromName(String id) {
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        for (int i = 0; i < Blocks.COUNT; i++) if (Blocks.NAMES[i].equals(n)) return (byte) i;
        if (NON_SOLID.contains(n) || n.endsWith("_flower") || n.endsWith("sapling") || n.endsWith("torch")
            || n.endsWith("tulip") || n.endsWith("_button") || n.endsWith("_sign") || n.endsWith("_carpet")
            || n.endsWith("_pressure_plate") || n.endsWith("rail")) return Blocks.AIR;
        if (n.contains("sand")) return Blocks.SAND;
        if (n.contains("dirt") || n.equals("podzol") || n.equals("mycelium") || n.equals("farmland")
            || n.equals("dirt_path") || n.equals("mud")) return Blocks.DIRT;
        return Blocks.STONE;
    }

    public static Chunk decode(Map<String, Object> root, int expectCx, int expectCz) {
        Map<String, Object> level = Nbt.getCompound(root, "Level");
        boolean legacy = level != null;
        Map<String, Object> data = legacy ? level : root;
        Chunk chunk = new Chunk(expectCx, expectCz);
        List<Object> sections = Nbt.getList(data, legacy ? "Sections" : "sections");
        for (Object o : sections) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sec = (Map<String, Object>) o;
            int y = Nbt.getInt(sec, "Y", -999);
            if (y < 0 || y >= Chunk.SECTIONS) continue;
            List<Object> palette;
            long[] states;
            if (legacy) {
                palette = Nbt.getList(sec, "Palette");
                states = sec.get("BlockStates") instanceof long[] a ? a : null;
            } else {
                Map<String, Object> bs = Nbt.getCompound(sec, "block_states");
                if (bs == null) continue;
                palette = Nbt.getList(bs, "palette");
                states = bs.get("data") instanceof long[] a ? a : null;
            }
            if (palette.isEmpty()) continue;
            byte[] ids = new byte[palette.size()];
            for (int i = 0; i < ids.length; i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) palette.get(i);
                ids[i] = blockFromName(String.valueOf(entry.get("Name")));
            }
            fill(chunk, y, ids, states);
        }
        chunk.markClean();
        return chunk;
    }

    private static void fill(Chunk chunk, int sy, byte[] ids, long[] states) {
        int base = sy << 4;
        if (ids.length == 1 || states == null) {
            byte b = ids[0];
            if (b == Blocks.AIR) return;
            for (int i = 0; i < Section.VOLUME; i++) chunk.set(i & 15, base + (i >> 8), (i >> 4) & 15, b);
            return;
        }
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(ids.length - 1));
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        if (states.length < (Section.VOLUME + perLong - 1) / perLong) return; // corrupt: skip section
        for (int i = 0; i < Section.VOLUME; i++) {
            int p = (int) ((states[i / perLong] >>> ((i % perLong) * bits)) & mask);
            byte b = p < ids.length ? ids[p] : Blocks.STONE;
            if (b != Blocks.AIR) chunk.set(i & 15, base + (i >> 8), (i >> 4) & 15, b);
        }
    }

    public static Map<String, Object> encode(Chunk chunk) {
        Map<String, Object> root = Nbt.compound();
        root.put("DataVersion", DATA_VERSION);
        root.put("xPos", chunk.cx);
        root.put("zPos", chunk.cz);
        root.put("yPos", 0);
        root.put("Status", "minecraft:full");
        root.put("LastUpdate", 0L);
        Nbt.NbtList sections = new Nbt.NbtList(Nbt.COMPOUND);
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
            Section s = chunk.section(sy);
            if (s == null) continue;
            Map<String, Object> sec = Nbt.compound();
            sec.put("Y", (byte) sy);
            sec.put("block_states", encodeStates(s));
            Map<String, Object> biomes = Nbt.compound();
            Nbt.NbtList bp = new Nbt.NbtList(Nbt.STRING);
            bp.values().add("minecraft:plains");
            biomes.put("palette", bp);
            sec.put("biomes", biomes);
            sections.values().add(sec);
        }
        root.put("sections", sections);
        return root;
    }

    private static Map<String, Object> encodeStates(Section s) {
        // Local palette: block id -> palette index.
        int[] toPalette = new int[256];
        java.util.Arrays.fill(toPalette, -1);
        Nbt.NbtList palette = new Nbt.NbtList(Nbt.COMPOUND);
        int n = 0;
        for (int i = 0; i < Section.VOLUME; i++) {
            int b = s.get(i & 15, i >> 8, (i >> 4) & 15) & 0xFF;
            if (toPalette[b] < 0) {
                toPalette[b] = n++;
                Map<String, Object> e = Nbt.compound();
                e.put("Name", "minecraft:" + Blocks.NAMES[b]);
                palette.values().add(e);
            }
        }
        Map<String, Object> bs = Nbt.compound();
        bs.put("palette", palette);
        if (n > 1) {
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(n - 1));
            int perLong = 64 / bits;
            long[] data = new long[(Section.VOLUME + perLong - 1) / perLong];
            for (int i = 0; i < Section.VOLUME; i++) {
                long p = toPalette[s.get(i & 15, i >> 8, (i >> 4) & 15) & 0xFF];
                data[i / perLong] |= p << ((i % perLong) * bits);
            }
            bs.put("data", data);
        }
        return bs;
    }
}
