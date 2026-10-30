package net.mynecraft.client;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.World;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL33.*;

/**
 * The view from in or at the water: when the eye is near or under the surface, the finished
 * world picture goes to the window through a pass that splits it at the waterline, colours and
 * swallows what is under water with distance, and adds caustics, drops (coming out) and bubbles
 * (going in). Elsewhere the picture is copied as it is.
 */
final class WaterView {
    private final Shader shader = new Shader("/shaders/post.vert", "/shaders/water_view.frag");
    private final int vao = glGenVertexArrays();
    private final int uInvViewProj, uCamWorld, uDepthRange, uAspect, uSurface, uLens, uWaterColor, uDaylight,
        uTime, uWet, uSplash;
    private final Matrix4f inv = new Matrix4f();
    private final float[] buf = new float[16];

    /** Water surface over the eye in blocks (negative: the eye is above it); NO_WATER when none is near. */
    double surface = NO_WATER;
    float wet, splash;
    final float[] waterColor = new float[3];
    static final double NO_WATER = -100;

    WaterView() {
        int p = shader.program;
        glUseProgram(p);
        glUniform1i(glGetUniformLocation(p, "uColor"), 0);
        glUniform1i(glGetUniformLocation(p, "uDepth"), 1);
        uInvViewProj = shader.uniform("uInvViewProj");
        uCamWorld = shader.uniform("uCamWorld");
        uDepthRange = shader.uniform("uDepthRange");
        uAspect = shader.uniform("uAspect");
        uSurface = shader.uniform("uSurface");
        uLens = shader.uniform("uLens");
        uWaterColor = shader.uniform("uWaterColor");
        uDaylight = shader.uniform("uDaylight");
        uTime = shader.uniform("uTime");
        uWet = shader.uniform("uWet");
        uSplash = shader.uniform("uSplash");
        glUseProgram(0);
    }

    /** True when the eye is under the water surface. */
    boolean under() { return surface > 0; }

    /**
     * True when the world is drawn as seen from under water. Just under the surface, where the
     * waterline crosses the view, it is drawn from just above instead (see lift()): this pass
     * then puts the lower part under water.
     */
    boolean sceneUnder() { return surface >= LINE_BAND; }

    /** How far to raise the eye for drawing the world: over the surface when it is just above. */
    double lift() { return surface > 0 && surface < LINE_BAND ? surface + 0.03 : 0; }

    private static final double LINE_BAND = 0.35;

    /**
     * Follows the eye: where the water surface is over or under it, the colour of the water there
     * (daylight and the sky light reaching the eye) and the splash and drops of going in and out.
     */
    void update(World world, double x, double y, double z, float daylight, double dt) {
        boolean was = under();
        surface = surfaceOver(world, x, y, z);
        if (under() && !was) splash = 1;
        if (!under() && was) wet = 1;
        splash = Math.max(0, splash - (float) dt / 0.8f);
        wet = Math.max(0, wet - (float) dt / 3.5f);
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        float sky = (world.getLight(bx, by, bz) >> 4) / 15f;
        float lit = 0.04f + 0.96f * daylight * sky * sky;
        waterColor[0] = 0.05f * lit;
        waterColor[1] = 0.25f * lit;
        waterColor[2] = 0.33f * lit;
    }

    /** Height of the top of the water column at the eye over the eye, or NO_WATER. */
    static double surfaceOver(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        int top;
        if (water(w.get(bx, by, bz))) {
            top = by;
            while (top < by + 64 && water(w.get(bx, top + 1, bz))) top++;
        } else if (water(w.get(bx, by - 1, bz))) {
            top = by - 1;
        } else return NO_WATER;
        return top + Blocks.fluidHeight(w.get(bx, top, bz)) / 16.0 - y;
    }

    private static boolean water(short b) { return Blocks.FLUID[b & 0xFFFF] == Blocks.FLUID_WATER; }

    /** Whether the picture needs this pass at all (the eye near or under water, or still wet). */
    boolean active() { return surface > -0.6 || wet > 0; }

    /** Draws the scene's picture to the window (bound) through the water. */
    void draw(SceneTarget scene, Matrix4f viewProj, Camera cam, float far, float daylight, double time) {
        glDisable(GL_DEPTH_TEST);
        glUseProgram(shader.program);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, scene.depth());
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, scene.color());
        glUniformMatrix4fv(uInvViewProj, false, viewProj.invert(inv).get(buf));
        glUniform3f(uCamWorld, (float) (cam.pos.x % 1024.0), (float) cam.pos.y, (float) (cam.pos.z % 1024.0));
        glUniform2f(uDepthRange, 0.05f, far);
        glUniform1f(uAspect, scene.width() / (float) Math.max(1, scene.height()));
        glUniform1f(uSurface, (float) Math.max(-50, Math.min(50, surface)));
        float lens = 0.3f; // the eye seen as a small window this far in front of it
        glUniform2f(uLens, lens * (float) Math.sin(cam.pitch),
            lens * (float) (Math.tan(cam.fovY / 2) * Math.cos(cam.pitch)));
        glUniform3f(uWaterColor, waterColor[0], waterColor[1], waterColor[2]);
        glUniform1f(uDaylight, daylight);
        glUniform1f(uTime, (float) (time % 512.0));
        glUniform1f(uWet, wet);
        glUniform1f(uSplash, splash);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        glEnable(GL_DEPTH_TEST);
    }

    void dispose() {
        glDeleteProgram(shader.program);
        glDeleteVertexArrays(vao);
    }
}
