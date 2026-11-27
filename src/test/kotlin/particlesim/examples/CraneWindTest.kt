package particlesim.examples

import particlesim.core.Vector3
import particlesim.physics.Truss
import particlesim.physics.TrussWind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [CraneWind] and the wind on the cranes: what the scenes set, where the hook blows to, and the
 * cranes working in it. */
class CraneWindTest {
    @Test
    fun `the wind comes up and turns gradually, the short way round, and blows the way it's pointed`() {
        val w = CraneWind()
        w.speedTarget = 10.0
        w.directionTarget = 300.0
        repeat(100) { w.advance(0.01) }
        assertEquals(CraneWind.SPEED_RAMP * 1.0, w.speed, 1e-9, "a second's worth of ramp")
        assertEquals(-CraneWind.TURN_RATE * 1.0, w.direction, 1e-9, "toward 300 by going down from 0")
        repeat(10000) { w.advance(0.01) }
        assertEquals(10.0, w.speed)
        assertEquals(300.0, w.direction, 1e-9, "and lands on it")
        w.set(12.0, 90.0)
        val v = w.velocity(3.0)
        assertTrue((v - Vector3(0.0, 0.0, -12.0)).length() < 1e-9, "90 degrees blows toward -z, as the cranes slew: $v")
        w.set(12.0, 0.0)
        assertTrue((w.velocity(3.0) - Vector3(12.0, 0.0, 0.0)).length() < 1e-9)
    }

    @Test
    fun `gusts swing the speed about the mean by at most the gust intensity, the same every run`() {
        val w = CraneWind()
        w.set(20.0)
        w.gusts = true
        val speeds = (0 until 20000).map { w.speedAt(it * 0.01) }
        assertTrue(speeds.max() <= 20.0 * (1 + CraneWind.GUST_INTENSITY) + 1e-9 && speeds.min() >= 20.0 * (1 - CraneWind.GUST_INTENSITY) - 1e-9)
        assertTrue(speeds.max() > 20.0 * (1 + 0.8 * CraneWind.GUST_INTENSITY), "it does gust: ${speeds.max()}")
        assertEquals(20.0, speeds.average(), 0.5, "about the mean")
        assertEquals(w.velocity(123.4), CraneWind().also { it.set(20.0); it.gusts = true }.velocity(123.4), "a pure function of time")
    }

    @Test
    fun `member widths come from the same tubes the buckling assumes, rope from its falls`() {
        val area = 60e-4
        assertEquals(CraneWind.CD_CYLINDER * Truss.tubeDiameter(area), CraneWind.tubeDragWidth(area), 1e-12)
        // A = pi t (D - t), t = D/20.
        val d = Truss.tubeDiameter(area)
        assertEquals(area, PI * d / 20 * (d - d / 20), 1e-12)
        // Four falls of 16 mm rope (1.2 cm² each): four 16 mm cylinders side by side.
        assertEquals(CraneWind.CD_CYLINDER * 4 * 0.016, CraneWind.ropeDragWidth(4 * 1.2e-4, 4), 0.02 * CraneWind.CD_CYLINDER * 4 * 0.016)
    }

    private fun run(step: (Double) -> Unit, dt: Double, from: Double, until: Double, each: () -> Unit = {}): Double {
        var t = from
        while (t < until) { step(t); t += dt; each() }
        return t
    }

    @Test
    fun `the wind blows the hook out until its weight balances the wind on it and half its rope`() {
        // A tower crane's empty hook 20 m below the jib in a 25 m/s crosswind. The rope swings out
        // to tan(theta) = horizontal force / weight - the hook block's drag and the half of the
        // rope's that lands on the hook, as the wind force applies them.
        val crane = TowerCrane(radius = 30.0, hookHeight = 20.0)
        crane.wind.set(0.0, 90.0)
        crane.wind.speedTarget = 25.0
        val ready = run(crane::step, crane.dt, 0.0, crane.readyAt + crane.easeSeconds + 6.0)
        val length = crane.truss.length(crane.store, crane.rope)
        val period = 2 * PI * sqrt(length / STANDARD_GRAVITY)
        var n = 0; var tangent = 0.0; var expected = 0.0
        val hookBody = crane.windForce.bodies.indexOfFirst { it.group == TowerCrane.HOOK_GROUP }
        var t = ready
        run(crane::step, crane.dt, ready, ready + 2 * period) {
            t += crane.dt
            val d = crane.store.position(crane.trolleyNode) - crane.store.position(crane.hookNode)
            tangent += d.z / d.y
            val f = crane.windForce.bodyForce(crane.store, crane.groups, t, hookBody) + crane.windForce.memberForce(crane.store, t, crane.rope) * 0.5
            expected += -f.z / (crane.store.mass(crane.hookNode) * STANDARD_GRAVITY)
            n++
        }
        tangent /= n; expected /= n
        assertTrue(expected > 0.05, "a real push: tan theta $expected")
        assertEquals(expected, tangent, 0.03 * expected, "blown downwind (-z) to tan theta $expected")
    }

