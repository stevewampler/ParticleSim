package particlesim.render

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §10.2's texture-mapped surfaces: [TextureAssets] is the only source of image bytes this
 * project ships (procedurally generated, never fetched externally or checked in as a binary
 * asset) — proven here as valid, decodable PNG data, not just "some bytes came back". */
class TextureAssetsTest {

    @Test
    fun `a known texture name returns decodable, non-trivial PNG bytes`() {
        val bytes = TextureAssets.pngBytes(TextureAssets.FLAG_STRIPES)
        assertNotNull(bytes)
        assertTrue(bytes.size > 100, "expected real image data, got ${bytes.size} bytes")

        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertNotNull(image, "TextureAssets should produce bytes ImageIO can actually decode as an image")
        assertTrue(image.width > 1 && image.height > 1)
    }

    @Test
    fun `an unknown texture name returns null`() {
        assertNull(TextureAssets.pngBytes("not-a-real-texture"))
    }

    @Test
    fun `the same name returns byte-identical data on repeated calls (generated once, cached)`() {
        val first = TextureAssets.pngBytes(TextureAssets.FLAG_STRIPES)
        val second = TextureAssets.pngBytes(TextureAssets.FLAG_STRIPES)
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first.toList(), second.toList())
    }
}
