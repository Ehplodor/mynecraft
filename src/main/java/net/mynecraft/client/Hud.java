package net.mynecraft.client;

import net.mynecraft.resource.BlockAppearance;
import net.mynecraft.resource.FontLoader;
import net.mynecraft.world.Blocks;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Immediate-mode 2D layer: hotbar with isometric block icons, text with the pack's bitmap font,
 * plain rectangles. Everything queued between {@link #begin} and {@link #end} is drawn in one call
 * from one small streamed buffer.
 */
final class Hud {
    private static final int FLOATS_PER_VERTEX = 9, MAX_QUADS = 8192;
    private static final float[][] TINT = {{1, 1, 1}, {0.57f, 0.74f, 0.35f}, {0.47f, 0.70f, 0.27f}, {0.25f, 0.46f, 0.89f}};
    private final Shader shader;
    private final int vao, vbo, uOrtho, fontTexture;
    private final int[] faceInfo;
    private final FontLoader font;
    private final FloatBuffer data = BufferUtils.createFloatBuffer(MAX_QUADS * 6 * FLOATS_PER_VERTEX);
    private final Matrix4f ortho = new Matrix4f();
    private final float[] matrix = new float[16];
    private int vertices, width, height;
    /** GUI scale: one font/hotbar pixel in screen pixels, like vanilla's "GUI scale: auto". */
    float scale = 1;

    Hud(int[] faceInfo, FontLoader font) {
        this.faceInfo = faceInfo;
        this.font = font;
        shader = new Shader("/shaders/hud.vert", "/shaders/hud.frag");
        uOrtho = shader.uniform("uOrtho");
        glUseProgram(shader.program);
        glUniform1i(shader.uniform("uTextures"), 0);
        glUniform1i(shader.uniform("uFont"), 1);
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
        fontTexture = uploadFont(font);
    }

    private static int uploadFont(FontLoader font) {
        int w = font == null ? 1 : font.atlas.getWidth(), h = font == null ? 1 : font.atlas.getHeight();
        ByteBuffer px = BufferUtils.createByteBuffer(w * h * 4);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int argb = font == null ? 0 : font.atlas.getRGB(x, y);
                px.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        px.flip();
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, px);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return id;
    }

    void begin(int width, int height) {
        this.width = width;
        this.height = height;
        scale = Math.max(1, Math.round(height / 360f));
        data.clear();
        vertices = 0;
    }

    void end(int textureArray) {
        data.flip();
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE); // y-down screen space flips the winding
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(shader.program);
        glUniformMatrix4fv(uOrtho, false, ortho.setOrtho2D(0, width, height, 0).get(matrix));
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, fontTexture);
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

    int width() { return width; }

    int height() { return height; }

    /** Hotbar at the bottom centre; returns its top edge (pixels) so text can sit above it. */
    float hotbar(byte[] slots, int selected) {
        float slot = 20 * scale, gap = 2 * scale;
        float total = slots.length * slot + (slots.length - 1) * gap;
        float x0 = (width - total) / 2, y0 = height - slot - 4 * scale;
        for (int i = 0; i < slots.length; i++) {
            float x = x0 + i * (slot + gap);
            if (i == selected) rect(x - scale, y0 - scale, slot + 2 * scale, slot + 2 * scale, 1, 1, 1, 0.9f);
            rect(x, y0, slot, slot, 0, 0, 0, 0.55f);
            icon(slots[i], x + slot / 2, y0 + slot / 2, slot * 0.8f);
        }
        return y0;
    }

    // ---- text ----

    /** Width of a string in screen pixels at the current GUI scale. */
    float textWidth(String s) {
        if (font == null) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += font.advance(s.charAt(i));
        return w * scale;
    }

    float lineHeight() {
        return 10 * scale;
    }

    /** Text with vanilla's drop shadow (a darker copy one pixel down-right). */
    void text(String s, float x, float y, float r, float g, float b) {
        glyphs(s, x + scale, y + scale, r * 0.25f, g * 0.25f, b * 0.25f);
        glyphs(s, x, y, r, g, b);
    }

    void textCentered(String s, float cx, float y, float r, float g, float b) {
        text(s, cx - textWidth(s) / 2, y, r, g, b);
    }

    private void glyphs(String s, float x, float y, float r, float g, float b) {
        if (font == null) return;
        float atlasW = font.atlas.getWidth(), atlasH = font.atlas.getHeight();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            FontLoader.Glyph gl = font.glyphs.get(ch);
            if (gl != null && gl.width() > 0 && vertices + 6 <= MAX_QUADS * 6) {
                float w = gl.width() * scale, h = font.cell * scale;
                float u0 = gl.x() / atlasW, v0 = gl.y() / atlasH;
                float u1 = (gl.x() + gl.width()) / atlasW, v1 = (gl.y() + font.cell) / atlasH;
                quad(x, y, w, h, u0, v0, u1, v1, -2, r, g, b, 1);
            }
            x += font.advance(ch) * scale;
        }
    }

    // ---- shapes ----

    void rect(float x, float y, float w, float h, float r, float g, float b, float a) {
        quad(x, y, w, h, 0, 0, 0, 0, -1, r, g, b, a);
    }

    private void quad(float x, float y, float w, float h, float u0, float v0, float u1, float v1, float layer,
                      float r, float g, float b, float a) {
        vertex(x, y, u0, v0, layer, r, g, b, a);
        vertex(x + w, y, u1, v0, layer, r, g, b, a);
        vertex(x + w, y + h, u1, v1, layer, r, g, b, a);
        vertex(x, y, u0, v0, layer, r, g, b, a);
        vertex(x + w, y + h, u1, v1, layer, r, g, b, a);
        vertex(x, y + h, u0, v1, layer, r, g, b, a);
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

    private void vertex(float x, float y, float u, float v, float layer, float r, float g, float b, float a) {
        data.put(x).put(y).put(u).put(v).put(layer).put(r).put(g).put(b).put(a);
        vertices++;
    }

    void dispose() {
        glDeleteTextures(fontTexture);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteProgram(shader.program);
    }
}
