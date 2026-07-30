plugins {
    `java-library`
    id("connect.build-logic")
    id("io.freefair.lombok") version "8.6" apply false
    id("org.jetbrains.kotlin.jvm") apply false
}

allprojects {
    group = "com.minekube.connect"
    version = gitVersion()
    description =
        "Connects the server/proxy to the global Connect network to reach more players while also supporting online mode server, bungee or velocity mode. Visit https://minekube.com/connect"
}

val deployProjectPaths = setOf(
    ":api",
    ":core",
    ":bungee",
    ":spigot",
    ":velocity",
)

val shareProjectPaths = setOf(
    ":share",
    ":share:common",
    ":share:fabric-common",
    ":share:fabric-1-21-11",
    ":share:fabric-26-2",
)

//todo re-add checkstyle when we switch back to 2 space indention
// and take a look again at spotbugs someday

subprojects {
    if (path !in shareProjectPaths) {
        apply {
            plugin("java-library")
            plugin("io.freefair.lombok")
            plugin("connect.build-logic")
        }

        when (path) {
            in deployProjectPaths -> plugins.apply("connect.shadow-conventions")
            else -> plugins.apply("connect.base-conventions")
        }
    }
}
