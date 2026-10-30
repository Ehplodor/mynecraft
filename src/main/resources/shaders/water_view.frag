#version 330 core
// The finished world picture seen from in or at the water. Under the (wavy) waterline: light is
// absorbed with distance (red first) and scattered back toward the eye, brighter looking up toward
// the lit surface than down into the deep; shafts of sunlight hang under the surface; caustics
// play on what faces up; specks drift in the water; the picture wavers slightly. Diving in, the
// view starts blurred and cloudy (stirred water, eyes adjusting) and clears; coming out, a few
// drops run down the lens.
in vec2 vUv;
uniform sampler2D uColor;
uniform sampler2D uDepth;
uniform mat4 uInvViewProj;     // camera-relative
uniform vec3 uCamWorld;        // camera position (wrapped) for world-anchored effects
uniform float uAspect;
uniform float uSurface;        // water surface height over the eye (negative: the eye is above it)
uniform vec2 uLens;            // height of the screen centre over the eye, and per unit of screen y
uniform vec3 uWaterColor;      // scattered light of the water, lit as at the eye
uniform vec3 uSunDir;
uniform float uDaylight;
uniform float uTime;
uniform float uWet;            // 1 just out of the water, drying to 0
uniform float uSplash;         // 1 just in, settling to 0
out vec4 fragColor;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float hash3(vec3 p) { return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453); }

float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), f.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), f.x), f.y);
}

/** Bright net of light focused by the waves onto what lies under them. */
float caustics(vec2 p) {
    float t = uTime * 0.5, c = 0.0;
    for (int i = 0; i < 2; i++) {
        vec2 q = p * (0.6 + 0.45 * float(i)) + vec2(t * (0.3 + 0.1 * float(i)), -t * 0.2);
        vec2 w = q + vec2(sin(q.y * 1.7 + t), cos(q.x * 1.5 - t)) * 0.8;
        c += pow(1.0 - abs(sin(w.x) * sin(w.y)), 7.0);
    }
    return c * 0.5;
}

/** A drop sliding down the lens in this cell: offset to its centre and radius, 0 when none. */
vec3 drop(vec2 uv) {
    vec2 g = vec2(uv.x * uAspect, uv.y) * 5.0;
    g.y += uTime * 0.25;
    vec2 id = floor(g);
    float h = hash(id);
    if (h > 0.3) return vec3(0.0);
    vec2 d = fract(g) - vec2(0.2 + 0.6 * fract(h * 17.0), 0.5);
    float r = 0.08 + 0.1 * fract(h * 31.0);
    return length(d * vec2(1.0, 0.7)) < r ? vec3(d, r) : vec3(0.0);
}

