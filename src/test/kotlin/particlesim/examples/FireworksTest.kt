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
    fun `a crossette's comets each split into four pieces flying apart in a cross`() {
        val show = quietShow()
        val (t, _) = burstOne(show, BurstType.CROSSETTE)
        val comets = show.starColorsById.keys.toSet()
        assertEquals(FireworksShow.recipeFor(BurstType.CROSSETTE).count, comets.size)
        // Step until the comets have all split, checking each split the step it happens.
        var now = t
        var splits = 0
        var known = comets
        while (comets.any { show.store.contains(it) }) {
            show.step(now, FIREWORKS_DT)
            now += FIREWORKS_DT
            val fresh = show.starColorsById.keys - known
            known = show.starColorsById.keys.toSet()
            // Pieces from one comet start at exactly the same point.
            for (pieces in fresh.groupBy { show.store.position(it) }.values) {
                assertEquals(4, pieces.size)
                val v = pieces.map { show.store.velocity(it) }
                val carried = v.reduce { a, b -> a + b } * 0.25
                val arms = v.map { it - carried }
                for (arm in arms) {
                    // Each piece leaves at the piece speed, square to the comet's path...
                    assertEquals(FireworksShow.CROSSETTE_PIECE_RECIPE.speed, arm.length(), 1e-6)
                    assertEquals(0.0, arm.dot(carried) / carried.length(), 1e-6)
                }
                // ...in a cross: each arm is opposite one other and square to the remaining two.
                for (arm in arms) {
                    val cosines = arms.filter { it !== arm }.map { it.dot(arm) / (it.length() * arm.length()) }.sorted()
                    assertEquals(-1.0, cosines[0], 1e-6)
                    assertEquals(0.0, cosines[1], 1e-6)
                    assertEquals(0.0, cosines[2], 1e-6)
                }
                splits++
            }
        }
        assertEquals(comets.size, splits)
    }

    @Test
    fun `a strobe burst's stars are marked for blinking, and burn long`() {
        val show = quietShow()
        val (t, _) = burstOne(show, BurstType.STROBE)
        val stars = show.starColorsById.keys.toSet()
        assertEquals(FireworksShow.recipeFor(BurstType.STROBE).count, stars.size)
        assertEquals(stars, show.strobeIds)
        assertTrue(stars.all { show.starColorsById[it] == FireworksShow.STROBE_WHITE })
        val end = run(show, 2.35, start = t)
        assertEquals(stars, stars.filter { show.store.contains(it) }.toSet(), "all still burning at 2.35 s")
        run(show, 1.0, start = end)
        assertTrue(show.strobeIds.isEmpty())
    }

    @Test
    fun `palm fronds rise up and out, then arc over, shedding tail sparks while they burn`() {
        val show = quietShow()
        val shellId = show.launch(0.0, BurstType.PALM)
        var t = 0.0
        var shellVelocity = Vector3.ZERO
        while (show.store.contains(shellId)) {
            shellVelocity = show.store.velocity(shellId)
            show.step(t, FIREWORKS_DT)
            t += FIREWORKS_DT
        }
        val fronds = show.palmFrondIds.toSet()
        assertEquals(FireworksShow.recipeFor(BurstType.PALM).count, fronds.size)
        // Every frond heads upward, tipped 35-70 degrees from the vertical (the shell's own,
        // nearly spent, climb aside).
        for (frond in fronds) {
            val relative = show.store.velocity(frond) - shellVelocity
            val tilt = kotlin.math.acos(relative.y / relative.length())
            assertTrue(tilt in FireworksShow.PALM_MIN_TILT - 0.02..FireworksShow.PALM_MAX_TILT + 0.02, "tilt $tilt")
        }
        // Two seconds on, they've gone over the top and are falling.
        var tails = 0
        var known = show.starColorsById.keys.toSet()
        val end = run(show, 2.1, start = t) {
            val fresh = show.starColorsById.keys - known
            known = show.starColorsById.keys.toSet()
            // Each tail spark starts at a burning frond.
            for (spark in fresh) {
                val p = show.store.position(spark)
                assertTrue(fronds.any { show.store.contains(it) && (show.store.position(it) - p).length() < 1e-9 })
            }
            tails += fresh.size
        }
        assertTrue(fronds.all { show.store.contains(it) && show.store.velocity(it).y < 0.0 }, "burning and falling at 2.1 s")
        // About 0.6 sparks a step from each of 7 fronds over 126 steps: ~530.
        assertTrue(tails in 420..640, "tail sparks: $tails")
        // Once the fronds burn out (2.8 s at most), the tails stop and fade (1.8 s at most).
        run(show, 2.6, start = end)
        assertTrue(show.palmFrondIds.isEmpty())
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

    /** Steps [show] from [start] for [seconds], collecting every sound. */
    private fun soundsOver(show: FireworksShow, seconds: Double, start: Double = 0.0): List<FireworkSound> {
        val sounds = mutableListOf<FireworkSound>()
        var t = start
        repeat((seconds / FIREWORKS_DT).toInt()) {
            sounds += show.step(t, FIREWORKS_DT).sounds
            t += FIREWORKS_DT
        }
        return sounds
    }

    @Test
    fun `every launch sounds at its mortar, and every shell burst booms where it bursts`() {
        val show = FireworksShow()
        val sounds = mutableListOf<FireworkSound>()
        val launchedAt = mutableListOf<Vector3>()
        val burstAt = mutableListOf<Vector3>()
        var t = 0.0
        var lastShells = emptyMap<Int, Vector3>()
        repeat((20.0 / FIREWORKS_DT).toInt()) {
            val result = show.step(t, FIREWORKS_DT)
            t += FIREWORKS_DT
            sounds += result.sounds
            val shells = show.liveShells.associate { it.id to show.store.position(it.id) }
            launchedAt += (shells.keys - lastShells.keys).map { shells.getValue(it) }
            // A shell gone this step burst from where it was a step ago, moved on by one step.
            burstAt += (lastShells.keys - shells.keys).map { lastShells.getValue(it) }
            lastShells = shells
        }
        val launches = sounds.filter { it.noise == FireworkNoise.LAUNCH }
        assertEquals(launchedAt, launches.map { it.position })
        assertTrue(launches.all { it.loudness == 1.0 && it.position.y == FireworksShow.MORTAR_HEIGHT })
        val shellBooms = sounds.filter { it.noise == FireworkNoise.BOOM && it.loudness == 1.0 }
        assertEquals(burstAt.size, shellBooms.size)
        for ((boom, at) in shellBooms.zip(burstAt)) assertTrue((boom.position - at).length() < 2.0)
        // Each sound carries the time it happened: within the run, never decreasing.
        assertEquals(sounds.map { it.time }.sorted(), sounds.map { it.time })
    }

    @Test
    fun `split and crossette breaks give quieter booms, and each crackling star a crackle`() {
        for ((type, loudness) in listOf(
            BurstType.SPLIT to FireworksShow.SPLIT_BOOM_LOUDNESS,
            BurstType.CROSSETTE to FireworksShow.CROSSETTE_BOOM_LOUDNESS,
        )) {
            val show = quietShow()
            val (t, _) = burstOne(show, type)
            val small = soundsOver(show, 1.0, start = t).filter { it.noise == FireworkNoise.BOOM }
            assertEquals(FireworksShow.recipeFor(type).count, small.size, "$type breaks")
            assertTrue(small.all { it.loudness == loudness })
        }

        val show = quietShow()
        val (t, _) = burstOne(show, BurstType.CRACKLE)
        val glitter = show.glitterIds.toSet()
        val crackles = soundsOver(show, 2.0, start = t).filter { it.noise == FireworkNoise.CRACKLE }
        assertEquals(glitter.size, crackles.size)
        assertTrue(glitter.none { show.store.contains(it) })
    }

    @Test
    fun `about 30% of shells whistle for about a second, stopping just before they burst`() {
        val show = FireworksShow()
        val sounds = mutableListOf<FireworkSound>()
        val shells = mutableMapOf<Int, Shell>()
        var t = 0.0
        repeat((120.0 / FIREWORKS_DT).toInt()) {
            sounds += show.step(t, FIREWORKS_DT).sounds
            t += FIREWORKS_DT
            for (s in show.liveShells) shells[s.id] = s
        }
        // Only shells that have burst, so every whistle has had its chance to sound.
        val done = shells.values.filter { it.burstTime < t }
        val whistlers = done.filter { it.whistleStart != null }
        val share = whistlers.size.toDouble() / done.size
        assertTrue(share in 0.2..0.4, "whistling share $share of ${done.size}")
        for (s in whistlers) {
            val length = s.whistleEnd!! - s.whistleStart!!
            val gap = s.burstTime - s.whistleEnd!!
            assertTrue(length in FireworksShow.WHISTLE_MIN_LENGTH..FireworksShow.WHISTLE_MAX_LENGTH)
            assertTrue(gap in FireworksShow.WHISTLE_MIN_GAP..FireworksShow.WHISTLE_MAX_GAP)
        }
        // One whistle sound per whistling shell, at its start, from where the shell then was.
        val whistles = sounds.filter { it.noise == FireworkNoise.WHISTLE && it.time < t - 2.0 }
        val expected = whistlers.filter { it.whistleStart!! < t - 2.0 }
        assertEquals(expected.map { it.whistleStart!! }.sorted(), whistles.map { it.time }.sorted())
        for (w in whistles) {
            val s = expected.single { it.whistleStart == w.time }
            assertEquals(s.whistleEnd!! - s.whistleStart!!, w.duration, 1e-12)
            assertTrue(w.position.y > 20.0, "whistling high up the climb, at ${w.position.y} m")
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
