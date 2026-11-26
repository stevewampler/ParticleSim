package particlesim.examples

import particlesim.core.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [LuffingCrane]: a luffing-jib crane whose luffing rope and mast carry what statics says, its
 * jib luffing to where it's told with the hook held level. */
class LuffingCraneTest {
    private fun runUntil(crane: LuffingCrane, from: Double, until: Double, each: (Double) -> Unit = {}): Double {
        var t = from
        while (t < until) { crane.step(t); t += crane.dt; each(t) }
        return t
    }

    private fun settled(crane: LuffingCrane): Double = runUntil(crane, 0.0, crane.readyAt + crane.easeSeconds + 1.0)

    /** [sample]'s values averaged over [seconds] of running on from [t]: the jib bounces a little
     * on its rope, a percent or two of its forces. */
    private fun averaged(crane: LuffingCrane, t: Double, seconds: Double = 3.0, sample: () -> DoubleArray): DoubleArray {
        val sum = DoubleArray(sample().size)
        var n = 0
        runUntil(crane, t, t + seconds) { sample().forEachIndexed { i, v -> sum[i] += v }; n++ }
        return DoubleArray(sum.size) { sum[it] / n }
    }

    private fun weight(crane: LuffingCrane, id: Int) = crane.store.mass(id) * STANDARD_GRAVITY

    /** The hoist rope's pull on the jib head. */
    private fun hoistPull(crane: LuffingCrane): Vector3 {
        val store = crane.store
        return (store.position(crane.hookNode) - store.position(crane.jibHead)).normalized() * crane.truss.axialForce(store, crane.hoistRope)
    }

    private fun nonRope(crane: LuffingCrane) = (0 until crane.truss.size).filter {
        crane.parts[it] != LuffingCrane.Part.LUFFING_ROPE && crane.parts[it] != LuffingCrane.Part.HOIST_ROPE
    }

    @Test
    fun `a straight jib's geometry - head radius and height, the luffing rope's length, and the angle for a radius`() {
        val spec = LuffingCraneSpec()
        val crane = LuffingCrane(spec, jibAngle = 40.0)
        val head = crane.store.position(crane.jibHead)
        assertEquals(spec.headRadius(40.0), head.x, 1e-9)
        assertEquals(spec.headHeight(40.0), head.y, 1e-9)
        assertEquals(spec.luffingRopeLength(40.0), (head - crane.store.position(crane.apex)).length(), 1e-9)
        assertEquals(40.0, crane.jibAngle, 1e-9, "measured from the jib as built")
        for (r in listOf(5.0, 20.0, 35.0, 48.0)) assertEquals(r, spec.headRadius(spec.jibAngleFor(r)), 1e-9)
        assertEquals(spec.headRadius(spec.maxJibAngle), spec.minRadius)
        assertEquals(spec.minJibAngle, spec.jibAngleFor(500.0), "clamped")
        assertEquals(spec.maxJibAngle, spec.jibAngleFor(0.0))
        assertTrue(spec.minRadius < 5.0 && spec.maxRadius > 48.0, "${spec.minRadius}..${spec.maxRadius}")
    }

    @Test
    fun `the luffing rope holds up the jib, its head and the load with the tension statics gives`() {
        val spec = LuffingCraneSpec()
        for ((radius, load) in listOf(spec.maxLoadRadius to spec.maxLoadTonnes, spec.maxRadius to spec.tipLoadTonnes)) {
            val crane = LuffingCrane(spec, jibAngle = spec.jibAngleFor(radius), loadTonnes = load)
            val t = settled(crane)
            val store = crane.store
            // Moments about the line through the foot pins: the jib's weight, the head's, and the
            // hoist rope's pull on the head (along the rope - the hook still swings a little from
            // settling), against the luffing rope pulling the head toward the apex.
            val (tension, actual) = averaged(crane, t) {
                val a = store.position(crane.jibFoot[0])
                val axis = (store.position(crane.jibFoot[1]) - a).normalized()
                fun moment(at: Vector3, force: Vector3) = axis.dot((at - a).cross(force))
                val head = store.position(crane.jibHead)
                val carried = crane.jib.flatten() + crane.jibHead
                val gravity = carried.sumOf { moment(store.position(it), Vector3(0.0, -weight(crane, it), 0.0)) } +
                    moment(head, hoistPull(crane))
                doubleArrayOf(-gravity / moment(head, (store.position(crane.apex) - head).normalized()), crane.truss.axialForce(store, crane.luffingRope))
            }
            assertEquals(tension, actual, 0.01 * tension, "$load t at $radius m")
            assertTrue(tension > 250e3, "hundreds of kN: $tension")
        }
    }

