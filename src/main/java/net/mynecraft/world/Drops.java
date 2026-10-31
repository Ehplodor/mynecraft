package net.mynecraft.world;

import net.mynecraft.world.actor.Skill;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * What breaking a block gives, and which skill it trains: the block's own drop (when the tool can
 * harvest it), plus the gatherer's finds: seeds, herbs and fibre in the grass, flint in gravel,
 * apples and saplings in oak leaves, coal from its ore, clay balls, wheat and seeds from ripe wheat.
 * A practised gatherer finds a little more.
 */
public final class Drops {
    private Drops() {}

    public record Drop(short item, int count) {}

    public static List<Drop> of(short block, short tool, int foragingLevel, SplittableRandom rnd) {
        List<Drop> out = new ArrayList<>();
        int b = block & 0xFFFF;
        if (!Items.canHarvest(tool, block)) return out;
        double luck = 1 + 0.08 * foragingLevel;
        switch (block) {
            case Blocks.SHORT_GRASS -> {
                if (rnd.nextDouble() < 0.15 * luck) out.add(new Drop(Items.WHEAT_SEEDS, 1));
                if (rnd.nextDouble() < 0.10 * luck) out.add(new Drop(Items.WILD_HERBS, 1));
                if (rnd.nextDouble() < 0.30 * luck) out.add(new Drop(Items.PLANT_FIBER, 1));
            }
            case Blocks.GRAVEL -> out.add(rnd.nextDouble() < 0.15 * luck ? new Drop(Items.FLINT, 1) : new Drop(Blocks.GRAVEL, 1));
            case Blocks.OAK_LEAVES, Blocks.BIRCH_LEAVES, Blocks.SPRUCE_LEAVES -> {
                if (block == Blocks.OAK_LEAVES && rnd.nextDouble() < 0.05 * luck) out.add(new Drop(Items.APPLE, 1));
                if (block == Blocks.OAK_LEAVES && rnd.nextDouble() < 0.05 * luck) out.add(new Drop(Blocks.OAK_SAPLING, 1));
                if (rnd.nextDouble() < 0.10) out.add(new Drop(Items.STICK, 1));
            }
            case Blocks.COAL_ORE -> out.add(new Drop(Items.COAL, 1));
            case Blocks.CLAY -> out.add(new Drop(Items.CLAY_BALL, 4));
            case Blocks.WHEAT_7 -> {
                out.add(new Drop(Items.WHEAT, 1));
                out.add(new Drop(Items.WHEAT_SEEDS, 1 + rnd.nextInt(3)));
            }
            case Blocks.WHEAT_0, Blocks.WHEAT_0 + 1, Blocks.WHEAT_0 + 2 -> out.add(new Drop(Items.WHEAT_SEEDS, 1));
            case Blocks.BERRY_BUSH -> out.add(new Drop(Items.SWEET_BERRIES, 2 + rnd.nextInt(2)));
            default -> {
                short d = Blocks.DROP[b];
                if (d != Blocks.AIR) out.add(new Drop(d, Blocks.DROP_COUNT[b]));
            }
        }
        return out;
    }

    /** The skill breaking this block trains. */
    public static Skill skill(short block) {
        int b = block & 0xFFFF;
        if (Blocks.MATTER[b] == 0) return Skill.WOODCUTTING;
        if (Blocks.MATTER[b] == 4 || Blocks.MATTER[b] == 5) return Skill.FORAGING; // plants, fruit
        if (Blocks.wheatAge(block) >= 0) return Skill.FARMING;
        return Skill.MINING;
    }
}
