package net.mynecraft.client;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Drops;
import net.mynecraft.world.Growth;
import net.mynecraft.world.Inventory;
import net.mynecraft.world.Items;
import net.mynecraft.world.Player;
import net.mynecraft.world.Power;
import net.mynecraft.world.Survival;
import net.mynecraft.world.World;
import net.mynecraft.world.actor.Actor;
import net.mynecraft.world.actor.ActorCodec;
import net.mynecraft.world.actor.Flows;
import net.mynecraft.world.actor.Gestures;
import net.mynecraft.world.actor.Harmony;
import net.mynecraft.world.actor.Matter;
import net.mynecraft.world.actor.Skill;
import net.mynecraft.world.actor.Wellbeing;
import net.mynecraft.world.build.Blueprint;
import net.mynecraft.world.crafting.RecipeBook;
import net.mynecraft.world.crafting.Workshop;
import net.mynecraft.world.life.Creature;
import net.mynecraft.world.life.Ecology;
import net.mynecraft.world.life.Life;
import net.mynecraft.world.life.Species;
import net.mynecraft.world.people.Hamlet;
import net.mynecraft.world.people.Villager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The player's life on the land (M27..M34), between the game loop and the world-side systems: the
 * player as an actor (skills, needs, plans, hearth), the ledger of flows, what grows, the hamlet,
 * the place's wellbeing and the harmony compass, fishing, hunting with the bow, work at a station,
 * plans and the creative building tools. The game loop calls in; this class keeps the state and
 * saves it with the world.
 */
final class Livelihood {
    final Actor actor;
    final Flows flows;
    final Growth growth = new Growth();
    final Hamlet hamlet = new Hamlet();
    final Map<String, Blueprint> plans;
    private final Path worldDir;
    private final SplittableRandom rnd = new SplittableRandom();

    Wellbeing place = Wellbeing.NONE;
    Harmony.Nature nature = new Harmony.Nature(0, 0, 0, 0, 0, 0.5);
    Harmony.Self self = new Harmony.Self(0, 0, 0, 0, 0.5);
    private double measureTimer, growTimer = 4, lastGrowDay = Double.NaN;
    /** 0..1, recent time spent near or talking with others. */
    double company;
    String toast = "";
    double toastLeft;
    double effort;

    // fishing: seconds left before something bites, where the line is
    double fishLeft = -1;
    private double[] fishFrom;
    // a station at work
    RecipeBook.Process making;
    double makingLeft, makingTotal, power;
    String station;
    int[] stationAt;
    // plans and building
    int[] markA, markB;
    Blueprint activePlan;
    int[] planAnchor;
    private int planIndex = -1;
    private final ArrayDeque<int[]> undo = new ArrayDeque<>(); // x, y, z, previous state

    private final Life life;

    Livelihood(Path worldDir, Inventory inventory, Life life) {
        this.worldDir = worldDir;
        this.life = life;
        actor = new Actor("Moi", inventory);
        flows = new Flows(life.ecology);
        plans = Blueprint.loadAll(worldDir.resolve("blueprints"));
        try {
            ActorCodec.load(actor, worldDir.resolve("actor.json"));
            flows.load(worldDir.resolve("flows.bin"));
            growth.load(worldDir.resolve("growth.txt"));
            hamlet.load(worldDir.resolve("hamlet.json"));
            projects.load(worldDir.resolve("projects.json"));
            flood.load(worldDir.resolve("flood.txt"));
            life.loadHerd(worldDir.resolve("herd.json"));
        } catch (IOException | RuntimeException e) {
            System.err.println("[life] cannot read the player's life: " + e.getMessage());
        }
        actor.plans.add("cabane"); // everybody knows how to raise a hut
    }

    void save() {
        try {
            ActorCodec.save(actor, worldDir.resolve("actor.json"));
            flows.save(worldDir.resolve("flows.bin"));
            growth.save(worldDir.resolve("growth.txt"));
            hamlet.save(worldDir.resolve("hamlet.json"));
            projects.save(worldDir.resolve("projects.json"));
            flood.save(worldDir.resolve("flood.txt"));
            life.saveHerd(worldDir.resolve("herd.json"));
        } catch (IOException e) {
            System.err.println("[life] cannot save the player's life: " + e.getMessage());
        }
    }

    void say(String s) {
        toast = s;
        toastLeft = 4;
    }

    // ---------------------------------------------------------------------------- breaking

    double breakSeconds(short block, int slot) {
        return Gestures.breakSeconds(actor, block, actor.inventory.id(slot));
    }

    void broke(short block, int slot, int x, int y, int z) {
        short tool = actor.inventory.id(slot);
        List<Drops.Drop> drops = Gestures.broke(actor, block, slot, x, y, z, flows, rnd);
        if (tool != Blocks.AIR && Items.DURABILITY[tool] > 0 && actor.inventory.id(slot) == Blocks.AIR) say("Votre outil s'est brisé.");
        else if (drops.isEmpty() && !Items.canHarvest(tool, block)) say("Il faudrait un meilleur outil.");
    }

