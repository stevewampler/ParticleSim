package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The self-excited deck moment [TorsionalFlutter], on a single strip. */
class TorsionalFlutterTest {
    private val store = ParticleStore()
    private val groups = Groups()
    private val left = store.create(position = Vector3(-1.0, 0.0, 0.0))
    private val right = store.create(position = Vector3(1.0, 0.0, 0.0))
    private val strip = TorsionalFlutter.Strip(left, right, spanLength = 1.5)

    private fun flutter(windX: Double, slope: Double = 0.2, vr0: Double = 4.0) = TorsionalFlutter(
        listOf(strip), windVelocity = { Vector3(windX, 0.0, 0.0) }, frequency = 0.5, density = 0.3, slope = slope,
        criticalReducedVelocity = vr0, saturationTwist = 0.5,
    )

    private fun forces(f: TorsionalFlutter): Pair<Vector3, Vector3> {
        val chunk = ChunkAccumulator(store.capacity)
        f.accumulate(store, groups, 0.0, chunk, 0, 1)
        return chunk.at(store.slotOf(left)) to chunk.at(store.slotOf(right))
    }

    /** Twist the strip at rate [rate] rad/s (right edge up). */
    private fun twistAt(rate: Double) {
        store.setVelocity(left, Vector3(0.0, -rate, 0.0))
        store.setVelocity(right, Vector3(0.0, rate, 0.0))
    }

    @Test
    fun `the moment matches Scanlan's A2 term and is a pure couple`() {
        twistAt(0.1)
        // B = 2, f = 0.5, U = 6: Vr = 6 / (0.5 * 2) = 6, A2* = 0.2 * (6 - 4) = 0.4.
        // M = ½ ρ B⁴ ω A2* α̇ ℓ = 0.5 * 0.3 * 16 * π * 0.4 * 0.1 * 1.5.
        val f = flutter(windX = 6.0)
        val expected = 0.5 * 0.3 * 16.0 * PI * 0.4 * 0.1 * 1.5
        assertEquals(expected, f.moment(store, strip, 0.0), 1e-12)
        val (fl, fr) = forces(f)
        assertEquals(0.0, (fl + fr).length(), 1e-12, "net force should be zero")
        assertEquals(expected, fr.y * 2.0, 1e-12, "couple of ±M/B over lever arm B")
    }

    @Test
    fun `above the critical reduced velocity it feeds energy into the twist, below it takes energy out`() {
        twistAt(0.1)
        val above = flutter(windX = 6.0).moment(store, strip, 0.0)
        val below = flutter(windX = 2.0).moment(store, strip, 0.0)
        assertTrue(above * 0.1 > 0.0, "power M·α̇ should be positive above Vr₀, was ${above * 0.1}")
        assertTrue(below * 0.1 < 0.0, "power M·α̇ should be negative below Vr₀, was ${below * 0.1}")
        // Wind direction doesn't matter, only its speed across the deck.
        assertEquals(above, flutter(windX = -6.0).moment(store, strip, 0.0), 1e-12)
    }

    @Test
    fun `no wind, no twist rate, or a zero slope gives no moment`() {
        twistAt(0.1)
        assertEquals(0.0, flutter(windX = 0.0).moment(store, strip, 0.0), 1e-15)
        assertEquals(0.0, flutter(windX = 6.0, slope = 0.0).moment(store, strip, 0.0), 1e-15)
        twistAt(0.0)
        assertEquals(0.0, flutter(windX = 6.0).moment(store, strip, 0.0), 1e-15)
    }

    @Test
    fun `wind along the span doesn't count`() {
        twistAt(0.1)
        val f = TorsionalFlutter(listOf(strip), windVelocity = { Vector3(0.0, 0.0, 6.0) }, frequency = 0.5)
        assertEquals(0.0, f.moment(store, strip, 0.0), 1e-15)
    }

