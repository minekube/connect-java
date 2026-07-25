var bungeeProxyVersion = "1.21-R0.1-SNAPSHOT"
var bungeeApiVersion = "1.21-R0.1"
var gsonVersion = "2.8.0"
var guavaVersion = "21.0"

dependencies {
    api(projects.core)
    implementation("cloud.commandframework", "cloud-bungee", Versions.cloudVersion)

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.5")
    testImplementation("org.mockito:mockito-core:4.11.0")
    testImplementation(testFixtures(projects.core))
    // Bungee plugin classes (e.g. BungeeListener) are needed on the test classpath for the
    // per-platform startup test's reflective DI-graph walk.
    testImplementation("net.md-5", "bungeecord-api", bungeeApiVersion)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

relocate("com.google.inject")
relocate("com.google.protobuf")
relocate("net.kyori")
relocate("cloud.commandframework")
// used in cloud
relocate("io.leangen.geantyref")
// since 1.20
relocate("org.yaml")

// these dependencies are already present on the platform
provided("net.md-5", "bungeecord-proxy", bungeeProxyVersion, includeTransitiveDeps = false)
provided("net.md-5", "bungeecord-api", bungeeApiVersion)
provided("com.google.code.gson", "gson", gsonVersion)
provided("com.google.guava", "guava", guavaVersion)
