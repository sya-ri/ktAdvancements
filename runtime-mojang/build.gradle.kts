plugins {
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.s7a"
            artifactId = "ktAdvancements-runtime-mojang"
            version = rootProject.version.toString()
            pom {
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
            }
        }
    }
}
