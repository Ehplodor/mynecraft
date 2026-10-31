package net.mynecraft.world;

/**
 * Mechanical work available at a workshop (M33), from the machines within three blocks: a water
 * wheel turns with the river's real discharge where it touches moving water (the basins' flow,
 * see Hydrology), a windmill with the atmosphere's wind where it stands free above the ground.
 * In units a millstone or a sawmill asks for (2 to 3); without it, the arms do the work.
 */
public final class Power {
    private Power() {}

    /** Work units at (x, y, z); {@code wind} in blocks per second (the weather's wind there). */
    public static double at(World w, int x, int y, int z, double wind) {
        double p = 0;
        for (int dy = -3; dy <= 3; dy++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dx = -3; dx <= 3; dx++) {
                    short b = w.get(x + dx, y + dy, z + dz);
                    if (b == Blocks.WATER_WHEEL) p += wheel(w, x + dx, y + dy, z + dz);
                    else if (b == Blocks.WINDMILL) p += mill(w, x + dx, y + dy, z + dz, wind);
                }
        return p;
    }

    /** A water wheel: moving water beside or under it, more where the river carries more. */
    static double wheel(World w, int x, int y, int z) {
        int moving = 0, still = 0;
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};
        for (int[] o : around) {
            short b = w.get(x + o[0], y + o[1], z + o[2]);
            if (Blocks.FLUID[b & 0xFFFF] != Blocks.FLUID_WATER) continue;
            if (Blocks.LEVEL[b & 0xFFFF] > 0) moving++;
            else still++;
        }
        if (moving + still == 0) return 0;
        double flow = w.generator.landscape().hydrology().flow(x, z); // the basin's discharge here
        double river = Math.min(3, Math.log1p(Math.max(0, flow)) / 2);
        return moving > 0 ? 1.5 + river + moving * 0.5 : river; // a pond turns nothing, a river does
    }

    /** A windmill: the wind, if nothing stands within two blocks around its sails. */
    static double mill(World w, int x, int y, int z, double wind) {
        for (int dy = -1; dy <= 2; dy++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dx = -2; dx <= 2; dx++)
                    if ((dx != 0 || dz != 0 || dy > 0) && Blocks.isSolid(w.get(x + dx, y + dy, z + dz)) && dy >= 0) return 0;
        return Math.min(5, wind * 0.6);
    }
}
