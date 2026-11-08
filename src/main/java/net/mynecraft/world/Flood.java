package net.mynecraft.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The flood (E7): days of heavy rain swell the rivers; the water rises over the low banks near the
 * player, a couple of blocks, for a day and a half, then withdraws, leaving a line of driftwood where
 * it stood highest and fertile silt on the flooded ground. Willows hold their bank. Only the water
 * the flood laid is taken back (no water is left behind), and only round the player (the rivers far
 * away are not drawn).
 */
public final class Flood {
    /** Days a flood lasts, its height at the crest (blocks), how far it spreads from the river (blocks). */
    public static final double DAYS = 1.5, HEIGHT = 2, SPREAD = 8;
    /** Rain gathered (in days of heavy rain, forgotten over {@link #MEMORY_DAYS}) that starts a flood; days between two. */
    public static final double THRESHOLD = 1.5, MEMORY_DAYS = 2, REST_DAYS = 12;

    /** Is there a river at this column (one the flood rises from)? */
    public interface River {
        boolean at(int x, int z);
    }

    public double rain, since = -1, rest;
    /** Columns flooded now: key -> {x, z, lowest water y, highest water y}; and every column reached during this flood. */
    private final Map<Long, int[]> placed = new HashMap<>();
    private final Map<Long, int[]> reached = new HashMap<>(); // key -> {x, z, ground y}

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public boolean active() {
        return since >= 0;
    }

    /** The rise now (blocks): up then down over the flood's days. */
    public double height() {
        return since < 0 ? 0 : HEIGHT * Math.sin(Math.PI * Math.min(1, since / DAYS));
    }

    /**
     * Advances {@code days} under rain of {@code precipitation} (0..1); true when a flood starts now.
     */
    public boolean update(double days, double precipitation) {
        if (days <= 0) return false;
        rain = rain * Math.exp(-days / MEMORY_DAYS) + Math.max(0, precipitation) * days;
        rest = Math.max(0, rest - days);
        if (since >= 0) {
            since += days;
            return false;
        }
        if (rain >= THRESHOLD && rest <= 0) {
            since = 0;
            rain = 0;
            reached.clear();
            return true;
        }
        return false;
    }

