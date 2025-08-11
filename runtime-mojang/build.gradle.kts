plugins {
    `maven-publish`
    signing
}

applyPublishingConfig(
    "ktAdvancements-runtime-mojang",
    pom = {
        withXml {
            asNode().appendNode("dependencies").apply {
                rootProject.subprojects.forEach {
                    if (it.path.startsWith(":runtime:")) {
                        appendNode("dependency").apply {
                            appendNode("groupId", "dev.s7a")
                            appendNode("artifactId", "ktAdvancements-runtime-${it.name}")
                            appendNode("version", rootProject.version.toString())
                            appendNode("classifier", "mojang-mapped") // Use mojang mapped
                            appendNode("scope", "compile")
                        }
                    }
                }
            }
        }
    },
)
