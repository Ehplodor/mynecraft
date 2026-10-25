package net.mynecraft.client;

import net.mynecraft.resource.BlockAppearance;
import net.mynecraft.world.Blocks;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Hotbar: nine slots at the bottom of the screen with an isometric icon of each block, drawn from
 * the block texture array. Geometry is rebuilt into one small buffer per frame and drawn at once.
 */
final class Hud {
    private static final int FLOATS_PER_VERTEX = 9, MAX_QUADS = 64;
    private static final float[][] TINT = {{1, 1, 1}, {0.57f, 0.74f, 0.35f}, {0.47f, 0.70f, 0.27f}, {0.25f, 0.46f, 0.89f}};
    private final Shader shader;
    private final int vao, vbo, uOrtho;
    private final int[] faceInfo;
    private final FloatBuffer data = BufferUtils.createFloatBuffer(MAX_QUADS * 6 * FLOATS_PER_VERTEX);
    private final Matrix4f ortho = new Matrix4f();
    private final float[] matrix = new float[16];
    private int vertices;

    Hud(int[] faceInfo) {
        this.faceInfo = faceInfo;
        shader = new Shader("/shaders/hud.vert", "/shaders/hud.frag");
        uOrtho = shader.uniform("uOrtho");
        glUseProgram(shader.program);
        glUniform1i(shader.uniform("uTextures"), 0);
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) data.capacity() * 4, GL_STREAM_DRAW);
        int stride = FLOATS_PER_VERTEX * 4;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 8);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 20);
        glEnableVertexAttribArray(2);
        glBindVertexArray(0);
    }

    void draw(int width, int height, byte[] slots, int selected, int textureArray) {
        float scale = Math.max(1, Math.round(height / 360f));
        float slot = 20 * scale, gap = 2 * scale;
        float total = slots.length * slot + (slots.length - 1) * gap;
        float x0 = (width - total) / 2, y0 = height - slot - 4 * scale;
        data.clear();
        vertices = 0;
        for (int i = 0; i < slots.length; i++) {
            float x = x0 + i * (slot + gap);
            if (i == selected) rect(x - scale, y0 - scale, slot + 2 * scale, slot + 2 * scale, 1, 1, 1, 0.9f);
            rect(x, y0, slot, slot, 0, 0, 0, 0.55f);
            icon(slots[i], x + slot / 2, y0 + slot / 2, slot * 0.8f);
        }
        data.flip();
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE); // y-down screen space flips the winding
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(shader.program);
        glUniformMatrix4fv(uOrtho, false, ortho.setOrtho2D(0, width, height, 0).get(matrix));
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureArray);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, data);
        glDrawArrays(GL_TRIANGLES, 0, vertices);
        glBindVertexArray(0);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
    }

    /** Isometric cube (top, left, right faces with simple shading), or a flat square for plants. */
    private void icon(byte block, float cx, float cy, float s) {
        if (Blocks.SHAPE[block & 0xFF] == Blocks.SHAPE_CROSS) {
            face(block, 4, 1, cx - s / 2, cy - s / 2, cx + s / 2, cy - s / 2, cx + s / 2, cy + s / 2, cx - s / 2, cy + s / 2, 0);
            return;
        }
        float w = s * 0.43f;
        float d = Blocks.isSlab(block) ? s / 4 : 0; // half-height icon for slabs: lower the top face
        face(block, 3, 1.0f, cx, cy - s / 2 + d, cx + w, cy - s / 4 + d, cx, cy + d, cx - w, cy - s / 4 + d, 0); // top
        face(block, 5, 0.8f, cx - w, cy - s / 4 + d, cx, cy + d, cx, cy + s / 2, cx - w, cy + s / 4, d > 0 ? 0.5f : 0);
        face(block, 1, 0.6f, cx, cy + d, cx + w, cy - s / 4 + d, cx + w, cy + s / 4, cx, cy + s / 2, d > 0 ? 0.5f : 0);
    }

    /**
     * Quad with the face's texture; corners in texture order (u0v0, u1v0, u1v1, u0v1). {@code v0}
     * crops the top of the texture (0.5 shows its lower half, like a slab side).
     */
    private void face(byte block, int f, float shade, float ax, float ay, float bx, float by, float cx, float cy,
                      float dx, float dy, float v0) {
        int info = faceInfo[(block & 0xFF) * 6 + f];
        float layer = info & BlockAppearance.LAYER_MASK;
        float[] tint = TINT[(info >> BlockAppearance.TINT_SHIFT) & 3];
        if ((info & BlockAppearance.OVERLAY_BIT) != 0) tint = TINT[0]; // base of an overlay face is untinted
        float r = tint[0] * shade, g = tint[1] * shade, b = tint[2] * shade;
        vertex(ax, ay, 0, v0, layer, r, g, b, 1);
        vertex(bx, by, 1, v0, layer, r, g, b, 1);
        vertex(cx, cy, 1, 1, layer, r, g, b, 1);
        vertex(ax, ay, 0, v0, layer, r, g, b, 1);
        vertex(cx, cy, 1, 1, layer, r, g, b, 1);
        vertex(dx, dy, 0, 1, layer, r, g, b, 1);
    }

    private void rect(float x, float y, float w, float h, float r, float g, float b, float a) {
        vertex(x, y, 0, 0, -1, r, g, b, a);
        vertex(x + w, y, 0, 0, -1, r, g, b, a);
        vertex(x + w, y + h, 0, 0, -1, r, g, b, a);
        vertex(x, y, 0, 0, -1, r, g, b, a);
        vertex(x + w, y + h, 0, 0, -1, r, g, b, a);
        vertex(x, y + h, 0, 0, -1, r, g, b, a);
    }

    private void vertex(float x, float y, float u, float v, float layer, float r, float g, float b, float a) {
        data.put(x).put(y).put(u).put(v).put(layer).put(r).put(g).put(b).put(a);
        vertices++;
    }

    void dispose() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteProgram(shader.program);
    }
}
