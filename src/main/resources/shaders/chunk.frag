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
    vec3 color = texture(uTextures, vec3(vUv, vLayer)).rgb * vLight;
    float fog = clamp((vDist - uFogEnd * 0.6) / (uFogEnd * 0.4), 0.0, 1.0);
    fragColor = vec4(mix(color, uFogColor, fog), 1.0);
}
