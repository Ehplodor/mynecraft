#version 330 core
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aColor;
layout(location = 2) in vec2 aFade; // clouds: opacity at the previous and current weather step
uniform float uBlend;
uniform mat4 uViewProj;
uniform vec3 uOffset; // anchor minus camera: vertices are stored relative to an anchor
out vec4 vColor;
out vec2 vHorizontal;
void main() {
    vec3 p = aPos + uOffset;
    vColor = vec4(aColor.rgb, aColor.a * mix(aFade.x, aFade.y, uBlend));
    vHorizontal = p.xz;
    gl_Position = uViewProj * vec4(p, 1.0);
}
