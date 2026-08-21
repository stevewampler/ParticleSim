package particlesim.render

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
