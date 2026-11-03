package particlesim.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every bridge scene starts with only its load (or train) particles visible - the bridge's own
 * particles are hidden by default via [SceneFrame.hiddenByDefaultGroups], not removed outright,
 * so a group's "show particles" toggle can still bring them back. */
class BridgeSceneVisibilityTest {
    private fun visibleByDefault(scene: DemoScene): Set<Int> {
        val frame = scene.frame(0.0)
        val serverVisible = frame.visibleIds ?: scene.ids().toSet()
        val hidden = frame.hiddenByDefaultGroups.flatMap { frame.registry.groups[it].orEmpty() }.toSet()
        return serverVisible - hidden
    }

    private fun check(name: String, scene: DemoScene, loadIds: Set<Int>) {
        assertEquals(loadIds, visibleByDefault(scene), "$name: only the load should be visible by default")
        assertTrue(scene.frame(0.0).hiddenByDefaultGroups.isNotEmpty(), "$name: bridge groups should be hidden by default")
    }

    @Test fun bridge() = BridgeScene().let { check("bridge", it, setOf(it.scenario.loadId)) }
    @Test fun suspensionBridge() = SuspensionBridgeScene().let { check("suspensionBridge", it, setOf(it.scenario.loadId)) }
    @Test fun trainTrestle() = TrainTrestleScene().let { check("trainTrestle", it, it.scenario.trainIds.toSet()) }
    @Test fun archBridge() = ArchBridgeScene().let { check("archBridge", it, setOf(it.scenario.loadId)) }
    @Test fun cableStayedBridge() = CableStayedBridgeScene().let { check("cableStayedBridge", it, setOf(it.scenario.loadId)) }
}
