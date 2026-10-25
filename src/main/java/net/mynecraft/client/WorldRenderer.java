package net.mynecraft.client;

import net.mynecraft.world.Chunk;
import net.mynecraft.world.ChunkMesher;
import net.mynecraft.world.OcclusionCuller;
import net.mynecraft.world.QuadAllocator;
import net.mynecraft.world.SectionVisibility;
import net.mynecraft.world.Section;
import net.mynecraft.world.World;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.opengl.GL33.*;

/**
 * Owns GPU buffers for section meshes. Generation and meshing run on worker threads;
 * only buffer uploads happen on the render thread.
 *
 * <p>All sections live in one shared vertex buffer (sub-allocated by {@link QuadAllocator}), so each
 * pass is a single {@code glMultiDrawElementsBaseVertex} call. Visible sections come from an
 * occlusion walk ({@link OcclusionCuller}) restricted to the view frustum.
 *
 * <p>Three passes per frame: opaque sections front to back (cheap early depth rejection), cutout
 * (alpha-tested leaves, glass, plants) with a separate program so the opaque one never pays for
 * {@code discard}, then translucent sections back to front with blending and no depth writes.
 */
final class WorldRenderer {
    // Worst case: every block shows 6 unmerged faces (e.g. glass and leaves alternating).
    private static final int MAX_QUADS = Section.VOLUME * 6;
    private static final int STRIDE = ChunkMesher.INTS_PER_VERTEX * 4;
    private static final int QUAD_BYTES = 4 * STRIDE;
    private static final int INITIAL_QUADS = 1 << 20; // 32 MiB, grows by doubling

    private record Pending(long key, int cx, int sy, int cz, long seq, ChunkMesher.Result mesh) {}

    private static final class Mesh {
        final int cx, sy, cz;
        int offset = -1, size;          // allocation in the shared buffer, in quads
        int opaque, cutout, translucent; // quad counts, stored back to back in the allocation
        float distance;
        Mesh(int cx, int sy, int cz) {
            this.cx = cx; this.sy = sy; this.cz = cz;
        }
    }

    /** One chunk shader variant and its uniform locations. */
    private static final class Program {
        final Shader shader;
        final int uViewProj, uCamChunk, uCamOffset, uFogColor, uFogEnd, uDaylight, uMinLight;
        Program(String defines) {
            shader = new Shader("/shaders/chunk.vert", "/shaders/chunk.frag", defines);
            uViewProj = shader.uniform("uViewProj");
            uCamChunk = shader.uniform("uCamChunk");
            uCamOffset = shader.uniform("uCamOffset");
            uFogColor = shader.uniform("uFogColor");
            uFogEnd = shader.uniform("uFogEnd");
            uDaylight = shader.uniform("uDaylight");
            uMinLight = shader.uniform("uMinLight");
            glUseProgram(shader.program);
            glUniform1i(shader.uniform("uTextures"), 0);
            glUniform1i(shader.uniform("uBiome"), 1);
            // Tint colours by type: none, grass, foliage, water. Grass and foliage come from the biome map.
            glUniform3fv(shader.uniform("uTints"), new float[] {
                1, 1, 1, 0.57f, 0.74f, 0.35f, 0.47f, 0.70f, 0.27f, 0.25f, 0.46f, 0.89f});
        }
    }

    private final World world;
    private final Program opaqueProgram, cutoutProgram, translucentProgram;
    private final int ebo;
    private final HashMap<Long, Mesh> meshes = new HashMap<>();
    private final QuadAllocator allocator = new QuadAllocator(INITIAL_QUADS);
    private final int vao;
    private int vbo;
    private final OcclusionCuller culler;
    /**
     * Direct-indexed view of visible chunks for the per-frame walk: column slot = (cx mod 64, cz mod 64),
     * which never collides while the render radius stays below 32. Avoids hashing and boxing on the
     * ~17k section lookups of a frame.
     */
    private static final int GRID = 64;
    private final long[] gridOwner = new long[GRID * GRID];          // chunk key of the slot, or EMPTY
    private final int[] gridVisibility = new int[GRID * GRID * Chunk.SECTIONS];
    private final Mesh[] gridMesh = new Mesh[GRID * GRID * Chunk.SECTIONS];
    private static final long EMPTY = Long.MIN_VALUE;
    boolean occlusion = true;
    int frustumSections = -1; // sections passing the frustum alone (stats), -1 when not measured
    boolean measureFrustum;
    private IntBuffer drawCounts = BufferUtils.createIntBuffer(1024), drawBase = BufferUtils.createIntBuffer(1024);
    private PointerBuffer drawOffsets = BufferUtils.createPointerBuffer(1024);
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
    private Mesh[] drawList = new Mesh[256];