    @Test
    fun `the destabilizing moment fades with twist and reverses beyond the saturation twist`() {
        twistAt(0.1)
        val level = flutter(windX = 6.0).moment(store, strip, 0.0)
        // The same strip rigidly twisted to αs/√2 (αs = saturationTwist = 0.5), still twisting at
        // 0.1 rad/s: the factor 1 − (α/αs)² halves the moment there.
        val angle = 0.5 / kotlin.math.sqrt(2.0)
        val c = kotlin.math.cos(angle); val s = kotlin.math.sin(angle)
        store.setPosition(left, Vector3(-c, -s, 0.0)); store.setPosition(right, Vector3(c, s, 0.0))
        store.setVelocity(left, Vector3(0.1 * s, -0.1 * c, 0.0)); store.setVelocity(right, Vector3(-0.1 * s, 0.1 * c, 0.0))
        val twisted = flutter(windX = 6.0).moment(store, strip, 0.0)
        assertTrue(abs(twisted / level - 0.5) < 1e-9, "expected half the moment at αs/√2, got ${twisted / level}")
        val c2 = kotlin.math.cos(0.6); val s2 = kotlin.math.sin(0.6)
        store.setPosition(left, Vector3(-c2, -s2, 0.0)); store.setPosition(right, Vector3(c2, s2, 0.0))
        store.setVelocity(left, Vector3(0.1 * s2, -0.1 * c2, 0.0)); store.setVelocity(right, Vector3(-0.1 * s2, 0.1 * c2, 0.0))
        assertTrue(flutter(windX = 6.0).moment(store, strip, 0.0) * 0.1 < 0.0, "beyond αs it should take energy out")
    }

    @Test
    fun `editable fields round-trip`() {
        val f = flutter(windX = 6.0)
        assertTrue(f.setField("a2Slope", FieldValue.Scalar(0.0)))
        assertEquals(FieldValue.Scalar(0.0), f.editableFields()["a2Slope"])
        assertTrue(!f.setField("frequency", FieldValue.Scalar(-1.0)), "a non-positive frequency should be rejected")
    }

    @Test
    fun `with modes each part of the motion uses its own mode's frequency`() {
        // Two strips; mode A twists both the same way at 0.5 Hz, mode B opposite ways at 1.0 Hz.
        val store = ParticleStore()
        val l1 = store.create(position = Vector3(-1.0, 0.0, 0.0)); val r1 = store.create(position = Vector3(1.0, 0.0, 0.0))
        val l2 = store.create(position = Vector3(-1.0, 0.0, 1.0)); val r2 = store.create(position = Vector3(1.0, 0.0, 1.0))
        val strips = listOf(TorsionalFlutter.Strip(l1, r1, 1.0), TorsionalFlutter.Strip(l2, r2, 1.0))
        val f = TorsionalFlutter(
            strips, windVelocity = { Vector3(6.0, 0.0, 0.0) }, frequency = 2.0, density = 0.3, slope = 0.2,
            criticalReducedVelocity = 4.0,
            modes = listOf(TorsionalFlutter.Mode(doubleArrayOf(1.0, 1.0), 0.5), TorsionalFlutter.Mode(doubleArrayOf(1.0, -1.0), 1.0)),
        )
        fun twist(rate1: Double, rate2: Double) {
            store.setVelocity(l1, Vector3(0.0, -rate1, 0.0)); store.setVelocity(r1, Vector3(0.0, rate1, 0.0))
            store.setVelocity(l2, Vector3(0.0, -rate2, 0.0)); store.setVelocity(r2, Vector3(0.0, rate2, 0.0))
        }
        // ½ρB⁴ω·A2*(U/(fB))·α̇ℓ with B = 2, U = 6.
        fun expected(freq: Double, rate: Double) = 0.5 * 0.3 * 16.0 * 2.0 * PI * freq * f.a2(6.0 / (freq * 2.0)) * rate
        twist(0.1, 0.1)
        assertEquals(expected(0.5, 0.1), f.moments(store, 0.0)[0], 1e-12)
        twist(0.1, -0.1)
        assertEquals(expected(1.0, 0.1), f.moments(store, 0.0)[0], 1e-12)
        assertEquals(expected(1.0, -0.1), f.moments(store, 0.0)[1], 1e-12)
        assertTrue(f.moments(store, 0.0)[0] < 0.0, "mode B's Vr (3) is below Vr₀ (4): it should damp")
    }
}
