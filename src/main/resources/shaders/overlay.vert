#version 330 core
layout(location = 0) in vec3 aPos;
uniform mat4 uMvp;
uniform vec3 uOffset;
uniform vec3 uScale;
void main() {
    gl_Position = uMvp * vec4(aPos * uScale + uOffset, 1.0);
}
