#version 330 core
layout(location = 0) in uint aPacked;

uniform mat4 uViewProj;
uniform vec3 uOrigin;     // section origin relative to the camera (camera-relative rendering)
uniform vec3 uColors[16];

out vec3 vColor;
out float vDist;

const float SHADE[6] = float[6](0.6, 0.6, 0.5, 1.0, 0.8, 0.8);

void main() {
    vec3 p = vec3(aPacked & 31u, (aPacked >> 5) & 31u, (aPacked >> 10) & 31u) + uOrigin;
    uint face = (aPacked >> 15) & 7u;
    uint block = (aPacked >> 18) & 255u;
    vColor = uColors[block] * SHADE[face];
    vDist = length(p);
    gl_Position = uViewProj * vec4(p, 1.0);
}
