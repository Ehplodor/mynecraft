package net.mynecraft.client;

import net.mynecraft.resource.ResourceManager;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Sun, moon and clouds from the pack's environment textures. Everything is drawn around the camera
 * (camera-relative), so it costs three tiny draws per frame and never needs world-space precision.
 */
final class SkyRenderer {
    private static final float SUN_DISTANCE = 100, SUN_SIZE = 30, MOON_SIZE = 20;
    private static final float CLOUD_Y = 192, CLOUD_SCALE = 12; // one cloud texel spans 12 blocks
    private final Shader shader;
    private final int vao, vbo, sun, moon, clouds, uViewProj, uColor, uFadeEnd;
    private final FloatBuffer quad = BufferUtils.createFloatBuffer(4 * 5);
    private final float[] matrix = new float[16];

    SkyRenderer(ResourceManager resources) {
        shader = new Shader("/shaders/sky.vert", "/shaders/sky.frag");
        uViewProj = shader.uniform("uViewProj");
        uColor = shader.uniform("uColor");
        uFadeEnd = shader.uniform("uFadeEnd");
        sun = load(resources, "sun", false);
        moon = load(resources, "moon_phases", false);
        clouds = load(resources, "clouds", true);
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, 4 * 5 * 4, GL_STREAM_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 20, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 20, 12);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
    }

    private static int load(ResourceManager resources, String name, boolean repeat) {
        BufferedImage img = null;
        byte[] data = resources.read("assets/minecraft/textures/environment/" + name + ".png");
        if (data != null) {
            try {
                img = ImageIO.read(new ByteArrayInputStream(data));
            } catch (IOException e) {
                System.err.println("[resources] unreadable environment/" + name + ".png");
            }
        }
        int w = img == null ? 1 : img.getWidth(), h = img == null ? 1 : img.getHeight();
        ByteBuffer px = BufferUtils.createByteBuffer(w * h * 4);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int argb = img == null ? 0 : img.getRGB(x, y);
                px.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        px.flip();
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, px);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        int wrap = repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
        return id;
    }

    /** Sun and moon: drawn first, behind everything, additively like vanilla. */
    void drawCelestial(Matrix4f viewProj, double ticks) {
        double angle = (ticks % DayCycle.DAY_TICKS) / DayCycle.DAY_TICKS * 2 * Math.PI;
        // Sun rises in the east (+x) at tick 0, culminates at 6000, sets in the west (-x).
        float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
        begin(viewProj);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        glUniform1f(uFadeEnd, 0);
        glUniform4f(uColor, 1, 1, 1, 1);
        glBindTexture(GL_TEXTURE_2D, sun);
        billboard(dx, dy, SUN_SIZE, 0, 0, 1, 1);
        int phase = (int) ((long) (ticks / DayCycle.DAY_TICKS) % 8);
        float u0 = (phase % 4) / 4f, v0 = (phase / 4) / 2f;
        glBindTexture(GL_TEXTURE_2D, moon);
        billboard(-dx, -dy, MOON_SIZE, u0, v0, u0 + 0.25f, v0 + 0.5f);
        glDisable(GL_BLEND);
        glDepthMask(true);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
    }

    /** Quad facing the camera at direction (dx, dy, 0), its sides along z and the arc of the sun. */
    private void billboard(float dx, float dy, float size, float u0, float v0, float u1, float v1) {
        float cx = dx * SUN_DISTANCE, cy = dy * SUN_DISTANCE, h = size / 2;
        float tx = -dy * h, ty = dx * h; // tangent along the sun's path
        quad.clear();
        quad.put(cx - tx).put(cy - ty).put(-h).put(u0).put(v1);
        quad.put(cx + tx).put(cy + ty).put(-h).put(u1).put(v1);
        quad.put(cx + tx).put(cy + ty).put(h).put(u1).put(v0);
        quad.put(cx - tx).put(cy - ty).put(h).put(u0).put(v0);
        quad.flip();
        glBufferSubData(GL_ARRAY_BUFFER, 0, quad);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
    }

    /** Flat cloud layer: one quad around the camera, UVs from world position (double precision). */
    void drawClouds(Matrix4f viewProj, double camX, double camY, double camZ, double ticks, float daylight, float radius) {
        begin(viewProj);
        double period = 256 * CLOUD_SCALE;
        double drift = ticks * 0.03; // blocks moved by the wind
        float u = (float) (((camX + drift) / period) % 1.0), v = (float) ((camZ / period) % 1.0);
        float y = (float) (CLOUD_Y - camY), r = radius, d = r / (float) period;
        quad.clear();
        quad.put(-r).put(y).put(-r).put(u - d).put(v - d);
        quad.put(r).put(y).put(-r).put(u + d).put(v - d);
        quad.put(r).put(y).put(r).put(u + d).put(v + d);
        quad.put(-r).put(y).put(r).put(u - d).put(v + d);
        quad.flip();
        glBufferSubData(GL_ARRAY_BUFFER, 0, quad);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        float b = 0.25f + 0.75f * (daylight - 0.15f) / 0.85f;
        glUniform4f(uColor, b, b, b, 0.8f);
        glUniform1f(uFadeEnd, radius);
        glBindTexture(GL_TEXTURE_2D, clouds);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        glEnable(GL_CULL_FACE);
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    private void begin(Matrix4f viewProj) {
        glUseProgram(shader.program);
        glUniformMatrix4fv(uViewProj, false, viewProj.get(matrix));
        glActiveTexture(GL_TEXTURE0);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
    }

    void dispose() {
        glDeleteTextures(new int[] {sun, moon, clouds});
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteProgram(shader.program);
    }
}
