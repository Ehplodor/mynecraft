package net.mynecraft.client;

import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import static org.lwjgl.opengl.GL33.*;

/**
 * Sun shadows: the terrain within {@link #RADIUS} blocks is drawn into a depth texture as seen
 * from the sun (orthographic), and the world pass compares against it. The projection is kept on
 * the shadow map's texel grid in world space, so shadow edges do not crawl as the player moves.
 * Matrices work in camera-relative coordinates, like the world pass.
 */
final class ShadowMap {
    static final int SIZE = 2048;
    static final float RADIUS = 96;
    final int fbo, depth;
    final Matrix4f viewProj = new Matrix4f();

    ShadowMap() {
        depth = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depth);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, SIZE, SIZE, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); // hardware 2x2 comparison filtering
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("shadow framebuffer incomplete");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * The light's view-projection for a sun direction (towards the sun) and the camera's world
     * position, in camera-relative coordinates, snapped to the texel grid.
     */
    void update(Vector3d sun, double camX, double camY, double camZ) {
        Vector3d d = new Vector3d(sun).normalize();
        Vector3d up = Math.abs(d.z) > 0.9 ? new Vector3d(1, 0, 0) : new Vector3d(0, 0, 1);
        Matrix4d rot = new Matrix4d().lookAt(0, 0, 0, -d.x, -d.y, -d.z, up.x, up.y, up.z); // looking along the light
        Vector3d c = rot.transformPosition(new Vector3d(camX, camY, camZ));
        double texel = 2 * RADIUS / SIZE;
        double fx = c.x - Math.floor(c.x / texel) * texel, fy = c.y - Math.floor(c.y / texel) * texel;
        // Camera-relative: the eye sits up the light direction, far enough to catch casters.
        Matrix4d view = new Matrix4d().lookAt(d.x * 300, d.y * 300, d.z * 300, 0, 0, 0, up.x, up.y, up.z);
        Matrix4d proj = new Matrix4d().ortho(-RADIUS - fx, RADIUS - fx, -RADIUS - fy, RADIUS - fy, 1, 600);
        viewProj.set(proj.mul(view));
    }

    void dispose() {
        glDeleteFramebuffers(fbo);
        glDeleteTextures(depth);
    }
}
