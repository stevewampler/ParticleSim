package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [GroundContact]: compression-only support with Coulomb friction, reporting what it applies. */
class GroundContactTest {
    private val k = 1e6
    private val m = 100.0
    private val g = 9.81

    private fun pad(position: Vector3, velocity: Vector3 = Vector3.ZERO): Triple<ParticleStore, Groups, Int> {
        val store = ParticleStore()
        val id = store.create(position = position, velocity = velocity, mass = ScalarExpr.of(m))
        val groups = Groups().apply { add("pads", id) }
        return Triple(store, groups, id)
    }

    private fun applied(contact: GroundContact, store: ParticleStore, groups: Groups, id: Int, chunkCount: Int = 1): Vector3 {
        val total = ChunkAccumulator(store.capacity)
        for (c in 0 until chunkCount) {
            val chunk = ChunkAccumulator(store.capacity)
            contact.accumulate(store, groups, 0.0, chunk, c, chunkCount)
            chunk.addInto(total)
        }
        return total.at(store.slotOf(id))
    }

    @Test
    fun `below the ground it pushes up with k pen - c v, never pulls, and nothing above it`() {
        val c = 2 * 0.5 * sqrt(k * m)
        // Pressed 2 mm in, moving down: pushed up harder.
        val (store, groups, id) = pad(Vector3(0.0, -0.002, 0.0), Vector3(0.0, -0.01, 0.0))
        val contact = GroundContact("pads", groups, store, k, dampingRatio = 0.5, friction = 0.5)
        assertEquals(k * 0.002 + c * 0.01, contact.normalForce(store, id), 1e-9)
        assertTrue((contact.force(store, id) - applied(contact, store, groups, id, chunkCount = 3)).length() < 1e-9, "what it reports is what it applies, whatever the chunks")
        // Moving up fast enough that the damping would pull: let go.
        store.setVelocity(id, Vector3(0.0, 1.0, 0.0))
        assertEquals(Vector3.ZERO, applied(contact, store, groups, id))
        assertTrue(!contact.inContact(store, id))
        // Above the ground: nothing.
        store.setPosition(id, Vector3(0.0, 0.01, 0.0))
        store.setVelocity(id, Vector3.ZERO)
        assertEquals(Vector3.ZERO, applied(contact, store, groups, id))
        // A disabled group: nothing.
        store.setPosition(id, Vector3(0.0, -0.002, 0.0))
        groups.setEnabled("pads", false)
        assertEquals(Vector3.ZERO, applied(contact, store, groups, id))
    }

    /** A pad resting under its own weight, then pushed sideways by [push] N for [seconds]:
     * returns how far it moved. */
    private fun pushed(push: Double, seconds: Double): Pair<Double, Double> {
        val (store, groups, id) = pad(Vector3(0.0, -m * g / k, 0.0))
        val contact = GroundContact("pads", groups, store, k, dampingRatio = 1.0, friction = 0.5)
        val weight = UniformGravity("pads", Vector3(0.0, -g, 0.0))
        val integrator = Integrator()
        val dt = 1e-4
        var t = 0.0
        while (t < 0.5) { integrator.step(store, groups, listOf(contact, weight), emptyList(), t, dt); t += dt }
        val x0 = store.position(id).x
        val side = ConstantForce("pads", Vector3(push, 0.0, 0.0))
        while (t < 0.5 + seconds) { integrator.step(store, groups, listOf(contact, weight, side), emptyList(), t, dt); t += dt }
        return (store.position(id).x - x0) to contact.force(store, id).x
    }

    @Test
    fun `friction holds a push below mu N, giving way by about F over k, and lets it slide above`() {
        val mu = 0.5
        val held = mu * m * g * 0.8
        val (creep, holding) = pushed(held, 1.0)
        assertEquals(held / k, creep, held / k * 0.05, "sticks, the tangential spring stretching")
        assertEquals(-held, holding, held * 0.01, "and holds the whole push")
        // Above the limit it slides at (F - mu m g) / m.
        val push = mu * m * g * 1.5
        val (slid, drag) = pushed(push, 1.0)
        val expected = 0.5 * (push - mu * m * g) / m * 1.0 * 1.0
        assertEquals(expected, slid, expected * 0.05, "slides")
        assertEquals(-mu * m * g, drag, mu * m * g * 0.01, "against mu N")
        assertTrue(abs(slid) > 100 * abs(creep))
    }
}
