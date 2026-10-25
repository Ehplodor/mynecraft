package net.mynecraft.client;

import static org.lwjgl.opengl.GL33.*;

/** GPU time of a frame from GL_TIME_ELAPSED queries, read back RING frames later so the CPU never waits. */
final class GpuTimer {
    private static final int RING = 4;
    private final int[] queries = new int[RING];
    private final boolean[] pending = new boolean[RING];
    private int frame;
    /** Most recent measured frame, in milliseconds. */
    double lastMillis;

    GpuTimer() {
        glGenQueries(queries);
    }

    void begin() {
        int q = frame % RING;
        if (pending[q]) {
            lastMillis = glGetQueryObjecti64(queries[q], GL_QUERY_RESULT) / 1e6;
            pending[q] = false;
        }
        glBeginQuery(GL_TIME_ELAPSED, queries[q]);
    }

    void end() {
        glEndQuery(GL_TIME_ELAPSED);
        pending[frame % RING] = true;
        frame++;
    }

    void dispose() {
        glDeleteQueries(queries);
    }
}
