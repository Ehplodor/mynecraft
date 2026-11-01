package net.mynecraft.world.actor;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Drops;
import net.mynecraft.world.Growth;
import net.mynecraft.world.Items;
import net.mynecraft.world.World;

import java.util.List;
import java.util.SplittableRandom;

/**
 * The everyday gestures of an actor on the world, the same for the player and the inhabitants:
 * breaking a block (time from the tool, the skill and the body; drops; the flow taken from the
 * patch; the tool's wear; the practice), and the uses of the right hand: eating, drinking,
 * picking berries, hoeing, sowing, planting, composting, opening a door. Pure world logic.
 */
public final class Gestures {
    private Gestures() {}

    /** Seconds this actor takes to break the block with the tool (AIR: by hand); infinite if unbreakable. */
    public static double breakSeconds(Actor a, short block, short tool) {
        float h = Blocks.HARDNESS[block & 0xFFFF];
        if (h < 0) return Double.POSITIVE_INFINITY;
        double base = h * (Items.canHarvest(tool, block) ? 1.5 : 5);
        return base / Items.speed(tool, block) / a.efficiency(Drops.skill(block));
    }

    /** The block at (x, y, z) was broken by the actor with the tool in {@code slot}: drops, flows, wear, practice. */
    public static List<Drops.Drop> broke(Actor a, short block, int slot, int x, int y, int z, Flows flows, SplittableRandom rnd) {
        short tool = a.inventory.id(slot);
        Skill skill = Drops.skill(block);
        List<Drops.Drop> drops = Drops.of(block, tool, a.skills.level(Skill.FORAGING), rnd);
        for (Drops.Drop d : drops) a.inventory.add(d.item(), d.count());
        byte m = Blocks.MATTER[block & 0xFFFF];
        if (flows != null && m >= 0) flows.take(Matter.values()[m], 1, x, z);
        if (Items.DURABILITY[tool & 0xFFFF] > 0 && Blocks.HARDNESS[block & 0xFFFF] > 0) a.inventory.wearOut(slot);
        a.skills.practice(skill, 0.3 + 0.3 * Blocks.HARDNESS[block & 0xFFFF]);
        return drops;
    }

    /** What a use of the right hand did (for sounds and messages); NONE if nothing. */
    public enum Use { NONE, ATE, DRANK, PICKED, HOED, SOWED, PLANTED, COMPOSTED, DOOR, SLEPT, HIVE_LOST, SAT, RESTED, LIGHT }

    /**
     * The right hand on the block at (x, y, z) (or on nothing: x = Integer.MIN_VALUE) holding the item
     * in {@code slot}. {@code waterAimed}: the eyes' ray meets water first (drinking from a river).
     */
    public static Use use(Actor a, World w, int slot, int x, int y, int z, boolean waterAimed, boolean night, Flows flows,
                          Growth growth, SplittableRandom rnd) {
        return use(a, w, slot, x, y, z, waterAimed, night, flows, growth, rnd, 0.4);
    }

