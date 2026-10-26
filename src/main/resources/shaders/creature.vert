#version 330 core
layout(location = 0) in vec3 aPos;    // camera-relative
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aColor;  // rgb, glow
layout(location = 3) in vec2 aLight;  // sky, block (already as brightness)

uniform mat4 uViewProj;
uniform mat4 uShadowVP;

out vec3 vColor;
out float vGlow;
out vec3 vNormal;
out vec2 vLight;
out vec3 vShadow;
out float vDist;

void main() {
    gl_Position = uViewProj * vec4(aPos, 1.0);
    vColor = aColor.rgb;
    vGlow = aColor.a;
    vNormal = aNormal;
    vLight = aLight;
#ifndef SHADOW
    vShadow = (uShadowVP * vec4(aPos + aNormal * 0.05, 1.0)).xyz * 0.5 + 0.5;
#endif
    vDist = length(aPos);
}
