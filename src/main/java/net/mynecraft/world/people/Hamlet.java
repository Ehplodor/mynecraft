package net.mynecraft.world.people;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mynecraft.world.Blocks;
import net.mynecraft.world.Inventory;
import net.mynecraft.world.Items;
import net.mynecraft.world.World;
import net.mynecraft.world.actor.Actor;
import net.mynecraft.world.actor.ActorCodec;
import net.mynecraft.world.actor.Flows;
import net.mynecraft.world.actor.Gestures;
import net.mynecraft.world.actor.Job;
import net.mynecraft.world.actor.Matter;
import net.mynecraft.world.actor.Skill;
import net.mynecraft.world.build.Blueprint;
import net.mynecraft.world.crafting.RecipeBook;
import net.mynecraft.world.crafting.Workshop;
import net.mynecraft.world.life.Ecology;
import net.mynecraft.world.life.Ecosystem;
import net.mynecraft.world.life.Species;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The first inhabitants (M31): a hermit in his hut, and a family come to settle beside him, whose
 * builder raises their own hut from the same plan the player can learn. They live by the same
 * rules as the player: they gather, fish and cook through the same gestures and recipes, eat from
 * the hamlet's store, and every matter they take is written in the ledger of flows (so a hamlet
 * that fishes too much empties the river as the player would). Asleep at night and at home in the
 * rain. KISS: their gathering of building material (wood, stone, straw) is drawn from the patch's
 * account rather than walked tree by tree.
 */
public final class Hamlet {
    public final List<Villager> villagers = new ArrayList<>();
    /** Shared larder and woodpile. */
    public final Inventory store = new Inventory();
    /** Hut anchors (lowest north-west corner): [0] the hermit's, [1] the family's (being built). */
    public final List<int[]> huts = new ArrayList<>();
    public boolean founded;
    public double clock;
    private final SplittableRandom rnd = new SplittableRandom(7);
    private final Blueprint hut = Blueprint.hut();
    /** Blocks changed by the inhabitants this tick (for redrawing). */
    public final java.util.ArrayDeque<int[]> edits = new java.util.ArrayDeque<>();

    /**
     * Founds the hamlet near (cx, cz): the hermit's hut stands already, the family's site is
     * marked beside it. {@code ground(x, z)} gives the height of the ground there.
     */
    public void found(World w, int cx, int cz, java.util.function.IntBinaryOperator ground) {
        int[] a = site(w, cx + 14, cz + 6, ground), b = site(w, cx + 22, cz + 6, ground);
        huts.add(a);
        huts.add(b);
        build(w, hut, a);
        Villager hermit = person("Anselme", Job.HERBALIST, 0x5a6b3a, 0x4a3a28, false);
        hermit.skills.xp[Skill.FORAGING.ordinal()] = 400; // a master gatherer: he can teach
        hermit.skills.xp[Skill.COOKING.ordinal()] = 200;
        hermit.setHome(a[0] + 1.5, a[1] + 1, a[2] + 1.5);
        Villager builder = person("Berthe", Job.BUILDER, 0x9a4030, 0x3a3a50, false);
        builder.skills.xp[Skill.BUILDING.ordinal()] = 320;
        builder.plans.add(hut.name);
        Villager fisher = person("Corentin", Job.FISHER, 0x3a5a90, 0x3a3a3a, false);
        fisher.skills.xp[Skill.FISHING.ordinal()] = 250;
        Villager child = person("Daphné", Job.GATHERER, 0xd0a040, 0x6a4a30, true);
        Villager hunter = person("Edmond", Job.HUNTER, 0x4a5a30, 0x50402a, false);
        hunter.skills.xp[Skill.HUNTING.ordinal()] = 280;
        hunter.setHome(a[0] + 2.5, a[1] + 1, a[2] + 6.5); // lodges by the hermit's fire
        place(hunter, a[0] + 3.5, a[1] + 1, a[2] + 6.5);
        for (Villager v : List.of(builder, fisher, child)) v.setHome(b[0] + 2.5, b[1] + 1, b[2] + 6.5);
        place(hermit, a[0] + 2.5, a[1] + 1, a[2] + 6.5);
        for (int i = 0; i < 3; i++) place(List.of(builder, fisher, child).get(i), b[0] + 1.5 + i, b[1] + 1, b[2] + 6.5);
        store.add(Items.STICK, 16);
        store.add(Items.SWEET_BERRIES, 8);
        founded = true;
    }

