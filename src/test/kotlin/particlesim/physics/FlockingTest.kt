package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §5.2's [Flocking] and [Pursuit], one steering term at a time. */
class FlockingTest {
    private val mass = 0.08

    private class World(val store: ParticleStore = ParticleStore(), val groups: Groups = Groups())

    private fun World.bird(position: Vector3, velocity: Vector3 = Vector3(10.0, 0.0, 0.0), group: String = "birds") =
        store.create(position = position, velocity = velocity, mass = ScalarExpr.of(0.08)).also { groups.add(group, it) }

    /** Every term off except those given - a [Flocking] for testing one behaviour. */
    private fun only(vararg terms: Pair<String, Double>, neighbours: Int = 7, predators: String? = null): Flocking {
        val f = Flocking(
            "birds", neighbours = neighbours, separationStrength = 0.0, alignmentStrength = 0.0, cohesionStrength = 0.0,
            cruiseSpeed = 10.0, roostRadius = 1e9, roostStrength = 0.0, altitudeStrength = 0.0, predatorGroup = predators,
            fleeStrength = 0.0, maxAcceleration = 1e9,
        )
        for ((field, value) in terms) assertTrue(f.setField(field, FieldValue.Scalar(value)))
        return f
    }

    private fun World.forceOn(force: Force, id: Int): Vector3 {
        val chunk = ChunkAccumulator(store.capacity)
        for (i in 0 until 4) force.accumulate(store, groups, 0.0, chunk, i, 4)
        return chunk.at(store.slotOf(id))
    }

    private fun assertClose(expected: Vector3, actual: Vector3, tolerance: Double = 1e-9) =
        assertTrue((expected - actual).length() <= tolerance, "expected $expected, got $actual")

    @Test
    fun `separation pushes two close birds apart, equally and oppositely`() {
        val w = World()
        val a = w.bird(Vector3.ZERO)
        val b = w.bird(Vector3(0.5, 0.0, 0.0))
        val f = only("separationStrength" to 30.0, "separationDistance" to 2.0)
        // (1 - 0.5/2) * 30 m/s², along the line between them.
        assertClose(Vector3(-0.75 * 30.0 * mass, 0.0, 0.0), w.forceOn(f, a))
        assertClose(Vector3(0.75 * 30.0 * mass, 0.0, 0.0), w.forceOn(f, b))
    }

    @Test
    fun `separation ignores birds beyond the separation distance`() {
        val w = World()
        val a = w.bird(Vector3.ZERO)
        w.bird(Vector3(3.0, 0.0, 0.0))
        assertClose(Vector3.ZERO, w.forceOn(only("separationStrength" to 30.0, "separationDistance" to 2.0), a))
    }

    @Test
    fun `alignment turns a bird toward its neighbours' mean velocity`() {
        val w = World()
        val a = w.bird(Vector3.ZERO, Vector3(10.0, 0.0, 0.0))
        w.bird(Vector3(3.0, 0.0, 0.0), Vector3(0.0, 0.0, 10.0))
        w.bird(Vector3(-3.0, 0.0, 0.0), Vector3(0.0, 0.0, 6.0))
        // Mean neighbour velocity (0, 0, 8); 2/s times the difference.
        assertClose(Vector3(-10.0, 0.0, 8.0) * (2.0 * mass), w.forceOn(only("alignmentStrength" to 2.0), a))
    }

    @Test
    fun `cohesion pulls a bird toward its neighbours' centroid`() {
        val w = World()
        val a = w.bird(Vector3.ZERO)
        w.bird(Vector3(4.0, 2.0, 0.0))
        w.bird(Vector3(6.0, -2.0, 0.0))
        assertClose(Vector3(5.0, 0.0, 0.0) * (0.5 * mass), w.forceOn(only("cohesionStrength" to 0.5, "perceptionRadius" to 200.0), a))
    }

    @Test
    fun `only the k nearest neighbours count, however far away they are`() {
        // Topological: with k = 2 the two nearest count even 20 m off, and a third, farther one
        // changes nothing.
        val w = World()
        val a = w.bird(Vector3.ZERO)
        w.bird(Vector3(20.0, 0.0, 0.0))
        w.bird(Vector3(0.0, 0.0, 20.0))
        val f = only("cohesionStrength" to 1.0, neighbours = 2)
        val withTwo = w.forceOn(f, a)
        assertClose(Vector3(10.0, 0.0, 10.0) * mass, withTwo)
        w.bird(Vector3(-24.0, 0.0, 0.0))
        assertClose(withTwo, w.forceOn(f, a))
    }

