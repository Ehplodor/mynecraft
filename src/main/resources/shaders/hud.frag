#version 330 core
in vec3 vUv;
in vec4 vColor;
uniform sampler2DArray uTextures;
uniform sampler2D uFont;
uniform sampler2D uSprites;
out vec4 fragColor;
void main() {
    vec4 c = vColor;
    if (vUv.z >= 0.0) {          // block texture layer
        vec4 t = texture(uTextures, vUv);
        if (t.a < 0.1) discard;
        c *= t;
    } else if (vUv.z < -2.5) {   // HUD sprite
        vec4 t = texture(uSprites, vUv.xy);
        if (t.a < 0.1) discard;
        c *= t;
    } else if (vUv.z < -1.5) {   // font glyph
        vec4 t = texture(uFont, vUv.xy);
        if (t.a < 0.1) discard;
        c *= t;
    }
    fragColor = c;
}
