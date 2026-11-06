package particlesim.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ColorRampTest {

    @Test
    fun `0 is green, 0point5 is yellow, 1 is red`() {
        fun assertColor(expected: Color, actual: Color) {
            assertEquals(expected.r, actual.r, 1e-12); assertEquals(expected.g, actual.g, 1e-12); assertEquals(expected.b, actual.b, 1e-12)
        }
        assertColor(Color(0.0, 170.0 / 255, 60.0 / 255), ColorRamp.greenYellowRed(0.0))
        assertColor(Color(240.0 / 255, 200.0 / 255, 0.0), ColorRamp.greenYellowRed(0.5))
        assertColor(Color(215.0 / 255, 25.0 / 255, 28.0 / 255), ColorRamp.greenYellowRed(1.0))
    }

    @Test
    fun `each half interpolates linearly between its two stops`() {
        val quarter = ColorRamp.greenYellowRed(0.25)
        assertEquals((0.0 + 240.0 / 255) / 2, quarter.r, 1e-12)
        assertEquals((170.0 / 255 + 200.0 / 255) / 2, quarter.g, 1e-12)
        assertEquals((60.0 / 255 + 0.0) / 2, quarter.b, 1e-12)
        val threeQuarter = ColorRamp.greenYellowRed(0.75)
        assertEquals((240.0 / 255 + 215.0 / 255) / 2, threeQuarter.r, 1e-12)
        assertEquals((200.0 / 255 + 25.0 / 255) / 2, threeQuarter.g, 1e-12)
        assertEquals((0.0 + 28.0 / 255) / 2, threeQuarter.b, 1e-12)
    }

    @Test
    fun `values outside 0,1 are clamped, not extrapolated`() {
        assertEquals(ColorRamp.greenYellowRed(0.0), ColorRamp.greenYellowRed(-5.0))
        assertEquals(ColorRamp.greenYellowRed(1.0), ColorRamp.greenYellowRed(5.0))
    }

    @Test
    fun `no point on the gradient coincides with the default uncolored line color`() {
        // §10.3's viewer-side color legend has no explicit per-connection tag saying "this color
        // came from a colorBy" - it detects activity purely by checking whether a connection's
        // color differs from Color.DEFAULT_LINE (viewer.html's isDefaultLineColor). That
        // heuristic silently breaks if any point on this gradient ever equals DEFAULT_LINE. Every
        // stop's blue channel is at most 60/255, far below DEFAULT_LINE's 1.0, and linear
        // interpolation between stops can't exceed its endpoints - the samples exercise that.
        val samples = (0..20).map { ColorRamp.greenYellowRed(it / 20.0) }
        for (color in samples) {
            assertNotEquals(Color.DEFAULT_LINE, color)
        }
    }
}
