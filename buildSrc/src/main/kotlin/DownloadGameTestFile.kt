import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@DisableCachingByDefault(because = "Downloads a remote game-test dependency")
abstract class DownloadGameTestFile : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    @get:Optional
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val destination: RegularFileProperty

    @TaskAction
    fun download() {
        val target = destination.get().asFile.toPath()
        val temporary = target.resolveSibling("${target.fileName}.part")
        Files.createDirectories(target.parent)
        val connection = URI(url.get()).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "ktAdvancements game tests (https://github.com/sya-ri/ktAdvancements)")
        }
        logger.lifecycle("Downloading {}", url.get())
        try {
            connection.getInputStream().use { input ->
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
            }
            sha256.orNull?.let { expected ->
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(temporary).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(expected, ignoreCase = true)) {
                    "SHA-256 mismatch for ${url.get()}: expected $expected, got $actual"
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
