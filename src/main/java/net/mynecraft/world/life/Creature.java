package net.mynecraft.world.life;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.World;

/**
 * One animal: an axis-aligned body (feet at x, y, z) moved like the player's, what it is doing,
 * its needs, and the state its animation is drawn from.
 */
public final class Creature {
    public enum State { IDLE, WANDER, GRAZE, FLEE, CHASE, SLEEP, FLY, FISH, FOLLOW }

    private static final double EPS = 1e-7, GRAVITY = 28, STEP = 0.6;

    public final Species species;
    public final int id;
    public double x, y, z, vx, vy, vz;
    /** Facing (radians, 0 = +z), turning smoothly toward {@link #targetYaw}. */
    public float yaw, targetYaw;
    /** Head turn and nod relative to the body (radians). */
    public float headYaw, headPitch;
    /** Whole-body pitch (fish diving, a flyer climbing) and roll (a flyer banking into a turn). */
    public float pitch, bank;
    /** Seconds out of water (fish) or out of its season (night/day species fading away). */
    public double stranded;
    public boolean onGround, inWater;
    public State state = State.IDLE;
    /** Seconds left in the current state, and since birth. */
    public double stateTime, age;
    /** 0 fed .. 1 starving. */
    public double hunger;
    public float health;
    /** Coat colour (0xRRGGBB) and adult size, chosen at birth; scale = size while growing up. */
    public int coat;
    public float scale = 1, size = 1;
    /** Walk cycle (radians, advances with distance) and a running clock for idle motion. */
    public float gait, clock;
    /** How fast it moved last step (blocks/s), for the gait's amplitude. */
    public float speed;
    /** Seconds until it may breed again. */
    public double breedCooldown;
    /** Where it wants to go, and the creature it follows, chases or flees (null: none). */
    public double goalX, goalY, goalZ;
    public Creature other;
    /** Flinch timer after a hit (red flash), and 1 while dying (removed at 0 after fading). */
    public float hurt, dying;
    public boolean dead;

    Creature(Species species, int id, double x, double y, double z) {
        this.species = species;
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.health = species.health;
        goalX = x;
        goalY = y;
        goalZ = z;
    }

    public double halfWidth() {
        return species.halfWidth * scale;
    }

    public double height() {
        return species.height * scale;
    }

    public boolean adult() {
        return species.adultAge <= 0 || age >= species.adultAge;
    }

    /** Integrates one step with the body colliding against blocks; {@code gravity} off for flyers and swimmers. */
    void move(World w, double dt, boolean gravity) {
        if (gravity) vy = Math.max(-40, vy - GRAVITY * dt);
        boolean wasOnGround = onGround;
        double dx = vx * dt, dy = vy * dt, dz = vz * dt;
        double my = sweep(w, 1, dy);
        onGround = dy < 0 && my > dy;
        if (my != dy) vy = 0;
        double mx = sweep(w, 0, dx), mz = sweep(w, 2, dz);
        if ((mx != dx || mz != dz) && (wasOnGround || onGround) && gravity) {
            // A ledge: climb it if low, else the caller will jump or turn.
            double sx = x, sy = y, sz = z;
            double up = sweep(w, 1, STEP);
            double ax = sweep(w, 0, dx - mx), az = sweep(w, 2, dz - mz);
            sweep(w, 1, -up);
            if (Math.abs(ax) + Math.abs(az) < EPS) { x = sx; y = sy; z = sz; } else { mx += ax; mz += az; }
        }
        speed = (float) (Math.hypot(mx, mz) / Math.max(dt, 1e-6));
        blocked = Math.abs(mx - dx) > 1e-4 || Math.abs(mz - dz) > 1e-4;
        if (Math.abs(mx - dx) > EPS) vx = 0;
        if (Math.abs(mz - dz) > EPS) vz = 0;
        byte at = w.get(fl(x), fl(y + height() * 0.4), fl(z));
        inWater = Blocks.FLUID[at & 0xFF] == Blocks.FLUID_WATER;
    }

    /** True when the last move was stopped by a wall. */
    public boolean blocked;

    private double sweep(World w, int axis, double d) {
        if (d == 0) return 0;
        double hw = halfWidth(), hh = height();
        double[] min = {x - hw, y, z - hw};
        double[] max = {x + hw, y + hh, z + hw};
        double[] lo = min.clone(), hi = max.clone();
        if (d < 0) lo[axis] += d; else hi[axis] += d;
        int x0 = fl(lo[0] + EPS), x1 = fl(hi[0] - EPS);
        int y0 = fl(lo[1] + EPS) - 1, y1 = fl(hi[1] - EPS);
        int z0 = fl(lo[2] + EPS), z1 = fl(hi[2] - EPS);
        for (int by = y0; by <= y1; by++)
            for (int bz = z0; bz <= z1; bz++)
                for (int bx = x0; bx <= x1; bx++) {
                    int box = w.collisionBox(bx, by, bz);
                    if (box < 0) continue;
                    double bminY = by + (box & 0xFF) / 16.0, bmaxY = by + (box >> 8) / 16.0;
                    double[] bmin = {bx, bminY, bz}, bmax = {bx + 1, bmaxY, bz + 1};
                    boolean overlaps = true;
                    for (int a = 0; a < 3 && overlaps; a++)
                        if (a != axis) overlaps = bmax[a] > min[a] + EPS && bmin[a] < max[a] - EPS;
                    if (!overlaps) continue;
                    if (d > 0 && bmin[axis] >= max[axis] - EPS) d = Math.min(d, bmin[axis] - max[axis]);
                    else if (d < 0 && bmax[axis] <= min[axis] + EPS) d = Math.max(d, bmax[axis] - min[axis]);
                }
        if (axis == 0) x += d; else if (axis == 1) y += d; else z += d;
        return d;
    }

    /** Distance along the ray to the body's box, or -1 if missed. */
    public double rayHit(double ox, double oy, double oz, double dx, double dy, double dz, double maxDist) {
        double hw = halfWidth() + 0.1;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, min = {x - hw, y, z - hw}, max = {x + hw, y + height() + 0.1, z + hw};
        double near = 0, far = maxDist;
        for (int a = 0; a < 3; a++) {
            if (d[a] == 0) {
                if (o[a] < min[a] || o[a] > max[a]) return -1;
                continue;
            }
            double t1 = (min[a] - o[a]) / d[a], t2 = (max[a] - o[a]) / d[a];
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
            near = Math.max(near, t1);
            far = Math.min(far, t2);
            if (near > far) return -1;
        }
        return near;
    }

    static int fl(double v) {
        return (int) Math.floor(v);
    }
}