    /** Each crane at its limits in [speed] m/s of wind from each of [directions]: the worst
     * utilisation of any member, and whether it stayed standing. */
    private data class Case(val name: String, val worst: Double)

    private fun worstIn(name: String, truss: Truss, parts: List<Any>, utilisation: (Int) -> Double): Case {
        val i = (0 until truss.size).maxBy(utilisation)
        return Case("$name (${parts[i]} $i)", utilisation(i))
    }

    @Test
    fun `in a 20 m per second working wind every crane holds its limits within its strength, and no derrick guy goes slack`() {
        val speed = WORKING_WIND
        val cases = ArrayList<Case>()
        for (direction in listOf(0.0, 90.0, 180.0)) {
            val spec = TowerCraneSpec()
            for ((radius, load) in listOf(spec.maxLoadRadius to spec.maxLoadTonnes, spec.maxRadius to spec.tipLoadTonnes)) {
                val c = TowerCrane(spec, radius = radius, loadTonnes = load)
                c.wind.set(0.0, direction); c.wind.speedTarget = speed
                run(c::step, c.dt, 0.0, c.readyAt + c.easeSeconds + 12.0)
                cases += worstIn("tower $radius m $load t from $direction", c.truss, c.parts) { c.truss.utilisation(c.store, it) }
            }
            val ls = LuffingCraneSpec()
            for ((radius, load) in listOf(ls.maxLoadRadius to ls.maxLoadTonnes, ls.maxRadius to ls.tipLoadTonnes)) {
                val c = LuffingCrane(ls, jibAngle = ls.jibAngleFor(radius), loadTonnes = load)
                c.wind.set(0.0, direction); c.wind.speedTarget = speed
                run(c::step, c.dt, 0.0, c.readyAt + c.easeSeconds + 12.0)
                cases += worstIn("luffing $radius m $load t from $direction", c.truss, c.parts) { c.truss.utilisation(c.store, it) }
            }
            val gs = GantryCraneSpec()
            val g = GantryCrane(gs, trolley = 1.0, loadTonnes = gs.maxLoadTonnes)
            g.wind.set(0.0, direction); g.wind.speedTarget = speed
            run(g::step, g.dt, 0.0, g.readyAt + g.easeSeconds + 12.0)
            cases += worstIn("gantry from $direction", g.truss, g.parts) { g.truss.utilisation(g.store, it) }
            val ds = GuyDerrickSpec()
            val d = GuyDerrick(ds, boomAngle = ds.minBoomAngle, hookHeight = 5.0, loadTonnes = ds.maxLoadTonnes)
            d.wind.set(0.0, direction); d.wind.speedTarget = speed
            var slackest = Double.POSITIVE_INFINITY
            val ready = run(d::step, d.dt, 0.0, d.readyAt + d.easeSeconds)
            run(d::step, d.dt, ready, ready + 12.0) { slackest = minOf(slackest, d.guyTensions.min()) }
            cases += worstIn("derrick from $direction (slackest guy ${"%.0f".format(slackest / 1e3)} kN)", d.truss, d.parts) { d.truss.utilisation(d.store, it) }
            assertTrue(slackest > 5e3, "derrick from $direction: a guy down to ${slackest / 1e3} kN")
        }
        for (c in cases) println("${c.name}: ${"%.2f".format(c.worst)}")
        for (c in cases) assertTrue(c.worst < 0.85, "${c.name}: a member at ${c.worst} of its capacity")
    }

    @Test
    fun `wind from behind lowers the crawler's tipping load, and it still tips where it says`() {
        val still = CrawlerCrane(boomAngle = 45.0, hookHeight = 5.0)
        val stillTipping = still.tippingLoadTonnes(run(still::step, still.dt, 0.0, still.readyAt + still.easeSeconds + 1.0))
        fun windy(): Pair<CrawlerCrane, Double> {
            val c = CrawlerCrane(boomAngle = 45.0, hookHeight = 5.0)
            c.wind.set(0.0, 0.0); c.wind.speedTarget = 15.0
            return c to run(c::step, c.dt, 0.0, c.readyAt + c.easeSeconds + 4.0)
        }
        val (probe, tp) = windy()
        val windTipping = probe.tippingLoadTonnes(tp)
        println("tipping over the end: still $stillTipping t, 15 m/s from behind $windTipping t")
        assertTrue(windTipping < stillTipping - 0.5, "from behind it tips with less: $windTipping t vs $stillTipping t")
        for ((fraction, tips) in listOf(0.9 to false, 1.05 to true)) {
            val (c, t) = windy()
            c.loadTonnes = fraction * c.tippingLoadTonnes(t)
            run(c::step, c.dt, t, t + 20.0)
            if (tips) assertTrue(c.tilt > 15.0, "$fraction of the windy tipping load: over, ${c.tilt} degrees")
            else assertTrue(c.tilt < 1.0, "$fraction of the windy tipping load: stands, ${c.tilt} degrees")
        }
    }

    private companion object {
        /** m/s at 10 m: a working limit chosen for these tests (not taken from a standard), steady, no gusts. */
        const val WORKING_WIND = 20.0
    }
}
