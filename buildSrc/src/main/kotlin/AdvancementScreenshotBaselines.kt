import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import javax.imageio.ImageIO

/** Compares captured stages, or explicitly approves their original, full-size PNG files. */
object AdvancementScreenshotBaselines {
    data class Result(val status: String, val errors: List<String>) {
        val success: Boolean get() = errors.isEmpty() && status in listOf("passed", "updated")
    }

    fun verify(
        actualDirectory: Path,
        baselineDirectory: Path,
        comparisonDirectory: Path,
        update: Boolean,
    ): Result {
        val actual = canonicalDirectory(actualDirectory)
        val baseline = canonicalDirectory(baselineDirectory)
        val comparison = canonicalDirectory(comparisonDirectory)
        requireSeparateDirectories(actual, baseline, comparison)

        val errors = mutableListOf<String>()
        val reports = STAGES.map { StageReport(it.first, it.second) }
        val captures = linkedMapOf<StageReport, Screenshot>()
        try {
            Files.createDirectories(comparison)
            clearDiagnostics(comparison)
            // Snapshot every actual PNG before any baseline write. Updating must not approve
            // earlier stages when a later file is missing, truncated, or semantically wrong.
            reports.forEach { stage ->
                try {
                    val capture = readPng(actual.resolve("${stage.name}.png"))
                    try {
                        AdvancementScreenshotValidator.validate(capture.image, stage.progress)
                        captures[stage] = capture
                        stage.status = "validated"
                    } catch (exception: Exception) {
                        capture.close()
                        throw exception
                    }
                } catch (exception: Exception) {
                    fail(stage, "invalid-actual", "Invalid actual screenshot: ${exception.message}", errors)
                }
            }

            if (update) {
                if (captures.size == STAGES.size) {
                    updateBaselines(captures, baseline, errors)
                } else {
                    captures.keys.forEach { it.status = "update-blocked" }
                }
            } else {
                captures.forEach { (stage, capture) -> compare(stage, capture, baseline, comparison, errors) }
            }
        } catch (exception: Exception) {
            errors += "Screenshot baseline verification failed: ${exception.message ?: exception.javaClass.name}"
        } finally {
            captures.values.forEach(Screenshot::close)
        }

        val status = if (errors.isNotEmpty()) "failed" else if (update) "updated" else "passed"
        val report = Properties().apply {
            setProperty("status", status)
            setProperty("update", update.toString())
            setProperty("stages", reports.joinToString(",") { it.name })
            setProperty("errorCount", errors.size.toString())
            errors.forEachIndexed { index, error -> setProperty("error.$index", error) }
            reports.forEach { stage ->
                val prefix = "stage.${stage.name}."
                setProperty(prefix + "status", stage.status)
                setProperty(prefix + "progress", "${stage.progress}/10")
                setProperty(prefix + "differingPixelCount", stage.differingPixels.toString())
                setProperty(prefix + "comparedPixelCount", stage.comparedPixels.toString())
                if (stage.errors.isNotEmpty()) setProperty(prefix + "error", stage.errors.joinToString("\n"))
            }
        }
        try {
            Files.createDirectories(comparison)
            val bytes = ByteArrayOutputStream().use { output ->
                report.store(output, "ktAdvancements screenshot baseline comparison")
                output.toByteArray()
            }
            writeAtomically(comparison.resolve("report.properties"), bytes)
        } catch (exception: Exception) {
            errors += "Could not write comparison report: ${exception.message ?: exception.javaClass.name}"
        }
        return Result(if (errors.isNotEmpty()) "failed" else status, errors.toList())
    }

    private fun compare(
        stage: StageReport,
        capture: Screenshot,
        baseline: Path,
        comparison: Path,
        errors: MutableList<String>,
    ) {
        val expectedPath = baseline.resolve("${stage.name}.png")
        if (!Files.exists(expectedPath, LinkOption.NOFOLLOW_LINKS)) {
            fail(stage, "missing-baseline", "Missing baseline: $expectedPath; approve it explicitly with baseline update", errors)
            return
        }
        try {
            readPng(expectedPath).use { expected ->
                val result = AdvancementScreenshotComparison.compare(expected.image, capture.image)
                try {
                    stage.differingPixels = result.differingPixelCount
                    stage.comparedPixels = result.comparedPixelCount
                    if (result.matches) {
                        stage.status = "matched"
                    } else {
                        fail(
                            stage,
                            "different",
                            "${result.differingPixelCount}/${result.comparedPixelCount} compared pixels differ from $expectedPath",
                            errors,
                        )
                        writeAtomically(comparison.resolve("${stage.name}-expected.png"), expected.bytes)
                        writeAtomically(comparison.resolve("${stage.name}-actual.png"), capture.bytes)
                        val diff = ByteArrayOutputStream().use { output ->
                            check(ImageIO.write(result.diff, "png", output)) { "PNG encoder unavailable" }
                            output.toByteArray()
                        }
                        writeAtomically(comparison.resolve("${stage.name}-diff.png"), diff)
                    }
                } finally {
                    result.diff.flush()
                }
            }
        } catch (exception: Exception) {
            fail(
                stage,
                if (stage.status == "different") "comparison-error" else "invalid-baseline",
                "Could not compare baseline: ${exception.message ?: exception.javaClass.name}",
                errors,
            )
        }
    }

