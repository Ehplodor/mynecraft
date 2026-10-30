package net.mynecraft.world;

import java.util.Arrays;

/**
 * Greedy mesher for one section. Visible faces with the same block, light and ambient occlusion are
 * merged into the largest rectangles possible, so flat terrain becomes a handful of quads.
 *
 * <p>Each quad is one 16-byte record (4 ints), read by the vertex shader through a texture buffer
 * ("vertex pulling"): the shader builds the quad's 6 vertices from {@code gl_VertexID}.
 * <ol>
 *   <li>origin x, y, z (5 bits each, 0..16; the origin is the corner at the low u and v of the face)
 *       | face &lt;&lt; 15 (3 bits: 0-5 cube faces, 6 cross plane) | width - 1 &lt;&lt; 18 | height - 1 &lt;&lt; 22
 *       (4 bits each, along the face's u and v axes) | special &lt;&lt; 26 (3 bits, see SPECIAL_*) | split &lt;&lt; 29
 *       (1: triangles share the 1-3 diagonal instead of 0-2). Cross planes reuse width/height bit 0 as
 *       "second diagonal" and "back side".</li>
 *   <li>texture layer (10 bits) | tint type &lt;&lt; 10 | section x mod 64 &lt;&lt; 12 | section z mod 64 &lt;&lt; 18
 *       | section index &lt;&lt; 24.</li>
 *   <li>light of corners 0 and 1 (12 bits each: smooth sky 0..60 | smooth block 0..60 &lt;&lt; 6)
 *       | ambient occlusion of corners 0..3 &lt;&lt; 24 (2 bits each).</li>
 *   <li>light of corners 2 and 3 | fluid surface height in sixteenths &lt;&lt; 24 (5 bits, SPECIAL_FLUID only: fluids and snow layers).</li>
 * </ol>
 * SPECIAL_FLUID_CORNERS (moving or sloping fluid, one cell per quad): each corner has its own surface
 * height, minus one, in 4 bits: corners 0 and 1 in bits 24-31 of the last word, corners 2 and 3 in
 * bits 24-31 of the third (in place of the AO, which fluids do without). The shader also scrolls
 * the texture along the slope (currents) and down the sides (falls).
 * Corners are numbered (u0,v0), (u1,v0), (u1,v1), (u0,v1). Smooth light is the exact sum of the 4
 * cells around a vertex. UVs are derived from position in the shader, so merged quads tile their
 * texture. Quads are grouped by render layer: opaque, then cutout, then translucent.
 *
 * <p>Not thread-safe: use one instance per thread; buffers are reused between calls.
 */
public final class ChunkMesher {
    public static final int INTS_PER_QUAD = 4;
    private static final int P = Section.SIZE + 2;
    private static final int[] STRIDE = {1, P * P, P}; // padded index step along x, y, z
    private static final int CROSS_FACE = 6;
    /** Vertex adjustments applied by the shader (3-bit field). */
    public static final int SPECIAL_NONE = 0, SPECIAL_FLUID = 1, SPECIAL_SLAB_BOTTOM = 2, SPECIAL_OVERLAY = 3,
        SPECIAL_SLAB_TOP = 4, SPECIAL_FLUID_CORNERS = 5;

    /**
     * Mesh of one section; {@link #data} holds the layers back to back. {@link #visibility} says which
     * pairs of section faces see each other through non-opaque blocks (see {@link SectionVisibility}).
     */
    public record Result(int[] data, int opaqueQuads, int cutoutQuads, int translucentQuads, int visibility) {
        public static final Result EMPTY = new Result(new int[0], 0, 0, 0, SectionVisibility.ALL);

        public int quads() {
            return opaqueQuads + cutoutQuads + translucentQuads;
        }
    }

    // ---- quad record accessors (tests, tools) ----

    public static int face(int[] d, int q) { return d[q * 4] >> 15 & 7; }

    public static int special(int[] d, int q) { return d[q * 4] >> 26 & 7; }

    public static int originY(int[] d, int q) { return d[q * 4] >> 5 & 31; }

    public static int layer(int[] d, int q) { return d[q * 4 + 1] & 1023; }

    /** Smooth light of corner k: sky sum | block sum &lt;&lt; 6. */
    public static int light(int[] d, int q, int k) { return d[q * 4 + 2 + (k >> 1)] >> ((k & 1) * 12) & 0xFFF; }

