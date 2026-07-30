plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.core)
    implementation(projects.share.common)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.coroutinesVersion}")
    implementation(platform("io.arrow-kt:arrow-stack:${Versions.arrowVersion}"))
    implementation("io.arrow-kt:arrow-core")
    implementation("io.arrow-kt:arrow-fx-coroutines")
    implementation("com.google.protobuf:protobuf-java:${Versions.protocVersion}")
    implementation("com.squareup.okhttp3:okhttp:4.9.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.coroutinesVersion}")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.9.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
