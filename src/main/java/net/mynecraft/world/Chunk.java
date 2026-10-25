package net.mynecraft.world;

/** Vertical column of sections. Empty sections stay null to save memory and meshing time. */
public final class Chunk {
    public static final int SECTIONS = 8;
    public static final int HEIGHT = SECTIONS * Section.SIZE;

    public final int cx, cz;
    final Section[] sections = new Section[SECTIONS];
    /**
     * Light per section, one byte per block: sky &lt;&lt; 4 | block. A null array means full sky light
     * and no block light, which is what most air above the terrain has, so it costs nothing.
     */
    final byte[][] light = new byte[SECTIONS][];
    public static final int DEFAULT_LIGHT = 0xF0;
    /** Set when modified since load/generation; only dirty chunks are written (generation is deterministic). */
    private volatile boolean dirty;

    public Chunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
    }

    public Section section(int sy) {
        return sections[sy];
    }

    public byte get(int x, int y, int z) {
        Section s = sections[y >> 4];
        return s == null ? Blocks.AIR : s.get(x, y & 15, z);
    }

    public void set(int x, int y, int z, byte id) {
        Section s = sections[y >> 4];
        if (s == null) {
            if (id == Blocks.AIR) return;
            s = sections[y >> 4] = new Section();
        }
        s.set(x, y & 15, z, id);
    }

    public int getLight(int x, int y, int z) {
        byte[] l = light[y >> 4];
        return l == null ? DEFAULT_LIGHT : l[Section.index(x, y & 15, z)] & 0xFF;
    }

    public void setLight(int x, int y, int z, int value) {
        byte[] l = light[y >> 4];
        if (l == null) {
            if (value == DEFAULT_LIGHT) return;
            l = light[y >> 4] = new byte[Section.VOLUME];
            java.util.Arrays.fill(l, (byte) DEFAULT_LIGHT);
        }
        l[Section.index(x, y & 15, z)] = (byte) value;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        dirty = true;
    }

    public void markClean() {
        dirty = false;
    }

    /** Drops sections that ended up all air. */
    public void compact() {
        for (int i = 0; i < SECTIONS; i++) {
            if (sections[i] != null && sections[i].isEmpty()) sections[i] = null;
        }
    }
}
