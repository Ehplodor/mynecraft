package net.mynecraft.world;

import net.mynecraft.world.life.Ecology;
import net.mynecraft.world.life.Vegetation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What grows where someone acted (M29, M32): picked berry bushes bearing again, sown wheat ripening,
 * saplings becoming trees. Growth follows the local calendar (the patch's climate, rain and season,
 * see life.Vegetation), the soil of the patch (which a harvest draws on and compost restores), and
 * water within four blocks or rain. Only the planted places are followed, by position.
 */
public final class Growth {
    /** Days of full growth per wheat stage, for a berry bush to bear again, for a sapling to become a tree. */
    static final double WHEAT_STAGE_DAYS = 1.5, BERRY_DAYS = 3, SAPLING_DAYS = 4, TRAP_DAYS = 1, REED_DAYS = 3;
    /** Reeds stop spreading when this many already grow within four blocks. */
    static final int REED_CROWD = 12;

    private final Map<Long, double[]> places = new LinkedHashMap<>(); // position -> {progress 0..1}

    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int[] unkey(long k) {
        int x = (int) (k >> 38), z = (int) (k << 26 >> 38), y = (int) (k << 52 >> 52);
        return new int[] {x, y, z};
    }

    /** Follow this place (just sown, planted or picked). */
    public void planted(int x, int y, int z) {
        places.put(key(x, y, z), new double[] {0});
    }

    /** Nests where a chick just hatched (x, y, z), for the caller to bring it to life. */
    public final List<int[]> hatched = new ArrayList<>();
    static final double HATCH_DAYS = 2, SWARM_DAYS = 1, HONEY_DAYS = 3;

    public int size() {
        return places.size();
    }

