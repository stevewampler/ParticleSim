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

/** [this] display (sRGB) colour as the linear values [Color] usually carries - for a ramp colour
 * (display values, so it matches its legend) going somewhere linear is expected, like a line. */
fun Color.srgbToLinear(): Color {
    fun ch(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    return Color(ch(r), ch(g), ch(b))
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

    /** Blue → cyan → green → yellow → red, evenly spaced: the usual stress-plot ramp, low to
     * high. Display (sRGB) values, unlike [Color]'s usual linear ones - the viewer draws them
     * as given, so the legend's CSS gradient and the surface match. */
    val STRESS_STOPS: List<Color> = listOf(
        Color(0.16, 0.27, 0.86),
        Color(0.0, 0.75, 0.92),
        Color(0.2, 0.8, 0.27),
        Color(0.98, 0.84, 0.1),
        Color(0.86, 0.12, 0.1),
    )

    /** Blue → near-white → red: a signed quantity, compression (negative) blue and tension
     * red, nothing white. Display (sRGB) values, like [STRESS_STOPS]. */
    val SIGNED_STOPS: List<Color> = listOf(
        Color(0.12, 0.3, 0.8),
        Color(0.45, 0.65, 0.95),
        Color(0.93, 0.93, 0.93),
        Color(0.97, 0.6, 0.45),
        Color(0.82, 0.1, 0.1),
    )

    /** [SIGNED_STOPS] at [t] from -1 (blue) through 0 (white) to 1 (red), clamped. */
    fun signed(t: Double): Color = along(SIGNED_STOPS, (t.coerceIn(-1.0, 1.0) + 1.0) / 2.0)

    /** [STRESS_STOPS] at [t] (clamped to `[0,1]`), linear between neighbouring stops. */
    fun stress(t: Double): Color = along(STRESS_STOPS, t)

    /** [stops] spread evenly over 0..1, at [t] (clamped), linear between neighbours. */
    fun along(stops: List<Color>, t: Double): Color {
        val c = t.coerceIn(0.0, 1.0) * (stops.size - 1)
        if (c >= stops.size - 1) return stops.last()
        val i = c.toInt()
        val f = c - i
        val from = stops[i]; val to = stops[i + 1]
        return Color(from.r + (to.r - from.r) * f, from.g + (to.g - from.g) * f, from.b + (to.b - from.b) * f)
    }
}
