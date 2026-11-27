package particlesim.examples

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [GantryCrane]: a goliath crane whose legs and girder carry what statics says for a simple beam,
 * travelling on its rails and running its trolley to where it's told with the hook held level. */
class GantryCraneTest {
    private fun runUntil(crane: GantryCrane, from: Double, until: Double, each: (Double) -> Unit = {}): Double {
        var t = from
        while (t < until) { crane.step(t); t += crane.dt; each(t) }
        return t
    }

    private fun settled(crane: GantryCrane): Double = runUntil(crane, 0.0, crane.readyAt + crane.easeSeconds + 1.0)

    /** [sample]'s values averaged over [seconds] of running on from [t]. */
    private fun averaged(crane: GantryCrane, t: Double, seconds: Double = 3.0, sample: () -> DoubleArray): DoubleArray {
        val sum = DoubleArray(sample().size)
        var n = 0
        runUntil(crane, t, t + seconds) { sample().forEachIndexed { i, v -> sum[i] += v }; n++ }
        return DoubleArray(sum.size) { sum[it] / n }
    }

    private fun steel(crane: GantryCrane) = (0 until crane.truss.size).filter { crane.parts[it] != GantryCrane.Part.HOIST_ROPE }

    private fun anythingFailed(crane: GantryCrane) =
        (0 until crane.truss.size).any { crane.truss.hasBuckled(it) || crane.truss.yieldStrain(it) > 0.0 || crane.truss.isTorn(it) }

    @Test
    fun `the girder spans the legs, the feet stand on the rails, and the couplings put things where they are`() {
        val spec = GantryCraneSpec()
        val crane = GantryCrane(spec, travel = 7.0, trolley = -4.0)
        val store = crane.store
        assertEquals(17, spec.girderPanels)
        assertEquals(-spec.halfSpan - spec.girderPanel, store.position(crane.girder.first()[0]).x, 1e-9)
        assertEquals(spec.halfSpan + spec.girderPanel, store.position(crane.girder.last()[0]).x, 1e-9)
        assertEquals(-spec.halfSpan, spec.girderX(spec.rigidLegPanel), 1e-9)
        assertEquals(spec.halfSpan, spec.girderX(spec.flexibleLegPanel), 1e-9)
        for (foot in crane.rigidFeet) assertEquals(-spec.halfSpan, store.position(foot).x)
        for (foot in crane.flexibleFeet) assertEquals(spec.halfSpan, store.position(foot).x)
        assertEquals(7.0, crane.travelPosition, 1e-9)
        assertEquals(spec.girderHeight, store.position(crane.girder[5][0]).y, 1e-9)
        // Each wheel's blend of its rail's ends is where its foot stands; the trolley's blend of
        // the bottom chords is where it hangs.
        for ((i, foot) in crane.feet.withIndex()) {
            val rail = if (i < 2) crane.rigidRail else crane.flexibleRail
            val at = crane.wheelWeights(rail, if (i % 2 == 0) -1 else 1, 7.0).fold(particlesim.core.Vector3.ZERO) { s, (id, w) -> s + store.position(id) * w }
            assertTrue((at - store.position(foot)).length() < 1e-9, "foot $i")
        }
        val trolley = crane.trolleyWeights(-4.0).fold(particlesim.core.Vector3.ZERO) { s, (id, w) -> s + store.position(id) * w }
        assertTrue((trolley - store.position(crane.trolleyNode)).length() < 1e-9)
        // The flexible leg's members all lie across the span, so it can only rock along it.
        val flexible = (0 until crane.truss.size).filter { crane.parts[it] == GantryCrane.Part.FLEXIBLE_LEG || crane.parts[it] == GantryCrane.Part.LEG_BRACE }
        assertEquals(4, flexible.size)
        for (i in flexible) crane.truss.members[i].let { m -> assertEquals(store.position(m.a).x, store.position(m.b).x, 1e-9) }
    }

    @Test
    fun `the legs carry the weight by the lever rule, as for a simple beam, and the flexible leg no thrust`() {
        val spec = GantryCraneSpec()
        val w = spec.maxLoadTonnes * 1000 * STANDARD_GRAVITY
        for (x in listOf(-9.0, 1.0, 9.0)) {
            fun reactions(tonnes: Double): DoubleArray {
                val crane = GantryCrane(spec, trolley = x, loadTonnes = tonnes)
                val total = crane.store.liveIds().filter { !crane.groups.membersOf(GantryCrane.RAILS_GROUP).contains(it) }.sumOf { crane.store.mass(it) } * STANDARD_GRAVITY
                return averaged(crane, settled(crane)) {
                    doubleArrayOf(crane.rigidLegReaction, crane.flexibleLegReaction, crane.rigidLegThrust, crane.flexibleLegThrust, total)
                }
            }
            val empty = reactions(0.0)
            val loaded = reactions(spec.maxLoadTonnes)
            for (r in listOf(empty, loaded)) {
                assertEquals(r[4], r[0] + r[1], r[4] * 1e-3, "the rails hold up the whole crane")
                assertTrue(abs(r[3]) < 0.01 * r[1], "trolley at $x: the flexible leg pushes ${r[3]} N along the span, holding ${r[1]} N")
            }
            // The load's share on the flexible leg, by moments about the rigid leg's feet. Not
            // exact: the flexible leg leans a few millimetres as the girder bends.
            val expected = w * (x + spec.halfSpan) / spec.span
            assertEquals(expected, loaded[1] - empty[1], 0.005 * w, "trolley at $x")
            assertEquals(w - expected, loaded[0] - empty[0], 0.005 * w, "trolley at $x")
        }
    }

