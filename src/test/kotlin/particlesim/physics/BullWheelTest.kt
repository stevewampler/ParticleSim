package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [BullWheel]: a slewing drive that only twists. */
class BullWheelTest {
    /** Four particles round the y axis at radius 1, turned [degrees], tied into a square by a truss. */
    private fun square(degrees: Double = 0.0): Triple<ParticleStore, Groups, List<Int>> {
        val store = ParticleStore()
        val groups = Groups()
        val a = degrees * PI / 180
        val ids = (0 until 4).map { k ->
            val phi = a + k * PI / 2
            store.create(position = Vector3(cos(phi), 1.0, -sin(phi)), mass = ScalarExpr.of(10.0)).also { groups.add("wheel", it) }
        }
        return Triple(store, groups, ids)
    }

    private fun applied(wheel: BullWheel, store: ParticleStore, groups: Groups, ids: List<Int>, chunks: Int = 1): List<Vector3> {
        val total = ChunkAccumulator(store.capacity)
        for (c in 0 until chunks) {
            val chunk = ChunkAccumulator(store.capacity)
            wheel.accumulate(store, groups, 0.0, chunk, c, chunks)
            chunk.addInto(total)
        }
        return ids.map { total.at(store.slotOf(it)) }
    }

    @Test
    fun `behind its target it twists with k times the lag, as a pure couple, whatever the chunks`() {
        val (store, groups, ids) = square()
        var goal = 10.0
        val wheel = BullWheel("wheel", groups, store, stiffness = 1000.0, damping = 0.0, target = { goal })
        assertEquals(10 * PI / 180, wheel.lag(store), 1e-12)
        assertEquals(1000 * 10 * PI / 180, wheel.torque(store), 1e-9)
        val forces = applied(wheel, store, groups, ids)
        assertTrue(forces.fold(Vector3.ZERO) { s, f -> s + f }.length() < 1e-9, "no net force")
        // Its moment about the axis is the torque, every force tangential and level.
        val moment = ids.indices.sumOf { i -> val p = store.position(ids[i]); p.z * forces[i].x - p.x * forces[i].z }
        assertEquals(wheel.torque(store), moment, 1e-9)
        assertTrue(forces.all { it.y == 0.0 })
        forces.zip(applied(wheel, store, groups, ids, chunks = 5)).forEach { (a, b) -> assertTrue((a - b).length() < 1e-12) }
        // Winding round: 370 degrees is 10 more than once round, and it lags by 10.
        goal = 370.0
        assertEquals(10 * PI / 180, wheel.lag(store), 1e-9)
        groups.setEnabled("wheel", false)
        assertTrue(applied(wheel, store, groups, ids).all { it == Vector3.ZERO })
    }

    @Test
    fun `it measures from where it starts, and its damping follows the target's rate`() {
        val (store, groups, ids) = square(30.0)
        val wheel = BullWheel("wheel", groups, store, stiffness = 1000.0, damping = 50.0, startDegrees = 30.0, target = { 30.0 }, targetRate = { 2.0 })
        assertEquals(0.0, wheel.lag(store), 1e-12, "already where it was told it starts")
        assertEquals(50 * 2 * PI / 180, wheel.torque(store), 1e-9, "only the damping, toward the target's rate")
        // Turning at the target's rate: nothing.
        for (id in ids) {
            val p = store.position(id)
            val w = 2 * PI / 180
            store.setVelocity(id, Vector3(w * p.z, 0.0, -w * p.x))
        }
        assertEquals(2 * PI / 180, wheel.rate(store), 1e-12)
        assertEquals(0.0, wheel.torque(store), 1e-9)
    }

    @Test
    fun `it swings a free wheel round to its target and holds it there`() {
        val (store, groups, ids) = square()
        val members = (0 until 4).map { Truss.Member(ids[it], ids[(it + 1) % 4], 1e-6, 200e9) } + Truss.Member(ids[0], ids[2], 1e-6, 200e9)
        val truss = Truss(store, members)
        // I = 4 m r^2 = 40; k = 400 gives 3.2 rad/s, c = 2 * 0.7 * sqrt(k I) damps it.
        val wheel = BullWheel("wheel", groups, store, stiffness = 400.0, damping = 2 * 0.7 * Math.sqrt(400.0 * 40), target = { 90.0 })
        val integrator = Integrator()
        var t = 0.0
        while (t < 10.0) { integrator.step(store, groups, listOf(truss, wheel), emptyList(), t, 1e-3); t += 1e-3 }
        val p = store.position(ids[0])
        assertEquals(90.0, atan2(-p.z, p.x) * 180 / PI, 0.05)
        val centre = ids.fold(Vector3.ZERO) { s, id -> s + store.position(id) } * 0.25
        assertTrue(abs(centre.x) < 1e-6 && abs(centre.z) < 1e-6, "turned in place: $centre")
    }
}
