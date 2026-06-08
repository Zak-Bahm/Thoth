plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// litertlm-jvm 0.13.1 is compiled for Java 21, so the desktop target runs (and compiles) on a
// JDK 21 toolchain. Gradle provisions/locates it via java toolchains.
kotlin {
    jvmToolchain(21)
}

sourceSets {
    named("main") {
        // Compile the org.kiwix.libzim Java bindings straight from the submodule (libkiwix is
        // Android-only and unused — exclude it). The matching native libs are produced by
        // build-zim-native.sh into src/main/resources/native/linux-x86_64/.
        java.srcDir("$rootDir/third_party/java-libkiwix/lib/src/main/java")
        java.exclude("org/kiwix/libkiwix/**")
    }
}

dependencies {
    implementation(project(":core"))

    // In-process inference: the desktop (glibc + Vulkan) LiteRT-LM build. Same API as Android.
    implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.13.1")

    // :core declares these as implementation/compileOnly, so they are not on our compile
    // classpath transitively — declare what desktop code + :core's runtime need.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")
}

application {
    mainClass.set("com.bahm.thoth.desktop.MainKt")
}
