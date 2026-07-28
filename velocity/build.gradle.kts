var velocityVersion = "3.2.0-SNAPSHOT"
var log4jVersion = "2.11.2"
var gsonVersion = "2.8.8"
var guavaVersion = "25.1-jre"

java {
    // For Velocity API
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    api(projects.core)
    implementation("cloud.commandframework", "cloud-velocity", Versions.cloudVersion)

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.5")
    testImplementation("io.netty", "netty-transport", Versions.nettyVersion)
    testImplementation("org.mockito:mockito-core:4.11.0")
    testImplementation(testFixtures(projects.core))
    testRuntimeOnly("com.velocitypowered", "velocity-api", velocityVersion)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

relocate("cloud.commandframework")
// used in cloud
relocate("io.leangen.geantyref")

tasks.test {
    useJUnitPlatform()
}

// Connect's login re-assert has to run after every other plugin's pre-login handler, which is a
// property of Velocity's dispatcher, not of Connect. So it is asserted against the real
// VelocityEventManager instead of a re-implementation of its ordering rules.
//
// Own source set on purpose: the Velocity *proxy* jar is shaded and carries its own copy of
// velocity-api, which must not shadow the 3.2.0 API the plugin compiles against, nor the
// com.velocitypowered.proxy test stubs the other tests use. The proxy jar is pinned by sha256 -
// see the papermc-fill-velocity-proxy repository in settings.gradle.kts.
val eventOrderTest: SourceSet by sourceSets.creating

dependencies {
    "eventOrderTestImplementation"(sourceSets["main"].output)
    "eventOrderTestImplementation"(projects.core)
    "eventOrderTestImplementation"("com.velocitypowered.proxy-jar:velocity:3.4.0-566")
    "eventOrderTestImplementation"("org.junit.jupiter:junit-jupiter:5.10.5")
    "eventOrderTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val eventOrderTestTask = tasks.register<Test>("eventOrderTest") {
    description = "Asserts Connect's login re-assert ordering against a real Velocity proxy."
    group = "verification"
    testClassesDirs = eventOrderTest.output.classesDirs
    classpath = eventOrderTest.runtimeClasspath
    useJUnitPlatform()
}

tasks.check {
    dependsOn(eventOrderTestTask)
}


// these dependencies are already present on the platform
provided("com.google.code.gson", "gson", gsonVersion)
provided("com.google.guava", "guava", guavaVersion)
provided("com.google.inject", "guice", Versions.guiceVersion)
provided("org.yaml", "snakeyaml", Versions.snakeyamlVersion) // included in Configurate
provided("com.velocitypowered", "velocity-api", velocityVersion)
provided("org.apache.logging.log4j", "log4j-core", log4jVersion)
