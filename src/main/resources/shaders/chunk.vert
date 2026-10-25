#version 330 core
layout(location = 0) in uint aPacked;
layout(location = 1) in uint aLight;

uniform mat4 uViewProj;
uniform ivec2 uCamChunk;  // camera chunk coordinates
uniform vec3 uCamOffset;  // camera position relative to its chunk origin (y from the world bottom)
uniform vec3 uTints[4];   // none, grass, foliage, water
uniform float uDaylight;  // 1 at noon, low at night: scales sky light only
uniform float uMinLight;  // debug floor (--fullbright), 0 normally
uniform sampler2DArray uBiome; // grass (layer 0) and foliage (layer 1) colour per column, wraps every 512 blocks

out vec2 vUv;
flat out float vLayer;
flat out float vOverlay;
out vec3 vTint;
out float vLight;
out float vDist;

const float SHADE[7] = float[7](0.6, 0.6, 0.5, 1.0, 0.8, 0.8, 0.9);
const float AO[4] = float[4](0.45, 0.65, 0.82, 1.0);

float level(float l) {
    // Each light level is ~20% darker than the next, with a small floor so caves are not pitch black.
    return max(pow(0.8, 15.0 - l), 0.03);
}

void main() {
    vec3 local = vec3(aPacked & 31u, (aPacked >> 5) & 31u, (aPacked >> 10) & 31u);
    uint face = (aPacked >> 15) & 7u;
    uint layer = (aPacked >> 18) & 1023u;
    uint tint = (aPacked >> 28) & 3u;
    uint special = aPacked >> 30;
    if (special == 1u) local.y -= 0.125;      // water surface sits 2/16 below the block top
    else if (special == 2u) local.y -= 0.5;   // half blocks (slabs)
    vOverlay = special == 3u ? 1.0 : 0.0;

    // UV from position so greedy-merged quads repeat the texture once per block. V grows downward.
    if (face < 2u)      vUv = vec2(face == 0u ? local.z : -local.z, -local.y);
    else if (face < 4u) vUv = vec2(local.x, face == 3u ? local.z : -local.z);
    else if (face < 6u) vUv = vec2(face == 5u ? local.x : -local.x, -local.y);
    else                vUv = vec2(local.x, -local.y); // cross planes: x spans exactly one block

    // Smooth light: sums of the 4 cells around the vertex (0..60), averaged here.
    float sky = level(float(aLight & 63u) * 0.25) * uDaylight;
    float block = level(float((aLight >> 6) & 63u) * 0.25);
    float light = max(max(sky, block), uMinLight) * AO[(aLight >> 12) & 3u] * SHADE[face];
    vLayer = float(layer);
    // Section coordinates are stored mod 64: unambiguous while the render radius stays below 32.
    int dx = ((int((aLight >> 14) & 63u) - (uCamChunk.x & 63) + 96) & 63) - 32;
    int dz = ((int((aLight >> 20) & 63u) - (uCamChunk.y & 63) + 96) & 63) - 32;
    int sy = int((aLight >> 26) & 31u);
    vec3 tintColor = uTints[tint];
    if (tint == 1u || tint == 2u) {
        // Column coordinates mod 512 (32 chunks); linear filtering at block corners blends columns.
        vec2 column = vec2(float(((uCamChunk.x + dx) & 31) * 16) + local.x,
                           float(((uCamChunk.y + dz) & 31) * 16) + local.z);
        tintColor = textureLod(uBiome, vec3(column / 512.0, float(tint - 1u)), 0.0).rgb;
    }
    vTint = tintColor;
    vLight = light;
    vec3 p = vec3(dx * 16, sy * 16, dz * 16) + local - uCamOffset;
    vDist = length(p);
    gl_Position = uViewProj * vec4(p, 1.0);
}
