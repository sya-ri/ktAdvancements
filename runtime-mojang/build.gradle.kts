plugins {
    `maven-publish`
    signing
}

fun isPaperOnlyRuntime(versionName: String) = "26_1" <= versionName

applyPublishingConfig(
    "ktAdvancements-runtime-mojang",
    pom = {
        withXml {
            asNode().appendNode("dependencies").apply {
                rootProject.subprojects.forEach {
                    if (it.path.startsWith(":runtime:")) {
                        val versionName = it.name.drop(1)
                        appendNode("dependency").apply {
                            appendNode("groupId", "dev.s7a")
                            appendNode("artifactId", "ktAdvancements-runtime-${it.name}")
                            appendNode("version", rootProject.version.toString())
                            if (isPaperOnlyRuntime(versionName).not()) {
                                appendNode("classifier", "mojang-mapped")
                            }
                            appendNode("scope", "compile")
                        }
                    }
                }
            }
        }
    },
)