    /**
     * Advances {@code days} of the calendar for every followed place whose chunk is loaded; returns
     * the blocks changed (x, y, z) so the caller can redraw them.
     */
    public List<int[]> grow(World w, Ecology ecology, net.mynecraft.world.actor.Flows flows, double phase, double days) {
        List<int[]> changed = new ArrayList<>();
        for (Iterator<Map.Entry<Long, double[]>> it = places.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            int[] p = unkey(e.getKey());
            if (w.chunk(p[0] >> 4, p[2] >> 4) == null) continue;
            short b = w.get(p[0], p[1], p[2]);
            Ecology.Patch patch = ecology.at(p[0], p[2]);
            double rate = Vegetation.growth(patch.climate, patch.rain, phase); // the patch's climate is in deg C already
            if (Blocks.wheatAge(b) >= 0) {
                boolean wet = patch.rain > 0.5 || nearWater(w, p[0], p[1] - 1, p[2]);
                rate *= (wet ? 1 : 0.4) * (0.4 + 0.6 * Math.min(1, patch.soil * 1.5));
                e.getValue()[0] += rate * days / WHEAT_STAGE_DAYS;
                if (e.getValue()[0] >= 1) {
                    e.getValue()[0] = 0;
                    int age = Blocks.wheatAge(b);
                    w.set(p[0], p[1], p[2], (short) (Blocks.WHEAT_0 + age + 1));
                    changed.add(p);
                    if (age + 1 == 3) {
                        patch.soil = Math.max(0, patch.soil - 0.002); // the ear draws on the soil
                        it.remove();
                    }
                }
            } else if (b == Blocks.FISH_TRAP || b == Blocks.SNARE) { // a trap catches in about a day, if the patch has the prey
                boolean fish = b == Blocks.FISH_TRAP;
                var prey = fish ? net.mynecraft.world.life.Species.TROUT : net.mynecraft.world.life.Species.RABBIT;
                e.getValue()[0] += days / TRAP_DAYS;
                if (e.getValue()[0] >= 1) {
                    e.getValue()[0] = 0;
                    if (patch.pop[prey.ordinal()] >= 1) {
                        ecology.died(prey, p[0], p[2]);
                        if (flows != null) flows.take(fish ? net.mynecraft.world.actor.Matter.FISH : net.mynecraft.world.actor.Matter.GAME, 1, p[0], p[2]);
                        w.set(p[0], p[1], p[2], fish ? Blocks.FISH_TRAP_FULL : Blocks.SNARE_FULL);
                        changed.add(p);
                        it.remove();
                    }
                }
            } else if (b == Blocks.NEST_EGG) { // brooded, an egg hatches in two days
                e.getValue()[0] += days / HATCH_DAYS;
                if (e.getValue()[0] >= 1) {
                    w.set(p[0], p[1], p[2], Blocks.NEST);
                    hatched.add(p);
                    changed.add(p);
                    it.remove();
                }
            } else if (b == Blocks.BEEHIVE || b == Blocks.BEEHIVE_BEES || b == Blocks.BEEHIVE_HONEY) {
                // An empty hive draws a wild swarm where flowers are many (not in winter); a colony makes honey from
                // the patch's flowers and pollinates them in return (mutualism, like the mist lanterns).
                boolean winter = phase >= 0.75;
                double bloom = Math.min(1, patch.flowers * 4);
                if (b == Blocks.BEEHIVE) {
                    if (!winter) e.getValue()[0] += days * bloom / SWARM_DAYS;
                    if (e.getValue()[0] >= 1) {
                        e.getValue()[0] = 0;
                        w.set(p[0], p[1], p[2], Blocks.BEEHIVE_BEES);
                        changed.add(p);
                    }
                } else {
                    patch.flowers = Math.min(1, patch.flowers + 0.01 * days);
                    patch.plants = Math.min(1.5, patch.plants + 0.002 * days);
                    if (b == Blocks.BEEHIVE_BEES && !winter) {
                        e.getValue()[0] += days * bloom / HONEY_DAYS;
                        if (e.getValue()[0] >= 1) {
                            e.getValue()[0] = 0;
                            w.set(p[0], p[1], p[2], Blocks.BEEHIVE_HONEY);
                            changed.add(p);
                        }
                    }
                }
            } else if (b == Blocks.REEDS) { // reeds spread along the wet bank, one clump at a time, until crowded
                if (!nearWater(w, p[0], p[1] - 1, p[2])) { it.remove(); continue; }
                e.getValue()[0] += rate * days / REED_DAYS;
                if (e.getValue()[0] >= 1) {
                    e.getValue()[0] = 0;
                    int[] s = reedSpot(w, p[0], p[1], p[2], (int) (e.getKey() * 31 + spreads++));
                    if (s == null) it.remove();
                    else {
                        w.set(s[0], s[1], s[2], Blocks.REEDS);
                        changed.add(s);
                        spread.add(s);
                    }
                }
            } else if (b == Blocks.WILLOW_SAPLING) { // a willow grows only with its feet near water
                if (!nearWater(w, p[0], p[1] - 1, p[2])) continue;
                e.getValue()[0] += rate * days / SAPLING_DAYS;
                if (e.getValue()[0] >= 1) {
                    willow(w, p[0], p[1], p[2]);
                    changed.add(p);
                    it.remove();
                }
            } else if (b == Blocks.BERRY_BUSH_PICKED || b == Blocks.OAK_SAPLING) {
                e.getValue()[0] += rate * days / (b == Blocks.OAK_SAPLING ? SAPLING_DAYS : BERRY_DAYS);
                if (e.getValue()[0] >= 1) {
                    if (b == Blocks.BERRY_BUSH_PICKED) w.set(p[0], p[1], p[2], Blocks.BERRY_BUSH);
                    else tree(w, p[0], p[1], p[2]);
                    changed.add(p);
                    it.remove();
                }
            } else {
                it.remove(); // dug up, eaten, harvested
            }
        }
        for (int[] q : spread) planted(q[0], q[1], q[2]);
        spread.clear();
        return changed;
    }

    private final List<int[]> spread = new ArrayList<>();
    private int spreads;

