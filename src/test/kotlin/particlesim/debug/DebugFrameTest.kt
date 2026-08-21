package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.render.CameraPose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * §9.1/§10.2: the only piece of the Phase 3 debug renderer that's meaningfully testable
 * without a browser — the actual dots/lines rendering can only be confirmed by looking.
 */
class DebugFrameTest {

    @Test
    fun `renders particle positions and connection endpoints as JSON`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3(1.0, 2.0, 3.0))
        val b = store.create(position = Vector3(-1.5, 0.0, 2.25))

        val json = DebugFrame.render(1.5, 42L, store, listOf(a, b), listOf(a to b))

        assertEquals(
            "{\"t\":1.500000,\"step\":42,\"particles\":[" +
                "{\"id\":0,\"x\":1.000000,\"y\":2.000000,\"z\":3.000000}," +
                "{\"id\":1,\"x\":-1.500000,\"y\":0.000000,\"z\":2.250000}" +
                "],\"connections\":[{\"a\":0,\"b\":1}]}",
            json,
        )
    }

    @Test
    fun `renders empty particle and connection lists correctly`() {
        val store = ParticleStore()
        val json = DebugFrame.render(0.0, 0L, store, emptyList(), emptyList())
        assertEquals("{\"t\":0.000000,\"step\":0,\"particles\":[],\"connections\":[]}", json)
    }

    @Test
    fun `omits the camera field entirely when no camera is supplied`() {
        val store = ParticleStore()
        val json = DebugFrame.render(0.0, 0L, store, emptyList(), emptyList())
        assertFalse(json.contains("camera"), "a demo with no scripted camera shouldn't send a camera field at all")
    }

    @Test
    fun `renders the camera pose as JSON when supplied`() {
        val store = ParticleStore()
        val camera = CameraPose(position = Vector3(1.0, 2.0, 3.0), lookAt = Vector3(0.0, 0.0, 0.0), up = Vector3(0.0, 1.0, 0.0))
        val json = DebugFrame.render(0.0, 0L, store, emptyList(), emptyList(), camera)

        assertEquals(
            "{\"t\":0.000000,\"step\":0,\"particles\":[],\"connections\":[]," +
                "\"camera\":{\"position\":{\"x\":1.000000,\"y\":2.000000,\"z\":3.000000}," +
                "\"lookAt\":{\"x\":0.000000,\"y\":0.000000,\"z\":0.000000}," +
                "\"up\":{\"x\":0.000000,\"y\":1.000000,\"z\":0.000000}}}",
            json,
        )
    }
}