    @Test
    fun `nothing beyond the perception radius counts`() {
        val w = World()
        val a = w.bird(Vector3.ZERO)
        w.bird(Vector3(30.0, 0.0, 0.0))
        assertClose(Vector3.ZERO, w.forceOn(only("cohesionStrength" to 1.0, "perceptionRadius" to 25.0), a))
    }

    @Test
    fun `a lone bird relaxes to its cruise speed, keeping its heading`() {
        val w = World()
        val a = w.bird(Vector3.ZERO, Vector3(0.0, 0.0, 4.0))
        val forces = listOf(only("speedRelaxation" to 1.0))
        val integrator = Integrator()
        var t = 0.0
        repeat(600) { integrator.step(w.store, w.groups, forces, emptyList(), t, 0.01); t += 0.01 }
        val v = w.store.velocity(a)
        assertEquals(10.0, v.length(), 0.05)
        assertTrue(abs(v.x) < 1e-12 && abs(v.y) < 1e-12)
    }

    @Test
    fun `the roost pulls back only birds outside its radius, and everyone toward its altitude`() {
        val w = World()
        val inside = w.bird(Vector3(30.0, 60.0, 0.0))
        val outside = w.bird(Vector3(0.0, 55.0, 80.0))
        val f = Flocking(
            "birds", neighbours = 0, cruiseSpeed = 10.0, roostCenter = Vector3(0.0, 60.0, 0.0), roostRadius = 60.0,
            roostStrength = 0.05, altitudeStrength = 0.2, maxAcceleration = 1e9,
        )
        assertClose(Vector3.ZERO, w.forceOn(f, inside))
        // 20 m past the radius pulls 0.05 * 20 back toward the centre; 5 m low lifts 0.2 * 5.
        assertClose(Vector3(0.0, 1.0, -1.0) * mass, w.forceOn(f, outside))
    }

    @Test
    fun `birds flee a predator inside the flee radius, harder the closer it is`() {
        val w = World()
        val near = w.bird(Vector3(5.0, 0.0, 0.0))
        val far = w.bird(Vector3(0.0, 0.0, 40.0))
        w.bird(Vector3.ZERO, group = "falcon")
        val f = only("fleeStrength" to 40.0, "fleeRadius" to 25.0, "perceptionRadius" to 0.0, predators = "falcon")
        assertClose(Vector3(40.0 * (1.0 - 5.0 / 25.0) * mass, 0.0, 0.0), w.forceOn(f, near))
        assertClose(Vector3.ZERO, w.forceOn(f, far))
        // Disabling the falcon's group calls it off.
        w.groups.setEnabled("falcon", false)
        assertClose(Vector3.ZERO, w.forceOn(f, near))
    }

    @Test
    fun `steering is capped at the maximum acceleration`() {
        val w = World()
        val a = w.bird(Vector3.ZERO)
        w.bird(Vector3(0.01, 0.0, 0.0))
        val f = only("separationStrength" to 1e6, "maxAcceleration" to 40.0)
        assertEquals(40.0 * mass, w.forceOn(f, a).length(), 1e-9)
    }

    @Test
    fun `no force applies to a disabled flock`() {
        val w = World()
        val a = w.bird(Vector3.ZERO, Vector3(0.0, 0.0, 3.0))
        w.groups.setEnabled("birds", false)
        assertClose(Vector3.ZERO, w.forceOn(Flocking("birds"), a))
    }

