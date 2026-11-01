package net.mynecraft.world.build;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.World;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/**
 * Where and how a plan meets the ground, as a builder would see to it before the first stone.
 * <ul>
 *   <li>{@link #choose}: among the places near the one wanted, the flattest dry one that keeps clear of
 *       the buildings already standing;</li>
 *   <li>the level: the median height of the ground under the plan, so the plinth is laid on it, one
 *       block proud, never sunk into a slope nor perched on stilts;</li>
 *   <li>{@link #prepare}: trees felled, the ground cut down to that level under the plan and stepped
 *       back around it (a little bank), filled up where it is lower (earth with grass on it), and
 *       stone foundations under the walls down to the firm ground.</li>
 * </ul>
 */
public final class Site {
    private Site() {}

    /** How far around a plan the ground is shaped: a level apron, then a bank one block down or up. */
    static final int BANK = 2;

    /** Writes a block (and records it for redrawing). */
    public interface Setter {
        void set(int x, int y, int z, short b);
    }

    /** The top of the ground at a column: {height of the top solid block, 1 if water lies on it}. */
    static int[] surface(World w, int x, int z, IntBinaryOperator ground) {
        int y = ground.applyAsInt(x, z);
        short above = w.get(x, y + 1, z);
        return new int[] {y, Blocks.isFluid(above) || above == Blocks.ICE ? 1 : 0};
    }

    /** The height the plan's plinth is laid upon, anchored with its north-west corner at (x, z): the median ground. */
    public static int level(World w, Blueprint p, int x, int z, IntBinaryOperator ground) {
        int[] hs = new int[p.w * p.d];
        for (int dz = 0; dz < p.d; dz++) for (int dx = 0; dx < p.w; dx++) hs[dz * p.w + dx] = ground.applyAsInt(x + dx, z + dz);
        Arrays.sort(hs);
        return hs[hs.length / 2];
    }

    /** The anchor of the plan laid on ground of height {@code g} at (x, z): its layer {@code sunk} is the first above it. */
    public static int[] anchor(Blueprint p, int x, int g, int z) {
        return new int[] {x, g + 1 - p.sunk, z};
    }

    /**
     * The best anchor for the plan near (x, z), within {@code radius}: flat (little earth to move), dry
     * (no water under it), near the place wanted, and at least {@code gap} blocks from the boxes already
     * taken ({x0, z0, x1, z1} each). Null if there is none.
     */
    public static int[] choose(World w, Blueprint p, int x, int z, int radius, int gap, List<int[]> taken, IntBinaryOperator ground) {
        Map<Long, int[]> cache = new HashMap<>();
        java.util.function.BiFunction<Integer, Integer, int[]> at0 = (gx, gz) ->
            cache.computeIfAbsent((long) gx << 32 ^ (gz & 0xFFFFFFFFL), k -> surface(w, gx, gz, ground));
        IntBinaryOperator g = (gx, gz) -> at0.apply(gx, gz)[0];
        double best = Double.MAX_VALUE;
        int[] at = null;
        for (int dz = -radius; dz <= radius; dz++)
            for (int dx = -radius; dx <= radius; dx++) {
                int ax = x + dx, az = z + dz;
                if (overlaps(ax - gap, az - gap, ax + p.w - 1 + gap, az + p.d - 1 + gap, taken)) continue;
                boolean wet = false; // no water on the site nor on its banks: a cut there would flood it
                for (int cz = -BANK - 1; cz <= p.d + BANK && !wet; cz++)
                    for (int cx = -BANK - 1; cx <= p.w + BANK && !wet; cx++) wet = at0.apply(ax + cx, az + cz)[1] == 1;
                if (wet) continue;
                int lv = level(w, p, ax, az, g);
                double cost = Math.hypot(dx, dz) * 0.6;
                for (int cz = -1; cz <= p.d && cost < best; cz++)
                    for (int cx = -1; cx <= p.w; cx++) {
                        int[] s = at0.apply(ax + cx, az + cz);
                        cost += Math.abs(s[0] - lv) + (s[1] == 1 ? 40 : 0);
                    }
                if (cost < best) { best = cost; at = anchor(p, ax, lv, az); }
            }
        return at;
    }

    private static boolean overlaps(int x0, int z0, int x1, int z1, List<int[]> taken) {
        for (int[] t : taken) if (x0 <= t[2] && t[0] <= x1 && z0 <= t[3] && t[1] <= z1) return true;
        return false;
    }

    /** The ground box of the plan anchored at {@code a}: {x0, z0, x1, z1}. */
    public static int[] box(Blueprint p, int[] a) {
        return new int[] {a[0], a[2], a[0] + p.w - 1, a[2] + p.d - 1};
    }

    /** Natural ground and growth, which the builder may cut away. */
    static boolean natural(short b) {
        return Blueprint.obstacle(b) || b == Blocks.FARMLAND;
    }

