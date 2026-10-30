#version 330 core
in vec2 vUv;
flat in float vLayer;
flat in float vOverlay;
in vec3 vTint;
in float vLight;
in float vDist;
in float vSky;
in float vBlock;
in float vShade;
in float vSun;
in vec3 vShadow;
in vec3 vPos;
flat in float vWater;

uniform sampler2DArray uTextures;
uniform vec3 uFogColor;
uniform float uFogEnd;
uniform sampler2DShadow uShadowMap;
uniform float uShadowStrength; // 0 at night (no sun), 1 by day
uniform vec3 uSunDir;
uniform vec3 uSkyColor;
uniform float uTime;
uniform vec2 uCamWorld;        // camera x, z (wrapped) for world-anchored water ripples
uniform sampler2D uSceneColor; // the opaque world, for refraction under water
uniform sampler2D uSceneDepth;
uniform vec2 uScreen;
uniform vec2 uDepthRange;      // near, far of the camera projection
uniform float uHideWater;      // 1: the eye is at the waterline, the water view draws the water

out vec4 fragColor;

/** Sun visibility 0..1: four hardware-filtered taps (soft edges), fading out at the map's border. */
float sunlight() {
    if (uShadowStrength <= 0.0) return 1.0;
    vec3 c = vShadow;
    if (c.x < 0.0 || c.x > 1.0 || c.y < 0.0 || c.y > 1.0 || c.z > 1.0) return 1.0;
    float t = 1.0 / 2048.0, sum = 0.0;
    sum += texture(uShadowMap, vec3(c.xy + vec2(-t, -t), c.z));
    sum += texture(uShadowMap, vec3(c.xy + vec2(t, -t), c.z));
    sum += texture(uShadowMap, vec3(c.xy + vec2(-t, t), c.z));
    sum += texture(uShadowMap, vec3(c.xy + vec2(t, t), c.z));
    float edge = smoothstep(0.0, 0.08, min(min(c.x, 1.0 - c.x), min(c.y, 1.0 - c.y)));
    return mix(1.0, sum * 0.25, edge);
}

float linearDepth(float d) {
    float z = d * 2.0 - 1.0;
    return 2.0 * uDepthRange.x * uDepthRange.y / (uDepthRange.y + uDepthRange.x - z * (uDepthRange.y - uDepthRange.x));
}

/** Ripples: a few travelling waves in different directions, as a surface normal. */
vec3 rippleNormal(vec2 p) {
    float t = uTime;
    vec2 g = vec2(0.0);
    g += vec2(0.8, 0.6) * cos(dot(p, vec2(0.8, 0.6)) * 1.3 + t * 1.6) * 0.06;
    g += vec2(-0.5, 0.86) * cos(dot(p, vec2(-0.5, 0.86)) * 2.1 + t * 2.1) * 0.04;
    g += vec2(0.2, -0.98) * cos(dot(p, vec2(0.2, -0.98)) * 3.7 + t * 2.9) * 0.025;
    g += vec2(-0.93, -0.37) * cos(dot(p, vec2(-0.93, -0.37)) * 6.3 + t * 3.7) * 0.012;
    return normalize(vec3(-g.x, 1.0, -g.y));
}

void main() {
    vec4 tex = texture(uTextures, vec3(vUv, vLayer));
#ifdef CUTOUT
    if (tex.a < 0.5) discard;
#endif
#ifdef SHADOW
    fragColor = vec4(1.0);
    return;
#endif
    // Light: sky light is brighter where the sun reaches the face and dimmer in shade; block light
    // (torches, glowstone) is untouched by the sun.
    float sun = sunlight();
    float direct = vSun * sun;
    float sky = vSky * mix(1.0, 0.68 + 0.42 * direct, uShadowStrength);
    float lit = max(sky, vBlock) * vShade;
    vec3 rgb;
    if (vOverlay > 0.5) {
        // Untinted base with a tinted overlay on top (grass block sides).
        vec4 over = texture(uTextures, vec3(vUv, vLayer + 1.0));
        rgb = mix(tex.rgb, over.rgb * vTint, over.a) * lit;
    } else {
        rgb = tex.rgb * vTint * lit;
    }
    float alpha = tex.a;
#ifdef TRANSLUCENT
    if (vWater > 0.5) {
        if (uHideWater > 0.5) discard;
        // Water: what is under it (bent by the ripples, darker and bluer with depth), the sky
        // mirrored at grazing angles (Fresnel), and the sun glinting on the ripples.
        vec2 suv = gl_FragCoord.xy / uScreen;
        float thick = max(0.0, linearDepth(texture(uSceneDepth, suv).r) - linearDepth(gl_FragCoord.z));
        vec3 v = normalize(-vPos);
        vec3 n = rippleNormal(vPos.xz + uCamWorld);
        bool above = v.y > 0.0;
        vec2 bent = suv + n.xz * 0.04 * clamp(thick / 4.0, 0.0, 1.0);
        if (linearDepth(texture(uSceneDepth, bent).r) < linearDepth(gl_FragCoord.z)) bent = suv; // not what is in front
        vec3 under = texture(uSceneColor, bent).rgb;
        float absorb = 1.0 - exp(-thick * 0.22);
        vec3 deep = vTint * 0.35 * lit;
        vec3 body = mix(under * mix(vec3(1.0), vTint * 1.4, 0.45), deep, absorb);
        if (above) {
            float fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
            vec3 r = reflect(-v, n);
            vec3 skyRef = mix(uFogColor, uSkyColor, clamp(r.y * 3.0, 0.0, 1.0)) * (0.35 + 0.65 * vSky);
            float glint = pow(max(dot(r, uSunDir), 0.0), 220.0) * 4.0 * sun * uShadowStrength;
            rgb = mix(body, skyRef, clamp(fresnel * 0.9, 0.0, 0.9)) + vec3(glint);
        } else {
            // From below: the world above shows through a round window (refraction up to the
            // critical angle); outside it the surface mirrors the dim water (total reflection).
            float window = smoothstep(0.6, 0.7, -v.y + (n.x + n.z) * 0.5);
            vec3 sky = texture(uSceneColor, suv + n.xz * 0.06).rgb;
            rgb = mix(vTint * 0.3 * lit, sky * 0.9 + vTint * 0.1, window);
        }
        alpha = 1.0;
    }
#endif
    float fog = clamp((vDist - uFogEnd * 0.6) / (uFogEnd * 0.4), 0.0, 1.0);
#ifdef TRANSLUCENT
    fragColor = vec4(mix(rgb, uFogColor, fog), alpha);
#else
    fragColor = vec4(mix(rgb, uFogColor, fog), 1.0);
#endif
}
