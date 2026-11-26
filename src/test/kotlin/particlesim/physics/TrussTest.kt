package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [Truss] and [WeightedAttachment]. */
class TrussTest {
    private val steel = 200e9

    /** The force [force] puts on each particle, summed over [chunkCount] chunks. */
    private fun forcesOf(force: Force, store: ParticleStore, chunkCount: Int = 1): Map<Int, Vector3> {
        val total = ChunkAccumulator(store.capacity)
        for (c in 0 until chunkCount) {
            val chunk = ChunkAccumulator(store.capacity)
            force.accumulate(store, Groups(), 0.0, chunk, c, chunkCount)
            chunk.addInto(total)
        }
        return store.liveIds().associateWith { total.at(store.slotOf(it)) }
    }

    @Test
    fun `a loaded tripod settles to the member forces statics gives`() {
        val store = ParticleStore()
        val groups = Groups()
        val supports = listOf(Vector3(-2.0, 0.0, 0.0), Vector3(2.0, 0.0, -1.0), Vector3(0.5, 0.0, 2.0))
            .map { p -> store.create(position = p).also { groups.add("supports", it) } }
        val apex = store.create(position = Vector3(0.0, -3.0, 0.0), mass = ScalarExpr.of(100.0))
        groups.add("apex", apex)
        val area = 1e-4
        val truss = Truss(store, supports.map { Truss.Member(it, apex, area, steel) }, stiffnessDamping = 1e-4)
        val load = 50e3
        val forces = listOf(truss, ConstantForce("apex", Vector3(1e4, -load, 0.0)), Drag("apex", 2e4))
        val constraints = listOf(FixedPosition.atCurrentPositions("supports", store, groups))
        val integrator = Integrator()
        var t = 0.0
        repeat(20000) { integrator.step(store, groups, forces, constraints, t, 1e-4); t += 1e-4 }

        // Statics: the three member tensions, along each member from the apex, balance the load.
        val p = store.position(apex)
        val u = supports.map { (store.position(it) - p).normalized() }
        val expected = solve3(u, Vector3(-1e4, load, 0.0))
        for (i in 0 until 3) assertEquals(expected[i], truss.axialForce(store, i), 1e-4 * load, "member $i")
        assertEquals(truss.axialForce(store, 0) / area, truss.stress(store, 0), 1e-6)
        // Stiffness EA/L, and the stretch it implies.
        val l0 = truss.restLength(0)
        assertEquals(area * steel / l0, truss.stiffness(0), 1e-6)
        assertEquals(truss.axialForce(store, 0) / truss.stiffness(0), truss.length(store, 0) - l0, 1e-12)
    }

    /** Solves sum(t_i * u_i) = rhs for the three t_i (Cramer's rule). */
    private fun solve3(u: List<Vector3>, rhs: Vector3): List<Double> {
        fun det(a: Vector3, b: Vector3, c: Vector3) = a.dot(b.cross(c))
        val d = det(u[0], u[1], u[2])
        return listOf(det(rhs, u[1], u[2]) / d, det(u[0], rhs, u[2]) / d, det(u[0], u[1], rhs) / d)
    }

    @Test
    fun `a tension-only member goes slack instead of pushing, and its rest length can be paid out`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3.ZERO)
        val b = store.create(position = Vector3(0.0, -10.0, 0.0))
        val rope = Truss(store, listOf(Truss.Member(a, b, 1e-4, steel, tensionOnly = true, restLength = 10.5)))
        assertEquals(0.0, rope.axialForce(store, 0), "slack")
        assertEquals(Vector3.ZERO, forcesOf(rope, store).getValue(b))
        rope.setRestLength(0, 9.0)
        val k = 1e-4 * steel / 9.0
        assertEquals(k * 1.0, rope.axialForce(store, 0), 1e-6)
        assertEquals(Vector3(0.0, k, 0.0), forcesOf(rope, store).getValue(b), "pulls the hook up")
        // A strut pushes back.
        val strut = Truss(store, listOf(Truss.Member(a, b, 1e-4, steel, restLength = 10.5)))
        assertTrue(strut.axialForce(store, 0) < 0.0)
    }

    @Test
    fun `member forces don't depend on how the members are split into chunks, beyond rounding`() {
        val store = ParticleStore()
        val ids = (0 until 6).map { store.create(position = Vector3(it.toDouble(), (it * it % 5).toDouble(), (it % 2).toDouble()), velocity = Vector3(0.1 * it, 0.0, -0.2)) }
        val members = ids.indices.flatMap { i -> ((i + 1) until ids.size).map { j -> Truss.Member(ids[i], ids[j], 1e-4, steel, restLength = 1.0 + 0.1 * i) } }
        val truss = Truss(store, members, stiffnessDamping = 1e-3)
        val one = forcesOf(truss, store, 1)
        val seven = forcesOf(truss, store, 7)
        for (id in ids) assertTrue((one.getValue(id) - seven.getValue(id)).length() < 1e-9 * one.getValue(id).length(), "particle $id")
        // A fixed chunk count (what the integrator uses) is exactly repeatable.
        assertEquals(seven, forcesOf(truss, store, 7))
    }

    @Test
    fun `an attachment holds its particle at the weighted blend, with no net force or torque`() {
        val store = ParticleStore()
        val corners = listOf(Vector3(0.0, 0.0, 0.0), Vector3(2.0, 0.1, 0.0), Vector3(2.0, 0.0, 2.0), Vector3(0.0, 0.3, 2.0))
            .map { store.create(position = it, velocity = Vector3(0.0, 0.5, 0.0)) }
        val p = store.create(position = Vector3(1.2, 1.0, 0.4))
        val attachment = WeightedAttachment(p, stiffness = 1000.0, damping = 10.0)
        val weights = listOf(-0.1, 0.6, 0.6, -0.1)
        attachment.setTarget(corners.zip(weights))
        val target = corners.zip(weights).fold(Vector3.ZERO) { sum, (id, w) -> sum + store.position(id) * w }
        val f = forcesOf(attachment, store)
        val expectedOnParticle = (target - store.position(p)) * 1000.0 + Vector3(0.0, 0.5, 0.0) * 10.0
        assertTrue((f.getValue(p) - expectedOnParticle).length() < 1e-9)
        val net = f.values.fold(Vector3.ZERO) { s, v -> s + v }
        assertTrue(net.length() < 1e-9, "no net force: $net")
        // Without damping the force lies along target - x, so no net torque either.
        store.setVelocity(p, Vector3(0.0, 0.5, 0.0))
        val undamped = WeightedAttachment(p, stiffness = 1000.0).apply { setTarget(corners.zip(weights)) }
        val torque = forcesOf(undamped, store).entries.fold(Vector3.ZERO) { s, (id, force) -> s + store.position(id).cross(force) }
        assertTrue(torque.length() < 1e-9, "no net torque: $torque")
        // Weights must sum to one.
        assertTrue(runCatching { attachment.setTarget(listOf(corners[0] to 0.5)) }.isFailure)
    }
}