    // ---------------------------------------------------------------------------- the right hand

    /** Water the eyes meet within {@code max} blocks before {@code stop} (the block aimed at), or null. */
    static int[] water(World w, double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        for (double t = 0.5; t < max; t += 0.25) {
            int x = (int) Math.floor(ox + dx * t), y = (int) Math.floor(oy + dy * t), z = (int) Math.floor(oz + dz * t);
            short b = w.get(x, y, z);
            if (Blocks.FLUID[b & 0xFFFF] == Blocks.FLUID_WATER) return new int[] {x, y, z};
            if (Blocks.isSolid(b)) return null;
        }
        return null;
    }

    /**
     * Uses the held item (right click) on what is aimed at; returns what happened for sounds. Fishing
     * and the bow are handled here, the rest by world gestures.
     */
    Gestures.Use use(World w, int slot, int[] block, int[] waterAt, boolean night, Life life, double[] eye, double[] dir,
                     SoundEngine sound) {
        short held = actor.inventory.id(slot);
        byte kind = Items.TOOL[held & 0xFFFF];
        if (kind == Items.TOOL_ROD && waterAt != null) {
            if (fishLeft >= 0) { fishLeft = -1; say("Vous relevez la ligne."); return Gestures.Use.NONE; }
            Ecology.Patch p = life.ecology.at(actor.x, actor.z);
            double fish = Math.min(1, p.pop[Species.TROUT.ordinal()] / 6 + p.aquatic * 0.3);
            fishLeft = (4 + rnd.nextDouble() * 12) / actor.efficiency(Skill.FISHING) / (0.3 + fish);
            fishFrom = new double[] {actor.x, actor.z, slot};
            say("Vous lancez la ligne...");
            sound.splash(waterAt[0] + 0.5, waterAt[1] + 0.9, waterAt[2] + 0.5);
            return Gestures.Use.NONE;
        }
        if (kind == Items.TOOL_BOW && eye != null) {
            if (actor.inventory.count(Items.ARROW) == 0) { say("Plus de flèches."); return Gestures.Use.NONE; }
            actor.inventory.remove(Items.ARROW, 1);
            double spread = 0.06 / actor.efficiency(Skill.HUNTING);
            double ax = dir[0] + rnd.nextGaussian() * spread, ay = dir[1] + rnd.nextGaussian() * spread, az = dir[2] + rnd.nextGaussian() * spread;
            Creature c = life.raycast(eye[0], eye[1], eye[2], ax, ay, az, 40);
            actor.inventory.wearOut(slot);
            sound.click();
            if (c != null) {
                life.strike(c, 6, eye[0], eye[2]);
                struck(c, life);
                actor.skills.practice(Skill.HUNTING, 2);
            } else actor.skills.practice(Skill.HUNTING, 0.3);
            return Gestures.Use.NONE;
        }
        if (kind == Items.TOOL_FLUTE || kind == Items.TOOL_DRUM) {
            if (kind == Items.TOOL_FLUTE) sound.flute(actor.x, actor.y + 1.5, actor.z);
            else sound.drum(actor.x, actor.y + 1.5, actor.z);
            actor.skills.practice(Skill.MUSIC, 1);
            company = Math.min(1, company + 0.05); // music is shared
            return Gestures.Use.NONE;
        }
        Gestures.Use u = block == null
            ? Gestures.use(actor, w, slot, Integer.MIN_VALUE, 0, 0, waterAt != null, night, flows, growth, rnd, life.eco.season().phase())
            : Gestures.use(actor, w, slot, block[0], block[1], block[2], waterAt != null, night, flows, growth, rnd, life.eco.season().phase());
        switch (u) {
            case ATE -> say(String.format("Miam. (faim %.0f/20, variété des repas %.0f %%)", actor.needs.food, 100 * actor.needs.variety()));
            case DRANK -> say("Vous buvez.");
            case REMEDY -> { heal += 4; say("L'écorce de saule est amère, mais la douleur s'apaise."); }
            case SLEPT -> say("Vous dormez jusqu'au matin.");
            case RESTED -> say("Ce n'est pas l'heure de dormir : vous vous allongez un moment.");
            case SAT -> say("Vous vous asseyez. (avancer pour vous lever)");
            case HIVE_LOST -> say("Du miel pris en hiver : c'était la réserve des abeilles, la colonie meurt.");
            default -> { }
        }
        return u;
    }

    final net.mynecraft.world.life.Riverbank riverbank = new net.mynecraft.world.life.Riverbank();
    private double bankTimer;

