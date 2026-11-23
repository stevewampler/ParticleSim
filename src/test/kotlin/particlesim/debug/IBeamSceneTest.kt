package particlesim.debug

import particlesim.examples.STEEL_YIELD_STRESS
import particlesim.physics.LatticeIndex
import particlesim.render.SurfaceRenderer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [IBeamScene]: the beam's skin with colour fields the viewer picks from, rest positions for
 * exaggeration, and options for failure and spring colouring. */
class IBeamSceneTest {
    private fun run(scene: IBeamScene, steps: Int, from: Double = 0.0): Double {
        var t = from
        repeat(steps) { scene.step(t); t += scene.dt }
        return t
    }

    @Test
    fun `the skin carries four colour fields, everything has a rest position, and nothing is pickable`() {
        val scene = IBeamScene()
        val t = run(scene, 3000)
        val frame = scene.frame(t)
        assertEquals(emptySet(), frame.visibleIds, "the lattice's nodes aren't drawn as dots - the skin shows them")
        val beam = frame.meshes.first()
        assertEquals("I-beam", beam.surface.name)
        val skinIds = beam.surface.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet()
        assertEquals(IBeamScene.FIELDS, beam.scalarFields.map { it.name })
        assertTrue(beam.scalarFields.all { it.values.keys == skinIds })
        val (vonMises, bending, shear, plastic) = beam.scalarFields
        assertEquals(0.0 to STEEL_YIELD_STRESS / 1e6, vonMises.min to vonMises.max)
        assertEquals(-bending.max, bending.min, "signed")
        assertEquals(-shear.max, shear.min, "signed")
        assertEquals(0.0, plastic.values.values.max(), "nothing yields with failure off")
        assertTrue(skinIds.all { it in frame.restPositions })
        assertEquals(IBeamScene.DEFORMATION_SCALE, frame.deformationScale)
        assertTrue(beam.flatShading)
        assertEquals(listOf("I-beam", "weight", "pier 1", "pier 2", "ground"), frame.meshes.map { it.surface.name })
        assertTrue(frame.meshes.none { it.pickable }, "the beam is drawn exaggerated, so picking it would miss")
        assertEquals(listOf("failure" to "off", "springs" to "hidden", "section" to "off"), frame.options.map { it.name to it.selected })
        assertNull(frame.lineLegend)
        assertTrue(frame.connections.isEmpty())
    }

    @Test
    fun `as the weight comes on, the bottom flange bends in tension, most at mid-span, and the weight rides the sag`() {
        val scene = IBeamScene()
        val t = run(scene, 4000)
        val frame = scene.frame(t)
        val rest = frame.restPositions
        val beam = frame.meshes.first()
        // Bottom-face skin vertices on the centre line; mid-span is at x = 0, the piers at +-2.
        val bottom = beam.scalarFields[0].values.keys.filter { val p = rest.getValue(it); p.y == IBeamScene.PIER_HEIGHT && abs(p.z) < 1e-9 }
        fun nearest(x: Double) = bottom.minBy { abs(rest.getValue(it).x - x) }
        assertTrue(scene.vonMisesAt(nearest(0.0)) > 3 * scene.vonMisesAt(nearest(-1.7)))
        assertEquals(scene.vonMisesAt(nearest(0.0)) / 1e6, beam.scalarFields[0].values.getValue(nearest(0.0)), 1e-9)
        assertTrue(beam.scalarFields[1].values.getValue(nearest(0.0)) > 0.0, "tension along the bottom")
        // The weight block rides on the flange: it moves with the nodes under it.
        val weight = frame.meshes[1]
        val corners = weight.surface.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet()
        val sag = corners.map { scene.store.position(it).y - rest.getValue(it).y }
        assertTrue(sag.all { it < 0.0 && abs(it - sag.first()) < 1e-12 }, "moves down as one: $sag")
        assertEquals(scene.weightSag(), sag.first(), 1e-12)
    }