    /**
     * Shapes the ground for the plan anchored at {@code a}: fells the trees over it, cuts and fills the
     * ground to the plinth's level under it and banks it around, lays stone foundations under its walls.
     * Logs felled are counted in {@code logs[0]} (for the woodpile).
     */
    public static void prepare(World w, Blueprint p, int[] a, Setter out, int[] logs) {
        int g = a[1] + p.sunk - 1; // the ground's top under the plinth
        // A clearing: trunks, leaves and plants over the plan and round it.
        for (int z = -BANK - 1; z <= p.d + BANK; z++)
            for (int x = -BANK - 1; x <= p.w + BANK; x++)
                for (int y = g + 1; y <= a[1] + p.h + 8; y++) {
                    short b = w.get(a[0] + x, y, a[2] + z);
                    String n = Blocks.NAMES[b & 0xFFFF];
                    if (n.endsWith("_leaves") || n.endsWith("_log") || Blocks.isPlant(b) || Blocks.snowLayers(b) > 0 && b != Blocks.SNOW_BLOCK) {
                        if (n.endsWith("_log") && logs != null) logs[0]++;
                        out.set(a[0] + x, y, a[2] + z, Blocks.AIR);
                    }
                }
        for (int z = -BANK; z < p.d + BANK; z++)
            for (int x = -BANK; x < p.w + BANK; x++) {
                int ring = Math.max(Math.max(-x, x - p.w + 1), Math.max(-z, z - p.d + 1)); // 0 under the plan, 1.. around
                ring = Math.max(0, ring);
                int wx = a[0] + x, wz = a[2] + z;
                int top = topSolid(w, wx, g + 24, wz, g - 24);
                // Cut: down to the level under the plan and an apron round it, then a step higher for each step out.
                int cutTo = g + Math.max(0, ring - 1);
                for (int y = top; y > cutTo; y--) if (natural(w.get(wx, y, wz)) && !nearWater(w, wx, y, wz)) out.set(wx, y, wz, Blocks.AIR);
                // Fill: up to the level under the plan and its apron, then a step lower for each step out.
                int fillTo = g - Math.max(0, ring - 1);
                boolean footing = ring == 0 && p.at(x, p.sunk, z) != Blocks.AIR && Blocks.isSolid(p.at(x, p.sunk, z))
                    && !Blocks.isDoor(p.at(x, p.sunk, z)) && Blocks.SHAPE[p.at(x, p.sunk, z) & 0xFFFF] != Blocks.SHAPE_WALL
                    && p.at(x, p.sunk, z) != Blocks.OAK_FENCE;
                int ground = Math.min(top, cutTo);
                for (int y = ground + 1; y <= fillTo; y++) {
                    if (Blocks.isSolid(w.get(wx, y, wz)) && !natural(w.get(wx, y, wz))) continue;
                    out.set(wx, y, wz, footing ? Blocks.COBBLESTONE : y == fillTo ? Blocks.GRASS : Blocks.DIRT);
                }
                if (footing) continue;
                // The new surface: grass on bare earth.
                int surf = Math.max(Math.min(top, cutTo), fillTo);
                short s = w.get(wx, surf, wz);
                if (s == Blocks.DIRT && !Blocks.isSolid(w.get(wx, surf + 1, wz)) && !Blocks.isFluid(w.get(wx, surf + 1, wz)))
                    out.set(wx, surf, wz, Blocks.GRASS);
            }
    }

    /** Whether water lies beside or above (x, y, z): the bank is left standing there. */
    static boolean nearWater(World w, int x, int y, int z) {
        return Blocks.isFluid(w.get(x + 1, y, z)) || Blocks.isFluid(w.get(x - 1, y, z)) || Blocks.isFluid(w.get(x, y, z + 1))
            || Blocks.isFluid(w.get(x, y, z - 1)) || Blocks.isFluid(w.get(x, y + 1, z));
    }

    /** The highest solid block of the column from {@code from} down to {@code to} (leaves and logs not counted). */
    static int topSolid(World w, int x, int from, int z, int to) {
        for (int y = from; y > to; y--) {
            short b = w.get(x, y, z);
            String n = Blocks.NAMES[b & 0xFFFF];
            if (Blocks.isSolid(b) && !n.endsWith("_leaves") && !n.endsWith("_log")) return y;
        }
        return to;
    }

    /**
     * A path of gravel along the ground from (x0, z0) to (x1, z1), first along x then along z: the top of
     * each column, where it is grass or earth under open air, becomes gravel.
     */
    public static void path(World w, int x0, int z0, int x1, int z1, int nearY, Setter out) {
        int x = x0, z = z0;
        while (true) {
            int y = topSolid(w, x, nearY + 8, z, nearY - 8);
            short b = w.get(x, y, z), above = w.get(x, y + 1, z);
            if ((b == Blocks.GRASS || b == Blocks.DIRT) && (above == Blocks.AIR || Blocks.isPlant(above))) {
                if (above != Blocks.AIR) out.set(x, y + 1, z, Blocks.AIR);
                out.set(x, y, z, Blocks.GRAVEL);
            }
            if (x == x1 && z == z1) return;
            if (x != x1) x += Integer.signum(x1 - x);
            else z += Integer.signum(z1 - z);
        }
    }
}
