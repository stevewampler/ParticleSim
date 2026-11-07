package particlesim.render

import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Generated road-surface textures for the two Tacoma Narrows bridges ([TextureAssets]). Each image
 * is one repeat of the road: its width spans the deck from one cable plane to the other (the
 * mesh's `u` 0 to 1) and its height 40 ft along the span (one 10 ft dash and its 30 ft gap), drawn
 * to the real layouts in feet. Deterministic: a fixed-seed grain, no randomness between runs.
 */
internal object RoadwayTextures {
    /** One roadway image, [widthFt] across by 40 ft along, with paint helpers in feet. */
    private class Canvas(val widthFt: Double, val widthPx: Int, val heightPx: Int = 512) {
        val image = BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB)
        private val pxPerFtX = widthPx / widthFt
        private val pxPerFtY = heightPx / 40.0

        /** Fills `[x0, x1)` across × `[y0, y1)` along (feet) with [rgb], plus ±[grain] of noise. */
        fun fill(x0: Double, x1: Double, rgb: Int, grain: Int = 0, y0: Double = 0.0, y1: Double = 40.0, seed: Int = 1) {
            val random = Random(seed)
            val r = rgb shr 16 and 255; val g = rgb shr 8 and 255; val b = rgb and 255
            for (px in (x0 * pxPerFtX).toInt() until minOf(widthPx, (x1 * pxPerFtX).toInt())) {
                for (py in (y0 * pxPerFtY).toInt() until minOf(heightPx, (y1 * pxPerFtY).toInt())) {
                    val n = if (grain == 0) 0 else random.nextInt(-grain, grain + 1)
                    image.setRGB(px, py, AwtColor((r + n).coerceIn(0, 255), (g + n).coerceIn(0, 255), (b + n).coerceIn(0, 255)).rgb)
                }
            }
        }

        /** A painted line [width] ft wide centred at [x] ft, solid or 10 ft on / 30 ft off. */
        fun line(x: Double, width: Double, rgb: Int, dashed: Boolean = false) =
            fill(x - width / 2, x + width / 2, rgb, grain = 6, y1 = if (dashed) 10.0 else 40.0, seed = x.hashCode())

        /** Mirrors a line or strip about the deck's centreline. */
        fun both(x: Double, f: (Double) -> Unit) { f(x); f(widthFt - x) }

        fun png(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private const val WHITE = 0xE8E6DE
    private const val YELLOW = 0xE2B11C

    /**
     * 1940: a 26 ft concrete roadway with a single white centre line, centred in the 39 ft
     * between the cables, with a raised curb (its shadowed gutter as a dark strip) and a sidewalk
     * on each side, out over the plate girders - after the WSDOT photograph "Tacoma Narrows
     * Bridge, view looking west from east approach", 29 Aug 1940. The slab's transverse joints
     * every 20 ft show as faint lines.
     */
    fun tacoma1940(): ByteArray = Canvas(widthFt = 39.0, widthPx = 512).run {
        val curb = (39.0 - 26.0) / 2
        fill(0.0, 39.0, 0xBDBAB0, grain = 7) // concrete roadway
        fill(0.0, curb, 0xD2CFC6, grain = 5, seed = 2); fill(39.0 - curb, 39.0, 0xD2CFC6, grain = 5, seed = 3) // sidewalks
        both(curb) { x -> line(x, 0.5, 0x5E5C58) } // curb gutter shadow
        both(curb - 0.6) { x -> line(x, 0.25, 0xE4E1D8) } // curb top edge, catching the light
        // Wheel paths, very slightly darker.
        for (x in listOf(curb + 3.0, curb + 9.0, 39.0 - curb - 9.0, 39.0 - curb - 3.0)) line(x, 2.0, 0xB7B4AA)
        line(19.5, 0.4, WHITE) // centre line
        for (y in listOf(0.0, 20.0)) fill(curb, 39.0 - curb, 0x9C9990, y0 = y, y1 = y + 0.15) // slab joints
        png()
    }

    /**
     * 1950: 49 ft 10 in of asphalt-dark roadway between the curbs, centred in the 60 ft between
     * the trusses - four 9⅝ ft lanes, separated by 33 in strips (open steel grates as built) and
     * edged by 19 in strips along the curbs - with a 3 ft sidewalk each side and the trusses' top
     * chords at the edges. Markings after a recent photograph of the bridge (white edge lines,
     * white dashed lane lines), with a double yellow centre line for the original two-way traffic.
     */
    fun tacoma1950(): ByteArray = Canvas(widthFt = 60.0, widthPx = 1024).run {
        val roadway = 49.0 + 10.0 / 12.0
        val curb = (60.0 - roadway) / 2
        val lane = 9.625
        val separator = 2.75
        val gutter = 19.0 / 12.0
        fill(0.0, 60.0, 0x4A4C4F, grain = 9) // asphalt
        fill(0.0, 1.5, 0x52745C, grain = 4, seed = 4); fill(58.5, 60.0, 0x52745C, grain = 4, seed = 5) // top chords, painted green
        fill(1.5, curb, 0xB5B2A9, grain = 5, seed = 6); fill(60.0 - curb, 58.5, 0xB5B2A9, grain = 5, seed = 7) // sidewalks
        both(curb) { x -> line(x, 0.4, 0x2E2F31) } // curb face
        // Lanes from the left curb: gutter, lane, separator, lane, separator (centre), lane, ...
        val edge = curb + gutter
        both(edge) { x -> line(x, 0.33, WHITE) } // edge lines
        val sideSeparator = edge + lane + separator / 2
        both(sideSeparator) { x -> line(x, 0.33, WHITE, dashed = true) } // lane lines
        both(30.0 - 0.33) { x -> line(x, 0.33, YELLOW) } // double yellow centre line
        // Worn, slightly lighter wheel paths in each lane.
        for (laneStart in listOf(edge, edge + lane + separator, 30.0 + separator / 2, 30.0 + separator / 2 + lane + separator)) {
            for (x in listOf(laneStart + 2.2, laneStart + lane - 2.2)) line(x, 1.4, 0x4E5053)
        }
        png()
    }
}