    @Test
    fun `the mast's bottom panel carries the crane's weight and moment, leaning back with the jib up and empty, forward at the tip load`() {
        val spec = LuffingCraneSpec()
        for ((angle, load) in listOf(spec.maxJibAngle to 0.0, spec.minJibAngle to spec.tipLoadTonnes)) {
            val crane = LuffingCrane(spec, jibAngle = angle, loadTonnes = load)
            val t = settled(crane)
            val store = crane.store
            // Everything above a cut through the bottom panel but the hook, whose pull on the head
            // is the hoist rope's.
            val above = store.liveIds().filter { store.position(it).y > spec.mastHeight / spec.mastPanels / 2 && it != crane.hookNode }
            val base = crane.mast[0].toSet()
            val cut = (0 until crane.truss.size).filter { val mm = crane.truss.members[it]; (mm.a in base) != (mm.b in base) }
            val (w, m, lift, moment) = averaged(crane, t) {
                val head = store.position(crane.jibHead)
                val rope = hoistPull(crane)
                var lift = 0.0; var moment = 0.0
                for (i in cut) {
                    val mm = crane.truss.members[i]
                    val (lower, upper) = if (mm.a in base) mm.a to mm.b else mm.b to mm.a
                    val pull = (store.position(lower) - store.position(upper)).normalized() * crane.truss.axialForce(store, i)
                    val at = store.position(upper)
                    lift += pull.y
                    moment += at.x * pull.y - at.y * pull.x
                }
                doubleArrayOf(
                    above.sumOf { weight(crane, it) } - rope.y,
                    above.sumOf { weight(crane, it) * store.position(it).x } - (head.x * rope.y - head.y * rope.x),
                    lift, moment,
                )
            }
            assertEquals(w, lift, 0.01 * w, "jib at $angle with $load t: holds up the weight")
            assertEquals(m, moment, 0.02 * abs(m) + 1e3, "jib at $angle with $load t: resists the overturning moment")
            // Balanced like a real crane, about equally far each way.
            if (load == 0.0) assertTrue(m < -1.5e6, "leans back: $m") else assertTrue(m > 1.5e6, "leans forward: $m")
        }
    }

    @Test
    fun `at the load chart's limits the members work hard but stay well below yield`() {
        val spec = LuffingCraneSpec()
        for ((radius, load) in listOf(spec.maxLoadRadius to spec.maxLoadTonnes, spec.maxRadius to spec.tipLoadTonnes)) {
            val crane = LuffingCrane(spec, jibAngle = spec.jibAngleFor(radius), loadTonnes = load)
            settled(crane)
            val peak = nonRope(crane).maxOf { abs(crane.truss.stress(crane.store, it)) }
            assertTrue(peak in 70e6..200e6, "peak ${peak / 1e6} MPa with $load t at $radius m")
            val worst = (0 until crane.truss.size).maxOf { crane.truss.utilisation(crane.store, it) }
            assertTrue(worst < 0.6, "nothing near its capacity, rope or steel: $worst")
        }
    }

    @Test
    fun `luffing brings the hook out and in while the hoist holds it level, and the jib ends up where it was told`() {
        val crane = LuffingCrane(jibAngle = 70.0, hookHeight = 20.0, loadTonnes = 3.0)
        var t = settled(crane)
        for (radius in listOf(40.0, 8.0)) {
            crane.radiusTarget = radius
            assertEquals(radius, crane.radiusTarget, 1e-9)
            var low = Double.MAX_VALUE; var high = -Double.MAX_VALUE; var slack = false
            val start = t
            t = runUntil(crane, t, t + 120.0) {
                val y = crane.store.position(crane.hookNode).y
                low = minOf(low, y); high = maxOf(high, y)
                if (crane.truss.axialForce(crane.store, crane.luffingRope) <= 0.0) slack = true
            }
            assertTrue(!crane.luff.moving && t - start > 30.0, "luffed at its own speed")
            assertTrue(!slack, "the luffing rope never went slack")
            assertTrue(low > 18.5 && high < 21.5, "level luffing: the hook stayed between $low and $high m")
            // The mast leans under the crane, carrying the head with it.
            assertEquals(radius, crane.radius, 0.6, "the jib luffed to $radius m")
            assertEquals(crane.luff.position, crane.jibAngle, 0.3, "and sits at the angle asked for")
            assertEquals(20.0, crane.store.position(crane.hookNode).y, 0.2, "the hook back at its height")
        }
    }

    @Test
    fun `the jib stays up through a slew with it raised`() {
        val crane = LuffingCrane(jibAngle = 84.0, loadTonnes = 3.0)
        var t = settled(crane)
        crane.slew.target = 90.0
        var worst = 0.0
        t = runUntil(crane, t, t + 40.0) { worst = maxOf(worst, abs(crane.jibAngle - 84.0)) }
        assertEquals(90.0, crane.slewDegrees, 1e-9)
        assertTrue(worst < 1.5, "the jib rocked by at most $worst degrees")
        // Turned to +90 (+x toward -z), the head is out along -z.
        val head = crane.store.position(crane.jibHead)
        assertTrue(head.z < -3.0 && abs(head.x) < 0.5, "head at $head")
    }

