package particlesim.debug

import particlesim.core.Vector3
import particlesim.render.ColorRamp
import particlesim.render.srgbToLinear
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [GantryCraneScene]: the goliath crane's members drawn as steel coloured by stress, blocks that
 * travel with it, and its controls. */
class GantryCraneSceneTest {
    private fun run(scene: GantryCraneScene, seconds: Double, from: Double = 0.0): Double {
        var t = from
        while (t < from + seconds) { scene.step(t); t += scene.dt }
        return t
    }

    @Test
    fun `every member is drawn as styled steel or rope, coloured by its stress with a legend`() {
        val scene = GantryCraneScene()
        val t = run(scene, 1.0)
        val frame = scene.frame(t)
        val crane = scene.model
        val members = crane.truss.members.map { it.a to it.b }
        assertEquals(members, frame.connections, "no load yet, so no slings")
        assertEquals(members.toSet(), frame.connectionStyles.keys)
        assertEquals(
            setOf("chords", "lattice", "legs", "leg braces", "sills", "hoist rope"),
            frame.connectionStyles.values.map { it.name }.toSet(),
        )
        val chord = crane.midspanTopChords.first()
        assertEquals(ColorRamp.signed(crane.truss.stress(crane.store, chord) / GantryCraneScene.FULL_SCALE_STRESS).srgbToLinear(), frame.lineColors.getValue(members[chord]))
        assertEquals("member stress", frame.lineLegend!!.label)
        assertEquals(emptySet(), frame.visibleIds)
        assertEquals(
            listOf("trolley", "cab", "hook block", "bogie 1", "bogie 2", "bogie 3", "bogie 4", "rail 1", "rail 2", "ground"),
            frame.meshes.map { it.surface.name },
        )
        assertEquals(listOf("failure" to "off", "colours" to "stress"), frame.options.map { it.name to it.selected })
        assertEquals(listOf(true, false), frame.options.map { it.toggle }, "failure is a checkbox")
    }

    @Test
    fun `paint shows the crane's colours instead`() {
        val scene = GantryCraneScene()
        scene.handleControl(SceneControlMessage.SetSceneOption("colours", "paint"), 0.0)
        val frame = scene.frame(0.0)
        assertTrue(frame.lineColors.isEmpty())
        assertNull(frame.lineLegend)
    }

    @Test
    fun `the controls set the crane's targets, and a load hangs on slings`() {
        val scene = GantryCraneScene()
        val crane = scene.model
        val spec = crane.spec
        val before = scene.frame(0.0).controls
        assertEquals(listOf("travel", "trolley", "hook height", "load"), before.map { it.name })
        assertEquals(listOf("m", "m", "m", "t"), before.map { it.unit })
        assertEquals(-spec.travelRange to spec.travelRange, before[0].min to before[0].max)
        assertEquals(spec.minTrolley to spec.maxTrolley, before[1].min to before[1].max)
        scene.handleControl(SceneControlMessage.SetSceneControl("travel", 12.0), 0.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("trolley", -6.0), 0.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("hook height", 10.0), 0.0)
        scene.handleControl(SceneControlMessage.SetSceneControl("load", 25.0), 0.0)
        assertEquals(12.0, crane.travel.target)
        assertEquals(-6.0, crane.trolley.target)
        assertEquals(10.0, crane.hookHeightTarget)
        assertEquals(25.0, crane.loadTonnes)
        assertTrue(!scene.setControl("load", -2.0) && !scene.setControl("travel", Double.NaN) && !scene.setControl("slew", 1.0))
        val frame = scene.frame(0.0)
        assertEquals(12.0 to 0.0, frame.controls.single { it.name == "travel" }.let { it.value to it.current!! })
        val slings = frame.connections.filter { it.first == crane.hookNode }
        assertEquals(4, slings.size)
        assertTrue(slings.all { frame.connectionStyles.getValue(it).name == "slings" })
        assertTrue("load" in frame.meshes.map { it.surface.name })
    }

    @Test
    fun `the trolley and bogies travel with the gantry`() {
        val scene = GantryCraneScene()
        var t = run(scene, scene.model.readyAt)
        scene.handleControl(SceneControlMessage.SetSceneControl("travel", 10.0), t)
        t = run(scene, 25.0, t)
        val frame = scene.frame(t)
        fun centre(name: String): Vector3 {
            val ids = frame.meshes.single { it.surface.name == name }.surface.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet()
            return ids.fold(Vector3.ZERO) { s, id -> s + scene.store.position(id) } * (1.0 / ids.size)
        }
        assertEquals(10.0, scene.model.travelPosition, 0.01)
        assertEquals(10.0, centre("trolley").z, 0.05)
        assertEquals(10.0 - scene.model.spec.legSpread / 2, centre("bogie 1").z, 0.05)
        assertEquals(0.0, centre("rail 1").z, 1e-9, "the rails stay put")
    }

    @Test
    fun `the failure checkbox switches the steel's failure on, and torn members aren't drawn`() {
        val scene = GantryCraneScene()
        scene.handleControl(SceneControlMessage.SetSceneOption("failure", "on"), 0.0)
        assertTrue(scene.model.failure)
        assertEquals("on", scene.frame(0.0).options.single { it.name == "failure" }.selected)
        var t = run(scene, scene.model.readyAt)
        // The most the load control allows, mid-span: the top chords buckle and the girder
        // comes down, tearing something as it lands.
        scene.handleControl(SceneControlMessage.SetSceneControl("load", GantryCraneScene.MAX_LOAD_CONTROL), t)
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
