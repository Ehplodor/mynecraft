package net.mynecraft.client;

import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL33.*;

/** Crosshair and outline of the targeted block. Static geometry, two tiny draws per frame. */
final class Overlay {
    private static final float E = 0.002f; // outline slightly outside the block to avoid z-fighting
    private final Shader shader;
    private final int vao, vbo, uMvp, uOffset, uColor;
    private final float[] matrixBuf = new float[16];
    private final Matrix4f ortho = new Matrix4f();

    Overlay() {
        shader = new Shader("/shaders/overlay.vert", "/shaders/overlay.frag");
        uMvp = shader.uniform("uMvp");
        uOffset = shader.uniform("uOffset");
        uColor = shader.uniform("uColor");

        float lo = -E, hi = 1 + E;
        float[] c = {lo, hi};
        float[] data = new float[24 * 3 + 4 * 3];
        int i = 0;
        // 12 cube edges: for each axis, 4 segments along it.
        for (int axis = 0; axis < 3; axis++)
            for (float a : c) for (float b : c) {
                float[] p0 = new float[3], p1 = new float[3];
                p0[axis] = lo; p1[axis] = hi;
                p0[(axis + 1) % 3] = p1[(axis + 1) % 3] = a;
                p0[(axis + 2) % 3] = p1[(axis + 2) % 3] = b;
                for (float v : p0) data[i++] = v;
                for (float v : p1) data[i++] = v;
            }
        // Crosshair in pixels around the screen centre.
        float s = 10;
        float[] cross = {-s, 0, 0, s, 0, 0, 0, -s, 0, 0, s, 0};
        System.arraycopy(cross, 0, data, i, cross.length);

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
    }

    /** @param viewProj camera-relative view-projection; offset = block position minus camera. */
    void outline(Matrix4f viewProj, float ox, float oy, float oz) {
        glUseProgram(shader.program);
        glUniformMatrix4fv(uMvp, false, viewProj.get(matrixBuf));
        glUniform3f(uOffset, ox, oy, oz);
        glUniform4f(uColor, 0, 0, 0, 1);
        glBindVertexArray(vao);
        glDrawArrays(GL_LINES, 0, 24);
    }

    void crosshair(int width, int height) {
        glUseProgram(shader.program);
        ortho.setOrtho2D(-width / 2f, width / 2f, -height / 2f, height / 2f);
        glUniformMatrix4fv(uMvp, false, ortho.get(matrixBuf));
        glUniform3f(uOffset, 0, 0, 0);
        glUniform4f(uColor, 1, 1, 1, 1);
        glDisable(GL_DEPTH_TEST);
        glBindVertexArray(vao);
        glDrawArrays(GL_LINES, 24, 4);
        glEnable(GL_DEPTH_TEST);
    }

    void dispose() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteProgram(shader.program);
    }
}
