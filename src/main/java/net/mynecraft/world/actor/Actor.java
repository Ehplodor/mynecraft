package net.mynecraft.world.actor;

import net.mynecraft.world.Inventory;

/**
 * Someone who lives in the world: the player or an inhabitant. Everything that makes, takes, eats,
 * learns or builds works on an actor, so the inhabitants follow the same rules as the player.
 */
public class Actor {
    public String name;
    public final Inventory inventory;
    public final Skills skills = new Skills();
    public final Needs needs = new Needs();
    /** Building plans this actor knows (by name, see build.Blueprint). */
    public final java.util.Set<String> plans = new java.util.TreeSet<>();
    /** Where the actor stands (feet), kept up to date by whoever moves it. */
    public double x, y, z;
    /** The hearth: the centre of one's territory (NaN until set). */
    public double homeX = Double.NaN, homeY, homeZ = Double.NaN;

    public Actor(String name, Inventory inventory) {
        this.name = name;
        this.inventory = inventory;
    }

    public boolean hasHome() {
        return !Double.isNaN(homeX);
    }

    public void setHome(double x, double y, double z) {
        homeX = x;
        homeY = y;
        homeZ = z;
    }

    /** How well a gesture of this skill goes now (see {@link Skills#efficiency}). */
    public double efficiency(Skill s) {
        return skills.efficiency(s, needs.condition());
    }
}
