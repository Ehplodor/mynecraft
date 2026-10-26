#version 330 core
// Vertex pulling: no attributes. Each quad is one RGBA32UI texel of uQuads (layout documented in
// ChunkMesher); its 4 corners are gl_VertexID 4q..4q+3, drawn with indices 0,1,2, 2,3,0.

uniform usamplerBuffer uQuads;
uniform mat4 uViewProj;
uniform ivec2 uCamChunk;  // camera chunk coordinates
uniform vec3 uCamOffset;  // camera position relative to its chunk origin (y from the world bottom)
uniform vec3 uTints[4];   // none, grass, foliage, water
uniform float uDaylight;  // 1 at noon, low at night: scales sky light only
uniform float uMinLight;  // debug floor (--fullbright), 0 normally
uniform float uTime;      // seconds, wraps: scrolls flowing fluids
uniform sampler2DArray uBiome; // grass (layer 0) and foliage (layer 1) colour per column, wraps every 512 blocks
uniform mat4 uShadowVP;   // sun's view-projection (camera-relative), for shadows
uniform vec3 uSunDir;     // towards the sun

out vec2 vUv;
flat out float vLayer;
flat out float vOverlay;
out vec3 vTint;
out float vLight;
out float vDist;
out float vSky;           // sky light (with daylight), block light, AO x face shade: lit in the fragment
out float vBlock;
out float vShade;
out float vSun;           // how squarely the face meets the sun (0..1)
out vec3 vShadow;         // position in the shadow map (0..1)
out vec3 vPos;            // camera-relative position
flat out float vWater;    // 1 on water faces (reflections, depth)

// Face normals: -x, +x, -y, +y, -z, +z, cross planes (lit as if facing up a little).
const vec3 NORMAL[7] = vec3[7](vec3(-1, 0, 0), vec3(1, 0, 0), vec3(0, -1, 0), vec3(0, 1, 0), vec3(0, 0, -1), vec3(0, 0, 1),
                               vec3(0, 0.7, 0));

const float SHADE[7] = float[7](0.6, 0.6, 0.5, 1.0, 0.8, 0.8, 0.9);
const float AO[4] = float[4](0.45, 0.65, 0.82, 1.0);
// Corner (u, v) offsets in quad order.
const ivec2 CORNER[4] = ivec2[4](ivec2(0, 0), ivec2(1, 0), ivec2(1, 1), ivec2(0, 1));

// Surface height (sixteenths) at corner c of a SPECIAL_FLUID_CORNERS quad.
float cornerHeight(uvec4 q, int c) {
    uint word = c < 2 ? q.w : q.z;
    return float((word >> uint(24 + 4 * (c & 1))) & 15u) + 1.0;
}

float level(float l) {
    // Each light level is ~20% darker than the next, with a small floor so caves are not pitch black.
    return max(pow(0.8, 15.0 - l), 0.03);
}

