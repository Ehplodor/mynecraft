package net.mynecraft.world;

/**
 * Player body: axis-aligned box, feet at (x, y, z). Movement is resolved one axis at a time
 * against the block grid, which is exact for voxel worlds and allocation-free.
 */
public final class Player {
    public static final double HALF_WIDTH = 0.3, HEIGHT = 1.8, EYE_HEIGHT = 1.62;
    public static final double GRAVITY = 32, JUMP_VELOCITY = 9, TERMINAL_VELOCITY = 78;
    private static final double EPS = 1e-4;

    public double x, y, z;
    public double vx, vy, vz;
    public boolean onGround, flying;

    /** Integrates one step. Horizontal velocity is set by the controller; gravity is applied here. */
    public void step(World world, double dt) {
        if (!flying) vy = Math.max(-TERMINAL_VELOCITY, vy - GRAVITY * dt);
        double dy = vy * dt;
        double my = moveY(world, dy);
        onGround = dy < 0 && my > dy;
        if (my != dy) vy = 0;
        if (moveX(world, vx * dt) != vx * dt) vx = 0;
        if (moveZ(world, vz * dt) != vz * dt) vz = 0;
    }

    public void jump() {
        if (onGround) { vy = JUMP_VELOCITY; onGround = false; }
    }

    /** True if the box overlaps block (bx, by, bz); used to forbid placing a block inside the player. */
    public boolean intersects(int bx, int by, int bz) {
        return x + HALF_WIDTH > bx && x - HALF_WIDTH < bx + 1
            && y + HEIGHT > by && y < by + 1
            && z + HALF_WIDTH > bz && z - HALF_WIDTH < bz + 1;
    }

    private double moveY(World w, double d) {
        if (d == 0) return 0;
        int x0 = fl(x - HALF_WIDTH + EPS), x1 = fl(x + HALF_WIDTH - EPS);
        int z0 = fl(z - HALF_WIDTH + EPS), z1 = fl(z + HALF_WIDTH - EPS);
        if (d < 0) {
            int from = fl(y - EPS), to = fl(y + d);
            for (int by = from; by >= to; by--)
                if (any(w, x0, x1, by, by, z0, z1)) { double m = by + 1 - y; y = by + 1; return m; }
        } else {
            int from = fl(y + HEIGHT + EPS), to = fl(y + HEIGHT + d);
            for (int by = from; by <= to; by++)
                if (any(w, x0, x1, by, by, z0, z1)) { double m = by - HEIGHT - y; y = by - HEIGHT; return m; }
        }
        y += d;
        return d;
    }

    private double moveX(World w, double d) {
        if (d == 0) return 0;
        int y0 = fl(y + EPS), y1 = fl(y + HEIGHT - EPS);
        int z0 = fl(z - HALF_WIDTH + EPS), z1 = fl(z + HALF_WIDTH - EPS);
        if (d < 0) {
            int from = fl(x - HALF_WIDTH - EPS), to = fl(x - HALF_WIDTH + d);
            for (int bx = from; bx >= to; bx--)
                if (any(w, bx, bx, y0, y1, z0, z1)) { double m = bx + 1 + HALF_WIDTH - x; x = bx + 1 + HALF_WIDTH; return m; }
        } else {
            int from = fl(x + HALF_WIDTH + EPS), to = fl(x + HALF_WIDTH + d);
            for (int bx = from; bx <= to; bx++)
                if (any(w, bx, bx, y0, y1, z0, z1)) { double m = bx - HALF_WIDTH - x; x = bx - HALF_WIDTH; return m; }
        }
        x += d;
        return d;
    }

    private double moveZ(World w, double d) {
        if (d == 0) return 0;
        int y0 = fl(y + EPS), y1 = fl(y + HEIGHT - EPS);
        int x0 = fl(x - HALF_WIDTH + EPS), x1 = fl(x + HALF_WIDTH - EPS);
        if (d < 0) {
            int from = fl(z - HALF_WIDTH - EPS), to = fl(z - HALF_WIDTH + d);
            for (int bz = from; bz >= to; bz--)
                if (any(w, x0, x1, y0, y1, bz, bz)) { double m = bz + 1 + HALF_WIDTH - z; z = bz + 1 + HALF_WIDTH; return m; }
        } else {
            int from = fl(z + HALF_WIDTH + EPS), to = fl(z + HALF_WIDTH + d);
            for (int bz = from; bz <= to; bz++)
                if (any(w, x0, x1, y0, y1, bz, bz)) { double m = bz - HALF_WIDTH - z; z = bz - HALF_WIDTH; return m; }
        }
        z += d;
        return d;
    }

    private static boolean any(World w, int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++)
                    if (w.isSolid(x, y, z)) return true;
        return false;
    }

    private static int fl(double v) {
        return (int) Math.floor(v);
    }
}
