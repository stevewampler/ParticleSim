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

/** [CrawlerCraneScene]: the crawler crane's members drawn as steel coloured by stress, blocks that
 * slew and tilt with it, and its controls. */
class CrawlerCraneSceneTest {
    private fun run(scene: CrawlerCraneScene, seconds: Double, from: Double = 0.0): Double {
        var t = from
        while (t < from + seconds) { scene.step(t); t += scene.dt }
        return t
    }

    @Test
    fun `every member is drawn as styled steel or rope, coloured by its stress with a legend`() {
        val scene = CrawlerCraneScene()
        val t = run(scene, 1.0)
        val frame = scene.frame(t)
        val crane = scene.model
        val members = crane.truss.members.map { it.a to it.b }
        assertEquals(members, frame.connections, "no load yet, so no slings")
        assertEquals(members.toSet(), frame.connectionStyles.keys)
        assertEquals(
            setOf("boom chords", "boom lattice", "frame", "gantry", "tracks", "pendants", "boom hoist rope", "hoist rope"),
            frame.connectionStyles.values.map { it.name }.toSet(),
        )
        val rope = crane.boomHoistRope
        assertEquals(ColorRamp.signed(crane.truss.stress(crane.store, rope) / CrawlerCraneScene.FULL_SCALE_STRESS).srgbToLinear(), frame.lineColors.getValue(members[rope]))
        assertEquals("member stress", frame.lineLegend!!.label)
        assertEquals(emptySet(), frame.visibleIds)
        assertEquals(listOf("track 1", "track 2", "counterweight", "cab", "hook block", "ground"), frame.meshes.map { it.surface.name })
        assertEquals(listOf("failure" to "off", "colours" to "stress"), frame.options.map { it.name to it.selected })
        assertEquals(listOf(true, false), frame.options.map { it.toggle }, "failure is a checkbox")
    }

    @Test
    fun `paint shows the crane's colours instead`() {
        val scene = CrawlerCraneScene()
        scene.handleControl(SceneControlMessage.SetSceneOption("colours", "paint"), 0.0)
        val frame = scene.frame(0.0)
        assertTrue(frame.lineColors.isEmpty())
        assertNull(frame.lineLegend)
    }

    @Test
    fun `the controls set the crane's targets, the boom angle and radius are one target, and a load hangs on slings`() {
        val scene = CrawlerCraneScene()
        val crane = scene.model
        val spec = crane.spec
        val before = scene.frame(0.0).controls
        assertEquals(listOf("slew", "boom angle", "radius", "hook height", "load"), before.map { it.name })
        assertEquals(listOf("°", "°", "m", "m", "t"), before.map { it.unit })
        assertEquals(spec.minBoomAngle to spec.maxBoomAngle, before[1].min to before[1].max)
        assertEquals(spec.minRadius to spec.maxRadius, before[2].min to before[2].max)
        scene.handleControl(SceneControlMessage.SetSceneControl("radius", 30.0), 0.0)
        assertEquals(spec.boomAngleFor(30.0), crane.luff.target, 1e-9)
        scene.handleControl(SceneControlMessage.SetSceneControl("boom angle", 70.0), 0.0)
        assertEquals(spec.tipRadius(70.0), scene.frame(0.0).controls.single { it.name == "radius" }.value, 1e-9, "moving one moves the other")
        scene.handleControl(SceneControlMessage.SetSceneControl("slew", 30.0), 0.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("hook height", 12.0), 0.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("load", 5.0), 0.0)
        assertEquals(30.0, crane.slew.target)
        assertEquals(12.0, crane.hookHeightTarget)
        assertEquals(5.0, crane.loadTonnes)
        assertTrue(!scene.setControl("load", -2.0) && !scene.setControl("slew", Double.NaN) && !scene.setControl("travel", 1.0))
        val frame = scene.frame(0.0)
        val slings = frame.connections.filter { it.first == crane.hookNode }
        assertEquals(4, slings.size)
        assertTrue(slings.all { frame.connectionStyles.getValue(it).name == "slings" })
        assertTrue("load" in frame.meshes.map { it.surface.name })
    }

    @Test
    fun `the failure checkbox switches the steel's failure on and off`() {
        val scene = CrawlerCraneScene()
        scene.handleControl(SceneControlMessage.SetSceneOption("failure", "on"), 0.0)
        assertTrue(scene.model.failure)
        assertEquals("on", scene.frame(0.0).options.single { it.name == "failure" }.selected)
        scene.handleControl(SceneControlMessage.SetSceneOption("failure", "off"), 0.0)
        assertTrue(!scene.model.failure)
    }

    private fun centre(scene: CrawlerCraneScene, frame: SceneFrame, name: String): Vector3 {
        val ids = frame.meshes.single { it.surface.name == name }.surface.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet()
        return ids.fold(Vector3.ZERO) { s, id -> s + scene.store.position(id) } * (1.0 / ids.size)
    }

    @Test
    fun `the counterweight turns with the slew and the tracks stay put`() {
        val scene = CrawlerCraneScene()
        var t = run(scene, scene.model.readyAt)
        val tracksBefore = centre(scene, scene.frame(t), "track 2")
        scene.handleControl(SceneControlMessage.SetSceneControl("slew", 90.0), t)
        t = run(scene, 26.0, t)
        val frame = scene.frame(t)
        assertEquals(90.0, scene.model.slewDegrees, 1e-9)
        // Behind the slewing axis (-x) at slew zero; turned 90 degrees it's at +z.
        val c = centre(scene, frame, "counterweight")
        assertEquals(90.0, atan2(c.z, -c.x) * 180 / PI, 3.0, "counterweight at $c")
        assertTrue((centre(scene, frame, "track 2") - tracksBefore).length() < 0.01)
    }

    @Test
    fun `seventeen tonnes it holds over the end tips it over when slewed over the side, the tracks tilting with it`() {
        val scene = CrawlerCraneScene()
        scene.handleControl(SceneControlMessage.SetSceneControl("boom angle", 45.0), 0.0)
        var t = run(scene, scene.model.readyAt + 32.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("load", 17.0), t)
        t = run(scene, 15.0, t)
        assertTrue(scene.model.tilt < 1.0, "over the end it stands: ${scene.model.tilt} degrees")
        scene.handleControl(SceneControlMessage.SetSceneControl("slew", 90.0), t)
        t = run(scene, 40.0, t)
        assertTrue(scene.model.tilt > 15.0, "over the side it goes over: ${scene.model.tilt} degrees")
        // The far track's block went up with it.
        val track = centre(scene, scene.frame(t), "track 2")
        assertTrue(track.y > 1.5, "the +z track lifted: $track")
    }
}
