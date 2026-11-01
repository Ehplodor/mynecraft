package net.mynecraft.world;

/**
 * Vanilla-like placement rules: block state from the clicked face and the player's position (logs,
 * roofs, stairs, windows, chairs, two-block doors), slabs merging into doubles.
 */
public final class Placement {
    private Placement() {}

    /** Cells a placed block may overwrite. */
    private static boolean replaceable(short b) {
        return b == Blocks.AIR || Blocks.isFluid(b) || b == Blocks.SHORT_GRASS;
    }

    /**
     * Places {@code selected} against the clicked face. {@code hitY} is the height (0..1) of the
     * aimed point within the clicked block, used for slabs on side faces.
     *
     * @return the changed position as {x, y, z}, or null if nothing was placed.
     */
    public static int[] place(World world, Player player, Raycast.Hit hit, double hitY, short selected) {
        short clicked = world.get(hit.x(), hit.y(), hit.z());
        if (Blocks.isSlab(selected)) {
            // Clicking the open face of a slab completes it.
            if ((clicked == Blocks.OAK_SLAB && hit.ny() == 1) || (clicked == Blocks.OAK_SLAB_TOP && hit.ny() == -1)) {
                return world.set(hit.x(), hit.y(), hit.z(), Blocks.OAK_SLAB_DOUBLE) ? new int[] {hit.x(), hit.y(), hit.z()} : null;
            }
        }
        int px = hit.x() + hit.nx(), py = hit.y() + hit.ny(), pz = hit.z() + hit.nz();
        short there = world.get(px, py, pz);
        short state = selected;
        if (Blocks.isSlab(selected)) {
            boolean top = hit.ny() == -1 || (hit.ny() == 0 && hitY > 0.5);
            state = top ? Blocks.OAK_SLAB_TOP : Blocks.OAK_SLAB;
            // The other half of an existing slab: merge into a double slab.
            if ((there == Blocks.OAK_SLAB && top) || (there == Blocks.OAK_SLAB_TOP && !top)) {
                state = Blocks.OAK_SLAB_DOUBLE;
                there = Blocks.AIR;
            }
        } else if (Blocks.SHAPE[selected & 0xFFFF] == Blocks.SHAPE_SLOPE) {
            // Roofs rise away from the player, like vanilla stairs.
            int base = selected - Blocks.FACING[selected & 0xFFFF];
            state = (short) (base + away(player, px, pz));
        } else if (Blocks.SHAPE[selected & 0xFFFF] == Blocks.SHAPE_STAIRS) {
            // Stairs climb away from the player; against a ceiling or the upper half of a side they hang
            // upside down (vanilla).
            boolean upper = hit.ny() == -1 || (hit.ny() == 0 && hitY > 0.5);
            state = Blocks.stair(selected, away(player, px, pz), upper);
        } else if (Blocks.SHAPE[selected & 0xFFFF] == Blocks.SHAPE_WINDOW) {
            // The pane faces the player: it runs across the line of sight.
            int base = selected - Blocks.FACING[selected & 0xFFFF];
            state = (short) (base + (away(player, px, pz) < 2 ? 0 : 1));
        } else if (Blocks.isChair(selected)) {
            state = (short) (Blocks.OAK_CHAIR + away(player, px, pz)); // its back away from the player
        } else if (Blocks.isDoor(selected)) {
            // A door: its leaf across the line of sight, the upper half set with it (if there is room).
            if (!replaceable(there) || player.intersects(px, py, pz) || player.intersects(px, py + 1, pz)) return null;
            return Doors.place(world, px, py, pz, away(player, px, pz) >= 2, false) ? new int[] {px, py, pz} : null;
        } else if (Blocks.logStates(selected) != null) {
            short[] axes = Blocks.logStates(selected); // y, x, z
            state = hit.nx() != 0 ? axes[1] : hit.nz() != 0 ? axes[2] : axes[0];
        }
        if (!replaceable(there)) return null;
        if (Blocks.isSolid(state) && player.intersects(px, py, pz)) return null;
        return world.set(px, py, pz, state) ? new int[] {px, py, pz} : null;
    }

    /** Horizontal direction from the player to the cell (0 north, 1 south, 2 west, 3 east), as Blocks.FACING. */
    private static int away(Player player, int px, int pz) {
        double dx = px + 0.5 - player.x, dz = pz + 0.5 - player.z;
        return Math.abs(dz) >= Math.abs(dx) ? (dz < 0 ? 0 : 1) : (dx < 0 ? 2 : 3);
    }
}
