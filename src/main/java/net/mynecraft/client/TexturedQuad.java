package net.mynecraft.client;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/** An RGBA texture drawn as a screen rectangle (maps). Reuses the sky shader: textured quad times a colour. */
final class TexturedQuad {
    private final Shader shader;
    private final int uViewProj, uColor, uFadeEnd, vao, vbo;
    private final FloatBuffer quad = BufferUtils.createFloatBuffer(4 * 5);
    private final float[] matrix = new float[16];
    private int texture, texW, texH;

    TexturedQuad() {
        shader = new Shader("/shaders/sky.vert", "/shaders/sky.frag");
        uViewProj = shader.uniform("uViewProj");
        uColor = shader.uniform("uColor");
        uFadeEnd = shader.uniform("uFadeEnd");
        texture = glGenTextures();
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

    /** Uploads w x h RGBA pixels (reallocating the texture if the size changed). */
    void upload(ByteBuffer pixels, int w, int h) {
        glBindTexture(GL_TEXTURE_2D, texture);
        if (w != texW || h != texH) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            texW = w;
            texH = h;
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        }
    }

    /** Draws texture coordinates (u0, v0)-(u1, v1) on the screen rectangle (x0, y0)-(x1, y1), y down. */
    void draw(float screenW, float screenH, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1,
              float alpha) {
        glUseProgram(shader.program);
        glUniformMatrix4fv(uViewProj, false, new Matrix4f().setOrtho2D(0, screenW, screenH, 0).get(matrix));
        glUniform4f(uColor, 1, 1, 1, alpha);
        glUniform1f(uFadeEnd, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        quad.clear();
        quad.put(x0).put(y0).put(0).put(u0).put(v0);
        quad.put(x1).put(y0).put(0).put(u1).put(v0);
        quad.put(x1).put(y1).put(0).put(u1).put(v1);
        quad.put(x0).put(y1).put(0).put(u0).put(v1);
        quad.flip();
        glBufferSubData(GL_ARRAY_BUFFER, 0, quad);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        glBindVertexArray(0);
    }

    void dispose() {
        glDeleteTextures(texture);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteProgram(shader.program);
    }
}
