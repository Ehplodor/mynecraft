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
 * pass is a single {@code glMultiDrawElementsBaseVertex} call. There are no vertex attributes: the
 * buffer is exposed as a texture buffer of 16-byte quad records and the vertex shader builds each
 * corner from {@code gl_VertexID / 4} ("vertex pulling"), half the memory of four 8-byte vertices.
 * A shared index buffer (0,1,2, 2,3,0 per quad) keeps it at 4 shader runs per quad thanks to the
 * post-transform vertex cache, instead of 6 with non-indexed draws. Visible sections come from an
 * occlusion walk ({@link OcclusionCuller}) restricted to the view frustum.
 *
 * <p>Three passes per frame: opaque sections front to back (cheap early depth rejection), cutout
 * (alpha-tested leaves, glass, plants) with a separate program so the opaque one never pays for
 * {@code discard}, then translucent sections back to front with blending and no depth writes.
 */
final class WorldRenderer {
    // Worst case: every block shows 6 unmerged faces (e.g. glass and leaves alternating).
    private static final int QUAD_BYTES = ChunkMesher.INTS_PER_QUAD * 4;
    // Largest section mesh: every block shows 6 unmerged faces (e.g. glass and leaves alternating).
    private static final int MAX_SECTION_QUADS = Section.VOLUME * 6;
    private static final int INITIAL_QUADS = 1 << 21; // 32 MiB, grows by doubling

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
        final int uViewProj, uCamChunk, uCamOffset, uFogColor, uFogEnd, uDaylight, uMinLight, uTime,
            uShadowVP, uSunDir, uShadowStrength, uSkyColor, uCamWorld, uScreen, uDepthRange, uHideWater;
        Program(String defines) {
            shader = new Shader("/shaders/chunk.vert", "/shaders/chunk.frag", defines);
            uViewProj = shader.uniform("uViewProj");
            uCamChunk = shader.uniform("uCamChunk");
            uCamOffset = shader.uniform("uCamOffset");
            uFogColor = shader.uniform("uFogColor");
            uFogEnd = shader.uniform("uFogEnd");
            uDaylight = shader.uniform("uDaylight");
            glUseProgram(shader.program);
            glUniform1i(shader.uniform("uQuads"), 2);
            uMinLight = shader.uniform("uMinLight");
            uTime = shader.uniform("uTime");
            uShadowVP = shader.uniform("uShadowVP");
            uSunDir = shader.uniform("uSunDir");
            uShadowStrength = shader.uniform("uShadowStrength");
            uSkyColor = shader.uniform("uSkyColor");
            uCamWorld = shader.uniform("uCamWorld");
            uScreen = shader.uniform("uScreen");
            uDepthRange = shader.uniform("uDepthRange");
            uHideWater = shader.uniform("uHideWater");
            glUseProgram(shader.program);
            glUniform1i(shader.uniform("uTextures"), 0);
            glUniform1i(shader.uniform("uBiome"), 1);
            glUniform1i(shader.uniform("uShadowMap"), 3);
            glUniform1i(shader.uniform("uSceneColor"), 4);
            glUniform1i(shader.uniform("uSceneDepth"), 5);
            // Tint colours by type: none, grass, foliage, water. Grass and foliage come from the biome map.
            glUniform3fv(shader.uniform("uTints"), new float[] {
                1, 1, 1, 0.57f, 0.74f, 0.35f, 0.47f, 0.70f, 0.27f, 0.25f, 0.46f, 0.89f});
        }
    }

    private final World world;
    private final Program opaqueProgram, cutoutProgram, translucentProgram, shadowProgram, shadowCutoutProgram;
    final ShadowMap shadow = new ShadowMap();
    final SceneTarget scene = new SceneTarget();
    /** Towards the sun (set each frame), how much it lights (0 at night), the sky's colour overhead. */
    final org.joml.Vector3d sunDir = new org.joml.Vector3d(0, 1, 0);
    float shadowStrength;
    final float[] skyColor = {0.5f, 0.7f, 1f};
    /** Sun shadows on (the shadow pass costs a second drawing of the nearby terrain). */
    boolean shadows = true;
    float far = 200;
    boolean underwater; // the eye is under the water surface
    boolean hideWater;  // the eye at the waterline: the water view draws the water itself
    private final int quadTexture; // GL_TEXTURE_BUFFER view (RGBA32UI) of the shared buffer
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
    /** Visible chunks whose grass and leaf colours still show the previous season (redrawn a few per frame). */
    private final java.util.ArrayDeque<Long> biomeRefresh = new java.util.ArrayDeque<>();

    /** True when every visible chunk shows the current season's colours. */
    boolean biomesSettled() {
        return biomeRefresh.isEmpty();
    }

    /** The year moved on: grass and foliage colours are redrawn, a few chunks per frame. */
    void seasonChanged(double phase) {
        if (!biomes.setSeason(phase)) return;
        biomeRefresh.clear();
        biomeRefresh.addAll(visibleChunks);
    }
    private final ConcurrentLinkedQueue<Pending> uploads = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Pending> urgentUploads = new ConcurrentLinkedQueue<>();
    private final ExecutorService workers;
    /** Player edits get their own thread so they never wait behind bulk world loading. */
    private final ExecutorService editWorker;
    private final ThreadLocal<ChunkMesher> meshers;
    private final TextureArray textures;
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f viewProj = new Matrix4f();
    private final float[] matrixBuf = new float[16], shadowBuf = new float[16];
    private double camWorldX, camWorldZ;
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
        shadowProgram = new Program("#define SHADOW\n");
        shadowCutoutProgram = new Program("#define SHADOW\n#define CUTOUT\n");

        vao = glGenVertexArrays(); // core profile needs one bound, even with no attributes
        glBindVertexArray(vao);
        int[] indices = new int[MAX_SECTION_QUADS * 6];
        for (int q = 0, v = 0, i = 0; q < MAX_SECTION_QUADS; q++, v += 4) {
            indices[i++] = v; indices[i++] = v + 1; indices[i++] = v + 2;
            indices[i++] = v + 2; indices[i++] = v + 3; indices[i++] = v;
        }
        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        glBindVertexArray(0);
        vbo = glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER, vbo);
        glBufferData(GL_TEXTURE_BUFFER, (long) INITIAL_QUADS * QUAD_BYTES, GL_DYNAMIC_DRAW);
        quadTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, quadTexture);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, vbo);
        maxQuads = glGetInteger(GL_MAX_TEXTURE_BUFFER_SIZE);

        workers = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
            daemon("mesher"));
        editWorker = Executors.newSingleThreadExecutor(daemon("edit-mesher"));
    }

    /** Largest texture buffer the driver allows, in quads (one RGBA32UI texel each). */
    private final int maxQuads;

    /** Doubles the shared buffer, copying the old contents GPU-side. */
    private void grow() {
        int oldQuads = allocator.capacity(), newQuads = oldQuads * 2;
        if (newQuads > maxQuads) {
            throw new IllegalStateException("Quad buffer would exceed GL_MAX_TEXTURE_BUFFER_SIZE (" + maxQuads + " texels)");
        }
        int nb = glGenBuffers();
        glBindBuffer(GL_COPY_WRITE_BUFFER, nb);
        glBufferData(GL_COPY_WRITE_BUFFER, (long) newQuads * QUAD_BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_COPY_READ_BUFFER, vbo);
        glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0, 0, (long) oldQuads * QUAD_BYTES);
        glDeleteBuffers(vbo);
        vbo = nb;
        glBindTexture(GL_TEXTURE_BUFFER, quadTexture);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, vbo);
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
    /** Fog distance as a fraction of the view distance (weather: fog, rain, snow). */
    float fogScale = 1;
    /** Seconds of game time, for animated fluids. */
    double time;
    /** Debug: lowest light level shown (1 = everything fully lit). */
    float minLight;

    /** Animals, drawn with the opaque world (before the water samples it) and into the shadow map. */
    CreatureRenderer creatures;
    /** Drawn after the opaque world and before the water (the weather, when the eye is under water). */
    Runnable beforeWater;

    void render(Camera cam, float aspect, float far, float[] fog) {
        drainUploads(4_000_000L);
        for (int i = 0; i < 24 && !biomeRefresh.isEmpty(); i++) {
            long k = biomeRefresh.poll();
            if (visibleChunks.contains(k)) biomes.chunkVisible((int) (k >> 32), (int) k);
        }
        this.far = far;
        if (shadows && shadowStrength > 0) renderShadows(cam, fog);
        cam.viewProj(viewProj, aspect, far);
        frustum.set(viewProj);
        viewProj.get(matrixBuf);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D_ARRAY, textures.id);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D_ARRAY, biomes.id);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, shadow.depth);
        glActiveTexture(GL_TEXTURE0);

        double px = cam.pos.x, py = cam.pos.y, pz = cam.pos.z;
        camWorldX = px;
        camWorldZ = pz;
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

        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_BUFFER, quadTexture);
        glActiveTexture(GL_TEXTURE0);
        glBindVertexArray(vao);
        float offX = (float) (px - (camCx << 4)), offY = (float) (py - Chunk.MIN_Y), offZ = (float) (pz - (camCz << 4));
        pass(opaqueProgram, false, 0, fog, far, camCx, camCz, offX, offY, offZ);
        pass(cutoutProgram, false, 1, fog, far, camCx, camCz, offX, offY, offZ);
        if (creatures != null) {
            creatures.draw(this, matrixBuf, false, fog);
            glBindVertexArray(vao);
        }
        if (beforeWater != null) { // under water, the sky (clouds) must be in what the water samples
            beforeWater.run();
            glBindVertexArray(vao);
        }
        // The water samples the opaque world drawn so far (refraction, depth under the surface).
        scene.snapshot();
        glActiveTexture(GL_TEXTURE4);
        glBindTexture(GL_TEXTURE_2D, scene.copyColor());
        glActiveTexture(GL_TEXTURE5);
        glBindTexture(GL_TEXTURE_2D, scene.copyDepth());
        glActiveTexture(GL_TEXTURE0);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        // From under water the surface is seen from below: both sides drawn (the shader keeps only
        // the water's backs). Depth is written there, so that a far surface drawn after nearer ice
        // does not cover it (nothing coplanar is left: water under ice has no surface).
        if (underwater) glDisable(GL_CULL_FACE);
        glDepthMask(underwater);
        pass(translucentProgram, true, 2, fog, far, camCx, camCz, offX, offY, offZ);
        glDepthMask(true);
        glEnable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glBindVertexArray(0);
    }

    private int drawCount;

    /**
     * Shadow pass: the opaque and cutout layers of every section within the shadow radius (not
     * only those in view: a hill behind the player still casts its shadow) into the sun's depth map.
     */
    private void renderShadows(Camera cam, float[] fog) {
        double px = cam.pos.x, py = cam.pos.y, pz = cam.pos.z;
        shadow.update(sunDir, px, py, pz);
        int camCx = (int) Math.floor(px) >> 4, camCz = (int) Math.floor(pz) >> 4;
        float reach = ShadowMap.RADIUS + 24;
        drawCount = 0;
        for (Mesh m : meshes.values()) {
            if (m == null || m.offset < 0) continue;
            float ox = (float) ((m.cx << 4) + 8 - px), oz = (float) ((m.cz << 4) + 8 - pz);
            if (Math.abs(ox) > reach || Math.abs(oz) > reach) continue;
            if (drawCount == drawList.length) drawList = Arrays.copyOf(drawList, drawCount * 2);
            drawList[drawCount++] = m;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, shadow.fbo);
        glViewport(0, 0, ShadowMap.SIZE, ShadowMap.SIZE);
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(1.5f, 3f);
        glDisable(GL_CULL_FACE); // thin things (leaves, slabs) cast from both sides
        shadow.viewProj.get(matrixBuf);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D_ARRAY, textures.id);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_BUFFER, quadTexture);
        glActiveTexture(GL_TEXTURE0);
        glBindVertexArray(vao);
        float offX = (float) (px - (camCx << 4)), offY = (float) (py - Chunk.MIN_Y), offZ = (float) (pz - (camCz << 4));
        pass(shadowProgram, false, 0, fog, far, camCx, camCz, offX, offY, offZ);
        pass(shadowCutoutProgram, false, 1, fog, far, camCx, camCz, offX, offY, offZ);
        if (creatures != null) creatures.draw(this, matrixBuf, true, fog);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glEnable(GL_CULL_FACE);
        scene.bind(scene.width(), scene.height()); // back to the world picture
        glViewport(0, 0, scene.width(), scene.height());
        drawCount = 0;
    }


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
            drawBase.put(first * 4); // gl_VertexID = index + base: gl_VertexID / 4 is the quad record
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
        glUniform1f(p.uFogEnd, far * fogScale);
        glUniform1f(p.uDaylight, daylight);
        glUniform1f(p.uMinLight, minLight);
        glUniform1f(p.uTime, (float) (time % 512.0));
        glUniformMatrix4fv(p.uShadowVP, false, shadow.viewProj.get(shadowBuf));
        glUniform3f(p.uSunDir, (float) sunDir.x, (float) sunDir.y, (float) sunDir.z);
        glUniform1f(p.uShadowStrength, shadows ? shadowStrength : 0);
        glUniform3f(p.uSkyColor, skyColor[0], skyColor[1], skyColor[2]);
        glUniform2f(p.uCamWorld, (float) (camWorldX % 1024.0), (float) (camWorldZ % 1024.0));
        glUniform2f(p.uScreen, scene.width(), scene.height());
        glUniform2f(p.uDepthRange, 0.05f, this.far);
        glUniform1f(p.uHideWater, hideWater ? 1 : 0);
        glMultiDrawElementsBaseVertex(GL_TRIANGLES, drawCounts, GL_UNSIGNED_INT, drawOffsets, drawBase);
    }

    /** True once all scheduled meshing work has been uploaded. */
    boolean idle() {
        return pendingJobs.get() == 0 && uploads.isEmpty() && urgentUploads.isEmpty();
    }

    int meshCount() {
        return meshes.size();
    }

    /** GPU buffer usage in bytes: used and allocated. */
    long[] bufferBytes() {
        return new long[] {(long) allocator.used() * QUAD_BYTES, (long) allocator.capacity() * QUAD_BYTES};
    }

    void dispose() {
        shadow.dispose();
        scene.dispose();
        workers.shutdownNow();
        editWorker.shutdownNow();
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteTextures(quadTexture);
        glDeleteBuffers(ebo);
        glDeleteTextures(textures.id);
        biomes.dispose();
        for (Program p : new Program[] {opaqueProgram, cutoutProgram, translucentProgram}) glDeleteProgram(p.shader.program);
    }
}
