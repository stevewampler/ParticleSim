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
        assertTrue((attachment.force(store) - expectedOnParticle).length() < 1e-9, "force() reads what it applies")
        assertEquals(Vector3.ZERO, WeightedAttachment(p, stiffness = 1000.0).force(store), "no target, no force")
        // Without damping the force lies along target - x, so no net torque either.
        store.setVelocity(p, Vector3(0.0, 0.5, 0.0))
        val undamped = WeightedAttachment(p, stiffness = 1000.0).apply { setTarget(corners.zip(weights)) }
        val torque = forcesOf(undamped, store).entries.fold(Vector3.ZERO) { s, (id, force) -> s + store.position(id).cross(force) }
        assertTrue(torque.length() < 1e-9, "no net torque: $torque")
        // Weights must sum to one.
        assertTrue(runCatching { attachment.setTarget(listOf(corners[0] to 0.5)) }.isFailure)
    }

    /** One member from a fixed particle at the origin to [b], stretched or squeezed by moving [b]. */
    private fun oneMember(member: (Int, Int) -> Truss.Member): Triple<ParticleStore, Int, Truss> {
        val store = ParticleStore()
        val a = store.create(position = Vector3.ZERO)
        val b = store.create(position = Vector3(2.0, 0.0, 0.0))
        return Triple(store, b, Truss(store, listOf(member(a, b))))
    }

    @Test
    fun `with failure on, a member yields in tension at its yield stress and stays stretched`() {
        val (store, b, truss) = oneMember { a, b -> Truss.Member(a, b, 1e-4, steel, yieldStress = 300e6, ruptureStrain = 0.2) }
        truss.failure = true
        store.setPosition(b, Vector3(2.01, 0.0, 0.0)) // 0.5% strain: 1000 MPa elastic
        assertEquals(Vector3(-30e3, 0.0, 0.0), forcesOf(truss, store).getValue(b), "held at yield")
        assertEquals(30e3, truss.axialForce(store, 0), 1e-6)
        assertEquals(1.0, truss.utilisation(store, 0), 1e-9)
        assertEquals(0.005 - 300e6 / steel, truss.yieldStrain(0), 1e-12, "the stretch past yield is permanent")
        // Let go: it springs back only by its elastic part, and pushes back from the new length.
        store.setPosition(b, Vector3(2.0, 0.0, 0.0))
        assertTrue(truss.axialForce(store, 0) < 0.0, "now too long for the gap: compressed")
        // Off again, it stays as it was but nothing new yields.
        truss.failure = false
        assertTrue(truss.yieldStrain(0) > 0.0)
        store.setPosition(b, Vector3(2.1, 0.0, 0.0))
        assertTrue(truss.axialForce(store, 0) > 30e3, "elastic again")
    }

    @Test
    fun `with failure on, a member buckles in compression and its strength falls away as it folds`() {
        val buckling = 100e6
        val (store, b, truss) = oneMember { a, b -> Truss.Member(a, b, 1e-4, steel, yieldStress = 300e6, bucklingStress = buckling) }
        truss.failure = true
        store.setPosition(b, Vector3(1.998, 0.0, 0.0)) // 0.1% strain: 200 MPa elastic
        assertEquals(-buckling * 1e-4, truss.axialForce(store, 0), 1e-6, "readout capped too")
        forcesOf(truss, store)
        assertTrue(truss.hasBuckled(0))
        // Squeezed on past buckling by the softening strain, it keeps only its residual strength.
        store.setPosition(b, Vector3(2.0 * (1 - 0.0005 - Truss.SOFTENING_STRAIN), 0.0, 0.0))
        repeat(3) { forcesOf(truss, store) }
        assertEquals(Truss.BUCKLED_RESIDUAL * buckling * 1e-4, truss.compressionCapacity(0), 1e-6)
        assertEquals(-Truss.BUCKLED_RESIDUAL * buckling * 1e-4, truss.axialForce(store, 0), 1e-6)
    }

    @Test
    fun `with failure on, a member tears past its rupture strain and a rope snaps at its breaking force`() {
        val (store, b, truss) = oneMember { a, b -> Truss.Member(a, b, 1e-4, steel, yieldStress = 300e6, ruptureStrain = 0.1) }
        truss.failure = true
        store.setPosition(b, Vector3(2.1, 0.0, 0.0))
        forcesOf(truss, store)
        assertTrue(!truss.isTorn(0) && !truss.hasTorn, "5%: yielding, not torn")
        store.setPosition(b, Vector3(2.3, 0.0, 0.0))
        assertEquals(Vector3.ZERO, forcesOf(truss, store).getValue(b))
        assertTrue(truss.isTorn(0) && truss.hasTorn)
        assertEquals(0.0, truss.axialForce(store, 0))
        store.setPosition(b, Vector3(2.0, 0.0, 0.0))
        assertEquals(0.0, truss.axialForce(store, 0), "torn for good")

        val (ropeStore, hook, rope) = oneMember { a, h -> Truss.Member(a, h, 1e-4, 100e9, tensionOnly = true, breakingForce = 50e3) }
        rope.failure = true
        ropeStore.setPosition(hook, Vector3(2.008, 0.0, 0.0)) // 40 kN
        forcesOf(rope, ropeStore)
        assertTrue(!rope.isTorn(0))
        ropeStore.setPosition(hook, Vector3(2.012, 0.0, 0.0)) // 60 kN
        forcesOf(rope, ropeStore)
        assertTrue(rope.isTorn(0), "snapped")
    }

    @Test
    fun `with failure off, members stay elastic however far they're pushed`() {
        val (store, b, truss) = oneMember { a, b -> Truss.Member(a, b, 1e-4, steel, yieldStress = 300e6, bucklingStress = 100e6, ruptureStrain = 0.1) }
        store.setPosition(b, Vector3(3.0, 0.0, 0.0))
        forcesOf(truss, store)
        assertEquals(1e-4 * steel / 2.0, truss.axialForce(store, 0), 1e-3)
        assertTrue(!truss.isTorn(0) && truss.yieldStrain(0) == 0.0)
    }

    @Test
    fun `Rankine buckling sits below both yield and Euler, and a tube's radius of gyration fits its area`() {
        val r = Truss.tubeRadiusOfGyration(25e-4)
        // A 20:1 tube of 25 cm^2 is about 13 cm across, r about 0.34 of that.
        assertTrue(r in 0.04..0.05, "r = $r")
        val stocky = Truss.rankineBucklingStress(355e6, steel, 0.5, r)
        val slender = Truss.rankineBucklingStress(355e6, steel, 8.0, r)
        val euler = kotlin.math.PI * kotlin.math.PI * steel / ((8.0 / r) * (8.0 / r))
        assertTrue(stocky in 0.9 * 355e6..355e6, "stocky: near yield")
        assertTrue(slender < euler && slender > 0.7 * euler, "slender: near Euler")
    }
}
