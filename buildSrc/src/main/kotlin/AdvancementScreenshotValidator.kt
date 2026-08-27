import java.awt.image.BufferedImage
import kotlin.math.abs

/** Validates the vanilla, English, GUI-scale-2 fixture while its Progress node is hovered. */
object AdvancementScreenshotValidator {
    fun validate(image: BufferedImage, expectedProgress: Int) {
        require(expectedProgress in listOf(0, 3, 10, 9)) { "Unsupported visual-test progress: $expectedProgress" }
        check(image.width == 1280 && image.height == 720) {
            "Expected a 1280x720 screenshot, got ${image.width}x${image.height}"
        }
        FRAME_PIXELS.forEach { (x, y, color) ->
            requireColor(image, 388 + 2 * x, 220 + 2 * y, color, "advancement window frame")
        }
        BACKGROUND_PIXELS.forEach { (x, y) ->
            val color = image.getRGB(x, y)
            check(CHANNEL_SHIFTS.all { channel(color, it) > 64 }) {
                "Missing advancement background at $x,$y; the advancement tree may be empty"
            }
        }
        // The non-hovered root is dimmed by vanilla's fully settled 30% hover overlay.
        listOf(582, 598, 616).forEach { x ->
            requireColor(image, x, 346, 0x9A720D, "completed root frame")
            requireColor(image, x, 348, 0x77580B, "completed root frame")
        }
        val complete = expectedProgress == 10
        listOf(666, 680, 700).forEach { x ->
            requireColor(image, x, 346, if (complete) 0xDBA213 else 0xFFFFFF, "Progress frame")
            requireColor(image, x, 348, if (complete) 0xAA7E0F else 0xC6C6C6, "Progress frame")
        }

        val tooltipWidth = detectTooltipWidth(image)
        val right = BAR_LEFT + 2 * tooltipWidth
        val transition = BAR_LEFT + 2 * (tooltipWidth * expectedProgress / 10)
        BAR_ROWS.forEach { y ->
            // The node frame covers the left of the bar. The remaining strip is unobscured by text.
            for (x in BAR_VISIBLE_LEFT until right - 4) {
                val color = if (x < transition) GOLD else BLUE
                requireColor(image, x, y, color, "progress bar for $expectedProgress/10")
            }
            requireColor(image, right - 4, y, if (complete) 0x493606 else 0x012E40, "tooltip right border")
            requireColor(image, right - 2, y, 0x000000, "tooltip right border")
        }
        requireColor(image, right - 6, 410, 0x212121, "tooltip description panel")
        requireText(image, "Progress", 718, "tooltip title")
        // Inspect a complete five-character field, including blank leading space, so that
        // an unexpected extra digit (for example 10/10 instead of 0/10) cannot be ignored.
        val fraction = "$expectedProgress/10".padStart(5)
        requireText(image, fraction, right - 10 - 12 * fraction.length, "progress fraction")
    }

    private fun detectTooltipWidth(image: BufferedImage): Int {
        val interiorRight =
            (BAR_VISIBLE_LEFT until 1000).firstOrNull { x ->
                val color = image.getRGB(x, BAR_ROWS.first())
                !matches(color, GOLD) && !matches(color, BLUE)
            } ?: error("Could not find the Progress tooltip's right border")
        val width = interiorRight + 4 - BAR_LEFT
        // Vanilla introduced a minimum title width in 1.21.4. Detect the two actual
        // fixture widths instead of trusting the requested server/client version.
        check(width == 246 || width == 310) {
            "Missing Progress tooltip or unexpected tooltip width: $width pixels"
        }
        return width / 2
    }

