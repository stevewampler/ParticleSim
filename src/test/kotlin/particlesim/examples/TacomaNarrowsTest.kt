package particlesim.examples

import particlesim.core.Vector3
import particlesim.physics.Integrator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** §13.1/§13.5 plus the model's own dynamics: the 1940 Tacoma Narrows Bridge at 1:10 scale. */
class TacomaNarrowsTest {
    private fun step(s: TacomaNarrowsScenario, seconds: Double, startT: Double = 0.0, sampleEvery: Double = 0.02, onSample: () -> Unit = {}): Double {
        val integrator = Integrator()
        var t = startT
        val every = (sampleEvery / TACOMA_DT).toInt()
        for (i in 0 until (seconds / TACOMA_DT).toInt()) {
            integrator.step(s.store, s.groups, s.forces, s.constraints, t, TACOMA_DT)
            t += TACOMA_DT
            if (i % every == 0) onSample()
        }
        return t
    }

    /** Twist (rad) of a deck row: girder-top height difference over the deck width. */
    private fun twist(s: TacomaNarrowsScenario, row: DeckRow) =
        (s.store.position(row.topLeft).y - s.store.position(row.topRight).y) /
            (s.store.position(row.topRight).x - s.store.position(row.topLeft).x)

    /** A row's tilt angle (rad), valid at any twist. */
    private fun tilt(s: TacomaNarrowsScenario, row: DeckRow) =
        (s.store.position(row.topRight) - s.store.position(row.topLeft)).let { kotlin.math.atan2(it.y, it.x) }

    /** Growth of the one-node twist in a steady wind [u]: settle 15 s so the wind's switch-on
     * transient dies away, seed a small one-node twist, and compare its antisymmetric part
     * ((1/4-span twist − 3/4-span twist)/2) early and late over the next 24 s. */
    private fun oneNodeGrowth(u: Double, flutter: Boolean = true): Pair<Double, Double> {
        val s = buildTacomaNarrows(windVelocity = "[$u, 0, 0]", flutter = flutter)
        val t = step(s, 15.0)
        s.seedOneNodeTwist(0.05)
        val quarter = s.mainSpan[s.mainSpan.size / 4]
        val threeQuarter = s.mainSpan[3 * s.mainSpan.size / 4]
        val series = ArrayList<Double>()
        step(s, 24.0, startT = t) { series += (twist(s, quarter) - twist(s, threeQuarter)) / 2 }
        val third = series.size / 3
        fun amplitude(seg: List<Double>) = seg.average().let { m -> seg.maxOf { abs(it - m) } }
        return amplitude(series.subList(0, third)) to amplitude(series.subList(2 * third, series.size))
    }

    @Test
    fun `with the flutter force the one-node twist decays in a light wind`() {
        val (early, late) = oneNodeGrowth(3.0)
        assertTrue(late < 0.7 * early, "one-node twist went from $early to $late rad at U=3 - should decay below the flutter speed")
    }

    @Test
    fun `with the flutter force the one-node twist grows in the collapse-day wind`() {
        // Calibrated so onset is just below the failure wind (~5.0 vs 5.9): there the twist grows
        // at roughly 7%/s, about 3x over the 16 s between the measurement windows.
        val (early, late) = oneNodeGrowth(TACOMA_FAILURE_WIND)
        assertTrue(late > 1.5 * early, "one-node twist went from $early to $late rad at the failure wind - should grow")
    }

    private fun dominantFrequency(series: List<Double>, dtS: Double): Double {
        val mean = series.average()
        var best = 0.0 to 0.0
        var f = 0.1
        while (f < 2.0) {
            var re = 0.0; var im = 0.0
            for (i in series.indices) { val a = 2 * PI * f * i * dtS; re += (series[i] - mean) * cos(a); im += (series[i] - mean) * sin(a) }
            val amp = sqrt(re * re + im * im)
            if (amp > best.second) best = f to amp
            f += 0.01
        }
        return best.first
    }

    @Test
    fun `the pretensioned bridge starts at its equilibrium shape`() {
        val s = buildTacomaNarrows(windVelocity = "[0, 0, 0]")
        val ids = s.spans.flatten().flatMap { it.all } + s.cables.flatten()
        val start = ids.associateWith { s.store.position(it) }
        var maxDrop = 0.0
        step(s, 3.0) { for (id in ids) maxDrop = maxOf(maxDrop, (s.store.position(id) - start.getValue(id)).length()) }
        // 0.05 units = 0.5 m on a 853 m span with a 70 m cable sag.
        assertTrue(maxDrop < 0.05, "bridge moved $maxDrop from its built shape while settling - pretension is off")
    }

