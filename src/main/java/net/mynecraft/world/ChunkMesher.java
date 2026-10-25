package net.mynecraft.world;

import java.util.Arrays;

/**
 * Greedy mesher for one section. Visible faces with the same block id are merged into the
 * largest rectangles possible, so flat terrain becomes a handful of quads instead of thousands.
 *
 * <p>Each vertex is one packed int: x,y,z (5 bits each, 0..16) | face (3 bits) | texture layer (10 bits)
 * | tint flag (1 bit). UVs are derived from position in the shader, so merged quads tile their texture.
 * Quads are 4 consecutive vertices drawn with a shared index buffer (0,1,2, 2,3,0).
 *
 * <p>Not thread-safe: use one instance per thread; buffers are reused between calls.
 */
public final class ChunkMesher {
    private static final int P = Section.SIZE + 2;

    private final int[] faceInfo;
    private final byte[] padded = new byte[P * P * P];
    private final byte[] mask = new byte[Section.SIZE * Section.SIZE];
    private int[] out = new int[4096];
    private int count;

    /** @param faceInfo per (blockId * 6 + face): texture layer | tint bit, see BlockAppearance. */
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

    /** Returns packed vertices (4 per quad), or an empty array if nothing is visible. */
    public int[] mesh(World world, Chunk chunk, int sy) {
        Section section = chunk.section(sy);
        if (section == null) return new int[0];
        fillPadded(world, chunk, sy, section);
        count = 0;
        for (int d = 0; d < 3; d++) {
            meshAxis(d, false);
            meshAxis(d, true);
        }
        return Arrays.copyOf(out, count);
    }

    private void fillPadded(World world, Chunk chunk, int sy, Section section) {
        int ox = chunk.cx << 4, oy = sy << 4, oz = chunk.cz << 4;
        for (int y = -1; y <= Section.SIZE; y++) {
            for (int z = -1; z <= Section.SIZE; z++) {
                boolean innerYZ = y >= 0 && y < 16 && z >= 0 && z < 16;
                for (int x = -1; x <= Section.SIZE; x++) {
                    byte b;
                    if (innerYZ && x >= 0 && x < 16) b = section.blocks[Section.index(x, y, z)];
                    else b = world.get(ox + x, oy + y, oz + z);
                    padded[pIndex(x, y, z)] = b;
                }
            }
        }
    }

    private void meshAxis(int d, boolean positive) {
        int u = (d + 1) % 3, v = (d + 2) % 3;
        int face = d * 2 + (positive ? 1 : 0);
        int[] pos = new int[3];
        int[] step = new int[3];
        step[d] = positive ? 1 : -1;

        for (int slice = 0; slice < Section.SIZE; slice++) {
            pos[d] = slice;
            for (int j = 0; j < Section.SIZE; j++) {
                pos[v] = j;
                for (int i = 0; i < Section.SIZE; i++) {
                    pos[u] = i;
                    byte b = padded[pIndex(pos[0], pos[1], pos[2])];
                    byte n = padded[pIndex(pos[0] + step[0], pos[1] + step[1], pos[2] + step[2])];
                    mask[j * Section.SIZE + i] = (b != Blocks.AIR && !Blocks.isOpaque(n)) ? b : 0;
                }
            }
            int plane = slice + (positive ? 1 : 0);
            for (int j = 0; j < Section.SIZE; j++) {
                for (int i = 0; i < Section.SIZE; ) {
                    byte b = mask[j * Section.SIZE + i];
                    if (b == 0) { i++; continue; }
                    int w = 1;
                    while (i + w < Section.SIZE && mask[j * Section.SIZE + i + w] == b) w++;
                    int h = 1;
                    grow:
                    while (j + h < Section.SIZE) {
                        int row = (j + h) * Section.SIZE + i;
                        for (int k = 0; k < w; k++) if (mask[row + k] != b) break grow;
                        h++;
                    }
                    for (int jj = 0; jj < h; jj++) {
                        Arrays.fill(mask, (j + jj) * Section.SIZE + i, (j + jj) * Section.SIZE + i + w, (byte) 0);
                    }
                    emitQuad(d, u, v, plane, i, j, w, h, face, b, positive);
                    i += w;
                }
            }
        }
    }

    private void emitQuad(int d, int u, int v, int plane, int i, int j, int w, int h,
                          int face, byte block, boolean positive) {
        if (count + 4 > out.length) out = Arrays.copyOf(out, out.length * 2);
        int tail = (face << 15) | (faceInfo[(block & 0xFF) * 6 + face] << 18);
        int sd = d * 5, su = u * 5, sv = v * 5;
        int base = (plane << sd) | tail;
        int u0 = i << su, u1 = (i + w) << su, v0 = j << sv, v1 = (j + h) << sv;
        // Corners in CCW order when seen from the +d side; reversed for -d faces.
        out[count++] = base | u0 | v0;
        if (positive) {
            out[count++] = base | u1 | v0;
            out[count++] = base | u1 | v1;
            out[count++] = base | u0 | v1;
        } else {
            out[count++] = base | u0 | v1;
            out[count++] = base | u1 | v1;
            out[count++] = base | u1 | v0;
        }
    }

    public static int quadCount(int[] vertices) {
        return vertices.length / 4;
    }
}
