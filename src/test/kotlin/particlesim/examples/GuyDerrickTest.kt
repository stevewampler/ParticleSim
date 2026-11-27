package particlesim.examples

import particlesim.core.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [GuyDerrick]: a mast pinned at its foot and held at its top by guys, swung by a bull wheel,
 * its boom luffed by a topping lift - carrying what statics says. */
class GuyDerrickTest {
    private fun runUntil(d: GuyDerrick, from: Double, until: Double, each: (Double) -> Unit = {}): Double {
        var t = from
        while (t < until) { d.step(t); t += d.dt; each(t) }
        return t
    }

    private fun settled(d: GuyDerrick): Double = runUntil(d, 0.0, d.readyAt + d.easeSeconds + 2.0)

    /** [sample] averaged over [seconds] from [t]. */
    private fun averaged(d: GuyDerrick, t: Double, seconds: Double = 3.0, sample: () -> List<Vector3>): List<Vector3> {
        var sum: List<Vector3>? = null
        var n = 0
        runUntil(d, t, t + seconds) { val s = sample(); sum = sum?.zip(s) { a, b -> a + b } ?: s; n++ }
        return sum!!.map { it * (1.0 / n) }
    }

    @Test
    fun `a straight boom's geometry, the topping lift's length, the guys all round, and a slewed start`() {
        val spec = GuyDerrickSpec()
        val d = GuyDerrick(spec, boomAngle = 40.0)
        val tip = d.store.position(d.boomTip)
        assertEquals(spec.tipRadius(40.0), tip.x, 1e-9)
        assertEquals(spec.tipHeight(40.0), tip.y, 1e-9)
        assertEquals(40.0, d.boomAngle, 1e-9)
        assertEquals(spec.toppingLiftLength(40.0), (d.store.position(d.topPin) - tip).length(), 1e-9)
        for (r in listOf(8.0, 20.0, 28.0)) assertEquals(r, spec.tipRadius(spec.boomAngleFor(r)), 1e-9)
        assertEquals(spec.minBoomAngle, spec.boomAngleFor(500.0))
        assertEquals(Vector3.ZERO, d.store.position(d.foot))
        assertEquals(spec.mastHeight, d.store.position(d.topPin).y)
        assertEquals(spec.guyCount, d.anchors.size)
        assertTrue(d.anchors.all { abs(hypot(d.store.position(it).x, d.store.position(it).z) - spec.guyAnchorRadius) < 1e-9 })
        // Swung 90 degrees, the boom points along -z.
        val turned = GuyDerrick(spec, slewDegrees = 90.0, boomAngle = 40.0)
        val p = turned.store.position(turned.boomTip)
        assertEquals(-spec.tipRadius(40.0), p.z, 1e-9)
        assertEquals(0.0, p.x, 1e-9)
    }

    @Test
    fun `the boom clears every guy across its whole luff, swung any way`() {
        val spec = GuyDerrickSpec()
        val top = Vector3(0.0, spec.mastHeight, 0.0)
        fun distanceToGuy(p: Vector3, k: Int): Double {
            val a = spec.anchor(k)
            val u = (a - top).normalized()
            val w = p - top
            return (w - u * w.dot(u)).length()
        }
        var least = Double.POSITIVE_INFINITY
        var angle = spec.minBoomAngle
        while (angle <= spec.maxBoomAngle + 1e-9) {
            val a = angle * PI / 180
            for (slew in 0 until 360 step 3) {
                val s = slew * PI / 180
                // The boom's upper chords (the side facing the guys), from foot to tip.
                for (i in 0..30) {
                    val along = spec.boomLength * i / 30
                    for (side in listOf(-spec.boomWidth / 2, spec.boomWidth / 2)) {
                        val x = spec.halfWidth + along * cos(a)
                        val y = spec.boomFootHeight + along * sin(a)
                        val p = Vector3(x * cos(s) + side * sin(s), y, -x * sin(s) + side * cos(s))
                        for (k in 0 until spec.guyCount) least = minOf(least, distanceToGuy(p, k))
                    }
                }
            }
            angle += 1.0
        }
        assertTrue(least > 2.0, "the boom comes within ${"%.2f".format(least)} m of a guy")
    }

