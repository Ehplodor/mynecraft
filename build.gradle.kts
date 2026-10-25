plugins {
    application
}

val lwjglVersion = "3.3.4"
val jomlVersion = "1.10.8"

val lwjglNatives = Pair(
    System.getProperty("os.name")!!,
    System.getProperty("os.arch")!!
).let { (name, arch) ->
    when {
        name.startsWith("Linux") -> if (arch.startsWith("aarch64")) "natives-linux-arm64" else "natives-linux"
        name.startsWith("Mac") -> if (arch.startsWith("aarch64")) "natives-macos-arm64" else "natives-macos"
        name.startsWith("Windows") -> "natives-windows"
        else -> throw GradleException("Unsupported OS: $name")
    }
}

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    for (module in listOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl")) {
        implementation("org.lwjgl:$module")
        runtimeOnly("org.lwjgl:$module::$lwjglNatives")
    }
    implementation("org.joml:joml:$jomlVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("net.mynecraft.client.Main")
    applicationDefaultJvmArgs = buildList {
        add("-XX:+UseZGC")
        if (System.getProperty("os.name").startsWith("Mac")) add("-XstartOnFirstThread")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); showStandardStreams = true }
}
