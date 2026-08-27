import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Properties
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvancementScreenshotBaselinesTest {
    @field:TempDir
    lateinit var directory: Path

    private val actual: Path get() = directory.resolve("actual")
    private val baseline: Path get() = directory.resolve("baseline")
    private val comparison: Path get() = directory.resolve("comparison")

    @Test
    fun `matching captures pass without writing any baseline or diagnostic PNG`() {
        writeSet(actual)
        writeSet(baseline)
        val originals = baselineBytes()
        val modified = FileTime.fromMillis(1_600_000_000_000)
        STAGES.forEach { (stage, _) -> Files.setLastModifiedTime(baseline.resolve("$stage.png"), modified) }

        val result = verify()

        assertEquals("passed", result.status)
        assertTrue(result.success)
        assertTrue(result.errors.isEmpty())
        assertBaselinesUnchanged(originals)
        val report = report()
        assertEquals("passed", report.getProperty("status"))
        STAGES.forEach { (stage, _) ->
            assertEquals(modified, Files.getLastModifiedTime(baseline.resolve("$stage.png")))
            assertEquals("matched", report.getProperty("stage.$stage.status"))
            assertEquals("0", report.getProperty("stage.$stage.differingPixelCount"))
            assertTrue(report.getProperty("stage.$stage.comparedPixelCount").toInt() > 0)
            assertNoDiagnostics(stage)
        }
    }

    @Test
    fun `missing baselines fail all four stages without approving or fabricating images`() {
        writeSet(actual)

        val result = verify()

        assertEquals("failed", result.status)
        assertFalse(result.success)
        assertEquals(4, result.errors.size)
        assertFalse(Files.exists(baseline))
        val report = report()
        assertEquals("4", report.getProperty("errorCount"))
        STAGES.forEach { (stage, _) ->
            assertTrue(result.errors.any { it.startsWith("$stage: Missing baseline:") })
            assertEquals("missing-baseline", report.getProperty("stage.$stage.status"))
            assertEquals("0", report.getProperty("stage.$stage.comparedPixelCount"))
            assertTrue(Files.isRegularFile(actual.resolve("$stage.png")))
            assertNoDiagnostics(stage)
        }
    }

    @Test
    fun `all mismatches are reported with exact expected and actual PNGs and image diffs`() {
        writeSet(actual, changed = true)
        writeSet(baseline)
        val originals = baselineBytes()

        val result = verify()

        assertEquals("failed", result.status)
        assertEquals(4, result.errors.size)
        assertBaselinesUnchanged(originals)
        val report = report()
        STAGES.forEach { (stage, _) ->
            assertEquals("different", report.getProperty("stage.$stage.status"))
            assertEquals("1", report.getProperty("stage.$stage.differingPixelCount"))
            assertContentEquals(originals.getValue(stage), Files.readAllBytes(comparison.resolve("$stage-expected.png")))
            assertContentEquals(
                Files.readAllBytes(actual.resolve("$stage.png")),
                Files.readAllBytes(comparison.resolve("$stage-actual.png")),
            )
            val diff = ImageIO.read(comparison.resolve("$stage-diff.png").toFile())
            assertEquals(1280, diff.width)
            assertEquals(720, diff.height)
            diff.flush()
        }
    }

    @Test
    fun `explicit update retains original full PNG bytes including metadata`() {
        writeSet(actual, changed = true)

        val result = verify(update = true)

        assertEquals("updated", result.status)
        assertTrue(result.success)
        val report = report()
        assertEquals("updated", report.getProperty("status"))
        assertEquals("true", report.getProperty("update"))
        STAGES.forEach { (stage, _) ->
            val capturedBytes = Files.readAllBytes(actual.resolve("$stage.png"))
            assertTrue(String(capturedBytes, Charsets.ISO_8859_1).contains("Original full-size fixture"))
            assertContentEquals(capturedBytes, Files.readAllBytes(baseline.resolve("$stage.png")))
            val saved = ImageIO.read(baseline.resolve("$stage.png").toFile())
            assertEquals(1280, saved.width)
            assertEquals(720, saved.height)
            saved.flush()
            assertEquals("updated", report.getProperty("stage.$stage.status"))
            assertNoDiagnostics(stage)
        }
        assertEquals(STAGES.map { "${it.first}.png" }.toSet(), filenames(baseline))
        assertTrue(verify().success)
    }

    @Test
    fun `an invalid final stage prevents every baseline update`() {
        writeSet(actual, changed = true)
        writeSet(baseline)
        writePng(actual.resolve("revoked.png"), fixture(3))
        val originals = baselineBytes()

        val result = verify(update = true)

        assertEquals("failed", result.status)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().startsWith("revoked: Invalid actual screenshot:"))
        assertBaselinesUnchanged(originals)
        assertEquals(STAGES.map { "${it.first}.png" }.toSet(), filenames(baseline))
        val report = report()
        listOf("zero", "partial", "complete").forEach { stage ->
            assertEquals("update-blocked", report.getProperty("stage.$stage.status"))
        }
        assertEquals("invalid-actual", report.getProperty("stage.revoked.status"))
    }

    @Test
    fun `invalid actual files are all diagnosed and cannot create baselines`() {
        Files.createDirectories(actual)
        writePng(actual.resolve("partial.png"), BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB))
        Files.write(actual.resolve("complete.png"), byteArrayOf(1, 2, 3))
        writePng(actual.resolve("revoked.png"), BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB))

        val result = verify(update = true)

        assertEquals(4, result.errors.size)
        assertFalse(Files.exists(baseline))
        val report = report()
        STAGES.forEach { (stage, _) ->
            assertEquals("invalid-actual", report.getProperty("stage.$stage.status"))
            assertTrue(result.errors.any { it.startsWith("$stage: Invalid actual screenshot:") })
        }
    }

    @Test
    fun `an invalid baseline does not prevent comparison of remaining stages`() {
        writeSet(actual)
        Files.createDirectories(baseline)
        Files.write(baseline.resolve("zero.png"), byteArrayOf(1, 2, 3))
        writePng(baseline.resolve("complete.png"), BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB))
        writePng(baseline.resolve("revoked.png"), fixture(9, changed = true))

        val result = verify()

        assertEquals("failed", result.status)
        assertEquals(4, result.errors.size)
        val report = report()
        assertEquals("invalid-baseline", report.getProperty("stage.zero.status"))
        assertEquals("missing-baseline", report.getProperty("stage.partial.status"))
        assertEquals("invalid-baseline", report.getProperty("stage.complete.status"))
        assertEquals("different", report.getProperty("stage.revoked.status"))
        listOf("zero", "partial", "complete").forEach(::assertNoDiagnostics)
        assertTrue(Files.isRegularFile(comparison.resolve("revoked-diff.png")))
    }

    @Test
    fun `non-file baseline destinations are checked before replacing any PNG`() {
        writeSet(actual, changed = true)
        writeSet(baseline)
        val originals = baselineBytes().filterKeys { it != "partial" }
        Files.delete(baseline.resolve("partial.png"))
        Files.createDirectory(baseline.resolve("partial.png"))

        val result = verify(update = true)

        assertEquals("failed", result.status)
        assertEquals(1, result.errors.size)
        assertBaselinesUnchanged(originals)
        assertTrue(Files.isDirectory(baseline.resolve("partial.png")))
        val report = report()
        assertEquals("update-error", report.getProperty("stage.partial.status"))
        listOf("zero", "complete", "revoked").forEach { stage ->
            assertEquals("update-blocked", report.getProperty("stage.$stage.status"))
        }
    }

    @Test
    fun `only the thirteen owned diagnostic filenames are cleared`() {
        writeSet(actual)
        writeSet(baseline)
        Files.createDirectories(comparison.resolve("nested"))
        STAGES.forEach { (stage, _) ->
            listOf("expected", "actual", "diff").forEach { suffix ->
                Files.writeString(comparison.resolve("$stage-$suffix.png"), "stale")
            }
        }
        Files.writeString(comparison.resolve("report.properties"), "stale")
        listOf("keep.txt", "unrelated.png", "nested/keep.txt").forEach { name ->
            Files.writeString(comparison.resolve(name), "preserve")
        }

        assertTrue(verify().success)

        STAGES.forEach { (stage, _) -> assertNoDiagnostics(stage) }
        assertEquals("passed", report().getProperty("status"))
        listOf("keep.txt", "unrelated.png", "nested/keep.txt").forEach { name ->
            assertEquals("preserve", Files.readString(comparison.resolve(name)))
        }
    }

    @Test
    fun `overlapping directories are rejected before any cleanup or baseline write`() {
        writeSet(actual)
        writeSet(baseline)
        val originals = baselineBytes()
        Files.writeString(baseline.resolve("report.properties"), "preserve")

        for (target in listOf(baseline, baseline.resolve("nested"), actual, directory)) {
            assertFailsWith<IllegalArgumentException> {
                AdvancementScreenshotBaselines.verify(actual, baseline, target, update = false)
            }
        }

        assertBaselinesUnchanged(originals)
        assertEquals("preserve", Files.readString(baseline.resolve("report.properties")))
    }

    private fun verify(update: Boolean = false) = AdvancementScreenshotBaselines.verify(actual, baseline, comparison, update)

    private fun report(): Properties = Properties().apply {
        Files.newInputStream(comparison.resolve("report.properties")).use(::load)
    }

    private fun baselineBytes() = STAGES.associate { (stage, _) -> stage to Files.readAllBytes(baseline.resolve("$stage.png")) }

    private fun assertBaselinesUnchanged(originals: Map<String, ByteArray>) {
        originals.forEach { (stage, bytes) -> assertContentEquals(bytes, Files.readAllBytes(baseline.resolve("$stage.png"))) }
    }

    private fun filenames(path: Path): Set<String> = Files.list(path).use { files -> files.map { it.fileName.toString() }.toList().toSet() }

    private fun assertNoDiagnostics(stage: String) {
        listOf("expected", "actual", "diff").forEach { suffix ->
            assertFalse(Files.exists(comparison.resolve("$stage-$suffix.png")))
        }
    }

    private fun writeSet(path: Path, changed: Boolean = false) {
        Files.createDirectories(path)
        STAGES.forEach { (stage, progress) -> writePng(path.resolve("$stage.png"), fixture(progress, changed)) }
    }

    private fun writePng(path: Path, image: BufferedImage) {
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        try {
            val parameters = writer.defaultWriteParam
            val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), parameters)
            val root = IIOMetadataNode("javax_imageio_png_1.0")
            val text = IIOMetadataNode("tEXt")
            val entry = IIOMetadataNode("tEXtEntry")
            entry.setAttribute("keyword", "Fixture")
            entry.setAttribute("value", "Original full-size fixture")
            text.appendChild(entry)
            root.appendChild(text)
            metadata.mergeTree("javax_imageio_png_1.0", root)
            ImageIO.createImageOutputStream(path.toFile()).use { output ->
                writer.output = output
                writer.write(null, IIOImage(image, null, metadata), parameters)
            }
        } finally {
            writer.dispose()
            image.flush()
        }
    }

    // Synthetic pixels for the validator contract, never committed as screenshot baselines.
    private fun fixture(progress: Int, changed: Boolean = false): BufferedImage {
        val image = BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB)
        paint(image, 388, 220, 504, 280, 0xC6C6C6)
        paint(image, 390, 224, 500, 2, 0xFFFFFF)
        paint(image, 390, 224, 2, 272, 0xFFFFFF)
        paint(image, 390, 496, 500, 2, 0x555555)
        paint(image, 406, 256, 468, 226, 0x808080)
        paint(image, 582, 346, 36, 2, 0x9A720D)
        paint(image, 582, 348, 36, 2, 0x77580B)
        paint(image, 666, 346, 36, 2, if (progress == 10) 0xDBA213 else 0xFFFFFF)
        paint(image, 666, 348, 36, 2, if (progress == 10) 0xAA7E0F else 0xC6C6C6)
        val transition = 654 + 2 * (155 * progress / 10)
        for (x in 714 until 960) paint(image, x, 352, 1, 8, if (x < transition) 0xB98F2C else 0x036A96)
        paint(image, 960, 352, 2, 8, if (progress == 10) 0x493606 else 0x012E40)
        paint(image, 962, 352, 2, 8, 0)
        paint(image, 654, 394, 310, 24, 0x212121)
        drawText(image, "Progress", 718)
        drawText(image, "$progress/10".padStart(5), 894)
        if (changed) image.setRGB(440, 320, Color.RED.rgb)
        return image
    }

    private fun drawText(image: BufferedImage, text: String, left: Int) {
        paint(image, left, 360, 12 * text.length, 16, 0x036A96)
        text.forEachIndexed { index, character ->
            requireNotNull(FONT[character]).forEachIndexed { row, mask ->
                for (column in 0 until 6) {
                    if (mask and (1 shl column) != 0) paint(image, left + 12 * index + 2 * column, 360 + 2 * row, 2, 2, 0xFFFFFF)
                }
            }
        }
    }

    private fun paint(image: BufferedImage, x: Int, y: Int, width: Int, height: Int, rgb: Int) {
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(rgb)
            graphics.fillRect(x, y, width, height)
        } finally {
            graphics.dispose()
        }
    }

    private companion object {
        val STAGES = listOf("zero" to 0, "partial" to 3, "complete" to 10, "revoked" to 9)
        val FONT = mapOf(
            ' ' to intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
            '0' to intArrayOf(14, 17, 25, 21, 19, 17, 14, 0),
            '1' to intArrayOf(4, 6, 4, 4, 4, 4, 31, 0),
            '3' to intArrayOf(14, 17, 16, 12, 16, 17, 14, 0),
            '9' to intArrayOf(14, 17, 17, 30, 16, 8, 6, 0),
            '/' to intArrayOf(16, 8, 8, 4, 2, 2, 1, 0),
            'P' to intArrayOf(15, 17, 15, 1, 1, 1, 1, 0),
            'r' to intArrayOf(0, 0, 13, 19, 1, 1, 1, 0),
            'o' to intArrayOf(0, 0, 14, 17, 17, 17, 14, 0),
            'g' to intArrayOf(0, 0, 30, 17, 17, 30, 16, 15),
            'e' to intArrayOf(0, 0, 14, 17, 31, 1, 30, 0),
            's' to intArrayOf(0, 0, 30, 1, 14, 16, 15, 0),
        )
    }
}