    @Test
    fun `the same flock steps identically on one thread or several, in any chunking`() {
        fun run(integrator: Integrator): List<Vector3> {
            val w = World()
            val random = kotlin.random.Random(3)
            val ids = (0 until 200).map {
                w.bird(Vector3(random.nextDouble(-8.0, 8.0), random.nextDouble(-3.0, 3.0), random.nextDouble(-8.0, 8.0)), Vector3(10.0, random.nextDouble(-1.0, 1.0), random.nextDouble(-2.0, 2.0)))
            }
            val forces = listOf(Flocking("birds", roostCenter = Vector3.ZERO))
            var t = 0.0
            repeat(100) { integrator.step(w.store, w.groups, forces, emptyList(), t, 1.0 / 60.0); t += 1.0 / 60.0 }
            return ids.map { w.store.position(it) }
        }
        val pool = Executors.newFixedThreadPool(3)
        try {
            val serial = run(Integrator(chunkCount = 1))
            assertEquals(serial, run(Integrator(chunkCount = 4)))
            assertEquals(serial, run(Integrator(chunkCount = 4, executor = pool)))
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `flocking fields are live-editable, and nonsense is refused`() {
        val f = Flocking("birds")
        assertTrue(f.setField("neighbours", FieldValue.Scalar(12.0)))
        assertEquals(FieldValue.Scalar(12.0), f.editableFields()["neighbours"])
        assertTrue(f.setField("roostCenter", FieldValue.Vector(Vector3(1.0, 2.0, 3.0))))
        assertEquals(FieldValue.Vector(Vector3(1.0, 2.0, 3.0)), f.editableFields()["roostCenter"])
        assertFalse(f.setField("roostCenter", FieldValue.Scalar(1.0)))
        assertFalse(f.setField("speedRelaxation", FieldValue.Scalar(0.0)))
        assertFalse(f.setField("wingspan", FieldValue.Scalar(1.0)))
    }

    @Test
    fun `a pursuer steers at its chase speed toward the nearest target`() {
        val w = World()
        val falcon = w.bird(Vector3.ZERO, Vector3.ZERO, group = "falcon")
        w.bird(Vector3(0.0, 0.0, 10.0))
        w.bird(Vector3(30.0, 0.0, 0.0))
        val p = Pursuit("falcon", "birds", chaseSpeed = 20.0, responseTime = 2.0, maxAcceleration = 1e9)
        // Wants (0, 0, 20) from rest, over 2 s.
        assertClose(Vector3(0.0, 0.0, 10.0) * mass, w.forceOn(p, falcon))
    }

    @Test
    fun `a pursuer's acceleration is capped`() {
        val w = World()
        val falcon = w.bird(Vector3.ZERO, Vector3(-30.0, 0.0, 0.0), group = "falcon")
        w.bird(Vector3(10.0, 0.0, 0.0))
        val p = Pursuit("falcon", "birds", maxAcceleration = 30.0)
        assertEquals(30.0 * mass, w.forceOn(p, falcon).length(), 1e-9)
    }

    @Test
    fun `a pursuer rests, then hunts, over and over`() {
        val p = Pursuit("falcon", "birds", huntSeconds = 8.0, restSeconds = 12.0)
        assertFalse(p.isHunting(0.0))
        assertFalse(p.isHunting(11.9))
        assertTrue(p.isHunting(12.0))
        assertTrue(p.isHunting(19.9))
        assertFalse(p.isHunting(20.0))
        assertTrue(p.isHunting(33.0))
        assertTrue(Pursuit("falcon", "birds").isHunting(0.0), "no rest means always hunting")
    }

    @Test
    fun `resting, a pursuer heads for its rest point at half speed, or coasts with none`() {
        val w = World()
        val falcon = w.bird(Vector3.ZERO, Vector3.ZERO, group = "falcon")
        w.bird(Vector3(10.0, 0.0, 0.0))
        val chunk = ChunkAccumulator(w.store.capacity)
        val perching = Pursuit("falcon", "birds", chaseSpeed = 20.0, responseTime = 1.0, restSeconds = 5.0, restPoint = Vector3(0.0, 50.0, 0.0), maxAcceleration = 1e9)
        perching.accumulate(w.store, w.groups, 1.0, chunk, 0, 1)
        assertClose(Vector3(0.0, 10.0, 0.0) * mass, chunk.at(w.store.slotOf(falcon)))
        val coasting = Pursuit("falcon", "birds", restSeconds = 5.0)
        val chunk2 = ChunkAccumulator(w.store.capacity)
        coasting.accumulate(w.store, w.groups, 1.0, chunk2, 0, 1)
        assertClose(Vector3.ZERO, chunk2.at(w.store.slotOf(falcon)))
    }

    @Test
    fun `a bird ignores neighbours in its blind cone behind it`() {
        val w = World()
        val a = w.bird(Vector3.ZERO) // flying +x
        w.bird(Vector3(-4.0, 1.0, 0.0)) // 14° off straight behind: hidden by a 90° blind cone
        w.bird(Vector3(0.0, 0.0, 6.0)) // square to the side: seen
        val f = only("cohesionStrength" to 0.5, "blindAngle" to 90.0)
        assertClose(Vector3(0.0, 0.0, 6.0) * (0.5 * mass), w.forceOn(f, a))
        // With all-round vision both count.
        assertClose(Vector3(-2.0, 0.5, 3.0) * (0.5 * mass), w.forceOn(only("cohesionStrength" to 0.5, "perceptionRadius" to 200.0), a))
    }

    @Test
    fun `a blind bird looks past the hidden ones for its nearest visible neighbours`() {
        val w = World()
        val a = w.bird(Vector3.ZERO)
        w.bird(Vector3(-1.0, 0.0, 0.0)) // nearest, but straight behind
        w.bird(Vector3(5.0, 0.0, 0.0))
        val f = only("cohesionStrength" to 1.0, "blindAngle" to 90.0, neighbours = 1)
        assertClose(Vector3(5.0, 0.0, 0.0) * mass, w.forceOn(f, a))
    }

    @Test
    fun `separation still works on a bird in the blind cone`() {
        val w = World()
        val a = w.bird(Vector3.ZERO)
        w.bird(Vector3(-0.5, 0.0, 0.0)) // straight behind, too close
        val f = only("separationStrength" to 30.0, "separationDistance" to 2.0, "blindAngle" to 90.0)
        assertClose(Vector3(0.75 * 30.0 * mass, 0.0, 0.0), w.forceOn(f, a))
    }

    @Test
    fun `a banked turn is capped by the lift tilted to the steepest bank`() {
        val w = World()
        val a = w.bird(Vector3.ZERO) // level, flying +x
        // Cohesion toward a neighbour 100 m off to the side asks for 50 m/s² sideways.
        w.bird(Vector3(0.0, 0.0, 100.0))
        val g = 9.80665
        val f = only("cohesionStrength" to 0.5, "maxBankAngle" to 45.0, "perceptionRadius" to 200.0)
        // Level flight makes lift g, so at 45° it can turn at g sideways.
        assertClose(Vector3(0.0, 0.0, g * mass), w.forceOn(f, a), 1e-9)
        // No limit at 90°.
        assertClose(Vector3(0.0, 0.0, 50.0 * mass), w.forceOn(only("cohesionStrength" to 0.5, "perceptionRadius" to 200.0), a))
    }

    @Test
    fun `a bird pulling up turns harder and one diving can't turn`() {
        val g = 9.80665
        val up = World()
        val a = up.bird(Vector3.ZERO)
        up.bird(Vector3(0.0, 10.0, 100.0)) // 5 m/s² up as well as 50 sideways
        val f = only("cohesionStrength" to 0.5, "maxBankAngle" to 45.0, "perceptionRadius" to 200.0)
        assertClose(Vector3(0.0, 5.0, g + 5.0) * mass, up.forceOn(f, a), 1e-9)

        val down = World()
        val b = down.bird(Vector3.ZERO)
        down.bird(Vector3(0.0, -30.0, 100.0)) // 15 m/s² down: more than gravity, so no lift
        assertClose(Vector3(0.0, -15.0, 0.0) * mass, down.forceOn(f, b), 1e-9)
    }

    @Test
    fun `banking leaves forward steering alone, and measures sideways square to a climbing heading`() {
        val w = World()
        // Climbing at 30°: lift holding off gravity is g cos 30°.
        val heading = Vector3(kotlin.math.cos(Math.PI / 6), kotlin.math.sin(Math.PI / 6), 0.0)
        val a = w.bird(Vector3.ZERO, heading * 10.0)
        w.bird(heading * 20.0 + Vector3(0.0, 0.0, 100.0)) // 10 m/s² forward along the heading, 50 sideways
        val g = 9.80665
        val f = only("cohesionStrength" to 0.5, "maxBankAngle" to 45.0, "perceptionRadius" to 200.0)
        val expected = heading * 10.0 + Vector3(0.0, 0.0, g * kotlin.math.cos(Math.PI / 6))
        assertClose(expected * mass, w.forceOn(f, a), 1e-9)
    }

    @Test
    fun `blind angle and bank are editable and validated`() {
        val f = only()
        assertTrue(f.setField("blindAngle", FieldValue.Scalar(90.0)))
        assertTrue(f.setField("maxBankAngle", FieldValue.Scalar(60.0)))
        assertFalse(f.setField("blindAngle", FieldValue.Scalar(400.0)))
        assertFalse(f.setField("maxBankAngle", FieldValue.Scalar(-1.0)))
        assertEquals(FieldValue.Scalar(90.0), f.editableFields()["blindAngle"])
        assertEquals(FieldValue.Scalar(60.0), f.editableFields()["maxBankAngle"])
    }
}
