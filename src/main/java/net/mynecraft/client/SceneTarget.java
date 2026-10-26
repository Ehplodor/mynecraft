package net.mynecraft.client;

import static org.lwjgl.opengl.GL33.*;

/**
 * The world is drawn into this off-screen target (colour + depth textures) rather than straight
 * to the window, so that water can see what lies under it: before the translucent pass, colour
 * and depth are copied to a second target the water shader samples (refraction, depth). At the
 * end, the picture is copied to the window.
 */
final class SceneTarget {
    private int width, height;
    private int fbo, color, depth;          // drawn into
    private int copyFbo, copyColor, copyDepth; // sampled by water

    void bind(int w, int h) {
        if (w != width || h != height) resize(w, h);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    }

    private void resize(int w, int h) {
        dispose();
        width = w;
        height = h;
        color = texture(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE);
        depth = texture(GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT);
        fbo = framebuffer(color, depth);
        copyColor = texture(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE);
        copyDepth = texture(GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT);
        copyFbo = framebuffer(copyColor, copyDepth);
    }

    private int texture(int internal, int format, int type) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glTexImage2D(GL_TEXTURE_2D, 0, internal, width, height, 0, format, type, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return t;
    }

    private static int framebuffer(int color, int depth) {
        int f = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, f);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("scene framebuffer incomplete");
        return f;
    }

    /** Copies what is drawn so far (opaque world) for the water to sample; drawing continues on the scene. */
    void snapshot() {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, copyFbo);
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    }

    int copyColor() { return copyColor; }

    int copyDepth() { return copyDepth; }

    int width() { return width; }

    int height() { return height; }

    /** Puts the finished picture in the window and draws there from now on (HUD). */
    void present() {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    void dispose() {
        if (fbo == 0) return;
        glDeleteFramebuffers(new int[] {fbo, copyFbo});
        glDeleteTextures(new int[] {color, depth, copyColor, copyDepth});
        fbo = copyFbo = 0;
    }
}
