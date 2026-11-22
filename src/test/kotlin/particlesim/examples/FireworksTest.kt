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

    /** Launches one [type] shell straight up from the origin and steps until it bursts;
     * returns the time and where it burst. */
    private fun burstOne(show: FireworksShow, type: BurstType): Pair<Double, Vector3> {
        val id = show.launch(0.0, type)
        var t = 0.0
        var at = Vector3.ZERO
        while (show.store.contains(id)) {
            at = show.store.position(id)
            show.step(t, FIREWORKS_DT)
            t += FIREWORKS_DT
        }
        return t to at
    }

    private fun quietShow() = FireworksShow(launchHalfWidth = 0.0, firstLaunchTime = Double.POSITIVE_INFINITY)

    @Test
    fun `the launch speed and fuse match a ball rising against quadratic drag`() {
        // Integrate dv/dt = -g - (k/m) v² finely from launchSpeedFor(h): it stops at h, after
        // timeToApex.
        for (h in listOf(90.0, 140.0)) {
            var v = FireworksShow.launchSpeedFor(h)
            val apexTime = FireworksShow.timeToApex(v)
            var y = 0.0
            var t = 0.0
            val dt = 1e-5
            while (v > 0.0) {
                v -= (FIREWORKS_GRAVITY + SHELL_DRAG / FireworksShow.SHELL_MASS * v * v) * dt
                y += v * dt
                t += dt
            }
            assertEquals(h, y, 0.01)
            assertEquals(apexTime, t, 1e-3)
        }
        // Drag means launching well above the drag-free sqrt(2gh), and topping out sooner.
        val v140 = FireworksShow.launchSpeedFor(140.0)
        assertTrue(v140 > 1.5 * kotlin.math.sqrt(2 * FIREWORKS_GRAVITY * 140.0), "launch speed $v140")
        assertTrue(FireworksShow.timeToApex(v140) < v140 / FIREWORKS_GRAVITY)
    }

    @Test
    fun `a rising shell feels drag, and without it overshoots its break height`() {
        val show = quietShow()
        val id = show.launch(0.0, BurstType.PEONY)
        val v0 = show.store.velocity(id).y
        show.step(0.0, FIREWORKS_DT)
        val deceleration = (v0 - show.store.velocity(id).y) / FIREWORKS_DT
        val expected = FIREWORKS_GRAVITY + SHELL_DRAG / FireworksShow.SHELL_MASS * v0 * v0
        assertEquals(expected, deceleration, expected * 0.03)

        // Unchecking the shells group takes the drag (not gravity) away: launched at drag
        // speed, the shell climbs far past where it was aimed.
        val free = quietShow()
        free.groups.setEnabled(FireworksShow.SHELL_GROUP, false)
        val freeId = free.launch(0.0, BurstType.PEONY)
        val breakHeight = free.liveShells.single().breakHeight
        var t = 0.0
        var at = Vector3.ZERO
        while (free.store.contains(freeId)) {
            at = free.store.position(freeId)
            free.step(t, FIREWORKS_DT)
            t += FIREWORKS_DT
        }
        assertTrue(at.y > 1.3 * breakHeight, "aimed at $breakHeight m, burst at ${at.y} m")
    }

    @Test
    fun `a ring burst's stars spread in one flat circle`() {
        val show = quietShow()
        val (t, burstAt) = burstOne(show, BurstType.RING)
        val stars = show.starColorsById.keys.toList()
        assertEquals(FireworksShow.recipeFor(BurstType.RING).count, stars.size)
        run(show, 0.5, start = t)
        val points = stars.map { show.store.position(it) }
        val centroid = points.reduce { a, b -> a + b } * (1.0 / points.size)
        assertTrue((centroid - burstAt).length() < 3.0)
        val offsets = points.map { it - centroid }
        val radius = offsets.map { it.length() }.average()
        // The ring's plane: its normal is perpendicular to every offset, so take the cross
        // product of two offsets a quarter-turn apart.
        val normal = offsets[0].cross(offsets[stars.size / 4]).normalized()
        for (o in offsets) {
            assertTrue(kotlin.math.abs(o.dot(normal)) < 0.12 * radius, "off the plane by ${o.dot(normal)} m (radius $radius)")
            assertEquals(radius, o.length(), 0.12 * radius)
        }
    }

    @Test
    fun `crackling stars twinkle, and pop into brief sparks where they burn out`() {
        val show = quietShow()
        val (t, _) = burstOne(show, BurstType.CRACKLE)
        val glitter = show.starColorsById.keys.toSet()
        assertEquals(glitter, show.glitterIds)
        // Step until the first glitter star burns out, watching where it was.
        var now = t
        var last = glitter.associateWith { show.store.position(it) }
        while (glitter.all { show.store.contains(it) }) {
            last = glitter.associateWith { show.store.position(it) }
            show.step(now, FIREWORKS_DT)
            now += FIREWORKS_DT
        }
        val gone = glitter.filter { !show.store.contains(it) }
        val sparks = show.starColorsById.keys - glitter
        assertEquals(gone.size * FireworksShow.CRACKLE_RECIPE.count, sparks.size)
        assertTrue(sparks.none { it in show.glitterIds })
        assertTrue(gone.none { it in show.glitterIds })
        // Each spark starts where a burnt-out star was (moved on by at most one step).
        for (spark in sparks) {
            val p = show.store.position(spark)
            assertTrue(gone.any { (last.getValue(it) - p).length() < 1.0 }, "spark at $p")
        }
        // Sparks last at most 0.16 s.
        run(show, 0.2, start = now)
        assertTrue(sparks.none { show.store.contains(it) })
    }

    @Test
    fun `a split shell bursts into sub-shells, which burst again into small peonies`() {
        val show = quietShow()
        val (t, burstAt) = burstOne(show, BurstType.SPLIT)
        val subShells = show.starColorsById.keys.toSet()
        assertEquals(FireworksShow.recipeFor(BurstType.SPLIT).count, subShells.size)
        assertTrue(subShells.all { show.starColorsById[it] == FireworksShow.SUBSHELL_COLOR })
        // The longest sub-shell fuse is 0.95 s.
        val end = run(show, 1.0, start = t)
        assertTrue(subShells.none { show.store.contains(it) })
        val secondary = show.starColorsById.keys.toList()
        assertEquals(subShells.size * FireworksShow.SPLIT_STAR_RECIPE.count, secondary.size)
        assertTrue(secondary.none { show.starColorsById[it] == FireworksShow.SUBSHELL_COLOR })
        // The small bursts sit out around the first: on average ~20 m from it.
        val spread = secondary.map { (show.store.position(it) - burstAt).length() }.average()
        assertTrue(spread in 10.0..35.0, "secondary stars average $spread m from the first burst")
        run(show, 1.5, start = end)
        assertTrue(show.starColorsById.isEmpty())
    }

    @Test
    fun `every shell burst is remembered briefly, for the ground flash`() {
        val show = quietShow()
        val (t, burstAt) = burstOne(show, BurstType.PEONY)
        val flash = show.recentFlashes.single()
        assertEquals(t, flash.time, 1e-9)
        assertTrue((flash.position - burstAt).length() < 2.0)
        assertEquals(show.liveShells.size, 0)
        run(show, FireworksShow.FLASH_MEMORY + 0.05, start = t)
        assertTrue(show.recentFlashes.isEmpty())
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
