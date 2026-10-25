package net.mynecraft.world;

import java.util.HashSet;
import java.util.function.LongConsumer;

/**
 * Sky and block light, 4 bits each, propagated by breadth-first flood fill (the classic
 * "add queue / removal queue" algorithm), so an edit only touches the cells whose light changes.
 *
 * <p>Rules: light loses max(1, opacity) per step; full sky light travelling straight down through
 * transparent blocks stays at 15 (sunlight columns). {@link #initChunk} runs on loader threads on a
 * chunk that is not yet in the world; everything else runs on the main thread.
 */
public final class LightEngine {
    static final int SKY = 4, BLOCK = 0;
    private static final int[] DX = {-1, 1, 0, 0, 0, 0}, DY = {0, 0, -1, 1, 0, 0}, DZ = {0, 0, 0, 0, -1, 1};
    private static final int DOWN = 2;

    /** Light storage seen through world or chunk-local coordinates. */
    private interface Access {
        /** Opacity of the block, or -1 if the cell is outside what may be lit. */
        int opacity(int x, int y, int z);
        int get(int x, int y, int z);
        void set(int x, int y, int z, int value);
    }

    private final World world;
    private final HashSet<Long> touched = new HashSet<>();
    private final LongQueue addQueue = new LongQueue(), removeQueue = new LongQueue();
    private final LongQueue skyQueue = new LongQueue(), blockQueue = new LongQueue();
    private final Access worldAccess = new Access() {
        public int opacity(int x, int y, int z) {
            if (y < Chunk.MIN_Y || y >= Chunk.MAX_Y) return -1;
            Chunk c = world.chunk(x >> 4, z >> 4);
            return c == null ? -1 : Blocks.OPACITY[c.get(x & 15, y, z & 15) & 0xFF];
        }
        public int get(int x, int y, int z) {
            return world.getLight(x, y, z);
        }
        public void set(int x, int y, int z, int value) {
            Chunk c = world.chunk(x >> 4, z >> 4);
            c.setLight(x & 15, y, z & 15, value);
            touch(x, y, z);
        }
    };

    LightEngine(World world) {
        this.world = world;
    }

    // ---- per-chunk initial light (no neighbours) ----