    /**
     * Lays or takes back the flood's water round (px, pz) within {@code radius}; when the flood has
     * passed, leaves its marks. Returns the blocks changed (x, y, z).
     */
    public List<int[]> apply(World w, int px, int pz, int radius, River river) {
        List<int[]> changed = new ArrayList<>();
        double h = height();
        int rise = (int) Math.round(h);
        // Take back what stands above the water's level now.
        for (var it = placed.entrySet().iterator(); it.hasNext(); ) {
            int[] c = it.next().getValue();
            int top = since >= DAYS || since < 0 ? c[2] - 1 : Math.min(c[3], c[4] + rise);
            for (int y = c[3]; y > top && y >= c[2]; y--)
                if (Blocks.FLUID[w.get(c[0], y, c[1]) & 0xFFFF] == Blocks.FLUID_WATER && w.set(c[0], y, c[1], Blocks.AIR)) changed.add(new int[] {c[0], y, c[1]});
            c[3] = Math.min(c[3], top);
            if (c[3] < c[2]) it.remove();
        }
        if (since >= DAYS) { // the flood has passed: its marks, and the calendar starts again
            if (placed.isEmpty()) {
                marks(w, changed);
                since = -1;
                rest = REST_DAYS;
            }
            return changed;
        }
        if (since < 0 || rise <= 0) return changed;
        // Spread from the river's columns over the low ground, at the river's level plus the rise.
        ArrayDeque<int[]> queue = new ArrayDeque<>(); // x, z, flood top y, steps
        Set<Long> seen = new HashSet<>();
        for (int z = pz - radius; z <= pz + radius; z++)
            for (int x = px - radius; x <= px + radius; x++) {
                if (!river.at(x, z)) continue;
                int top = waterTop(w, x, z);
                if (top == Integer.MIN_VALUE) continue;
                seen.add(key(x, z));
                queue.add(new int[] {x, z, top + rise, 0, top});
            }
        while (!queue.isEmpty()) {
            int[] q = queue.poll();
            if (q[3] >= SPREAD) continue;
            for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = q[0] + o[0], z = q[1] + o[1];
                if (Math.abs(x - px) > radius || Math.abs(z - pz) > radius || !seen.add(key(x, z))) continue;
                if (w.chunk(x >> 4, z >> 4) == null) continue;
                int[] already = placed.get(key(x, z));
                int ground = already != null ? already[2] - 1 : ground(w, x, z, q[2] + 1);
                if (ground == Integer.MIN_VALUE || ground >= q[2]) continue; // higher than the flood
                if (heldByWillow(w, x, z, ground)) continue;
                int[] c = placed.computeIfAbsent(key(x, z), k -> new int[] {x, z, ground + 1, ground, q[4]});
                for (int y = c[3] + 1; y <= q[2]; y++) {
                    short b = w.get(x, y, z);
                    if (b != Blocks.AIR && Blocks.SHAPE[b & 0xFFFF] != Blocks.SHAPE_CROSS) break;
                    if (w.set(x, y, z, Blocks.WATER)) changed.add(new int[] {x, y, z});
                    c[3] = y;
                }
                if (c[3] < c[2]) { placed.remove(key(x, z)); continue; }
                reached.put(key(x, z), new int[] {x, z, ground});
                queue.add(new int[] {x, z, q[2], q[3] + 1, q[4]});
            }
        }
        return changed;
    }

    /** The highest water block of a river column (searched round the ground), or MIN_VALUE. */
    static int waterTop(World w, int x, int z) {
        for (int y = Chunk.MAX_Y - 1; y > Chunk.MIN_Y; y--) {
            short b = w.get(x, y, z);
            if (Blocks.FLUID[b & 0xFFFF] == Blocks.FLUID_WATER) return y;
            if (Blocks.isSolid(b)) return Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    /** The top solid block at or under {@code from}, or MIN_VALUE (water first: already wet). */
    static int ground(World w, int x, int z, int from) {
        for (int y = from; y > from - 24; y--) {
            short b = w.get(x, y, z);
            if (Blocks.FLUID[b & 0xFFFF] != Blocks.FLUID_NONE) return Integer.MIN_VALUE;
            if (Blocks.isSolid(b)) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** A willow's crown within three blocks: its roots hold the bank. */
    static boolean heldByWillow(World w, int x, int z, int ground) {
        for (int dz = -3; dz <= 3; dz++)
            for (int dx = -3; dx <= 3; dx++)
                for (int y = ground + 2; y <= ground + 8; y++)
                    if (w.get(x + dx, y, z + dz) == Blocks.WILLOW_LEAVES) return true;
        return false;
    }

    /** The flood's marks: driftwood along its highest line, silt on the ground it covered. */
    private void marks(World w, List<int[]> changed) {
        for (int[] c : reached.values()) {
            int x = c[0], z = c[1], g = c[2];
            long hsh = (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
            int r = (int) ((hsh >>> 40) & 0xFF);
            boolean edge = false;
            for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + o[0], nz = z + o[1];
                if (!reached.containsKey(key(nx, nz)) && Blocks.FLUID[w.get(nx, g, nz) & 0xFFFF] == Blocks.FLUID_NONE
                    && Blocks.FLUID[w.get(nx, g + 1, nz) & 0xFFFF] == Blocks.FLUID_NONE) edge = true;
            }
            short top = w.get(x, g, z);
            if (edge && r % 3 == 0 && w.get(x, g + 1, z) == Blocks.AIR) {
                if (w.set(x, g + 1, z, Blocks.DRIFTWOOD)) changed.add(new int[] {x, g + 1, z});
            } else if ((top == Blocks.GRASS || top == Blocks.DIRT || top == Blocks.SAND) && r % 2 == 0) {
                if (w.set(x, g, z, Blocks.SILT)) changed.add(new int[] {x, g, z});
            }
        }
        reached.clear();
    }

    /** Columns flooded now (for tests and the notebook). */
    public int flooded() {
        return placed.size();
    }

    public void save(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(rain).append(' ').append(since).append(' ').append(rest).append('\n');
        for (int[] c : placed.values()) sb.append("p ").append(c[0]).append(' ').append(c[1]).append(' ').append(c[2]).append(' ').append(c[3]).append(' ').append(c[4]).append('\n');
        for (int[] c : reached.values()) sb.append("r ").append(c[0]).append(' ').append(c[1]).append(' ').append(c[2]).append('\n');
        Files.writeString(file, sb);
    }

    public void load(Path file) throws IOException {
        if (!Files.exists(file)) return;
        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty()) return;
        String[] h = lines.get(0).trim().split(" ");
        rain = Double.parseDouble(h[0]);
        since = Double.parseDouble(h[1]);
        rest = Double.parseDouble(h[2]);
        for (String line : lines.subList(1, lines.size())) {
            String[] t = line.trim().split(" ");
            if (t[0].equals("p") && t.length == 6) {
                int[] c = {Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]), Integer.parseInt(t[4]), Integer.parseInt(t[5])};
                placed.put(key(c[0], c[1]), c);
            } else if (t[0].equals("r") && t.length == 4) {
                int[] c = {Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3])};
                reached.put(key(c[0], c[1]), c);
            }
        }
    }
}
