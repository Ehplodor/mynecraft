package net.mynecraft.client;

import org.joml.Matrix4f;
import org.joml.Vector3d;

final class Camera {
    final Vector3d pos = new Vector3d();
    float yaw, pitch; // radians
    float fovY = (float) Math.toRadians(70);

    /** View-projection with the camera at the origin; world positions are passed camera-relative. */
    Matrix4f viewProj(Matrix4f dest, float aspect, float far) {
        return dest.setPerspective(fovY, aspect, 0.05f, far)
            .rotateX(-pitch)
            .rotateY(-yaw);
    }

}