    public static void initChunk(Chunk c) {
        // Buried sections (all opaque, nothing glowing) are entirely dark: share one zero array.
        boolean[] dark = new boolean[Chunk.SECTIONS];
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
            Section s = c.sections[sy];
            if (s != null && s.isDark()) {
                dark[sy] = true;
                c.light[sy] = Chunk.DARK;
            }
        }
        // Vertical pass: sunlight down each column; remember where it first gets dimmed.
        int[] top = new int[256];
        LongQueue blockSeeds = new LongQueue();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int sky = 15, t = Chunk.MIN_Y - 1;
                for (int y = Chunk.MAX_Y - 1; y >= Chunk.MIN_Y; y--) {
                    int sy = Chunk.sectionIndex(y);
                    if (dark[sy]) {
                        if (t < Chunk.MIN_Y) t = y;
                        sky = 0;
                        y = Chunk.sectionMinY(sy); // skip the rest of this section
                        continue;
                    }
                    if (sky == 15 && c.sections[sy] == null) {
                        y = Chunk.sectionMinY(sy); // empty and fully sunlit: the default light already
                        continue;
                    }
                    byte b = c.get(x, y, z);
                    int op = Blocks.OPACITY[b & 0xFF];
                    if (sky != 15 || op != 0) {
                        if (t < Chunk.MIN_Y) t = y;
                        sky = Math.max(0, sky - Math.max(1, op));
                    }
                    int emission = Blocks.EMISSION[b & 0xFF];
                    if (emission > 0) blockSeeds.push(pack(x, y, z), 0);
                    c.setLight(x, y, z, sky << 4 | emission);
                }
                top[z * 16 + x] = t;
            }
        }
        Access a = new Access() {
            public int opacity(int x, int y, int z) {
                if (x < 0 || x > 15 || z < 0 || z > 15 || y < Chunk.MIN_Y || y >= Chunk.MAX_Y) return -1;
                return Blocks.OPACITY[c.get(x, y, z) & 0xFF];
            }
            public int get(int x, int y, int z) { return c.getLight(x, y, z); }
            public void set(int x, int y, int z, int value) { c.setLight(x, y, z, value); }
        };
        // Sideways sky spread can only start at or below the highest dimming point of the column
        // or its 4 neighbours: everything above is uniformly full sky.
        LongQueue q = new LongQueue();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int limit = top[z * 16 + x];
                if (x > 0) limit = Math.max(limit, top[z * 16 + x - 1]);
                if (x < 15) limit = Math.max(limit, top[z * 16 + x + 1]);
                if (z > 0) limit = Math.max(limit, top[(z - 1) * 16 + x]);
                if (z < 15) limit = Math.max(limit, top[(z + 1) * 16 + x]);
                for (int y = Math.min(Chunk.MAX_Y - 1, limit + 1); y >= Chunk.MIN_Y; y--) {
                    int v = c.getLight(x, y, z) >> SKY;
                    if (v <= 1) {
                        if (y < top[z * 16 + x]) break; // under the column's own dimming: stays dark
                        continue;
                    }
                    for (int d = 0; d < 6; d++) {
                        int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                        int op = a.opacity(nx, ny, nz);
                        if (op >= 0 && op < 15 && (a.get(nx, ny, nz) >> SKY) < v - 1) {
                            q.push(pack(x, y, z), 0);
                            break;
                        }
                    }
                }
            }
        }
        propagate(a, SKY, q);
        propagate(a, BLOCK, blockSeeds);
    }

    // ---- world updates ----

    /** Re-lights around a changed block. */
    void blockChanged(int x, int y, int z) {
        byte b = world.get(x, y, z);
        for (int shift : new int[] {SKY, BLOCK}) {
            int old = (worldAccess.get(x, y, z) >> shift) & 15;
            write(worldAccess, shift, x, y, z, 0);
            if (old > 0) removeQueue.push(pack(x, y, z), old);
            remove(worldAccess, shift);
            if (shift == BLOCK && Blocks.EMISSION[b & 0xFF] > 0) {
                write(worldAccess, shift, x, y, z, Blocks.EMISSION[b & 0xFF]);
                addQueue.push(pack(x, y, z), 0);
            }
            if (shift == SKY && y == Chunk.MAX_Y - 1 && Blocks.OPACITY[b & 0xFF] == 0) {
                write(worldAccess, shift, x, y, z, 15);
                addQueue.push(pack(x, y, z), 0);
            }
            for (int d = 0; d < 6; d++) {
                int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                if (worldAccess.opacity(nx, ny, nz) >= 0 && ((worldAccess.get(nx, ny, nz) >> shift) & 15) > 0) {
                    addQueue.push(pack(nx, ny, nz), 0);
                }
            }
            propagate(worldAccess, shift, addQueue);
        }
    }

    /**
     * Lets light flow across the borders between a newly loaded chunk and its loaded neighbours.
     * Reads both chunks' arrays directly and skips section pairs sharing the same light array
     * (both full sky, or both buried), which is most of them.
     */
    public void chunkJoined(Chunk c) {
        for (int side = 0; side < 4; side++) {
            int sx = side == 0 ? -1 : side == 1 ? 1 : 0, sz = side == 2 ? -1 : side == 3 ? 1 : 0;
            Chunk n = world.chunk(c.cx + sx, c.cz + sz);
            if (n == null) continue;
            for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
                byte[] la = c.light[sy], lb = n.light[sy];
                if (la == lb) continue; // identical uniform light on both sides: nothing to exchange
                Section secA = c.sections[sy], secB = n.sections[sy];
                int y0 = Chunk.sectionMinY(sy);
                for (int i = 0; i < 16; i++) {
                    int ax = side == 0 ? 0 : side == 1 ? 15 : i, az = side == 2 ? 0 : side == 3 ? 15 : i;
                    int bx = side == 0 ? 15 : side == 1 ? 0 : i, bz = side == 2 ? 15 : side == 3 ? 0 : i;
                    for (int ly = 0; ly < 16; ly++) {
                        int ia = Section.index(ax, ly, az), ib = Section.index(bx, ly, bz);
                        int va = la == null ? Chunk.DEFAULT_LIGHT : la[ia] & 0xFF;
                        int vb = lb == null ? Chunk.DEFAULT_LIGHT : lb[ib] & 0xFF;
                        if (va == vb) continue;
                        int opA = secA == null ? 0 : Blocks.OPACITY[secA.blocks[ia] & 0xFF];
                        int opB = secB == null ? 0 : Blocks.OPACITY[secB.blocks[ib] & 0xFF];
                        int y = y0 + ly;
                        int wax = (c.cx << 4) + ax, waz = (c.cz << 4) + az;
                        int wbx = (n.cx << 4) + bx, wbz = (n.cz << 4) + bz;
                        seedIfBrighter(va >> 4, vb >> 4, opB, wax, y, waz, wbx, wbz, skyQueue);
                        seedIfBrighter(va & 15, vb & 15, opB, wax, y, waz, wbx, wbz, blockQueue);
                        seedIfBrighter(vb >> 4, va >> 4, opA, wbx, y, wbz, wax, waz, skyQueue);
                        seedIfBrighter(vb & 15, va & 15, opA, wbx, y, wbz, wax, waz, blockQueue);
                    }
                }
            }
        }
        propagate(worldAccess, SKY, skyQueue);
        propagate(worldAccess, BLOCK, blockQueue);
    }

    /** Seeds the brighter side if it can raise the other (transparent) side. */
    private static void seedIfBrighter(int from, int to, int toOpacity, int fx, int y, int fz, int tx, int tz, LongQueue q) {
        if (from > to + 1 && toOpacity < 15) q.push(pack(fx, y, fz), 0);
    }

    /** Hands out the sections whose light changed since the last call (they need re-meshing). */
    public void drainTouched(LongConsumer sink) {
        for (long k : touched) sink.accept(k);
        touched.clear();
    }

    private void touch(int x, int y, int z) {
        int cx = x >> 4, sy = Chunk.sectionIndex(y), cz = z >> 4, lx = x & 15, ly = y & 15, lz = z & 15;
        touched.add(World.sectionKey(cx, sy, cz));
        // Faces of neighbouring blocks read this cell's light: mark sections across a border too.
        if (lx == 0) touched.add(World.sectionKey(cx - 1, sy, cz));
        if (lx == 15) touched.add(World.sectionKey(cx + 1, sy, cz));
        if (ly == 0 && sy > 0) touched.add(World.sectionKey(cx, sy - 1, cz));
        if (ly == 15 && sy < Chunk.SECTIONS - 1) touched.add(World.sectionKey(cx, sy + 1, cz));
        if (lz == 0) touched.add(World.sectionKey(cx, sy, cz - 1));
        if (lz == 15) touched.add(World.sectionKey(cx, sy, cz + 1));
    }

    // ---- flood fill core ----

    private static void write(Access a, int shift, int x, int y, int z, int v) {
        int old = a.get(x, y, z);
        int nv = (old & ~(15 << shift)) | (v << shift);
        if (nv != old) a.set(x, y, z, nv);
    }

    private static void propagate(Access a, int shift, LongQueue q) {
        while (!q.isEmpty()) {
            long p = q.pop();
            int x = unpackX(p), y = unpackY(p), z = unpackZ(p);
            int v = (a.get(x, y, z) >> shift) & 15;
            if (v <= 1) continue;
            for (int d = 0; d < 6; d++) {
                int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                int op = a.opacity(nx, ny, nz);
                if (op < 0 || op >= 15) continue;
                int nv = shift == SKY && d == DOWN && v == 15 && op == 0 ? 15 : v - Math.max(1, op);
                if (nv > ((a.get(nx, ny, nz) >> shift) & 15)) {
                    write(a, shift, nx, ny, nz, nv);
                    q.push(pack(nx, ny, nz), 0);
                }
            }
        }
    }

    private void remove(Access a, int shift) {
        while (!removeQueue.isEmpty()) {
            int lv = removeQueue.peekPayload();
            long p = removeQueue.pop();
            int x = unpackX(p), y = unpackY(p), z = unpackZ(p);
            for (int d = 0; d < 6; d++) {
                int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                if (a.opacity(nx, ny, nz) < 0) continue;
                int nl = (a.get(nx, ny, nz) >> shift) & 15;
                if (nl == 0) continue;
                if (nl < lv || (shift == SKY && d == DOWN && lv == 15 && nl == 15)) {
                    write(a, shift, nx, ny, nz, 0);
                    removeQueue.push(pack(nx, ny, nz), nl);
                    int emission = shift == BLOCK ? Blocks.EMISSION[world.get(nx, ny, nz) & 0xFF] : 0;
                    if (emission > 0) {
                        write(a, shift, nx, ny, nz, emission);
                        addQueue.push(pack(nx, ny, nz), 0);
                    }
                } else {
                    addQueue.push(pack(nx, ny, nz), 0);
                }
            }
        }
    }

    // ---- position packing: x and z on 27 signed bits, y - MIN_Y on 9 ----

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x7FFFFFF) << 36) | ((long) (z & 0x7FFFFFF) << 9) | (y - Chunk.MIN_Y);
    }

    static int unpackX(long p) { return ((int) (p >>> 36) << 5) >> 5; }

    static int unpackZ(long p) { return ((int) ((p >>> 9) & 0x7FFFFFF) << 5) >> 5; }

    static int unpackY(long p) { return (int) (p & 0x1FF) + Chunk.MIN_Y; }

    /** FIFO of packed positions with an int payload, growable ring buffer: no boxing. */
    static final class LongQueue {
        private long[] items = new long[1024];
        private int[] payload = new int[1024];
        private int head, size;

        boolean isEmpty() { return size == 0; }

        void push(long v, int extra) {
            if (size == items.length) {
                long[] ni = new long[items.length * 2];
                int[] np = new int[items.length * 2];
                for (int i = 0; i < size; i++) {
                    ni[i] = items[(head + i) & (items.length - 1)];
                    np[i] = payload[(head + i) & (items.length - 1)];
                }
                items = ni;
                payload = np;
                head = 0;
            }
            int t = (head + size) & (items.length - 1);
            items[t] = v;
            payload[t] = extra;
            size++;
        }

        int peekPayload() { return payload[head]; }

        long pop() {
            long v = items[head];
            head = (head + 1) & (items.length - 1);
            size--;
            return v;
        }
    }
}
