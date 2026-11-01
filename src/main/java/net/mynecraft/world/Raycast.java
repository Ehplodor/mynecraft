package net.mynecraft.world;

/** Voxel traversal (Amanatides &amp; Woo DDA): visits every block the ray crosses, in order. */
public final class Raycast {
    /** Hit block and the normal of the face that was entered (where a placed block goes). */
    public record Hit(int x, int y, int z, int nx, int ny, int nz) {}

    private Raycast() {}

    /**
     * Slab test of the ray against the box [min, max]: distance to its entry, or -1 if missed.
     * {@code normal} receives the entry face as {axis, sign} (axis -1: the ray starts inside).
     */
    private static double enter(double[] o, double[] d, double[] min, double[] max, double maxDist, int[] normal) {
        double tNear = 0, tFar = maxDist;
        int axis = -1, sign = 0;
        for (int a = 0; a < 3; a++) {
            if (d[a] == 0) {
                if (o[a] < min[a] || o[a] > max[a]) return -1;
                continue;
            }
            double t1 = (min[a] - o[a]) / d[a], t2 = (max[a] - o[a]) / d[a];
            int s = -1; // entering through the min face: normal points to -axis
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; s = 1; }
            if (t1 > tNear) { tNear = t1; axis = a; sign = s; }
            tFar = Math.min(tFar, t2);
            if (tNear > tFar) return -1;
        }
        normal[0] = axis;
        normal[1] = sign;
        return tNear;
    }

    /** The nearest of a partial block's boxes the ray crosses (slab, stair, wall, window), or null. */
    private static Hit partial(double ox, double oy, double oz, double dx, double dy, double dz, double maxDist,
                               int x, int y, int z, int[] boxes) {
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, min = new double[3], max = new double[3];
        int[] normal = new int[2];
        double best = Double.MAX_VALUE;
        Hit hit = null;
        for (int b : boxes) {
            min[0] = x + Shapes.min(b, 0) / 16.0; min[1] = y + Shapes.min(b, 1) / 16.0; min[2] = z + Shapes.min(b, 2) / 16.0;
            max[0] = x + Shapes.max(b, 0) / 16.0; max[1] = y + Shapes.max(b, 1) / 16.0; max[2] = z + Shapes.max(b, 2) / 16.0;
            double t = enter(o, d, min, max, maxDist, normal);
            if (t < 0 || t >= best) continue;
            best = t;
            int axis = normal[0], sign = normal[1];
            hit = axis < 0 ? new Hit(x, y, z, 0, 1, 0) // ray starts inside the box
                : new Hit(x, y, z, axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0);
        }
        return hit;
    }

    public static Hit cast(World world, double ox, double oy, double oz,
                           double dx, double dy, double dz, double maxDist) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = (int) Math.signum(dx), sy = (int) Math.signum(dy), sz = (int) Math.signum(dz);
        double tdx = sx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
        double tdy = sy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
        double tdz = sz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tmx = sx == 0 ? Double.MAX_VALUE : (sx > 0 ? x + 1 - ox : ox - x) * tdx;
        double tmy = sy == 0 ? Double.MAX_VALUE : (sy > 0 ? y + 1 - oy : oy - y) * tdy;
        double tmz = sz == 0 ? Double.MAX_VALUE : (sz > 0 ? z + 1 - oz : oz - z) * tdz;
        int nx = 0, ny = 0, nz = 0;
        double t = 0;
        while (t <= maxDist) {
            short b = world.get(x, y, z);
            if (Blocks.isTargetable(b)) {
                int[] boxes = Blocks.isSlab(b) ? Shapes.cell(b) : Blocks.hasBoxes(b) ? Shapes.at(world, x, y, z) : null;
                if (boxes == null) return new Hit(x, y, z, nx, ny, nz);
                // Partial block: the ray must actually cross one of its boxes, otherwise keep going.
                Hit partial = partial(ox, oy, oz, dx, dy, dz, maxDist, x, y, z, boxes);
                if (partial != null) return partial;
            }
            if (tmx < tmy && tmx < tmz) {
                x += sx; t = tmx; tmx += tdx; nx = -sx; ny = 0; nz = 0;
            } else if (tmy < tmz) {
                y += sy; t = tmy; tmy += tdy; nx = 0; ny = -sy; nz = 0;
            } else {
                z += sz; t = tmz; tmz += tdz; nx = 0; ny = 0; nz = -sz;
            }
        }
        return null;
    }
}
