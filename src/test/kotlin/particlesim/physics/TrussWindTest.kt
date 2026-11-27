package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [TrussWind]: drag across slender members, on bluff bodies, with height. */
class TrussWindTest {
    private val rho = TrussWind.AIR_DENSITY
    private val cdD = 0.24
    private val wind = Vector3(20.0, 0.0, 0.0)

    private class Rig(val store: ParticleStore, val groups: Groups, val truss: Truss, val ids: List<Int>)

    /** One member from [a] to [b], plus a lone particle "box" at (0, 5, 5). */
    private fun rig(a: Vector3, b: Vector3, velocity: Vector3 = Vector3.ZERO): Rig {
        val store = ParticleStore()
        val ia = store.create(position = a, velocity = velocity, mass = ScalarExpr.of(10.0))
        val ib = store.create(position = b, velocity = velocity, mass = ScalarExpr.of(10.0))
        val box = store.create(position = Vector3(0.0, 5.0, 5.0), mass = ScalarExpr.of(10.0))
        val groups = Groups().apply { add("box", box) }
        val truss = Truss(store, listOf(Truss.Member(ia, ib, 1e-3, 200e9, ruptureStrain = 0.1)))
        return Rig(store, groups, truss, listOf(ia, ib, box))
    }

    private fun windOn(r: Rig, bodies: List<TrussWind.Body> = emptyList(), exponent: Double = 0.0) =
        TrussWind(r.truss, r.store, r.groups, doubleArrayOf(cdD), bodies, VectorExpr.of(wind), profileExponent = exponent)

    private fun applied(w: TrussWind, r: Rig, chunkCount: Int = 1): List<Vector3> {
        val total = ChunkAccumulator(r.store.capacity)
        for (c in 0 until chunkCount) {
            val chunk = ChunkAccumulator(r.store.capacity)
            w.accumulate(r.store, r.groups, 0.0, chunk, c, chunkCount)
            chunk.addInto(total)
        }
        return r.ids.map { total.at(r.store.slotOf(it)) }
    }

    private fun close(expected: Vector3, actual: Vector3, tol: Double = 1e-9) =
        assertTrue((expected - actual).length() <= tol * maxOf(1.0, expected.length()), "expected $expected, was $actual")

    @Test
    fun `a member across the wind feels half rho Cd D L v squared, half at each end`() {
        val r = rig(Vector3(0.0, 0.0, 0.0), Vector3(0.0, 0.0, 4.0))
        val w = windOn(r)
        val full = 0.5 * rho * cdD * 4.0 * 20.0 * 20.0
        close(Vector3(full, 0.0, 0.0), w.memberForce(r.store, 0.0, 0))
        val (fa, fb, _) = applied(w, r)
        close(Vector3(full / 2, 0.0, 0.0), fa)
        close(Vector3(full / 2, 0.0, 0.0), fb)
    }

    @Test
    fun `edge-on it feels nothing, and at an angle sin squared of it, along its normal`() {
        val along = rig(Vector3.ZERO, Vector3(4.0, 0.0, 0.0))
        close(Vector3.ZERO, windOn(along).memberForce(along.store, 0.0, 0))
        val theta = PI / 6
        val r = rig(Vector3.ZERO, Vector3(4 * cos(theta), 0.0, 4 * sin(theta)))
        val f = windOn(r).memberForce(r.store, 0.0, 0)
        val full = 0.5 * rho * cdD * 4.0 * 20.0 * 20.0
        assertEquals(full * sin(theta).pow(2), f.length(), 1e-9 * full)
        val e = Vector3(cos(theta), 0.0, sin(theta))
        assertEquals(0.0, f.dot(e), 1e-9 * full, "normal to the member")
        assertTrue(f.x > 0.0, "and downwind")
    }

    @Test
    fun `it acts on the wind relative to the member - none moving with it, four times moving against it - and still air does nothing`() {
        val r = rig(Vector3.ZERO, Vector3(0.0, 0.0, 4.0), velocity = wind)
        close(Vector3.ZERO, windOn(r).memberForce(r.store, 0.0, 0))
        val against = rig(Vector3.ZERO, Vector3(0.0, 0.0, 4.0), velocity = wind * -1.0)
        close(Vector3(4 * 0.5 * rho * cdD * 4.0 * 400.0, 0.0, 0.0), windOn(against).memberForce(against.store, 0.0, 0))
        // Still air leaves even a moving member alone (its drag is nothing beside its damping).
        val still = TrussWind(against.truss, against.store, against.groups, doubleArrayOf(cdD), listOf(TrussWind.Body("box") { 2.0 }))
        close(Vector3.ZERO, still.totalForce(against.store, against.groups, 0.0))
        assertTrue(applied(still, against).all { it == Vector3.ZERO })
    }

    @Test
    fun `a body feels half rho CdA v squared, read live, and nothing while disabled`() {
        val r = rig(Vector3.ZERO, Vector3(4.0, 0.0, 0.0))
        var area = 3.0
        val w = windOn(r, listOf(TrussWind.Body("box") { area }))
        close(Vector3(0.5 * rho * 3.0 * 400.0, 0.0, 0.0), applied(w, r)[2])
        area = 6.0
        close(Vector3(0.5 * rho * 6.0 * 400.0, 0.0, 0.0), applied(w, r)[2])
        r.groups.setEnabled("box", false)
        close(Vector3.ZERO, applied(w, r)[2])
    }

    @Test
    fun `higher up it blows harder by the power law`() {
        val r = rig(Vector3(0.0, 40.0, 0.0), Vector3(0.0, 40.0, 4.0))
        val w = windOn(r, exponent = 0.14)
        assertEquals(4.0.pow(0.14), w.profile(40.0), 1e-12)
        assertEquals(0.1.pow(0.14), w.profile(0.0), 1e-12, "floored at a metre")
        val v = 20.0 * 4.0.pow(0.14)
        close(Vector3(0.5 * rho * cdD * 4.0 * v * v, 0.0, 0.0), w.memberForce(r.store, 0.0, 0))
    }

    @Test
    fun `what it reports is what it applies, whatever the chunks, and torn members feel nothing`() {
        val r = rig(Vector3(0.0, 2.0, 0.0), Vector3(1.0, 6.0, 3.0), velocity = Vector3(1.0, -2.0, 0.5))
        val w = windOn(r, listOf(TrussWind.Body("box") { 2.0 }), exponent = 0.14)
        val one = applied(w, r)
        for (chunks in listOf(2, 3, 8)) applied(w, r, chunks).zip(one).forEach { (a, b) -> close(b, a, 1e-12) }
        close(w.totalForce(r.store, r.groups, 0.0), one.fold(Vector3.ZERO) { s, f -> s + f })
        // Each force where it acts: the member's at its midpoint, the body's at its particle.
        val at = ArrayList<Vector3>()
        w.forEachForce(r.store, r.groups, 0.0) { p, _ -> at += p }
        close(Vector3(0.5, 4.0, 1.5), at[0]); close(Vector3(0.0, 5.0, 5.0), at[1])
        // Pull it apart with failure on: it tears, and the wind lets it go.
        r.truss.failure = true
        r.store.setPosition(r.ids[1], Vector3(1.0, 9.0, 3.0))
        r.truss.accumulate(r.store, r.groups, 0.0, ChunkAccumulator(r.store.capacity), 0, 1)
        assertTrue(r.truss.isTorn(0))
        close(Vector3.ZERO, w.memberForce(r.store, 0.0, 0))
        close(Vector3.ZERO, applied(w, r)[0])
    }
}
