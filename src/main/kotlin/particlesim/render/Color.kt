package particlesim.render

/** RGB in `[0,1]` per channel — three.js's `Color.setRGB` convention, since this is meant to
 * be consumed directly by the (future) web viewer without a client-side conversion step. */
data class Color(val r: Double, val g: Double, val b: Double)

object ColorRamp {
    // Okabe-Ito colorblind-safe palette's blue/orange pair.
    private val blue = Color(0.0 / 255, 114.0 / 255, 178.0 / 255)
    private val orange = Color(230.0 / 255, 159.0 / 255, 0.0 / 255)

    /**
     * Perceptually-ordered, colorblind-safe blue → orange gradient (§10.2) as [t] goes 0 → 1 —
     * chosen explicitly over the intuitive green → yellow → red, which is close to worst-case
     * for deuteranopia, one of the most common color vision deficiencies.
     *
     * [t] is clamped to `[0,1]` first: a caller passing an unclamped `breakProximity` (which
     * can exceed `1` the one step a connection actually breaks — its force still applies that
     * step, §5.4) still gets a valid color rather than an out-of-gamut extrapolation.
     */
    fun blueOrange(t: Double): Color {
        val c = t.coerceIn(0.0, 1.0)
        return Color(
            blue.r + (orange.r - blue.r) * c,
            blue.g + (orange.g - blue.g) * c,
            blue.b + (orange.b - blue.b) * c,
        )
    }
}
