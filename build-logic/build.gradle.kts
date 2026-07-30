import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

dependencies {
    implementation("net.kyori.indra.git:net.kyori.indra.git.gradle.plugin:4.0.0")
    implementation("com.jfrog.artifactory:com.jfrog.artifactory.gradle.plugin:6.0.4")
    implementation("com.gradleup.shadow:shadow-gradle-plugin:8.3.11")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
