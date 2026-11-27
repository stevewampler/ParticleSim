package particlesim.debug

import particlesim.examples.CraneWind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [WindControls]: every crane scene's wind, direction and gusts reach its crane's wind. */
class WindControlsTest {
    private fun scenes(): List<Pair<DemoScene, CraneWind>> = listOf(
        TowerCraneScene().let { it to it.model.wind },
        LuffingCraneScene().let { it to it.model.wind },
        GantryCraneScene().let { it to it.model.wind },
        CrawlerCraneScene().let { it to it.model.wind },
    )

    @Test
    fun `each crane scene's wind controls set its crane's wind, and show what's blowing`() {
        for ((scene, wind) in scenes()) {
            scene.handleControl(SceneControlMessage.SetSceneControl("wind", 15.0), 0.0)
            scene.handleControl(SceneControlMessage.SetSceneControl("wind direction", 90.0), 0.0)
            scene.handleControl(SceneControlMessage.SetSceneOption("gusts", "on"), 0.0)
            assertEquals(15.0, wind.speedTarget)
            assertEquals(90.0, wind.directionTarget)
            assertTrue(wind.gusts)
            val frame = scene.frame(0.0)
            val speed = frame.controls.single { it.name == "wind" }
            assertEquals(15.0, speed.value)
            assertEquals(0.0, speed.current, "still coming up: none yet")
            assertEquals("on", frame.options.single { it.name == "gusts" }.selected)
            // A negative speed is refused; past the slider's top is clamped to it.
            scene.handleControl(SceneControlMessage.SetSceneControl("wind", -3.0), 0.0)
            assertEquals(15.0, wind.speedTarget)
            scene.handleControl(SceneControlMessage.SetSceneControl("wind", 99.0), 0.0)
            assertEquals(WindControls.MAX_WIND, wind.speedTarget)
            scene.handleControl(SceneControlMessage.SetSceneOption("gusts", "off"), 0.0)
            assertTrue(!wind.gusts)
        }
    }

    @Test
    fun `the wind comes up as the scene runs`() {
        val scene = TowerCraneScene()
        scene.handleControl(SceneControlMessage.SetSceneControl("wind", 10.0), 0.0)
        var t = 0.0
        while (t < 2.0) { scene.step(t); t += scene.dt }
        assertEquals(2.0 * CraneWind.SPEED_RAMP, scene.frame(t).controls.single { it.name == "wind" }.current!!, 0.01)
    }
}