    private Villager person(String name, Job job, int shirt, int trousers, boolean child) {
        Villager v = new Villager(name, job);
        v.shirt = shirt;
        v.trousers = trousers;
        v.child = child;
        villagers.add(v);
        return v;
    }

    private static void place(Villager v, double x, double y, double z) {
        v.x = x;
        v.y = y;
        v.z = z;
    }

    /** Anchor of a hut near (x, z): its footing at the ground's height (the lowest of its corners). */
    private static int[] site(World w, int x, int z, java.util.function.IntBinaryOperator ground) {
        int y = Integer.MAX_VALUE;
        for (int dz = 0; dz < 6; dz += 5) for (int dx = 0; dx < 5; dx += 4) y = Math.min(y, ground.applyAsInt(x + dx, z + dz));
        return new int[] {x, y, z};
    }

    /** Raises a plan at once (the hermit's hut was there before the player came). */
    private void build(World w, Blueprint p, int[] a) {
        // A clearing: trees over the site are felled (their wood goes to the hamlet's pile).
        for (int y = a[1] + 1; y < a[1] + p.h + 8; y++)
            for (int z = -1; z <= p.d; z++)
                for (int x = -1; x <= p.w; x++) {
                    short b = w.get(a[0] + x, y, a[2] + z);
                    if (Blocks.NAMES[b & 0xFFFF].endsWith("_leaves") || Blocks.NAMES[b & 0xFFFF].endsWith("_log") || Blocks.isPlant(b)) {
                        if (Blocks.NAMES[b & 0xFFFF].endsWith("_log")) store.add(Blocks.fromState(Blocks.NAMES[b & 0xFFFF], java.util.Map.of()), 1);
                        set(w, a[0] + x, y, a[2] + z, Blocks.AIR);
                    }
                }
        for (int i = 0; i < 400; i++) {
            int[] s = p.next(w, a[0], a[1], a[2]);
            if (s == null) break;
            set(w, s[0], s[1], s[2], (short) s[3]);
        }
        // Earth under the footing, so the hut does not float over a slope.
        for (int z = 0; z < p.d; z++)
            for (int x = 0; x < p.w; x++)
                for (int y = a[1] - 1; y > a[1] - 6 && !Blocks.isSolid(w.get(a[0] + x, y, a[2] + z)); y--) set(w, a[0] + x, y, a[2] + z, Blocks.DIRT);
    }

    private void set(World w, int x, int y, int z, short b) {
        if (w.set(x, y, z, b)) edits.add(new int[] {x, y, z});
    }

    /** One step of the hamlet's life: {@code daylight} 0..1, {@code day} the calendar. */
    public void tick(double dt, World w, Flows flows, Ecology ecology, RecipeBook recipes, double daylight, boolean raining,
                     double day) {
        clock += dt;
        lastWorld = w;
        for (Villager v : villagers) {
            if (w.chunk((int) Math.floor(v.x) >> 4, (int) Math.floor(v.z) >> 4) == null) continue; // far away: resting
            v.skills.rust(day);
            boolean night = daylight < 0.25;
            v.needs.tick(dt, v.state == Villager.State.WORK ? 0.4 : v.state == Villager.State.WALK ? 0.2 : 0, 15,
                v.state == Villager.State.SLEEP);
            if (v.needs.food < 12) eat(v);
            if (v.needs.water < 10) v.needs.drink(20); // the hamlet's well (KISS)
            v.stateTime += dt;
            if (!v.path.isEmpty()) { walk(v, w, dt); continue; }
            if (night || raining) { // home: sleep (or shelter)
                if (dist(v, v.homeX, v.homeZ) > 1.5) go(v, w, (int) Math.floor(v.homeX), (int) Math.floor(v.homeY), (int) Math.floor(v.homeZ));
                else v.state = night ? Villager.State.SLEEP : Villager.State.IDLE;
                continue;
            }
            if (v.state == Villager.State.SLEEP) v.state = Villager.State.IDLE;
            work(v, w, flows, ecology, recipes, dt);
        }
    }

