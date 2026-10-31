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
    static final double WHEAT_STAGE_DAYS = 1.5, BERRY_DAYS = 3, SAPLING_DAYS = 4, TRAP_DAYS = 1;

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
        return changed;
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
