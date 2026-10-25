package net.mynecraft.world;

import java.util.Arrays;

/**
 * Greedy mesher for one section. Visible faces with the same block, light and ambient occlusion are
 * merged into the largest rectangles possible, so flat terrain becomes a handful of quads.
 *
 * <p>Each vertex is two ints (8 bytes):
 * <ol>
 *   <li>x,y,z (5 bits each, 0..16) | face (3 bits: 0-5 cube faces, 6 cross plane) | texture layer
 *       (10 bits) | tint type (2 bits) | special (2 bits: 0 none, 1 water surface 2/16 lower,
 *       2 half block 8/16 lower, 3 tinted overlay in layer + 1 over an untinted base).
 *       UVs are derived from position in the shader, so merged quads tile their texture.</li>
 *   <li>smooth sky light (6 bits) | smooth block light (6 bits) | ambient occlusion 0..3 &lt;&lt; 12
 *       | section x mod 64 &lt;&lt; 14 | section z mod 64 &lt;&lt; 20 | section index &lt;&lt; 26. Smooth light is
 *       the exact sum of the 4 cells around the vertex (0..60). The section position lets every section
 *       share one buffer and one draw call: the shader rebuilds camera-relative positions from it.</li>
 * </ol>
 * Quads are 4 consecutive vertices drawn with a shared index buffer (0,1,2, 2,3,0), grouped by
 * render layer: opaque, then cutout, then translucent.
 *
 * <p>Not thread-safe: use one instance per thread; buffers are reused between calls.
 */
public final class ChunkMesher {
    public static final int INTS_PER_VERTEX = 2, INTS_PER_QUAD = 4 * INTS_PER_VERTEX;
    private static final int P = Section.SIZE + 2;
    private static final int[] STRIDE = {1, P * P, P}; // padded index step along x, y, z
    private static final int CROSS_FACE = 6;

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

    /** Result for a section that is opaque and walled in: no faces, no sight through it. */
    private static final Result SOLID = new Result(new int[0], 0, 0, 0, 0);
    private final Chunk[] around = new Chunk[9];
    private final int[] faceInfo;
    private final byte[] padded = new byte[P * P * P];
    private final byte[] paddedLight = new byte[P * P * P];
    private final long[] mask = new long[Section.SIZE * Section.SIZE];
    static final int SPECIAL_WATER = 1 << 30, SPECIAL_HALF = 2 << 30, SPECIAL_OVERLAY = 3 << 30;
    private static final int LOWERED_FLAG = SPECIAL_WATER;
    private static final int WATER_SURFACE = 0x80; // key bit on the block field (ids stay below 128)
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

    private static int pIndex(int x, int y, int z) {
        return ((y + 1) * P + (z + 1)) * P + (x + 1);
    }

