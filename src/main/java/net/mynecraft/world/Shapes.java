package net.mynecraft.world;

/**
 * Blocks made of a few boxes inside their cell: stairs, walls, windows. They are drawn, collided
 * with and aimed at box by box. Coordinates are in sixteenths of a block (0..16).
 *
 * <p>Part of the shape follows the neighbours, as in vanilla, without extra block states:
 * <ul>
 *   <li>a stair turns a corner (inner or outer) against a stair set across it in front or behind;</li>
 *   <li>a wall reaches out to the walls and full blocks beside it, keeps its post at ends, corners
 *       and crossings (not along a straight run), and rises to the full height under a solid block;</li>
 *   <li>a window drops its frame on the sides where the next window of the same plane continues it,
 *       so a row or a column of windows is one glazing with one frame around it.</li>
 * </ul>
 * {@link #variant} works this out from the six face neighbours; {@link #boxes} gives the boxes of a
 * state in a variant (cached, nothing is allocated per call).
 *
 * <p>A box is packed in an int: x0 | y0 &lt;&lt; 5 | z0 &lt;&lt; 10 | x1 &lt;&lt; 15 | y1 &lt;&lt; 20 | z1 &lt;&lt; 25 | part &lt;&lt; 30.
 * The part says which material a box shows ({@link #partBlock}): a window's frame is oak, its pane glass.
 */
public final class Shapes {
    private Shapes() {}

    /** Horizontal directions as in Blocks.FACING: 0 north (-z), 1 south (+z), 2 west (-x), 3 east (+x). */
    public static final int NORTH = 0, SOUTH = 1, WEST = 2, EAST = 3;
    /** Face index (-x, +x, -y, +y, -z, +z) of the neighbour in each direction. */
    public static final int[] FACE_OF = {4, 5, 0, 1};
    private static final int[] OPPOSITE = {SOUTH, NORTH, EAST, WEST};
    /** Turning left, seen from above: north to west, west to south... (vanilla getCounterClockWise). */
    private static final int[] LEFT = {WEST, EAST, SOUTH, NORTH};
    private static final int[] RIGHT = {EAST, WEST, NORTH, SOUTH};

    /** Stair variants. */
    public static final int STRAIGHT = 0, OUTER_LEFT = 1, OUTER_RIGHT = 2, INNER_LEFT = 3, INNER_RIGHT = 4;
    /** Wall variant bits: arms towards each direction (1 << direction), then the post and the full height. */
    public static final int WALL_POST = 16, WALL_TALL = 32;
    /** Window variant bits: the frame is left out towards the next window, low side / high side / below / above. */
    public static final int WINDOW_LOW = 1, WINDOW_HIGH = 2, WINDOW_DOWN = 4, WINDOW_UP = 8;
    private static final int VARIANTS = 64;