    private void eat(Villager v) {
        short best = Blocks.AIR;
        for (int i = 0; i < Inventory.SIZE; i++) {
            short id = store.id(i);
            if (Items.isFood(id) && (best == Blocks.AIR || v.needs.nourishment(id) > v.needs.nourishment(best))) best = id;
        }
        if (best != Blocks.AIR) { store.remove(best, 1); v.needs.eat(best); }
    }

    private void work(Villager v, World w, Flows flows, Ecology ecology, RecipeBook recipes, double dt) {
        v.workTimer += dt;
        switch (v.job) {
            case BUILDER -> {
                int[] a = huts.size() > 1 ? huts.get(1) : null;
                int[] step = a == null ? null : hut.next(w, a[0], a[1], a[2]);
                if (step == null) { cook(v, recipes, flows); return; }
                if (v.workAt != null) { fetch(v, w, flows); return; } // on the way to a tree, a rock, a tuft of grass
                if (dist(v, step[0] + 0.5, step[2] + 0.5) > 7) { go(v, w, a[0] + 2, a[1] + 1, a[2] + 6); return; } // works from the yard
                v.state = Villager.State.WORK;
                if (v.workTimer < 2.5 / v.efficiency(Skill.BUILDING)) return;
                v.workTimer = 0;
                short need = step[3] == Blocks.AIR ? Blocks.AIR : Blueprint.item((short) step[3]);
                if (need != Blocks.AIR && store.count(need) == 0) { gatherMaterial(v, w, need, flows); return; }
                if (need != Blocks.AIR) store.remove(need, 1);
                set(w, step[0], step[1], step[2], (short) step[3]);
                v.skills.practice(Skill.BUILDING, 1);
                if (step[3] == Blocks.STRAW_BED && hut.next(w, a[0], a[1], a[2]) == null) {
                    for (Villager o : villagers) if (o.homeX == v.homeX) o.setHome(a[0] + 2.5, a[1] + 1, a[2] + 2.5); // moving in
                }
            }
            case FISHER -> {
                if (v.workAt == null) { v.workAt = bank(w, (int) v.x, (int) v.z); if (v.workAt == null) { wander(v, w); return; } }
                if (dist(v, v.workAt[0] + 0.5, v.workAt[2] + 0.5) > 2) { go(v, w, v.workAt[0], v.workAt[1], v.workAt[2]); return; }
                v.state = Villager.State.WORK;
                if (v.workTimer < 40 / v.efficiency(Skill.FISHING)) return;
                v.workTimer = 0;
                Ecology.Patch p = ecology.existing(v.x, v.z);
                if (p != null && p.pop[Species.TROUT.ordinal()] >= 1) { // only what the river holds
                    ecology.died(Species.TROUT, v.x, v.z);
                    flows.take(Matter.FISH, 1, v.x, v.z);
                    store.add(Items.TROUT, 1);
                    say(v, "Une belle truite !");
                } else say(v, "Rien ne mord... la rivière est vide.");
                v.skills.practice(Skill.FISHING, 2);
            }
            case HUNTER -> { // hunts deer in the forest around, never in their breeding season, never the last ones
                if (v.stateTime > 30) wander(v, w);
                if (v.workTimer < 120 / v.efficiency(Skill.HUNTING)) return;
                v.workTimer = 0;
                Ecology.Patch p = ecology.existing(v.x, v.z);
                if (p == null) return;
                if (Ecology.breeding(Species.DEER, ecology.day() % Ecosystem.YEAR_DAYS / (double) Ecosystem.YEAR_DAYS) > 0.3) say(v, "Je laisse les biches mettre bas.");
                else if (p.pop[Species.DEER.ordinal()] < 3) say(v, "Trop peu de cerfs ici : je les laisse en paix.");
                else {
                    ecology.died(Species.DEER, v.x, v.z);
                    flows.take(Matter.GAME, 1, v.x, v.z);
                    store.add(Items.VENISON, 2);
                    store.add(Items.HIDE, 1);
                    say(v, "Un cerf. Merci à la forêt.");
                }
                v.skills.practice(Skill.HUNTING, 2);
            }
            default -> { // gatherers and the herbalist
                if (v.workAt == null || v.stateTime > 60) {
                    v.stateTime = 0;
                    v.workAt = bush(w, (int) Math.floor(v.homeX), (int) Math.floor(v.homeY), (int) Math.floor(v.homeZ));
                    if (v.workAt == null) { wander(v, w); return; }
                }
                if (dist(v, v.workAt[0] + 0.5, v.workAt[2] + 0.5) > 1.8) { go(v, w, v.workAt[0], v.workAt[1], v.workAt[2]); return; }
                v.state = Villager.State.WORK;
                if (v.workTimer < 6 / v.efficiency(Skill.FORAGING)) return;
                v.workTimer = 0;
                if (w.get(v.workAt[0], v.workAt[1], v.workAt[2]) == Blocks.BERRY_BUSH) {
                    Gestures.use(v, w, 0, v.workAt[0], v.workAt[1], v.workAt[2], false, false, flows, null, rnd);
                    edits.add(v.workAt.clone());
                    store.add(Items.SWEET_BERRIES, v.inventory.remove(Items.SWEET_BERRIES, 64));
                } else { // herbs and fibre along the way
                    flows.take(Matter.PLANTS, 1, v.x, v.z);
                    store.add(rnd.nextBoolean() ? Items.WILD_HERBS : Items.PLANT_FIBER, 1);
                    v.skills.practice(Skill.FORAGING, 0.5);
                }
                v.workAt = null;
                if (v.job == Job.HERBALIST) cook(v, recipes, flows);
            }
        }
    }

