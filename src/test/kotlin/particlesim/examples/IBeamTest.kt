package particlesim.examples

import particlesim.physics.Integrator
import particlesim.physics.Diagnostics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [IBeam]: a lattice-steel I-beam settles to the stresses and sag beam theory predicts. */
class IBeamTest {
    // Small enough to settle quickly: 2-cube flanges and web, 6 deep, 4 wide, 42 long.
    private val shape = IBeamShape(lengthCells = 42, depthCells = 6, flangeWidthCells = 4, flangeThicknessCells = 2, webThicknessCells = 2)

    private fun settle(beam: IBeam): Double {
        var t = 0.0
        val periods = 3.0
        val steps = ((beam.rampSeconds + periods * 2 * PI / beam.firstModeOmega) / beam.dt).toInt()
        repeat(steps) {
            beam.step(t)
            t += beam.dt
        }
        return t
    }

    @Test
    fun `in four-point bending the middle third's stress is M y over I, peaking at the surface`() {
        // Two equal weights at the third points: between them the beam carries a constant moment
        // and no shear, where beam theory is exact.
        val tonnes = 40.0
        val beam = IBeam(shape, tonnes = tonnes, weightPositions = listOf(1.0 / 3, 2.0 / 3), weightLengthCells = 0, selfWeight = false)
        settle(beam)
        assertTrue(Diagnostics.kineticEnergy(beam.store) < 1e-6 * beam.weight.weightAt(1e9) * shape.spacing, "settled")

        val p = tonnes * 1000 * STANDARD_GRAVITY
        val moment = p / 2 * (14 * shape.spacing) // each half of the load, over the 14 cubes to its support
        val inertia = shape.secondMomentOfArea
        val lattice = beam.lattice
        val cellStress = lattice.cells.zip(lattice.cellStresses(beam.store)).toMap()
        // Through the depth at mid-span: sigma_xx = -M (y - neutral axis) / I (compression on top).
        for (cell in lattice.cells.filter { it.i == 21 }) {
            val y = (cell.j + 0.5) * shape.spacing - shape.neutralAxis
            val expected = -moment * y / inertia
            val peak = moment * (shape.depth / 2) / inertia
            assertEquals(expected, cellStress.getValue(cell).xx, 0.03 * peak, "cube $cell")
        }
        // At the top and bottom surfaces, read at the nodes.
        val peak = moment * (shape.depth / 2) / inertia
        val surface = lattice.nodeIds.filter { val n = lattice.indexOf(it)!!; n.i == 21 && (n.j == 0 || n.j == shape.depthCells) }
        val stress = lattice.nodeStresses(beam.store, surface)
        for (id in surface) {
            val top = lattice.indexOf(id)!!.j == shape.depthCells
            assertEquals(if (top) -peak else peak, stress.getValue(id).xx, 0.05 * peak, "node ${lattice.indexOf(id)}")
        }
    }

    @Test
    fun `a weight at mid-span sags the beam as beam theory says, bending plus the web's shear`() {
        val tonnes = 40.0
        val beam = IBeam(shape, tonnes = tonnes, weightPositions = listOf(0.5), weightLengthCells = 0, selfWeight = false)
        settle(beam)
        val p = tonnes * 1000 * STANDARD_GRAVITY
        val l = shape.length
        val e = STEEL_YOUNGS_MODULUS
        val bending = p * l * l * l / (48 * e * shape.secondMomentOfArea)
        // Shear: half the load through the web over each half span; G = E / (2 (1 + 1/4)).
        val webArea = shape.depth * shape.webThicknessCells * shape.spacing
        val shear = (p / 2) * (l / 2) / (e / 2.5 * webArea)
        val lattice = beam.lattice
        val neutral = lattice.nodeIds.filter { val n = lattice.indexOf(it)!!; n.i == 21 && n.j == shape.depthCells / 2 }
        val sag = neutral.map { lattice.restPositions.getValue(it).y - beam.store.position(it).y }.average()
        assertEquals(bending + shear, sag, 0.1 * (bending + shear))
        assertTrue(abs(sag) > 0.0)
    }

    @Test
    fun `the beam's own weight comes from real steel, not the scaled-up node mass`() {
        val beam = IBeam(shape, tonnes = 0.0, selfWeight = true)
        val steel = shape.area * shape.length * STEEL_DENSITY * STANDARD_GRAVITY
        assertEquals(steel, beam.selfWeightForce!!.total, 1e-6 * steel)
        assertTrue(beam.nodeMass > 1000 * STEEL_DENSITY * shape.spacing * shape.spacing * shape.spacing, "node mass is scaled up")
    }

    @Test
    fun `the weight's tonnes is live-editable, ramps on smoothly, and refuses nonsense`() {
        val weight = RestingWeight(listOf(1, 2), tonnes = 10.0, rampSeconds = 2.0)
        val full = 10.0 * 1000 * STANDARD_GRAVITY
        assertEquals(0.0, weight.weightAt(0.0))
        assertEquals(full / 2, weight.weightAt(1.0), 1e-9)
        assertEquals(full, weight.weightAt(5.0), 1e-9)
        assertTrue(weight.setField("tonnes", particlesim.physics.FieldValue.Scalar(25.0)))
        assertEquals(2.5 * full, weight.weightAt(5.0), 1e-9)
        assertEquals(mapOf("tonnes" to particlesim.physics.FieldValue.Scalar(25.0)), weight.editableFields())
        assertTrue(!weight.setField("tonnes", particlesim.physics.FieldValue.Scalar(-1.0)))
        assertTrue(!weight.setField("tonnes", particlesim.physics.FieldValue.Scalar(Double.NaN)))
        assertTrue(!weight.setField("mass", particlesim.physics.FieldValue.Scalar(1.0)))
        assertEquals(25.0, weight.tonnes)
    }

