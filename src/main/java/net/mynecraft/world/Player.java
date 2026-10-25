package net.mynecraft.world;

/**
 * Player body: axis-aligned box, feet at (x, y, z). Movement is swept one axis at a time against
 * the collision boxes of nearby blocks (full cubes, slabs...), which is exact and allocation-free.
 * Walking into a ledge up to {@link #STEP_HEIGHT} high climbs it, like vanilla.
 */
public final class Player {
    public static final double HALF_WIDTH = 0.3, HEIGHT = 1.8, EYE_HEIGHT = 1.62, STEP_HEIGHT = 0.6;
    public static final double GRAVITY = 32, JUMP_VELOCITY = 9, TERMINAL_VELOCITY = 78;
    private static final double EPS = 1e-7;

    public double x, y, z;
    public double vx, vy, vz;
    public boolean onGround, flying;
    /** Fluid the body is in (Blocks.FLUID_*), updated every step. */
    public byte fluid;

    /** Integrates one step. Horizontal velocity is set by the controller; gravity is applied here. */
    public void step(World world, double dt) {
        fluid = fluidAround(world);
        if (flying) {
            // no gravity
        } else if (fluid != Blocks.FLUID_NONE) {
            // Swimming: weak gravity and strong drag, sinking slowly (more slowly still in lava).
            double drag = fluid == Blocks.FLUID_LAVA ? 6 : 4;
            vy -= GRAVITY * 0.15 * dt;
            vy -= vy * Math.min(1, drag * dt);
            vy = Math.max(vy, -3);
        } else {
            vy = Math.max(-TERMINAL_VELOCITY, vy - GRAVITY * dt);
        }
        boolean wasOnGround = onGround;
        double dy = vy * dt, dx = vx * dt, dz = vz * dt;
        double my = sweep(world, 1, dy);
        onGround = dy < 0 && my > dy;
        if (my != dy) vy = 0;
        double x0 = x, z0 = z;
        double mx = sweep(world, 0, dx), mz = sweep(world, 2, dz);
        if ((mx != dx || mz != dz) && (wasOnGround || onGround) && !flying) stepUp(world, dx - mx, dz - mz);
        // Whatever could not be travelled was blocked: stop that velocity component.
        if (Math.abs(x - x0 - dx) > EPS) vx = 0;
        if (Math.abs(z - z0 - dz) > EPS) vz = 0;
    }

    /** Retries the blocked rest of a horizontal move from up to STEP_HEIGHT higher; undone if it gains nothing. */
    private void stepUp(World world, double restX, double restZ) {
        double sx = x, sy = y, sz = z;
        double up = sweep(world, 1, STEP_HEIGHT);
        double ax = sweep(world, 0, restX), az = sweep(world, 2, restZ);
        sweep(world, 1, -up);
        if (Math.abs(ax) + Math.abs(az) > EPS) {
            onGround = true;
        } else {
            x = sx; y = sy; z = sz;
        }
    }

    public void jump() {
        if (fluid != Blocks.FLUID_NONE) vy = fluid == Blocks.FLUID_LAVA ? 2 : 3.5; // swim up
        else if (onGround) { vy = JUMP_VELOCITY; onGround = false; }
    }

    /** The fluid touching the body (feet to head), water first; FLUID_NONE if dry. */
    private byte fluidAround(World w) {
        byte found = Blocks.FLUID_NONE;
        for (int by = fl(y); by <= fl(y + HEIGHT - EPS); by++)
            for (int bz = fl(z - HALF_WIDTH); bz <= fl(z + HALF_WIDTH); bz++)
                for (int bx = fl(x - HALF_WIDTH); bx <= fl(x + HALF_WIDTH); bx++) {
                    byte t = Blocks.FLUID[w.get(bx, by, bz) & 0xFF];
                    if (t == Blocks.FLUID_WATER) return t;
                    if (t != Blocks.FLUID_NONE) found = t;
                }
        return found;
    }

    /** True if the box overlaps block (bx, by, bz); used to forbid placing a block inside the player. */
    public boolean intersects(int bx, int by, int bz) {
        return x + HALF_WIDTH > bx && x - HALF_WIDTH < bx + 1
            && y + HEIGHT > by && y < by + 1
            && z + HALF_WIDTH > bz && z - HALF_WIDTH < bz + 1;
    }

    /**
     * Moves along one axis (0 x, 1 y, 2 z) by at most d, stopping at the first collision box in the
     * way. Returns the distance actually moved.
     */
    private double sweep(World w, int axis, double d) {
        if (d == 0) return 0;
        double[] min = {x - HALF_WIDTH, y, z - HALF_WIDTH};
        double[] max = {x + HALF_WIDTH, y + HEIGHT, z + HALF_WIDTH};
        // Blocks overlapped by the box swept along the axis (one extra below: slabs end inside a cell).
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
                    double[] bmin = {bx, by + (box & 0xFF) / 16.0, bz};
                    double[] bmax = {bx + 1, by + (box >> 8) / 16.0, bz + 1};
                    boolean overlaps = true;
                    for (int a = 0; a < 3 && overlaps; a++) {
                        if (a != axis) overlaps = bmax[a] > min[a] + EPS && bmin[a] < max[a] - EPS;
                    }
                    if (!overlaps) continue;
                    if (d > 0 && bmin[axis] >= max[axis] - EPS) d = Math.min(d, bmin[axis] - max[axis]);
                    else if (d < 0 && bmax[axis] <= min[axis] + EPS) d = Math.max(d, bmax[axis] - min[axis]);
                }
        if (axis == 0) x += d; else if (axis == 1) y += d; else z += d;
        return d;
    }

    private static int fl(double v) {
        return (int) Math.floor(v);
    }
}
