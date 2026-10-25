#version 330 core
in vec3 vColor;
in float vDist;

uniform vec3 uFogColor;
uniform float uFogEnd;

out vec4 fragColor;

void main() {
    float fog = clamp((vDist - uFogEnd * 0.6) / (uFogEnd * 0.4), 0.0, 1.0);
    fragColor = vec4(mix(vColor, uFogColor, fog), 1.0);
}