    public static int ao(int[] d, int q, int k) { return d[q * 4 + 2] >>> 24 >> (k * 2) & 3; }

    public static int sectionWord(int[] d, int q) { return d[q * 4 + 1]; }

    /** Fluid surface height of a SPECIAL_FLUID quad, in sixteenths of a block. */
    public static int fluidHeight(int[] d, int q) { return d[q * 4 + 3] >>> 24 & 31; }

    /** Result for a section that is opaque and walled in: no faces, no sight through it. */
    private static final Result SOLID = new Result(new int[0], 0, 0, 0, 0);
    private final Chunk[] around = new Chunk[9];
    private final int[] faceInfo;
    private final short[] padded = new short[P * P * P];
    private final byte[] paddedLight = new byte[P * P * P];
    private final long[] mask = new long[Section.SIZE * Section.SIZE];
    private static final int WATER_SURFACE = 0x80; // key bit on the block field (local palette index below 128)
    /**
     * The merge key has room for 7 bits of block, so blocks are keyed by a palette local to the section
     * being meshed (a padded section never holds anywhere near 127 distinct states); global ids are shorts.
     */
    private final short[] palette = new short[128];
    private final byte[] paletteIndex = new byte[Blocks.COUNT];
    private int paletteSize;
    private final int[][] out = {new int[4096], new int[1024], new int[1024]};
    private final int[] count = new int[3];
    private final SectionVisibility visibility = new SectionVisibility();

    /** @param faceInfo per (blockId * 6 + face): texture layer | tint type &lt;&lt; 10, see BlockAppearance. */
    public ChunkMesher(int[] faceInfo) {
        this.faceInfo = faceInfo;
    }

    /** Uses the block id as texture layer for every face (tests, tools). */
    public ChunkMesher() {
        this(identityFaceInfo());
    }

    private static int[] identityFaceInfo() {
        int[] t = new int[Blocks.COUNT * 6];
        for (int i = 0; i < t.length; i++) t[i] = i / 6;
        return t;
    }

    /** Palette index of a block for the merge key, assigned on first use in the current section. */
    private int local(short b) {
        int i = paletteIndex[b];
        if (i == 0) {
            if (paletteSize == 127) throw new IllegalStateException("more than 127 block states around one section");
            palette[++paletteSize] = b;
            i = paletteIndex[b] = (byte) paletteSize;
        }
        return i;
    }

    private static int pIndex(int x, int y, int z) {
        return ((y + 1) * P + (z + 1)) * P + (x + 1);
    }

    public Result mesh(World world, Chunk chunk, int sy) {
        Section section = chunk.section(sy);
        if (section == null) return Result.EMPTY;
        if (section.isFullyOpaque() && buried(world, chunk, sy)) return SOLID;
        for (int i = 1; i <= paletteSize; i++) paletteIndex[palette[i]] = 0;
        paletteSize = 0;
        fillPadded(world, chunk, sy, section);
        Arrays.fill(count, 0);
        for (int d = 0; d < 3; d++) {
            meshAxis(d, false);
            meshAxis(d, true);
        }
        meshCrosses();
        meshSlabs();
        meshLayers();
        int vis = visibility.compute(section);
        int total = count[0] + count[1] + count[2];
        if (total == 0) return new Result(new int[0], 0, 0, 0, vis);
        int[] data = new int[total];
        System.arraycopy(out[0], 0, data, 0, count[0]);
        System.arraycopy(out[1], 0, data, count[0], count[1]);
        System.arraycopy(out[2], 0, data, count[0] + count[1], count[2]);
        int sectionBits = (chunk.cx & 63) << 12 | (chunk.cz & 63) << 18 | sy << 24;
        for (int i = 1; i < total; i += INTS_PER_QUAD) data[i] |= sectionBits;
        return new Result(data, count[0] / INTS_PER_QUAD, count[1] / INTS_PER_QUAD, count[2] / INTS_PER_QUAD, vis);
    }

    /** True if all six neighbouring sections are fully opaque too (typical deep underground). */
    private static boolean buried(World world, Chunk chunk, int sy) {
        if (sy == 0 || sy == Chunk.SECTIONS - 1) return false;
        if (!opaque(chunk, sy - 1) || !opaque(chunk, sy + 1)) return false;
        return opaque(world.chunk(chunk.cx - 1, chunk.cz), sy) && opaque(world.chunk(chunk.cx + 1, chunk.cz), sy)
            && opaque(world.chunk(chunk.cx, chunk.cz - 1), sy) && opaque(world.chunk(chunk.cx, chunk.cz + 1), sy);
    }

