package net.mynecraft.client;

import net.mynecraft.world.Chunk;
import net.mynecraft.world.ChunkMesher;
import net.mynecraft.world.Section;
import net.mynecraft.world.World;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.lwjgl.opengl.GL33.*;

/**
 * Owns GPU buffers for section meshes. Generation and meshing run on worker threads;
 * only buffer uploads happen on the render thread.
 */
final class WorldRenderer {
    // Worst case for greedy meshing is a 3D checkerboard: half the blocks, 6 unmerged faces each.
    private static final int MAX_QUADS = Section.VOLUME / 2 * 6;

    private record Pending(long key, int cx, int sy, int cz, long seq, int[] vertices) {}

    private static final class Mesh {
        final int cx, sy, cz, vao, vbo;
        int indexCount;
        Mesh(int cx, int sy, int cz, int vao, int vbo) {
            this.cx = cx; this.sy = sy; this.cz = cz; this.vao = vao; this.vbo = vbo;
        }
    }

    private final World world;
    private final Shader shader;
    private final int ebo;
    private final HashMap<Long, Mesh> meshes = new HashMap<>();
    /** Latest requested version per section: a slower, older job must never overwrite a newer mesh. */
    private final ConcurrentHashMap<Long, Long> latest = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final HashSet<Long> visibleChunks = new HashSet<>();
    private final ConcurrentLinkedQueue<Pending> uploads = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Pending> urgentUploads = new ConcurrentLinkedQueue<>();
    private final ExecutorService workers;
    /** Player edits get their own thread so they never wait behind bulk world loading. */
    private final ExecutorService editWorker;
    private final ThreadLocal<ChunkMesher> meshers;
    private final TextureArray textures;
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f viewProj = new Matrix4f();
    private final float[] matrixBuf = new float[16];
    private final int uViewProj, uOrigin, uFogColor, uFogEnd;

    private final AtomicInteger pendingJobs = new AtomicInteger();
    int drawnSections, totalQuads;

    WorldRenderer(World world, int[] faceInfo, TextureArray textures) {
        this.world = world;
        this.textures = textures;
        this.meshers = ThreadLocal.withInitial(() -> new ChunkMesher(faceInfo));
        this.shader = new Shader("/shaders/chunk.vert", "/shaders/chunk.frag");
        uViewProj = shader.uniform("uViewProj");
        uOrigin = shader.uniform("uOrigin");
        uFogColor = shader.uniform("uFogColor");
        uFogEnd = shader.uniform("uFogEnd");

        int[] indices = new int[MAX_QUADS * 6];
        for (int q = 0, v = 0, i = 0; q < MAX_QUADS; q++, v += 4) {
            indices[i++] = v; indices[i++] = v + 1; indices[i++] = v + 2;
            indices[i++] = v + 2; indices[i++] = v + 3; indices[i++] = v;
        }
        // Core profile needs a bound VAO to touch GL_ELEMENT_ARRAY_BUFFER.
        int initVao = glGenVertexArrays();
        glBindVertexArray(initVao);
        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        glBindVertexArray(0);
        glDeleteVertexArrays(initVao);

        glUseProgram(shader.program);
        glUniform1i(shader.uniform("uTextures"), 0);
        // Plains-like grass colour; replaced by per-biome colormaps later.
        glUniform3f(shader.uniform("uTint"), 0.57f, 0.74f, 0.35f);

        workers = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
            daemon("mesher"));
        editWorker = Executors.newSingleThreadExecutor(daemon("edit-mesher"));
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private static long sectionKey(int cx, int sy, int cz) {
        return ((long) cx << 32) | ((cz & 0x0FFFFFFFL) << 4) | sy;
    }