    /** As above, {@code season} the phase of the year (0..1, winter from 0.75: a hive's honey is the bees' own). */
    public static Use use(Actor a, World w, int slot, int x, int y, int z, boolean waterAimed, boolean night, Flows flows,
                          Growth growth, SplittableRandom rnd, double season) {
        short held = a.inventory.id(slot);
        boolean aimed = x != Integer.MIN_VALUE;
        short target = aimed ? w.get(x, y, z) : Blocks.AIR;
        if (Blocks.isDoor(target)) { // both halves
            net.mynecraft.world.Doors.toggle(w, x, y, z);
            return Use.DOOR;
        }
        if (target == Blocks.LANTERN || target == Blocks.LANTERN_OFF) {
            w.set(x, y, z, target == Blocks.LANTERN ? Blocks.LANTERN_OFF : Blocks.LANTERN);
            return Use.LIGHT;
        }
        if (Blocks.isChair(target)) { // a seat: the legs rest (the caller sits the body down)
            a.needs.rest = Math.min(1, a.needs.rest + 0.02);
            return Use.SAT;
        }
        if (target == Blocks.BARREL && a.needs.water < Needs.MAX - 0.5) { // the house's water, drawn from the well
            a.needs.drink(6);
            return Use.DRANK;
        }
        if (target == Blocks.STRAW_BED && !night) { // a nap: some rest, and the day goes on
            a.needs.rest = Math.min(1, a.needs.rest + 0.15);
            return Use.RESTED;
        }
        if (target == Blocks.BERRY_BUSH) {
            w.set(x, y, z, Blocks.BERRY_BUSH_PICKED);
            a.inventory.add(Items.SWEET_BERRIES, 2 + rnd.nextInt(2) + (rnd.nextDouble() < 0.1 * a.skills.level(Skill.FORAGING) ? 1 : 0));
            if (flows != null) flows.take(Matter.FRUIT, 1, x, z);
            if (growth != null) growth.planted(x, y, z);
            a.skills.practice(Skill.FORAGING, 1);
            return Use.PICKED;
        }
        if (target == Blocks.STRAW_BED && night) return Use.SLEPT;
        if (target == Blocks.FISH_TRAP_FULL) { // the trap's catch (already counted when it filled)
            w.set(x, y, z, Blocks.FISH_TRAP);
            a.inventory.add(Items.TROUT, 1);
            if (growth != null) growth.planted(x, y, z);
            a.skills.practice(Skill.FISHING, 1);
            return Use.PICKED;
        }
        if (target == Blocks.SNARE_FULL) { // a rabbit in the snare
            w.set(x, y, z, Blocks.SNARE);
            a.inventory.add(Items.RABBIT, 1);
            a.inventory.add(Items.HIDE, 1);
            if (growth != null) growth.planted(x, y, z);
            a.skills.practice(Skill.HUNTING, 1);
            return Use.PICKED;
        }
        if (target == Blocks.NEST_EGG) { // an egg from the nest (left there, it would hatch)
            w.set(x, y, z, Blocks.NEST);
            a.inventory.add(Items.EGG, 1);
            a.skills.practice(Skill.HUSBANDRY, 0.5);
            return Use.PICKED;
        }
        if (held == Items.EGG && target == Blocks.NEST) { // to brood: a chick in two days
            w.set(x, y, z, Blocks.NEST_EGG);
            a.inventory.takeOne(slot);
            if (growth != null) growth.planted(x, y, z);
            a.skills.practice(Skill.HUSBANDRY, 0.5);
            return Use.PLANTED;
        }
        if (target == Blocks.BEEHIVE_HONEY) { // honey; taken in winter, it is the colony's own store and the bees die
            boolean winter = season >= 0.75;
            w.set(x, y, z, winter ? Blocks.BEEHIVE : Blocks.BEEHIVE_BEES);
            a.inventory.add(Items.HONEY, winter ? 3 : 2 + (rnd.nextDouble() < 0.1 * a.skills.level(Skill.HUSBANDRY) ? 1 : 0));
            if (flows != null) flows.take(Matter.FRUIT, 0.5, x, z);
            if (growth != null) growth.planted(x, y, z);
            a.skills.practice(Skill.HUSBANDRY, 1);
            a.skills.practice(Skill.FORAGING, 0.5);
            return winter ? Use.HIVE_LOST : Use.PICKED;
        }
        byte kind = Items.TOOL[held & 0xFFFF];
        if (kind == Items.TOOL_HOE && (target == Blocks.GRASS || target == Blocks.DIRT) && w.get(x, y + 1, z) == Blocks.AIR) {
            w.set(x, y, z, Blocks.FARMLAND);
            a.inventory.wearOut(slot);
            a.skills.practice(Skill.FARMING, 0.5);
            return Use.HOED;
        }
        if (held == Items.WHEAT_SEEDS && target == Blocks.FARMLAND && w.get(x, y + 1, z) == Blocks.AIR) {
            w.set(x, y + 1, z, Blocks.WHEAT_0);
            a.inventory.takeOne(slot);
            if (growth != null) growth.planted(x, y + 1, z);
            a.skills.practice(Skill.FARMING, 0.5);
            return Use.SOWED;
        }
        if (held == Blocks.OAK_SAPLING && (target == Blocks.GRASS || target == Blocks.DIRT) && w.get(x, y + 1, z) == Blocks.AIR) {
            w.set(x, y + 1, z, Blocks.OAK_SAPLING);
            a.inventory.takeOne(slot);
            if (growth != null) growth.planted(x, y + 1, z);
            a.skills.practice(Skill.FARMING, 1);
            return Use.PLANTED;
        }
        if (held == Items.COMPOST && (target == Blocks.FARMLAND || Blocks.wheatAge(target) >= 0)) {
            a.inventory.takeOne(slot);
            if (flows != null) flows.give(Matter.COMPOST, 1, x, z); // feeds the patch's soil
            int age = Blocks.wheatAge(target);
            if (age >= 0 && age < 3) w.set(x, y, z, (short) (target + 1));
            a.skills.practice(Skill.FARMING, 0.5);
            return Use.COMPOSTED;
        }
        if (Items.isFood(held) && (a.needs.food < Needs.MAX - 0.5 || held == Items.MILK && a.needs.water < Needs.MAX - 0.5)) {
            a.needs.eat(held);
            a.inventory.takeOne(slot);
            if (held == Items.MILK) a.inventory.add(Items.JUG, 1); // the jug is kept
            return Use.ATE;
        }
        if (waterAimed && (held == Blocks.AIR || !Items.isBlock(held)) && a.needs.water < Needs.MAX - 0.5) {
            a.needs.drink(4);
            if (flows != null) flows.take(Matter.WATER, 0.05, a.x, a.z);
            return Use.DRANK;
        }
        return Use.NONE;
    }
}
