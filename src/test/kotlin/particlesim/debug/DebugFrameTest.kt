package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.physics.PairwiseForce
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §9.1/§10.2: the only piece of the Phase 3 debug renderer that's meaningfully testable
 * without a browser — the actual dots/lines rendering can only be confirmed by looking.
 */
class DebugFrameTest {

    private class FakeConnection(override val particleA: Int, override val particleB: Int) : PairwiseForce

    @Test
    fun `renders particle positions and connection endpoints as JSON`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3(1.0, 2.0, 3.0))
        val b = store.create(position = Vector3(-1.5, 0.0, 2.25))

        val json = DebugFrame.render(1.5, store, listOf(a, b), listOf(FakeConnection(a, b)))

        assertEquals(
            "{\"t\":1.500000,\"particles\":[" +
                "{\"id\":0,\"x\":1.000000,\"y\":2.000000,\"z\":3.000000}," +
                "{\"id\":1,\"x\":-1.500000,\"y\":0.000000,\"z\":2.250000}" +
                "],\"connections\":[{\"a\":0,\"b\":1}]}",
            json,
        )
    }

    @Test
    fun `renders empty particle and connection lists correctly`() {
        val store = ParticleStore()
        val json = DebugFrame.render(0.0, store, emptyList(), emptyList())
        assertEquals("{\"t\":0.000000,\"particles\":[],\"connections\":[]}", json)
    }
}
