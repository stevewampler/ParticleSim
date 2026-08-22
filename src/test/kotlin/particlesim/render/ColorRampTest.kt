package particlesim.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ColorRampTest {

    @Test
    fun `0 is pure blue, 1 is pure orange`() {
        assertEquals(Color(0.0, 114.0 / 255, 178.0 / 255), ColorRamp.blueOrange(0.0))
        assertEquals(Color(230.0 / 255, 159.0 / 255, 0.0), ColorRamp.blueOrange(1.0))
    }

    @Test
    fun `0point5 is the midpoint of each channel`() {
        val mid = ColorRamp.blueOrange(0.5)
        assertEquals((0.0 + 230.0 / 255) / 2, mid.r, 1e-12)
        assertEquals((114.0 / 255 + 159.0 / 255) / 2, mid.g, 1e-12)
        assertEquals((178.0 / 255 + 0.0) / 2, mid.b, 1e-12)
    }

    @Test
    fun `values outside 0,1 are clamped, not extrapolated`() {
        assertEquals(ColorRamp.blueOrange(0.0), ColorRamp.blueOrange(-5.0))
        assertEquals(ColorRamp.blueOrange(1.0), ColorRamp.blueOrange(5.0))
    }

    @Test
    fun `no point on the gradient coincides with the default uncolored line color`() {
        // §10.3's viewer-side color legend has no explicit per-connection tag saying "this color
        // came from a colorBy" - it detects activity purely by checking whether a connection's
        // color differs from Color.DEFAULT_LINE (debug-viewer.html's isDefaultLineColor). That
        // heuristic silently breaks if any point on this gradient ever equals DEFAULT_LINE - a
        // breakProximity-colored connection would then look indistinguishable from an uncolored
        // one and the legend would never show. Sampled points, not a proof of every interior t -
        // but DEFAULT_LINE's blue channel (1.0) exceeds both endpoints' (0.698 max), which bounds
        // the whole straight-line-interpolated segment's blue channel below DEFAULT_LINE's, so no
        // point on it can coincide - the samples exercise that conclusion, not just assert it.
        val samples = listOf(0.0, 0.25, 0.5, 0.75, 1.0).map { ColorRamp.blueOrange(it) }
        for (color in samples) {
            assertNotEquals(Color.DEFAULT_LINE, color)
        }
    }
}
