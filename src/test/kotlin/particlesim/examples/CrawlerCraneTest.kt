package particlesim.examples

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [CrawlerCrane]: a crawler crane standing on the ground - held up where statics says, tipping
 * when its centre of mass passes over the pads' edge, over the side before over the end. */
class CrawlerCraneTest {
    private fun runUntil(crane: CrawlerCrane, from: Double, until: Double, each: (Double) -> Unit = {}): Double {
        var t = from
        while (t < until) { crane.step(t); t += crane.dt; each(t) }
        return t
    }

    private fun settled(crane: CrawlerCrane): Double = runUntil(crane, 0.0, crane.readyAt + crane.easeSeconds + 1.0)

    private fun averaged(crane: CrawlerCrane, t: Double, seconds: Double = 3.0, sample: () -> DoubleArray): DoubleArray {
        val sum = DoubleArray(sample().size)
        var n = 0
        runUntil(crane, t, t + seconds) { sample().forEachIndexed { i, v -> sum[i] += v }; n++ }
        return DoubleArray(sum.size) { sum[it] / n }
    }

    /** How hard each member works: rope against its breaking force, steel against its strength
     * (buckling in compression, yield in tension). */
    private fun utilisation(crane: CrawlerCrane, i: Int): Double {
        val m = crane.truss.members[i]
        val s = crane.truss.stress(crane.store, i)
        val breaking = m.breakingForce
        return if (breaking != null) crane.truss.axialForce(crane.store, i) / breaking
        else abs(s) / (if (s < 0) m.bucklingStress!! else crane.spec.yieldStress)
    }

    private fun anythingFailed(crane: CrawlerCrane) =
        (0 until crane.truss.size).any { crane.truss.hasBuckled(it) || crane.truss.yieldStrain(it) > 0.0 || crane.truss.isTorn(it) }

    @Test
    fun `a straight boom's geometry, the pendants and rope making up the line, and a slewed start`() {
        val spec = CrawlerCraneSpec()
        val crane = CrawlerCrane(spec, boomAngle = 50.0)
        val store = crane.store
        val tip = store.position(crane.boomTip)
        assertEquals(spec.tipRadius(50.0), tip.x, 1e-9)
        assertEquals(spec.tipHeight(50.0), tip.y, 1e-9)
        assertEquals(50.0, crane.boomAngle, 1e-9)
        assertEquals(spec.boomLineLength(50.0), (store.position(crane.apex) - tip).length(), 1e-9)
        assertEquals(spec.pendantLength, (store.position(crane.bail) - tip).length(), 1e-9)
        for (r in listOf(10.0, 25.0, 40.0)) assertEquals(r, spec.tipRadius(spec.boomAngleFor(r)), 1e-9)
        assertEquals(spec.minBoomAngle, spec.boomAngleFor(500.0))
        // The pads: 20 of them, on the ground, the outer ones on the tipping lines.
        assertEquals(20, crane.pads.size)
        assertTrue(crane.pads.all { store.position(it).y == 0.0 })
        assertEquals(spec.padLineZ, crane.pads.maxOf { store.position(it).z }, 1e-9)
        assertEquals(spec.trackLength / 2, crane.pads.maxOf { store.position(it).x }, 1e-9)
        // Slewed 90 degrees, the boom points along -z.
        val turned = CrawlerCrane(spec, slewDegrees = 90.0, boomAngle = 50.0)
        val p = turned.store.position(turned.boomTip)
        assertEquals(-spec.tipRadius(50.0), p.z, 1e-9)
        assertEquals(0.0, p.x, 1e-9)
    }

