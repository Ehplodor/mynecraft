package net.mynecraft.world.people;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Walking routes in the voxel world (A*): a person stands in two cells free of solid blocks over a
 * block to stand on (or in shallow water), steps up one block, steps down up to three, opens closed
 * doors on the way (they count as free), and wades. Searches at most {@code budget} cells.
 */
public final class PathFinder {
    private PathFinder() {}

    static boolean free(short b) {
        return !Blocks.isSolid(b) || b == Blocks.OAK_DOOR;
    }

    /** Can a person stand with their feet in (x, y, z)? */
    public static boolean standable(World w, int x, int y, int z) {
        short feet = w.get(x, y, z), head = w.get(x, y + 1, z), ground = w.get(x, y - 1, z);
        if (!free(feet) || !free(head) || Blocks.FLUID[feet & 0xFFFF] == Blocks.FLUID_LAVA) return false;
        return (Blocks.isSolid(ground) && ground != Blocks.OAK_DOOR) || Blocks.FLUID[ground & 0xFFFF] == Blocks.FLUID_WATER
            && Blocks.isSolid(w.get(x, y - 2, z));
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Cells from the start (excluded) to the goal (or the closest reached), or an empty list. */
    public static List<int[]> find(World w, int sx, int sy, int sz, int gx, int gy, int gz, int budget) {
        record Node(int x, int y, int z, double g, double f, Node from) {}
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> best = new HashMap<>();
        Node start = new Node(sx, sy, sz, 0, dist(sx, sy, sz, gx, gy, gz), null), closest = start;
        open.add(start);
        best.put(key(sx, sy, sz), 0.0);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty() && budget-- > 0) {
            Node n = open.poll();
            if (n.x == gx && n.z == gz && Math.abs(n.y - gy) <= 1) { closest = n; break; }
            if (n.f - n.g < closest.f - closest.g) closest = n;
            for (int[] d : dirs) {
                int x = n.x + d[0], z = n.z + d[1];
                for (int dy = 1; dy >= -3; dy--) {
                    int y = n.y + dy;
                    if (dy == 1 && !free(w.get(n.x, n.y + 2, n.z))) continue; // no room to step up
                    if (!standable(w, x, y, z)) continue;
                    if (dy < 0) { // falling: the column above the landing must be free
                        boolean clear = true;
                        for (int k = y + 2; k <= n.y + 1 && clear; k++) clear = free(w.get(x, k, z));
                        if (!clear) continue;
                    }
                    double g = n.g + 1 + (dy != 0 ? 0.5 : 0) + (Blocks.isFluid(w.get(x, y, z)) ? 2 : 0);
                    long k = key(x, y, z);
                    Double old = best.get(k);
                    if (old != null && old <= g) break;
                    best.put(k, g);
                    open.add(new Node(x, y, z, g, g + dist(x, y, z, gx, gy, gz), n));
                    break;
                }
            }
        }
        List<int[]> path = new ArrayList<>();
        for (Node n = closest; n != null && n != start; n = n.from) path.add(0, new int[] {n.x, n.y, n.z});
        return path;
    }

    private static double dist(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(x - gx) + Math.abs(z - gz) + Math.abs(y - gy) * 0.5;
    }
}
