plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // DI annotations only (@Inject/@Singleton). Hilt's processor lives in :app; on desktop we
    // wire manually. Keeping the annotations lets the Android app's Hilt graph inject :core types.
    implementation("javax.inject:javax.inject:1")

    // Coroutines (Flow + Dispatchers) used by the pipeline.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // HTML parsing for the chunker / lookupArticle.
    implementation("org.jsoup:jsoup:1.17.2")

    // LiteRT-LM engine API. compileOnly so the (large, platform-specific) native artifact is NOT
    // bundled here: :app supplies litertlm-android, :desktop supplies litertlm-jvm at runtime.
    compileOnly("com.google.ai.edge.litertlm:litertlm-jvm:0.13.1")

    // org.json is built into the Android platform; on desktop :desktop supplies it. compileOnly
    // here avoids a duplicate-class clash with Android's bundled org.json in :app.
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
