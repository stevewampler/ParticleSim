package particlesim.debug

import particlesim.examples.STEEL_YIELD_STRESS
import particlesim.render.ColorRamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [IBeamScene]: the beam's skin painted by stress, with a legend and rest positions for the
 * viewer's deformation exaggeration. */
class IBeamSceneTest {
    @Test
    fun `the beam's skin is coloured by stress, with a legend, rest positions and exaggeration`() {
        val scene = IBeamScene()
        var t = 0.0
        repeat(3000) { scene.step(t); t += scene.dt }
        val frame = scene.frame(t)
        assertEquals(emptySet(), frame.visibleIds, "the lattice's nodes aren't drawn as dots - the skin shows them")
        val beam = frame.meshes.first()
        assertEquals("I-beam", beam.surface.name)
        val skinIds = beam.surface.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet()
        val colors = assertNotNull(beam.vertexColors)
        assertEquals(skinIds, colors.keys)
        assertEquals(IBeamScene.LEGEND, beam.legend)
        assertEquals(STEEL_YIELD_STRESS / 1e6, beam.legend!!.max)
        assertEquals(skinIds, beam.restPositions!!.keys)
        assertEquals(IBeamScene.DEFORMATION_SCALE, beam.deformationScale)
        assertTrue(beam.flatShading)
        assertEquals(listOf("I-beam", "weight", "pier 1", "pier 2", "ground"), frame.meshes.map { it.surface.name })
        assertTrue(frame.meshes.none { it.pickable }, "the beam is drawn exaggerated, so picking it would miss")
    }

    @Test
    fun `as the weight comes on, the bottom flange is stressed most at mid-span and the weight rides the sag`() {
        val scene = IBeamScene()
        var t = 0.0
        repeat(4000) { scene.step(t); t += scene.dt }
        val frame = scene.frame(t)
        val beam = frame.meshes.first()
        val store = scene.store
        val rest = beam.restPositions!!
        // Bottom-face skin vertices on the centre line; mid-span is at x = 0, the piers at +-2.
        val bottom = rest.filter { (_, p) -> p.y == IBeamScene.PIER_HEIGHT && kotlin.math.abs(p.z) < 1e-9 }
        fun nearest(x: Double) = bottom.minBy { (_, p) -> kotlin.math.abs(p.x - x) }.key
        assertTrue(scene.vonMisesAt(nearest(0.0)) > 3 * scene.vonMisesAt(nearest(-1.7)))
        // ...and painted accordingly.
        val colors = beam.vertexColors!!
        assertEquals(ColorRamp.stress(scene.vonMisesAt(nearest(0.0)) / STEEL_YIELD_STRESS), colors.getValue(nearest(0.0)))
        // The weight block sits on the flange: its underside follows the nodes under it.
        val weight = frame.meshes[1]
        val blockRest = weight.restPositions!!
        val sag = blockRest.keys.map { store.position(it).y - blockRest.getValue(it).y }
        assertTrue(sag.all { it < 0.0 && kotlin.math.abs(it - sag.first()) < 1e-12 }, "moves down as one: $sag")
        assertEquals(scene.weightSag(), sag.first(), 1e-12)
    }

}
