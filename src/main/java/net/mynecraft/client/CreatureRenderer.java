package net.mynecraft.client;

import net.mynecraft.world.World;
import net.mynecraft.world.life.Creature;
import net.mynecraft.world.life.Life;
import net.mynecraft.world.life.Species;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Draws the creatures as their animated box models: each part turns around its pivot (legs
 * swinging with the gait, heads looking and grazing, wings, tails, fins, tentacles), the body
 * turns, banks and pitches. The boxes are built on the CPU once per frame, camera-relative, into
 * one streamed buffer drawn in the world pass and again into the sun's shadow map.
 */
final class CreatureRenderer {
    /** Creatures farther than this are not drawn. */
    static final double RANGE = 96;
    private static final int FLOATS = 12; // position, normal, colour, glow, sky light, block light
    private static final int[][] FACES = { // corner indices (bit 0 x, bit 1 y, bit 2 z) per face, counter-clockwise
        {0, 4, 6, 2}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 2, 3, 1}, {4, 5, 7, 6}};
    private static final float[][] NORMALS = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    private final Shader shader, shadowShader;
    private final int vao, vbo;
    private final int uViewProj, uFogColor, uFogEnd, uDaylight, uSunDir, uShadowVP, uShadowStrength;
    private final int sViewProj;
    private FloatBuffer data = BufferUtils.createFloatBuffer(64 * 1024);
    private int vertices, capacity;
    private final Matrix4f body = new Matrix4f(), part = new Matrix4f();
    private final Vector3f v = new Vector3f(), n = new Vector3f();
    private final float[] corner = new float[24];
    private final FrustumIntersection frustum = new FrustumIntersection();
    int drawn;

    CreatureRenderer() {
        shader = new Shader("/shaders/creature.vert", "/shaders/creature.frag");
        shadowShader = new Shader("/shaders/creature.vert", "/shaders/creature.frag", "#define SHADOW\n");
        uViewProj = shader.uniform("uViewProj");
        uFogColor = shader.uniform("uFogColor");
        uFogEnd = shader.uniform("uFogEnd");
        uDaylight = shader.uniform("uDaylight");
        uSunDir = shader.uniform("uSunDir");
        uShadowVP = shader.uniform("uShadowVP");
        uShadowStrength = shader.uniform("uShadowStrength");
        glUseProgram(shader.program);
        glUniform1i(shader.uniform("uShadowMap"), 3);
        sViewProj = shadowShader.uniform("uViewProj");
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        int stride = FLOATS * 4;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 12);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 24);
        glVertexAttribPointer(3, 2, GL_FLOAT, false, stride, 40);
        for (int i = 0; i < 4; i++) glEnableVertexAttribArray(i);
        glBindVertexArray(0);
    }

    /** Builds this frame's geometry: every creature in range and in view (or casting a shadow into it). */
    void prepare(Life life, World world, Camera cam, Matrix4f viewProj, boolean shadows) {
        data.clear();
        vertices = 0;
        drawn = 0;
        frustum.set(viewProj);
        double px = cam.pos.x, py = cam.pos.y, pz = cam.pos.z;
        for (Creature c : life.creatures) {
            float ox = (float) (c.x - px), oy = (float) (c.y - py), oz = (float) (c.z - pz);
            if (ox * ox + oz * oz > RANGE * RANGE) continue;
            float r = (float) (c.species.parts.isEmpty() ? 1 : Math.max(c.halfWidth() * 6, c.height() * 1.5) + 1);
            boolean visible = frustum.testSphere(ox, oy + (float) c.height() / 2, oz, r);
            if (!visible && !(shadows && ox * ox + oz * oz < 48 * 48)) continue;
            int light = world.getLight((int) Math.floor(c.x), (int) Math.floor(c.y + c.height() * 0.6), (int) Math.floor(c.z));
            float sky = level(light >> 4), block = level(light & 15);
            emit(c, ox, oy, oz, sky, block);
            drawn++;
        }
        data.flip();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        if (data.limit() > capacity) {
            capacity = data.capacity();
            glBufferData(GL_ARRAY_BUFFER, (long) capacity * 4, GL_STREAM_DRAW);
        }
        glBufferSubData(GL_ARRAY_BUFFER, 0, data);
    }

    private static float level(int l) {
        return (float) Math.max(Math.pow(0.8, 15 - l), 0.03);
    }

    /** Draws the prepared creatures with the world's lighting, or into the shadow map. */
    void draw(WorldRenderer r, float[] viewProj, boolean shadowPass, float[] fog) {
        if (vertices == 0) return;
        glBindVertexArray(vao);
        if (shadowPass) {
            glUseProgram(shadowShader.program);
            glUniformMatrix4fv(sViewProj, false, viewProj);
        } else {
            glUseProgram(shader.program);
            glUniformMatrix4fv(uViewProj, false, viewProj);
            glUniform3f(uFogColor, fog[0], fog[1], fog[2]);
            glUniform1f(uFogEnd, r.far * r.fogScale);
            glUniform1f(uDaylight, r.daylight);
            glUniform3f(uSunDir, (float) r.sunDir.x, (float) r.sunDir.y, (float) r.sunDir.z);
            glUniformMatrix4fv(uShadowVP, false, r.shadow.viewProj.get(new float[16]));
            glUniform1f(uShadowStrength, r.shadows ? r.shadowStrength : 0);
        }
        glDrawArrays(GL_TRIANGLES, 0, vertices);
        glBindVertexArray(0);
    }

    // ------------------------------------------------------------------------------- geometry

    private void emit(Creature c, float ox, float oy, float oz, float sky, float block) {
        Species s = c.species;
        float t = c.clock;
        float moving = (float) Math.min(1, c.speed / Math.max(0.5, s.walkSpeed));
        boolean sleeping = c.state == Creature.State.SLEEP;
        boolean flying = c.state == Creature.State.FLY || s.kind == Species.Kind.FLY;
        // Lying down while asleep, rolling over when dying, a little bounce in the stride.
        float drop = 0, roll = c.bank;
        if (sleeping && s != Species.HERON) drop = -(float) s.height * 0.28f;
        if (c.dying > 0) roll += (float) (Math.PI / 2) * (1 - Math.min(1, c.dying / 1.2f));
        boolean hopper = s == Species.RABBIT || s == Species.FROSTLING;
        float bounce = hopper ? 0 : (float) Math.abs(Math.sin(c.gait)) * 0.04f * moving * (float) s.height;
        float scale = c.scale * (c.dying > 0 && s.kind != Species.Kind.GROUND ? Math.max(0.05f, c.dying / 1.5f) : 1);
        float mid = (float) s.height * 0.5f;
        body.identity().translate(ox, oy + drop + bounce, oz).rotateY(c.yaw)
            .translate(0, mid * scale, 0).rotateZ(roll).rotateX(c.pitch).scale(scale).translate(0, -mid, 0);
        float hurt = c.hurt > 0 ? Math.min(1, c.hurt * 3) : 0;
        for (Species.Part p : s.parts) {
            part.set(body).translate(p.px(), p.py(), p.pz());
            switch (p.anim()) {
                case LEG -> {
                    float swing = sleeping ? 1.35f : (float) Math.sin(c.gait + p.phase()) * 0.75f * moving;
                    if (hopper && moving > 0.1f) swing = (float) Math.sin(c.gait * 2 + p.phase()) * 0.9f;
                    if (s == Species.HERON && sleeping && p.phase() > 0) swing = 2.4f; // standing on one leg
                    if (flying) swing = s == Species.HERON ? -1.4f : 0; // legs trailing behind in flight
                    part.rotateX(swing);
                }
                case HEAD -> {
                    float strike = s == Species.HERON && c.state == Creature.State.FISH ? (float) Math.max(0, Math.sin(t * 0.7)) * 0.2f : 0;
                    part.rotateY(c.headYaw).rotateX(c.headPitch + strike + (flying && s == Species.HERON ? 1.2f : 0));
                }
                case TAIL -> part.rotateY((float) Math.sin(t * (s == Species.FOX ? 2.5 : 4)) * (sleeping ? 0.1f : 0.35f))
                    .rotateX(s == Species.SKY_MANTA ? (float) Math.sin(t * 1.3) * 0.12f : 0);
                case FIN -> part.rotateY((float) Math.sin(t * (6 + 10 * moving)) * (0.35f + 0.3f * moving));
                case WING -> {
                    float side = p.phase() > 0 ? -1 : 1, flap;
                    if (s == Species.SKY_MANTA) flap = (float) Math.sin(t * 1.4 + c.id) * 0.38f;
                    else if (flying) flap = (float) Math.sin(t * 9) * 0.9f;
                    else flap = -1.45f; // folded against the body
                    part.rotateZ(side * flap);
                }
                case EAR -> part.rotateX((float) (Math.sin(t * 9 + c.id) > 0.96 ? -0.5 : 0) + (sleeping ? 0.9f : 0));
                case TENTACLE -> part.rotateX((float) Math.sin(t * 1.8 + p.phase()) * 0.32f)
                    .rotateZ((float) Math.cos(t * 1.3 + p.phase()) * 0.2f);
                case BOB -> part.translate(0, (float) Math.sin(t * 1.6 + c.id) * 0.06f, 0)
                    .scale(1 + (float) Math.sin(t * 1.6 + c.id + 1) * 0.06f, 1, 1 + (float) Math.sin(t * 1.6 + c.id + 1) * 0.06f);
                case BODY -> part.translate(0, (float) Math.sin(t * 1.7) * 0.006f * (float) s.height, 0);
                default -> {
                }
            }
            int color = p.coat() ? multiply(c.sick ? dull(c.coat) : c.coat, p.color()) : p.color();
            float cr = ((color >> 16) & 255) / 255f, cg = ((color >> 8) & 255) / 255f, cb = (color & 255) / 255f;
            if (hurt > 0) { cr += (1 - cr) * hurt * 0.7f; cg *= 1 - hurt * 0.6f; cb *= 1 - hurt * 0.6f; }
            box(p.ox(), p.oy(), p.oz(), p.w(), p.h(), p.d(), cr, cg, cb, p.glow(), sky, block);
        }
    }

    /** A sick animal's coat: greyer and darker. */
    private static int dull(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255, grey = (r + g + b) / 3;
        return ((r + grey) * 2 / 5) << 16 | ((g + grey) * 2 / 5) << 8 | (b + grey) * 2 / 5;
    }

    private static int multiply(int a, int b) {
        int r = ((a >> 16) & 255) * ((b >> 16) & 255) / 255, g = ((a >> 8) & 255) * ((b >> 8) & 255) / 255, bl = (a & 255) * (b & 255) / 255;
        return r << 16 | g << 8 | bl;
    }

    private void box(float x, float y, float z, float w, float h, float d, float r, float g, float b, float glow, float sky, float block) {
        for (int i = 0; i < 8; i++) {
            v.set(x + ((i & 1) != 0 ? w : 0), y + ((i & 2) != 0 ? h : 0), z + ((i & 4) != 0 ? d : 0));
            part.transformPosition(v);
            corner[i * 3] = v.x;
            corner[i * 3 + 1] = v.y;
            corner[i * 3 + 2] = v.z;
        }
        if (data.remaining() < 36 * FLOATS) {
            FloatBuffer bigger = BufferUtils.createFloatBuffer(data.capacity() * 2);
            data.flip();
            bigger.put(data);
            data = bigger;
        }
        for (int f = 0; f < 6; f++) {
            n.set(NORMALS[f][0], NORMALS[f][1], NORMALS[f][2]);
            part.transformDirection(n).normalize();
            int[] q = FACES[f];
            int[] order = {q[0], q[1], q[2], q[0], q[2], q[3]};
            for (int k : order) {
                data.put(corner[k * 3]).put(corner[k * 3 + 1]).put(corner[k * 3 + 2]);
                data.put(n.x).put(n.y).put(n.z);
                data.put(r).put(g).put(b).put(glow);
                data.put(sky).put(block);
            }
            vertices += 6;
        }
    }

    void dispose() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteProgram(shader.program);
        glDeleteProgram(shadowShader.program);
    }
}
