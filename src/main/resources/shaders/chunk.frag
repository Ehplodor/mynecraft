#version 330 core
in vec2 vUv;
flat in float vLayer;
in vec3 vLight;
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
    float fog = clamp((vDist - uFogEnd * 0.6) / (uFogEnd * 0.4), 0.0, 1.0);
#ifdef TRANSLUCENT
    fragColor = vec4(mix(tex.rgb * vLight, uFogColor, fog), tex.a);
#else
    fragColor = vec4(mix(tex.rgb * vLight, uFogColor, fog), 1.0);
#endif
}