    /** Cooks what is raw in the store at the campfire (same recipes as the player). */
    private void cook(Villager v, RecipeBook recipes, Flows flows) {
        for (RecipeBook.Process p : recipes.at("campfire")) {
            Inventory own = v.inventory;
            for (RecipeBook.Input in : p.inputs()) moveAll(store, own, in.ingredient());
            moveFuel(store, own);
            if (Workshop.make(v, p, 0, flows)) say(v, "J'ai préparé " + Items.name(p.outputs().get(0).item()).replace('_', ' ') + ".");
            for (int i = 0; i < Inventory.SIZE; i++) if (own.counts[i] > 0) store.add(own.ids[i], own.counts[i]);
            own.clear();
        }
    }

    private static void moveAll(Inventory from, Inventory to, RecipeBook.Ingredient ing) {
        for (int i = 0; i < Inventory.SIZE; i++)
            if (from.counts[i] > 0 && ing.test(from.ids[i])) { to.add(from.ids[i], from.counts[i]); from.counts[i] = 0; from.ids[i] = Blocks.AIR; }
    }

    private static void moveFuel(Inventory from, Inventory to) {
        for (int i = 0; i < Inventory.SIZE; i++)
            if (from.counts[i] > 0 && Items.FUEL[from.ids[i]] > 0) { to.add(from.ids[i], from.counts[i]); from.counts[i] = 0; from.ids[i] = Blocks.AIR; }
    }

