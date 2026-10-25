#version 330 core
layout(location = 0) in uint aPacked;
layout(location = 1) in uint aLight;

uniform mat4 uViewProj;
uniform vec3 uOrigin;     // section origin relative to the camera (camera-relative rendering)
uniform vec3 uTints[4];   // none, grass, foliage, water

out vec2 vUv;
flat out float vLayer;
out vec3 vLight;
out float vDist;

const float SHADE[7] = float[7](0.6, 0.6, 0.5, 1.0, 0.8, 0.8, 0.9);
const float AO[4] = float[4](0.45, 0.65, 0.82, 1.0);

float level(uint l) {
    // Each light level is ~20% darker than the next, with a small floor so caves are not pitch black.
    return max(pow(0.8, 15.0 - float(l)), 0.03);
}

void main() {
    vec3 local = vec3(aPacked & 31u, (aPacked >> 5) & 31u, (aPacked >> 10) & 31u);
    uint face = (aPacked >> 15) & 7u;
    uint layer = (aPacked >> 18) & 1023u;
    uint tint = (aPacked >> 28) & 3u;

    // UV from position so greedy-merged quads repeat the texture once per block. V grows downward.
    if (face < 2u)      vUv = vec2(face == 0u ? local.z : -local.z, -local.y);
    else if (face < 4u) vUv = vec2(local.x, face == 3u ? local.z : -local.z);
    else if (face < 6u) vUv = vec2(face == 5u ? local.x : -local.x, -local.y);
    else                vUv = vec2(local.x, -local.y); // cross planes: x spans exactly one block

    float sky = level((aLight >> 4) & 15u), block = level(aLight & 15u);
    float light = max(sky, block) * AO[(aLight >> 8) & 3u] * SHADE[face];
    vLayer = float(layer);
    vLight = uTints[tint] * light;
    vec3 p = local + uOrigin;
    vDist = length(p);
    gl_Position = uViewProj * vec4(p, 1.0);
}