    /** The flood of the rivers round the player (E7). */
    final net.mynecraft.world.Flood flood = new net.mynecraft.world.Flood();
    private double lastFloodDay = Double.NaN, floodTimer;

    /** Rain, the flood's rise and fall, its marks; Corentin's warning. */
    private void followFlood(double day, double precipitation, World w, Player p, List<int[]> changed) {
        if (w.generator == null) return;
        if (!Double.isNaN(lastFloodDay) && day > lastFloodDay && flood.update(day - lastFloodDay, precipitation)) {
            say("La rivière grossit... l'eau monte sur les berges basses.");
            var fisher = hamlet.villager(net.mynecraft.world.actor.Job.FISHER);
            if (fisher != null) hamlet.warnFlood(fisher);
        }
        lastFloodDay = day;
        if ((floodTimer -= 1) > 0 || !flood.active() && flood.flooded() == 0) return;
        floodTimer = 60; // about every second
        var hydrology = w.generator.landscape().hydrology();
        boolean was = flood.active();
        changed.addAll(flood.apply(w, (int) Math.floor(p.x), (int) Math.floor(p.z), 40, (x, z) -> hydrology.water(x, z) != null));
        if (was && !flood.active()) say("La crue est passée : du bois flotté, du limon sur les berges.");
    }

    /** The player's projects (aspirations), shown on the notebook's last page. */
    final net.mynecraft.world.actor.Projects projects = new net.mynecraft.world.actor.Projects();
    private double lastProjectDay = Double.NaN;
    /** Set when the river's project was just accomplished: the heron comes (the caller plays the scene). */
    boolean heronComing;

    /** Accepts the first proposed project (Y, on the notebook's projects page). */
    void acceptProject() {
        var p = projects.acceptNext();
        say(p == null ? "Aucun projet en attente." : "Projet accepté : " + p.title + ".");
    }

    /** Half-hearts a remedy will give back at the next tick. */
    int heal;

    /** After the player struck an animal: if it died, the hunter's share (meat, hide) and the ledger. */
    void struck(Creature c, Life life) {
        if (c.dying <= 0 || c.health > 0) return;
        if (c.tame) { // one of the flock: the meat is the keeper's, nothing is taken from the wild
            switch (c.species) {
                case SHEEP -> { actor.inventory.add(Items.MUTTON, c.adult() ? 2 : 1); if (!c.shorn) actor.inventory.add(Items.FLEECE, 1); }
                case GOAT -> { actor.inventory.add(Items.MUTTON, c.adult() ? 2 : 1); actor.inventory.add(Items.HIDE, 1); }
                case HEN -> actor.inventory.add(Items.CHICKEN, 1);
                default -> { }
            }
            actor.skills.practice(Skill.HUSBANDRY, 1);
            if ("hameau".equals(c.owner)) {
                company = Math.max(0, company - 0.3);
                say("C'était une bête du hameau : ses gens ne l'oublieront pas.");
            }
            return;
        }
        switch (c.species) {
            case DEER, IBEX -> { actor.inventory.add(Items.VENISON, 2); actor.inventory.add(Items.HIDE, 1); }
            case RABBIT -> { actor.inventory.add(Items.RABBIT, 1); if (rnd.nextBoolean()) actor.inventory.add(Items.HIDE, 1); }
            case TROUT -> actor.inventory.add(Items.TROUT, 1);
            case FOX -> actor.inventory.add(Items.HIDE, 1);
            case SHEEP, GOAT -> { actor.inventory.add(Items.MUTTON, 2); actor.inventory.add(Items.HIDE, 1); }
            default -> { }
        }
        flows.take(c.species == Species.TROUT ? Matter.FISH : Matter.GAME, 1, c.x, c.z);
        actor.skills.practice(Skill.HUNTING, 3);
        if (Ecology.breeding(c.species, life.eco.season().phase()) > 0.3) {
            flows.offSeasonKill(c.x, c.z);
            say(c.species.displayName + " tué en pleine saison des petits : la boussole s'en souviendra.");
        }
    }


    // ---------------------------------------------------------------------------- the herd