    /**
     * Material for the plan: timber from a real tree, stone from a real rock, straw from real grass,
     * each found within 24 blocks and walked to ({@link #fetch}); planks, doors, thatch and the bed are
     * then made from them as the recipes say. What has no source near (glass, a campfire) is drawn from
     * the patch's account.
     */
    private void gatherMaterial(Villager v, World w, short item, Flows flows) {
        if (item == Blocks.THATCH && store.count(Items.PLANT_FIBER) >= 4) { store.remove(Items.PLANT_FIBER, 4); store.add(Blocks.THATCH, 1); return; }
        if (item == Blocks.STRAW_BED && store.count(Blocks.THATCH) >= 3 && store.count(Blocks.OAK_PLANKS) >= 3) {
            store.remove(Blocks.THATCH, 3); store.remove(Blocks.OAK_PLANKS, 3); store.add(Blocks.STRAW_BED, 1); return;
        }
        if ((item == Blocks.OAK_PLANKS || item == Blocks.OAK_DOOR) && store.count(Blocks.OAK_LOG) > 0) {
            store.remove(Blocks.OAK_LOG, 1); store.add(Blocks.OAK_PLANKS, 4);
            if (item == Blocks.OAK_DOOR && store.count(Blocks.OAK_PLANKS) >= 6) { store.remove(Blocks.OAK_PLANKS, 6); store.add(Blocks.OAK_DOOR, 3); }
            return;
        }
        if (item == Blocks.OAK_DOOR && store.count(Blocks.OAK_PLANKS) >= 6) { store.remove(Blocks.OAK_PLANKS, 6); store.add(Blocks.OAK_DOOR, 3); return; }
        java.util.function.Predicate<Short> source = item == Blocks.COBBLESTONE ? b -> b == Blocks.STONE || b == Blocks.COBBLESTONE
            : item == Blocks.THATCH || item == Blocks.STRAW_BED ? b -> b == Blocks.SHORT_GRASS
            : item == Blocks.OAK_LOG || item == Blocks.OAK_PLANKS || item == Blocks.OAK_DOOR ? b -> b == Blocks.OAK_LOG : null;
        int[] at = source == null ? null : find(w, (int) Math.floor(v.homeX), (int) Math.floor(v.homeY), (int) Math.floor(v.homeZ), source);
        if (at == null) { // nothing of it nearby: drawn from the patch's account
            byte m = Blocks.MATTER[item & 0xFFFF];
            Matter matter = m >= 0 ? Matter.values()[m] : Matter.WOOD;
            flows.take(matter, 1, v.x, v.z);
            store.add(item, 1);
            return;
        }
        v.workAt = at;
    }

    /** Walks to the source in {@code v.workAt} and takes it: a log, a stone, a tuft of grass. */
    private void fetch(Villager v, World w, Flows flows) {
        int[] at = v.workAt;
        if (dist(v, at[0] + 0.5, at[2] + 0.5) > 3 || Math.abs(v.y - at[1]) > 4) {
            if (v.state != Villager.State.WALK) go(v, w, at[0], at[1], at[2]);
            if (v.path.isEmpty()) v.workAt = null; // out of reach: another next time
            return;
        }
        v.state = Villager.State.WORK;
        if (v.workTimer < 3 / v.efficiency(Skill.WOODCUTTING)) return;
        v.workTimer = 0;
        short b = w.get(at[0], at[1], at[2]);
        v.workAt = null;
        if (b == Blocks.OAK_LOG) {
            int top = at[1]; // fells the whole trunk
            while (w.get(at[0], top, at[2]) == Blocks.OAK_LOG) { set(w, at[0], top, at[2], Blocks.AIR); store.add(Blocks.OAK_LOG, 1); top++; }
            flows.take(Matter.WOOD, top - at[1], at[0], at[2]);
            v.skills.practice(Skill.WOODCUTTING, 2);
            say(v, "Un chêne pour la charpente.");
        } else if (b == Blocks.STONE || b == Blocks.COBBLESTONE) {
            set(w, at[0], at[1], at[2], Blocks.AIR);
            store.add(Blocks.COBBLESTONE, 1);
            flows.take(Matter.STONE, 1, at[0], at[2]);
            v.skills.practice(Skill.MINING, 1);
        } else if (b == Blocks.SHORT_GRASS) {
            set(w, at[0], at[1], at[2], Blocks.AIR);
            store.add(Items.PLANT_FIBER, 2);
            flows.take(Matter.PLANTS, 1, at[0], at[2]);
            v.skills.practice(Skill.FORAGING, 0.5);
        }
    }

