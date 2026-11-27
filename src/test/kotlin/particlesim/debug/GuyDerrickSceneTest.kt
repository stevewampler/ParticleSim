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

/** [GuyDerrickScene]: the derrick's members drawn as steel and rope coloured by stress, a bull
 * wheel that swings with it, and its controls. */
class GuyDerrickSceneTest {
    private fun run(scene: GuyDerrickScene, seconds: Double, from: Double = 0.0): Double {
        var t = from
        while (t < from + seconds) { scene.step(t); t += scene.dt }
        return t
    }

    @Test
    fun `every member is drawn as styled steel or rope, coloured by its stress with a legend`() {
        val scene = GuyDerrickScene()
        val t = run(scene, 1.0)
        val frame = scene.frame(t)
        val derrick = scene.model
        val members = derrick.truss.members.map { it.a to it.b }
        assertEquals(members, frame.connections, "no load yet, so no slings")
        assertEquals(members.toSet(), frame.connectionStyles.keys)
        assertEquals(
            setOf("mast chords", "mast lattice", "boom chords", "boom lattice", "guys", "topping lift", "hoist rope"),
            frame.connectionStyles.values.map { it.name }.toSet(),
        )
        val guy = derrick.guys[0]
        assertEquals(ColorRamp.signed(derrick.truss.stress(derrick.store, guy) / GuyDerrickScene.FULL_SCALE_STRESS).srgbToLinear(), frame.lineColors.getValue(members[guy]))
        assertEquals("member stress", frame.lineLegend!!.label)
        assertEquals(emptySet(), frame.visibleIds)
        assertEquals(
            listOf("bull wheel", "hook block", "base") + (1..8).map { "anchor $it" } + listOf("winch house", "ground"),
            frame.meshes.map { it.surface.name },
        )
        assertEquals(listOf("failure" to "off", "colours" to "stress", "gusts" to "off"), frame.options.map { it.name to it.selected })
        assertEquals(listOf(true, false, true), frame.options.map { it.toggle }, "failure and gusts are checkboxes")
    }

    @Test
    fun `paint shows the derrick's colours instead`() {
        val scene = GuyDerrickScene()
        scene.handleControl(SceneControlMessage.SetSceneOption("colours", "paint"), 0.0)
        val frame = scene.frame(0.0)
        assertTrue(frame.lineColors.isEmpty())
        assertNull(frame.lineLegend)
    }

    @Test
    fun `the controls set the derrick's targets, the boom angle and radius are one target, and a load hangs on slings`() {
        val scene = GuyDerrickScene()
        val derrick = scene.model
        val spec = derrick.spec
        val before = scene.frame(0.0).controls
        assertEquals(listOf("slew", "boom angle", "radius", "hook height", "load", "wind", "wind direction"), before.map { it.name })
        assertEquals(listOf("°", "°", "m", "m", "t", "m/s", "°"), before.map { it.unit })
        assertEquals(spec.minBoomAngle to spec.maxBoomAngle, before[1].min to before[1].max)
        assertEquals(spec.minRadius to spec.maxRadius, before[2].min to before[2].max)
        scene.handleControl(SceneControlMessage.SetSceneControl("radius", 20.0), 0.0)
        assertEquals(spec.boomAngleFor(20.0), derrick.luff.target, 1e-9)
        scene.handleControl(SceneControlMessage.SetSceneControl("boom angle", 70.0), 0.0)
        assertEquals(spec.tipRadius(70.0), scene.frame(0.0).controls.single { it.name == "radius" }.value, 1e-9, "moving one moves the other")
        scene.handleControl(SceneControlMessage.SetSceneControl("slew", 30.0), 0.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("hook height", 12.0), 0.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("load", 5.0), 0.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("wind", 12.0), 0.0)
        assertEquals(30.0, derrick.slew.target)
        assertEquals(12.0, derrick.hookHeightTarget)
        assertEquals(5.0, derrick.loadTonnes)
        assertEquals(12.0, derrick.wind.speedTarget)
        assertTrue(!scene.setControl("load", -2.0) && !scene.setControl("slew", Double.NaN) && !scene.setControl("travel", 1.0))
        val frame = scene.frame(0.0)
        val slings = frame.connections.filter { it.first == derrick.hookNode }
        assertEquals(4, slings.size)
        assertTrue(slings.all { frame.connectionStyles.getValue(it).name == "slings" })
        assertTrue("load" in frame.meshes.map { it.surface.name })
    }

    @Test
    fun `the failure checkbox switches the steel's failure on and off`() {
        val scene = GuyDerrickScene()
        scene.handleControl(SceneControlMessage.SetSceneOption("failure", "on"), 0.0)
        assertTrue(scene.model.failure)
        assertEquals("on", scene.frame(0.0).options.single { it.name == "failure" }.selected)
        scene.handleControl(SceneControlMessage.SetSceneOption("failure", "off"), 0.0)
        assertTrue(!scene.model.failure)
    }

    private fun centre(scene: GuyDerrickScene, frame: SceneFrame, name: String): Vector3 {
        val ids = frame.meshes.single { it.surface.name == name }.surface.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet()
        return ids.fold(Vector3.ZERO) { s, id -> s + scene.store.position(id) } * (1.0 / ids.size)
    }

    @Test
    fun `swung round, the bull wheel turns with the mast and a load at full radius eases the guys in front`() {
        val scene = GuyDerrickScene()
        val derrick = scene.model
        scene.handleControl(SceneControlMessage.SetSceneControl("boom angle", derrick.spec.minBoomAngle), 0.0)
        var t = run(scene, derrick.readyAt + 35.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("load", 20.0), t)
        scene.handleControl(SceneControlMessage.SetSceneControl("slew", 90.0), t)
        t = run(scene, 40.0, t)
        val frame = scene.frame(t)
        // Corner 1 of the wheel's box is on the boom's side; turned 90 degrees, toward -z.
        val wheel = frame.meshes.single { it.surface.name == "bull wheel" }.surface
        val forward = scene.store.position(wheel.triangles.flatMap { listOf(it.a, it.b, it.c) }.maxBy { scene.store.position(it).let { p -> -p.z } })
        assertTrue(forward.z < -1.5, "the wheel turned with the mast: $forward")
        // The boom now points along -z: the guys toward -z (anchors 2 and 3, at 112.5 and 67.5
        // degrees) are the ones in front, slackest; those toward +z behind, tightest.
        val tensions = derrick.guyTensions
        val tip = scene.store.position(derrick.boomTip)
        assertEquals(-90.0, atan2(tip.z, tip.x) * 180 / PI, 0.5)
        assertTrue(maxOf(tensions[1], tensions[2]) < minOf(tensions[5], tensions[6]), "in front ${tensions[1]}, ${tensions[2]}; behind ${tensions[5]}, ${tensions[6]}")
    }
}