    private static boolean opaque(Chunk c, int sy) {
        if (c == null) return false;
        Section s = c.section(sy);
        return s != null && s.isFullyOpaque();
    }

    /**
     * Copies the section plus a one-block border into flat arrays. Neighbouring chunks are looked up
     * once (3x3), not per cell, which keeps hash lookups out of the 5832-cell loop.
     */
    private void fillPadded(World world, Chunk chunk, int sy, Section section) {
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++)
                around[(dz + 1) * 3 + dx + 1] = dx == 0 && dz == 0 ? chunk : world.chunk(chunk.cx + dx, chunk.cz + dz);
        int oy = Chunk.sectionMinY(sy);
        for (int y = -1; y <= Section.SIZE; y++) {
            int wy = oy + y;
            boolean inWorld = wy >= Chunk.MIN_Y && wy < Chunk.MAX_Y;
            for (int z = -1; z <= Section.SIZE; z++) {
                int cz = z < 0 ? 0 : z > 15 ? 2 : 1, lz = z & 15;
                boolean innerYZ = y >= 0 && y < 16 && z >= 0 && z < 16;
                for (int x = -1; x <= Section.SIZE; x++) {
                    int i = pIndex(x, y, z);
                    if (innerYZ && x >= 0 && x < 16) {
                        padded[i] = section.blocks[Section.index(x, y, z)];
                        paddedLight[i] = (byte) chunk.getLight(x, wy, z);
                        continue;
                    }
                    Chunk c = around[cz * 3 + (x < 0 ? 0 : x > 15 ? 2 : 1)];
                    if (c == null || !inWorld) {
                        padded[i] = Blocks.AIR;
                        paddedLight[i] = (byte) (wy >= Chunk.MAX_Y || c == null ? Chunk.DEFAULT_LIGHT : 0);
                    } else {
                        padded[i] = c.get(x & 15, wy, lz);
                        paddedLight[i] = (byte) c.getLight(x & 15, wy, lz);
                    }
                }
            }
        }
    }

    private boolean occludes(int i) {
        return Blocks.isOpaque(padded[i]);
    }

    /** Vanilla-style AO for one corner: two sides and the diagonal around the vertex, in front of the face. */
    private int ao(int front, int du, int dv) {
        boolean s1 = occludes(front + du), s2 = occludes(front + dv);
        if (s1 && s2) return 0;
        return 3 - ((s1 ? 1 : 0) + (s2 ? 1 : 0) + (occludes(front + du + dv) ? 1 : 0));
    }

    /**
     * Smooth light at one corner: sum of the 4 cells around the vertex in front of the face, with
     * occluding cells (and a corner hidden by both sides) replaced by the front cell. Returns
     * skySum | blockSum &lt;&lt; 6, each 0..60.
     */
    private int smoothLight(int front, int du, int dv) {
        int lf = paddedLight[front] & 0xFF;
        boolean s1 = occludes(front + du), s2 = occludes(front + dv);
        int l1 = s1 ? lf : paddedLight[front + du] & 0xFF;
        int l2 = s2 ? lf : paddedLight[front + dv] & 0xFF;
        int lc = (s1 && s2) || occludes(front + du + dv) ? lf : paddedLight[front + du + dv] & 0xFF;
        int sky = (lf >> 4) + (l1 >> 4) + (l2 >> 4) + (lc >> 4);
        int block = (lf & 15) + (l1 & 15) + (l2 & 15) + (lc & 15);
        return sky | block << 6;
    }

    private void meshAxis(int d, boolean positive) {
        int u = (d + 1) % 3, v = (d + 2) % 3;
        int face = d * 2 + (positive ? 1 : 0);
        int step = positive ? STRIDE[d] : -STRIDE[d];
        int su = STRIDE[u], sv = STRIDE[v], up = STRIDE[1];
        int[] pos = new int[3];

        for (int slice = 0; slice < Section.SIZE; slice++) {
            pos[d] = slice;
            for (int j = 0; j < Section.SIZE; j++) {
                pos[v] = j;
                for (int i = 0; i < Section.SIZE; i++) {
                    pos[u] = i;
                    int idx = pIndex(pos[0], pos[1], pos[2]);
                    short b = padded[idx];
                    long key = 0;
                    if (Blocks.SHAPE[b & 0xFFFF] == Blocks.SHAPE_CUBE
                        && (Blocks.faceVisible(b, padded[idx + step]) || fluidStep(b, idx, idx + step, d, up))) {
                        int f = idx + step;
                        // Corners in quad order (u0,v0), (u1,v0), (u1,v1), (u0,v1).
                        long ao = ao(f, -su, -sv) | ao(f, su, -sv) << 2 | ao(f, su, sv) << 4 | ao(f, -su, sv) << 6;
                        long light = (long) smoothLight(f, -su, -sv) | (long) smoothLight(f, su, -sv) << 12
                            | (long) smoothLight(f, su, sv) << 24 | (long) smoothLight(f, -su, sv) << 36;
                        int blockField = local(b);
                        // Fluid surface: lowered unless the same fluid continues above.
                        if (Blocks.isFluid(b) && Blocks.FLUID[padded[idx + up] & 0xFF] != Blocks.FLUID[b & 0xFFFF]) {
                            blockField |= WATER_SURFACE;
                        }
                        if (Blocks.isFluid(b) && fluidCorners(b, idx, up)) {
                            // Sloping or moving fluid: its own quad, with a height per corner.
                            emitFluidCell(d, u, v, slice + (positive ? 1 : 0), i, j, face, b, positive, light);
                            key = 0;
                        } else {
                            key = blockField | ao << 8 | light << 16;
                        }
                    }
                    mask[j * Section.SIZE + i] = key;
                }
            }
            int plane = slice + (positive ? 1 : 0);
            for (int j = 0; j < Section.SIZE; j++) {
                for (int i = 0; i < Section.SIZE; ) {
                    long key = mask[j * Section.SIZE + i];
                    if (key == 0) { i++; continue; }
                    int w = 1;
                    while (i + w < Section.SIZE && mask[j * Section.SIZE + i + w] == key) w++;
                    int h = 1;
                    grow:
                    while (j + h < Section.SIZE) {
                        int row = (j + h) * Section.SIZE + i;
                        for (int k = 0; k < w; k++) if (mask[row + k] != key) break grow;
                        h++;
                    }
                    for (int jj = 0; jj < h; jj++) {
                        Arrays.fill(mask, (j + jj) * Section.SIZE + i, (j + jj) * Section.SIZE + i + w, 0L);
                    }
                    emitQuad(d, u, v, plane, i, j, w, h, face, key, positive);
                    i += w;
                }
            }
        }
    }

    // Surface heights at the 4 top corners of the fluid cell being meshed: [ox + 2 * oz].
    private final int[] corner = new int[4];

    /**
     * Works out the corner heights of a fluid cell and says whether it needs its own sloped quad:
     * flowing or falling fluid, or a surface whose corners are not all at the cell's own height.
     */
    private boolean fluidCorners(short b, int idx, int up) {
        int own = surface(idx, up);
        boolean flat = Blocks.LEVEL[b & 0xFFFF] == 0;
        for (int oz = 0; oz < 2; oz++)
            for (int ox = 0; ox < 2; ox++) {
                int h = own == 16 ? 16 : cornerHeight(b, idx + (ox - 1) * STRIDE[0] + (oz - 1) * STRIDE[2], up);
                corner[ox + 2 * oz] = h;
                if (h != own) flat = false;
            }
        return !flat;
    }

    /**
     * Surface height at a corner (vanilla-like): full if any of the 4 cells around it has the same
     * fluid above; otherwise the mean of those cells' surfaces, open air counting as 0 (surfaces
     * slope down to a free edge), solid blocks not counting.
     */
    private int cornerHeight(short b, int low, int up) {
        int sum = 0, n = 0;
        byte type = Blocks.FLUID[b & 0xFFFF];
        for (int k = 0; k < 4; k++) {
            int i = low + (k & 1) * STRIDE[0] + (k >> 1) * STRIDE[2];
            short c = padded[i];
            if (Blocks.FLUID[c & 0xFFFF] == type) {
                if (Blocks.FLUID[padded[i + up] & 0xFF] == type) return 16;
                int h = Blocks.fluidHeight(c);
                sum += Blocks.LEVEL[c & 0xFFFF] == 0 ? h * 4 : h; // sources weigh more, like vanilla
                n += Blocks.LEVEL[c & 0xFFFF] == 0 ? 4 : 1;
            } else if (!Blocks.isSolid(c)) {
                n++;
            }
        }
        return n == 0 ? Blocks.fluidHeight(b) : Math.max(1, Math.min(16, Math.round(sum / (float) n)));
    }

    /** One fluid cell face with per-corner surface heights (see SPECIAL_FLUID_CORNERS). */
    private void emitFluidCell(int d, int u, int v, int plane, int i, int j, int face, short b, boolean positive, long light) {
        int[] p = new int[3];
        p[d] = plane;
        p[u] = i;
        p[v] = j;
        int[] nib = new int[4];
        for (int c = 0; c < 4; c++) {
            int du = c == 1 || c == 2 ? 1 : 0, dv = c >= 2 ? 1 : 0;
            int ox = u == 0 ? du : v == 0 ? dv : (positive ? 1 : 0);
            int oz = u == 2 ? du : v == 2 ? dv : (positive ? 1 : 0);
            nib[c] = corner[ox + 2 * oz] - 1;
        }
        int l0 = (int) light & 0xFFF, l1 = (int) (light >> 12) & 0xFFF, l2 = (int) (light >> 24) & 0xFFF, l3 = (int) (light >>> 36) & 0xFFF;
        int info = faceInfo[(b & 0xFFFF) * 6 + face];
        put(Blocks.LAYER[b & 0xFFFF], p[0], p[1], p[2], face, 0, 0, SPECIAL_FLUID_CORNERS, false, info,
            l0 | l1 << 12 | (nib[2] | nib[3] << 4) << 24, l2 | l3 << 12 | (nib[0] | nib[1] << 4) << 24);
    }

    /**
     * A side face between two cells of the same fluid is still visible where the neighbour's surface
     * is lower (a flowing step), otherwise a gap would show between the two surfaces.
     */
    private boolean fluidStep(short b, int idx, int n, int d, int up) {
        if (d == 1 || !Blocks.isFluid(b) || Blocks.FLUID[padded[n] & 0xFF] != Blocks.FLUID[b & 0xFFFF]) return false;
        return surface(idx, up) > surface(n, up);
    }

    /** Height of a fluid cell's surface in sixteenths: full when the same fluid continues above. */
    private int surface(int i, int up) {
        short b = padded[i];
        return Blocks.FLUID[padded[i + up] & 0xFF] == Blocks.FLUID[b & 0xFFFF] ? 16 : Blocks.fluidHeight(b);
    }

    private void emitQuad(int d, int u, int v, int plane, int i, int j, int w, int h,
                          int face, long key, boolean positive) {
        int blockField = (int) (key & 0xFF), block = palette[blockField & 0x7F];
        int a0 = (int) (key >> 8) & 3, a1 = (int) (key >> 10) & 3, a2 = (int) (key >> 12) & 3, a3 = (int) (key >> 14) & 3;
        int l0 = (int) (key >> 16) & 0xFFF, l1 = (int) (key >> 28) & 0xFFF, l2 = (int) (key >> 40) & 0xFFF, l3 = (int) (key >>> 52);
        int[] p = new int[3];
        p[d] = plane;
        p[u] = i;
        p[v] = j;
        int info = faceInfo[block * 6 + face];
        boolean surface = (blockField & WATER_SURFACE) != 0;
        int special = surface ? SPECIAL_FLUID : overlay(info);
        int fluidTop = surface ? Blocks.fluidHeight((short) block) << 24 : 0;
        // Brightness per corner decides the split diagonal, otherwise light and AO look anisotropic.
        boolean split = score(a0, l0) + score(a2, l2) > score(a1, l1) + score(a3, l3);
        put(Blocks.LAYER[block], p[0], p[1], p[2], face, w - 1, h - 1, special, split, info,
            l0 | l1 << 12 | (a0 | a1 << 2 | a2 << 4 | a3 << 6) << 24, l2 | l3 << 12 | fluidTop);
    }

    private static int overlay(int info) {
        return (info >> 12 & 1) != 0 ? SPECIAL_OVERLAY : SPECIAL_NONE;
    }

    /** Appends one quad record to a layer. */
    private void put(int layer, int x, int y, int z, int face, int wm1, int hm1, int special, boolean split,
                     int info, int light01, int light23) {
        if (count[layer] + INTS_PER_QUAD > out[layer].length) out[layer] = Arrays.copyOf(out[layer], out[layer].length * 2);
        int[] o = out[layer];
        int c = count[layer];
        o[c] = x | y << 5 | z << 10 | face << 15 | wm1 << 18 | hm1 << 22 | special << 26 | (split ? 1 << 29 : 0);
        o[c + 1] = info & 0xFFF; // layer + tint; section bits are added once the mesh is complete
        o[c + 2] = light01;
        o[c + 3] = light23;
        count[layer] = c + INTS_PER_QUAD;
    }

    private static int score(int ao, int light) {
        return ao * 64 + Math.max(light & 63, light >> 6);
    }

    /** Uniform corner lights for a single cell value (sky &lt;&lt; 4 | block), AO fully open. */
    private static int[] flatLight(int l) {
        int c = (l >> 4) * 4 | (l & 15) * 4 << 6;
        return new int[] {c | c << 12 | 0xFF << 24, c | c << 12};
    }

    /** Plants: two diagonal planes, each drawn from both sides, lit by their own cell, no AO. */
    private void meshCrosses() {
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int idx = pIndex(x, y, z);
                    short b = padded[idx];
                    if (Blocks.SHAPE[b & 0xFFFF] != Blocks.SHAPE_CROSS) continue;
                    int[] light = flatLight(paddedLight[idx] & 0xFF);
                    int info = faceInfo[(b & 0xFFFF) * 6 + 4];
                    for (int diagonal = 0; diagonal < 2; diagonal++)
                        for (int back = 0; back < 2; back++)
                            put(Blocks.LAYER[b], x, y, z, CROSS_FACE, diagonal, back, SPECIAL_NONE, false, info, light[0], light[1]);
                }
    }

    /**
     * Slabs: six 1x1 faces per block at the full cell's faces; the shader moves the edges at the
     * half (bottom slab: top edge 8/16 down; top slab: bottom edge 8/16 up). The face inside the
     * cell uses the cell's own light, outer faces the light in front of them; slabs do not occlude.
     */
    private void meshSlabs() {
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int idx = pIndex(x, y, z);
                    short b = padded[idx];
                    byte shape = Blocks.SHAPE[b & 0xFFFF];
                    if (shape != Blocks.SHAPE_SLAB_BOTTOM && shape != Blocks.SHAPE_SLAB_TOP) continue;
                    boolean top = shape == Blocks.SHAPE_SLAB_TOP;
                    for (int f = 0; f < 6; f++) {
                        int d = f >> 1;
                        boolean positive = (f & 1) != 0;
                        int n = idx + (positive ? STRIDE[d] : -STRIDE[d]);
                        boolean inner = top ? f == 2 : f == 3;
                        if (!inner && !Blocks.faceVisible(b, padded[n])) continue;
                        int[] light = flatLight(paddedLight[inner ? idx : n] & 0xFF);
                        int[] p = {x, y, z};
                        if (positive) p[d]++;
                        put(Blocks.LAYER[b & 0xFFFF], p[0], p[1], p[2], f, 0, 0, top ? SPECIAL_SLAB_TOP : SPECIAL_SLAB_BOTTOM,
                            false, faceInfo[(b & 0xFFFF) * 6 + f], light[0], light[1]);
                    }
                }
    }

    /**
     * Snow layers: one quad per visible face, lowered by the shader like a fluid surface (the height
     * rides in the same 5 bits). Sides are hidden by an opaque neighbour or by snow at least as deep.
     */
    private void meshLayers() {
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int idx = pIndex(x, y, z);
                    short b = padded[idx];
                    if (Blocks.SHAPE[b & 0xFFFF] != Blocks.SHAPE_LAYER) continue;
                    int layers = Blocks.snowLayers(b);
                    for (int f = 0; f < 6; f++) {
                        int d = f >> 1;
                        boolean positive = (f & 1) != 0;
                        int n = idx + (positive ? STRIDE[d] : -STRIDE[d]);
                        short nb = padded[n];
                        if (f != 3 && (Blocks.isOpaque(nb) || f != 2 && Blocks.snowLayers(nb) >= layers)) continue;
                        int[] light = flatLight(paddedLight[f == 3 ? idx : n] & 0xFF);
                        int[] p = {x, y, z};
                        if (positive) p[d]++;
                        put(Blocks.LAYER[b & 0xFFFF], p[0], p[1], p[2], f, 0, 0, SPECIAL_FLUID, false,
                            faceInfo[(b & 0xFFFF) * 6 + f], light[0], light[1] | 2 * layers << 24);
                    }
                }
    }
}
