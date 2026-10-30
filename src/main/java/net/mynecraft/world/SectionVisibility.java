package net.mynecraft.world;

/**
 * Which pairs of a section's 6 faces are connected through non-opaque blocks ("cave culling",
 * as popularised by Minecraft 1.8). 15 bits, one per unordered face pair. Computed once per mesh by
 * flood-filling the section; used every frame by {@link OcclusionCuller}.
 *
 * <p>Face order matches the mesher: 0 -x, 1 +x, 2 -y, 3 +y, 4 -z, 5 +z. Not thread-safe.
 */
public final class SectionVisibility {
    public static final int ALL = (1 << 15) - 1;
    private static final int[] PAIR = new int[36];

    static {
        for (int a = 0, bit = 0; a < 6; a++)
            for (int b = a + 1; b < 6; b++, bit++) PAIR[a * 6 + b] = PAIR[b * 6 + a] = bit;
    }

    private final int[] stack = new int[Section.VOLUME];
    private final boolean[] seen = new boolean[Section.VOLUME];

    public static boolean connected(int visibility, int faceA, int faceB) {
        return faceA != faceB && (visibility >> PAIR[faceA * 6 + faceB] & 1) != 0;
    }

    /** Faces touched by a cell: bit per section border the cell lies on. */
    private static int borders(int i) {
        int x = i & 15, y = i >> 8, z = (i >> 4) & 15, f = 0;
        if (x == 0) f |= 1; else if (x == 15) f |= 2;
        if (y == 0) f |= 4; else if (y == 15) f |= 8;
        if (z == 0) f |= 16; else if (z == 15) f |= 32;
        return f;
    }

    public int compute(Section s) {
        if (s == null) return ALL;
        short[] blocks = s.blocks;
        java.util.Arrays.fill(seen, false);
        int result = 0;
        for (int start = 0; start < Section.VOLUME; start++) {
            if (seen[start] || Blocks.isOpaque(blocks[start])) continue;
            // Flood one connected region of open cells and collect the faces it reaches.
            int faces = 0, top = 0;
            stack[top++] = start;
            seen[start] = true;
            while (top > 0) {
                int i = stack[--top];
                faces |= borders(i);
                int x = i & 15, y = i >> 8, z = (i >> 4) & 15;
                if (x > 0) top = visit(blocks, i - 1, top);
                if (x < 15) top = visit(blocks, i + 1, top);
                if (y > 0) top = visit(blocks, i - 256, top);
                if (y < 15) top = visit(blocks, i + 256, top);
                if (z > 0) top = visit(blocks, i - 16, top);
                if (z < 15) top = visit(blocks, i + 16, top);
            }
            for (int a = 0; a < 6; a++) {
                if ((faces >> a & 1) == 0) continue;
                for (int b = a + 1; b < 6; b++) if ((faces >> b & 1) != 0) result |= 1 << PAIR[a * 6 + b];
            }
            if (result == ALL) break;
        }
        return result;
    }

    private int visit(short[] blocks, int i, int top) {
        if (!seen[i] && !Blocks.isOpaque(blocks[i])) {
            seen[i] = true;
            stack[top++] = i;
        }
        return top;
    }
}
