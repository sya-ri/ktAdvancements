plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "ktAdvancements"

include(":api")
file("./runtime").list().forEach {
    if (it.matches("v1_\\d+_\\d+".toRegex())) {
        include(":runtime:$it")
    }
}
include(":runtime")
include(":runtime-mojang")
