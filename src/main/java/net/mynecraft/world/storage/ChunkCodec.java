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
 * Height matches 1.18+ (sections Y -4..19); foreign blocks are mapped to the closest known block.
 */
public final class ChunkCodec {
    public static final int DATA_VERSION = 3953; // 1.21

    private static final Set<String> NON_SOLID = Set.of(
        "air", "cave_air", "void_air", "lava", "snow", "vine", "bubble_column", "light", "structure_void",
        "kelp", "kelp_plant", "seagrass", "tall_seagrass");
    private static final Set<String> PLANTS = Set.of("short_grass", "grass", "tall_grass", "fern", "large_fern",
        "dead_bush");

    private ChunkCodec() {}

    public static byte blockFromName(String id) {
        return blockFromState(id, Map.of());
    }

    /**
     * Maps a Java block state to ours: exact name (and properties) first, then the closest family
     * keeping its properties (any log keeps its axis, any slab its type), then stone.
     */
    public static byte blockFromState(String id, Map<String, String> properties) {
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        byte exact = Blocks.fromState(n, properties);
        if (exact >= 0) return exact;
        if (n.endsWith("_slab")) return Blocks.fromState("oak_slab", properties);
        if (NON_SOLID.contains(n) || n.endsWith("torch") || n.endsWith("_button") || n.endsWith("_sign")
            || n.endsWith("_carpet") || n.endsWith("_pressure_plate") || n.endsWith("rail")) return Blocks.AIR;
        if (PLANTS.contains(n)) return Blocks.SHORT_GRASS;
        if (n.endsWith("_flower") || n.endsWith("tulip") || n.endsWith("sapling") || n.equals("dandelion")
            || n.equals("cornflower") || n.equals("azure_bluet") || n.equals("oxeye_daisy")) return Blocks.POPPY;
        // Wood families: birch and spruce exist, other species fall back to oak.
        String wood = n.startsWith("birch_") || n.startsWith("stripped_birch_") ? "birch"
            : n.startsWith("spruce_") || n.startsWith("stripped_spruce_") ? "spruce" : "oak";
        if (n.endsWith("_leaves")) return Blocks.fromState(wood + "_leaves", properties);
        if (n.endsWith("_log") || n.endsWith("_wood") || n.endsWith("_stem")) return Blocks.fromState(wood + "_log", properties);
        if (n.endsWith("_planks")) return Blocks.fromState(wood + "_planks", properties);
        if (n.endsWith("_ore")) {
            for (String m : new String[] {"coal", "iron", "gold", "diamond"}) if (n.contains(m)) return Blocks.fromState(m + "_ore", properties);
            return Blocks.STONE;
        }
        if (n.contains("deepslate") || n.equals("tuff")) return Blocks.DEEPSLATE;
        for (String v : new String[] {"granite", "diorite", "andesite"}) if (n.contains(v)) return Blocks.fromState(v, properties);
        if (n.contains("glass")) return Blocks.GLASS;
        if (n.contains("cobble")) return Blocks.COBBLESTONE;
        if (n.contains("sand") && !n.contains("stone")) return Blocks.SAND;
        if (n.contains("dirt") || n.equals("podzol") || n.equals("mycelium") || n.equals("farmland")
            || n.equals("mud") || n.equals("clay")) return Blocks.DIRT;
        if (n.contains("lantern") || n.equals("sea_lantern") || n.equals("shroomlight")) return Blocks.GLOWSTONE;
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
            int y = Nbt.getInt(sec, "Y", -999) - Chunk.MIN_Y / 16; // section index
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
                Map<String, String> props = new java.util.HashMap<>();
                Map<String, Object> p = Nbt.getCompound(entry, "Properties");
                if (p != null) for (var e : p.entrySet()) props.put(e.getKey(), String.valueOf(e.getValue()));
                ids[i] = blockFromState(String.valueOf(entry.get("Name")), props);
            }
            fill(chunk, y, ids, states);
        }
        chunk.compact();
        net.mynecraft.world.LightEngine.initChunk(chunk);
        chunk.markClean();
        return chunk;
    }

    private static void fill(Chunk chunk, int sy, byte[] ids, long[] states) {
        int base = Chunk.sectionMinY(sy);
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
        root.put("yPos", Chunk.MIN_Y / 16);
        root.put("Status", "minecraft:full");
        root.put("LastUpdate", 0L);
        Nbt.NbtList sections = new Nbt.NbtList(Nbt.COMPOUND);
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
            Section s = chunk.section(sy);
            if (s == null) continue;
            Map<String, Object> sec = Nbt.compound();
            sec.put("Y", (byte) (sy + Chunk.MIN_Y / 16));
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
                if (!Blocks.STATE[b].isEmpty()) {
                    Map<String, Object> props = Nbt.compound();
                    for (String kv : Blocks.STATE[b].split(",")) {
                        int eq = kv.indexOf('=');
                        props.put(kv.substring(0, eq), kv.substring(eq + 1));
                    }
                    e.put("Properties", props);
                }
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
