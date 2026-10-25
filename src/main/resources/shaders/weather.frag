#version 330 core
in vec4 vColor;
in vec2 vHorizontal;
uniform vec4 uTint;
uniform float uFadeEnd;
out vec4 fragColor;
void main() {
    vec4 c = vColor * uTint;
    c.a *= 1.0 - smoothstep(uFadeEnd * 0.6, uFadeEnd, length(vHorizontal));
    if (c.a < 0.01) discard;
    fragColor = c;
}