    /** Wet ground to sow a reed on, next to the reeds at (x, y, z); null when crowded or none. */
    static int[] reedSpot(World w, int x, int y, int z, int seed) {
        int count = 0;
        for (int dz = -4; dz <= 4; dz++)
            for (int dx = -4; dx <= 4; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    if (w.get(x + dx, y + dy, z + dz) == Blocks.REEDS) count++;
        if (count >= REED_CROWD) return null;
        for (int i = 0; i < 8; i++) {
            int a = Math.floorMod(seed + i * 3, 8);
            int dx = new int[] {1, -1, 0, 0, 1, -1, 1, -1}[a], dz = new int[] {0, 0, 1, -1, 1, 1, -1, -1}[a];
            for (int dy = -1; dy <= 1; dy++) {
                int sx = x + dx, sy = y + dy, sz = z + dz;
                if (reedGround(w, sx, sy - 1, sz) && w.get(sx, sy, sz) == Blocks.AIR) return new int[] {sx, sy, sz};
            }
        }
        return null;
    }

    /** Ground reeds take on: wet soil or sand by the water (within two blocks). */
    public static boolean reedGround(World w, int x, int y, int z) {
        short g = w.get(x, y, z);
        if (g != Blocks.GRASS && g != Blocks.DIRT && g != Blocks.SAND) return false;
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    if (Blocks.FLUID[w.get(x + dx, y + dy, z + dz) & 0xFFFF] == Blocks.FLUID_WATER) return true;
        return false;
    }

    /** A willow: a short oak trunk and a wide, drooping crown of willow leaves. */
    static void willow(World w, int x, int y, int z) {
        int top = y + 4;
        for (int dz = -3; dz <= 3; dz++)
            for (int dx = -3; dx <= 3; dx++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > 10) continue;
                for (int ly = top - 1; ly <= top + 1; ly++) {
                    if (ly == top + 1 && d2 > 4) continue;
                    if (w.get(x + dx, ly, z + dz) == Blocks.AIR) w.set(x + dx, ly, z + dz, Blocks.WILLOW_LEAVES);
                }
                if (d2 >= 5 && ((dx * 7 + dz * 13) & 1) == 0) // hanging curtains at the rim
                    for (int ly = top - 2; ly >= top - 3; ly--)
                        if (w.get(x + dx, ly, z + dz) == Blocks.AIR) w.set(x + dx, ly, z + dz, Blocks.WILLOW_LEAVES);
            }
        for (int ty = y; ty < top; ty++) w.set(x, ty, z, Blocks.OAK_LOG);
    }

    static boolean nearWater(World w, int x, int y, int z) {
        for (int dz = -4; dz <= 4; dz++)
            for (int dx = -4; dx <= 4; dx++)
                if (Blocks.FLUID[w.get(x + dx, y, z + dz) & 0xFFFF] == Blocks.FLUID_WATER) return true;
        return false;
    }

    /** A young oak where the sapling stood (the leaves around are redrawn by the caller's light updates). */
    static void tree(World w, int x, int y, int z) {
        int top = y + 4;
        for (int ly = top - 2; ly <= top + 1; ly++) {
            int r = ly >= top ? 1 : 2;
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    if (ly == top + 1 && Math.abs(dx) + Math.abs(dz) > 1) continue;
                    if (w.get(x + dx, ly, z + dz) == Blocks.AIR) w.set(x + dx, ly, z + dz, Blocks.OAK_LEAVES);
                }
        }
        for (int ty = y; ty < top; ty++) w.set(x, ty, z, Blocks.OAK_LOG);
    }

    public void save(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (var e : places.entrySet()) {
            int[] p = unkey(e.getKey());
            sb.append(p[0]).append(' ').append(p[1]).append(' ').append(p[2]).append(' ').append(e.getValue()[0]).append('\n');
        }
        Files.writeString(file, sb);
    }

    public void load(Path file) throws IOException {
        if (!Files.exists(file)) return;
        for (String line : Files.readAllLines(file)) {
            String[] t = line.trim().split(" ");
            if (t.length < 4) continue;
            places.put(key(Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2])), new double[] {Double.parseDouble(t[3])});
        }
    }
}
