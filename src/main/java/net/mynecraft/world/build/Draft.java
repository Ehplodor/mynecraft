package net.mynecraft.world.build;

import net.mynecraft.world.Blocks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The drawing board of a plan: cells set in the architect's own coordinates, which may run below zero
 * (the eaves overhang the walls, a chimney stands outside the gable), turned into a {@link Blueprint}
 * whose box holds them all. Layer y = 0 is the plinth, laid on the levelled ground.
 */
final class Draft {
    private final int x0, y0, z0, w, h, d;
    private final short[] cells;
    private final Map<String, int[]> marks = new LinkedHashMap<>();

    /** A board covering x0..x1, y0..y1, z0..z1 (inclusive). */
    Draft(int x0, int y0, int z0, int x1, int y1, int z1) {
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        w = x1 - x0 + 1;
        h = y1 - y0 + 1;
        d = z1 - z0 + 1;
        cells = new short[w * h * d];
    }

    void set(int x, int y, int z, short b) {
        int px = x - x0, py = y - y0, pz = z - z0;
        if (px < 0 || py < 0 || pz < 0 || px >= w || py >= h || pz >= d)
            throw new IllegalArgumentException("off the board: " + x + "," + y + "," + z);
        cells[(py * d + pz) * w + px] = b;
    }

    short get(int x, int y, int z) {
        return cells[((y - y0) * d + (z - z0)) * w + (x - x0)];
    }

    void fill(int xa, int ya, int za, int xb, int yb, int zb, short b) {
        for (int y = ya; y <= yb; y++) for (int z = za; z <= zb; z++) for (int x = xa; x <= xb; x++) set(x, y, z, b);
    }

    /** A named point of the plan, in the architect's coordinates (it may lie outside the box: the yard). */
    void mark(String name, int x, int y, int z) {
        marks.put(name, new int[] {x, y, z});
    }

    /**
     * Walls of the rectangle x0..x1, z0..z1 from layer ya to yb: {@code corner} at the four corners,
     * {@code wall} between them (null leaves a side as it is).
     */
    void walls(int xa, int za, int xb, int zb, int ya, int yb, short wall, short corner) {
        for (int y = ya; y <= yb; y++)
            for (int z = za; z <= zb; z++)
                for (int x = xa; x <= xb; x++) {
                    boolean ex = x == xa || x == xb, ez = z == za || z == zb;
                    if (ex && ez) set(x, y, z, corner);
                    else if (ex || ez) set(x, y, z, wall);
                }
    }

    /** A door two blocks high with its lower half at (x, y, z), its leaf along x or along z. */
    void door(int x, int y, int z, boolean alongZ) {
        set(x, y, z, Blocks.door(false, alongZ, false));
        set(x, y + 1, z, Blocks.door(true, alongZ, false));
    }

    /** A window in a wall: along x for a wall running along x (north and south), along z otherwise. */
    void window(int x, int y, int z, boolean alongZ) {
        set(x, y, z, (short) (Blocks.OAK_WINDOW + (alongZ ? 1 : 0)));
    }

    /** A step before a door: a stair whose high side is towards the door (0 north, 1 south, 2 west, 3 east). */
    void step(int x, int y, int z, short stairs, int towards) {
        set(x, y, z, Blocks.stair(stairs, towards, false));
    }

    /** Material of a roof: the slope (or stair) whose high side faces a direction, and the ridge. */
    interface Roofing {
        short slope(int highSide);

        short ridge();
    }

    static final Roofing THATCH = new Roofing() {
        public short slope(int f) { return (short) (Blocks.THATCH_ROOF + f); }

        public short ridge() { return Blocks.THATCH; }
    };

    static final Roofing OAK = new Roofing() {
        public short slope(int f) { return Blocks.stair(Blocks.OAK_STAIRS, f, false); }

        public short ridge() { return Blocks.OAK_SLAB; }
    };

    /**
     * A gable roof over x0..x1, z0..z1 (eaves included) from layer {@code y}: each course one up and one
     * in, the ridge along x (slopes to the north and south) or along z. The gables under it, at the given
     * wall planes ({@code gables}, x for a ridge along x), are filled with {@code gable}. Returns the
     * layer of the ridge.
     */
    int roof(int xa, int za, int xb, int zb, int y, boolean ridgeAlongX, Roofing r, short gable, int... gables) {
        for (int k = 0; ; k++) {
            int lo = (ridgeAlongX ? za : xa) + k, hi = (ridgeAlongX ? zb : xb) - k, yy = y + k;
            if (lo > hi) return yy - 1;
            for (int a = ridgeAlongX ? xa : za; a <= (ridgeAlongX ? xb : zb); a++) {
                if (lo == hi) { put(ridgeAlongX, a, yy, lo, r.ridge()); continue; }
                put(ridgeAlongX, a, yy, lo, r.slope(ridgeAlongX ? 1 : 3));
                put(ridgeAlongX, a, yy, hi, r.slope(ridgeAlongX ? 0 : 2));
            }
            for (int g : gables) for (int c = lo + 1; c < hi; c++) put(ridgeAlongX, g, yy, c, gable);
            if (lo == hi || lo + 1 == hi) return yy;
        }
    }

    /** Sets (a, y, c) with a along the ridge and c across it. */
    private void put(boolean ridgeAlongX, int a, int y, int c, short b) {
        if (ridgeAlongX) set(a, y, c, b);
        else set(c, y, a, b);
    }

    /** A chimney: a column of cobblestone from layer ya to yb, capped by a muret. */
    void chimney(int x, int z, int ya, int yb) {
        fill(x, ya, z, x, yb, z, Blocks.COBBLESTONE);
        set(x, yb + 1, z, Blocks.COBBLESTONE_WALL);
    }

    /** The plan: marks are stored relative to the box's corner; the layers below 0 are dug into the ground. */
    Blueprint done(String name) {
        int sunk = -y0;
        Map<String, int[]> rel = new LinkedHashMap<>();
        for (var e : marks.entrySet()) {
            int[] m = e.getValue();
            rel.put(e.getKey(), new int[] {m[0] - x0, m[1] - y0, m[2] - z0});
        }
        return new Blueprint(name, w, h, d, cells, sunk, rel);
    }
}