    /**
     * The right hand on an animal (returns false if nothing came of it, so the hand does its usual
     * work): taming a wild mouflon or goat with its food, feeding a kept one (fed by hand, an adult
     * is ready to breed), shearing a sheep, milking a goat into a jug, or a look at how it fares.
     */
    boolean tend(Creature c, int slot, Life life) {
        if (c.dying > 0) return false;
        short held = actor.inventory.id(slot);
        byte kind = Items.TOOL[held & 0xFFFF];
        Species s = c.species;
        String name = c.name();
        if (!c.tame) {
            if (!s.domestic() || !s.eats(held)) return false;
            actor.inventory.takeOne(slot);
            c.hunger = 0;
            c.trust = Math.min(1, c.trust + 0.25 * actor.efficiency(Skill.HUSBANDRY));
            actor.skills.practice(Skill.HUSBANDRY, 1);
            if (c.trust >= 1) {
                life.tame(c, "joueur");
                flows.take(Matter.GAME, 1, c.x, c.z); // taken alive from the wild flock
                actor.skills.practice(Skill.HUSBANDRY, 3);
                say(s.displayName + " vous fait confiance : " + (s == Species.SHEEP ? "c'est désormais un mouton" : "elle est à vous")
                    + ". Il suit la nourriture que vous tenez.");
            } else say(String.format("%s mange dans votre main (confiance %.0f %%). Approchez accroupi.", s.displayName, 100 * c.trust));
            return true;
        }
        if (s.eats(held)) {
            boolean ready = c.adult() && c.breedCooldown <= 0;
            if (c.hunger < 0.05 && !ready) { say(name + " n'a pas faim."); return true; }
            actor.inventory.takeOne(slot);
            c.hunger = 0;
            if (ready) c.love = 30;
            actor.skills.practice(Skill.HUSBANDRY, 0.5);
            say(ready ? name + " est nourri : un autre adulte nourri tout près, et un petit naîtra." : name + " est nourri.");
            return true;
        }
        if (s == Species.SHEEP && (kind == Items.TOOL_SHEARS || kind == Items.TOOL_KNIFE)) {
            if (!c.adult() || c.shorn) { say(String.format("Sa toison repousse (%.0f %%).", 100 * c.produce)); return true; }
            int n = kind == Items.TOOL_SHEARS ? 2 + (rnd.nextDouble() < 0.1 * actor.skills.level(Skill.HUSBANDRY) ? 1 : 0) : 1;
            actor.inventory.add(Items.FLEECE, n);
            actor.inventory.wearOut(slot);
            c.shorn = true;
            c.produce = 0;
            actor.skills.practice(Skill.HUSBANDRY, 1.5);
            say("Vous tondez le mouton : " + n + (n > 1 ? " toisons." : " toison") + (kind == Items.TOOL_KNIFE ? " (des cisailles en donneraient plus)." : "."));
            return true;
        }
        if (s == Species.GOAT && held == Items.JUG) {
            if (!c.adult() || c.produce < 1) { say(String.format("La chèvre n'a pas encore de lait (%.0f %%).", 100 * c.produce)); return true; }
            actor.inventory.takeOne(slot);
            actor.inventory.add(Items.MILK, 1);
            c.produce = 0;
            actor.skills.practice(Skill.HUSBANDRY, 1);
            say("Vous trayez la chèvre : une cruche de lait.");
            return true;
        }
        // A look at how it fares.
        StringBuilder t = new StringBuilder(name).append(c.adult() ? "" : " (jeune)").append(" : ");
        t.append(c.hunger < 0.3 ? "repu" : c.hunger < 0.7 ? "a un peu faim" : "a faim");
        if (c.adult() && s == Species.SHEEP) t.append(c.shorn ? String.format(", toison %.0f %%", 100 * c.produce) : ", toison à tondre");
        if (c.adult() && s == Species.GOAT) t.append(c.produce >= 1 ? ", lait à traire" : String.format(", lait %.0f %%", 100 * c.produce));
        if (c.adult() && s == Species.HEN) t.append(String.format(", œuf %.0f %%", 100 * c.produce));
        if (c.pen != null) t.append(String.format(", enclos de %d cases (herbe pour %d, %d demandées)", c.pen.cells(),
            c.pen.grass(), life.demand(c.pen)));
        else t.append(", en liberté");
        say(t.toString());
        return false;
    }

    // ---------------------------------------------------------------------------- every frame