    @Test
    fun `the guys hold the top against the moment of everything about the foot pin, and the foot carries the rest`() {
        // The mast is pinned at its foot, so about the foot pin the only moments are the weights'
        // and the guys' pull at the top pin; and the foot pin, the guys and the weights balance.
        for ((slew, tonnes) in listOf(0.0 to 0.0, 0.0 to 20.0, 60.0 to 12.0)) {
            val d = GuyDerrick(slewDegrees = slew, boomAngle = 30.0, hookHeight = 5.0, loadTonnes = tonnes)
            val weighed = d.groups.membersOf(GuyDerrick.DERRICK_GROUP) + d.groups.membersOf(GuyDerrick.HOOK_GROUP)
            val (gravityMoment, guys, top, foot, weight) = averaged(d, settled(d)) {
                var m = Vector3.ZERO; var w = Vector3.ZERO
                for (id in weighed) {
                    val f = Vector3(0.0, -d.store.mass(id) * STANDARD_GRAVITY, 0.0)
                    m += d.store.position(id).cross(f); w += f
                }
                listOf(m, d.guyForce, d.store.position(d.topPin), d.footThrust, w)
            }
            val guyMoment = top.cross(guys)
            val horizontal = { v: Vector3 -> hypot(v.x, v.z) }
            val residual = gravityMoment + guyMoment
            assertTrue(horizontal(residual) < 0.01 * horizontal(gravityMoment), "slew $slew, $tonnes t: moments ${gravityMoment} and $guyMoment")
            val balance = foot + guys + weight
            assertTrue(balance.length() < 0.005 * foot.length(), "slew $slew, $tonnes t: forces foot $foot guys $guys weight $weight")
            assertTrue(foot.y > -weight.y, "the guys pull the mast down onto its foot too")
        }
    }

    @Test
    fun `a load's thrust on the boom is the triangle of forces at the tip, nearly the same at any radius, while the topping lift's grows`() {
        val ratios = ArrayList<Double>()
        val lifts = ArrayList<Double>()
        for (angle in listOf(25.0, 70.0)) {
            fun measure(tonnes: Double): List<Vector3> {
                val d = GuyDerrick(boomAngle = angle, hookHeight = 5.0, loadTonnes = tonnes)
                return averaged(d, settled(d)) {
                    val pins = (d.store.position(d.boomFoot[0]) + d.store.position(d.boomFoot[1])) * 0.5
                    listOf(d.boomThrust, pins, d.store.position(d.boomTip), d.store.position(d.topPin), Vector3(d.truss.axialForce(d.store, d.toppingLift), 0.0, 0.0))
                }
            }
            val empty = measure(0.0)
            val loaded = measure(20.0)
            val dw = 20e3 * STANDARD_GRAVITY
            // At the tip: the extra load (down), the extra topping-lift pull (toward the top pin)
            // and the boom's extra thrust (out along it from its pins) balance.
            val (_, pins, tip, top) = loaded
            val boom = (tip - pins).normalized()
            val lift = (top - tip).normalized()
            // Solve dw*(0,-1) + T*lift + C*boom = 0 in the boom's vertical plane.
            val bh = hypot(boom.x, boom.z); val lh = -hypot(lift.x, lift.z)
            val det = lh * boom.y - lift.y * bh
            val c = (lh * dw) / det
            val t = -(bh * dw) / det
            val thrust = loaded[0] - empty[0]
            assertEquals(c, thrust.length(), 0.03 * c, "boom at $angle: thrust")
            assertTrue(thrust.normalized().dot(boom * -1.0) > 0.999, "along the boom, onto its pins")
            assertEquals(t, loaded[4].x - empty[4].x, 0.03 * t, "boom at $angle: topping lift")
            ratios += c / dw
            lifts += t
        }
        assertEquals(ratios[0], ratios[1], 0.05 * ratios[0], "thrust per tonne the same at 25 and 70 degrees")
        assertTrue(lifts[0] > 2 * lifts[1], "the topping lift works far harder with the boom down: ${lifts[0]} vs ${lifts[1]}")
    }

    @Test
    fun `at its rated load, the boom right down and swung any way, no guy goes slack and nothing works near its strength`() {
        val spec = GuyDerrickSpec()
        for ((slew, angle) in listOf(0.0 to spec.minBoomAngle, 22.5 to spec.minBoomAngle, 135.0 to spec.minBoomAngle, 0.0 to 50.0, 0.0 to spec.maxBoomAngle)) {
            val d = GuyDerrick(spec, slewDegrees = slew, boomAngle = angle, hookHeight = 5.0)
            val t = settled(d)
            d.loadTonnes = d.ratedLoadTonnes()
            var slackest = Double.POSITIVE_INFINITY
            runUntil(d, t, t + 10.0) { slackest = minOf(slackest, d.guyTensions.min()) }
            assertTrue(slackest > 5e3, "slew $slew, boom $angle: a guy down to ${slackest / 1e3} kN")
            val worst = (0 until d.truss.size).maxBy { d.truss.utilisation(d.store, it) }
            assertTrue(d.truss.utilisation(d.store, worst) < 0.6, "slew $slew, boom $angle: member $worst (${d.parts[worst]}) at ${d.truss.utilisation(d.store, worst)}")
        }
    }

