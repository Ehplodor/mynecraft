#version 330 core
// The finished world picture seen from in or at the water: what lies below the (wavy) waterline
// is bent, lit by caustics and swallowed by the water with distance, red first; drops run down the
// screen after coming out, bubbles rise after diving in.
in vec2 vUv;
uniform sampler2D uColor;
uniform sampler2D uDepth;
uniform mat4 uInvViewProj;     // camera-relative
uniform vec3 uCamWorld;        // camera position (wrapped) for world-anchored caustics
uniform vec2 uDepthRange;      // near, far
uniform float uAspect;
uniform float uSurface;        // water surface height over the eye (negative: the eye is above it)
uniform vec2 uLens;            // height of the screen centre over the eye, and per unit of screen y
uniform vec3 uWaterColor;      // what the water looks like far away, lit as at the eye
uniform float uDaylight;
uniform float uTime;
uniform float uWet;            // 1 just out of the water, drying to 0
uniform float uSplash;         // 1 just in, settling to 0
out vec4 fragColor;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

/** Bright net of light focused by the waves onto what lies under them. */
float caustics(vec2 p) {
    float t = uTime * 0.6, c = 0.0;
    for (int i = 0; i < 3; i++) {
        vec2 q = p * (0.55 + 0.35 * float(i)) + vec2(t * (0.3 + 0.1 * float(i)), -t * 0.2);
        vec2 w = q + vec2(sin(q.y * 1.7 + t), cos(q.x * 1.5 - t));
        c += pow(1.0 - abs(sin(w.x) * sin(w.y)), 6.0);
    }
    return c / 3.0;
}

/** Round drops (or bubbles) in a grid of cells: offset to the drop's centre and its radius, 0 when none. */
vec3 drops(vec2 uv, float scale, float fall) {
    vec2 g = vec2(uv.x * uAspect, uv.y) * scale;
    vec2 id = floor(g);
    float h = hash(id);
    if (h > 0.55) return vec3(0.0);
    vec2 c = vec2(0.2 + 0.6 * fract(h * 17.0), fract(0.3 + h * 7.0 - fall * (0.4 + h)));
    vec2 d = fract(g) - c;
    float r = 0.12 + 0.18 * fract(h * 31.0);
    return length(d * vec2(1.0, 0.8)) < r ? vec3(d, r) : vec3(0.0);
}

void main() {
    vec2 ndc = vUv * 2.0 - 1.0;
    float wave = 0.006 * sin(ndc.x * 5.0 + uTime * 2.3) + 0.003 * sin(ndc.x * 13.0 - uTime * 3.1);
    float lensY = uLens.x + uLens.y * ndc.y;
    float edge = lensY - (uSurface + wave); // < 0: this part of the view is under water
    bool under = edge < 0.0;
    vec2 uv = vUv;
    if (under) {
        float a = 0.0025 + 0.01 * uSplash;
        uv += vec2(sin(vUv.y * 23.0 + uTime * 1.9), cos(vUv.x * 19.0 + uTime * 1.6)) * a;
    } else if (uWet > 0.0) {
        vec3 d = drops(vUv, 7.0, uTime * 0.15);
        if (d.z > 0.0) uv -= d.xy * 0.08 * uWet; // a drop is a little lens
    }
    uv = clamp(uv, vec2(0.001), vec2(0.999));
    vec3 col = texture(uColor, uv).rgb;
    if (under) {
        float depth = texture(uDepth, uv).r;
        vec4 p = uInvViewProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        vec3 pos = p.xyz / p.w;
        float dist = depth >= 1.0 ? 1e4 : length(pos);
        if (depth < 1.0 && pos.y < uSurface - 0.05) { // ground and things in the water catch caustics
            float c = caustics((pos.xz + uCamWorld.xz) * 0.9 + pos.y * 0.25);
            col *= 1.0 + 1.3 * c * uDaylight * exp(-max(0.0, uSurface - pos.y) * 0.06);
        }
        vec3 keep = exp(-vec3(0.16, 0.065, 0.05) * dist);
        col = col * keep + uWaterColor * (1.0 - keep);
        col *= 1.0 - 0.3 * dot(ndc, ndc) * 0.5; // the eye's own shadow at the borders
        if (uSplash > 0.0) { // foam and bubbles going up after a dive
            vec3 b = drops(vUv, 9.0, -uTime * 0.9);
            float ring = b.z > 0.0 ? smoothstep(b.z * 0.6, b.z, length(b.xy)) : 0.0;
            col = mix(col, vec3(0.85, 0.95, 1.0), clamp(ring * uSplash + 0.35 * uSplash * uSplash, 0.0, 1.0));
        }
    }
    // The waterline itself: a thin dark meniscus with a bright rim on the lens.
    float line = 1.0 - smoothstep(0.0, 0.012, abs(edge));
    col = mix(col, col * 0.55 + vec3(0.05, 0.09, 0.1), line * 0.8);
    fragColor = vec4(col, 1.0);
}
