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
import net.mynecraft.world.Doors;
import net.mynecraft.world.build.Blueprint;
import net.mynecraft.world.build.Site;
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
 * The first inhabitants (M31): a hermit in his cottage, and a family come to settle beside him, whose
 * builder raises their house from a plan the player can learn, then the bake-house. Around the green and
 * its well stand the hen-house and its run, the hermit's apiary; gravel paths join the doors to the well.
 * Each building is set on ground chosen flat and dry, levelled, banked and founded (see build.Site). They live by the same
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
    /**
     * Building anchors (lowest north-west corner of their plan): [0] the hermit's cottage, [1] the family's
     * house (built by the builder), then the well, the hen-house, the apiary, the bake-house.
     */
    public final List<int[]> huts = new ArrayList<>();
    /** The plan of each building (by name, see build.Blueprint), in the order of {@link #huts}. */
    public final List<String> plans = new ArrayList<>();
    /** The north-west corner of the family's field (4 x 3), at the height of the ground nearby. */
    public int[] field;
    public boolean founded;
    /** Whether the family's hens have been brought (they live in the herd, see Life). */
    public boolean hens;
    public double clock;
    private final SplittableRandom rnd = new SplittableRandom(7);
    private final java.util.Map<String, Blueprint> known = Blueprint.builtIn();
    /**
     * The river (the water slice): whether the fisher's water is fouled, by whom (a name, the player's
     * actor name included), whether the fisher owes a fish trap to thank for the river's return, and
     * whether the herbalist already handed out reeds and a willow cutting.
     */
    public boolean riverFouled, owesTrap, taughtReeds;
    /** The fisher's river held back upstream by closed sluices (H3: the dispute over the water). */
    public boolean riverHeld;
    public String fouler;
    private double riverCheck;

    /** Blocks changed by the inhabitants this tick (for redrawing). */
    public final java.util.ArrayDeque<int[]> edits = new java.util.ArrayDeque<>();

    /** The plan of building i. */
    public Blueprint plan(int i) {
        String n = i < plans.size() ? plans.get(i) : Blueprint.HUT;
        return known.getOrDefault(n, known.get(Blueprint.HUT));
    }

    /**
     * The hamlet's layout around its green (the well): plan, offset of its north-west corner from the
     * green, and whether it stands when the player comes (else the builder raises it).
     */
    private static final Object[][] LAYOUT = {
        {Blueprint.COTTAGE, -14, -11, true}, {Blueprint.HOUSE, 3, -14, false}, {Blueprint.WELL, -2, -2, true},
        {Blueprint.COOP, -14, 3, true}, {Blueprint.APIARY, -22, -3, true}, {Blueprint.BAKEHOUSE, 5, 5, false}};

    /**
     * Founds the hamlet near (cx, cz): sites are chosen and their ground shaped, the hermit's cottage,
     * the well, the hen-house and the apiary stand already, the family's house and the bake-house are
     * marked out. {@code ground(x, z)} gives the height of the ground there.
     */
    public void found(World w, int cx, int cz, java.util.function.IntBinaryOperator ground) {
        editing = w;
        List<int[]> taken = new ArrayList<>();
        int[] logs = {0};
        int[][] anchors = new int[LAYOUT.length][];
        // The green first, round the well, on dry and level ground; the houses then round it.
        int ox = cx + 18, oz = cz + 10;
        for (int k = 0; k < LAYOUT.length; k++) {
            int i = k == 0 ? 2 : k <= 2 ? k - 1 : k; // the well, then the others in their order
            Blueprint p = known.get((String) LAYOUT[i][0]);
            int x = ox + (int) LAYOUT[i][1], z = oz + (int) LAYOUT[i][2];
            int[] a = null;
            for (int r = 4; a == null && r <= (k == 0 ? 24 : 16); r += 4) a = Site.choose(w, p, x, z, r, 3, taken, ground); // further if crowded or wet
            if (a == null) a = Site.anchor(p, x, Site.level(w, p, x, z, ground), z);
            taken.add(Site.box(p, a));
            anchors[i] = a;
            if (k == 0) { ox = a[0] - (int) LAYOUT[i][1]; oz = a[2] - (int) LAYOUT[i][2]; }
        }
        for (int i = 0; i < LAYOUT.length; i++) {
            huts.add(anchors[i]);
            plans.add((String) LAYOUT[i][0]);
        }
        for (int i = 0; i < huts.size(); i++) Site.prepare(w, plan(i), huts.get(i), this::set, logs);
        for (int i = 0; i < huts.size(); i++) if ((boolean) LAYOUT[i][3]) build(w, plan(i), huts.get(i));
        store.add(Blocks.OAK_LOG, logs[0]); // the trees felled for the sites
        int[] green = plan(2).mark("porte", huts.get(2));
        for (int i = 0; i < huts.size(); i++) {
            if (i == 2) continue;
            int[] door = plan(i).mark("porte", huts.get(i));
            Site.path(w, door[0], door[2], green[0], green[2], door[1] - 1, this::set);
        }
        field = plan(1).mark("champ", huts.get(1));
        int[] a = huts.get(0), yard = plan(1).mark("porte", huts.get(1)), cottageDoor = plan(0).mark("porte", a);
        Villager hermit = person("Anselme", Job.HERBALIST, 0x5a6b3a, 0x4a3a28, false);
        hermit.skills.xp[Skill.FORAGING.ordinal()] = 400; // a master gatherer: he can teach
        hermit.skills.xp[Skill.COOKING.ordinal()] = 200;
        int[] bed = plan(0).mark("lit1", a), hearth = plan(0).mark("foyer", a);
        hermit.setHome(bed[0] + 0.5, bed[1], bed[2] + 0.5);
        Villager builder = person("Berthe", Job.BUILDER, 0x9a4030, 0x3a3a50, false);
        builder.skills.xp[Skill.BUILDING.ordinal()] = 320;
        builder.plans.add(Blueprint.HOUSE);
        builder.plans.add(Blueprint.BAKEHOUSE);
        Villager fisher = person("Corentin", Job.FISHER, 0x3a5a90, 0x3a3a3a, false);
        fisher.skills.xp[Skill.FISHING.ordinal()] = 250;
        Villager child = person("Daphné", Job.GATHERER, 0xd0a040, 0x6a4a30, true);
        Villager hunter = person("Edmond", Job.HUNTER, 0x4a5a30, 0x50402a, false);
        hunter.skills.xp[Skill.HUNTING.ordinal()] = 280;
        hunter.setHome(hearth[0] + 0.5, hearth[1], hearth[2] + 0.5); // lodges by the hermit's fire
        place(hunter, cottageDoor[0] + 1.5, cottageDoor[1], cottageDoor[2] + 0.5);
        for (Villager v : List.of(builder, fisher, child)) v.setHome(yard[0] + 0.5, yard[1], yard[2] + 0.5);
        place(hermit, cottageDoor[0] + 0.5, cottageDoor[1], cottageDoor[2] + 0.5);
        for (int i = 0; i < 3; i++) place(List.of(builder, fisher, child).get(i), yard[0] - 0.5 + i, yard[1], yard[2] + 0.5);
        store.add(Items.STICK, 16);
        store.add(Items.SWEET_BERRIES, 8);
        founded = true;
    }

    /** Where the family's hens are brought: the hen-house's run, or by the family's hut in an older hamlet. */
    public int[] henRun() {
        for (int i = 0; i < huts.size(); i++) {
            int[] m = plan(i).mark("poules", huts.get(i));
            if (m != null) return m;
        }
        int[] h = huts.get(Math.min(1, huts.size() - 1));
        return new int[] {h[0] + 2, h[1] + 1, h[2] - 2};
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

    /** Raises a plan at once (the hermit's hut was there before the player came). */
    private void build(World w, Blueprint p, int[] a) {
        for (int i = 0; i < p.w * p.h * p.d; i++) {
            int[] s = p.next(w, a[0], a[1], a[2]);
            if (s == null) break;
            set(w, s[0], s[1], s[2], (short) s[3]);
        }
    }

    private World editing;

    private void set(World w, int x, int y, int z, short b) {
        if (w.set(x, y, z, b)) edits.add(new int[] {x, y, z});
    }

    /** For the site's earthworks (the world being founded). */
    private void set(int x, int y, int z, short b) {
        set(editing, x, y, z, b);
    }

    /** Raises every building at once, the family moved in (scripted shots of the finished hamlet). */
    public void raiseAll(World w) {
        for (int i = 0; i < huts.size(); i++) build(w, plan(i), huts.get(i));
        Villager builder = villagers.stream().filter(v -> v.job == Job.BUILDER).findFirst().orElse(null);
        if (builder != null) finished(builder, 1);
    }

    /** The first building not finished, from the family's house on (the cottage is the hermit's own), or -1. */
    public int project(World w) {
        for (int i = 1; i < huts.size(); i++) {
            int[] a = huts.get(i);
            if (plan(i).next(w, a[0], a[1], a[2]) != null) return i;
        }
        return -1;
    }

    /** Whether (x, y, z) belongs to a building of the hamlet (its box, and a block round it). */
    boolean built(int x, int y, int z) {
        for (int i = 0; i < huts.size(); i++) {
            int[] a = huts.get(i);
            Blueprint p = plan(i);
            if (x >= a[0] - 1 && x <= a[0] + p.w && z >= a[2] - 1 && z <= a[2] + p.d && y >= a[1] - 2 && y <= a[1] + p.h) return true;
        }
        return false;
    }

    /** One step of the hamlet's life: {@code daylight} 0..1, {@code day} the calendar. */
    public void tick(double dt, World w, Flows flows, Ecology ecology, RecipeBook recipes, double daylight, boolean raining,
                     double day) {
        clock += dt;
        lastWorld = w;
        if ((riverCheck -= dt) <= 0) { riverCheck = 5; watchRiver(flows, ecology); }
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

    /** The fisher's water: fouled above 0.3 (by the last who poured there), clean again under the spawning threshold. */
    void watchRiver(Flows flows, Ecology ecology) {
        Villager f = villager(Job.FISHER);
        if (f == null || ecology == null) return;
        double x = f.workAt != null ? f.workAt[0] : f.x, z = f.workAt != null ? f.workAt[2] : f.z;
        Ecology.Patch p = ecology.existing(x, z);
        if (p == null) return;
        if (p.held > 0.3 && !riverHeld) { riverHeld = true; say(f, "L'eau baisse... quelqu'un la retient en amont !"); }
        else if (p.held < 0.1 && riverHeld) { riverHeld = false; say(f, "L'eau redescend : merci à qui a rouvert la vanne."); }
        if (p.pollution > 0.3) {
            riverFouled = true;
            Flows.Source src = flows == null ? null : flows.pollutedBy(x, z);
            if (src != null) fouler = src.who();
        } else if (riverFouled && p.pollution < Ecology.SPAWN_RESUME && !p.spawnStopped) {
            riverFouled = false;
            if (p.cleaners >= 3) owesTrap = true; // somebody planted the banks: the fisher is grateful
            say(f, "L'eau s'éclaircit... les truites reviennent frayer !");
        }
    }

    /** The first inhabitant with this trade, or null. */
    public Villager villager(Job job) {
        for (Villager v : villagers) if (v.job == job) return v;
        return null;
    }

    /** How the inhabitants name the one who fouled the river, speaking to {@code player}. */
    private String foulerName(Actor player) {
        return fouler == null ? "quelqu'un en amont" : fouler.equals(player.name) ? "toi" : fouler;
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
                int site = project(w);
                if (site < 0) { decorate(v, w); cook(v, recipes, flows); return; }
                int[] a = huts.get(site);
                Blueprint plan = plan(site);
                int[] step = plan.next(w, a[0], a[1], a[2]);
                if (v.workAt != null) { fetch(v, w, flows); return; } // on the way to a tree, a rock, a tuft of grass
                int[] yard = plan.mark("porte", a);
                if (yard == null) yard = new int[] {a[0] + plan.w / 2, a[1] + plan.sunk, a[2] + plan.d};
                if (dist(v, step[0] + 0.5, step[2] + 0.5) > 7 && dist(v, yard[0] + 0.5, yard[2] + 0.5) > 1.5) {
                    go(v, w, yard[0], yard[1], yard[2]); // works from the yard
                    return;
                }
                v.state = Villager.State.WORK;
                if (v.workTimer < 2.5 / v.efficiency(Skill.BUILDING)) return;
                v.workTimer = 0;
                short need = step[3] == Blocks.AIR ? Blocks.AIR : Blueprint.item((short) step[3]);
                if (need != Blocks.AIR && store.count(need) == 0) { gatherMaterial(v, w, need, flows); return; }
                if (need != Blocks.AIR) store.remove(need, 1);
                set(w, step[0], step[1], step[2], (short) step[3]);
                v.skills.practice(Skill.BUILDING, 1);
                if (plan.next(w, a[0], a[1], a[2]) == null) finished(v, site);
            }
            case FISHER -> {
                if (v.workAt == null) { v.workAt = bank(w, (int) v.x, (int) v.z); if (v.workAt == null) { wander(v, w); return; } }
                if (dist(v, v.workAt[0] + 0.5, v.workAt[2] + 0.5) > 2) { go(v, w, v.workAt[0], v.workAt[1], v.workAt[2]); return; }
                v.state = Villager.State.WORK;
                if (v.workTimer < 40 / v.efficiency(Skill.FISHING)) return;
                v.workTimer = 0;
                Ecology.Patch p = ecology.existing(v.x, v.z);
                if (p != null && p.pollution > 0.3) { // fouled water: the trout hide or die, and the fisher grieves
                    say(v, "L'eau est trouble et sent la tannerie... rien ne mord.");
                    v.needs.rest = Math.max(0, v.needs.rest - 0.05);
                } else if (p != null && p.pop[Species.TROUT.ordinal()] >= 1) { // only what the river holds
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
                if (v.job == Job.GATHERER && v.workAt == null && tendField(v, w, flows)) return;
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

    /**
     * The family's field, 4 x 3 south of their hut: tilled from the grass, sown with the store's seeds,
     * harvested ripe (and sown again). Returns whether the gatherer worked it this time.
     */
    private boolean tendField(Villager v, World w, Flows flows) {
        if (huts.size() < 2) return false;
        if (field == null) { int[] h = huts.get(1); field = new int[] {h[0], h[1], h[2] + 9}; } // an older hamlet's
        int[] h = field;
        for (int dz = 0; dz < 3; dz++)
            for (int dx = 0; dx < 4; dx++) {
                int x = h[0] + dx, z = h[2] + dz, y = h[1] + 4;
                while (y > h[1] - 5 && !Blocks.isSolid(w.get(x, y, z)) && !Blocks.isPlant(w.get(x, y, z))) y--;
                short b = w.get(x, y, z), above = w.get(x, y + 1, z);
                boolean ripe = b == Blocks.WHEAT_7, sow = b == Blocks.FARMLAND && above == Blocks.AIR && store.count(Items.WHEAT_SEEDS) > 0;
                boolean till = (b == Blocks.GRASS || b == Blocks.DIRT) && (above == Blocks.AIR || above == Blocks.SHORT_GRASS);
                if (!ripe && !sow && !till) continue;
                if (dist(v, x + 0.5, z + 0.5) > 2.5) { go(v, w, x, y + (ripe ? 0 : 1), z); return true; }
                v.state = Villager.State.WORK;
                if (v.workTimer < 3 / v.efficiency(Skill.FARMING)) return true;
                v.workTimer = 0;
                if (ripe) {
                    set(w, x, y, z, Blocks.WHEAT_0); // harvested and sown again from the same ear
                    store.add(Items.WHEAT, 1);
                    store.add(Items.WHEAT_SEEDS, 1);
                    flows.take(Matter.PLANTS, 1, x, z);
                    say(v, "Le blé est mûr !");
                } else if (sow) {
                    store.remove(Items.WHEAT_SEEDS, 1);
                    set(w, x, y + 1, z, Blocks.WHEAT_0);
                } else {
                    if (above == Blocks.SHORT_GRASS) { set(w, x, y + 1, z, Blocks.AIR); store.add(Items.WHEAT_SEEDS, 1); } // wild seeds
                    set(w, x, y, z, Blocks.FARMLAND);
                }
                v.skills.practice(Skill.FARMING, 1);
                return true;
            }
        return false;
    }

    /** A building is finished: the family moves into its house, one bed each; the others are named. */
    private void finished(Villager v, int site) {
        Blueprint p = plan(site);
        List<int[]> beds = p.marks("lit", huts.get(site));
        if (site == 1 && !beds.isEmpty()) {
            double yardX = v.homeX, yardZ = v.homeZ;
            int n = 0;
            for (Villager o : villagers)
                if (o.homeX == yardX && o.homeZ == yardZ) { int[] b = beds.get(n++ % beds.size()); o.setHome(b[0] + 0.5, b[1], b[2] + 0.5); }
            say(v, "Notre maison est finie ! Chacun son lit.");
        } else say(v, Character.toUpperCase(Blueprint.title(p.name).charAt(0)) + Blueprint.title(p.name).substring(1) + " est bâti.");
    }

    /** A finished home gets a decorated pot by its bed: the hamlet's first work of art. */
    private void decorate(Villager v, World w) {
        int[] at = huts.size() > 1 ? plan(1).mark("pot", huts.get(1)) : null;
        if (at == null || decorated) return;
        int x = at[0], y = at[1], z = at[2];
        if (w.get(x, y, z) != Blocks.AIR) return;
        set(w, x, y, z, Blocks.DECORATED_POT);
        decorated = true;
        v.skills.practice(Skill.POTTERY, 2);
        say(v, "Un pot décoré, pour que la maison soit belle.");
    }

    /** Whether the builder has decorated the finished home (saved). */
    public boolean decorated;

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
        for (short[] m : MADE_FROM) // the builder's own work at the bench: planks, doors, stairs, furniture
            if (m[0] == item) {
                if (store.count(m[1]) >= m[2]) { store.remove(m[1], m[2]); store.add(item, m[3]); return; }
                item = m[1]; // first, what it is made from
                if (item == Blocks.OAK_PLANKS && store.count(Blocks.OAK_LOG) > 0) { store.remove(Blocks.OAK_LOG, 1); store.add(Blocks.OAK_PLANKS, 4); return; }
                if (item == Blocks.THATCH && store.count(Items.PLANT_FIBER) >= 4) { store.remove(Items.PLANT_FIBER, 4); store.add(Blocks.THATCH, 1); return; }
                break;
            }
        if (item == Blocks.OAK_PLANKS && store.count(Blocks.OAK_LOG) > 0) { store.remove(Blocks.OAK_LOG, 1); store.add(Blocks.OAK_PLANKS, 4); return; }
        // Natural sources only: a rock, a tree, grass (never the hamlet's own floors and posts).
        java.util.function.Predicate<Short> source = item == Blocks.COBBLESTONE ? b -> b == Blocks.STONE
            : item == Blocks.THATCH || item == Blocks.STRAW_BED ? b -> b == Blocks.SHORT_GRASS
            : item == Blocks.OAK_LOG || item == Blocks.OAK_PLANKS ? b -> b == Blocks.OAK_LOG : null;
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

    /**
     * What the builder makes from what (item, from, how many, gives), as the recipes have it; what has no
     * recipe here (glass, iron, wool) is drawn from the patch's account.
     */
    private static final short[][] MADE_FROM = {
        {Blocks.OAK_DOOR, Blocks.OAK_PLANKS, 6, 3}, {Blocks.OAK_STAIRS, Blocks.OAK_PLANKS, 6, 4}, {Blocks.OAK_SLAB, Blocks.OAK_PLANKS, 3, 6},
        {Blocks.OAK_FENCE, Blocks.OAK_PLANKS, 4, 3}, {Blocks.OAK_WINDOW, Blocks.OAK_PLANKS, 4, 2}, {Blocks.OAK_TABLE, Blocks.OAK_PLANKS, 5, 1},
        {Blocks.OAK_CHAIR, Blocks.OAK_PLANKS, 4, 2}, {Blocks.BARREL, Blocks.OAK_PLANKS, 7, 1}, {Blocks.CRAFTING_TABLE, Blocks.OAK_PLANKS, 4, 1},
        {Blocks.COBBLESTONE_STAIRS, Blocks.COBBLESTONE, 6, 4}, {Blocks.COBBLESTONE_WALL, Blocks.COBBLESTONE, 6, 6},
        {Blocks.FURNACE, Blocks.COBBLESTONE, 8, 1}, {Blocks.THATCH_ROOF, Blocks.THATCH, 3, 4}, {Blocks.NEST, Blocks.THATCH, 2, 1}};

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
    private int[] find(World w, int x, int y, int z, java.util.function.Predicate<Short> what) {
        for (int r = 3; r <= 24; r++)
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = -6; dy <= 8; dy++) {
                        short b = w.get(x + dx, y + dy, z + dz);
                        if (!what.test(b) || built(x + dx, y + dy, z + dz)) continue;
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
        for (int dy = 0; dy <= 1; dy++) // opens a door on the way (both halves), and closes it again behind
            if (Blocks.isClosedDoor(w.get(c[0], c[1] + dy, c[2]))) {
                for (int[] e : Doors.setOpen(w, c[0], c[1] + dy, c[2], true)) edits.add(e);
                v.doorOpened = new int[] {c[0], c[1] + dy, c[2]};
            }
        if (v.doorOpened != null && Math.abs(v.x - (v.doorOpened[0] + 0.5)) + Math.abs(v.z - (v.doorOpened[2] + 0.5)) > 1.8) {
            int[] o = v.doorOpened;
            boolean someone = false;
            for (Villager u : villagers) someone |= u != v && Math.abs(u.x - (o[0] + 0.5)) + Math.abs(u.z - (o[2] + 0.5)) < 1.5;
            if (!someone) for (int[] e : Doors.setOpen(w, o[0], o[1], o[2], false)) edits.add(e);
            v.doorOpened = null;
        }
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
        } else if (v.job == Job.FISHER && owesTrap) {
            owesTrap = false;
            player.inventory.add(Blocks.FISH_TRAP, 1);
            s = "La rivière revit, les roseaux y sont pour beaucoup. Tiens, une nasse : tu l'as bien méritée.";
        } else if (v.job == Job.FISHER && riverFouled)
            s = ("toi".equals(foulerName(player)) ? "C'est toi qui verses tes eaux de tannage dans ma rivière !"
                : "La rivière est trouble depuis que " + foulerName(player) + " y verse ses eaux sales.")
                + " Plus une truite ne fraie.";
        else if (riverHeld && (v.job == Job.FISHER || v.job == Job.BUILDER))
            s = v.job == Job.FISHER ? "La rivière est basse : une vanne fermée en amont la retient. Les truites manquent d'eau pour frayer."
                : "Plus assez d'eau pour gâcher le mortier ni faire tourner la roue. Rouvre la vanne, veux-tu ?";
        else if (v.job == Job.HERBALIST && riverFouled && !taughtReeds) {
            taughtReeds = true;
            player.inventory.add(Blocks.REEDS, 4);
            player.inventory.add(Blocks.WILLOW_SAPLING, 1);
            s = "Plante ces roseaux au bord de l'eau, en aval de la souillure : leurs racines filtrent. Et ce saule, au pied mouillé : il boit la boue, et son écorce soigne la fièvre.";
        } else if (v.job == Job.HERBALIST && riverFouled)
            s = "Des roseaux, encore des roseaux le long de la berge. Dix touffes, et l'eau en aval est deux fois plus claire.";
        else if (riverFouled && v.job != Job.FISHER && rnd.nextDouble() < 0.5) {
            Villager f = villager(Job.FISHER);
            s = (f == null ? "On dit" : f.name + " dit") + " que la rivière est sale à cause de " + foulerName(player) + ".";
        } else if (v.needs.food < 8) s = "J'ai faim. Le garde-manger est presque vide.";
        else if (v.job == Job.BUILDER && lastWorld != null && project(lastWorld) >= 0)
            s = "Je bâtis " + Blueprint.title(plan(project(lastWorld)).name) + ". Il me faut du bois, de la pierre et du chaume.";
        else if (v.job == Job.FISHER) s = "Ne pêche pas les truites à l'automne : c'est leur fraie.";
        else if (v.job == Job.HERBALIST) s = "Les baies repoussent si l'on en laisse. Prends-en, mais pas tout.";
        else s = "Bonjour ! Je suis " + v.name + ", " + (v.skills.roleTitle().isEmpty() ? v.job.displayName : v.skills.roleTitle()) + ".";
        say(v, s);
        return v.name + " : " + s;
    }

    /**
     * Exchanges: holding what an inhabitant's trade needs and talking to them gives one of it to the
     * hamlet for one of what they make, taken from the common store (null: no exchange).
     */
    public String trade(Villager v, Actor player, int slot, java.util.function.IntFunction<String> names) {
        short held = player.inventory.id(slot);
        if (held == Blocks.AIR || player.inventory.counts[slot] <= 0) return null;
        short[] wants = switch (v.job) {
            case BUILDER -> new short[] {Blocks.OAK_LOG, Blocks.OAK_PLANKS, Blocks.COBBLESTONE, Blocks.THATCH, Blocks.GLASS};
            case FISHER -> new short[] {Items.STRING, Items.STICK, Items.BREAD};
            case HUNTER -> new short[] {Items.ARROW, Items.FLINT, Items.BREAD};
            default -> new short[] {Items.BREAD, Items.COOKED_TROUT, Items.COOKED_VENISON, Blocks.CLAY, Items.WHEAT};
        };
        short[] gives = switch (v.job) {
            case BUILDER -> new short[] {Blocks.STRAW_BED, Blocks.THATCH, Blocks.OAK_PLANKS};
            case FISHER -> new short[] {Items.COOKED_TROUT, Items.TROUT};
            case HUNTER -> new short[] {Items.HIDE, Items.VENISON, Items.SMOKED_MEAT};
            default -> new short[] {Items.EGG, Items.SWEET_BERRIES, Items.WILD_HERBS, Items.PLANT_FIBER}; // eggs of the family's hens
        };
        boolean wanted = false;
        for (short w : wants) wanted |= w == held;
        if (!wanted) return null;
        if (v.job == Job.FISHER && riverFouled && player.name.equals(fouler)) {
            String s = "Je ne commerce pas avec qui empoisonne ma rivière.";
            say(v, s);
            return v.name + " : " + s;
        }
        for (short g : gives) {
            if (store.count(g) == 0) continue;
            store.remove(g, 1);
            player.inventory.remove(held, 1);
            store.add(held, 1);
            player.inventory.add(g, 1);
            String s = "Marché conclu : " + names.apply(held).toLowerCase() + " contre " + names.apply(g).toLowerCase() + ".";
            say(v, s);
            return v.name + " : " + s;
        }
        String s = "Volontiers, mais je n'ai rien à te donner en échange pour l'instant.";
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
        o.addProperty("decorated", decorated);
        o.addProperty("hens", hens);
        o.addProperty("riverFouled", riverFouled);
        o.addProperty("owesTrap", owesTrap);
        o.addProperty("taughtReeds", taughtReeds);
        o.addProperty("riverHeld", riverHeld);
        if (fouler != null) o.addProperty("fouler", fouler);
        JsonArray hs = new JsonArray();
        for (int[] h : huts) { JsonArray a = new JsonArray(); for (int v : h) a.add(v); hs.add(a); }
        o.add("huts", hs);
        JsonArray ps = new JsonArray();
        for (String n : plans) ps.add(n);
        o.add("plans", ps);
        if (field != null) { JsonArray f = new JsonArray(); for (int v : field) f.add(v); o.add("field", f); }
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
        decorated = o.has("decorated") && o.get("decorated").getAsBoolean();
        hens = o.has("hens") && o.get("hens").getAsBoolean();
        riverFouled = o.has("riverFouled") && o.get("riverFouled").getAsBoolean();
        owesTrap = o.has("owesTrap") && o.get("owesTrap").getAsBoolean();
        taughtReeds = o.has("taughtReeds") && o.get("taughtReeds").getAsBoolean();
        riverHeld = o.has("riverHeld") && o.get("riverHeld").getAsBoolean();
        fouler = o.has("fouler") ? o.get("fouler").getAsString() : null;
        for (var h : o.getAsJsonArray("huts")) {
            JsonArray a = h.getAsJsonArray();
            huts.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
        }
        if (o.has("plans")) for (var n : o.getAsJsonArray("plans")) plans.add(n.getAsString());
        while (plans.size() < huts.size()) plans.add(Blueprint.HUT); // a hamlet of the first huts
        if (o.has("field")) { JsonArray f = o.getAsJsonArray("field"); field = new int[] {f.get(0).getAsInt(), f.get(1).getAsInt(), f.get(2).getAsInt()}; }
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
