package net.mynecraft.world.actor;

import net.mynecraft.world.Items;

/**
 * The body's needs: hunger and thirst (0..20, 20 = sated), rest (0..1) and warmth (0 frozen, 0.5
 * comfortable, 1 overheated). They move with time and effort at a pace set by {@link #rate} (1 normal,
 * 0.5 slow, 0 off), warmth follows the felt temperature (shelter and a fire warm, see actor.Wellbeing).
 * Varied meals nourish more: a dish eaten again and again gives less, a food group missing from the
 * last meals gives more.
 */
public final class Needs {
    public static final int MAX = 20, MEMORY = 10;

    public double food = MAX, water = MAX, rest = 1, warmth = 0.5;
    /** Pace of the needs: 1 normal, 0.5 slow, 0 off (they stay full). */
    public double rate = 1;
    /** Last meals, newest last (item ids). */
    public final java.util.ArrayDeque<Short> meals = new java.util.ArrayDeque<>();
    private double hurtTimer;

    /**
     * Advances the needs by {@code dt} seconds. {@code effort} 0 (still) .. 1 (hard work, sprint),
     * {@code felt} the felt temperature in deg C, {@code sleeping} in a bed. Returns the damage the
     * body takes (half-hearts): starving, parched, freezing or overheating.
     */
    public int tick(double dt, double effort, double felt, boolean sleeping) {
        if (rate <= 0) {
            food = water = MAX;
            rest = 1;
            warmth = 0.5;
            return 0;
        }
        double r = dt * rate;
        double hot = Math.max(0, warmth - 0.6);
        food = Math.max(0, food - r * (0.004 + 0.012 * effort));
        water = Math.max(0, water - r * (0.006 + 0.012 * effort + 0.03 * hot));
        if (sleeping) rest = Math.min(1, rest + dt * 0.02);
        else if (effort < 0.05) rest = Math.min(1, rest + dt * 0.002);
        else rest = Math.max(0, rest - r * (0.0002 + 0.0008 * effort));
        // The body's warmth drifts toward what the felt temperature allows; 8..26 deg C is comfortable.
        double target = felt >= 8 && felt <= 26 ? 0.5 : felt < 8 ? 0.5 * Math.max(0, (felt + 22) / 30) : 0.5 + 0.5 * Math.min(1, (felt - 26) / 20);
        warmth += (target - warmth) * Math.min(1, r * (0.01 + 0.02 * effort * (target > warmth ? 1 : 0)));
        boolean suffering = food <= 0 || water <= 0 || warmth < 0.08 || warmth > 0.92;
        if (!suffering) { hurtTimer = 0; return 0; }
        hurtTimer += dt;
        if (hurtTimer < 6) return 0;
        hurtTimer = 0;
        return 1;
    }

    /** 0..1: how fit the body is for work (tired, hungry, thirsty, cold slow every gesture). */
    public double condition() {
        double cold = Math.max(0, Math.abs(warmth - 0.5) - 0.15) / 0.35;
        return Math.min(1, food / 8) * Math.min(1, water / 8) * (0.4 + 0.6 * rest) * (1 - 0.6 * Math.min(1, cold));
    }

    /** Hunger points a food would give now, with the variety of the last meals. */
    public double nourishment(short item) {
        int base = Items.NOURISHMENT[item & 0xFFFF];
        if (base <= 0) return 0;
        int same = 0, sameGroup = 0;
        for (short m : meals) {
            if (m == item) same++;
            if (Items.FOOD_GROUP[m & 0xFFFF] == Items.FOOD_GROUP[item & 0xFFFF]) sameGroup++;
        }
        double f = 1.3 - 0.12 * same - 0.04 * sameGroup;
        return base * Math.max(0.35, Math.min(1.3, f));
    }

    /** Eats one food; returns the points gained (0 if not food). */
    public double eat(short item) {
        double n = nourishment(item);
        if (n <= 0) return 0;
        food = Math.min(MAX, food + n);
        water = Math.min(MAX, water + Items.THIRST[item & 0xFFFF]);
        meals.addLast(item);
        while (meals.size() > MEMORY) meals.removeFirst();
        return n;
    }

    public void drink(double amount) {
        water = Math.min(MAX, water + amount);
    }

    /** 0..1: food groups among the last meals (5 groups). */
    public double variety() {
        java.util.Set<Byte> groups = new java.util.HashSet<>();
        for (short m : meals) groups.add(Items.FOOD_GROUP[m & 0xFFFF]);
        return Math.min(1, groups.size() / 4.0);
    }

    /** 0..1: needs met (for the harmony with oneself). */
    public double satisfaction() {
        return (food / MAX + water / MAX + rest + (1 - 2 * Math.abs(warmth - 0.5))) / 4;
    }
}
