package net.mynecraft.world;

/**
 * Two-block doors: a lower and an upper half that open, close, are set and broken together.
 * Every change returns the cells it touched (for redrawing). A lone half (an old save, a gate in a
 * fence line) works on its own.
 */
public final class Doors {
    private Doors() {}

    /** The y of the other half of the door half at (x, y, z), or Integer.MIN_VALUE if it stands alone. */
    public static int otherY(World w, int x, int y, int z) {
        short b = w.get(x, y, z);
        if (!Blocks.isDoor(b)) return Integer.MIN_VALUE;
        int oy = Blocks.UPPER[b & 0xFFFF] ? y - 1 : y + 1;
        short o = w.get(x, oy, z);
        return Blocks.isDoor(o) && Blocks.UPPER[o & 0xFFFF] != Blocks.UPPER[b & 0xFFFF] ? oy : Integer.MIN_VALUE;
    }

    /** Opens or closes the door at (x, y, z), both halves. Returns the cells changed (none if already so). */
    public static int[][] setOpen(World w, int x, int y, int z, boolean open) {
        short b = w.get(x, y, z);
        if (!Blocks.isDoor(b)) return new int[0][];
        int oy = otherY(w, x, y, z);
        boolean changed = w.set(x, y, z, Blocks.withOpen(b, open));
        if (oy == Integer.MIN_VALUE) return changed ? new int[][] {{x, y, z}} : new int[0][];
        w.set(x, oy, z, Blocks.withOpen(w.get(x, oy, z), open));
        return changed ? new int[][] {{x, y, z}, {x, oy, z}} : new int[0][];
    }

    /** Opens a closed door and closes an open one (both halves). */
    public static int[][] toggle(World w, int x, int y, int z) {
        short b = w.get(x, y, z);
        return Blocks.isDoor(b) ? setOpen(w, x, y, z, Blocks.isClosedDoor(b)) : new int[0][];
    }

    /**
     * Sets a whole door, its lower half at (x, y, z) and its upper half above (if there is room: else a
     * lone low door, a gate). Returns whether the lower half was set.
     */
    public static boolean place(World w, int x, int y, int z, boolean alongZ, boolean open) {
        if (!w.set(x, y, z, Blocks.door(false, alongZ, open))) return false;
        short above = w.get(x, y + 1, z);
        if (above == Blocks.AIR || above == Blocks.SHORT_GRASS || Blocks.isFluid(above))
            w.set(x, y + 1, z, Blocks.door(true, alongZ, open));
        return true;
    }

    /** After the half at (x, y, z) was broken (it was {@code was}): the other half goes too. Its y, or MIN_VALUE. */
    public static int broke(World w, int x, int y, int z, short was) {
        if (!Blocks.isDoor(was)) return Integer.MIN_VALUE;
        int oy = Blocks.UPPER[was & 0xFFFF] ? y - 1 : y + 1;
        short o = w.get(x, oy, z);
        if (!Blocks.isDoor(o) || Blocks.UPPER[o & 0xFFFF] == Blocks.UPPER[was & 0xFFFF]) return Integer.MIN_VALUE;
        w.set(x, oy, z, Blocks.AIR);
        return oy;
    }
}
