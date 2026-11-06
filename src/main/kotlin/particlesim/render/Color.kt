package particlesim.render

/** RGB in `[0,1]` per channel — three.js's `Color.setRGB` convention, since this is meant to
 * be consumed directly by the (future) web viewer without a client-side conversion step. */
data class Color(val r: Double, val g: Double, val b: Double) {
    companion object {
        /** The debug viewer's original uncolored-line blue (`0x3388ff`) — what a connection
         * renders as when nothing declares a `colorBy` for it. */
        val DEFAULT_LINE = Color(0x33 / 255.0, 0x88 / 255.0, 0xff / 255.0)
    }
}

object ColorRamp {
    private val green = Color(0.0 / 255, 170.0 / 255, 60.0 / 255)
    private val yellow = Color(240.0 / 255, 200.0 / 255, 0.0 / 255)
    private val red = Color(215.0 / 255, 25.0 / 255, 28.0 / 255)

    /**
     * Green → yellow → red as [t] goes 0 → 0.5 → 1 (§10.2), linear within each half - the
     * familiar traffic-light reading of "fine / getting close / about to break". It replaced an
     * Okabe-Ito blue → orange ramp at the user's request; the trade-off is that green vs red is
     * hard to tell apart with deuteranopia, one of the most common color vision deficiencies
     * (the yellow middle and the brightness difference still help).
     *
     * [t] is clamped to `[0,1]` first: a caller passing an unclamped `breakProximity` (which
     * can exceed `1` the one step a connection actually breaks — its force still applies that
     * step, §5.4) still gets a valid color rather than an out-of-gamut extrapolation.
     */
    fun greenYellowRed(t: Double): Color {
        val c = t.coerceIn(0.0, 1.0)
        val (from, to, f) = if (c <= 0.5) Triple(green, yellow, c * 2.0) else Triple(yellow, red, (c - 0.5) * 2.0)
        return Color(from.r + (to.r - from.r) * f, from.g + (to.g - from.g) * f, from.b + (to.b - from.b) * f)
    }
}
