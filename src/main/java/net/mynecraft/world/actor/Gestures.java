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
    public enum Use { NONE, ATE, DRANK, PICKED, HOED, SOWED, PLANTED, COMPOSTED, DOOR, SLEPT }

    /**
     * The right hand on the block at (x, y, z) (or on nothing: x = Integer.MIN_VALUE) holding the item
     * in {@code slot}. {@code waterAimed}: the eyes' ray meets water first (drinking from a river).
     */
    public static Use use(Actor a, World w, int slot, int x, int y, int z, boolean waterAimed, boolean night, Flows flows,
                          Growth growth, SplittableRandom rnd) {
        short held = a.inventory.id(slot);
        boolean aimed = x != Integer.MIN_VALUE;
        short target = aimed ? w.get(x, y, z) : Blocks.AIR;
        if (target == Blocks.OAK_DOOR || target == Blocks.OAK_DOOR_OPEN) {
            w.set(x, y, z, target == Blocks.OAK_DOOR ? Blocks.OAK_DOOR_OPEN : Blocks.OAK_DOOR);
            return Use.DOOR;
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
        if (Items.isFood(held) && a.needs.food < Needs.MAX - 0.5) {
            a.needs.eat(held);
            a.inventory.takeOne(slot);
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