    @Test
    fun `springs mode shows the edge springs coloured by stretch or force, with their own legend, instead of the skin`() {
        val scene = IBeamScene()
        val t = run(scene, 3000)
        scene.handleControl(SceneControlMessage.SetSceneOption("springs", "stretch"), t)
        val stretch = scene.frame(t)
        assertEquals(listOf("weight", "pier 1", "pier 2", "ground"), stretch.meshes.map { it.surface.name })
        assertTrue(stretch.registry.surfaces.isEmpty(), "left out, or it would get a default mesh hiding the springs")
        assertTrue(stretch.connections.size > 10000)
        assertEquals(stretch.connections.toSet(), stretch.lineColors.keys)
        assertEquals("spring stretch", stretch.lineLegend!!.label)
        assertEquals("%", stretch.lineLegend!!.unit)
        scene.handleControl(SceneControlMessage.SetSceneOption("springs", "force"), t)
        assertEquals("spring force", scene.frame(t).lineLegend!!.label)
        // A choice it doesn't offer changes nothing.
        scene.handleControl(SceneControlMessage.SetSceneOption("springs", "sideways"), t)
        assertEquals("force", scene.frame(t).options.single { it.name == "springs" }.selected)
    }

    /** The skin's nodes, as lattice indices, and the frame's beam mesh, with [section] chosen. */
    private fun cut(scene: IBeamScene, section: String, t: Double): Pair<Map<Int, LatticeIndex>, SurfaceRenderer> {
        scene.handleControl(SceneControlMessage.SetSceneOption("section", section), t)
        val frame = scene.frame(t)
        assertEquals(section, frame.options.single { it.name == "section" }.selected)
        val beam = frame.meshes.first()
        val lattice = scene.model.lattice
        return beam.surface.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet().associateWith { lattice.indexOf(it)!! } to beam
    }

    @Test
    fun `a mid-span section shows the pinned half, capped with the interior's bending stress, and halves the weight`() {
        val scene = IBeamScene()
        val t = run(scene, 4000)
        val shape = scene.model.shape
        val mid = shape.lengthCells / 2
        val (nodes, beam) = cut(scene, "mid-span", t)
        assertTrue(nodes.values.all { it.i <= mid }, "nothing past the cut")
        // The cap: the whole cross-section's nodes at the cut, interior ones included.
        val cap = nodes.filterValues { it.i == mid }
        val webCentre = cap.filterValues { it.k == shape.flangeWidthCells / 2 }
        assertEquals(shape.depthCells + 1, webCentre.size, "every node down the middle of the web")
        val vonMises = beam.scalarFields[0].values
        for (id in cap.keys) assertEquals(scene.vonMisesAt(id) / 1e6, vonMises.getValue(id), 1e-9)
        // Down the web: bending stress falls straight from compression at the top to tension at
        // the bottom, through zero at the neutral axis.
        val bending = beam.scalarFields[1].values
        val byHeight = webCentre.entries.sortedBy { it.value.j }.map { bending.getValue(it.key) }
        val bottom = byHeight.first()
        assertTrue(bottom > 0.0 && byHeight.last() < 0.0, "tension below, compression above: $byHeight")
        assertTrue(abs(byHeight[shape.depthCells / 2]) < 0.1 * bottom, "near zero at mid-depth: $byHeight")
        val steps = byHeight.zipWithNext { a, b -> a - b }
        assertTrue(steps.all { it > 0.0 }, "falls all the way up: $byHeight")
        // Evenly through the web; steeper at the flanges, right under the weight bearing on them.
        val inner = steps.subList(2, steps.size - 2)
        for (step in inner) assertEquals(inner.average(), step, 0.2 * inner.average(), "straight through the web: $byHeight")
        // The weight straddles the cut, so only its pinned-side half is drawn.
        val weight = scene.frame(t).meshes[1]
        val cutX = scene.model.lattice.restPositions.getValue(cap.keys.first()).x
        val rest = scene.frame(t).restPositions
        assertTrue(weight.surface.triangles.flatMap { listOf(it.a, it.b, it.c) }.all { rest.getValue(it).x <= cutX + 1e-9 })
    }

