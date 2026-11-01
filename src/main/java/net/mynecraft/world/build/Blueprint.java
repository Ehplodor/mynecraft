package net.mynecraft.world.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mynecraft.world.Blocks;
import net.mynecraft.world.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A building plan: a box of block states, {@code w} x {@code h} x {@code d}, anchored on its lowest
 * north-west corner. A plan is traced from the world (two corners), saved as JSON in the world's
 * {@code blueprints} folder, and raised block by block: {@link #next} gives the next block to set.
 * <p>
 * Order of the steps: first what stands in the way (plants, leaves, earth in an empty cell), then
 * the full blocks from the bottom up, then the rest (doors, windows, roofs), the furniture last, so
 * that a bed in place means the home is finished. Empty cells only clear natural obstacles: what
 * the inhabitants put inside (a pot, a chest) stays.
 */
public final class Blueprint {
    /** The plan everybody knows: the hamlet's hut. */
    public static final String HUT = "cabane";

    public final String name;
    public final int w, h, d;
    /** Block states, index {@code (y * d + z) * w + x}. */
    public final short[] cells;

    public Blueprint(String name, int w, int h, int d, short[] cells) {
        if (w <= 0 || h <= 0 || d <= 0 || cells.length != w * h * d) throw new IllegalArgumentException("bad plan size");
        this.name = name;
        this.w = w;
        this.h = h;
        this.d = d;
        this.cells = cells;
    }

    public short at(int x, int y, int z) {
        return cells[(y * d + z) * w + x];
    }

    private void put(int x, int y, int z, short b) {
        cells[(y * d + z) * w + x] = b;
    }

    // ---------------------------------------------------------------------------- the hut

    /**
     * A small hut, 5 x 5 x 6: a cobblestone footing, plank walls with log corners, a door to the
     * south (the yard), a window on each side, a thatch roof rising to a ridge, and a straw bed.
     */
    public static Blueprint hut() {
        int w = 5, h = 5, d = 6;
        Blueprint p = new Blueprint(HUT, w, h, d, new short[w * h * d]);
        for (int z = 0; z < d; z++) for (int x = 0; x < w; x++) p.put(x, 0, z, Blocks.COBBLESTONE);
        for (int y = 1; y <= 3; y++)
            for (int z = 0; z < d; z++)
                for (int x = 0; x < w; x++) {
                    boolean edgeX = x == 0 || x == w - 1, edgeZ = z == 0 || z == d - 1;
                    if (edgeX && edgeZ) p.put(x, y, z, Blocks.OAK_LOG);
                    else if (edgeX || edgeZ) p.put(x, y, z, Blocks.OAK_PLANKS);
                }
        p.put(2, 1, d - 1, Blocks.OAK_DOOR); // a door two blocks high
        p.put(2, 2, d - 1, Blocks.OAK_DOOR);
        p.put(0, 2, 2, (short) (Blocks.OAK_WINDOW + 1)); // walls along z
        p.put(w - 1, 2, 2, (short) (Blocks.OAK_WINDOW + 1));
        p.put(2, 2, 0, Blocks.OAK_WINDOW); // wall along x
        for (int z = 0; z < d; z++) { // slopes rising to a ridge along z
            p.put(0, 4, z, (short) (Blocks.THATCH_ROOF + 3));
            p.put(1, 4, z, (short) (Blocks.THATCH_ROOF + 3));
            p.put(2, 4, z, Blocks.THATCH);
            p.put(3, 4, z, (short) (Blocks.THATCH_ROOF + 2));
            p.put(4, 4, z, (short) (Blocks.THATCH_ROOF + 2));
        }
        p.put(1, 1, 1, Blocks.STRAW_BED);
        return p;
    }

    // ---------------------------------------------------------------------------- tracing and raising

    /** Copies the box between two corners (in any order) of the world. */
    public static Blueprint trace(String name, World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        int x0 = Math.min(x1, x2), y0 = Math.min(y1, y2), z0 = Math.min(z1, z2);
        int w = Math.abs(x2 - x1) + 1, h = Math.abs(y2 - y1) + 1, d = Math.abs(z2 - z1) + 1;
        if ((long) w * h * d > 1 << 20) throw new IllegalArgumentException("plan too large");
        Blueprint p = new Blueprint(name, w, h, d, new short[w * h * d]);
        for (int y = 0; y < h; y++)
            for (int z = 0; z < d; z++)
                for (int x = 0; x < w; x++) p.put(x, y, z, world.get(x0 + x, y0 + y, z0 + z));
        return p;
    }

    /**
     * The next block to set to raise the plan anchored at (ax, ay, az): {x, y, z, block}, or null
     * when it stands finished.
     */
    public int[] next(World world, int ax, int ay, int az) {
        for (int pass = 0; pass < 4; pass++)
            for (int y = 0; y < h; y++)
                for (int z = 0; z < d; z++)
                    for (int x = 0; x < w; x++) {
                        short want = at(x, y, z);
                        if (rank(want) != pass) continue;
                        short here = world.get(ax + x, ay + y, az + z);
                        if (todo(want, here)) return new int[] {ax + x, ay + y, az + z, want};
                    }
        return null;
    }

    /** How many blocks are left to set (or clear). */
    public int steps(World world, int ax, int ay, int az) {
        int n = 0;
        for (int y = 0; y < h; y++)
            for (int z = 0; z < d; z++)
                for (int x = 0; x < w; x++) if (todo(at(x, y, z), world.get(ax + x, ay + y, az + z))) n++;
        return n;
    }

    /** The items still needed (item -> count) to finish the plan where it stands. */
    public Map<Short, Integer> remaining(World world, int ax, int ay, int az) {
        Map<Short, Integer> m = new TreeMap<>();
        for (int y = 0; y < h; y++)
            for (int z = 0; z < d; z++)
                for (int x = 0; x < w; x++) {
                    short want = at(x, y, z);
                    if (want != Blocks.AIR && todo(want, world.get(ax + x, ay + y, az + z))) m.merge(item(want), 1, Integer::sum);
                }
        return m;
    }

    /** Every item the whole plan takes (item -> count). */
    public Map<Short, Integer> materials() {
        Map<Short, Integer> m = new TreeMap<>();
        for (short b : cells) if (b != Blocks.AIR) m.merge(item(b), 1, Integer::sum);
        return m;
    }

    /** The item that places a block (a roof's facings, an open door: the item it came from). */
    public static short item(short block) {
        short drop = Blocks.DROP[block & 0xFFFF];
        if (block == Blocks.STONE || drop == Blocks.AIR) return block; // a plan asks for what it shows
        return drop;
    }

    /** 0: obstacles to clear, 1: full blocks, 2: the rest, 3: furniture. */
    private static int rank(short b) {
        if (b == Blocks.AIR) return 0;
        if (b == Blocks.STRAW_BED) return 3;
        return Blocks.isSolid(b) && b != Blocks.OAK_DOOR ? 1 : 2;
    }

    private static boolean todo(short want, short here) {
        if (want == Blocks.AIR) return obstacle(here);
        if (want == here) return false;
        return !(want == Blocks.OAK_DOOR && here == Blocks.OAK_DOOR_OPEN); // an open door is a door
    }

    /** What may stand in an empty cell of the plan and is cleared: nature, not furniture. */
    private static boolean obstacle(short b) {
        if (b == Blocks.AIR) return false;
        if (Blocks.isPlant(b)) return true;
        String n = Blocks.NAMES[b & 0xFFFF];
        return n.endsWith("_leaves") || n.endsWith("_log") || n.equals("snow")
            || b == Blocks.STONE || b == Blocks.DIRT || b == Blocks.GRASS || b == Blocks.SAND || b == Blocks.GRAVEL;
    }

    // ---------------------------------------------------------------------------- saving

    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("w", w);
        o.addProperty("h", h);
        o.addProperty("d", d);
        JsonArray a = new JsonArray();
        for (short b : cells) a.add(b);
        o.add("cells", a);
        return o.toString();
    }

    public static Blueprint fromJson(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        JsonArray a = o.getAsJsonArray("cells");
        short[] cells = new short[a.size()];
        for (int i = 0; i < cells.length; i++) {
            int b = a.get(i).getAsInt();
            cells[i] = b >= 0 && b < Blocks.COUNT ? (short) b : Blocks.AIR; // a block that is gone: left empty
        }
        return new Blueprint(o.get("name").getAsString(), o.get("w").getAsInt(), o.get("h").getAsInt(), o.get("d").getAsInt(), cells);
    }

    /** Writes the plan as {@code <name>.json} in {@code dir}. */
    public void save(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), toJson());
    }

    /** The plans saved in {@code dir}, by name, after the hut that everybody knows. */
    public static Map<String, Blueprint> loadAll(Path dir) {
        Map<String, Blueprint> plans = new LinkedHashMap<>();
        plans.put(HUT, hut());
        if (!Files.isDirectory(dir)) return plans;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                try {
                    Blueprint b = fromJson(Files.readString(f));
                    plans.put(b.name, b);
                } catch (RuntimeException | IOException e) {
                    System.err.println("[plans] " + f.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[plans] " + e.getMessage());
        }
        return plans;
    }
}
