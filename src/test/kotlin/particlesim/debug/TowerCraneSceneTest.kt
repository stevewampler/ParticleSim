package particlesim.debug

import particlesim.core.Vector3
import particlesim.render.ColorRamp
import particlesim.render.srgbToLinear
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [TowerCraneScene]: the crane's members drawn as steel coloured by stress, blocks that turn
 * with it, and controls reached through the viewer's field edits. */
class TowerCraneSceneTest {
    private fun run(scene: TowerCraneScene, seconds: Double, from: Double = 0.0): Double {
        var t = from
        while (t < from + seconds) { scene.step(t); t += scene.dt }
        return t
    }

    @Test
    fun `every member is drawn as styled steel, coloured by its stress with a legend`() {
        val scene = TowerCraneScene()
        val t = run(scene, 1.0)
        val frame = scene.frame(t)
        val crane = scene.model
        val members = crane.truss.members.map { it.a to it.b }
        assertEquals(members, frame.connections, "no load yet, so no slings")
        assertEquals(members.toSet(), frame.connectionStyles.keys)
        assertEquals(setOf("chords", "lattice", "turntable", "tie bars", "hoist rope"), frame.connectionStyles.values.map { it.name }.toSet())
        assertEquals(members.toSet(), frame.lineColors.keys)
        val i = crane.jibTie
        val expected = ColorRamp.signed(crane.truss.stress(crane.store, i) / TowerCraneScene.FULL_SCALE_STRESS).srgbToLinear()
        assertEquals(expected, frame.lineColors.getValue(members[i]))
        assertEquals("member stress", frame.lineLegend!!.label)
        assertEquals(-160.0 to 160.0, frame.lineLegend!!.min to frame.lineLegend!!.max)
        assertEquals(emptySet(), frame.visibleIds)
        assertTrue(frame.meshes.none { it.pickable })
        assertEquals(listOf("ballast", "winch", "cab", "trolley", "hook block", "foundation", "ground"), frame.meshes.map { it.surface.name })
        assertEquals(listOf("failure" to "off", "colours" to "stress", "gusts" to "off"), frame.options.map { it.name to it.selected })
        assertEquals(listOf(true, false, true), frame.options.map { it.toggle }, "failure and gusts are checkboxes")
        assertTrue(scene.model.forces.none { it.name == "controls" }, "the controls aren't a force any more")
    }

    @Test
    fun `paint shows the crane's colours instead, and an unknown choice changes nothing`() {
        val scene = TowerCraneScene()
        scene.handleControl(SceneControlMessage.SetSceneOption("colours", "paint"), 0.0)
        val frame = scene.frame(0.0)
        assertTrue(frame.lineColors.isEmpty())
        assertNull(frame.lineLegend)
        scene.handleControl(SceneControlMessage.SetSceneOption("colours", "plaid"), 0.0)
        assertEquals("paint", scene.frame(0.0).options.single { it.name == "colours" }.selected)
    }

    @Test
    fun `the scene's controls set the crane's targets, show where it has got to, and a load hangs on slings`() {
        val scene = TowerCraneScene()
        val crane = scene.model
        val before = scene.frame(0.0).controls
        assertEquals(listOf("slew", "radius", "hook height", "load", "wind", "wind direction"), before.map { it.name })
        assertEquals(listOf("°", "m", "m", "t", "m/s", "°"), before.map { it.unit })
        assertEquals(crane.spec.minRadius to crane.spec.maxRadius, before[1].min to before[1].max)
        scene.handleControl(SceneControlMessage.SetSceneControl("load", 3.0), 0.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("slew", 45.0), 0.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("radius", 500.0), 0.0)
        assertEquals(3.0, crane.loadTonnes)
        assertEquals(45.0, crane.slew.target)
        assertEquals(crane.spec.maxRadius, crane.trolley.target, "clamped")
        // Nonsense is refused.
        assertTrue(!scene.setControl("load", -2.0) && !scene.setControl("slew", Double.NaN) && !scene.setControl("speed", 1.0))
        assertEquals(3.0, crane.loadTonnes)
        val frame = scene.frame(0.0)
        val slew = frame.controls.single { it.name == "slew" }
        assertEquals(45.0 to 0.0, slew.value to slew.current, "set to 45, still at 0")
        assertEquals(0.0, frame.controls.single { it.name == "load" }.current, "the load hasn't come on yet")
        assertTrue("load" in frame.meshes.map { it.surface.name })
        val slings = frame.connections.filter { it.first == crane.hookNode }
        assertEquals(4, slings.size)
        assertTrue(slings.all { frame.connectionStyles.getValue(it).name == "slings" })
        // The load hangs below the hook.
        val load = frame.meshes.single { it.surface.name == "load" }.surface.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet()
        assertTrue(load.all { scene.store.position(it).y < scene.store.position(crane.hookNode).y - TowerCraneScene.SLING_DROP + 1e-9 })
    }

    @Test
    fun `the ballast and cab turn with the slew`() {
        val scene = TowerCraneScene()
        var t = run(scene, scene.model.readyAt)
        scene.handleControl(SceneControlMessage.SetSceneControl("slew", 90.0), t)
        t = run(scene, 32.0, t)
        val frame = scene.frame(t)
        fun centre(name: String): Vector3 {
            val ids = frame.meshes.single { it.surface.name == name }.surface.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet()
            return ids.fold(Vector3.ZERO) { s, id -> s + scene.store.position(id) } * (1.0 / ids.size)
        }
        assertEquals(90.0, scene.model.slewDegrees, 1e-9)
        // At zero the ballast is behind the mast (-x); turned 90 degrees it's at +z.
        val ballast = centre("ballast")
        assertEquals(90.0, atan2(ballast.z, -ballast.x) * 180 / PI, 3.0, "ballast at $ballast")
        assertTrue(ballast.z > 10.0)
        val cab = centre("cab")
        // The cab rides beside the turntable on the +z side; turned 90 degrees that side faces +x.
        assertTrue(cab.x > 1.0 && cab.z < 0.0, "the cab has swung round with it: $cab")
    }

    @Test
    fun `the failure checkbox switches the steel's failure on, and torn members aren't drawn`() {
        val scene = TowerCraneScene()
        scene.handleControl(SceneControlMessage.SetSceneOption("failure", "on"), 0.0)
        assertTrue(scene.model.failure)
        assertEquals("on", scene.frame(0.0).options.single { it.name == "failure" }.selected)
        // Twice the load chart brings it down; what tore is left out of the drawing.
        var t = run(scene, scene.model.readyAt + 1.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("radius", scene.model.spec.maxLoadRadius), t)
        scene.handleControl(SceneControlMessage.SetSceneControl("load", 2.5 * scene.model.spec.maxLoadTonnes), t)
        t = run(scene, 25.0, t)
        val crane = scene.model
        val torn = (0 until crane.truss.size).filter { crane.truss.isTorn(it) }.map { crane.truss.members[it].let { m -> m.a to m.b } }
        assertTrue(torn.isNotEmpty(), "something tore")
        val frame = scene.frame(t)
        assertTrue(torn.none { it in frame.connections || it in frame.connectionStyles || it in frame.lineColors })
        scene.handleControl(SceneControlMessage.SetSceneOption("failure", "off"), t)
        assertTrue(!crane.failure)
    }
}
