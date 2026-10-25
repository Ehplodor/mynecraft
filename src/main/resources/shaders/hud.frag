#version 330 core
in vec3 vUv;
in vec4 vColor;
uniform sampler2DArray uTextures;
out vec4 fragColor;
void main() {
    vec4 c = vColor;
    if (vUv.z >= 0.0) {
        vec4 t = texture(uTextures, vUv);
        if (t.a < 0.1) discard;
        c *= t;
    }
    fragColor = c;
}
