package net.mynecraft.world;

/**
 * Survival rules applied every game tick (20 per second), vanilla-like without hunger:
 * fall damage (blocks fallen beyond 3, cancelled by landing in water), lava (4 every half
 * second), drowning (300 ticks of air, then 2 per second), regeneration (1 every 4 seconds).
 * Health is in half-hearts (20 = full).
 */
public final class Survival {
    public static final int MAX_HEALTH = 20, MAX_AIR = 300;
    private static final int LAVA_DAMAGE = 4, LAVA_INTERVAL = 10, DROWN_DAMAGE = 2, DROWN_INTERVAL = 20,
        REGEN_INTERVAL = 80;

    public int health = MAX_HEALTH, air = MAX_AIR;
    private double fallStart = Double.NaN;
    private int hurtCooldown, regenTimer, drownTimer;
    /** Set when health reaches 0; cleared by {@link #respawn}. */
    public boolean dead;
    /** Damage taken during the last tick (for a red flash in the HUD). */
    public int lastDamage;

    public void tick(Player p, World w, boolean creative) {
        lastDamage = 0;
        if (dead) return;
        if (creative || p.flying) {
            fallStart = Double.NaN;
            air = MAX_AIR;
            return;
        }
        // Fall damage: remember the highest point of each fall, settle on landing.
        if (p.fluid != Blocks.FLUID_NONE) {
            fallStart = Double.NaN; // water (and lava) cancel falls
        } else if (!p.onGround) {
            if (Double.isNaN(fallStart) || p.y > fallStart) fallStart = p.y;
        } else if (!Double.isNaN(fallStart)) {
            int damage = (int) Math.ceil(fallStart - p.y - 3);
            if (damage > 0) hurt(damage);
            fallStart = Double.NaN;
        }
        // Lava.
        if (hurtCooldown > 0) hurtCooldown--;
        if (p.fluid == Blocks.FLUID_LAVA && hurtCooldown == 0) {
            hurt(LAVA_DAMAGE);
            hurtCooldown = LAVA_INTERVAL;
        }
        // Drowning: air runs out while the head is under water.
        byte atEyes = w.get((int) Math.floor(p.x), (int) Math.floor(p.y + Player.EYE_HEIGHT), (int) Math.floor(p.z));
        if (Blocks.FLUID[atEyes & 0xFF] == Blocks.FLUID_WATER) {
            if (air > 0) air--;
            else if (++drownTimer >= DROWN_INTERVAL) { drownTimer = 0; hurt(DROWN_DAMAGE); }
        } else {
            air = Math.min(MAX_AIR, air + 5);
            drownTimer = 0;
        }
        // Slow natural regeneration.
        if (health < MAX_HEALTH && ++regenTimer >= REGEN_INTERVAL) {
            regenTimer = 0;
            health++;
        }
    }

    public void hurt(int damage) {
        if (dead) return;
        health = Math.max(0, health - damage);
        lastDamage += damage;
        if (health == 0) dead = true;
    }

    public void respawn() {
        health = MAX_HEALTH;
        air = MAX_AIR;
        dead = false;
        fallStart = Double.NaN;
    }
}
