package net.mynecraft.world;

/** Voxel traversal (Amanatides &amp; Woo DDA): visits every block the ray crosses, in order. */
public final class Raycast {
    /** Hit block and the normal of the face that was entered (where a placed block goes). */
    public record Hit(int x, int y, int z, int nx, int ny, int nz) {}

    private Raycast() {}

    /** Slab test of the ray against box [x, x+1] x [y+lo, y+hi] x [z, z+1]; normal of the entry face. */
    private static Hit box(double ox, double oy, double oz, double dx, double dy, double dz, double maxDist,
                           int x, int y, int z, double lo, double hi) {
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, min = {x, y + lo, z}, max = {x + 1, y + hi, z + 1};
        double tNear = 0, tFar = maxDist;
        int axis = -1, sign = 0;
        for (int a = 0; a < 3; a++) {
            if (d[a] == 0) {
                if (o[a] < min[a] || o[a] > max[a]) return null;
                continue;
            }
            double t1 = (min[a] - o[a]) / d[a], t2 = (max[a] - o[a]) / d[a];
            int s = -1; // entering through the min face: normal points to -axis
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; s = 1; }
            if (t1 > tNear) { tNear = t1; axis = a; sign = s; }
            tFar = Math.min(tFar, t2);
            if (tNear > tFar) return null;
        }
        if (axis < 0) return new Hit(x, y, z, 0, 1, 0); // ray starts inside the box
        return new Hit(x, y, z, axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0);
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
            byte b = world.get(x, y, z);
            if (Blocks.isTargetable(b)) {
                if (!Blocks.isSlab(b)) return new Hit(x, y, z, nx, ny, nz);
                // Partial block: the ray must actually cross its box, otherwise keep going.
                Hit partial = box(ox, oy, oz, dx, dy, dz, maxDist, x, y, z,
                    Blocks.BOX_MIN[b & 0xFF] / 16.0, Blocks.BOX_MAX[b & 0xFF] / 16.0);
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
