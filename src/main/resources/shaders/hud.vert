#version 330 core
layout(location = 0) in vec2 aPos;    // pixels, origin top-left
layout(location = 1) in vec3 aUv;     // u, v, texture layer (-1: plain colour, -2: font)
layout(location = 2) in vec4 aColor;
uniform mat4 uOrtho;
out vec3 vUv;
out vec4 vColor;
void main() {
    vUv = aUv;
    vColor = aColor;
    gl_Position = uOrtho * vec4(aPos, 0.0, 1.0);
}
