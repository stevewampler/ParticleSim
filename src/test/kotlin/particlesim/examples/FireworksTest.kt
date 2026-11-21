package particlesim.examples

import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [FireworksShow]: launches, bursts, burn-out, and a bounded, repeatable display. */
class FireworksTest {
    /** Steps [show] for [seconds] from [start]; returns the time it ends at. */
    private fun run(show: FireworksShow, seconds: Double, start: Double = 0.0, onStep: (Double) -> Unit = {}): Double {
        var t = start
        repeat((seconds / FIREWORKS_DT).toInt()) {
            show.step(t, FIREWORKS_DT)
            t += FIREWORKS_DT
            onStep(t)
        }
        return t
    }

    @Test
    fun `shells keep launching, at the scheduled intervals`() {
        val show = FireworksShow()
        val launchTimes = mutableListOf<Double>()
        var known = emptySet<Int>()
        run(show, 30.0) { t ->
            val ids = show.liveShells.map { it.id }.toSet()
            if ((ids - known).isNotEmpty()) launchTimes += t
            known = ids
        }
        // One launch (or salvo) every 0.6-1.6 s: 30 s holds 18-50 of them.
        assertTrue(launchTimes.size in 18..50, "launches: ${launchTimes.size}")
        val gaps = launchTimes.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it >= show.minLaunchInterval - 1e-9 && it <= show.maxLaunchInterval + FIREWORKS_DT }, "gaps: $gaps")
    }

    @Test
    fun `a shell bursts close to the top of its climb, between the break heights`() {
        val show = FireworksShow(firstLaunchTime = Double.POSITIVE_INFINITY)
        val id = show.launch(0.0, BurstType.PEONY)
        val shell = show.liveShells.single { it.id == id }
        var t = 0.0
        var lastPosition = show.store.position(id)
        while (show.store.contains(id)) {
            lastPosition = show.store.position(id)
            show.step(t, FIREWORKS_DT)
            t += FIREWORKS_DT
        }
        // Burst within one step of the fuse...
        assertTrue(t >= shell.burstTime && t < shell.burstTime + FIREWORKS_DT + 1e-9)
        // ...and, 95% of the way through the climb, within a percent of the planned height.
        assertTrue(lastPosition.y in show.minBreakHeight * 0.98..show.maxBreakHeight + 1.0, "burst at ${lastPosition.y} m")
    }

    @Test
    fun `a burst replaces its shell with a round shell of stars centred where it burst`() {
        val show = FireworksShow(launchHalfWidth = 0.0, firstLaunchTime = Double.POSITIVE_INFINITY)
        val id = show.launch(0.0, BurstType.PEONY)
        var t = 0.0
        var burstAt = Vector3.ZERO
        while (show.store.contains(id)) {
            burstAt = show.store.position(id)
            show.step(t, FIREWORKS_DT)
            t += FIREWORKS_DT
        }
        val stars = show.starColorsById.keys.toList()
        assertEquals(FireworksShow.recipeFor(BurstType.PEONY).count, stars.size)
        // Half a second on, the stars form a shell around the burst point: the centroid stays
        // near it (gravity has pulled everything down a little), and every star is a similar
        // distance out - round, not lopsided.
        run(show, 0.5, start = t)
        val live = stars.filter { show.store.contains(it) }
        val centroid = live.map { show.store.position(it) }.reduce { a, b -> a + b } * (1.0 / live.size)
        assertTrue((centroid - burstAt).length() < 3.0, "centroid ${(centroid - burstAt).length()} m from the burst")
        val radii = live.map { (show.store.position(it) - centroid).length() }
        assertTrue(radii.min() > 0.8 * radii.average() && radii.max() < 1.2 * radii.average(), "radii ${radii.min()}..${radii.max()}")
    }

    @Test
    fun `stars burn out, and the per-star colours go with them`() {
        val show = FireworksShow(firstLaunchTime = Double.POSITIVE_INFINITY)
        val id = show.launch(0.0, BurstType.PEONY)
        var t = 0.0
        while (show.store.contains(id)) { show.step(t, FIREWORKS_DT); t += FIREWORKS_DT }
        val stars = show.starColorsById.keys.toSet()
        // The longest peony burn is 2.4 s.
        run(show, 2.5, start = t)
        assertTrue(stars.none { show.store.contains(it) })
        assertTrue(stars.none { it in show.starColorsById })
    }

    @Test
    fun `the display stays bounded, and its colour bookkeeping matches the live stars`() {
        val show = FireworksShow()
        var peak = 0
        run(show, 120.0) {
            val stars = show.groups.membersOf(FireworksShow.STAR_GROUP)
            assertEquals(stars.toSet(), show.starColorsById.keys)
            peak = maxOf(peak, show.store.size)
        }
        assertTrue(peak < 1500, "peak live particles $peak")
    }

    @Test
    fun `stars droop under gravity and slow under drag`() {
        val show = FireworksShow(launchHalfWidth = 0.0, firstLaunchTime = Double.POSITIVE_INFINITY)
        val id = show.launch(0.0, BurstType.WILLOW)
        var t = 0.0
        while (show.store.contains(id)) { show.step(t, FIREWORKS_DT); t += FIREWORKS_DT }
        val stars = show.starColorsById.keys.toList()
        run(show, 3.0, start = t)
        val live = stars.filter { show.store.contains(it) }
        assertTrue(live.isNotEmpty())
        // A willow star's drag time is 0.5 s; after 3 s it has forgotten its burst speed and
        // falls at close to its terminal speed, g * m / c = 4.9 m/s.
        val terminal = FIREWORKS_GRAVITY * FireworksShow.recipeFor(BurstType.WILLOW).starMass / STAR_DRAG
        for (star in live) {
            val v = show.store.velocity(star)
            assertEquals(-terminal, v.y, 0.3)
            assertTrue(kotlin.math.hypot(v.x, v.z) < 0.5)
        }
    }

    @Test
    fun `the same seed gives the same display`() {
        val a = FireworksShow(seed = 7L)
        val b = FireworksShow(seed = 7L)
        run(a, 10.0)
        run(b, 10.0)
        assertEquals(a.store.liveIds(), b.store.liveIds())
        assertEquals(a.store.liveIds().map { a.store.position(it) }, b.store.liveIds().map { b.store.position(it) })
    }
}