    @Test
    fun `the girder's chords at mid-span carry the bending moment over its depth`() {
        val spec = GantryCraneSpec()
        val w = spec.maxLoadTonnes * 1000 * STANDARD_GRAVITY
        // The trolley over the panel point at x = 1, the end of the mid-span panel (-1..1).
        fun chords(tonnes: Double): DoubleArray {
            val crane = GantryCrane(spec, trolley = 1.0, loadTonnes = tonnes)
            return averaged(crane, settled(crane)) {
                doubleArrayOf(
                    crane.midspanBottomChords.sumOf { crane.truss.axialForce(crane.store, it) },
                    crane.midspanTopChords.sumOf { crane.truss.axialForce(crane.store, it) },
                )
            }
        }
        val empty = chords(0.0)
        val loaded = chords(spec.maxLoadTonnes)
        // The load's moment at either end of the panel, the flexible leg taking w (1 + 15) / 30;
        // by sections, each chord's force is the moment at one end over the depth.
        val share = w * (1.0 + spec.halfSpan) / spec.span
        val atLoad = share * (spec.halfSpan - 1.0)
        val atOtherEnd = share * (spec.halfSpan + 1.0) - w * 2.0
        val lo = minOf(atLoad, atOtherEnd) / spec.girderDepth
        val hi = maxOf(atLoad, atOtherEnd) / spec.girderDepth
        val bottom = loaded[0] - empty[0]
        val top = -(loaded[1] - empty[1])
        assertTrue(bottom in lo * 0.98..hi * 1.02, "bottom chords: $bottom N in tension, between $lo and $hi")
        assertTrue(top in lo * 0.98..hi * 1.02, "top chords: $top N in compression, between $lo and $hi")
    }

    @Test
    fun `at its rated load the steel works hard but within its strength`() {
        val spec = GantryCraneSpec()
        var peak = 0.0
        for (x in listOf(spec.minTrolley, 1.0, spec.maxTrolley)) {
            val crane = GantryCrane(spec, trolley = x, loadTonnes = spec.maxLoadTonnes)
            settled(crane)
            for (i in steel(crane)) {
                val s = crane.truss.stress(crane.store, i)
                val strength = if (s < 0) crane.truss.members[i].bucklingStress!! else spec.yieldStress
                peak = maxOf(peak, abs(s))
                assertTrue(abs(s) / strength < 0.7, "trolley at $x: member $i (${crane.parts[i]}) at ${s / 1e6} MPa, ${abs(s) / strength} of its strength")
            }
        }
        assertTrue(peak in 80e6..200e6, "peak ${peak / 1e6} MPa")
    }

    @Test
    fun `the trolley runs across and the hoist lifts, the hook arriving where it's told`() {
        val crane = GantryCrane(loadTonnes = 10.0)
        var t = settled(crane)
        crane.trolley.target = 10.0
        crane.hookHeightTarget = 12.0
        var slack = false
        t = runUntil(crane, t, t + 45.0) { if (crane.truss.axialForce(crane.store, crane.hoistRope) <= 0.0) slack = true }
        assertTrue(!slack, "the rope never goes slack")
        // Averaged over the load's swing, which tugs the trolley about too.
        val period = 2 * PI * sqrt((crane.store.position(crane.trolleyNode).y - 12.0) / STANDARD_GRAVITY)
        val (trolley, x, y) = averaged(crane, t, 2 * period) { doubleArrayOf(crane.trolleyPosition, crane.store.position(crane.hookNode).x, crane.store.position(crane.hookNode).y) }
        assertEquals(10.0, crane.trolley.position, 1e-9)
        // A few centimetres on: the bottom chords it runs on stretch as the girder bends.
        assertEquals(10.0, trolley, 0.06)
        assertEquals(trolley, x, 0.03, "the hook under the trolley")
        assertEquals(12.0, y, 0.05)
    }

