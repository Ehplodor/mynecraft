package net.mynecraft.client;

import java.util.Arrays;

import static org.lwjgl.opengl.GL33.*;

/**
 * Frame timing for benchmarks: CPU frame times plus GPU time per frame from GL_TIME_ELAPSED
 * queries, read back a few frames later through a small ring so the CPU never waits on the GPU.
 */
final class FrameStats {
    private static final int RING = 4;
    private final int[] queries = new int[RING];
    private final boolean[] pending = new boolean[RING];
    private int frame;
    private long[] cpu = new long[4096], gpu = new long[4096];
    private int cpuCount, gpuCount;

    FrameStats() {
        glGenQueries(queries);
    }

    void beginGpu() {
        int q = frame % RING;
        if (pending[q]) collect(q); // the oldest query: long finished in practice
        glBeginQuery(GL_TIME_ELAPSED, queries[q]);
    }

    void endGpu() {
        glEndQuery(GL_TIME_ELAPSED);
        pending[frame % RING] = true;
        frame++;
    }

    private void collect(int q) {
        long ns = glGetQueryObjecti64(queries[q], GL_QUERY_RESULT);
        if (gpuCount == gpu.length) gpu = Arrays.copyOf(gpu, gpuCount * 2);
        gpu[gpuCount++] = ns;
        pending[q] = false;
    }

    void cpuFrame(long nanos) {
        if (cpuCount == cpu.length) cpu = Arrays.copyOf(cpu, cpuCount * 2);
        cpu[cpuCount++] = nanos;
    }

    private static double percentile(long[] sorted, int n, double p) {
        return n == 0 ? 0 : sorted[Math.min(n - 1, (int) Math.ceil(p * n) - 1)] / 1e6;
    }

    String report(double seconds) {
        long[] c = Arrays.copyOf(cpu, cpuCount), g = Arrays.copyOf(gpu, gpuCount);
        Arrays.sort(c);
        Arrays.sort(g);
        double gpuAvg = Arrays.stream(g).average().orElse(0) / 1e6;
        Runtime rt = Runtime.getRuntime();
        return String.format(
            "[benchmark] %d frames in %.1f s = %.1f FPS | frame ms p50 %.2f p95 %.2f p99 %.2f max %.2f | GPU ms avg %.2f p99 %.2f | heap %.0f MiB",
            cpuCount, seconds, cpuCount / seconds, percentile(c, cpuCount, 0.5), percentile(c, cpuCount, 0.95),
            percentile(c, cpuCount, 0.99), percentile(c, cpuCount, 1.0), gpuAvg, percentile(g, gpuCount, 0.99),
            (rt.totalMemory() - rt.freeMemory()) / 1048576.0);
    }

    void dispose() {
        glDeleteQueries(queries);
    }
}
