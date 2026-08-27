plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ktAdvancements"

include(":api")
file("./runtime").list().forEach {
    if (it.matches("v\\d+_\\d+(_\\d+)?".toRegex())) {
        include(":runtime:$it")
    }
}
include(":runtime")
include(":runtime-mojang")
include(":store:mysql")
include(":store:sqlite")
include(":example")
include(":game-test")
