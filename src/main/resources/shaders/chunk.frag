#version 330 core
in vec2 vUv;
flat in float vLayer;
flat in float vOverlay;
in vec3 vTint;
in float vLight;
in float vDist;

uniform sampler2DArray uTextures;
uniform vec3 uFogColor;
uniform float uFogEnd;

out vec4 fragColor;

void main() {
    vec4 tex = texture(uTextures, vec3(vUv, vLayer));
#ifdef CUTOUT
    if (tex.a < 0.5) discard;
#endif
    vec3 rgb;
    if (vOverlay > 0.5) {
        // Untinted base with a tinted overlay on top (grass block sides).
        vec4 over = texture(uTextures, vec3(vUv, vLayer + 1.0));
        rgb = mix(tex.rgb, over.rgb * vTint, over.a) * vLight;
    } else {
        rgb = tex.rgb * vTint * vLight;
    }
    float fog = clamp((vDist - uFogEnd * 0.6) / (uFogEnd * 0.4), 0.0, 1.0);
#ifdef TRANSLUCENT
    fragColor = vec4(mix(rgb, uFogColor, fog), tex.a);
#else
    fragColor = vec4(mix(rgb, uFogColor, fog), 1.0);
#endif
}