    /** The nearest block matching {@code what} within 24 blocks that has open air beside or above it. */
    private static int[] find(World w, int x, int y, int z, java.util.function.Predicate<Short> what) {
        for (int r = 3; r <= 24; r++)
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = -6; dy <= 8; dy++) {
                        short b = w.get(x + dx, y + dy, z + dz);
                        if (!what.test(b)) continue;
                        if (w.get(x + dx, y + dy + 1, z + dz) == Blocks.AIR || w.get(x + dx, y + dy - 1, z + dz) != Blocks.OAK_LOG
                            && PathFinder.standable(w, x + dx + 1, y + dy, z + dz)) {
                            if (b == Blocks.OAK_LOG) while (w.get(x + dx, y + dy - 1, z + dz) == Blocks.OAK_LOG) dy--; // the foot of the trunk
                            return new int[] {x + dx, y + dy, z + dz};
                        }
                    }
                }
        return null;
    }

    // ---------------------------------------------------------------------------- moving

    private void go(Villager v, World w, int x, int y, int z) {
        v.path.clear();
        v.path.addAll(PathFinder.find(w, (int) Math.floor(v.x), (int) Math.floor(v.y), (int) Math.floor(v.z), x, y, z, 3000));
        v.state = v.path.isEmpty() ? Villager.State.IDLE : Villager.State.WALK;
        if (v.path.isEmpty()) { v.workAt = null; v.stateTime = 61; } // cannot get there: choose again later
    }

    private void wander(Villager v, World w) {
        if (v.stateTime < 8) return;
        v.stateTime = 0;
        int x = (int) Math.floor(v.homeX) + rnd.nextInt(-10, 11), z = (int) Math.floor(v.homeZ) + rnd.nextInt(-10, 11);
        go(v, w, x, (int) Math.floor(v.homeY), z);
    }

    private void walk(Villager v, World w, double dt) {
        int[] c = v.path.get(0);
        double tx = c[0] + 0.5, tz = c[2] + 0.5, dx = tx - v.x, dz = tz - v.z, d = Math.hypot(dx, dz);
        double speed = (v.child ? 2.6 : 2.2) * dt;
        if (w.get(c[0], c[1], c[2]) == Blocks.OAK_DOOR) set(w, c[0], c[1], c[2], Blocks.OAK_DOOR_OPEN); // opens on the way
        if (w.get(c[0], c[1] + 1, c[2]) == Blocks.OAK_DOOR) set(w, c[0], c[1] + 1, c[2], Blocks.OAK_DOOR_OPEN);
        v.yaw = (float) Math.atan2(dx, dz);
        v.gait += (float) (speed * 4);
        if (d <= speed) {
            v.x = tx;
            v.z = tz;
            v.y = c[1];
            v.path.remove(0);
            if (v.path.isEmpty()) v.state = Villager.State.IDLE;
        } else {
            v.x += dx / d * speed;
            v.z += dz / d * speed;
            v.y += (c[1] - v.y) * Math.min(1, dt * 8);
        }
    }

    private static double dist(Actor a, double x, double z) {
        return Math.hypot(a.x - x, a.z - z);
    }

    /** A berry bush within 16 blocks of (x, z), standing cell beside it. */
    private static int[] bush(World w, int x, int y, int z) {
        for (int r = 2; r <= 16; r += 2)
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = -4; dy <= 4; dy++)
                        if (w.get(x + dx, y + dy, z + dz) == Blocks.BERRY_BUSH) return new int[] {x + dx, y + dy, z + dz};
                }
        return null;
    }

    /** A standing cell on a bank within 32 blocks. */
    private static int[] bank(World w, int x, int z) {
        for (int r = 2; r <= 32; r += 2)
            for (int dz = -r; dz <= r; dz += 2)
                for (int dx = -r; dx <= r; dx += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int y = 120; y > 40; y--) {
                        short b = w.get(x + dx, y, z + dz);
                        if (b == Blocks.AIR) continue;
                        if (Blocks.FLUID[b & 0xFFFF] == Blocks.FLUID_WATER) {
                            for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                                if (PathFinder.standable(w, x + dx + o[0], y + 1, z + dz + o[1])) return new int[] {x + dx + o[0], y + 1, z + dz + o[1]};
                        }
                        break;
                    }
                }
        return null;
    }

    void say(Villager v, String s) {
        v.saying = s;
        v.sayingUntil = clock + 5;
    }

    // ---------------------------------------------------------------------------- talking and teaching

    /**
     * The player talks to an inhabitant: a word about its needs, its trade, or a request; once a day a
     * master teaches its skill (the player's practice of it counts double for a day), and the other way
     * round when the player knows more.
     */
    public String talk(Villager v, Actor player) {
        Skill skill = v.job.skills[0];
        int mine = v.skills.level(skill), theirs = player.skills.level(skill);
        String s;
        if (clock - v.lastLesson > 600 && mine >= theirs + 2) {
            v.lastLesson = clock;
            player.skills.taughtUntil[skill.ordinal()] = player.skills.today + 1;
            s = "Viens, je vais te montrer. (" + skill.displayName + " : ta pratique compte double aujourd'hui)";
        } else if (clock - v.lastLesson > 600 && theirs >= mine + 2) {
            v.lastLesson = clock;
            v.skills.taughtUntil[skill.ordinal()] = v.skills.today + 1;
            s = "Tu en sais plus que moi en " + skill.displayName.toLowerCase() + "... montre-moi ?";
        } else if (v.needs.food < 8) s = "J'ai faim. Le garde-manger est presque vide.";
        else if (v.job == Job.BUILDER && huts.size() > 1 && lastWorld != null
            && hut.next(lastWorld, huts.get(1)[0], huts.get(1)[1], huts.get(1)[2]) != null)
            s = "Je bâtis notre cabane. Il me manque du bois, de la pierre et du chaume.";
        else if (v.job == Job.FISHER) s = "Ne pêche pas les truites à l'automne : c'est leur fraie.";
        else if (v.job == Job.HERBALIST) s = "Les baies repoussent si l'on en laisse. Prends-en, mais pas tout.";
        else s = "Bonjour ! Je suis " + v.name + ", " + (v.skills.roleTitle().isEmpty() ? v.job.displayName : v.skills.roleTitle()) + ".";
        say(v, s);
        return v.name + " : " + s;
    }

    /** The world last ticked (for talking). */
    public World lastWorld;

    // ---------------------------------------------------------------------------- saving

    public void save(Path file) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("founded", founded);
        o.addProperty("clock", clock);
        JsonArray hs = new JsonArray();
        for (int[] h : huts) { JsonArray a = new JsonArray(); for (int v : h) a.add(v); hs.add(a); }
        o.add("huts", hs);
        o.add("store", items(store));
        JsonArray vs = new JsonArray();
        for (Villager v : villagers) {
            JsonObject j = ActorCodec.write(v);
            j.addProperty("job", v.job.name());
            j.addProperty("shirt", v.shirt);
            j.addProperty("trousers", v.trousers);
            j.addProperty("child", v.child);
            JsonArray p = new JsonArray();
            p.add(v.x);
            p.add(v.y);
            p.add(v.z);
            j.add("pos", p);
            vs.add(j);
        }
        o.add("villagers", vs);
        Files.writeString(file, o.toString());
    }

    public void load(Path file) throws IOException {
        if (!Files.exists(file)) return;
        JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        founded = o.get("founded").getAsBoolean();
        clock = o.get("clock").getAsDouble();
        for (var h : o.getAsJsonArray("huts")) {
            JsonArray a = h.getAsJsonArray();
            huts.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
        }
        readItems(store, o.getAsJsonObject("store"));
        for (var e : o.getAsJsonArray("villagers")) {
            JsonObject j = e.getAsJsonObject();
            Villager v = new Villager(j.get("name").getAsString(), Job.valueOf(j.get("job").getAsString()));
            ActorCodec.read(v, j);
            v.shirt = j.get("shirt").getAsInt();
            v.trousers = j.get("trousers").getAsInt();
            v.child = j.get("child").getAsBoolean();
            JsonArray p = j.getAsJsonArray("pos");
            place(v, p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble());
            villagers.add(v);
        }
    }

    private static JsonObject items(Inventory inv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < Inventory.SIZE; i++)
            if (inv.counts[i] > 0) o.addProperty(Items.name(inv.ids[i]), (o.has(Items.name(inv.ids[i])) ? o.get(Items.name(inv.ids[i])).getAsInt() : 0) + inv.counts[i]);
        return o;
    }

    private static void readItems(Inventory inv, JsonObject o) {
        for (var e : o.entrySet()) {
            short id = Items.fromName(e.getKey());
            if (id > 0) inv.add(id, e.getValue().getAsInt());
        }
    }
}