    @Test
    fun `a lengthwise section shows the back half, the web's shear peaking at its middle`() {
        val scene = IBeamScene()
        val t = run(scene, 4000)
        val shape = scene.model.shape
        val middle = shape.flangeWidthCells / 2
        val (nodes, beam) = cut(scene, "lengthwise", t)
        assertTrue(nodes.values.all { it.k <= middle })
        // A quarter of the way along, between support and weight, the web carries the shear.
        val quarter = shape.lengthCells / 4
        val shear = beam.scalarFields[2].values
        val down = nodes.filterValues { it.k == middle && it.i == quarter }.entries.sortedBy { it.value.j }.map { abs(shear.getValue(it.key)) }
        assertEquals(shape.depthCells + 1, down.size)
        val atMiddle = down[shape.depthCells / 2]
        assertTrue(atMiddle > 0.0)
        assertTrue(atMiddle > down.first() && atMiddle > down.last(), "most at mid-depth, least at the flange faces: $down")
    }

    @Test
    fun `every section is offered, cuts the springs too, and off restores the whole beam`() {
        val scene = IBeamScene()
        val t = run(scene, 100)
        val whole = scene.frame(t).meshes.first().surface.triangles.size
        assertEquals(IBeamScene.SECTIONS, scene.frame(t).options.single { it.name == "section" }.choices)
        val lattice = scene.model.lattice
        val shape = scene.model.shape
        for (section in IBeamScene.SECTIONS.drop(1)) {
            val (nodes, _) = cut(scene, section, t)
            assertTrue(nodes.size < lattice.nodeIds.size, section)
        }
        scene.handleControl(SceneControlMessage.SetSceneOption("section", "near support"), t)
        val nearSupport = shape.lengthCells - shape.depthCells / 2
        scene.handleControl(SceneControlMessage.SetSceneOption("springs", "stretch"), t)
        val springs = scene.frame(t).connections
        assertTrue(springs.isNotEmpty())
        assertTrue(springs.all { (a, b) -> lattice.indexOf(a)!!.i <= nearSupport && lattice.indexOf(b)!!.i <= nearSupport })
        assertTrue(springs.any { (a, _) -> lattice.indexOf(a)!!.i == nearSupport })
        scene.handleControl(SceneControlMessage.SetSceneOption("springs", "hidden"), t)
        scene.handleControl(SceneControlMessage.SetSceneOption("section", "sideways"), t)
        assertEquals("near support", scene.frame(t).options.single { it.name == "section" }.selected)
        scene.handleControl(SceneControlMessage.SetSceneOption("section", "off"), t)
        assertEquals(whole, scene.frame(t).meshes.first().surface.triangles.size)
    }

    @Test
    fun `the failure option switches the steel's failure model`() {
        val scene = IBeamScene()
        scene.handleControl(SceneControlMessage.SetSceneOption("failure", "on"), 0.0)
        assertEquals("on", scene.frame(0.0).options.single { it.name == "failure" }.selected)
        scene.handleControl(SceneControlMessage.SetSceneOption("failure", "off"), 0.0)
        assertEquals("off", scene.frame(0.0).options.single { it.name == "failure" }.selected)
    }

    @Test
    fun `deformation stops being exaggerated once the beam tears or sags past the limit`() {
        assertTrue(IBeamScene.exaggerates(torn = false, sag = -0.01))
        assertTrue(!IBeamScene.exaggerates(torn = true, sag = -0.001))
        assertTrue(!IBeamScene.exaggerates(torn = false, sag = -IBeamScene.EXAGGERATION_LIMIT - 1e-6))
    }
}
