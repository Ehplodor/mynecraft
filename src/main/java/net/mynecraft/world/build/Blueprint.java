package net.mynecraft.world.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mynecraft.world.Blocks;
import net.mynecraft.world.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A building plan: a box of block states, {@code w} x {@code h} x {@code d}, anchored on its lowest
 * north-west corner. A plan is traced from the world (two corners), saved as JSON in the world's
 * {@code blueprints} folder, and raised block by block: {@link #next} gives the next block to set.
 * <p>
 * The plan stands on the ground: its layer {@link #sunk} (0 for most, the plinth) is laid on the
 * levelled ground (see {@link Site}), the layers under it are dug in (a well's shaft). Named points
 * ({@link #marks}) tell the inhabitants where things are: the yard before the door, the beds, the
 * place for a pot, the hens' run, the field.
 * <p>
 * Order of the steps: first what stands in the way (plants, leaves, earth in an empty cell), then
 * the full blocks from the bottom up, then the rest (doors, windows, roofs), the furniture last, so
 * that a bed in place means the home is finished. Empty cells only clear natural obstacles: what
 * the inhabitants put inside (a pot, a chest) stays.
 */
public final class Blueprint {
    /** The plan everybody knows: the hamlet's first hut (kept for the hamlets founded before the others). */
    public static final String HUT = "cabane";
    public static final String COTTAGE = "chaumiere", HOUSE = "maison", WELL = "puits", COOP = "poulailler",
        APIARY = "rucher", BAKEHOUSE = "fournil";

    public final String name;
    public final int w, h, d;
    /** Block states, index {@code (y * d + z) * w + x}. */
    public final short[] cells;
    /** How many layers of the plan lie below the ground (its layer {@code sunk} is the first above it). */
    public final int sunk;
    /** Named points, relative to the anchor; they may lie outside the box (the yard before the door). */
    public final Map<String, int[]> marks;

    public Blueprint(String name, int w, int h, int d, short[] cells) {
        this(name, w, h, d, cells, 0, Map.of());
    }

    public Blueprint(String name, int w, int h, int d, short[] cells, int sunk, Map<String, int[]> marks) {
        if (w <= 0 || h <= 0 || d <= 0 || cells.length != w * h * d) throw new IllegalArgumentException("bad plan size");
        if (sunk < 0 || sunk >= h) throw new IllegalArgumentException("bad ground layer");
        this.name = name;
        this.w = w;
        this.h = h;
        this.d = d;
        this.cells = cells;
        this.sunk = sunk;
        this.marks = new LinkedHashMap<>(marks);
    }

    public short at(int x, int y, int z) {
        return cells[(y * d + z) * w + x];
    }

    private void put(int x, int y, int z, short b) {
        cells[(y * d + z) * w + x] = b;
    }

    /** A named point in the world for the plan anchored at {@code a}, or null. */
    public int[] mark(String key, int[] a) {
        int[] m = marks.get(key);
        return m == null ? null : new int[] {a[0] + m[0], a[1] + m[1], a[2] + m[2]};
    }

    /** The marks whose name starts with {@code prefix} ("lit" gives lit1, lit2...), in the world. */
    public List<int[]> marks(String prefix, int[] a) {
        List<int[]> out = new ArrayList<>();
        for (String k : marks.keySet()) if (k.startsWith(prefix)) out.add(mark(k, a));
        return out;
    }

    /** What the inhabitants call the building ("la maison"). */
    public static String title(String name) {
        return switch (name) {
            case HUT -> "la cabane";
            case COTTAGE -> "la chaumière";
            case HOUSE -> "la maison";
            case WELL -> "le puits";
            case COOP -> "le poulailler";
            case APIARY -> "le rucher";
            case BAKEHOUSE -> "le fournil";
            default -> "« " + name + " »";
        };
    }

    // ---------------------------------------------------------------------------- the plans

    /** The plans of the hamlet, by name. */
    public static Map<String, Blueprint> builtIn() {
        Map<String, Blueprint> m = new LinkedHashMap<>();
        for (Blueprint b : List.of(hut(), cottage(), house(), well(), coop(), apiary(), bakehouse())) m.put(b.name, b);
        return m;
    }

    /** A built-in plan by name (an unknown name: the hut). */
    public static Blueprint named(String name) {
        Blueprint b = builtIn().get(name);
        return b != null ? b : hut();
    }

    /**
     * The first hut, 5 x 5 x 6: a cobblestone footing, plank walls with log corners, a door to the
     * south (the yard), a window on each side, a thatch roof rising to a ridge, and a straw bed.
     */
    public static Blueprint hut() {
        int w = 5, h = 5, d = 6;
        Blueprint p = new Blueprint(HUT, w, h, d, new short[w * h * d], 0,
            Map.of("porte", new int[] {2, 0, d}, "lit1", new int[] {2, 1, 2}, "pot", new int[] {3, 1, 1}));
        for (int z = 0; z < d; z++) for (int x = 0; x < w; x++) p.put(x, 0, z, Blocks.COBBLESTONE);
        for (int y = 1; y <= 3; y++)
            for (int z = 0; z < d; z++)
                for (int x = 0; x < w; x++) {
                    boolean edgeX = x == 0 || x == w - 1, edgeZ = z == 0 || z == d - 1;
                    if (edgeX && edgeZ) p.put(x, y, z, Blocks.OAK_LOG);
                    else if (edgeX || edgeZ) p.put(x, y, z, Blocks.OAK_PLANKS);
                }
        p.put(2, 1, d - 1, Blocks.door(false, false, false)); // a door two blocks high
        p.put(2, 2, d - 1, Blocks.door(true, false, false));
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

    /**
     * The hermit's cottage: a long low house of 7 x 5 under a deep thatch, the ridge along its length.
     * A stone footing course, plank walls on log posts, a beam (sablière) under the eaves; the door in the
     * middle of the long south side, up a stone step, between two windows; the hearth in the west gable,
     * its chimney standing outside against it. Inside: the bed in the warm corner, a table between two
     * chairs with a lantern on it, a water barrel, a rug, the herbalist's pot and workbench.
     */
    public static Blueprint cottage() {
        Draft p = new Draft(-1, 0, -1, 7, 9, 5);
        p.fill(0, 0, 0, 6, 0, 4, Blocks.COBBLESTONE);
        p.fill(1, 0, 1, 5, 0, 3, Blocks.OAK_PLANKS); // a wooden floor inside the footing
        p.walls(0, 0, 6, 4, 1, 1, Blocks.COBBLESTONE, Blocks.OAK_LOG); // the stone course
        p.walls(0, 0, 6, 4, 2, 3, Blocks.OAK_PLANKS, Blocks.OAK_LOG);
        for (int x = 1; x <= 5; x++) { p.set(x, 3, 0, Blocks.OAK_LOG_X); p.set(x, 3, 4, Blocks.OAK_LOG_X); } // the beams
        p.door(3, 1, 4, false);
        p.step(3, 0, 5, Blocks.COBBLESTONE_STAIRS, 0);
        p.window(1, 2, 4, false);
        p.window(5, 2, 4, false);
        p.window(3, 2, 0, false);
        p.window(6, 2, 2, true);
        p.roof(-1, -1, 7, 5, 4, true, Draft.THATCH, Blocks.OAK_PLANKS, 0, 6);
        p.window(6, 5, 2, true); // the loft's window in the east gable
        // The hearth in the west gable, its chimney outside.
        p.set(0, 1, 2, Blocks.CAMPFIRE);
        p.set(0, 2, 2, Blocks.COBBLESTONE);
        p.set(0, 3, 2, Blocks.COBBLESTONE);
        p.chimney(-1, 2, 0, 8);
        // Furniture.
        p.set(5, 1, 1, Blocks.STRAW_BED);
        p.set(3, 1, 1, Blocks.OAK_TABLE);
        p.set(3, 2, 1, Blocks.LANTERN);
        p.set(2, 1, 1, (short) (Blocks.OAK_CHAIR + 2)); // backs to the walls, facing the table
        p.set(4, 1, 1, (short) (Blocks.OAK_CHAIR + 3));
        p.set(1, 1, 1, Blocks.BARREL);
        p.set(1, 1, 3, Blocks.DECORATED_POT);
        p.set(5, 1, 3, Blocks.CRAFTING_TABLE);
        p.fill(2, 1, 2, 4, 1, 2, Blocks.RUG);
        p.mark("porte", 3, 0, 6);
        p.mark("lit1", 5, 1, 2);
        p.mark("foyer", 1, 1, 2);
        return p.done(COTTAGE);
    }

    /**
     * The family's house: 9 x 7, two rooms. The common room has the hearth (chimney outside the west
     * gable), the table and three chairs, the barrel, the workbench; through an inner door, the bedroom
     * with three beds along the east wall and the loom. Half-timbered: a stone course, plank panels
     * between log posts, a beam under the eaves; a porch on fence posts before the door, under its own
     * little thatch; windows in pairs where the bedroom is, one in the east gable for the loft.
     */
    public static Blueprint house() {
        Draft p = new Draft(-1, 0, -1, 9, 10, 9);
        p.fill(0, 0, 0, 8, 0, 6, Blocks.COBBLESTONE);
        p.fill(1, 0, 1, 7, 0, 5, Blocks.OAK_PLANKS);
        p.walls(0, 0, 8, 6, 1, 1, Blocks.COBBLESTONE, Blocks.OAK_LOG);
        p.walls(0, 0, 8, 6, 2, 3, Blocks.OAK_PLANKS, Blocks.OAK_LOG);
        for (int x = 1; x <= 7; x++) { p.set(x, 3, 0, Blocks.OAK_LOG_X); p.set(x, 3, 6, Blocks.OAK_LOG_X); }
        for (int y = 1; y <= 3; y++) { p.set(4, y, 0, Blocks.OAK_LOG); p.set(4, y, 6, Blocks.OAK_LOG); } // posts
        p.fill(5, 1, 1, 5, 3, 5, Blocks.OAK_PLANKS); // the partition
        p.door(5, 1, 4, true);
        p.door(2, 1, 6, false);
        p.window(3, 2, 6, false);
        p.window(6, 2, 6, false);
        p.window(7, 2, 6, false);
        p.window(2, 2, 0, false);
        p.window(6, 2, 0, false);
        p.window(7, 2, 0, false);
        p.window(8, 2, 2, true);
        p.window(8, 2, 4, true);
        p.roof(-1, -1, 9, 7, 4, true, Draft.THATCH, Blocks.OAK_PLANKS, 0, 8);
        p.window(8, 5, 3, true);
        // The porch: a deck, two posts, a little thatch under the eaves, a step up to it.
        p.fill(1, 0, 7, 3, 0, 8, Blocks.OAK_PLANKS);
        for (int x : new int[] {1, 3}) p.fill(x, 1, 8, x, 2, 8, Blocks.OAK_FENCE);
        for (int x = 1; x <= 3; x++) { p.set(x, 3, 8, (short) (Blocks.THATCH_ROOF + 0)); p.set(x, 3, 7, Blocks.THATCH); }
        p.step(2, 0, 9, Blocks.COBBLESTONE_STAIRS, 0);
        // The hearth.
        p.set(0, 1, 3, Blocks.CAMPFIRE);
        p.set(0, 2, 3, Blocks.COBBLESTONE);
        p.set(0, 3, 3, Blocks.COBBLESTONE);
        p.chimney(-1, 3, 0, 9);
        // The common room.
        p.set(3, 1, 3, Blocks.OAK_TABLE);
        p.set(3, 2, 3, Blocks.LANTERN);
        p.set(2, 1, 3, (short) (Blocks.OAK_CHAIR + 2));
        p.set(4, 1, 3, (short) (Blocks.OAK_CHAIR + 3));
        p.set(3, 1, 2, (short) (Blocks.OAK_CHAIR + 0));
        p.set(1, 1, 1, Blocks.BARREL);
        p.set(2, 1, 1, Blocks.CRAFTING_TABLE);
        p.fill(1, 1, 4, 2, 1, 5, Blocks.RUG);
        // The bedroom.
        for (int z : new int[] {1, 3, 5}) p.set(7, 1, z, Blocks.STRAW_BED);
        p.set(7, 1, 2, Blocks.LANTERN);
        p.set(7, 1, 4, Blocks.LOOM);
        p.mark("porte", 2, 0, 10);
        p.mark("lit1", 6, 1, 1);
        p.mark("lit2", 6, 1, 3);
        p.mark("lit3", 6, 1, 5);
        p.mark("pot", 4, 1, 5);
        p.mark("champ", 5, 0, 10);
        return p.done(HOUSE);
    }

    /**
     * The well: a shaft of stone three blocks deep, full of water, a low round of stone at the ground,
     * two posts and a beam, and a little thatch roof over it.
     */
    public static Blueprint well() {
        Draft p = new Draft(-1, -3, -1, 3, 5, 3);
        for (int y = -3; y <= 0; y++) p.walls(0, 0, 2, 2, y, y, Blocks.COBBLESTONE, Blocks.COBBLESTONE);
        for (int y = -3; y <= -1; y++) p.set(1, y, 1, Blocks.WATER);
        for (int x : new int[] {0, 2}) p.fill(x, 1, 1, x, 2, 1, Blocks.OAK_FENCE);
        p.fill(0, 3, 1, 2, 3, 1, Blocks.OAK_LOG_X);
        p.roof(-1, -1, 3, 3, 3, true, Draft.THATCH, Blocks.AIR);
        p.mark("porte", 1, 0, 4);
        return p.done(WELL);
    }

    /**
     * The hen-house: a low hut of planks on a stone footing with two straw nests inside, a thatch over
     * it, a door to its run; the run fenced all round, with a gate, and a stone ramp up to the door.
     */
    public static Blueprint coop() {
        Draft p = new Draft(-1, 0, -1, 5, 5, 7);
        p.fill(0, 0, 0, 3, 0, 2, Blocks.COBBLESTONE);
        p.walls(0, 0, 3, 2, 1, 2, Blocks.OAK_PLANKS, Blocks.OAK_LOG);
        p.door(1, 1, 2, false);
        p.window(2, 2, 2, false);
        p.set(1, 1, 1, Blocks.NEST);
        p.set(2, 1, 1, Blocks.NEST);
        p.roof(-1, -1, 4, 3, 3, true, Draft.THATCH, Blocks.OAK_PLANKS, 0, 3);
        p.step(1, 0, 3, Blocks.COBBLESTONE_STAIRS, 0);
        // The run, south of the hut: fences on the ground (one below the plinth), a gate in the south side.
        for (int z = 2; z <= 7; z++) { p.set(-1, -0, z, Blocks.OAK_FENCE); p.set(5, 0, z, Blocks.OAK_FENCE); }
        for (int x = -1; x <= 5; x++) p.set(x, 0, 7, Blocks.OAK_FENCE);
        p.set(4, 0, 2, Blocks.OAK_FENCE);
        p.set(2, 0, 7, Blocks.door(false, false, false)); // the gate
        p.set(4, 0, 5, Blocks.THATCH); // a bale of straw to scratch
        p.mark("porte", 2, 0, 8);
        p.mark("poules", 2, 0, 5);
        return p.done(COOP);
    }

    /** The apiary: three hives on a stone sill against a low stone wall, under a lean-to of thatch, flowers before. */
    public static Blueprint apiary() {
        Draft p = new Draft(-1, 0, -1, 5, 2, 4);
        p.fill(-1, 0, -1, 5, 1, -1, Blocks.COBBLESTONE_WALL);
        for (int x : new int[] {-1, 5}) p.fill(x, 0, 2, x, 1, 2, Blocks.OAK_FENCE);
        for (int x = 0; x <= 4; x += 2) p.set(x, 0, 0, Blocks.BEEHIVE_BEES);
        for (int x = -1; x <= 5; x++) {
            p.set(x, 2, -1, Blocks.THATCH);
            p.set(x, 2, 0, Blocks.THATCH);
            p.set(x, 2, 1, Blocks.THATCH);
            p.set(x, 2, 2, (short) (Blocks.THATCH_ROOF + 0));
        }
        for (int x = -1; x <= 5; x++) if ((x & 1) != 0) p.set(x, 0, 3, Blocks.POPPY);
        p.set(0, 0, 4, Blocks.BERRY_BUSH);
        p.set(4, 0, 4, Blocks.BERRY_BUSH);
        p.mark("porte", 2, 0, 4);
        return p.done(APIARY);
    }

    /**
     * The bake-house: thick stone walls, the oven against the back with its hood and chimney going up
     * through an oak-shingled roof; a kneading table, flour and water at hand.
     */
    public static Blueprint bakehouse() {
        Draft p = new Draft(-1, 0, -1, 5, 8, 5);
        p.fill(0, 0, 0, 4, 0, 4, Blocks.COBBLESTONE);
        p.walls(0, 0, 4, 4, 1, 3, Blocks.COBBLESTONE, Blocks.OAK_LOG);
        for (int x = 1; x <= 3; x++) p.set(x, 3, 4, Blocks.OAK_LOG_X);
        p.door(2, 1, 4, false);
        p.step(2, 0, 5, Blocks.COBBLESTONE_STAIRS, 0);
        p.window(0, 2, 2, true);
        p.window(4, 2, 2, true);
        p.roof(-1, -1, 5, 5, 4, true, Draft.OAK, Blocks.COBBLESTONE, 0, 4);
        p.set(2, 1, 1, Blocks.FURNACE);
        p.chimney(2, 1, 2, 7);
        p.set(1, 1, 1, Blocks.OAK_TABLE);
        p.set(1, 2, 1, Blocks.LANTERN);
        p.set(3, 1, 1, Blocks.BARREL);
        p.set(3, 1, 3, Blocks.MILLSTONE);
        p.mark("porte", 2, 0, 6);
        return p.done(BAKEHOUSE);
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
                for (int x = 0; x < w; x++) p.put(x, y, z, Blocks.settled(world.get(x0 + x, y0 + y, z0 + z)));
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
                    if (want != Blocks.AIR && item(want) != Blocks.AIR && todo(want, world.get(ax + x, ay + y, az + z)))
                        m.merge(item(want), 1, Integer::sum);
                }
        return m;
    }

    /** Every item the whole plan takes (item -> count). */
    public Map<Short, Integer> materials() {
        Map<Short, Integer> m = new TreeMap<>();
        for (short b : cells) if (b != Blocks.AIR && item(b) != Blocks.AIR) m.merge(item(b), 1, Integer::sum);
        return m;
    }

    /**
     * The item that places a block (a roof's facings, an open door: the item it came from); AIR for what
     * comes with another (a door's upper half comes with the door).
     */
    public static short item(short block) {
        if (Blocks.isDoor(block)) return Blocks.UPPER[block & 0xFFFF] ? Blocks.AIR : Blocks.OAK_DOOR;
        short drop = Blocks.DROP[block & 0xFFFF];
        if (block == Blocks.STONE || drop == Blocks.AIR) return block; // a plan asks for what it shows
        return drop;
    }

    /** 0: obstacles to clear, 1: full blocks, 2: the rest (doors, windows, roofs, water), 3: furniture and life. */
    private static int rank(short b) {
        if (b == Blocks.AIR) return 0;
        if (Blocks.isFurniture(b) || Blocks.isStation(b) || b == Blocks.DECORATED_POT || b == Blocks.NEST || b == Blocks.NEST_EGG
            || b == Blocks.BEEHIVE || b == Blocks.BEEHIVE_BEES || b == Blocks.BEEHIVE_HONEY || Blocks.SHAPE[b & 0xFFFF] == Blocks.SHAPE_CROSS
            || b == Blocks.BERRY_BUSH) return 3;
        return Blocks.isSolid(b) && !Blocks.isDoor(b) ? 1 : 2;
    }

    private static boolean todo(short want, short here) {
        if (want == Blocks.AIR) return obstacle(here);
        if (Blocks.settled(want) == Blocks.settled(here)) return false; // an open door is a door, a lantern out is a lantern
        if (want == Blocks.BEEHIVE_BEES && (here == Blocks.BEEHIVE_HONEY || here == Blocks.BEEHIVE)) return false; // the bees' own business
        if (want == Blocks.NEST && here == Blocks.NEST_EGG) return false;
        return !(want == Blocks.BERRY_BUSH && here == Blocks.BERRY_BUSH_PICKED);
    }

    /** What may stand in an empty cell of the plan and is cleared: nature, not furniture. */
    static boolean obstacle(short b) {
        if (b == Blocks.AIR) return false;
        if (Blocks.isPlant(b) || Blocks.snowLayers(b) > 0) return true;
        String n = Blocks.NAMES[b & 0xFFFF];
        return n.endsWith("_leaves") || n.endsWith("_log") || b == Blocks.STONE || b == Blocks.DIRT || b == Blocks.GRASS
            || b == Blocks.SAND || b == Blocks.GRAVEL || b == Blocks.CLAY || b == Blocks.GRANITE || b == Blocks.DIORITE
            || b == Blocks.ANDESITE || b == Blocks.SNOW_BLOCK;
    }

    // ---------------------------------------------------------------------------- saving

    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("w", w);
        o.addProperty("h", h);
        o.addProperty("d", d);
        if (sunk > 0) o.addProperty("sunk", sunk);
        if (!marks.isEmpty()) {
            JsonObject m = new JsonObject();
            for (var e : marks.entrySet()) { JsonArray a = new JsonArray(); for (int v : e.getValue()) a.add(v); m.add(e.getKey(), a); }
            o.add("marks", m);
        }
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
        Map<String, int[]> marks = new LinkedHashMap<>();
        if (o.has("marks"))
            for (var e : o.getAsJsonObject("marks").entrySet()) {
                JsonArray m = e.getValue().getAsJsonArray();
                marks.put(e.getKey(), new int[] {m.get(0).getAsInt(), m.get(1).getAsInt(), m.get(2).getAsInt()});
            }
        return new Blueprint(o.get("name").getAsString(), o.get("w").getAsInt(), o.get("h").getAsInt(), o.get("d").getAsInt(), cells,
            o.has("sunk") ? o.get("sunk").getAsInt() : 0, marks);
    }

    /** Writes the plan as {@code <name>.json} in {@code dir}. */
    public void save(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), toJson());
    }

    /** The plans saved in {@code dir}, by name, after the hamlet's plans that everybody knows. */
    public static Map<String, Blueprint> loadAll(Path dir) {
        Map<String, Blueprint> plans = builtIn();
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
