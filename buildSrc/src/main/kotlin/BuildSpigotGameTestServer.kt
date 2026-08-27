import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.work.DisableCachingByDefault
import java.util.concurrent.TimeUnit

@DisableCachingByDefault(because = "Builds a private Spigot server using upstream BuildTools")
abstract class BuildSpigotGameTestServer : DefaultTask() {
    @get:Input
    abstract val revision: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val buildToolsJar: RegularFileProperty

    @get:Nested
    abstract val javaLauncher: Property<JavaLauncher>

    @get:LocalState
    abstract val workDirectory: DirectoryProperty

    @get:OutputFile
    abstract val serverJar: RegularFileProperty

    @TaskAction
    fun buildServer() {
        val work = workDirectory.get().asFile.apply { mkdirs() }
        val output = serverJar.get().asFile
        output.parentFile.mkdirs()
        val log = work.resolve("buildtools.log")
        logger.lifecycle("Building Spigot revision {} (log: {})", revision.get(), log)
        val process =
            ProcessBuilder(
                javaLauncher.get().executablePath.asFile.absolutePath,
                "-Xmx2G",
                "-jar",
                buildToolsJar.get().asFile.absolutePath,
                "--rev",
                revision.get(),
                "--output-dir",
                output.parentFile.absolutePath,
                "--final-name",
                output.name,
            ).directory(work)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
        try {
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                throw GradleException("Spigot BuildTools timed out; see $log")
            }
            if (process.exitValue() != 0 || !output.isFile) {
                throw GradleException("Spigot BuildTools failed with exit ${process.exitValue()}; see $log")
            }
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
    }
}
