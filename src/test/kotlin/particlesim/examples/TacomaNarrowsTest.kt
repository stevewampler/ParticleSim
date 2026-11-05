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

    /** Seeds the main span's one-node (antisymmetric) twist - the collapse-day mode shape. */
    private fun seedAntisymmetricTwist(s: TacomaNarrowsScenario, rate: Double = 0.05) {
        val main = s.mainSpan
        for ((r, row) in main.withIndex()) {
            val k = rate * sin(2 * PI * r / main.lastIndex)
            val c = s.store.position(row.centre)
            for (id in row.all) {
                val p = s.store.position(id) - c
                s.store.setVelocity(id, s.store.velocity(id) + Vector3(-k * p.y, k * p.x, 0.0))
            }
        }
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
    fun `the towers stay fixed and the collapse-day wind doesn't blow the model up`() {
        val s = buildTacomaNarrows()
        val towerStart = s.towerLegs.flatten().associateWith { s.store.position(it) }
        var maxSpeed = 0.0
        step(s, 15.0) { for (id in s.store.liveIds()) maxSpeed = maxOf(maxSpeed, s.store.velocity(id).length()) }
        assertTrue(maxSpeed < 20.0, "max speed $maxSpeed suggests instability at dt=$TACOMA_DT")
        for ((id, p) in towerStart) assertTrue((s.store.position(id) - p).length() < 1e-9, "tower particle $id moved")
    }

    @Test
    fun `the one-node torsion mode is near the real bridge's 0_2 Hz, Froude-scaled`() {
        // Real: ~0.2 Hz (12 cycles/min). Froude scaling at 1:10 multiplies frequencies by √10.
        val target = 0.2 * sqrt(10.0)
        val s = buildTacomaNarrows(windVelocity = "[0, 0, 0]")
        val t = step(s, 2.0)
        seedAntisymmetricTwist(s)
        val quarter = s.mainSpan[s.mainSpan.size / 4]
        val series = ArrayList<Double>()
        step(s, 25.0, startT = t) { series += twist(s, quarter) }
        val f = dominantFrequency(series, 0.02)
        assertTrue(abs(f - target) < 0.1, "torsion frequency $f Hz, expected about $target Hz")
    }

    @Test
    fun `a steady crosswind leans the deck but doesn't self-excite the twist`() {
        // The finding this scene was built to test: this engine's quasi-steady, linear Wind force
        // gives a static twist plus damping, not the self-excited torsional flutter that
        // destroyed the real bridge. If a future wind model does produce flutter, this should
        // fail - and that would be worth knowing.
        val s = buildTacomaNarrows(windVelocity = "[$TACOMA_FAILURE_WIND, 0, 0]")
        val t = step(s, 3.0)
        seedAntisymmetricTwist(s)
        val quarter = s.mainSpan[s.mainSpan.size / 4]
        val series = ArrayList<Double>()
        step(s, 30.0, startT = t) { series += twist(s, quarter) }
        val third = series.size / 3
        fun amplitude(seg: List<Double>) = seg.average().let { m -> seg.maxOf { abs(it - m) } }
        val early = amplitude(series.subList(0, third))
        val late = amplitude(series.subList(2 * third, series.size))
        assertTrue(late < early, "twist grew from $early to $late rad in a steady wind")
        assertTrue(abs(series.average()) > 0.005, "a crosswind should lean the deck: mean twist ${series.average()} rad")
    }
}