    /** Returns the blocks changed (growth, the hamlet) for redrawing. */
    List<int[]> tick(double dt, World w, Player p, net.mynecraft.world.weather.Weather weather, Life life, RecipeBook recipes,
                     double daylight, boolean creative, Survival survival, boolean mining) {
        List<int[]> changed = new java.util.ArrayList<>();
        actor.x = p.x;
        actor.y = p.y;
        actor.z = p.z;
        double day = life.eco.day(), phase = life.eco.season().phase();
        flows.day = day;
        if ((bankTimer -= dt) <= 0) { bankTimer = 1; riverbank.update(w, life.ecology); net.mynecraft.world.life.Riverbank.hold(life.ecology, flows::downstream); } // reeds and willows clean the water
        actor.skills.rust(day);
        toastLeft -= dt;
        var air = weather.here();
        boolean raining = air.precipitation() > 0.1 && air.temperature() > 0;
        followFlood(day, air.temperature() > 0 ? Math.min(1, air.precipitation() / 0.03) : 0, w, p, changed); // 0.03: a downpour
        double speed = Math.hypot(p.vx, p.vz);
        effort = mining ? 0.6 : speed > 5 ? 1 : speed > 0.5 ? 0.25 : 0;
        if (!creative && !p.flying) {
            int hurt = actor.needs.tick(dt, effort, place.felt(air.temperature(), raining), false);
            if (hurt > 0) survival.hurt(hurt);
            if (heal > 0) { survival.health = Math.min(Survival.MAX_HEALTH, survival.health + heal); heal = 0; }
        }
        survival.fed = actor.needs.food > 6 && actor.needs.water > 4;
        if ((measureTimer -= dt) <= 0) {
            measureTimer = 2;
            place = Wellbeing.measure(w, p.x, p.y, p.z);
            double cx = actor.hasHome() ? actor.homeX : p.x, cz = actor.hasHome() ? actor.homeZ : p.z;
            nature = Harmony.nature(life.ecology, flows, cx, cz);
            boolean near = false;
            for (Villager v : hamlet.villagers) near |= Math.hypot(v.x - p.x, v.z - p.z) < 12;
            company = Math.max(0, Math.min(1, company + (near ? 0.02 : -0.005)));
            self = Harmony.self(actor, place, company);
        }
        if ((growTimer -= dt) <= 0) {
            growTimer = 5;
            if (!Double.isNaN(lastGrowDay) && day > lastGrowDay) changed.addAll(growth.grow(w, life.ecology, flows, phase, day - lastGrowDay));
            lastGrowDay = day;
        }
        // Fishing: a bite after a while, if the river holds fish; moving or changing item reels in.
        if (fishLeft >= 0) {
            if (Math.hypot(p.x - fishFrom[0], p.z - fishFrom[1]) > 1.5 || Items.TOOL[actor.inventory.id((int) fishFrom[2])] != Items.TOOL_ROD) {
                fishLeft = -1;
            } else if ((fishLeft -= dt) < 0) {
                Ecology.Patch patch = life.ecology.at(p.x, p.z);
                if (patch.pop[Species.TROUT.ordinal()] >= 1 || rnd.nextDouble() < patch.aquatic * 0.2) {
                    if (patch.pop[Species.TROUT.ordinal()] >= 1) life.ecology.died(Species.TROUT, p.x, p.z);
                    flows.take(Matter.FISH, 1, p.x, p.z);
                    actor.inventory.add(Items.TROUT, 1);
                    actor.inventory.wearOut((int) fishFrom[2]);
                    actor.skills.practice(Skill.FISHING, 3);
                    if (Ecology.breeding(Species.TROUT, phase) > 0.3) {
                        flows.offSeasonKill(p.x, p.z);
                        say("Une truite ! Mais c'est le temps de la fraie...");
                    } else say("Une truite !");
                } else {
                    actor.skills.practice(Skill.FISHING, 0.5);
                    say("Rien ne mord ici : la rivière est pauvre en poissons.");
                }
            }
        }
        // A station at work.
        if (making != null && (makingLeft -= dt) <= 0) {
            if (Workshop.make(actor, making, power, flows)) say("Fait : " + Items.name(making.outputs().get(0).item()).replace('_', ' '));
            making = null;
        }
        // The hamlet: founded near the spawn once its land is loaded.
        if (flows.river == null && w.generator != null) { // wastewater goes down the river
            var hydrology = w.generator.landscape().hydrology();
            flows.river = (x, z) -> { float[] r = hydrology.water(x, z); return r == null ? Double.NaN : r[0]; };
        }
        // The herd: muck back to the soil, the hamlet's eggs into its store, chicks out of their eggs, news.
        for (double[] d; (d = life.dung.poll()) != null; ) flows.give(Matter.COMPOST, d[2], d[0], d[1]);
        for (Creature c : life.creatures) // the hamlet feeds its hens from the store when the grass is not enough
            if (c.tame && "hameau".equals(c.owner) && c.hunger > 0.7 && !c.dead) {
                short grain = hamlet.store.count(Items.WHEAT_SEEDS) > 0 ? Items.WHEAT_SEEDS : hamlet.store.count(Items.WHEAT) > 0 ? Items.WHEAT : 0;
                if (grain != 0) { hamlet.store.remove(grain, 1); c.hunger = 0; }
            }
        if (life.hamletEggs > 0) { hamlet.store.add(Items.EGG, life.hamletEggs); life.hamletEggs = 0; }
        for (int[] n : growth.hatched) { life.keepNew(Species.HEN, n[0] + 0.5, n[1] + 0.1, n[2] + 0.5, "joueur", true); say("Un poussin est éclos !"); }
        growth.hatched.clear();
        for (String note; (note = life.notes.poll()) != null; ) say(note);
        if (!hamlet.founded) {
            foundHamlet(w);
            if (hamlet.founded && raiseHamlet) hamlet.raiseAll(w); // scripted shots: the hamlet as it will be
        }
        if (hamlet.founded && !hamlet.hens && hamlet.huts.size() > 1) { // the family came with three hens, to their run
            int[] run = hamlet.henRun();
            for (int i = 0; i < 3; i++)
                life.keepNew(Species.HEN, run[0] - 0.5 + i, run[1] + 0.2, run[2] + 0.5, "hameau", false);
            hamlet.hens = true;
        }
        if (hamlet.founded) {
            hamlet.tick(dt, w, flows, life.ecology, recipes, daylight, raining, day);
            followProjects(day);
            for (int[] e; (e = hamlet.edits.poll()) != null; ) {
                changed.add(e);
                short b = w.get(e[0], e[1], e[2]); // what the inhabitants picked or sowed grows back like the player's
                if (b == Blocks.BERRY_BUSH_PICKED || b == Blocks.WHEAT_0 || b == Blocks.OAK_SAPLING || b == Blocks.BEEHIVE_BEES || b == Blocks.NEST_EGG)
                    growth.planted(e[0], e[1], e[2]);
            }
        }
        return changed;
    }

