import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * Compares the opaque advancement UI in full-size vanilla F2 screenshots, not the changing world/chat.
 *
 * Coordinates are for the English, GUI-scale-2, 1280x720 fixture. The compared area is the union of:
 * - the window interior [396,884) x [228,492), including its title, background, tree and item icons;
 * - the entire hovered tooltip [654,right) x [348,418), including its title, fraction and description.
 *
 * The outer window frame is checked separately by [AdvancementScreenshotValidator]. Its rounded
 * corners are not included here. Actual 1.20.4, 1.21.11 and 26.2 PNGs show that the tooltip's last
 * four columns in its top/bottom two rows, and last two columns in the next two rows, reveal the
 * world. Only those transparent corners are excluded. The tooltip's left corners remain inside
 * the opaque window. The baseline determines its 900/964 right edge; actual pixels never shrink
 * the comparison region. No text, icons, background texture or other interior content is masked.
 *
 * Every compared pixel must match, with at most 8 levels of RGB rounding per channel and identical
 * alpha. There is no percentage-of-different-pixels allowance. The diff renders changed pixels in
 * magenta, unchanged UI in grayscale, and ignored pixels in darker grayscale.
 */
object AdvancementScreenshotComparison {
    data class Result(
        val matches: Boolean,
        val differingPixelCount: Int,
        val comparedPixelCount: Int,
        val diff: BufferedImage,
    )

    fun compare(expected: BufferedImage, actual: BufferedImage): Result {
        requireDimensions(expected, "baseline")
        requireDimensions(actual, "actual screenshot")
        val tooltipRight = detectTooltipRight(expected)
        val expectedPixels = expected.getRGB(0, 0, WIDTH, HEIGHT, null, 0, WIDTH)
        val actualPixels = actual.getRGB(0, 0, WIDTH, HEIGHT, null, 0, WIDTH)
        val diffPixels = IntArray(WIDTH * HEIGHT)
        var comparedPixelCount = 0
        var differingPixelCount = 0

        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val index = y * WIDTH + x
                val compared = inComparedRegion(x, y, tooltipRight)
                val actualColor = actualPixels[index]
                val different = compared && !colorsMatch(expectedPixels[index], actualColor)
                if (compared) comparedPixelCount++
                if (different) differingPixelCount++
                diffPixels[index] = if (different) DIFF_COLOR else gray(actualColor, compared)
            }
        }

        val diff = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        diff.setRGB(0, 0, WIDTH, HEIGHT, diffPixels, 0, WIDTH)
        return Result(differingPixelCount == 0, differingPixelCount, comparedPixelCount, diff)
    }

    private fun requireDimensions(image: BufferedImage, description: String) {
        require(image.width == WIDTH && image.height == HEIGHT) {
            "Expected a ${WIDTH}x${HEIGHT} $description, got ${image.width}x${image.height}"
        }
    }

    private fun detectTooltipRight(expected: BufferedImage): Int {
        // These three bar rows are unobscured by text and the hovered node's frame.
        val border = requireNotNull((714 until 1000).firstOrNull { x -> !isBarColor(expected.getRGB(x, 354)) }) {
            "Could not find the baseline Progress tooltip's right border"
        }
        val right = border + 4
        require(right == 900 || right == 964) {
            "Expected the baseline Progress tooltip's right edge at 900 or 964, got $right"
        }
        intArrayOf(354, 356, 358).forEach { y ->
            require(
                (714 until right - 4).all { x -> isBarColor(expected.getRGB(x, y)) } &&
                    (right - 4 until right - 2).all { x ->
                        val color = expected.getRGB(x, y)
                        colorsMatch(color, 0xFF012E40.toInt()) || colorsMatch(color, 0xFF493606.toInt())
                    } &&
                    (right - 2 until right).all { x -> colorsMatch(expected.getRGB(x, y), 0xFF000000.toInt()) },
            ) { "Invalid baseline Progress tooltip border at y=$y" }
        }
        return right
    }

    private fun inComparedRegion(x: Int, y: Int, tooltipRight: Int): Boolean {
        if (x in 396 until 884 && y in 228 until 492) return true
        if (y !in 348 until 418 || x < 654) return false
        val transparentCornerWidth = when (y) {
            348, 349, 416, 417 -> 4
            350, 351, 414, 415 -> 2
            else -> 0
        }
        return x < tooltipRight - transparentCornerWidth
    }

    private fun isBarColor(color: Int): Boolean =
        colorsMatch(color, 0xFFB98F2C.toInt()) || colorsMatch(color, 0xFF036A96.toInt())

    private fun colorsMatch(expected: Int, actual: Int): Boolean =
        channel(expected, 24) == channel(actual, 24) &&
            CHANNEL_SHIFTS.all { shift -> abs(channel(expected, shift) - channel(actual, shift)) <= 8 }

    private fun gray(color: Int, compared: Boolean): Int {
        val luminance = (299 * channel(color, 16) + 587 * channel(color, 8) + 114 * channel(color, 0)) / 1000
        val value = if (compared) 64 + 3 * luminance / 4 else luminance / 4
        return 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
    }

    private fun channel(color: Int, shift: Int): Int = (color ushr shift) and 255

    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private val DIFF_COLOR = 0xFFFF00FF.toInt()
    private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)
}