    @Test
    fun `the bull wheel swings mast and boom together, the hook following`() {
        val d = GuyDerrick(boomAngle = 50.0, hookHeight = 10.0, loadTonnes = 5.0)
        val t = settled(d)
        d.slew.target = 90.0
        runUntil(d, t, t + 45.0)
        assertEquals(90.0, d.slewDegrees, 1e-9)
        val tip = d.store.position(d.boomTip)
        assertEquals(-90.0, atan2(tip.z, tip.x) * 180 / PI, 0.3, "the boom swung toward -z")
        // Across opposite corners, so the mast's lean doesn't count.
        val across = d.store.position(d.mast[5][1]) - d.store.position(d.mast[5][3])
        assertEquals(-45.0 - 90.0, atan2(across.z, across.x) * 180 / PI, 0.3, "and the mast with it")
        val hook = d.store.position(d.hookNode)
        assertTrue(hypot(hook.x - tip.x, hook.z - tip.z) < 0.5, "the hook under the tip: $hook, $tip")
        assertTrue(abs(d.bullWheel.lag(d.store)) < 1e-3, "the bull wheel holds it there")
    }

    @Test
    fun `it luffs to where it's told with the hook held level`() {
        val d = GuyDerrick(boomAngle = 60.0, hookHeight = 10.0, loadTonnes = 5.0)
        var t = settled(d)
        d.radiusTarget = 25.0
        var lowest = Double.POSITIVE_INFINITY; var highest = 0.0
        t = runUntil(d, t, t + 40.0) {
            val y = d.store.position(d.hookNode).y
            lowest = minOf(lowest, y); highest = maxOf(highest, y)
        }
        assertTrue(lowest > 9.0 && highest < 11.0, "the hook held near 10 m: $lowest..$highest")
        val (r) = averaged(d, runUntil(d, t, t + 25.0), 6.0) { listOf(Vector3(d.radius, d.boomAngle, d.store.position(d.hookNode).y)) }
        assertEquals(25.0, r.x, 0.5)
        assertEquals(d.spec.boomAngleFor(25.0), r.y, 0.3)
        assertEquals(10.0, r.z, 0.2)
    }

    @Test
    fun `targets are clamped to the derrick's reach`() {
        val spec = GuyDerrickSpec()
        val d = GuyDerrick(spec)
        d.luff.target = 120.0
        assertEquals(spec.maxBoomAngle, d.luff.target)
        d.radiusTarget = 500.0
        assertEquals(spec.minBoomAngle, d.luff.target)
        d.hookHeightTarget = 500.0
        assertEquals(spec.tipHeight(spec.maxBoomAngle) - GuyDerrick.MIN_ROPE, d.hookHeightTarget)
        d.hookHeightTarget = -5.0
        assertEquals(spec.minHookHeight, d.hookHeightTarget)
    }

    @Test
    fun `with failure on it holds its rated load, and three times it brings it down`() {
        val spec = GuyDerrickSpec()
        val d = GuyDerrick(spec, boomAngle = spec.minBoomAngle, hookHeight = 5.0)
        d.failure = true
        val t = settled(d)
        d.loadTonnes = d.ratedLoadTonnes()
        runUntil(d, t, t + 12.0)
        assertTrue((0 until d.truss.size).none { d.truss.hasBuckled(it) || d.truss.yieldStrain(it) > 0.0 || d.truss.isTorn(it) }, "nothing fails at the rated load")
        val over = GuyDerrick(spec, boomAngle = spec.minBoomAngle, hookHeight = 5.0)
        over.failure = true
        val t2 = settled(over)
        val tipBefore = over.store.position(over.boomTip).y
        over.loadTonnes = 3 * over.ratedLoadTonnes()
        runUntil(over, t2, t2 + 15.0)
        val failed = (0 until over.truss.size).filter { over.truss.hasBuckled(it) || over.truss.isTorn(it) }
        println("three times the rated load: ${failed.map { over.parts[it] }.distinct()} failed; tip at ${over.store.position(over.boomTip)}")
        assertTrue(failed.isNotEmpty(), "something gives")
        assertTrue(over.store.position(over.boomTip).y < tipBefore - 3.0, "and the boom comes down from $tipBefore m")
    }
}