    @Test
    fun `the ground holds up its weight with the push centred under its centre of mass`() {
        // Friction acts along the ground, so it has no moment about it: the pads' reactions
        // balance the weight and centre under the centre of mass, load and all.
        for ((slew, tonnes) in listOf(0.0 to 0.0, 90.0 to 0.0, 0.0 to 10.0, 135.0 to 10.0)) {
            val crane = CrawlerCrane(slewDegrees = slew, boomAngle = 45.0, hookHeight = 5.0, loadTonnes = tonnes)
            val a = averaged(crane, settled(crane)) {
                val (px, pz) = crane.centreOfPressure
                val (cx, cz) = crane.centreOfMass
                doubleArrayOf(crane.padReactions.sum(), px, pz, cx, cz, crane.totalMass * STANDARD_GRAVITY)
            }
            assertEquals(a[5], a[0], a[5] * 1e-3, "slew $slew, $tonnes t")
            assertEquals(a[3], a[1], 0.02, "slew $slew, $tonnes t: x")
            assertEquals(a[4], a[2], 0.02, "slew $slew, $tonnes t: z")
        }
    }

    @Test
    fun `empty with its boom right up, the counterweight doesn't tip it backward however it's slewed`() {
        val spec = CrawlerCraneSpec()
        for (slew in listOf(90.0, 135.0, 180.0)) {
            val crane = CrawlerCrane(spec, slewDegrees = slew, boomAngle = spec.maxBoomAngle, hookHeight = 5.0)
            val t = settled(crane)
            val (px, pz) = crane.centreOfPressure
            val margin = minOf(spec.trackLength / 2 - abs(px), spec.padLineZ - abs(pz))
            assertTrue(margin > 1.0, "slew $slew: ${"%.2f".format(margin)} m inside the pads")
            runUntil(crane, t, t + 5.0)
            assertTrue(crane.tilt < 0.1, "slew $slew: level, ${crane.tilt} degrees")
        }
    }

    /** Settles at [slew] with the boom at 45 degrees, then hangs [fraction] of the tipping load
     * on it and runs on [seconds]. Returns the crane. */
    private fun loaded(slew: Double, fraction: Double, seconds: Double = 20.0): CrawlerCrane {
        val crane = CrawlerCrane(slewDegrees = slew, boomAngle = 45.0, hookHeight = 5.0)
        val t = settled(crane)
        crane.loadTonnes = fraction * crane.tippingLoadTonnes()
        runUntil(crane, t, t + seconds)
        return crane
    }

    @Test
    fun `it holds just under its tipping load and tips just over it, over the end and over the side`() {
        // Measured: 0.9 holds and 1.05 tips, both ways, the load coming on over a few seconds. The boom's sag and the pads' squash move
        // things a little, so not closer than that.
        for (slew in listOf(0.0, 90.0)) {
            val holding = loaded(slew, 0.9)
            assertTrue(holding.tilt < 1.0, "slew $slew, 0.9 of the tipping load: stands, tilted ${holding.tilt} degrees")
            val tipped = loaded(slew, 1.05)
            assertTrue(tipped.tilt > 15.0, "slew $slew, 1.05 of the tipping load: over, tilted ${tipped.tilt} degrees")
        }
        // Over the side the pads' edge is nearer: it tips with less.
        val end = CrawlerCrane(boomAngle = 45.0, hookHeight = 5.0).also { settled(it) }.tippingLoadTonnes()
        val side = CrawlerCrane(slewDegrees = 90.0, boomAngle = 45.0, hookHeight = 5.0).also { settled(it) }.tippingLoadTonnes()
        assertTrue(side < 0.9 * end, "over the side $side t, over the end $end t")
    }

    @Test
    fun `a load it holds over the end tips it when it slews over the side`() {
        val crane = CrawlerCrane(boomAngle = 45.0, hookHeight = 5.0)
        var t = settled(crane)
        val end = crane.tippingLoadTonnes()
        crane.loadTonnes = 0.85 * end
        t = runUntil(crane, t, t + 15.0)
        assertTrue(crane.tilt < 1.0, "over the end it stands: ${crane.tilt} degrees")
        crane.slew.target = 90.0
        runUntil(crane, t, t + 40.0)
        assertTrue(crane.tilt > 15.0, "over the side it goes over: ${crane.tilt} degrees")
        val (_, z) = crane.centreOfMass
        assertTrue(z < -crane.spec.padLineZ, "toward -z, where the boom went: centre of mass at z = $z")
    }

