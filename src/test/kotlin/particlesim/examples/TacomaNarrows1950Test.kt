package particlesim.examples

import particlesim.physics.Integrator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The 1950 Tacoma Narrows replacement bridge ([buildTacomaNarrows1950]) at 1:10 scale. */
class TacomaNarrows1950Test {
    private fun step(s: TacomaNarrows1950Scenario, integrator: Integrator, from: Double, seconds: Double, every: Int = 10, onSample: () -> Unit = {}): Double {
        var t = from
        for (i in 0 until (seconds / TACOMA_DT).toInt()) {
            integrator.step(s.store, s.groups, s.forces, s.constraints, t, TACOMA_DT)
            t += TACOMA_DT
            if (i % every == 0) onSample()
        }
        return t
    }

    private fun tilt(s: TacomaNarrows1950Scenario, row: DeckRow) =
        (s.store.position(row.topRight) - s.store.position(row.topLeft)).let { atan2(it.y, it.x) }

    /** The main span's twist projected onto `sin(halfWaves·πz/L)`, sampled every 10 ms for
     * [seconds] after a 10 s settle and a small seed in that shape. */
    private fun twistSeries(s: TacomaNarrows1950Scenario, halfWaves: Int, seconds: Double): List<Double> {
        val integrator = Integrator()
        val t = step(s, integrator, 0.0, 10.0)
        s.seedTwist(halfWaves, 0.05)
        val n = s.mainSpan.lastIndex
        val series = ArrayList<Double>()
        step(s, integrator, t, seconds) { series += s.mainSpan.withIndex().sumOf { (r, row) -> sin(halfWaves * PI * r / n) * tilt(s, row) } }
        return series
    }

    /** Growth rate (per second) of a twist in [halfWaves]' shape in a steady wind [u]: compare
     * the amplitude over the first and last quarters of 16 s. */
    private fun twistGrowth(u: Double, halfWaves: Int): Double {
        val series = twistSeries(buildTacomaNarrows1950(windVelocity = "[$u, 0, 0]"), halfWaves, 16.0)
        val q = series.size / 4
        fun amplitude(seg: List<Double>) = seg.average().let { m -> seg.maxOf { abs(it - m) } }
        return ln(amplitude(series.subList(3 * q, series.size)) / amplitude(series.subList(0, q))) / 12.0
    }

    private fun dominantFrequency(series: List<Double>, dtS: Double): Double {
        val mean = series.average()
        var best = 0.0 to 0.0
        var f = 0.2
        while (f < 6.0) {
            var re = 0.0; var im = 0.0
            for (i in series.indices) { val a = 2 * PI * f * i * dtS; re += (series[i] - mean) * cos(a); im += (series[i] - mean) * sin(a) }
            val amp = sqrt(re * re + im * im)
            if (amp > best.second) best = f to amp
            f += 0.01
        }
        return best.first
    }

    @Test
    fun `it is built to the 1950 bridge's real dimensions`() {
        val s = buildTacomaNarrows1950()
        val mid = s.mainSpan[s.mainSpan.size / 2]
        fun p(id: Int) = s.store.position(id)
        assertEquals(1.829, p(mid.topRight).x - p(mid.topLeft).x, 1e-9, "60 ft between the cables and trusses")
        assertEquals(1.006, p(mid.topLeft).y - p(mid.bottomLeft).y, 1e-9, "33 ft deep trusses")
        assertEquals(87, s.mainSpan.size - 1, "2,800 ft in (nearly) 32 ft panels - 87.5 rounds down")
        assertEquals(listOf(34, 34), listOf(s.spans[0].size - 1, s.spans[2].size - 1), "1,100 ft side spans in 32 ft panels")
        assertEquals(85.34 / 87, p(s.mainSpan[1].centre).z - p(s.mainSpan[0].centre).z, 1e-9)
        // Tower tops 307 ft above the roadway, the cable 280 ft (1:10) lower at mid-span.
        val towerTop = s.towerLegs[0].last()
        assertEquals(9.357, p(towerTop).y, 1e-9)
        val midCable = s.cables[0][s.spans[0].size - 1 + s.mainSpan.size / 2]
        assertEquals(9.357 - 8.534, p(midCable).y, 3e-3, "(the nearest panel point to mid-span - 87 panels)")
        assertEquals(2 * (86 + 2 * 33), s.hangerConnections.size, "a suspender at every interior panel point, both sides")
    }

    @Test
    fun `it starts at equilibrium and holds still in still air`() {
        val s = buildTacomaNarrows1950(windVelocity = "[0, 0, 0]", flutter = false)
        val mid = s.mainSpan[s.mainSpan.size / 2]
        val y0 = s.store.position(mid.centre).y
        var fastest = 0.0
        step(s, Integrator(), 0.0, 8.0, every = 50) { fastest = maxOf(fastest, s.mainSpan.maxOf { r -> s.store.velocity(r.centre).length() }) }
        assertTrue(abs(s.store.position(mid.centre).y - y0) < 0.005, "mid-span moved ${s.store.position(mid.centre).y - y0} settling")
        assertTrue(fastest < 0.1, "deck reached $fastest units/s in still air")
    }

    @Test
    fun `its torsion modes are what the flutter force is told, and far stiffer than 1940's`() {
        for ((n, expected) in listOf(1 to TACOMA_1950_TORSION_MODE_FREQUENCIES[0], 2 to TACOMA_1950_TORSION_MODE_FREQUENCIES[1])) {
            val f = dominantFrequency(twistSeries(buildTacomaNarrows1950(windVelocity = "[0, 0, 0]", flutter = false), n, 20.0), 0.01)
            assertTrue(abs(f - expected) < 0.05, "mode $n measured at $f Hz, the flutter force uses $expected")
        }
        // The softest twist mode: 1940's one-node 0.645 Hz, 1950's whole-span 1.25 Hz.
        assertTrue(TACOMA_1950_TORSION_MODE_FREQUENCIES.min() > 1.9 * TACOMA_TORSION_MODE_FREQUENCIES.min())
    }

    @Test
    fun `in the wind that destroyed the 1940 bridge, its twist dies away`() {
        // Same flutter model and coefficients as 1940; measured -0.095/s and -0.20/s.
        for (n in 1..2) {
            val rate = twistGrowth(TACOMA_FAILURE_WIND, n)
            assertTrue(rate < -0.05, "mode $n twist growth $rate/s at the 1940 failure wind - should decay")
        }
    }

    @Test
    fun `it does flutter, but only in a far stronger wind`() {
        // Onset ~13 units/s (~42 m/s, ~94 mph full scale) vs ~4.7 for 1940; measured +0.18/s at 18.
        val rate = twistGrowth(18.0, 1)
        assertTrue(rate > 0.05, "whole-span twist growth $rate/s at U=18 - should grow")
    }

    @Test
    fun `every roadway vertex has texture coordinates, running on along the span`() {
        val s = buildTacomaNarrows1950()
        val uvs = s.roadwaySurface.uvs!!
        val ids = s.roadwaySurface.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet()
        assertTrue(ids.all { it in uvs })
        val row = s.mainSpan[10]
        assertEquals(0.0, uvs.getValue(row.topLeft).u); assertEquals(1.0, uvs.getValue(row.topRight).u)
        assertEquals(s.store.position(row.centre).z / TACOMA_ROADWAY_TILE_LENGTH, uvs.getValue(row.centre).v, 1e-12)
    }
}
