package particlesim.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/** No scene's camera follows its load by default: every scene that used to track the load (or
 * lead train car) now holds one fixed overview pose while the load moves. */
class BridgeSceneCameraTest {
    private fun checkFixed(name: String, scene: DemoScene, loadId: Int) {
        val before = assertNotNull(scene.frame(0.0).camera, "$name: should still send a camera")
        val loadStart = scene.store.position(loadId)
        var t = 0.0
        repeat((4.0 / scene.dt).toInt()) { scene.step(t); t += scene.dt }
        assertNotEquals(loadStart, scene.store.position(loadId), "$name: load should have moved")
        assertEquals(before, scene.frame(t).camera, "$name: camera moved with the load")
    }

    @Test fun bridge() = BridgeScene().let { checkFixed("bridge", it, it.scenario.loadId) }
    @Test fun suspensionBridge() = SuspensionBridgeScene().let { checkFixed("suspensionBridge", it, it.scenario.loadId) }
    @Test fun trainTrestle() = TrainTrestleScene().let { checkFixed("trainTrestle", it, it.scenario.trainIds.first()) }
    @Test fun archBridge() = ArchBridgeScene().let { checkFixed("archBridge", it, it.scenario.loadId) }
    @Test fun cableStayedBridge() = CableStayedBridgeScene().let { checkFixed("cableStayedBridge", it, it.scenario.loadId) }
}
