import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvancementScreenshotComparisonTest {
    @Test
    fun `accepts identical narrow and wide tooltip images with exact union pixel counts`() {
        listOf(900 to 129928, 964 to 134408).forEach { (right, count) ->
            val image = fixture(right)
            val result = AdvancementScreenshotComparison.compare(image, copy(image))
            assertTrue(result.matches)
            assertEquals(0, result.differingPixelCount)
            assertEquals(count, result.comparedPixelCount)
            assertEquals(1280, result.diff.width)
            assertEquals(720, result.diff.height)
        }
    }

    @Test
    fun `detects the baseline width for empty partial complete and revoked bars`() {
        listOf(900, 964).forEach { right ->
            listOf(0, 3, 10, 9).forEach { progress ->
                val expected = fixture(right)
                val transition = 654 + 2 * ((right - 654) / 2 * progress / 10)
                if (transition > 714) {
                    paint(expected, 714, 352, minOf(transition, right - 4) - 714, 8, Color(0xB98F2C))
                }
                if (progress == 10) paint(expected, right - 4, 352, 2, 8, Color(0x493606))
                assertTrue(AdvancementScreenshotComparison.compare(expected, copy(expected)).matches)
            }
        }
    }

    @Test
    fun `ignores world chat and outer window corners`() {
        val expected = fixture()
        val actual = copy(expected)
        listOf(100 to 100, 100 to 650, 389 to 221, 889 to 497, 640 to 700).forEach { (x, y) ->
            actual.setRGB(x, y, Color.MAGENTA.rgb)
        }
        assertTrue(AdvancementScreenshotComparison.compare(expected, actual).matches)
    }

    @Test
    fun `detects a single changed pixel in every kind of visible content`() {
        val expected = fixture()
        // Window title, tree background, root icon, Progress icon, title, fraction, description,
        // and the tooltip extension beyond the opaque window must all participate in comparison.
        listOf(
            420 to 240, 450 to 310, 592 to 368, 680 to 368,
            728 to 366, 854 to 366, 746 to 400, 950 to 400,
        ).forEach { (x, y) ->
            val actual = copy(expected)
            actual.setRGB(x, y, Color.MAGENTA.rgb)
            val result = AdvancementScreenshotComparison.compare(expected, actual)
            assertFalse(result.matches, "Must compare content at $x,$y")
            assertEquals(1, result.differingPixelCount, "Count pixel at $x,$y exactly once")
        }
    }

    @Test
    fun `counts multiple changed pixels exactly and does not double count overlapping regions`() {
        val expected = fixture()
        val actual = copy(expected)
        listOf(400 to 230, 654 to 348, 750 to 390, 950 to 400).forEach { (x, y) ->
            actual.setRGB(x, y, Color.MAGENTA.rgb)
        }
        val result = AdvancementScreenshotComparison.compare(expected, actual)
        assertFalse(result.matches)
        assertEquals(4, result.differingPixelCount)
        assertEquals(134408, result.comparedPixelCount)
    }

    @Test
    fun `ignores precisely the transparent tooltip corner pixels for both widths`() {
        listOf(900, 964).forEach { right ->
            val expected = fixture(right)
            val actual = copy(expected)
            listOf(348, 349, 350, 351, 414, 415, 416, 417).forEach { y ->
                val cornerWidth = if (y < 350 || y >= 416) 4 else 2
                for (x in right - cornerWidth until right) actual.setRGB(x, y, Color.MAGENTA.rgb)
            }
            for (y in 348 until 418) actual.setRGB(right, y, Color.MAGENTA.rgb)
            assertTrue(AdvancementScreenshotComparison.compare(expected, actual).matches)
        }
    }

    @Test
    fun `compares the opaque pixels immediately adjacent to each tooltip corner`() {
        listOf(900, 964).forEach { right ->
            val expected = fixture(right)
            val actual = copy(expected)
            listOf(348, 349, 350, 351, 352, 353, 412, 413, 414, 415, 416, 417).forEach { y ->
                val cornerWidth = when (y) {
                    348, 349, 416, 417 -> 4
                    350, 351, 414, 415 -> 2
                    else -> 0
                }
                actual.setRGB(right - cornerWidth - 1, y, Color.MAGENTA.rgb)
            }
            val result = AdvancementScreenshotComparison.compare(expected, actual)
            assertFalse(result.matches)
            assertEquals(12, result.differingPixelCount)
        }
    }

    @Test
    fun `includes every edge of the window interior rectangle`() {
        val expected = fixture()
        val actual = copy(expected)
        listOf(396 to 228, 883 to 228, 396 to 491, 883 to 491).forEach { (x, y) ->
            actual.setRGB(x, y, Color.MAGENTA.rgb)
        }
        assertEquals(4, AdvancementScreenshotComparison.compare(expected, actual).differingPixelCount)
    }

    @Test
    fun `accepts eight RGB levels of rounding but rejects nine in any channel`() {
        val expected = fixture()
        expected.setRGB(500, 400, Color(120, 120, 120).rgb)
        listOf(Color(128, 112, 128), Color(112, 128, 112)).forEach { color ->
            val actual = copy(expected)
            actual.setRGB(500, 400, color.rgb)
            assertTrue(AdvancementScreenshotComparison.compare(expected, actual).matches)
        }
        listOf(Color(129, 120, 120), Color(120, 111, 120), Color(120, 120, 129)).forEach { color ->
            val actual = copy(expected)
            actual.setRGB(500, 400, color.rgb)
            assertEquals(1, AdvancementScreenshotComparison.compare(expected, actual).differingPixelCount)
        }
    }

    @Test
    fun `detects even a one-level alpha change`() {
        val expected = fixture()
        val actual = copy(expected)
        actual.setRGB(500, 400, (actual.getRGB(500, 400) and 0xFFFFFF) or 0xFE000000.toInt())
        val result = AdvancementScreenshotComparison.compare(expected, actual)
        assertFalse(result.matches)
        assertEquals(1, result.differingPixelCount)
    }

    @Test
    fun `uses the baseline width even when the actual tooltip disappears or changes width`() {
        val expected = fixture(964)
        val actual = fixture(900)
        val result = AdvancementScreenshotComparison.compare(expected, actual)
        assertFalse(result.matches)
        assertEquals(134408, result.comparedPixelCount)

        val missingTooltip = copy(expected)
        paint(missingTooltip, 654, 348, 310, 70, Color.GRAY)
        assertFalse(AdvancementScreenshotComparison.compare(expected, missingTooltip).matches)
    }

    @Test
    fun `rejects a missing or unrecognized baseline tooltip instead of masking the wrong area`() {
        val missing = BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB)
        assertFailsWith<IllegalArgumentException> { AdvancementScreenshotComparison.compare(missing, fixture()) }
        val wrongWidth = fixture(932)
        assertFailsWith<IllegalArgumentException> { AdvancementScreenshotComparison.compare(wrongWidth, fixture()) }
        val noRightBorder = fixture()
        paint(noRightBorder, 714, 354, 286, 1, Color(0x036A96))
        assertFailsWith<IllegalArgumentException> { AdvancementScreenshotComparison.compare(noRightBorder, fixture()) }
    }

    @Test
    fun `requires a real baseline border on all three sampled rows`() {
        listOf(354, 356, 358).forEach { y ->
            listOf(720, 960, 962).forEach { x ->
                val expected = fixture()
                expected.setRGB(x, y, Color.MAGENTA.rgb)
                assertFailsWith<IllegalArgumentException>("Expected invalid baseline at $x,$y") {
                    AdvancementScreenshotComparison.compare(expected, fixture())
                }
            }
        }
    }

    @Test
    fun `rejects wrong baseline and actual dimensions without scaling or cropping`() {
        listOf(1279 to 720, 1280 to 719, 1281 to 720, 1280 to 721).forEach { (width, height) ->
            val wrongSize = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            assertFailsWith<IllegalArgumentException> { AdvancementScreenshotComparison.compare(wrongSize, fixture()) }
            assertFailsWith<IllegalArgumentException> { AdvancementScreenshotComparison.compare(fixture(), wrongSize) }
        }
    }

    @Test
    fun `diff highlights only changes and grays out unchanged and ignored pixels`() {
        val expected = fixture()
        val actual = copy(expected)
        actual.setRGB(500, 400, Color.RED.rgb)
        actual.setRGB(100, 100, Color.RED.rgb)
        val result = AdvancementScreenshotComparison.compare(expected, actual)
        assertEquals(Color.MAGENTA.rgb, result.diff.getRGB(500, 400))
        listOf(501 to 400, 100 to 100).forEach { (x, y) ->
            val color = Color(result.diff.getRGB(x, y))
            assertEquals(color.red, color.green)
            assertEquals(color.green, color.blue)
            assertEquals(255, color.alpha)
        }
        assertTrue(Color(result.diff.getRGB(501, 400)).red >= 64)
        assertTrue(Color(result.diff.getRGB(100, 100)).red < 64)
        assertEquals(1, result.differingPixelCount)
    }

    @Test
    fun `comparison never edits either input image`() {
        val expected = fixture()
        val actual = fixture()
        actual.setRGB(500, 400, Color.MAGENTA.rgb)
        val expectedBefore = expected.getRGB(0, 0, 1280, 720, null, 0, 1280)
        val actualBefore = actual.getRGB(0, 0, 1280, 720, null, 0, 1280)
        AdvancementScreenshotComparison.compare(expected, actual)
        assertTrue(expectedBefore.contentEquals(expected.getRGB(0, 0, 1280, 720, null, 0, 1280)))
        assertTrue(actualBefore.contentEquals(actual.getRGB(0, 0, 1280, 720, null, 0, 1280)))
    }

    /** Synthetic colored rectangles only; actual vanilla golden PNGs belong to game-test. */
    private fun fixture(right: Int = 964): BufferedImage {
        val image = BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB)
        paint(image, 0, 0, 1280, 720, Color(0x345678))
        paint(image, 388, 220, 504, 280, Color(0x888888))
        paint(image, 654, 348, right - 654, 70, Color(0x212121))
        paint(image, 714, 352, right - 4 - 714, 8, Color(0x036A96))
        paint(image, right - 4, 352, 2, 8, Color(0x012E40))
        paint(image, right - 2, 352, 2, 8, Color.BLACK)
        return image
    }

    private fun copy(image: BufferedImage): BufferedImage =
        BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width), 0, image.width)
        }

    private fun paint(image: BufferedImage, x: Int, y: Int, width: Int, height: Int, color: Color) {
        image.createGraphics().apply {
            this.color = color
            fillRect(x, y, width, height)
            dispose()
        }
    }
}
