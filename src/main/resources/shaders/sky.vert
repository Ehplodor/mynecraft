#version 330 core
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uViewProj;
out vec2 vUv;
out vec2 vHorizontal; // camera-relative x/z, interpolated; distance is taken per fragment
void main() {
    vUv = aUv;
    vHorizontal = aPos.xz;
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