void main() {
    uvec4 q = texelFetch(uQuads, gl_VertexID >> 2);
    vec3 origin = vec3(q.x & 31u, (q.x >> 5) & 31u, (q.x >> 10) & 31u);
    uint face = (q.x >> 15) & 7u;
    int w = int((q.x >> 18) & 15u) + 1, h = int((q.x >> 22) & 15u) + 1;
    uint special = (q.x >> 26) & 7u;
    // The index pattern splits along corners 0-2; shifting corners by one moves the split to 1-3.
    int k = ((gl_VertexID & 3) + int((q.x >> 29) & 1u)) & 3;

    vec3 local;
    bool positive = (face & 1u) != 0u;
    if (face < 6u) {
        // Negative faces walk the corners backwards so every face winds counter-clockwise from outside.
        int c = positive ? k : (4 - k) % 4;
        int d = int(face) / 2, u = (d + 1) % 3, v = (d + 2) % 3;
        local = origin;
        local[u] += float(CORNER[c].x * w);
        local[v] += float(CORNER[c].y * h);
        float yMin = origin.y, yMax = origin.y + (u == 1 ? float(w) : v == 1 ? float(h) : 0.0);
        bool top = d == 1 ? face == 3u : local.y == yMax;
        bool bottom = d == 1 ? face == 2u : local.y == yMin;
        if (special == 1u && top) local.y -= 1.0 - float((q.w >> 24) & 31u) / 16.0; // fluid surface
        else if (special == 5u && top) local.y -= 1.0 - cornerHeight(q, c) / 16.0;  // sloping fluid
        else if (special == 2u && top) local.y -= 0.5;     // bottom slab
        else if (special == 4u && bottom) local.y += 0.5;  // top slab
        k = c;
    } else {
        // Cross plane: width bit = second diagonal, height bit = back side (reversed winding).
        bool second = w == 2, back = h == 2;
        int c = back ? (4 - k) % 4 : k;
        float x0 = second ? 1.0 : 0.0;
        local = origin + vec3(CORNER[c].x == 0 ? x0 : 1.0 - x0, float(CORNER[c].y), float(CORNER[c].x));
        k = c;
    }

    // UV from position so greedy-merged quads repeat the texture once per block. V grows downward.
    if (face < 2u)      vUv = vec2(face == 0u ? local.z : -local.z, -local.y);
    else if (face < 4u) vUv = vec2(local.x, face == 3u ? local.z : -local.z);
    else if (face < 6u) vUv = vec2(face == 5u ? local.x : -local.x, -local.y);
    else                vUv = vec2(local.x, -local.y); // cross planes: x spans exactly one block
    if (special == 5u) {
        // Currents: the texture slides downhill on sloping surfaces, and down the sides of falls.
        if (face == 3u) {
            // Top face corners: c -> (x, z) = (CORNER[c].y, CORNER[c].x).
            float h0 = cornerHeight(q, 0), h1 = cornerHeight(q, 1), h2 = cornerHeight(q, 2), h3 = cornerHeight(q, 3);
            vec2 down = -vec2((h2 + h3) - (h0 + h1), (h1 + h2) - (h0 + h3)); // (x, z)
            float len = length(down);
            if (len > 0.01) vUv -= down / len * uTime * min(1.5, 0.4 + len * 0.15);
        } else if (face >= 4u || face < 2u) {
            vUv.y -= uTime * 1.5;
        }
    }

    uint lightWord = k < 2 ? q.z : q.w;
    uint light = (lightWord >> uint((k & 1) * 12)) & 4095u;
    uint ao = special == 5u ? 3u : (q.z >> uint(24 + 2 * k)) & 3u;
    // Smooth light: sums of the 4 cells around the vertex (0..60), averaged here.
    float sky = level(float(light & 63u) * 0.25) * uDaylight;
    float block = level(float((light >> 6) & 63u) * 0.25);
    float lit = max(max(sky, block), uMinLight) * AO[ao] * SHADE[face];
    vSky = sky;
    vBlock = max(block, uMinLight);
    vShade = AO[ao] * SHADE[face];
    vec3 normal = NORMAL[face];
    vSun = face == 6u ? 0.6 : max(dot(normal, uSunDir), 0.0);

    uint tint = (q.y >> 10) & 3u;
    vLayer = float(q.y & 1023u);
    vOverlay = special == 3u ? 1.0 : 0.0;

    // Section coordinates are stored mod 64: unambiguous while the render radius stays below 32.
    int dx = ((int((q.y >> 12) & 63u) - (uCamChunk.x & 63) + 96) & 63) - 32;
    int dz = ((int((q.y >> 18) & 63u) - (uCamChunk.y & 63) + 96) & 63) - 32;
    int sy = int((q.y >> 24) & 31u);
    vec3 tintColor = uTints[tint];
    if (tint == 1u || tint == 2u) {
        // Column coordinates mod 512 (32 chunks); linear filtering at block corners blends columns.
        vec2 column = vec2(float(((uCamChunk.x + dx) & 31) * 16) + local.x,
                           float(((uCamChunk.y + dz) & 31) * 16) + local.z);
        tintColor = textureLod(uBiome, vec3(column / 512.0, float(tint - 1u)), 0.0).rgb;
    }
    vTint = tintColor;
    vLight = lit;
    vec3 p = vec3(dx * 16, sy * 16, dz * 16) + local - uCamOffset;
    vDist = length(p);
    vPos = p;
    // Pushed a little along the normal before the lookup: no self-shadowing speckles ("acne").
    vShadow = (uShadowVP * vec4(p + (face == 6u ? vec3(0, 0.1, 0) : normal * 0.08), 1.0)).xyz * 0.5 + 0.5;
    vWater = tint == 3u ? 1.0 : 0.0;
    gl_Position = uViewProj * vec4(p, 1.0);
}