    private fun requireText(image: BufferedImage, text: String, left: Int, description: String) {
        text.forEachIndexed { index, character ->
            val glyph = requireNotNull(GLYPHS[character])
            glyph.forEachIndexed { row, mask ->
                for (column in 0 until 6) {
                    val expectedWhite = mask and (1 shl column) != 0
                    // Validate every pixel of the nearest-neighbor 2x2 glyph cells,
                    // including unpainted cells; accepting only white pixels loses digits.
                    for (dy in 0..1) {
                        for (dx in 0..1) {
                            val x = left + 12 * index + 2 * column + dx
                            val y = 360 + 2 * row + dy
                            val actual = image.getRGB(x, y)
                            val actualWhite = channel(actual, 24) >= 240 && CHANNEL_SHIFTS.all { channel(actual, it) >= 240 }
                            check(actualWhite == expectedWhite) {
                                "Incorrect $description '$text' at $x,$y (character '$character')"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requireColor(image: BufferedImage, x: Int, y: Int, expected: Int, description: String) {
        val actual = image.getRGB(x, y)
        check(matches(actual, expected)) {
            "Incorrect $description at $x,$y: expected #${expected.toString(16).padStart(6, '0')}, " +
                "got #${(actual and 0xFFFFFF).toString(16).padStart(6, '0')}"
        }
    }

    private fun matches(actual: Int, expected: Int): Boolean =
        channel(actual, 24) == 255 && CHANNEL_SHIFTS.all { abs(channel(actual, it) - channel(expected, it)) <= 8 }

    private fun channel(color: Int, shift: Int): Int = (color ushr shift) and 255

    private const val BAR_LEFT = 654
    private const val BAR_VISIBLE_LEFT = 714
    private const val GOLD = 0xB98F2C
    private const val BLUE = 0x036A96
    private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)
    private val BAR_ROWS = intArrayOf(354, 356, 358)
    private val FRAME_PIXELS =
        listOf(
            Triple(126, 2, 0xFFFFFF), Triple(180, 2, 0xFFFFFF),
            Triple(1, 30, 0xFFFFFF), Triple(1, 70, 0xFFFFFF), Triple(1, 110, 0xFFFFFF),
            Triple(126, 4, 0xC6C6C6), Triple(180, 4, 0xC6C6C6),
            Triple(3, 30, 0xC6C6C6), Triple(3, 70, 0xC6C6C6), Triple(3, 110, 0xC6C6C6),
            Triple(126, 136, 0xC6C6C6), Triple(220, 136, 0xC6C6C6),
            Triple(126, 138, 0x555555), Triple(220, 138, 0x555555),
        )
    private val BACKGROUND_PIXELS = listOf(440 to 300, 500 to 300, 800 to 300, 440 to 430, 500 to 430)

    // Tiny bitmap masks observed unchanged in vanilla 1.17.1, 1.20.4 and 26.2 ascii.png.
    // Each row's least-significant bit is the left pixel; the sixth column is spacing.
    private val GLYPHS =
        mapOf(
            ' ' to intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
            '0' to intArrayOf(0x0E, 0x11, 0x19, 0x15, 0x13, 0x11, 0x0E, 0),
            '1' to intArrayOf(0x04, 0x06, 0x04, 0x04, 0x04, 0x04, 0x1F, 0),
            '3' to intArrayOf(0x0E, 0x11, 0x10, 0x0C, 0x10, 0x11, 0x0E, 0),
            '9' to intArrayOf(0x0E, 0x11, 0x11, 0x1E, 0x10, 0x08, 0x06, 0),
            '/' to intArrayOf(0x10, 0x08, 0x08, 0x04, 0x02, 0x02, 0x01, 0),
            'P' to intArrayOf(0x0F, 0x11, 0x0F, 0x01, 0x01, 0x01, 0x01, 0),
            'r' to intArrayOf(0, 0, 0x0D, 0x13, 0x01, 0x01, 0x01, 0),
            'o' to intArrayOf(0, 0, 0x0E, 0x11, 0x11, 0x11, 0x0E, 0),
            'g' to intArrayOf(0, 0, 0x1E, 0x11, 0x11, 0x1E, 0x10, 0x0F),
            'e' to intArrayOf(0, 0, 0x0E, 0x11, 0x1F, 0x01, 0x1E, 0),
            's' to intArrayOf(0, 0, 0x1E, 0x01, 0x0E, 0x10, 0x0F, 0),
        )
}
