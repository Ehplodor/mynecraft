package net.mynecraft.world.life;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.World;

/**
 * A pen: the ground an animal can reach from where it stands without crossing a fence, a door, a
 * wall of two blocks, water or a drop of more than three blocks. Found by flooding the walkable
 * cells; past {@link #MAX_CELLS} the place is open land. A pen feeds as many animals as its grass
 * allows ({@link #need}): overstocked, the grazing tramples the grass to bare earth; with room to
 * spare, the earth greens again.
 */
public final class Pasture {
    public static final int MAX_CELLS = 1024;

    private Pasture() {}

    /**
     * One pen: its cells (x, ground y, z: the block under the feet), how many are grass, how many
     * bare earth, and an id (the smallest cell key) shared by every animal in the same pen.
     */
    public record Pen(int[] xs, int[] ys, int[] zs, int grass, int bare, long id) {
        public int cells() {
            return xs.length;
        }
    }

    /** Grass cells one animal of a species needs to live off a pen. */
    public static int need(Species s) {
        return switch (s) {
            case SHEEP, GOAT -> 8;
            case HEN -> 3;
            default -> 10;
        };
    }

    /** The pen around the feet at (x, y, z), or null if the place is open (or the land not loaded). */
    public static Pen measure(World w, double x, double y, double z) {
        int sx = (int) Math.floor(x), sz = (int) Math.floor(z), sy = (int) Math.floor(y + 0.01) - 1;
        if (!standable(w, sx, sy, sz)) {
            if (standable(w, sx, sy - 1, sz)) sy--;
            else return null;
        }
        java.util.HashMap<Long, Integer> seen = new java.util.HashMap<>(); // cell -> ground y
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        seen.put(key(sx, sz), sy);
        queue.add(new int[] {sx, sy, sz});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] d : dirs) {
                int nx = c[0] + d[0], nz = c[2] + d[1];
                if (seen.containsKey(key(nx, nz))) continue;
                int ground = step(w, nx, c[1], nz);
                if (ground == Integer.MIN_VALUE) continue;
                if (ground == Integer.MAX_VALUE) return null; // out of the loaded land: cannot tell, call it open
                seen.put(key(nx, nz), ground);
                if (seen.size() > MAX_CELLS) return null;
                queue.add(new int[] {nx, ground, nz});
            }
        }
        int n = seen.size(), grass = 0, bare = 0, i = 0;
        int[] xs = new int[n], ys = new int[n], zs = new int[n];
        long id = Long.MAX_VALUE;
        for (var e : seen.entrySet()) {
            long k = e.getKey();
            int cx = (int) (k >> 32), cz = (int) k, gy = e.getValue();
            xs[i] = cx;
            ys[i] = gy;
            zs[i] = cz;
            i++;
            id = Math.min(id, k);
            short g = w.get(cx, gy, cz);
            if (g == Blocks.GRASS) grass++;
            else if (g == Blocks.DIRT) bare++;
        }
        return new Pen(xs, ys, zs, grass, bare, id);
    }

    /**
     * From ground {@code y} into the column (x, z): the new ground y, MIN_VALUE if the way is
     * barred, MAX_VALUE if that land is not loaded.
     */
    private static int step(World w, int x, int y, int z) {
        if (w.chunk(x >> 4, z >> 4) == null) return Integer.MAX_VALUE;
        short feet = w.get(x, y + 1, z);
        if (barrier(feet) || Blocks.isFluid(feet)) return Integer.MIN_VALUE;
        if (w.collisionBox(x, y + 1, z) >= 0) { // a step up: climbable if there is room above it
            if (w.collisionBox(x, y + 2, z) >= 0 || w.collisionBox(x, y + 3, z) >= 0 || barrier(w.get(x, y + 2, z))) return Integer.MIN_VALUE;
            return y + 1;
        }
        for (int gy = y; gy >= y - 3; gy--) { // level ground, or down a drop of up to three blocks
            short b = w.get(x, gy, z);
            if (Blocks.isFluid(b)) return Integer.MIN_VALUE;
            if (barrier(b)) return Integer.MIN_VALUE;
            if (w.collisionBox(x, gy, z) >= 0) return gy;
        }
        return Integer.MIN_VALUE; // a cliff: animals turn back from it
    }

    private static boolean standable(World w, int x, int y, int z) {
        return w.collisionBox(x, y, z) >= 0 && w.collisionBox(x, y + 1, z) < 0;
    }

    /** Fences and doors (open or closed: an open gate lets the flock out only if it leads somewhere). */
    static boolean barrier(short b) {
        return b == Blocks.OAK_FENCE || Blocks.isClosedDoor(b);
    }

    static long key(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }
}