    @Test
    fun `at its rated loads the steel and rope work within their strength`() {
        val spec = CrawlerCraneSpec()
        for ((slew, angle) in listOf(0.0 to 30.0, 90.0 to 30.0, 90.0 to 60.0, 90.0 to 75.0)) {
            val crane = CrawlerCrane(spec, slewDegrees = slew, boomAngle = angle, hookHeight = 5.0)
            val t = settled(crane)
            val rated = crane.ratedLoadTonnes()
            assertTrue(rated <= spec.maxLoadTonnes)
            crane.loadTonnes = rated
            runUntil(crane, t, t + 12.0)
            for (i in 0 until crane.truss.size) {
                val u = utilisation(crane, i)
                assertTrue(u < 0.6, "slew $slew, boom $angle, $rated t: member $i (${crane.parts[i]}) at $u")
            }
            assertTrue(crane.tilt < 0.5, "and it stands")
        }
    }

    @Test
    fun `it luffs to where it's told with the hook held level`() {
        val crane = CrawlerCrane(boomAngle = 60.0, hookHeight = 10.0, loadTonnes = 5.0)
        var t = settled(crane)
        crane.radiusTarget = 35.0
        var lowest = Double.POSITIVE_INFINITY; var highest = 0.0
        t = runUntil(crane, t, t + 40.0) {
            val y = crane.store.position(crane.hookNode).y
            lowest = minOf(lowest, y); highest = maxOf(highest, y)
        }
        assertTrue(lowest > 9.0 && highest < 11.0, "the hook held near 10 m: $lowest..$highest")
        // Then the trim catches up with the boom's sag.
        val (radius, angle, hook) = averaged(crane, runUntil(crane, t, t + 30.0), 6.0) {
            doubleArrayOf(crane.radius, crane.boomAngle, crane.store.position(crane.hookNode).y)
        }
        assertEquals(crane.spec.boomAngleFor(35.0), angle, 0.3)
        assertEquals(35.0, radius, 0.5)
        assertEquals(10.0, hook, 0.2)
    }

    @Test
    fun `it slews, the boom turning with the upperworks`() {
        val crane = CrawlerCrane(boomAngle = 60.0, hookHeight = 10.0, loadTonnes = 5.0)
        val t = settled(crane)
        crane.slew.target = 45.0
        runUntil(crane, t, t + 20.0)
        assertEquals(45.0, crane.slewDegrees, 1e-9)
        val tip = crane.store.position(crane.boomTip)
        assertEquals(-45.0, Math.toDegrees(kotlin.math.atan2(tip.z, tip.x)), 0.5, "the boom turned toward -z")
        assertTrue(crane.tilt < 0.5, "and it stands")
    }

    @Test
    fun `targets are clamped to the crane's reach`() {
        val spec = CrawlerCraneSpec()
        val crane = CrawlerCrane(spec)
        crane.luff.target = 120.0
        assertEquals(spec.maxBoomAngle, crane.luff.target)
        crane.radiusTarget = 500.0
        assertEquals(spec.minBoomAngle, crane.luff.target)
        crane.hookHeightTarget = 500.0
        assertEquals(spec.tipHeight(spec.maxBoomAngle) - CrawlerCrane.MIN_ROPE, crane.hookHeightTarget)
        crane.hookHeightTarget = -5.0
        assertEquals(spec.minHookHeight, crane.hookHeightTarget)
    }

    @Test
    fun `with failure on, it holds its rated loads`() {
        for ((slew, angle) in listOf(90.0 to 30.0, 90.0 to 75.0)) {
            val crane = CrawlerCrane(slewDegrees = slew, boomAngle = angle, hookHeight = 5.0)
            crane.failure = true
            val t = settled(crane)
            crane.loadTonnes = crane.ratedLoadTonnes()
            runUntil(crane, t, t + 15.0)
            assertTrue(!anythingFailed(crane), "slew $slew, boom $angle: nothing yields, buckles or tears")
        }
    }

    @Test
    fun `the radius readout is the tip's distance from the slewing axis`() {
        val crane = CrawlerCrane(boomAngle = 45.0)
        val tip = crane.store.position(crane.boomTip)
        assertEquals(hypot(tip.x, tip.z), crane.radius, 1e-9)
    }
}