vec3 viewRay(vec2 uv, float depth) {
    vec4 p = uInvViewProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

void main() {
    vec2 ndc = vUv * 2.0 - 1.0;
    float wave = 0.006 * sin(ndc.x * 5.0 + uTime * 2.3) + 0.003 * sin(ndc.x * 13.0 - uTime * 3.1);
    float edge = uLens.x + uLens.y * ndc.y - (uSurface + wave); // < 0: this part of the view is under water
    bool under = edge < 0.0;
    vec2 uv = vUv;
    if (under) {
        uv += vec2(noise(vUv * 6.0 + uTime * 0.4) - 0.5, noise(vUv * 6.0 - uTime * 0.35 + 7.0) - 0.5) * 0.004;
    } else if (uWet > 0.0) {
        vec3 d = drop(vUv);
        if (d.z > 0.0) uv -= d.xy * 0.06 * uWet * (1.0 - length(d.xy) / d.z); // a drop is a small lens
    }
    uv = clamp(uv, vec2(0.001), vec2(0.999));
    vec3 col = texture(uColor, uv).rgb;
    if (under) {
        if (uSplash > 0.0) { // just in: out of focus, clearing
            float r = 0.005 * uSplash;
            vec3 sum = col;
            for (int i = 0; i < 8; i++) {
                float a = float(i) * 0.785 + 0.4;
                sum += texture(uColor, clamp(uv + vec2(cos(a), sin(a) * uAspect) * r, vec2(0.001), vec2(0.999))).rgb;
            }
            col = sum / 9.0;
        }
        float depth = texture(uDepth, uv).r;
        vec3 pos = viewRay(uv, depth);
        vec3 dir = normalize(viewRay(uv, 1.0));
        float dist = depth >= 1.0 ? 1e4 : length(pos);
        if (dir.y > 0.0) dist = min(dist, max(0.0, uSurface) / dir.y); // light only crosses water up to the surface
        if (depth < 1.0) { // caustics on what faces the light, fading with depth under the surface
            vec3 n = normalize(cross(dFdx(pos), dFdy(pos)));
            if (dot(n, dir) > 0.0) n = -n; // facing the eye
            float up = clamp(n.y, 0.0, 1.0);
            float below = uSurface - pos.y;
            if (below > 0.0) {
                float c = caustics((pos.xz + uCamWorld.xz) * 0.8 + pos.y * 0.2);
                col *= 1.0 + 1.1 * c * up * uDaylight * exp(-below * 0.08);
            }
        }
        // Scattered light: from the surface overhead, fading toward the deep below.
        float look = dir.y;
        vec3 scatter = uWaterColor * (0.35 + 1.25 * smoothstep(-0.6, 0.9, look));
        // Shafts of sunlight: parallel columns along the (refracted) light, made visible by the
        // water between them; marched a few steps along the view so they converge in perspective.
        float shafts = 0.0;
        if (uDaylight > 0.05) {
            vec3 l = normalize(vec3(uSunDir.x * 0.6, max(uSunDir.y, 0.2) + 0.6, uSunDir.z * 0.6));
            vec3 bu = normalize(cross(l, vec3(0.0, 0.0, 1.0))), bv = cross(l, bu);
            // Broad columns (a few blocks across) sampled finely enough that moving does not make
            // them swim; none right at the eye, where a single column would flood the view.
            float reach = min(dist, 16.0);
            for (int i = 0; i < 10; i++) {
                float z = 1.5 + (reach - 1.5) * (float(i) + 0.5) / 10.0;
                if (z > reach) break;
                vec3 p = dir * z;
                vec3 w = p + vec3(uCamWorld.x, 0.0, uCamWorld.z);
                float n = noise(vec2(dot(w, bu), dot(w, bv)) * 0.22 + vec2(uTime * 0.03, uTime * 0.012));
                shafts += smoothstep(0.5, 0.85, n) * exp(-max(0.0, uSurface - p.y) * 0.08);
            }
            shafts *= max(0.0, reach - 1.5) / 14.5 / 10.0 * uDaylight * smoothstep(0.0, 0.3, uSunDir.y);
        }
        scatter += uWaterColor * shafts * 4.5;
        vec3 keep = exp(-vec3(0.17, 0.07, 0.055) * dist * (1.0 + 0.6 * uSplash));
        col = col * keep + scatter * (1.0 - keep);
        // Specks drifting in the water, a few layers in front of the eye.
        for (int i = 1; i <= 3; i++) {
            float z = float(i) * 1.6;
            if (z > dist) break;
            vec3 p = dir * z + uCamWorld + vec3(0.0, -uTime * 0.05, 0.0);
            vec3 cell = floor(p * 2.5);
            float h = hash3(cell + float(i) * 13.0);
            vec3 c = (cell + 0.2 + 0.6 * vec3(h, fract(h * 7.0), fract(h * 13.0))) / 2.5;
            float d = length(p - c);
            col += uWaterColor * 1.6 * smoothstep(0.035, 0.0, d) * step(0.85, h) * exp(-z * 0.15);
        }
        col = mix(col, scatter * 1.3, 0.2 * uSplash * uSplash); // stirred, cloudy water at first
        col *= 1.0 - 0.18 * dot(ndc, ndc);
    }
    // The waterline itself: a thin dark meniscus.
    float line = 1.0 - smoothstep(0.0, 0.01, abs(edge));
    col = mix(col, col * 0.6 + uWaterColor * 0.4, line * 0.7);
    fragColor = vec4(col, 1.0);
}
