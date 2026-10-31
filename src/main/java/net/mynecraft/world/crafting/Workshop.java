package net.mynecraft.world.crafting;

import net.mynecraft.world.Inventory;
import net.mynecraft.world.Items;
import net.mynecraft.world.actor.Actor;
import net.mynecraft.world.actor.Flows;
import net.mynecraft.world.actor.Matter;

/**
 * Making a station recipe, for any actor (the player at a station screen, an inhabitant at work):
 * checks inputs, tool, skill level and energy, then takes the inputs, burns the fuel (heat) or
 * spends the arms' strength when no wheel or windmill turns (work), wears the tool, gives the
 * outputs, returns the waste and the ash to the land, and trains the skill. Pure logic, no GL.
 */
public final class Workshop {
    private Workshop() {}

    /** Why the actor cannot make it now, or null if it can. {@code power}: mechanical work available here. */
    public static String missing(Actor a, RecipeBook.Process p, double power) {
        RecipeBook.Rules r = p.rules();
        if (a.skills.level(r.skill()) < r.level()) return r.skill().displayName + " niveau " + r.level();
        if (r.tool() != Items.TOOL_NONE && a.inventory.findTool(r.tool()) < 0) return "outil : " + Items.TOOL_NAMES[r.tool()];
        for (RecipeBook.Input in : p.inputs()) if (available(a.inventory, in.ingredient()) < in.count()) return "ingrédients";
        if (r.energy().equals("heat") && fuel(a.inventory) < r.energyAmount()) return "combustible";
        return null;
    }

    /** Seconds it takes this actor: the recipe's time over its efficiency, three times longer by hand than by a wheel. */
    public static double seconds(Actor a, RecipeBook.Process p, double power) {
        RecipeBook.Rules r = p.rules();
        double t = r.seconds() / a.efficiency(r.skill());
        if (r.energy().equals("work") && power < r.energyAmount()) t *= 3;
        return t;
    }

    /** Makes it once; returns false (and changes nothing) if something is missing. */
    public static boolean make(Actor a, RecipeBook.Process p, double power, Flows flows) {
        if (missing(a, p, power) != null) return false;
        RecipeBook.Rules r = p.rules();
        Inventory inv = a.inventory;
        for (RecipeBook.Input in : p.inputs()) take(inv, in.ingredient(), in.count());
        if (r.energy().equals("heat")) {
            double burnt = burn(inv, r.energyAmount());
            if (flows != null) flows.give(Matter.ASH, 0.1 * burnt, a.x, a.z);
        } else if (r.energy().equals("work") && power < r.energyAmount()) {
            a.needs.rest = Math.max(0, a.needs.rest - 0.01 * r.energyAmount()); // turning the millstone by hand
        }
        if (r.tool() != Items.TOOL_NONE) inv.wearOut(inv.findTool(r.tool()));
        for (RecipeBook.Stack s : p.outputs()) inv.add(s.item(), s.count());
        if (flows != null) for (RecipeBook.Waste w : r.waste()) flows.give(w.matter(), w.amount(), a.x, a.z);
        a.skills.practice(r.skill(), 1 + r.level() + r.seconds() / 4);
        return true;
    }

    static int available(Inventory inv, RecipeBook.Ingredient ing) {
        int n = 0;
        for (int i = 0; i < Inventory.SIZE; i++) if (inv.counts[i] > 0 && ing.test(inv.ids[i])) n += inv.counts[i];
        return n;
    }

    private static void take(Inventory inv, RecipeBook.Ingredient ing, int n) {
        for (int i = Inventory.SIZE - 1; i >= 0 && n > 0; i--) {
            if (inv.counts[i] <= 0 || !ing.test(inv.ids[i])) continue;
            int t = Math.min(n, inv.counts[i]);
            inv.remove(inv.ids[i], t);
            n -= t;
        }
    }

    public static double fuel(Inventory inv) {
        double f = 0;
        for (int i = 0; i < Inventory.SIZE; i++) if (inv.counts[i] > 0) f += Items.FUEL[inv.ids[i]] * inv.counts[i];
        return f;
    }

    /** Burns fuel items, smallest first, until {@code need} heat is given; returns the heat burnt. */
    private static double burn(Inventory inv, double need) {
        double burnt = 0;
        while (burnt < need) {
            int best = -1;
            for (int i = 0; i < Inventory.SIZE; i++)
                if (inv.counts[i] > 0 && Items.FUEL[inv.ids[i]] > 0 && (best < 0 || Items.FUEL[inv.ids[i]] < Items.FUEL[inv.ids[best]])) best = i;
            if (best < 0) break;
            burnt += Items.FUEL[inv.ids[best]];
            inv.takeOne(best);
        }
        return burnt;
    }
}