    /** Proposes the river's project when the fisher complains, and follows it day by day. */
    private void followProjects(double day) {
        var fisher = hamlet.villager(net.mynecraft.world.actor.Job.FISHER);
        if (hamlet.riverFouled && fisher != null && projects.get(net.mynecraft.world.actor.Projects.RIVER) == null) {
            double fx = fisher.workAt != null ? fisher.workAt[0] : fisher.x, fz = fisher.workAt != null ? fisher.workAt[2] : fisher.z;
            projects.proposeRiver(fx, fz);
            say("Nouveau projet : ramener la rivière à la vie (carnet, page des projets ; Y pour l'accepter).");
        }
        if (Double.isNaN(lastProjectDay) || day < lastProjectDay) { lastProjectDay = day; return; }
        if (day - lastProjectDay < 0.05) return;
        var a = projects.get(net.mynecraft.world.actor.Projects.RIVER);
        Ecology.Patch p = a == null ? null : life.ecology.existing(a.x, a.z);
        if (projects.river(p, day - lastProjectDay)) {
            p.pop[Species.HERON.ordinal()] = Math.max(1, p.pop[Species.HERON.ordinal()]); // and the herons come back to stay
            heronComing = true;
            say("Projet accompli : la rivière revit ! Un héron cendré arrive...");
        }
        lastProjectDay = day;
    }

    int[] spawn;
    /** Scripted shots: every building of the hamlet raised at once when it is founded. */
    boolean raiseHamlet;

    private void foundHamlet(World w) {
        if (spawn == null) return;
        int cx = spawn[0], cz = spawn[2];
        for (int dx = -16; dx <= 48; dx += 16) // the hamlet's land, round its green (18, 10 from here), is loaded
            for (int dz = -16; dz <= 32; dz += 16)
                if (w.chunk((cx + dx) >> 4, (cz + dz) >> 4) == null) return;
        hamlet.found(w, cx, cz, (x, z) -> {
            for (int y = 140; y > 0; y--) {
                short b = w.get(x, y, z);
                if (Blocks.isSolid(b) && !Blocks.NAMES[b].endsWith("_leaves") && !Blocks.NAMES[b].endsWith("_log")) return y;
            }
            return 64;
        });
        say("Un hameau vit non loin d'ici.");
        StringBuilder sites = new StringBuilder();
        for (int i = 0; i < hamlet.huts.size(); i++) sites.append(' ').append(hamlet.plans.get(i)).append(java.util.Arrays.toString(hamlet.huts.get(i)));
        System.out.printf("[hamlet] founded near %d, %d:%s%n", cx, cz, sites);
    }

    // ---------------------------------------------------------------------------- stations

    void openStation(String name, int[] at, World w, double wind) {
        station = name;
        stationAt = at;
        Ecology.Patch here = life.ecology.existing(at[0], at[2]);
        power = Power.at(w, at[0], at[1], at[2], wind, here == null ? 0 : here.held);
    }

    void closeStation() {
        station = null;
        making = null;
    }

    void start(RecipeBook.Process p) {
        if (making != null) return;
        String missing = Workshop.missing(actor, p, power);
        if (missing != null) { say("Il manque : " + missing); return; }
        making = p;
        makingTotal = makingLeft = Workshop.seconds(actor, p, power);
    }

    // ---------------------------------------------------------------------------- plans and building tools

    void mark(int[] block) {
        if (markA == null || markB != null) { markA = block; markB = null; say("Premier coin marqué (K : second coin)."); }
        else { markB = block; say("Sélection : P enregistre un plan" + " | G remplit (créatif)."); }
    }

