package particlesim.examples

import particlesim.core.Vector3
import particlesim.physics.Integrator
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [buildMurmuration]: the flock as a whole behaves like a murmuration. */
class MurmurationTest {
    private class Run(val s: MurmurationScenario) {
        val integrator = Integrator()
        var t = 0.0
        fun advance(seconds: Double, each: () -> Unit = {}) {
            repeat((seconds / MURMURATION_DT).toInt()) {
                integrator.step(s.store, s.groups, s.forces, emptyList(), t, MURMURATION_DT)
                t += MURMURATION_DT
                each()
            }
        }
        val positions get() = s.birdIds.map { s.store.position(it) }
        val centroid get() = positions.fold(Vector3.ZERO) { a, p -> a + p } * (1.0 / s.birdIds.size)

        /** The length of the birds' mean heading: 1 all flying the same way, ~0 disordered. */
        val polarization get() = s.birdIds.fold(Vector3.ZERO) { a, id -> a + s.store.velocity(id).normalized() }.length() / s.birdIds.size

        /** The distance from the centroid within which [fraction] of the birds lie. */
        fun spread(fraction: Double): Double {
            val c = centroid
            return positions.map { (it - c).length() }.sorted()[((s.birdIds.size - 1) * fraction).toInt()]
        }
    }

    @Test
    fun `without a falcon the flock holds together, ordered, at cruise speed, spaced as starlings are, over the roost`() {
        val run = Run(buildMurmuration(birdCount = 600, withFalcon = false))
        run.advance(60.0)
        val s = run.s
        assertTrue(run.polarization > 0.9, "polarization ${run.polarization}")
        assertTrue(run.spread(0.95) < 25.0, "95% of birds within ${run.spread(0.95)} m of the centroid")
        val speeds = s.birdIds.map { s.store.velocity(it).length() }
        assertEquals(10.0, speeds.average(), 0.5)
        // Nearest neighbours a metre or two apart - Ballerini et al.'s starlings were ~0.7-1.5 m.
        val ps = run.positions
        val nn = ps.take(100).map { p -> ps.filter { it !== p }.minOf { (it - p).length() } }.average()
        assertTrue(nn in 1.0..2.5, "mean nearest-neighbour distance $nn")
        val c = run.centroid
        val horizontal = sqrt((c.x - s.roost.x) * (c.x - s.roost.x) + (c.z - s.roost.z) * (c.z - s.roost.z))
        assertTrue(horizontal < 100.0, "centroid $horizontal m from the roost")
        assertEquals(s.roost.y, c.y, 5.0)
    }

    @Test
    fun `the falcon's passes scatter the flock, and it re-forms between them`() {
        val run = Run(buildMurmuration(birdCount = 600))
        val s = run.s
        val falcon = assertNotNull(s.falconId)
        var closest = Double.MAX_VALUE
        var widest = 0.0
        run.advance(FALCON_REST_SECONDS + FALCON_HUNT_SECONDS) {
            val f = s.store.position(falcon)
            closest = minOf(closest, s.birdIds.minOf { (s.store.position(it) - f).length() })
            widest = maxOf(widest, run.spread(0.95))
        }
        // Inside the birds' flee radius (25 m by default) - close enough that they scatter.
        assertTrue(closest < 25.0, "the falcon got within $closest m of a bird")
        // A calm flock this size keeps 95% within ~15 m; the pass spreads it well past that.
        assertTrue(widest > 20.0, "widest spread $widest m")
        // Its first rest after that pass is enough to pull the flock back together.
        run.advance(FALCON_REST_SECONDS - 1.0)
        assertTrue(run.spread(0.5) < 20.0, "half the birds within ${run.spread(0.5)} m of the centroid")
    }

    @Test
    fun `the same seed flies the same murmuration`() {
        val a = Run(buildMurmuration(birdCount = 200)).apply { advance(5.0) }
        val b = Run(buildMurmuration(birdCount = 200)).apply { advance(5.0) }
        assertEquals(a.positions, b.positions)
    }
}
