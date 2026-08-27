plugins {
    `maven-publish`
    signing
}

applyPublishingConfig(
    "ktAdvancements-runtime-mojang",
    withJavadoc = false,
    pom = {
        withXml {
            asNode().appendNode("dependencies").apply {
                rootProject.subprojects.forEach {
                    if (it.path.startsWith(":runtime:")) {
                        val versionParts = it.name.removePrefix("v").split('_').map(String::toInt)
                        val usesUnobfuscatedJar =
                            versionParts[0] > 26 ||
                                (versionParts[0] == 26 && versionParts[1] >= 1)
                        appendNode("dependency").apply {
                            appendNode("groupId", "dev.s7a")
                            appendNode("artifactId", "ktAdvancements-runtime-${it.name}")
                            appendNode("version", rootProject.version.toString())
                            if (!usesUnobfuscatedJar) {
                                appendNode("classifier", "mojang-mapped") // Use mojang mapped
                            }
                            appendNode("scope", "compile")
                        }
                    }
                }
            }
        }
    },
)
