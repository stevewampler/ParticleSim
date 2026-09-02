package particlesim.render

import java.awt.Color as AwtColor
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * §10.2's texture-mapped surfaces (`[stretch]`, now built): a small named registry of
 * procedurally-generated PNG images, rather than a checked-in binary asset (this project has
 * none anywhere else) or an externally-fetched one (no legitimate source for "the actual flag
 * graphic" to pull from, and URLs are never fetched/guessed on a user's behalf for this kind of
 * request). Each image is generated once per process and cached — [particlesim.debug.ViewerHttpServer]
 * serves the bytes as a static file at `/textures/<name>.png`, referenced by a
 * [SurfaceRenderer.textureName], not pushed through the per-frame binary protocol.
 */
object TextureAssets {
    const val FLAG_STRIPES = "flag-stripes"

    private val cache = HashMap<String, ByteArray>()

    /** `null` if [name] isn't a known generated texture. */
    @Synchronized
    fun pngBytes(name: String): ByteArray? = when (name) {
        FLAG_STRIPES -> cache.getOrPut(name) { generateFlagStripes() }
        else -> null
    }

    /**
     * A striped pattern with a colored block in one corner — deliberately not a reproduction of
     * any real flag, just an asymmetric, high-contrast pattern that makes a UV mapping mistake
     * (flipped, rotated, or mis-scaled) visually obvious in a way a flat solid color couldn't.
     */
    private fun generateFlagStripes(): ByteArray {
        val width = 256
        val height = 160
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = image.createGraphics()
        val stripeCount = 8
        val stripeHeight = height / stripeCount
        for (i in 0 until stripeCount) {
            g.color = if (i % 2 == 0) AwtColor(178, 34, 52) else AwtColor.WHITE
            g.fillRect(0, i * stripeHeight, width, height / stripeCount)
        }
        val cantonWidth = width * 2 / 5
        val cantonHeight = height / 2
        g.color = AwtColor(50, 60, 130)
        g.fillRect(0, 0, cantonWidth, cantonHeight)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