    // Beam theory's collapse load for the test beam: four times its plastic moment over the span.
    private val collapseTonnes: Double = run {
        val a = shape.spacing
        // Plastic section modulus: each half of the section's area times its centroid's distance
        // from the neutral axis, summed cube by cube.
        val z = (0 until shape.depthCells).sumOf { j ->
            (0 until shape.flangeWidthCells).count { shape.inSection(j, it) } * a * a * kotlin.math.abs((j + 0.5) * a - shape.neutralAxis)
        }
        4 * z * STEEL_YIELD_STRESS / shape.length / (1000 * STANDARD_GRAVITY)
    }

    private fun run(beam: IBeam, seconds: Double, until: () -> Boolean = { false }): Double {
        var t = 0.0
        while (t < seconds && !until()) { beam.step(t); t += beam.dt }
        return t
    }

    @Test
    fun `with failure on, below its collapse load the beam yields a little and holds`() {
        // 0.6: the whole weight bears on one row of nodes here, which yields the flange under it
        // locally - by 0.8 of beam theory's collapse load that's enough to give way.
        val beam = IBeam(shape, tonnes = 0.6 * collapseTonnes, weightPositions = listOf(0.5), weightLengthCells = 0, selfWeight = false)
        beam.lattice.failure = true
        settle(beam)
        assertTrue(!beam.hasGivenWay && !beam.lattice.hasTorn)
        assertTrue(beam.lattice.maxPlasticStrain() < IBeam.GIVEN_WAY_STRAIN)
        assertTrue(Diagnostics.kineticEnergy(beam.store) < 1e-6 * beam.weight.weightAt(1e9) * shape.spacing, "came to rest")
    }

    @Test
    fun `with failure on, well past its collapse load it gives way, the damping drops, and it tears`() {
        val beam = IBeam(shape, tonnes = 1.5 * collapseTonnes, weightPositions = listOf(0.5), weightLengthCells = 0, selfWeight = false)
        beam.lattice.failure = true
        run(beam, 60.0) { beam.lattice.hasTorn }
        assertTrue(beam.hasGivenWay)
        assertEquals(beam.settlingCoefficient * IBeam.GIVEN_WAY_DAMPING_FRACTION, beam.settlingDamping.coefficient, 1e-6)
        assertTrue(beam.lattice.hasTorn, "tore within a minute of simulated time")
        // ...but never at the supports, where the load bears on a single row of nodes.
        val supports = (beam.pinnedNodes + beam.rollerNodes).toSet()
        assertTrue((0 until beam.lattice.springCount).filter { beam.lattice.isTorn(it) }.none { s ->
            val (p, q) = beam.lattice.springEnds(s); p in supports || q in supports
        })
    }

    @Test
    fun `with failure off, the same load leaves it elastic`() {
        val beam = IBeam(shape, tonnes = 1.5 * collapseTonnes, weightPositions = listOf(0.5), weightLengthCells = 0, selfWeight = false)
        settle(beam)
        assertEquals(0.0, beam.lattice.maxPlasticStrain())
        assertTrue(!beam.hasGivenWay)
    }

    @Test
    fun `settling damping pushes against velocity and is live-editable`() {
        val store = particlesim.core.ParticleStore()
        val groups = particlesim.core.Groups()
        val id = store.create(velocity = particlesim.core.Vector3(2.0, 0.0, -1.0))
        groups.add("g", id)
        val damping = SettlingDamping("g", 3.0)
        val chunk = particlesim.physics.ChunkAccumulator(store.capacity)
        damping.accumulate(store, groups, 0.0, chunk, 0, 1)
        assertEquals(particlesim.core.Vector3(-6.0, 0.0, 3.0), chunk.at(store.slotOf(id)))
        assertTrue(damping.setField("coefficient", particlesim.physics.FieldValue.Scalar(0.5)))
        assertEquals(0.5, damping.coefficient)
        assertTrue(!damping.setField("coefficient", particlesim.physics.FieldValue.Scalar(-1.0)))
    }

    @Test
    fun `a roller bearing holds height and line, slides along x, and stops at its travel`() {
        val store = particlesim.core.ParticleStore()
        val groups = particlesim.core.Groups()
        val id = store.create(position = particlesim.core.Vector3(1.0, 2.0, 3.0))
        groups.add("r", id)
        val bearing = RollerBearing("r", store, groups, travel = 0.2)
        val push = particlesim.physics.ConstantForce("r", particlesim.core.Vector3(5.0, -9.0, 4.0))
        val integrator = Integrator()
        var t = 0.0
        repeat(10) { integrator.step(store, groups, listOf(push), listOf(bearing), t, 0.01); t += 0.01 }
        val p = store.position(id)
        assertEquals(2.0, p.y, 1e-12)
        assertEquals(3.0, p.z, 1e-12)
        assertTrue(p.x > 1.0 && p.x < 1.2, "sliding: $p")
        repeat(200) { integrator.step(store, groups, listOf(push), listOf(bearing), t, 0.01); t += 0.01 }
        assertEquals(1.2, store.position(id).x, 1e-12)
        assertEquals(particlesim.core.Vector3.ZERO, store.velocity(id), "stopped against the stop")
    }
}