    @Test
    fun `the towers stay fixed and the default gusty wind with flutter levels off instead of blowing up`() {
        // The scene's own setup, run long enough for the flutter to grow and reach its limit cycle
        // (the viewer runs it indefinitely).
        val s = buildTacomaNarrows()
        s.seedOneNodeTwist(TACOMA_SCENE_SEED_RATE)
        val towerStart = s.towerLegs.flatten().associateWith { s.store.position(it) }
        val mid = s.mainSpan[s.mainSpan.size / 2]
        var maxSpeed = 0.0
        var lateTwist = 0.0
        var t = 0.0
        step(s, 120.0) {
            t += 0.02
            for (id in s.store.liveIds()) maxSpeed = maxOf(maxSpeed, s.store.velocity(id).length())
            if (t > 60.0) lateTwist = maxOf(lateTwist, abs(tilt(s, mid)))
        }
        assertTrue(maxSpeed < 5.0, "max speed $maxSpeed - the flutter isn't levelling off")
        assertTrue(lateTwist > 0.2, "mid-span twist only reached $lateTwist rad - the flutter didn't develop")
        assertTrue(lateTwist < 1.0, "mid-span twist reached $lateTwist rad - not a bounded limit cycle")
        for ((id, p) in towerStart) assertTrue((s.store.position(id) - p).length() < 1e-9, "tower particle $id moved")
    }

    @Test
    fun `the one-node torsion mode is near the real bridge's 0_2 Hz, Froude-scaled`() {
        // Real: ~0.2 Hz (12 cycles/min). Froude scaling at 1:10 multiplies frequencies by √10.
        val target = 0.2 * sqrt(10.0)
        val s = buildTacomaNarrows(windVelocity = "[0, 0, 0]")
        val t = step(s, 2.0)
        s.seedOneNodeTwist(0.05)
        val quarter = s.mainSpan[s.mainSpan.size / 4]
        val series = ArrayList<Double>()
        step(s, 25.0, startT = t) { series += twist(s, quarter) }
        val f = dominantFrequency(series, 0.02)
        assertTrue(abs(f - target) < 0.1, "torsion frequency $f Hz, expected about $target Hz")
    }

    @Test
    fun `the plain Wind force alone leans the deck but doesn't self-excite the one-node twist`() {
        // The finding this scene was built to test: this engine's quasi-steady, linear Wind force
        // gives a static lean plus (slightly) more damping, not the self-excited torsional
        // flutter that destroyed the real bridge. Measured properly: settle in the wind first so
        // the wind's own switch-on transient has died away, then seed the one-node twist and
        // track only its antisymmetric part, (twist at 1/4 span - twist at 3/4 span) / 2, which
        // cancels the span-uniform lean and symmetric twist a uniform wind drives. If a future
        // wind model does produce flutter, this should fail - and that would be worth knowing.
        // (Flutter off: this is the plain Wind's own behaviour, which is why TorsionalFlutter exists.)
        val s = buildTacomaNarrows(windVelocity = "[$TACOMA_FAILURE_WIND, 0, 0]", flutter = false)
        val t = step(s, 15.0)
        val quarter = s.mainSpan[s.mainSpan.size / 4]
        val threeQuarter = s.mainSpan[3 * s.mainSpan.size / 4]
        val lean = (twist(s, quarter) + twist(s, threeQuarter)) / 2
        s.seedOneNodeTwist(0.5)
        val series = ArrayList<Double>()
        step(s, 24.0, startT = t) { series += (twist(s, quarter) - twist(s, threeQuarter)) / 2 }
        val third = series.size / 3
        fun amplitude(seg: List<Double>) = seg.average().let { m -> seg.maxOf { abs(it - m) } }
        val early = amplitude(series.subList(0, third))
        val late = amplitude(series.subList(2 * third, series.size))
        // ~10 cycles apart at a log decrement of ~0.06/cycle gives ~0.55 in still air or this
        // wind; 0.7 leaves margin while still failing on anything that isn't clearly decaying.
        assertTrue(late < 0.7 * early, "one-node twist went from $early to $late rad in a steady wind - not decaying")
        assertTrue(abs(lean) > 0.005, "a crosswind should lean the deck: mean twist $lean rad")
    }
}