    public Result mesh(World world, Chunk chunk, int sy) {
        Section section = chunk.section(sy);
        if (section == null) return Result.EMPTY;
        if (section.isFullyOpaque() && buried(world, chunk, sy)) return SOLID;
        fillPadded(world, chunk, sy, section);
        Arrays.fill(count, 0);
        for (int d = 0; d < 3; d++) {
            meshAxis(d, false);
            meshAxis(d, true);
        }
        meshCrosses();
        int vis = visibility.compute(section);
        int total = count[0] + count[1] + count[2];
        int[] data = new int[total];
        System.arraycopy(out[0], 0, data, 0, count[0]);
        System.arraycopy(out[1], 0, data, count[0], count[1]);
        System.arraycopy(out[2], 0, data, count[0] + count[1], count[2]);
        int sectionBits = (chunk.cx & 63) << 14 | (chunk.cz & 63) << 20 | sy << 26;
        for (int i = 1; i < total; i += INTS_PER_VERTEX) data[i] |= sectionBits;
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
                    byte b = padded[idx];
                    long key = 0;
                    if (Blocks.SHAPE[b & 0xFF] == Blocks.SHAPE_CUBE && Blocks.faceVisible(b, padded[idx + step])) {
                        int f = idx + step;
                        // Corners in quad order (u0,v0), (u1,v0), (u1,v1), (u0,v1).
                        long ao = ao(f, -su, -sv) | ao(f, su, -sv) << 2 | ao(f, su, sv) << 4 | ao(f, -su, sv) << 6;
                        long light = (long) smoothLight(f, -su, -sv) | (long) smoothLight(f, su, -sv) << 12
                            | (long) smoothLight(f, su, sv) << 24 | (long) smoothLight(f, -su, sv) << 36;
                        int blockField = b & 0xFF;
                        if (b == Blocks.WATER && padded[idx + up] != Blocks.WATER) blockField |= WATER_SURFACE;
                        key = blockField | ao << 8 | light << 16;
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

    private int[] reserve(int layer) {
        if (count[layer] + INTS_PER_QUAD > out[layer].length) out[layer] = Arrays.copyOf(out[layer], out[layer].length * 2);
        return out[layer];
    }

    private void emitQuad(int d, int u, int v, int plane, int i, int j, int w, int h,
                          int face, long key, boolean positive) {
        int blockField = (int) (key & 0xFF), block = blockField & 0x7F;
        int a0 = (int) (key >> 8) & 3, a1 = (int) (key >> 10) & 3, a2 = (int) (key >> 12) & 3, a3 = (int) (key >> 14) & 3;
        int l0 = (int) (key >> 16) & 0xFFF, l1 = (int) (key >> 28) & 0xFFF, l2 = (int) (key >> 40) & 0xFFF, l3 = (int) (key >>> 52);
        int layer = Blocks.LAYER[block];
        int[] o = reserve(layer);
        int tail = tail(face, faceInfo[block * 6 + face]);
        int sd = d * 5, su = u * 5, sv = v * 5;
        int base = (plane << sd) | tail;
        // Water surface: lower the top face entirely, and the top edge of side faces (y is u or v).
        int low0 = 0, low1 = 0, low2 = 0, low3 = 0;
        if ((blockField & WATER_SURFACE) != 0) {
            if (face == 3) low0 = low1 = low2 = low3 = LOWERED_FLAG;
            else if (u == 1) low1 = low2 = LOWERED_FLAG;          // corners with u1 are on top
            else if (v == 1) low2 = low3 = LOWERED_FLAG;          // corners with v1 are on top
        }
        int u0 = i << su, u1 = (i + w) << su, v0 = j << sv, v1 = (j + h) << sv;
        int p0 = base | u0 | v0 | low0, p1 = base | u1 | v0 | low1, p2 = base | u1 | v1 | low2, p3 = base | u0 | v1 | low3;
        int x0 = l0 | a0 << 12, x1 = l1 | a1 << 12, x2 = l2 | a2 << 12, x3 = l3 | a3 << 12;
        // Brightness per corner decides the split diagonal, otherwise light and AO look anisotropic.
        int b0 = score(a0, l0), b1 = score(a1, l1), b2 = score(a2, l2), b3 = score(a3, l3);
        int c = count[layer];
        if (positive) {
            c = quad(o, c, b0 + b2 > b1 + b3, p0, x0, p1, x1, p2, x2, p3, x3);
        } else {
            c = quad(o, c, b0 + b2 > b1 + b3, p0, x0, p3, x3, p2, x2, p1, x1);
        }
        count[layer] = c;
    }

    /** Face, texture layer, tint and overlay bits of a vertex (see class doc). */
    private static int tail(int face, int info) {
        return face << 15 | (info & 0xFFF) << 18 | ((info >> 12 & 1) != 0 ? SPECIAL_OVERLAY : 0);
    }

    private static int score(int ao, int light) {
        return ao * 64 + Math.max(light & 63, light >> 6);
    }

    /**
     * Writes 4 vertices in winding order; when {@code rotate} the list starts at the second vertex,
     * which moves the shared-index diagonal from 0-2 to 1-3 without changing the winding.
     */
    private static int quad(int[] o, int c, boolean rotate, int pa, int la, int pb, int lb, int pc, int lc, int pd, int ld) {
        if (rotate) {
            o[c++] = pb; o[c++] = lb; o[c++] = pc; o[c++] = lc; o[c++] = pd; o[c++] = ld; o[c++] = pa; o[c++] = la;
        } else {
            o[c++] = pa; o[c++] = la; o[c++] = pb; o[c++] = lb; o[c++] = pc; o[c++] = lc; o[c++] = pd; o[c++] = ld;
        }
        return c;
    }

    /** Plants: two diagonal planes, each drawn from both sides, lit by their own cell, no AO. */
    private void meshCrosses() {
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int idx = pIndex(x, y, z);
                    byte b = padded[idx];
                    if (Blocks.SHAPE[b & 0xFF] != Blocks.SHAPE_CROSS) continue;
                    int l = paddedLight[idx] & 0xFF;
                    int light = (l >> 4) * 4 | (l & 15) * 4 << 6 | 3 << 12;
                    int tail = tail(CROSS_FACE, faceInfo[(b & 0xFF) * 6 + 4]);
                    int x1 = x + 1, y1 = y + 1, z1 = z + 1;
                    crossQuad(Blocks.LAYER[b], tail, light, x, y, z, x1, z1, y1);
                    crossQuad(Blocks.LAYER[b], tail, light, x1, y, z1, x, z, y1);
                    crossQuad(Blocks.LAYER[b], tail, light, x1, y, z, x, z1, y1);
                    crossQuad(Blocks.LAYER[b], tail, light, x, y, z1, x1, z, y1);
                }
    }

    private void crossQuad(int layer, int tail, int light, int ax, int y0, int az, int bx, int bz, int y1) {
        int[] o = reserve(layer);
        int c = count[layer];
        o[c++] = tail | ax | y0 << 5 | az << 10; o[c++] = light;
        o[c++] = tail | bx | y0 << 5 | bz << 10; o[c++] = light;
        o[c++] = tail | bx | y1 << 5 | bz << 10; o[c++] = light;
        o[c++] = tail | ax | y1 << 5 | az << 10; o[c++] = light;
        count[layer] = c;
    }
}
