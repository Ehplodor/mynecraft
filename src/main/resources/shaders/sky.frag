#version 330 core
in vec2 vUv;
in vec2 vHorizontal;
uniform sampler2D uTexture;
uniform vec4 uColor;
uniform float uFadeEnd; // > 0: fade out with horizontal distance (clouds)
out vec4 fragColor;
void main() {
    vec4 t = texture(uTexture, vUv) * uColor;
    if (uFadeEnd > 0.0) t.a *= 1.0 - smoothstep(uFadeEnd * 0.5, uFadeEnd, length(vHorizontal));
    if (t.a < 0.01) discard;
    fragColor = t;
}