    @Test
    fun `the gantry travels along its rails, the steel as it was standing, and the load swings at its pendulum's period when it stops`() {
        val spec = GantryCraneSpec()
        val crane = GantryCrane(spec, travel = -15.0, hookHeight = 6.0, loadTonnes = 20.0)
        var t = settled(crane)
        val legs = (0 until crane.truss.size).filter { crane.parts[it] == GantryCrane.Part.RIGID_LEG || crane.parts[it] == GantryCrane.Part.FLEXIBLE_LEG }
        val ropeLength = crane.store.position(crane.trolleyNode).y - crane.store.position(crane.hookNode).y
        val period = 2 * PI * sqrt(ropeLength / STANDARD_GRAVITY)
        val standing = averaged(crane, t, 2 * period) { DoubleArray(legs.size) { crane.truss.axialForce(crane.store, legs[it]) } }
        t += 2 * period
        crane.travel.target = 15.0
        // Up to speed, then the legs averaged over two of the load's swings at full speed.
        t = runUntil(crane, t, t + 8.0)
        assertEquals(spec.travelSpeed, crane.travel.velocity, 1e-9)
        val travelling = averaged(crane, t, 2 * period) { DoubleArray(legs.size) { crane.truss.axialForce(crane.store, legs[it]) } }
        t += 2 * period
        assertEquals(spec.travelSpeed, crane.travel.velocity, 1e-9, "still at full speed")
        for (i in legs.indices) assertEquals(standing[i], travelling[i], abs(standing[i]) * 0.015, "leg member ${legs[i]}")
        t = runUntil(crane, t, t + 40.0)
        assertEquals(15.0, crane.travelPosition, 0.01)
        // The swing along the rails, hook against trolley: its period from upward crossings.
        val crossings = ArrayList<Double>()
        var last = 0.0
        runUntil(crane, t, t + 5 * period) {
            val dz = crane.store.position(crane.hookNode).z - crane.store.position(crane.trolleyNode).z
            if (last < 0 && dz >= 0) crossings += it
            last = dz
        }
        assertTrue(crossings.size >= 4, "it swings: $crossings")
        val measured = (crossings.last() - crossings.first()) / (crossings.size - 1)
        assertEquals(period, measured, period * 0.01)
        assertEquals(6.0, crane.store.position(crane.hookNode).y, 0.05, "the hook held at its height")
    }

    @Test
    fun `targets are clamped to the crane's reach`() {
        val spec = GantryCraneSpec()
        val crane = GantryCrane(spec)
        crane.travel.target = 500.0
        crane.trolley.target = -500.0
        crane.hookHeightTarget = 500.0
        assertEquals(spec.travelRange, crane.travel.target)
        assertEquals(spec.minTrolley, crane.trolley.target)
        assertEquals(spec.girderHeight - GantryCrane.MIN_ROPE, crane.hookHeightTarget)
        crane.hookHeightTarget = -5.0
        assertEquals(spec.minHookHeight, crane.hookHeightTarget)
    }

    /** Lifts [tonnes] with the trolley at [x] - the load coming on once the crane's ready - and
     * runs [seconds] on. Returns how far the girder's middle dropped. */
    private fun lift(crane: GantryCrane, tonnes: Double, seconds: Double): Double {
        val t = runUntil(crane, 0.0, crane.readyAt + 1.0)
        val middle = crane.girder[crane.spec.girderPanels / 2][0]
        val y = crane.store.position(middle).y
        crane.loadTonnes = tonnes
        runUntil(crane, t, t + seconds)
        return y - crane.store.position(middle).y
    }

    @Test
    fun `with failure on, the crane holds its rated load anywhere across the span`() {
        val spec = GantryCraneSpec()
        for (x in listOf(spec.minTrolley, 1.0, spec.maxTrolley)) {
            val crane = GantryCrane(spec, trolley = x)
            crane.failure = true
            val drop = lift(crane, spec.maxLoadTonnes, 15.0)
            assertTrue(!anythingFailed(crane), "trolley at $x: nothing yields, buckles or tears")
            assertTrue(drop < 0.1, "trolley at $x: the girder just bends, $drop m")
        }
    }

    @Test
    fun `three times the rated load brings the girder down with failure on, and only bends it without`() {
        // Measured: 2.5 times holds, 3 times buckles the top chords in the middle of the span.
        val spec = GantryCraneSpec()
        val failing = GantryCrane(spec, trolley = 1.0).apply { failure = true }
        val fell = lift(failing, 3 * spec.maxLoadTonnes, 30.0)
        assertTrue(fell > 5.0, "the girder came down, $fell m")
        val elastic = GantryCrane(spec, trolley = 1.0)
        val bent = lift(elastic, 3 * spec.maxLoadTonnes, 30.0)
        assertTrue(bent < 0.3 && !anythingFailed(elastic), "elastic, it only bends: $bent m")
    }
}
