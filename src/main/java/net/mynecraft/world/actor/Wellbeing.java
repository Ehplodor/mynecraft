package net.mynecraft.world.actor;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.World;

/**
 * How the place where an actor stands feels (M28, the architecture that counts of M30): shelter (a
 * roof overhead, walls around), a fire nearby, a bed, beauty (flowers, cloth, pots, paintings,
 * statues), and the felt temperature that warmth follows. Measured from the blocks around, a few
 * thousand reads, every couple of seconds.
 */
public record Wellbeing(double roof, double walls, double fire, boolean bed, double beauty) {
    public static final Wellbeing NONE = new Wellbeing(0, 0, 0, false, 0);

    public static Wellbeing measure(World w, double px, double py, double pz) {
        int x = (int) Math.floor(px), y = (int) Math.floor(py + 1), z = (int) Math.floor(pz);
        // Roof: five rays up from around the head; walls: eight rays across at waist height.
        int roofed = 0;
        int[][] around = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int[] o : around)
            for (int dy = 1; dy <= 16; dy++)
                if (Blocks.isOpaque(w.get(x + o[0], y + dy, z + o[1])) || Blocks.isSlab(w.get(x + o[0], y + dy, z + o[1]))
                    || w.get(x + o[0], y + dy, z + o[1]) == Blocks.THATCH) { roofed++; break; }
        int walled = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] d : dirs)
            for (int s = 1; s <= 8; s++) {
                short b = w.get(x + d[0] * s, y - 1, z + d[1] * s);
                if (Blocks.isSolid(b) || b == Blocks.GLASS || b == Blocks.OAK_DOOR_OPEN) { walled++; break; }
            }
        double fire = 0, beauty = 0;
        boolean bed = false;
        for (int dy = -2; dy <= 3; dy++)
            for (int dz = -5; dz <= 5; dz++)
                for (int dx = -5; dx <= 5; dx++) {
                    short b = w.get(x + dx, y + dy, z + dz);
                    if (b == Blocks.AIR) continue;
                    beauty += Blocks.BEAUTY[b & 0xFFFF];
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (b == Blocks.CAMPFIRE || b == Blocks.FURNACE || Blocks.FLUID[b & 0xFFFF] == Blocks.FLUID_LAVA)
                        fire = Math.max(fire, Math.max(0, 1 - d / 6));
                    if (b == Blocks.STRAW_BED && d < 4) bed = true;
                }
        return new Wellbeing(roofed / 5.0, walled / 8.0, fire, bed, Math.min(1, beauty / 24));
    }

    public double shelter() {
        return 0.6 * roof + 0.4 * walls;
    }

    /** Felt temperature from the air's: a shelter keeps some warmth in, a fire warms, rain in the open chills. */
    public double felt(double air, boolean raining) {
        double t = air + shelter() * 6 + fire * 16;
        if (raining && roof < 0.5) t -= 4;
        return t;
    }

    /** 0..1: a bed, a fire and a roof make a place comfortable. */
    public double comfort() {
        return Math.min(1, (bed ? 0.4 : 0) + 0.3 * fire + 0.3 * shelter());
    }
}
