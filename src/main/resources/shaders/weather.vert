#version 330 core
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aColor;
uniform mat4 uViewProj;
uniform vec3 uOffset; // anchor minus camera: vertices are stored relative to an anchor
out vec4 vColor;
out vec2 vHorizontal;
void main() {
    vec3 p = aPos + uOffset;
    vColor = aColor;
    vHorizontal = p.xz;
    gl_Position = uViewProj * vec4(p, 1.0);
}
