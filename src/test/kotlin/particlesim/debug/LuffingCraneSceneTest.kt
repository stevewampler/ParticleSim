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

/** [LuffingCraneScene]: the luffing crane's members drawn as steel coloured by stress, blocks
 * that turn with it, and its controls - the jib angle and radius moving together. */
class LuffingCraneSceneTest {
    private fun run(scene: LuffingCraneScene, seconds: Double, from: Double = 0.0): Double {
        var t = from
        while (t < from + seconds) { scene.step(t); t += scene.dt }
        return t
    }

    @Test
    fun `every member is drawn as styled steel or rope, coloured by its stress with a legend`() {
        val scene = LuffingCraneScene()
        val t = run(scene, 1.0)
        val frame = scene.frame(t)
        val crane = scene.model
        val members = crane.truss.members.map { it.a to it.b }
        assertEquals(members, frame.connections, "no load yet, so no slings")
        assertEquals(members.toSet(), frame.connectionStyles.keys)
        assertEquals(
            setOf("chords", "lattice", "turntable", "A-frame", "tie bars", "luffing rope", "hoist rope"),
            frame.connectionStyles.values.map { it.name }.toSet(),
        )
        val rope = crane.luffingRope
        assertEquals(ColorRamp.signed(crane.truss.stress(crane.store, rope) / LuffingCraneScene.FULL_SCALE_STRESS).srgbToLinear(), frame.lineColors.getValue(members[rope]))
        assertEquals("member stress", frame.lineLegend!!.label)
        assertEquals(emptySet(), frame.visibleIds)
        assertEquals(listOf("ballast", "hoist winch", "luffing winch", "cab", "hook block", "foundation", "ground"), frame.meshes.map { it.surface.name })
        assertEquals(listOf("failure" to "off", "colours" to "stress", "gusts" to "off"), frame.options.map { it.name to it.selected })
        assertEquals(listOf(true, false, true), frame.options.map { it.toggle }, "failure and gusts are checkboxes")
    }

    @Test
    fun `paint shows the crane's colours instead`() {
        val scene = LuffingCraneScene()
        scene.handleControl(SceneControlMessage.SetSceneOption("colours", "paint"), 0.0)
        val frame = scene.frame(0.0)
        assertTrue(frame.lineColors.isEmpty())
        assertNull(frame.lineLegend)
    }

    @Test
    fun `the controls set the crane's targets, the jib angle and radius are one target, and a load hangs on slings`() {
        val scene = LuffingCraneScene()
        val crane = scene.model
        val before = scene.frame(0.0).controls
        assertEquals(listOf("slew", "jib angle", "radius", "hook height", "load", "wind", "wind direction"), before.map { it.name })
        assertEquals(listOf("°", "°", "m", "m", "t", "m/s", "°"), before.map { it.unit })
        assertEquals(crane.spec.minJibAngle to crane.spec.maxJibAngle, before[1].min to before[1].max)
        assertEquals(crane.spec.minRadius to crane.spec.maxRadius, before[2].min to before[2].max)
        scene.handleControl(SceneControlMessage.SetSceneControl("radius", 30.0), 0.0)
        assertEquals(crane.spec.jibAngleFor(30.0), crane.luff.target, 1e-9)
        assertEquals(30.0, scene.frame(0.0).controls.single { it.name == "radius" }.value, 1e-9)
        scene.handleControl(SceneControlMessage.SetSceneControl("jib angle", 60.0), 0.0)
        assertEquals(crane.spec.headRadius(60.0), scene.frame(0.0).controls.single { it.name == "radius" }.value, 1e-9, "moving one moves the other")
        scene.handleControl(SceneControlMessage.SetSceneControl("slew", 45.0), 0.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("hook height", 30.0), 0.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("load", 5.0), 0.0)
        assertEquals(45.0, crane.slew.target)
        assertEquals(30.0, crane.hookHeightTarget)
        assertEquals(5.0, crane.loadTonnes)
        assertTrue(!scene.setControl("load", -2.0) && !scene.setControl("jib angle", Double.NaN) && !scene.setControl("speed", 1.0))
        val frame = scene.frame(0.0)
        assertEquals(45.0 to 0.0, frame.controls.single { it.name == "slew" }.let { it.value to it.current })
        assertEquals(45.0, frame.controls.single { it.name == "jib angle" }.current!!, 1e-9, "built at 45 degrees, not luffed yet")
        val slings = frame.connections.filter { it.first == crane.hookNode }
        assertEquals(4, slings.size)
        assertTrue(slings.all { frame.connectionStyles.getValue(it).name == "slings" })
        assertTrue("load" in frame.meshes.map { it.surface.name })
    }

    @Test
    fun `the ballast and winches turn with the slew`() {
        val scene = LuffingCraneScene()
        var t = run(scene, scene.model.readyAt)
        scene.handleControl(SceneControlMessage.SetSceneControl("slew", 90.0), t)
        t = run(scene, 32.0, t)
        val frame = scene.frame(t)
        fun centre(name: String): Vector3 {
            val ids = frame.meshes.single { it.surface.name == name }.surface.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet()
            return ids.fold(Vector3.ZERO) { s, id -> s + scene.store.position(id) } * (1.0 / ids.size)
        }
        assertEquals(90.0, scene.model.slewDegrees, 1e-9)
        // At zero the counter-jib is behind the mast (-x); turned 90 degrees it's at +z.
        for (name in listOf("ballast", "luffing winch")) {
            val c = centre(name)
            assertEquals(90.0, atan2(c.z, -c.x) * 180 / PI, 3.0, "$name at $c")
        }
    }

    @Test
    fun `the failure checkbox switches the steel's failure on, and torn members aren't drawn`() {
        val scene = LuffingCraneScene()
        scene.handleControl(SceneControlMessage.SetSceneOption("failure", "on"), 0.0)
        assertTrue(scene.model.failure)
        assertEquals("on", scene.frame(0.0).options.single { it.name == "failure" }.selected)
        var t = run(scene, scene.model.readyAt)
        // Luffing down with the most the load control allows: the A-frame buckles and the jib
        // comes down, tearing something as it lands.
        scene.handleControl(SceneControlMessage.SetSceneControl("jib angle", 15.0), t)
        scene.handleControl(SceneControlMessage.SetSceneControl("load", LuffingCraneScene.MAX_LOAD_CONTROL), t)
        t = run(scene, 40.0, t)
        val crane = scene.model
        val torn = (0 until crane.truss.size).filter { crane.truss.isTorn(it) }.map { crane.truss.members[it].let { m -> m.a to m.b } }
        assertTrue(torn.isNotEmpty(), "something tore")
        val frame = scene.frame(t)
        assertTrue(torn.none { it in frame.connections || it in frame.connectionStyles || it in frame.lineColors })
        scene.handleControl(SceneControlMessage.SetSceneOption("failure", "off"), t)
        assertTrue(!crane.failure)
    }
}
