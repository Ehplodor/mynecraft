package net.mynecraft.world.people;

import net.mynecraft.world.Inventory;
import net.mynecraft.world.actor.Actor;
import net.mynecraft.world.actor.Job;

import java.util.ArrayList;
import java.util.List;

/**
 * An inhabitant (M31): an actor like the player (same needs, skills, inventory, recipes, flows),
 * with a trade it practises, a home, a day: asleep at night, at home when it rains, at work in the
 * day. It walks the routes of {@link PathFinder}.
 */
public final class Villager extends Actor {
    public enum State { IDLE, WALK, WORK, SLEEP }

    public Job job;
    public State state = State.IDLE;
    /** Where the work happens now (a bush, the bank, the building site). */
    public int[] workAt;
    public final List<int[]> path = new ArrayList<>();
    public double stateTime, workTimer;
    public float yaw, gait;
    /** Shirt and trousers colours (0xRRGGBB), and a child is smaller. */
    public int shirt, trousers;
    public boolean child;
    /** What it said last, and until when it is shown above it (seconds of hamlet time). */
    public String saying = "";
    public double sayingUntil;
    /** Hamlet time of the last talk with the player (once a day a lesson). */
    public double lastLesson = -10;

    public Villager(String name, Job job) {
        super(name, new Inventory());
        this.job = job;
    }

    public double height() {
        return child ? 1.2 : 1.8;
    }

    /** Ray against its standing box; distance or -1. */
    public double rayHit(double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        double hw = child ? 0.25 : 0.3;
        double[] lo = {x - hw, y, z - hw}, hi = {x + hw, y + height(), z + hw}, o = {ox, oy, oz}, d = {dx, dy, dz};
        double t0 = 0, t1 = max;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) { if (o[i] < lo[i] || o[i] > hi[i]) return -1; continue; }
            double a = (lo[i] - o[i]) / d[i], b = (hi[i] - o[i]) / d[i];
            if (a > b) { double t = a; a = b; b = t; }
            t0 = Math.max(t0, a);
            t1 = Math.min(t1, b);
            if (t0 > t1) return -1;
        }
        return t0;
    }
}