    public static int box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return x0 | y0 << 5 | z0 << 10 | x1 << 15 | y1 << 20 | z1 << 25;
    }

    /** Low corner of a box along an axis (0 x, 1 y, 2 z), in sixteenths. */
    public static int min(int box, int axis) {
        return box >> axis * 5 & 31;
    }

    public static int max(int box, int axis) {
        return box >> 15 + axis * 5 & 31;
    }

    public static int part(int box) {
        return box >>> 30 & 1;
    }

    /** The block whose textures and render layer a part of {@code id} uses. */
    public static short partBlock(short id, int part) {
        if (Blocks.SHAPE[id & 0xFFFF] == Blocks.SHAPE_WINDOW) return part == 0 ? Blocks.OAK_PLANKS : Blocks.GLASS;
        return id;
    }

    public static boolean isStair(short id) {
        return Blocks.SHAPE[id & 0xFFFF] == Blocks.SHAPE_STAIRS;
    }

    // ---- variants ----

    /** Variant of a shaped block from its face neighbours n (-x, +x, -y, +y, -z, +z); 0 for other blocks. */
    public static int variant(short id, short[] n) {
        return switch (Blocks.SHAPE[id & 0xFFFF]) {
            case Blocks.SHAPE_STAIRS -> stairVariant(id, n);
            case Blocks.SHAPE_WALL -> wallVariant(n);
            case Blocks.SHAPE_WINDOW -> windowVariant(id, n);
            default -> 0;
        };
    }

    /** Vanilla's stair shape rule: corners turn towards a stair across this one, same half, in front or behind. */
    private static int stairVariant(short id, short[] n) {
        int f = Blocks.FACING[id & 0xFFFF];
        boolean top = Blocks.UPPER[id & 0xFFFF];
        short front = n[FACE_OF[f]];
        if (isStair(front) && Blocks.UPPER[front & 0xFFFF] == top) {
            int f1 = Blocks.FACING[front & 0xFFFF];
            if (f1 >> 1 != f >> 1 && canTurn(id, n, OPPOSITE[f1])) return f1 == LEFT[f] ? OUTER_LEFT : OUTER_RIGHT;
        }
        short back = n[FACE_OF[OPPOSITE[f]]];
        if (isStair(back) && Blocks.UPPER[back & 0xFFFF] == top) {
            int f2 = Blocks.FACING[back & 0xFFFF];
            if (f2 >> 1 != f >> 1 && canTurn(id, n, f2)) return f2 == LEFT[f] ? INNER_LEFT : INNER_RIGHT;
        }
        return STRAIGHT;
    }

    /** A stair does not turn towards a side where the same stair continues it (a straight flight stays straight). */
    private static boolean canTurn(short id, short[] n, int side) {
        short s = n[FACE_OF[side]];
        return !isStair(s) || Blocks.FACING[s & 0xFFFF] != Blocks.FACING[id & 0xFFFF] || Blocks.UPPER[s & 0xFFFF] != Blocks.UPPER[id & 0xFFFF];
    }

    /** Walls join walls and full blocks. */
    private static boolean wallJoins(short n) {
        return Blocks.SHAPE[n & 0xFFFF] == Blocks.SHAPE_WALL || Blocks.isOpaque(n);
    }

    private static int wallVariant(short[] n) {
        int v = 0;
        for (int d = 0; d < 4; d++) if (wallJoins(n[FACE_OF[d]])) v |= 1 << d;
        // The post stands at the ends, corners and crossings; a straight run goes without it.
        if (v != (1 << NORTH | 1 << SOUTH) && v != (1 << WEST | 1 << EAST)) v |= WALL_POST;
        if (Blocks.isSolid(n[3])) v |= WALL_TALL; // under a block (or the next course) the wall rises to the top
        return v;
    }

    /** Windows join the next window of the same plane (same state). */
    private static int windowVariant(short id, short[] n) {
        boolean alongX = Blocks.FACING[id & 0xFFFF] == 0;
        int v = 0;
        if (n[alongX ? 0 : 4] == id) v |= WINDOW_LOW;
        if (n[alongX ? 1 : 5] == id) v |= WINDOW_HIGH;
        if (n[2] == id) v |= WINDOW_DOWN;
        if (n[3] == id) v |= WINDOW_UP;
        return v;
    }

    /** Variant of the shaped block at (x, y, z) of a world. */
    public static int variantAt(World w, int x, int y, int z, short id, short[] scratch) {
        scratch[0] = w.get(x - 1, y, z);
        scratch[1] = w.get(x + 1, y, z);
        scratch[2] = w.get(x, y - 1, z);
        scratch[3] = w.get(x, y + 1, z);
        scratch[4] = w.get(x, y, z - 1);
        scratch[5] = w.get(x, y, z + 1);
        return variant(id, scratch);
    }

    /** Boxes of the block at (x, y, z) of a world, or null if it is not a shaped block. */
    public static int[] at(World w, int x, int y, int z) {
        short id = w.get(x, y, z);
        if (!Blocks.hasBoxes(id)) return null;
        return boxes(id, variantAt(w, x, y, z, id, new short[6]));
    }

    // ---- boxes ----

    private static final int[][] CACHE = new int[Blocks.COUNT * VARIANTS][];

    /** Boxes of a shaped state in one of its variants (shared arrays: do not modify). */
    public static int[] boxes(short id, int variant) {
        int k = (id & 0xFFFF) * VARIANTS + (variant & VARIANTS - 1);
        int[] b = CACHE[k];
        if (b == null) {
            b = switch (Blocks.SHAPE[id & 0xFFFF]) {
                case Blocks.SHAPE_STAIRS -> stairBoxes(Blocks.FACING[id & 0xFFFF], Blocks.UPPER[id & 0xFFFF], variant);
                case Blocks.SHAPE_WALL -> wallBoxes(variant);
                case Blocks.SHAPE_WINDOW -> windowBoxes(Blocks.FACING[id & 0xFFFF] == 0, variant);
                default -> new int[] {box(0, 0, 0, 16, 16, 16)};
            };
            CACHE[k] = b;
        }
        return b;
    }

    private static final int[][] CELLS = new int[Blocks.COUNT][];

    /** The single box of an ordinary block (full cube, slab, snow layer), from Blocks.BOX_MIN / BOX_MAX. */
    public static int[] cell(short id) {
        int[] b = CELLS[id & 0xFFFF];
        if (b == null) CELLS[id & 0xFFFF] = b = new int[] {box(0, Blocks.BOX_MIN[id & 0xFFFF], 0, 16, Blocks.BOX_MAX[id & 0xFFFF], 16)};
        return b;
    }

    /** Variant shown in the hand and the inventory (a wall with its post and two arms). */
    public static int iconVariant(short id) {
        return Blocks.SHAPE[id & 0xFFFF] == Blocks.SHAPE_WALL ? 1 << WEST | 1 << EAST | WALL_POST : 0;
    }

    /** Half of the cell on the side of a direction, as {x0, z0, x1, z1}, clipped into r. */
    private static int[] clip(int[] r, int dir) {
        int[] c = r.clone();
        switch (dir) {
            case NORTH -> c[3] = Math.min(c[3], 8);
            case SOUTH -> c[1] = Math.max(c[1], 8);
            case WEST -> c[2] = Math.min(c[2], 8);
            default -> c[0] = Math.max(c[0], 8);
        }
        return c;
    }

    private static int[] stairBoxes(int f, boolean top, int variant) {
        int[] whole = {0, 0, 16, 16};
        int sy0 = top ? 8 : 0, ty0 = top ? 0 : 8; // slab, then the step above it (below it, upside down)
        java.util.List<int[]> steps = new java.util.ArrayList<>();
        switch (variant) {
            case OUTER_LEFT -> steps.add(clip(clip(whole, f), LEFT[f]));
            case OUTER_RIGHT -> steps.add(clip(clip(whole, f), RIGHT[f]));
            case INNER_LEFT -> { steps.add(clip(whole, f)); steps.add(clip(clip(whole, OPPOSITE[f]), LEFT[f])); }
            case INNER_RIGHT -> { steps.add(clip(whole, f)); steps.add(clip(clip(whole, OPPOSITE[f]), RIGHT[f])); }
            default -> steps.add(clip(whole, f));
        }
        int[] b = new int[1 + steps.size()];
        b[0] = box(0, sy0, 0, 16, sy0 + 8, 16);
        for (int i = 0; i < steps.size(); i++) {
            int[] r = steps.get(i);
            b[i + 1] = box(r[0], ty0, r[1], r[2], ty0 + 8, r[3]);
        }
        return b;
    }

    private static int[] wallBoxes(int v) {
        int h = (v & WALL_TALL) != 0 ? 16 : 14;
        java.util.List<Integer> b = new java.util.ArrayList<>();
        if ((v & WALL_POST) != 0 || (v & 15) == 0) b.add(box(4, 0, 4, 12, 16, 12));
        boolean n = (v & 1 << NORTH) != 0, s = (v & 1 << SOUTH) != 0, w = (v & 1 << WEST) != 0, e = (v & 1 << EAST) != 0;
        // A run through the cell is one box (no faces inside it); a single arm reaches the middle.
        if (n || s) b.add(box(5, 0, n ? 0 : 8, 11, h, s ? 16 : 8));
        if (w || e) b.add(box(w ? 0 : 8, 0, 5, e ? 16 : 8, h, 11));
        return b.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * A casement: a glass pane 2/16 thick in the middle of the cell, an oak frame 4/16 deep round it
     * (left out towards the next window), and a cross of glazing bars.
     */
    private static int[] windowBoxes(boolean alongX, int v) {
        boolean l = (v & WINDOW_LOW) == 0, r = (v & WINDOW_HIGH) == 0, d = (v & WINDOW_DOWN) == 0, u = (v & WINDOW_UP) == 0;
        int a0 = l ? 2 : 0, a1 = r ? 14 : 16, b0 = d ? 2 : 0, b1 = u ? 14 : 16;
        java.util.List<int[]> parts = new java.util.ArrayList<>(); // {u0, y0, u1, y1, glass}
        parts.add(new int[] {0, 0, 16, 16, 1});
        if (l) parts.add(new int[] {0, 0, 2, 16, 0});
        if (r) parts.add(new int[] {14, 0, 16, 16, 0});
        if (d) parts.add(new int[] {a0, 0, a1, 2, 0});
        if (u) parts.add(new int[] {a0, 14, a1, 16, 0});
        parts.add(new int[] {7, b0, 9, b1, 0});   // mullion
        parts.add(new int[] {a0, 7, 7, 9, 0});    // transom, either side of the mullion
        parts.add(new int[] {9, 7, a1, 9, 0});
        int[] out = new int[parts.size()];
        for (int i = 0; i < out.length; i++) {
            int[] p = parts.get(i);
            int t0 = p[4] == 1 ? 7 : 6, t1 = p[4] == 1 ? 9 : 10;
            int bx = alongX ? box(p[0], p[1], t0, p[2], p[3], t1) : box(t0, p[1], p[0], t1, p[3], p[2]);
            out[i] = p[4] == 1 ? bx | 1 << 30 : bx;
        }
        return out;
    }

    /** Smallest box holding all of them, packed the same way (outlines). */
    public static int bounds(int[] boxes) {
        int[] lo = {16, 16, 16}, hi = {0, 0, 0};
        for (int b : boxes)
            for (int a = 0; a < 3; a++) {
                lo[a] = Math.min(lo[a], min(b, a));
                hi[a] = Math.max(hi[a], max(b, a));
            }
        return box(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
    }
}