    void savePlan(World w) {
        if (markA == null || markB == null) { say("Marquez deux coins avec K."); return; }
        String name = "plan-" + (plans.size() + 1);
        Blueprint b = Blueprint.trace(name, w, markA[0], markA[1], markA[2], markB[0], markB[1], markB[2]);
        plans.put(name, b);
        actor.plans.add(name);
        try { b.save(worldDir.resolve("blueprints")); } catch (IOException e) { System.err.println("[plans] " + e.getMessage()); }
        actor.skills.practice(Skill.BUILDING, 2);
        say("Plan « " + name + " » tracé : " + b.w + " x " + b.h + " x " + b.d + ", " + b.materials().values().stream().mapToInt(i -> i).sum() + " blocs.");
    }

    /** O: the next known plan, anchored on the block aimed at (or put away after the last). */
    void cyclePlan(int[] block) {
        List<String> known = actor.plans.stream().filter(plans::containsKey).toList();
        planIndex = planIndex + 1 >= known.size() ? -1 : planIndex + 1;
        if (planIndex < 0 || block == null) { activePlan = null; say("Plan rangé."); return; }
        activePlan = plans.get(known.get(planIndex));
        planAnchor = new int[] {block[0], block[1], block[2]};
        say("Plan « " + activePlan.name + " » posé ici (O : plan suivant).");
    }

    /** The block the player just placed may be the plan's next step. */
    void placed(World w, int x, int y, int z) {
        short b = w.get(x, y, z);
        if (b == Blocks.FISH_TRAP || b == Blocks.SNARE || b == Blocks.BEEHIVE) growth.planted(x, y, z);
        if (activePlan == null) return;
        int ax = x - planAnchor[0], ay = y - planAnchor[1], az = z - planAnchor[2];
        if (ax >= 0 && ay >= 0 && az >= 0 && ax < activePlan.w && ay < activePlan.h && az < activePlan.d
            && activePlan.at(ax, ay, az) == w.get(x, y, z)) actor.skills.practice(Skill.BUILDING, 1);
        else actor.skills.practice(Skill.BUILDING, 0.2);
    }

    /** Creative: fill the selection with a block (AIR clears it); undoable. */
    List<int[]> fill(World w, short block) {
        List<int[]> changed = new java.util.ArrayList<>();
        if (markA == null || markB == null) { say("Marquez deux coins avec K."); return changed; }
        int n = 0;
        for (int y = Math.min(markA[1], markB[1]); y <= Math.max(markA[1], markB[1]); y++)
            for (int z = Math.min(markA[2], markB[2]); z <= Math.max(markA[2], markB[2]); z++)
                for (int x = Math.min(markA[0], markB[0]); x <= Math.max(markA[0], markB[0]); x++) {
                    if (n++ > 32768) break;
                    edit(w, x, y, z, block, changed);
                }
        undo.push(new int[] {Integer.MIN_VALUE}); // the end of one action
        return changed;
    }

    /** Creative: raise the active plan at once; undoable. */
    List<int[]> buildPlan(World w) {
        List<int[]> changed = new java.util.ArrayList<>();
        if (activePlan == null) return changed;
        for (int i = 0; i < 40000; i++) {
            int[] s = activePlan.next(w, planAnchor[0], planAnchor[1], planAnchor[2]);
            if (s == null) break;
            edit(w, s[0], s[1], s[2], (short) s[3], changed);
        }
        undo.push(new int[] {Integer.MIN_VALUE});
        return changed;
    }

    private void edit(World w, int x, int y, int z, short b, List<int[]> changed) {
        short old = w.get(x, y, z);
        if (w.set(x, y, z, b)) {
            undo.push(new int[] {x, y, z, old});
            changed.add(new int[] {x, y, z});
            while (undo.size() > 200000) undo.removeLast();
        }
    }

    List<int[]> undo(World w) {
        List<int[]> changed = new java.util.ArrayList<>();
        if (!undo.isEmpty() && undo.peek()[0] == Integer.MIN_VALUE) undo.pop();
        while (!undo.isEmpty() && undo.peek()[0] != Integer.MIN_VALUE) {
            int[] e = undo.pop();
            w.set(e[0], e[1], e[2], (short) e[3]);
            changed.add(e);
        }
        say(changed.isEmpty() ? "Rien à annuler." : "Annulé : " + changed.size() + " blocs.");
        return changed;
    }

    /** The villager under the crosshair (within reach), or null. */
    Villager aimedVillager(double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        Villager best = null;
        double bestT = max;
        for (Villager v : hamlet.villagers) {
            double t = v.rayHit(ox, oy, oz, dx, dy, dz, bestT);
            if (t >= 0 && t < bestT) { bestT = t; best = v; }
        }
        return best;
    }

    /** French names of items, set by the first HUD draw. */
    private ItemNames itemNames;

    void talk(Villager v, int slot) {
        String trade = hamlet.trade(v, actor, slot, id -> itemNames == null ? Items.name((short) id) : itemNames.name((short) id));
        say(trade != null ? trade : hamlet.talk(v, actor));
        company = Math.min(1, company + 0.15);
    }

