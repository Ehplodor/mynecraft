package net.mynecraft.world;

/** Voxel traversal (Amanatides &amp; Woo DDA): visits every block the ray crosses, in order. */
public final class Raycast {
    /** Hit block and the normal of the face that was entered (where a placed block goes). */
    public record Hit(int x, int y, int z, int nx, int ny, int nz) {}

    private Raycast() {}

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
            if (Blocks.isOpaque(world.get(x, y, z))) return new Hit(x, y, z, nx, ny, nz);
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
