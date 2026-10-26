#version 330 core
in vec3 vColor;
in float vGlow;
in vec3 vNormal;
in vec2 vLight;
in vec3 vShadow;
in float vDist;

uniform vec3 uFogColor;
uniform float uFogEnd;
uniform float uDaylight;
uniform vec3 uSunDir;
uniform sampler2DShadow uShadowMap;
uniform float uShadowStrength;

out vec4 fragColor;

float sunlight() {
    if (uShadowStrength <= 0.0) return 1.0;
    vec3 c = vShadow;
    if (c.x < 0.0 || c.x > 1.0 || c.y < 0.0 || c.y > 1.0 || c.z > 1.0) return 1.0;
    float t = 1.0 / 2048.0, sum = 0.0;
    sum += texture(uShadowMap, vec3(c.xy + vec2(-t, -t), c.z));
    sum += texture(uShadowMap, vec3(c.xy + vec2(t, -t), c.z));
    sum += texture(uShadowMap, vec3(c.xy + vec2(-t, t), c.z));
    sum += texture(uShadowMap, vec3(c.xy + vec2(t, t), c.z));
    return sum * 0.25;
}

void main() {
#ifdef SHADOW
    fragColor = vec4(1.0);
    return;
#else
    vec3 n = normalize(vNormal);
    // Same light as the blocks: sky light (stronger where the sun reaches), block light, and a
    // face shade by orientation (tops brightest, undersides darkest).
    float direct = max(dot(n, uSunDir), 0.0) * sunlight();
    float sky = vLight.x * uDaylight * mix(1.0, 0.68 + 0.42 * direct, uShadowStrength);
    float shade = 0.78 + 0.22 * n.y - 0.1 * abs(n.x);
    float lit = max(sky, vLight.y) * shade;
    vec3 rgb = mix(vColor * lit, vColor * (0.72 + 0.4 * shade), vGlow); // self-lit, still modelled
    float fog = clamp((vDist - uFogEnd * 0.6) / (uFogEnd * 0.4), 0.0, 1.0);
    fragColor = vec4(mix(rgb, uFogColor, fog), 1.0);
#endif
}
