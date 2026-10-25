package net.mynecraft.world;

import java.util.Arrays;

/**
 * Per-frame visibility walk over sections: breadth-first from the camera's section, entering a
 * neighbour only if the current section connects the face we came in by to the face we leave by,
 * and never stepping back towards the camera (no direction and its opposite on one path).
 * Hidden terrain behind hills and solid ground is skipped before any draw is issued.
 */
public final class OcclusionCuller {
    /** World view used by the walk. */
    public interface Graph {
        /** Face connectivity of a section, or -1 if it is not available (stops the walk there). */
        int visibility(int cx, int sy, int cz);

        boolean inFrustum(int cx, int sy, int cz);
    }

    /** Receives each visible section once. */
    public interface Sink {
        void accept(int cx, int sy, int cz);
    }

    private static final int[] DX = {-1, 1, 0, 0, 0, 0}, DY = {0, 0, -1, 1, 0, 0}, DZ = {0, 0, 0, 0, -1, 1};

    private final int radius, width;
    private final boolean[] visited;
    private int[] queue = new int[1024]; // packed: local index << 12 | entry face + 1 << 6 | direction mask

    public OcclusionCuller(int radius) {
        this.radius = radius;
        this.width = 2 * radius + 1;
        this.visited = new boolean[width * width * Chunk.SECTIONS];
    }

    /** @return number of visible sections. */
    public int cull(int camCx, int camSy, int camCz, Graph g, Sink out) {
        Arrays.fill(visited, false);
        int head = 0, tail = 0, count = 0;
        int sy0 = Math.max(0, Math.min(Chunk.SECTIONS - 1, camSy));
        int start = index(radius, sy0, radius);
        visited[start] = true;
        queue = ensure(queue, tail + 1);
        queue[tail++] = start << 12;
        while (head < tail) {
            int e = queue[head++];
            int idx = e >>> 12, entry = ((e >> 6) & 63) - 1, dirs = e & 63;
            int lx = idx % width, rest = idx / width, sy = rest % Chunk.SECTIONS, lz = rest / Chunk.SECTIONS;
            int cx = camCx + lx - radius, cz = camCz + lz - radius;
            int vis = g.visibility(cx, sy, cz);
            if (vis < 0) continue;
            out.accept(cx, sy, cz);
            count++;
            for (int f = 0; f < 6; f++) {
                if ((dirs & (1 << (f ^ 1))) != 0) continue; // would turn back towards the camera
                if (entry >= 0 && !SectionVisibility.connected(vis, entry, f)) continue;
                int nlx = lx + DX[f], nsy = sy + DY[f], nlz = lz + DZ[f];
                if (nlx < 0 || nlx >= width || nlz < 0 || nlz >= width || nsy < 0 || nsy >= Chunk.SECTIONS) continue;
                int n = index(nlx, nsy, nlz);
                if (visited[n]) continue;
                visited[n] = true;
                if (!g.inFrustum(cx + DX[f], nsy, cz + DZ[f])) continue;
                queue = ensure(queue, tail + 1);
                queue[tail++] = n << 12 | ((f ^ 1) + 1) << 6 | dirs | 1 << f;
            }
        }
        return count;
    }

    private int index(int lx, int sy, int lz) {
        return (lz * Chunk.SECTIONS + sy) * width + lx;
    }

    private static int[] ensure(int[] a, int n) {
        return n <= a.length ? a : Arrays.copyOf(a, a.length * 2);
    }
}
