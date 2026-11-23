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
        val integrator = Integrator()
        var t = 0.0
        val periods = 3.0
        val steps = ((beam.rampSeconds + periods * 2 * PI / beam.firstModeOmega) / beam.dt).toInt()
        repeat(steps) {
            integrator.step(beam.store, beam.groups, beam.forces, beam.constraints, t, beam.dt)
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
}
