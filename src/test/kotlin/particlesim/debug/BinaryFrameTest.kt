package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.render.CameraPose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** §9.1's binary per-frame encoding — proved by round-trip (encode then decode) rather than
 * asserting a specific byte layout, since the layout itself isn't part of any external
 * contract yet (only this codebase's own encoder/decoder pair, and the JS client's parser,
 * need to agree on it). */
class BinaryFrameTest {

    @Test
    fun `round-trips particles and connections exactly`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3(1.0, 2.0, 3.0))
        val b = store.create(position = Vector3(-1.5, 0.25, 100.0))

        val buffer = BinaryFrame.encode(t = 1.5, step = 42L, store = store, ids = listOf(a, b), connections = listOf(a to b))
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(1.5, decoded.t)
        assertEquals(42L, decoded.step)
        assertEquals(listOf(DecodedParticle(a, Vector3(1.0, 2.0, 3.0)), DecodedParticle(b, Vector3(-1.5, 0.25, 100.0))), decoded.particles)
        assertEquals(listOf(a to b), decoded.connections)
        assertNull(decoded.camera)
    }

    @Test
    fun `round-trips an empty frame`() {
        val store = ParticleStore()
        val buffer = BinaryFrame.encode(t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList())
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(0.0, decoded.t)
        assertEquals(0L, decoded.step)
        assertEquals(emptyList(), decoded.particles)
        assertEquals(emptyList(), decoded.connections)
        assertNull(decoded.camera)
    }

    @Test
    fun `round-trips a camera pose when present`() {
        val store = ParticleStore()
        val camera = CameraPose(position = Vector3(5.0, 6.0, 7.0), lookAt = Vector3(0.0, 1.0, 0.0), up = Vector3(0.0, 1.0, 0.0))
        val buffer = BinaryFrame.encode(t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(), camera = camera)
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(camera, decoded.camera)
    }

    @Test
    fun `decode does not consume the original buffer's position`() {
        val store = ParticleStore()
        val id = store.create(position = Vector3.ZERO)
        val buffer = BinaryFrame.encode(t = 0.0, step = 0L, store = store, ids = listOf(id), connections = emptyList())

        val positionBefore = buffer.position()
        BinaryFrame.decode(buffer)
        assertEquals(positionBefore, buffer.position(), "decode must not mutate the buffer a caller still needs to send")
    }
}
