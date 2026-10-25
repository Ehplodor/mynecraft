package net.mynecraft.world;

/** Vertical column of sections. Empty sections stay null to save memory and meshing time. */
public final class Chunk {
    /** Same vertical range as Java Edition 1.18+: y in [MIN_Y, MAX_Y). */
    public static final int MIN_Y = -64, MAX_Y = 320;
    public static final int HEIGHT = MAX_Y - MIN_Y;
    public static final int SECTIONS = HEIGHT / Section.SIZE;

    public final int cx, cz;
    /**
     * Fluids that must move as soon as the chunk joins the world (x, y, z world triples): springs,
     * water left hanging by carvers, flowing water saved mid-flow. Handed to the simulation once.
     */
    public volatile int[] unsettledFluids;
    final Section[] sections = new Section[SECTIONS];
    /**
     * Light per section, one byte per block: sky &lt;&lt; 4 | block. A null array means full sky light
     * and no block light, which is what most air above the terrain has, so it costs nothing.
     */
    final byte[][] light = new byte[SECTIONS][];
    public static final int DEFAULT_LIGHT = 0xF0;
    /** Shared all-zero light array for buried sections; replaced by a copy on first write. */
    static final byte[] DARK = new byte[Section.VOLUME];
    /** Set when modified since load/generation; only dirty chunks are written (generation is deterministic). */
    private volatile boolean dirty;

    public Chunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
    }

    /** Section index (0..SECTIONS-1) holding world height y. */
    public static int sectionIndex(int y) {
        return (y - MIN_Y) >> 4;
    }

    /** World y of the bottom of section index sy. */
    public static int sectionMinY(int sy) {
        return (sy << 4) + MIN_Y;
    }

    /** Fills a whole section with one block (generation fast path). */
    public void fillSection(int sy, byte id) {
        if (sections[sy] == null) sections[sy] = new Section();
        sections[sy].fill(id);
    }

    public Section section(int sy) {
        return sections[sy];
    }

    public byte get(int x, int y, int z) {
        Section s = sections[(y - MIN_Y) >> 4];
        return s == null ? Blocks.AIR : s.get(x, y & 15, z);
    }

    public void set(int x, int y, int z, byte id) {
        int sy = (y - MIN_Y) >> 4;
        Section s = sections[sy];
        if (s == null) {
            if (id == Blocks.AIR) return;
            s = sections[sy] = new Section();
        }
        s.set(x, y & 15, z, id);
    }

    public int getLight(int x, int y, int z) {
        byte[] l = light[(y - MIN_Y) >> 4];
        return l == null ? DEFAULT_LIGHT : l[Section.index(x, y & 15, z)] & 0xFF;
    }

    public void setLight(int x, int y, int z, int value) {
        int sy = (y - MIN_Y) >> 4;
        byte[] l = light[sy];
        if (l == null) {
            if (value == DEFAULT_LIGHT) return;
            l = light[sy] = new byte[Section.VOLUME];
            java.util.Arrays.fill(l, (byte) DEFAULT_LIGHT);
        } else if (l == DARK) {
            if (value == 0) return;
            l = light[sy] = new byte[Section.VOLUME];
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
