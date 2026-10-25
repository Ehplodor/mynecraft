#version 330 core
layout(location = 0) in uint aPacked;

uniform mat4 uViewProj;
uniform vec3 uOrigin;     // section origin relative to the camera (camera-relative rendering)
uniform vec3 uTint;       // biome tint for faces with a tintindex (constant until biomes exist)

out vec2 vUv;
flat out float vLayer;
out vec3 vLight;
out float vDist;

const float SHADE[6] = float[6](0.6, 0.6, 0.5, 1.0, 0.8, 0.8);

void main() {
    vec3 local = vec3(aPacked & 31u, (aPacked >> 5) & 31u, (aPacked >> 10) & 31u);
    uint face = (aPacked >> 15) & 7u;
    uint layer = (aPacked >> 18) & 1023u;
    bool tinted = ((aPacked >> 28) & 1u) != 0u;

    // UV from position so greedy-merged quads repeat the texture once per block. V grows downward.
    if (face < 2u)      vUv = vec2(face == 0u ? local.z : -local.z, -local.y);
    else if (face < 4u) vUv = vec2(local.x, face == 3u ? local.z : -local.z);
    else                vUv = vec2(face == 5u ? local.x : -local.x, -local.y);

    vLayer = float(layer);
    vLight = (tinted ? uTint : vec3(1.0)) * SHADE[face];
    vec3 p = local + uOrigin;
    vDist = length(p);
    gl_Position = uViewProj * vec4(p, 1.0);
}
