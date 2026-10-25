package net.mynecraft.client;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.ChunkMesher;
import net.mynecraft.world.Section;
import net.mynecraft.world.World;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    private record Pending(int cx, int sy, int cz, int[] vertices) {}

    private static final class Mesh {
        final int cx, sy, cz, vao, vbo, indexCount;
        Mesh(int cx, int sy, int cz, int vao, int vbo, int indexCount) {
            this.cx = cx; this.sy = sy; this.cz = cz; this.vao = vao; this.vbo = vbo; this.indexCount = indexCount;
        }
    }

    private final World world;
    private final Shader shader;
    private final int ebo;
    private final List<Mesh> meshes = new ArrayList<>();
    private final ConcurrentLinkedQueue<Pending> uploads = new ConcurrentLinkedQueue<>();
    private final ExecutorService workers;
    private final ThreadLocal<ChunkMesher> meshers = ThreadLocal.withInitial(ChunkMesher::new);
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f viewProj = new Matrix4f();
    private final float[] matrixBuf = new float[16];
    private final int uViewProj, uOrigin, uFogColor, uFogEnd;

    private final java.util.concurrent.atomic.AtomicInteger pendingJobs = new java.util.concurrent.atomic.AtomicInteger();
    int drawnSections, totalQuads;

    WorldRenderer(World world) {
        this.world = world;
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
        float[] colors = new float[16 * 3];
        System.arraycopy(Blocks.COLORS, 0, colors, 0, Blocks.COLORS.length);
        glUniform3fv(shader.uniform("uColors"), colors);

        workers = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1), r -> {
            Thread t = new Thread(r, "mesher");
            t.setDaemon(true);
            return t;
        });
    }

    /** Generates a square of chunks around the origin, then meshes each once its neighbours exist. */
    void loadArea(int radius) {
        List<Runnable> gen = new ArrayList<>();
        for (int cx = -radius - 1; cx <= radius + 1; cx++)
            for (int cz = -radius - 1; cz <= radius + 1; cz++) {
                int x = cx, z = cz;
                gen.add(() -> world.getOrGenerate(x, z));
            }
        gen.parallelStream().forEach(Runnable::run);

        for (int cx = -radius; cx <= radius; cx++)
            for (int cz = -radius; cz <= radius; cz++) {
                int x = cx, z = cz;
                pendingJobs.incrementAndGet();
                workers.execute(() -> {
                    Chunk c = world.chunk(x, z);
                    ChunkMesher mesher = meshers.get();
                    for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
                        int[] v = mesher.mesh(world, c, sy);
                        if (v.length > 0) uploads.add(new Pending(x, sy, z, v));
                    }
                    pendingJobs.decrementAndGet();
                });
            }
    }

    private void drainUploads(long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        Pending p;
        while (System.nanoTime() < deadline && (p = uploads.poll()) != null) {
            int vao = glGenVertexArrays();
            glBindVertexArray(vao);
            int vbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, p.vertices, GL_STATIC_DRAW);
            glVertexAttribIPointer(0, 1, GL_UNSIGNED_INT, 4, 0);
            glEnableVertexAttribArray(0);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            meshes.add(new Mesh(p.cx, p.sy, p.cz, vao, vbo, p.vertices.length / 4 * 6));
            totalQuads += p.vertices.length / 4;
        }
        glBindVertexArray(0);
    }

    void render(Camera cam, float aspect, float far, float[] fog) {
        drainUploads(4_000_000L);
        cam.viewProj(viewProj, aspect, far);
        frustum.set(viewProj);
        glUseProgram(shader.program);
        glUniformMatrix4fv(uViewProj, false, viewProj.get(matrixBuf));
        glUniform3f(uFogColor, fog[0], fog[1], fog[2]);
        glUniform1f(uFogEnd, far);

        double px = cam.pos.x, py = cam.pos.y, pz = cam.pos.z;
        drawnSections = 0;
        for (Mesh m : meshes) {
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
        return pendingJobs.get() == 0 && uploads.isEmpty();
    }

    int meshCount() {
        return meshes.size();
    }

    void dispose() {
        workers.shutdownNow();
        for (Mesh m : meshes) {
            glDeleteBuffers(m.vbo);
            glDeleteVertexArrays(m.vao);
        }
        glDeleteBuffers(ebo);
        glDeleteProgram(shader.program);
    }
}
