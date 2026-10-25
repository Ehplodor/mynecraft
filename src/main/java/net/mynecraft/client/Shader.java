package net.mynecraft.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL20.*;

final class Shader {
    final int program;

    Shader(String vertexResource, String fragmentResource) {
        this(vertexResource, fragmentResource, "");
    }

    /** @param defines lines inserted right after #version, e.g. "#define CUTOUT\n". */
    Shader(String vertexResource, String fragmentResource, String defines) {
        int vs = compile(GL_VERTEX_SHADER, inject(load(vertexResource), defines));
        int fs = compile(GL_FRAGMENT_SHADER, inject(load(fragmentResource), defines));
        program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Link failed: " + glGetProgramInfoLog(program));
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
    }

    int uniform(String name) {
        return glGetUniformLocation(program, name);
    }

    private static String inject(String src, String defines) {
        int eol = src.indexOf('\n');
        return src.substring(0, eol + 1) + defines + src.substring(eol + 1);
    }

    private static int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Compile failed: " + glGetShaderInfoLog(s));
        }
        return s;
    }

    private static String load(String resource) {
        try (InputStream in = Shader.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalArgumentException("Missing resource " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