    private final AtomicInteger pendingJobs = new AtomicInteger();
    int drawnSections, totalQuads;
    long cullNanos, cullFrames; // CPU time spent selecting and sorting sections (benchmark stats)

    private final BiomeMap biomes;

    WorldRenderer(World world, int[] faceInfo, TextureArray textures, BiomeMap biomes, int cullRadius) {
        this.world = world;
        Arrays.fill(gridOwner, EMPTY);
        this.biomes = biomes;
        this.culler = new OcclusionCuller(cullRadius);
        this.textures = textures;
        this.meshers = ThreadLocal.withInitial(() -> new ChunkMesher(faceInfo));
        opaqueProgram = new Program("");
        cutoutProgram = new Program("#define CUTOUT\n");
        translucentProgram = new Program("#define TRANSLUCENT\n");

        int[] indices = new int[MAX_QUADS * 6];
        for (int q = 0, v = 0, i = 0; q < MAX_QUADS; q++, v += 4) {
            indices[i++] = v; indices[i++] = v + 1; indices[i++] = v + 2;
            indices[i++] = v + 2; indices[i++] = v + 3; indices[i++] = v;
        }
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) INITIAL_QUADS * QUAD_BYTES, GL_DYNAMIC_DRAW);
        bindAttributes();
        glBindVertexArray(0);

        workers = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
            daemon("mesher"));
        editWorker = Executors.newSingleThreadExecutor(daemon("edit-mesher"));
    }

    private static void bindAttributes() {
        glVertexAttribIPointer(0, 1, GL_UNSIGNED_INT, STRIDE, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribIPointer(1, 1, GL_UNSIGNED_INT, STRIDE, 4);
        glEnableVertexAttribArray(1);
    }

    /** Doubles the shared buffer, copying the old contents GPU-side. */
    private void grow() {
        int oldQuads = allocator.capacity(), newQuads = oldQuads * 2;
        int nb = glGenBuffers();
        glBindBuffer(GL_COPY_WRITE_BUFFER, nb);
        glBufferData(GL_COPY_WRITE_BUFFER, (long) newQuads * QUAD_BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_COPY_READ_BUFFER, vbo);
        glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0, 0, (long) oldQuads * QUAD_BYTES);
        glDeleteBuffers(vbo);
        vbo = nb;
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        bindAttributes();
        glBindVertexArray(0);
        allocator.grow(newQuads);
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /** Schedules all sections of a chunk that just became visible (bulk priority). */
    private static int column(int cx, int cz) {
        return ((cx & (GRID - 1)) * GRID + (cz & (GRID - 1))) * Chunk.SECTIONS;
    }

    void chunkVisible(int cx, int cz) {
        visibleChunks.add(World.key(cx, cz));
        int col = column(cx, cz);
        gridOwner[col / Chunk.SECTIONS] = World.key(cx, cz);
        Arrays.fill(gridVisibility, col, col + Chunk.SECTIONS, SectionVisibility.ALL); // open until meshed
        Arrays.fill(gridMesh, col, col + Chunk.SECTIONS, null);
        biomes.chunkVisible(cx, cz);
        long[] seqs = new long[Chunk.SECTIONS];
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) seqs[sy] = request(World.sectionKey(cx, sy, cz));
        pendingJobs.incrementAndGet();
        workers.execute(() -> {
            for (int sy = 0; sy < Chunk.SECTIONS; sy++) meshSection(cx, sy, cz, seqs[sy], uploads);
            pendingJobs.decrementAndGet();
        });
    }

    /** Frees GPU buffers of a chunk that left render distance; in-flight meshes for it become stale. */
    void chunkHidden(int cx, int cz) {
        visibleChunks.remove(World.key(cx, cz));
        int col = column(cx, cz);
        if (gridOwner[col / Chunk.SECTIONS] == World.key(cx, cz)) {
            gridOwner[col / Chunk.SECTIONS] = EMPTY;
            Arrays.fill(gridMesh, col, col + Chunk.SECTIONS, null);
        }
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
            long k = World.sectionKey(cx, sy, cz);
            latest.remove(k);
            Mesh m = meshes.remove(k);
            if (m != null) {
                totalQuads -= m.opaque + m.cutout + m.translucent;
                if (m.offset >= 0) allocator.release(m.offset, m.size);
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
        queue.add(new Pending(World.sectionKey(cx, sy, cz), cx, sy, cz, seq, meshers.get().mesh(world, c, sy)));
    }

    /**
     * Re-meshes the section holding block (x, y, z) and every section that shares a face, edge or
     * corner with it when the block lies on a border (faces and ambient occlusion read neighbours).
     */
    void blockChanged(int x, int y, int z) {
        int cx = x >> 4, sy = Chunk.sectionIndex(y), cz = z >> 4, lx = x & 15, ly = y & 15, lz = z & 15;
        for (int dx = -1; dx <= 1; dx++) {
            if ((dx == -1 && lx != 0) || (dx == 1 && lx != 15)) continue;
            for (int dy = -1; dy <= 1; dy++) {
                if ((dy == -1 && ly != 0) || (dy == 1 && ly != 15)) continue;
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dz == -1 && lz != 0) || (dz == 1 && lz != 15)) continue;
                    remesh(cx + dx, sy + dy, cz + dz, true);
                }
            }
        }
    }

    /** Re-meshes a section by key (e.g. after a light change); ignored if its chunk is not visible. */
    void sectionChanged(long key, boolean urgent) {
        int cx = (int) (key >> 32), sy = (int) (key & 31), cz = (int) ((key << 32) >> 37);
        remesh(cx, sy, cz, urgent);
    }

    private void remesh(int cx, int sy, int cz, boolean urgent) {
        if (sy < 0 || sy >= Chunk.SECTIONS || !visibleChunks.contains(World.key(cx, cz))) return;
        long seq = request(World.sectionKey(cx, sy, cz));
        pendingJobs.incrementAndGet();
        (urgent ? editWorker : workers).execute(() -> {
            meshSection(cx, sy, cz, seq, urgent ? urgentUploads : uploads);
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
        ChunkMesher.Result r = p.mesh;
        int slot = column(p.cx, p.cz) + p.sy;
        boolean inGrid = gridOwner[slot / Chunk.SECTIONS] == World.key(p.cx, p.cz);
        if (inGrid) gridVisibility[slot] = r.visibility();
        Mesh m = meshes.get(p.key);
        if (m == null) {
            if (r.quads() == 0) return;
            m = new Mesh(p.cx, p.sy, p.cz);
            meshes.put(p.key, m);
            if (inGrid) gridMesh[slot] = m;
        } else {
            totalQuads -= m.opaque + m.cutout + m.translucent;
            if (m.offset >= 0) allocator.release(m.offset, m.size);
            m.offset = -1;
        }
        int quads = r.quads();
        if (quads > 0) {
            int off;
            while ((off = allocator.allocate(quads)) < 0) grow();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferSubData(GL_ARRAY_BUFFER, (long) off * QUAD_BYTES, r.data());
            m.offset = off;
            m.size = quads;
        }
        m.opaque = r.opaqueQuads();
        m.cutout = r.cutoutQuads();
        m.translucent = r.translucentQuads();
        totalQuads += quads;
    }

    Matrix4f viewProj() {
        return viewProj;
    }

    /** Sky light multiplier, see {@link DayCycle}. */
    float daylight = 1;
    /** Debug: lowest light level shown (1 = everything fully lit). */
    float minLight;

    void render(Camera cam, float aspect, float far, float[] fog) {
        drainUploads(4_000_000L);
        cam.viewProj(viewProj, aspect, far);
        frustum.set(viewProj);
        viewProj.get(matrixBuf);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D_ARRAY, textures.id);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D_ARRAY, biomes.id);
        glActiveTexture(GL_TEXTURE0);

        double px = cam.pos.x, py = cam.pos.y, pz = cam.pos.z;
        int camCx = (int) Math.floor(px) >> 4, camCz = (int) Math.floor(pz) >> 4;
        int camSy = Chunk.sectionIndex((int) Math.floor(py));
        drawCount = 0;
        long cullStart = System.nanoTime();
        if (occlusion) {
            culler.cull(camCx, camSy, camCz, new OcclusionCuller.Graph() {
                public int visibility(int cx, int sy, int cz) {
                    int col = column(cx, cz);
                    if (gridOwner[col / Chunk.SECTIONS] != World.key(cx, cz)) return -1;
                    return gridVisibility[col + sy];
                }
                public boolean inFrustum(int cx, int sy, int cz) {
                    float ox = (float) ((cx << 4) - px), oy = (float) (Chunk.sectionMinY(sy) - py), oz = (float) ((cz << 4) - pz);
                    return frustum.testAab(ox, oy, oz, ox + 16, oy + 16, oz + 16);
                }
            }, (cx, sy, cz) -> addDraw(gridMesh[column(cx, cz) + sy], px, py, pz, false));
        }
        if (!occlusion || measureFrustum) {
            int before = drawCount;
            for (Mesh m : meshes.values()) addDraw(m, px, py, pz, true);
            frustumSections = drawCount - before;
            if (occlusion) drawCount = before; // only measuring
        }
        Arrays.sort(drawList, 0, drawCount, (a, b) -> Float.compare(a.distance, b.distance));
        drawnSections = drawCount;
        cullNanos += System.nanoTime() - cullStart;
        cullFrames++;

        glBindVertexArray(vao);
        float offX = (float) (px - (camCx << 4)), offY = (float) (py - Chunk.MIN_Y), offZ = (float) (pz - (camCz << 4));
        pass(opaqueProgram, false, 0, fog, far, camCx, camCz, offX, offY, offZ);
        pass(cutoutProgram, false, 1, fog, far, camCx, camCz, offX, offY, offZ);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        pass(translucentProgram, true, 2, fog, far, camCx, camCz, offX, offY, offZ);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glBindVertexArray(0);
    }

    private int drawCount;

    private void addDraw(Mesh m, double px, double py, double pz, boolean testFrustum) {
        if (m == null || m.offset < 0) return;
        float ox = (float) ((m.cx << 4) - px), oy = (float) (Chunk.sectionMinY(m.sy) - py), oz = (float) ((m.cz << 4) - pz);
        if (testFrustum && !frustum.testAab(ox, oy, oz, ox + 16, oy + 16, oz + 16)) return;
        float cx = ox + 8, cy = oy + 8, cz = oz + 8;
        m.distance = cx * cx + cy * cy + cz * cz;
        if (drawCount == drawList.length) drawList = Arrays.copyOf(drawList, drawCount * 2);
        drawList[drawCount++] = m;
    }

    /** One multi-draw for the whole layer: per-section counts and base vertices, shared indices. */
    private void pass(Program p, boolean backToFront, int layer, float[] fog, float far,
                      int camCx, int camCz, float offX, float offY, float offZ) {
        if (drawCounts.capacity() < drawCount) {
            int cap = Integer.highestOneBit(drawCount) << 1;
            drawCounts = BufferUtils.createIntBuffer(cap);
            drawBase = BufferUtils.createIntBuffer(cap);
            drawOffsets = BufferUtils.createPointerBuffer(cap);
        }
        drawCounts.clear();
        drawBase.clear();
        drawOffsets.clear();
        for (int k = 0; k < drawCount; k++) {
            Mesh m = drawList[backToFront ? drawCount - 1 - k : k];
            int quads = layer == 0 ? m.opaque : layer == 1 ? m.cutout : m.translucent;
            if (quads == 0) continue;
            int first = m.offset + (layer == 0 ? 0 : layer == 1 ? m.opaque : m.opaque + m.cutout);
            drawCounts.put(quads * 6);
            drawBase.put(first * 4);
            drawOffsets.put(0L);
        }
        if (drawCounts.position() == 0) return;
        drawCounts.flip();
        drawBase.flip();
        drawOffsets.flip();
        glUseProgram(p.shader.program);
        glUniformMatrix4fv(p.uViewProj, false, matrixBuf);
        glUniform2i(p.uCamChunk, camCx, camCz);
        glUniform3f(p.uCamOffset, offX, offY, offZ);
        glUniform3f(p.uFogColor, fog[0], fog[1], fog[2]);
        glUniform1f(p.uFogEnd, far);
        glUniform1f(p.uDaylight, daylight);
        glUniform1f(p.uMinLight, minLight);
        glMultiDrawElementsBaseVertex(GL_TRIANGLES, drawCounts, GL_UNSIGNED_INT, drawOffsets, drawBase);
    }

    /** True once all scheduled meshing work has been uploaded. */
    boolean idle() {
        return pendingJobs.get() == 0 && uploads.isEmpty() && urgentUploads.isEmpty();
    }

    int meshCount() {
        return meshes.size();
    }

    /** GPU buffer usage: used and allocated quads. */
    long[] bufferUsage() {
        return new long[] {allocator.used(), allocator.capacity()};
    }

    void dispose() {
        workers.shutdownNow();
        editWorker.shutdownNow();
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteBuffers(ebo);
        glDeleteTextures(textures.id);
        biomes.dispose();
        for (Program p : new Program[] {opaqueProgram, cutoutProgram, translucentProgram}) glDeleteProgram(p.shader.program);
    }
}
