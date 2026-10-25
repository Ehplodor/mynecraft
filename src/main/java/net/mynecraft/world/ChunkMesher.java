package net.mynecraft.world;

import java.util.Arrays;

/**
 * Greedy mesher for one section. Visible faces with the same block, light and ambient occlusion are
 * merged into the largest rectangles possible, so flat terrain becomes a handful of quads.
 *
 * <p>Each vertex is two ints (8 bytes):
 * <ol>
 *   <li>x,y,z (5 bits each, 0..16) | face (3 bits: 0-5 cube faces, 6 cross plane) | texture layer
 *       (10 bits) | tint type (2 bits). UVs are derived from position in the shader, so merged quads
 *       tile their texture.</li>
 *   <li>light (sky &lt;&lt; 4 | block) | ambient occlusion level 0..3 &lt;&lt; 8.</li>
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

    /** Mesh of one section; {@link #data} holds the layers back to back. */
    public record Result(int[] data, int opaqueQuads, int cutoutQuads, int translucentQuads) {
        public static final Result EMPTY = new Result(new int[0], 0, 0, 0);

        public int quads() {
            return opaqueQuads + cutoutQuads + translucentQuads;
        }
    }

    private final int[] faceInfo;
    private final byte[] padded = new byte[P * P * P];
    private final byte[] paddedLight = new byte[P * P * P];
    private final int[] mask = new int[Section.SIZE * Section.SIZE];
    private final int[][] out = {new int[4096], new int[1024], new int[1024]};
    private final int[] count = new int[3];

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
        fillPadded(world, chunk, sy, section);
        Arrays.fill(count, 0);
        for (int d = 0; d < 3; d++) {
            meshAxis(d, false);
            meshAxis(d, true);
        }
        meshCrosses();
        int total = count[0] + count[1] + count[2];
        if (total == 0) return Result.EMPTY;
        int[] data = new int[total];
        System.arraycopy(out[0], 0, data, 0, count[0]);
        System.arraycopy(out[1], 0, data, count[0], count[1]);
        System.arraycopy(out[2], 0, data, count[0] + count[1], count[2]);
        return new Result(data, count[0] / INTS_PER_QUAD, count[1] / INTS_PER_QUAD, count[2] / INTS_PER_QUAD);
    }

    private void fillPadded(World world, Chunk chunk, int sy, Section section) {
        int ox = chunk.cx << 4, oy = sy << 4, oz = chunk.cz << 4;
        for (int y = -1; y <= Section.SIZE; y++) {
            for (int z = -1; z <= Section.SIZE; z++) {
                boolean innerYZ = y >= 0 && y < 16 && z >= 0 && z < 16;
                for (int x = -1; x <= Section.SIZE; x++) {
                    int i = pIndex(x, y, z);
                    if (innerYZ && x >= 0 && x < 16) {
                        padded[i] = section.blocks[Section.index(x, y, z)];
                        paddedLight[i] = (byte) chunk.getLight(x, oy + y, z);
                    } else {
                        padded[i] = world.get(ox + x, oy + y, oz + z);
                        paddedLight[i] = (byte) world.getLight(ox + x, oy + y, oz + z);
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

    private void meshAxis(int d, boolean positive) {
        int u = (d + 1) % 3, v = (d + 2) % 3;
        int face = d * 2 + (positive ? 1 : 0);
        int step = positive ? STRIDE[d] : -STRIDE[d];
        int su = STRIDE[u], sv = STRIDE[v];
        int[] pos = new int[3];

        for (int slice = 0; slice < Section.SIZE; slice++) {
            pos[d] = slice;
            for (int j = 0; j < Section.SIZE; j++) {
                pos[v] = j;
                for (int i = 0; i < Section.SIZE; i++) {
                    pos[u] = i;
                    int idx = pIndex(pos[0], pos[1], pos[2]);
                    byte b = padded[idx];
                    int key = 0;
                    if (Blocks.SHAPE[b & 0xFF] == Blocks.SHAPE_CUBE && Blocks.faceVisible(b, padded[idx + step])) {
                        int f = idx + step;
                        // AO corners in quad order (u0,v0), (u1,v0), (u1,v1), (u0,v1).
                        int ao = ao(f, -su, -sv) | ao(f, su, -sv) << 2 | ao(f, su, sv) << 4 | ao(f, -su, sv) << 6;
                        key = (b & 0xFF) | (paddedLight[f] & 0xFF) << 8 | ao << 16 | 1 << 24;
                    }
                    mask[j * Section.SIZE + i] = key;
                }
            }
            int plane = slice + (positive ? 1 : 0);
            for (int j = 0; j < Section.SIZE; j++) {
                for (int i = 0; i < Section.SIZE; ) {
                    int key = mask[j * Section.SIZE + i];
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
                        Arrays.fill(mask, (j + jj) * Section.SIZE + i, (j + jj) * Section.SIZE + i + w, 0);
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
                          int face, int key, boolean positive) {
        int block = key & 0xFF, light = (key >> 8) & 0xFF;
        int a0 = (key >> 16) & 3, a1 = (key >> 18) & 3, a2 = (key >> 20) & 3, a3 = (key >> 22) & 3;
        int layer = Blocks.LAYER[block];
        int[] o = reserve(layer);
        int tail = (face << 15) | (faceInfo[block * 6 + face] << 18);
        int sd = d * 5, su = u * 5, sv = v * 5;
        int base = (plane << sd) | tail;
        int u0 = i << su, u1 = (i + w) << su, v0 = j << sv, v1 = (j + h) << sv;
        // Corners CCW seen from the +d side (reversed for -d), each with its AO value.
        int[] p = positive
            ? new int[] {base | u0 | v0, a0, base | u1 | v0, a1, base | u1 | v1, a2, base | u0 | v1, a3}
            : new int[] {base | u0 | v0, a0, base | u0 | v1, a3, base | u1 | v1, a2, base | u1 | v0, a1};
        // Split along the diagonal through the darker corners, otherwise AO looks anisotropic.
        int start = p[1] + p[5] > p[3] + p[7] ? 1 : 0;
        int c = count[layer];
        for (int k = 0; k < 4; k++) {
            int s = ((start + k) & 3) * 2;
            o[c++] = p[s];
            o[c++] = light | p[s + 1] << 8;
        }
        count[layer] = c;
    }

    /** Plants: two diagonal planes, each drawn from both sides, lit by their own cell, no AO. */
    private void meshCrosses() {
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int idx = pIndex(x, y, z);
                    byte b = padded[idx];
                    if (Blocks.SHAPE[b & 0xFF] != Blocks.SHAPE_CROSS) continue;
                    int light = (paddedLight[idx] & 0xFF) | 3 << 8;
                    int tail = (CROSS_FACE << 15) | (faceInfo[(b & 0xFF) * 6 + 4] << 18);
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