    private fun updateBaselines(
        captures: Map<StageReport, Screenshot>,
        baseline: Path,
        errors: MutableList<String>,
    ) {
        // Preflight every destination before creating or replacing any baseline PNG.
        captures.keys.forEach { stage ->
            val target = baseline.resolve("${stage.name}.png")
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                fail(stage, "update-error", "Baseline destination is not a regular file: $target", errors)
            }
        }
        if (errors.isNotEmpty()) {
            captures.keys.filter { it.status == "validated" }.forEach { it.status = "update-blocked" }
            return
        }

        val prepared = linkedMapOf<StageReport, Path>()
        try {
            Files.createDirectories(baseline)
            // Prepare all bytes first; retain captured PNG metadata and every pixel exactly.
            captures.forEach { (stage, capture) ->
                val temporary = Files.createTempFile(baseline, ".${stage.name}-", ".tmp")
                prepared[stage] = temporary
                Files.write(temporary, capture.bytes)
            }
            prepared.forEach { (stage, temporary) ->
                Files.move(
                    temporary,
                    baseline.resolve("${stage.name}.png"),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                stage.status = "updated"
            }
        } catch (exception: Exception) {
            captures.keys.filter { it.status != "updated" }.forEach { stage ->
                fail(stage, "update-error", "Could not update baseline: ${exception.message ?: exception.javaClass.name}", errors)
            }
        } finally {
            prepared.values.forEach { temporary ->
                try {
                    Files.deleteIfExists(temporary)
                } catch (exception: Exception) {
                    errors += "Could not remove baseline temporary file $temporary: ${exception.message}"
                }
            }
        }
    }

    private fun readPng(path: Path): Screenshot {
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Not a regular PNG file: $path" }
        val bytes = Files.readAllBytes(path)
        check(bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) {
            "Not a PNG file: $path"
        }
        val image = ByteArrayInputStream(bytes).use { ImageIO.read(it) } ?: error("Could not decode PNG: $path")
        if (image.width != 1280 || image.height != 720) {
            val dimensions = "${image.width}x${image.height}"
            image.flush()
            error("Expected a 1280x720 screenshot, got $dimensions: $path")
        }
        return Screenshot(bytes, image)
    }

    private fun writeAtomically(destination: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(destination.parent, ".comparison-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun clearDiagnostics(directory: Path) {
        val filenames = STAGES.flatMap { (stage, _) -> listOf("$stage-expected.png", "$stage-actual.png", "$stage-diff.png") } +
            "report.properties"
        filenames.forEach { name ->
            val path = directory.resolve(name)
            check(!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "Diagnostic file is a directory: $path" }
            Files.deleteIfExists(path)
        }
    }

    private fun canonicalDirectory(path: Path): Path {
        var existing = path.toAbsolutePath().normalize()
        val suffix = mutableListOf<Path>()
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            suffix.add(existing.fileName)
            existing = requireNotNull(existing.parent) { "Could not resolve directory: $path" }
        }
        return suffix.asReversed().fold(existing.toRealPath()) { parent, child -> parent.resolve(child) }
    }

    private fun requireSeparateDirectories(vararg directories: Path) {
        directories.forEachIndexed { index, first ->
            directories.drop(index + 1).forEach { second ->
                require(!first.startsWith(second) && !second.startsWith(first)) {
                    "Actual, baseline and comparison directories must not overlap: $first and $second"
                }
            }
        }
    }

    private fun fail(stage: StageReport, status: String, message: String, errors: MutableList<String>) {
        stage.status = status
        stage.errors += message
        errors += "${stage.name}: $message"
    }

    private class StageReport(val name: String, val progress: Int) {
        var status = "not-compared"
        var differingPixels = 0
        var comparedPixels = 0
        val errors = mutableListOf<String>()
    }

    private class Screenshot(val bytes: ByteArray, val image: BufferedImage) : AutoCloseable {
        override fun close() = image.flush()
    }

    private val STAGES = listOf("zero" to 0, "partial" to 3, "complete" to 10, "revoked" to 9)
    private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
}