    // ---------------------------------------------------------------------------- the HUD

    /** Four small gauges over the hotbar (hunger, thirst, rest, warmth), the message, the plan at hand. */
    float drawHud(Hud hud, float top, float left, float right, boolean creative, World w, ItemNames names) {
        itemNames = names;
        float s = hud.scale, bw = (right - left - 9 * s) / 4, bh = 3 * s, y = top - bh - 2 * s;
        if (!creative && actor.needs.rate > 0) {
            var n = actor.needs;
            // Cold creeps in from the screen's edges as frost, heat as a red haze.
            float cold = (float) Math.max(0, (0.3 - n.warmth) / 0.3), hot = (float) Math.max(0, (n.warmth - 0.75) / 0.25);
            if (cold > 0 || hot > 0) {
                float r = cold > 0 ? 0.8f : 1, g = cold > 0 ? 0.9f : 0.35f, b = cold > 0 ? 1 : 0.15f, k = Math.max(cold, hot);
                float W = hud.width(), H = hud.height();
                for (int i = 0; i < 6; i++) {
                    float d = (i + 1) * 0.025f * k * Math.min(W, H), a = 0.07f * k;
                    hud.rect(0, 0, W, d, r, g, b, a);
                    hud.rect(0, H - d, W, d, r, g, b, a);
                    hud.rect(0, d, d, H - 2 * d, r, g, b, a);
                    hud.rect(W - d, d, d, H - 2 * d, r, g, b, a);
                }
            }
            double[] v = {n.food / 20, n.water / 20, n.rest, 1 - 2 * Math.abs(n.warmth - 0.5)};
            float[][] c = {{0.85f, 0.55f, 0.25f}, {0.3f, 0.6f, 0.95f}, {0.7f, 0.7f, 0.85f}, n.warmth < 0.5 ? new float[] {0.5f, 0.8f, 1}
                : new float[] {1, 0.5f, 0.3f}};
            for (int i = 0; i < 4; i++) {
                float x = left + i * (bw + 3 * s);
                hud.rect(x, y, bw, bh, 0, 0, 0, 0.55f);
                hud.rect(x, y, (float) (bw * Math.max(0, Math.min(1, v[i]))), bh, c[i][0], c[i][1], c[i][2], 0.95f);
            }
            top = y - 2 * s;
        }
        // Wear of the tools in the hotbar: a bar under each, green to red.
        float slot = 20 * s, gap = 2 * s, y0 = hud.height() - slot - 4 * s;
        for (int i = 0; i < Inventory.HOTBAR && !creative; i++) {
            short id = actor.inventory.id(i);
            int max = Items.DURABILITY[id & 0xFFFF], worn = actor.inventory.wear[i];
            if (max <= 0 || worn <= 0) continue;
            float f = 1 - worn / (float) max, x = left + i * (slot + gap) + 2 * s;
            hud.rect(x, y0 + slot - 3 * s, slot - 4 * s, 2 * s, 0, 0, 0, 0.8f);
            hud.rect(x, y0 + slot - 3 * s, (slot - 4 * s) * f, 2 * s, 1 - f, f, 0.1f, 1);
        }
        float cy = hud.height() * 0.62f;
        if (toastLeft > 0) hud.textCentered(toast, hud.width() / 2f, cy, 1, 0.95f, 0.8f);
        if (fishLeft >= 0) hud.textCentered("La ligne est à l'eau...", hud.width() / 2f, cy + hud.lineHeight() * 1.5f, 0.7f, 0.85f, 1);
        if (activePlan != null) {
            int[] next = activePlan.next(w, planAnchor[0], planAnchor[1], planAnchor[2]);
            StringBuilder t = new StringBuilder("Plan « " + activePlan.name + " »");
            if (next == null) t.append(" : terminé !");
            else {
                t.append(" : ").append(activePlan.steps(w, planAnchor[0], planAnchor[1], planAnchor[2])).append(" blocs à poser, manque ");
                int k = 0;
                for (var e : activePlan.remaining(w, planAnchor[0], planAnchor[1], planAnchor[2]).entrySet()) {
                    int have = actor.inventory.count(e.getKey());
                    if (have >= e.getValue()) continue;
                    if (k++ == 4) { t.append("..."); break; }
                    t.append(e.getValue() - have).append(' ').append(names.name(e.getKey())).append(", ");
                }
                if (k == 0) t.append("rien  ");
                t.setLength(t.length() - 2);
            }
            hud.text(t.toString(), 6 * s, hud.height() * 0.5f, 0.85f, 0.95f, 0.85f);
        }
        return top;
    }

    /** The next block of the active plan, to outline in the world (or null). */
    int[] nextStep(World w) {
        return activePlan == null ? null : activePlan.next(w, planAnchor[0], planAnchor[1], planAnchor[2]);
    }
}