    @Test
    fun `the load swings with a pendulum's period from the jib head`() {
        val crane = LuffingCrane(jibAngle = 60.0, hookHeight = 50.0)
        var t = settled(crane)
        val length = (crane.store.position(crane.jibHead) - crane.store.position(crane.hookNode)).length()
        crane.store.setVelocity(crane.hookNode, Vector3(0.0, 0.0, 0.3))
        val crossings = ArrayList<Double>()
        var last = crane.store.position(crane.hookNode).z - crane.store.position(crane.jibHead).z
        t = runUntil(crane, t, t + 50.0) { now ->
            val z = crane.store.position(crane.hookNode).z - crane.store.position(crane.jibHead).z
            if (last < 0.0 && z >= 0.0) crossings += now
            last = z
        }
        val periods = crossings.zipWithNext { a, b -> b - a }
        assertTrue(periods.size >= 2, "swung: $crossings")
        assertEquals(2 * PI * sqrt(length / STANDARD_GRAVITY), periods.average(), 0.02 * periods.average())
    }

    @Test
    fun `the targets stay in range, and a load can't be negative`() {
        val crane = LuffingCrane()
        crane.luff.target = 120.0
        assertEquals(crane.spec.maxJibAngle, crane.luff.target)
        crane.radiusTarget = 500.0
        assertEquals(crane.spec.minJibAngle, crane.luff.target)
        assertEquals(crane.spec.maxRadius, crane.radiusTarget, 1e-9)
        crane.hookHeightTarget = -5.0
        assertEquals(crane.spec.minHookHeight, crane.hookHeightTarget)
        crane.hookHeightTarget = 500.0
        assertEquals(crane.spec.headHeight(crane.spec.maxJibAngle) - LuffingCrane.MIN_ROPE, crane.hookHeightTarget)
        assertTrue(runCatching { crane.loadTonnes = -1.0 }.isFailure)
    }

    private fun anythingFailed(crane: LuffingCrane) =
        (0 until crane.truss.size).any { crane.truss.hasBuckled(it) || crane.truss.yieldStrain(it) > 0.0 || crane.truss.isTorn(it) }

    /** Lifts [tonnes] with the jib at [radius] once the crane's ready and runs [seconds] on;
     * returns how far the jib head dropped. */
    private fun lift(crane: LuffingCrane, tonnes: Double, seconds: Double): Double {
        val t = runUntil(crane, 0.0, crane.readyAt + 1.0)
        val head = crane.store.position(crane.jibHead).y
        crane.loadTonnes = tonnes
        runUntil(crane, t, t + seconds)
        return head - crane.store.position(crane.jibHead).y
    }

    @Test
    fun `with failure on, the crane holds its load chart's limits as the load comes on`() {
        val spec = LuffingCraneSpec()
        for ((radius, tonnes) in listOf(spec.maxLoadRadius to spec.maxLoadTonnes, spec.maxRadius to spec.tipLoadTonnes)) {
            val crane = LuffingCrane(spec, jibAngle = spec.jibAngleFor(radius)).apply { failure = true }
            val drop = lift(crane, tonnes, 15.0)
            assertTrue(!anythingFailed(crane), "$tonnes t at $radius m: nothing yields, buckles or tears")
            assertTrue(drop < 3.0, "$tonnes t at $radius m: just bends, $drop m")
        }
    }

    @Test
    fun `with failure on, three times the tip load brings it down, and with failure off it only bends`() {
        val spec = LuffingCraneSpec()
        val tonnes = 3 * spec.tipLoadTonnes
        val failing = LuffingCrane(spec, jibAngle = spec.minJibAngle).apply { failure = true }
        val drop = lift(failing, tonnes, 20.0)
        assertTrue(anythingFailed(failing))
        assertTrue(drop > 10.0, "collapsed: the head fell $drop m")
        assertTrue(failing.store.liveIds().all { failing.store.position(it).y > -0.5 }, "onto the ground, not through it")

        val elastic = LuffingCrane(spec, jibAngle = spec.minJibAngle)
        val bent = lift(elastic, tonnes, 20.0)
        assertTrue(!anythingFailed(elastic))
        assertTrue(bent < 3.0, "only bends: $bent m")
    }

    @Test
    fun `the radius it reports is the head's distance from the mast`() {
        val crane = LuffingCrane(jibAngle = 50.0)
        val head = crane.store.position(crane.jibHead)
        assertEquals(hypot(head.x, head.z), crane.radius, 1e-12)
    }
}