    /** Schedules all sections of a chunk that just became visible (bulk priority). */
    void chunkVisible(int cx, int cz) {
        visibleChunks.add(World.key(cx, cz));
        long[] seqs = new long[Chunk.SECTIONS];
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) seqs[sy] = request(sectionKey(cx, sy, cz));
        pendingJobs.incrementAndGet();
        workers.execute(() -> {
            for (int sy = 0; sy < Chunk.SECTIONS; sy++) meshSection(cx, sy, cz, seqs[sy], uploads);
            pendingJobs.decrementAndGet();
        });
    }

    /** Frees GPU buffers of a chunk that left render distance; in-flight meshes for it become stale. */
    void chunkHidden(int cx, int cz) {
        visibleChunks.remove(World.key(cx, cz));
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
            long k = sectionKey(cx, sy, cz);
            latest.remove(k);
            Mesh m = meshes.remove(k);
            if (m != null) {
                totalQuads -= m.indexCount / 6;
                glDeleteBuffers(m.vbo);
                glDeleteVertexArrays(m.vao);
            }
        }
    }

    private long request(long key) {
        long seq = sequence.incrementAndGet();
        latest.put(key, seq);
        return seq;
    }

    private void meshSection(int cx, int sy, int cz, long seq, ConcurrentLinkedQueue<Pending> queue) {
        Chunk c = world.chunk(cx, cz);
        if (c == null) return;
        int[] v = meshers.get().mesh(world, c, sy);
        queue.add(new Pending(sectionKey(cx, sy, cz), cx, sy, cz, seq, v));
    }

    /**
     * Re-meshes the section holding block (x, y, z), plus neighbouring sections when the block
     * lies on a section border (their faces against it may appear or disappear).
     */
    void blockChanged(int x, int y, int z) {
        int cx = x >> 4, sy = y >> 4, cz = z >> 4, lx = x & 15, ly = y & 15, lz = z & 15;
        remesh(cx, sy, cz);
        if (lx == 0) remesh(cx - 1, sy, cz);
        if (lx == 15) remesh(cx + 1, sy, cz);
        if (ly == 0 && sy > 0) remesh(cx, sy - 1, cz);
        if (ly == 15 && sy < Chunk.SECTIONS - 1) remesh(cx, sy + 1, cz);
        if (lz == 0) remesh(cx, sy, cz - 1);
        if (lz == 15) remesh(cx, sy, cz + 1);
    }

    private void remesh(int cx, int sy, int cz) {
        if (!visibleChunks.contains(World.key(cx, cz))) return;
        long seq = request(sectionKey(cx, sy, cz));
        pendingJobs.incrementAndGet();
        editWorker.execute(() -> {
            meshSection(cx, sy, cz, seq, urgentUploads);
            pendingJobs.decrementAndGet();
        });
    }

    private void drainUploads(long budgetNanos) {
        Pending p;
        while ((p = urgentUploads.poll()) != null) upload(p);
        long deadline = System.nanoTime() + budgetNanos;
        while (System.nanoTime() < deadline && (p = uploads.poll()) != null) upload(p);
        glBindVertexArray(0);
    }

    private void upload(Pending p) {
        Long want = latest.get(p.key);
        if (want == null || p.seq < want) return; // stale, or chunk hidden meanwhile
        Mesh m = meshes.get(p.key);
        int quads = p.vertices.length / 4;
        if (m == null) {
            if (quads == 0) return;
            int vao = glGenVertexArrays();
            glBindVertexArray(vao);
            int vbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glVertexAttribIPointer(0, 1, GL_UNSIGNED_INT, 4, 0);
            glEnableVertexAttribArray(0);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            m = new Mesh(p.cx, p.sy, p.cz, vao, vbo);
            meshes.put(p.key, m);
        } else {
            totalQuads -= m.indexCount / 6;
            glBindBuffer(GL_ARRAY_BUFFER, m.vbo);
        }
        // Reallocating the store (orphaning) avoids stalling on a buffer the GPU may still read.
        glBufferData(GL_ARRAY_BUFFER, p.vertices, GL_STATIC_DRAW);
        m.indexCount = quads * 6;
        totalQuads += quads;
    }

    Matrix4f viewProj() {
        return viewProj;
    }

    void render(Camera cam, float aspect, float far, float[] fog) {
        drainUploads(4_000_000L);
        cam.viewProj(viewProj, aspect, far);
        frustum.set(viewProj);
        glUseProgram(shader.program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D_ARRAY, textures.id);
        glUniformMatrix4fv(uViewProj, false, viewProj.get(matrixBuf));
        glUniform3f(uFogColor, fog[0], fog[1], fog[2]);
        glUniform1f(uFogEnd, far);

        double px = cam.pos.x, py = cam.pos.y, pz = cam.pos.z;
        drawnSections = 0;
        for (Mesh m : meshes.values()) {
            if (m.indexCount == 0) continue;
            float ox = (float) ((m.cx << 4) - px), oy = (float) ((m.sy << 4) - py), oz = (float) ((m.cz << 4) - pz);
            if (!frustum.testAab(ox, oy, oz, ox + 16, oy + 16, oz + 16)) continue;
            glUniform3f(uOrigin, ox, oy, oz);
            glBindVertexArray(m.vao);
            glDrawElements(GL_TRIANGLES, m.indexCount, GL_UNSIGNED_INT, 0);
            drawnSections++;
        }
        glBindVertexArray(0);
    }

    /** True once all scheduled meshing work has been uploaded. */
    boolean idle() {
        return pendingJobs.get() == 0 && uploads.isEmpty() && urgentUploads.isEmpty();
    }

    int meshCount() {
        return meshes.size();
    }

    void dispose() {
        workers.shutdownNow();
        editWorker.shutdownNow();
        for (Mesh m : meshes.values()) {
            glDeleteBuffers(m.vbo);
            glDeleteVertexArrays(m.vao);
        }
        glDeleteBuffers(ebo);
        glDeleteTextures(textures.id);
        glDeleteProgram(shader.program);
    }
}
