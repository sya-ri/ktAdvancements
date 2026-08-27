import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdvancementScreenshotValidatorTest {
    @Test
    fun `accepts all four stages at both vanilla tooltip widths`() {
        for (width in listOf(123, 155)) {
            for (progress in listOf(0, 3, 10, 9)) {
                AdvancementScreenshotValidator.validate(fixture(width, progress), progress)
            }
        }
    }

    @Test
    fun `accepts small rendering color differences`() {
        val image = fixture(155, 3)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val color = Color(image.getRGB(x, y))
                if (color.red < 240 || color.green < 240 || color.blue < 240) {
                    val adjusted =
                        Color(
                            (color.red + 4).coerceAtMost(255),
                            (color.green + 4).coerceAtMost(255),
                            (color.blue + 4).coerceAtMost(255),
                        )
                    image.setRGB(x, y, adjusted.rgb)
                }
            }
        }
        AdvancementScreenshotValidator.validate(image, 3)
    }

    @Test
    fun `rejects incorrect screenshot dimensions`() {
        for ((width, height) in listOf(1279 to 720, 1280 to 719, 640 to 360)) {
            assertRejected(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), 0, "1280x720")
        }
    }

    @Test
    fun `rejects a world image or missing window frame`() {
        assertRejected(BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB), 0, "window frame")
        val image = fixture(155, 0)
        image.setRGB(640, 224, Color.BLACK.rgb)
        assertRejected(image, 0, "window frame")
    }

    @Test
    fun `rejects an empty advancement window`() {
        val image = fixture(155, 0)
        paint(image, 406, 256, 468, 226, 0x000000)
        assertRejected(image, 0, "tree may be empty")
    }

    @Test
    fun `rejects a missing root or Progress node`() {
        val noRoot = fixture(155, 3)
        paint(noRoot, 576, 342, 52, 52, 0x808080)
        assertRejected(noRoot, 3, "root frame")
        val noProgress = fixture(155, 3)
        paint(noProgress, 660, 342, 52, 52, 0x808080)
        assertRejected(noProgress, 3, "Progress frame")
    }

    @Test
    fun `rejects missing or incorrectly sized tooltips`() {
        val noTooltip = fixture(155, 0)
        paint(noTooltip, 714, 350, 300, 68, 0x808080)
        assertRejected(noTooltip, 0, "tooltip")
        assertRejected(fixture(140, 0), 0, "tooltip width")
    }

    @Test
    fun `rejects missing tooltip title and description panel`() {
        val noTitle = fixture(155, 3)
        paint(noTitle, 718, 360, 96, 16, BLUE)
        assertRejected(noTitle, 3, "tooltip title")
        val noDescription = fixture(155, 3)
        noDescription.setRGB(958, 410, Color.BLACK.rgb)
        assertRejected(noDescription, 3, "description panel")
    }

    @Test
    fun `rejects incorrect fraction even when the bar is correct`() {
        for (width in listOf(123, 155)) {
            assertRejected(fixture(width, 3, fraction = "9/10"), 3, "progress fraction")
            assertRejected(fixture(width, 3, fraction = "3/11"), 3, "progress fraction")
            assertRejected(fixture(width, 0, fraction = "10/10"), 0, "progress fraction")
            assertRejected(fixture(width, 3, fraction = ""), 3, "progress fraction")
        }
    }

    @Test
    fun `rejects both extra glyph pixels and incomplete scaled pixels`() {
        val extraPixel = fixture(155, 3)
        extraPixel.setRGB(906, 364, Color.WHITE.rgb)
        assertRejected(extraPixel, 3, "progress fraction")
        val missingPixel = fixture(155, 3)
        missingPixel.setRGB(909, 361, Color.BLACK.rgb)
        assertRejected(missingPixel, 3, "progress fraction")
    }

    @Test
    fun `rejects incorrect bar even when the displayed fraction is correct`() {
        for (width in listOf(123, 155)) {
            assertRejected(fixture(width, 3, barProgress = 9), 3, "progress bar")
            assertRejected(fixture(width, 9, barProgress = 3), 9, "progress bar")
            assertRejected(fixture(width, 0, barProgress = 3), 0, "progress bar")
            assertRejected(fixture(width, 10, barProgress = 9), 10, "progress bar")
            assertRejected(fixture(width, 3, transitionOffset = 2), 3, "progress bar")
        }
    }

    @Test
    fun `checks multiple bar rows and its right border`() {
        val brokenRow = fixture(155, 3)
        brokenRow.setRGB(800, 356, Color.BLACK.rgb)
        assertRejected(brokenRow, 3, "progress bar")
        val brokenBorder = fixture(155, 3)
        brokenBorder.setRGB(962, 356, Color.WHITE.rgb)
        assertRejected(brokenBorder, 3, "right border")
    }

    @Test
    fun `rejects unsupported expected stages`() {
        assertFailsWith<IllegalArgumentException> {
            AdvancementScreenshotValidator.validate(fixture(155, 0), 2)
        }
    }

    private fun assertRejected(image: BufferedImage, progress: Int, message: String) {
        val error = assertFailsWith<IllegalStateException> {
            AdvancementScreenshotValidator.validate(image, progress)
        }
        assertTrue(error.message.orEmpty().contains(message), error.message)
    }

    // Deliberately constructed pixels, not screenshots or vendored Minecraft textures.
    private fun fixture(
        width: Int,
        progress: Int,
        barProgress: Int = progress,
        fraction: String = "$progress/10",
        transitionOffset: Int = 0,
    ): BufferedImage {
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

        val right = 654 + 2 * width
        val transition = 654 + 2 * (width * barProgress / 10) + transitionOffset
        for (x in 714 until right - 4) {
            paint(image, x, 352, 1, 8, if (x < transition) GOLD else BLUE)
        }
        paint(image, right - 4, 352, 2, 8, if (progress == 10) 0x493606 else 0x012E40)
        paint(image, right - 2, 352, 2, 8, 0x000000)
        paint(image, 654, 394, 2 * width, 24, 0x212121)
        drawText(image, "Progress", 718)
        drawText(image, fraction.padStart(5), right - 10 - 12 * fraction.padStart(5).length)
        return image
    }

    private fun drawText(image: BufferedImage, text: String, left: Int) {
        paint(image, left, 360, 12 * text.length, 16, BLUE)
        text.forEachIndexed { index, character ->
            requireNotNull(FONT[character]).forEachIndexed { y, row ->
                row.forEachIndexed { x, pixel ->
                    if (pixel == '#') paint(image, left + 12 * index + 2 * x, 360 + 2 * y, 2, 2, 0xFFFFFF)
                }
            }
        }
    }

    private fun paint(image: BufferedImage, x: Int, y: Int, width: Int, height: Int, rgb: Int) {
        image.createGraphics().let { graphics ->
            try {
                graphics.color = Color(rgb)
                graphics.fillRect(x, y, width, height)
            } finally {
                graphics.dispose()
            }
        }
    }

    private companion object {
        const val GOLD = 0xB98F2C
        const val BLUE = 0x036A96
        val FONT =
            mapOf(
                ' ' to List(8) { "....." },
                '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###.", "....."),
                '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", "#####", "....."),
                '3' to listOf(".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###.", "....."),
                '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##..", "....."),
                '/' to listOf("....#", "...#.", "...#.", "..#..", ".#...", ".#...", "#....", "....."),
                'P' to listOf("####.", "#...#", "####.", "#....", "#....", "#....", "#....", "....."),
                'r' to listOf(".....", ".....", "#.##.", "##..#", "#....", "#....", "#....", "....."),
                'o' to listOf(".....", ".....", ".###.", "#...#", "#...#", "#...#", ".###.", "....."),
                'g' to listOf(".....", ".....", ".####", "#...#", "#...#", ".####", "....#", "####."),
                'e' to listOf(".....", ".....", ".###.", "#...#", "#####", "#....", ".####", "....."),
                's' to listOf(".....", ".....", ".####", "#....", ".###.", "....#", "####.", "....."),
            )
    }
}
