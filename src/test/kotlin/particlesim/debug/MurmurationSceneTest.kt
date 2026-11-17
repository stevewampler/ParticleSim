package particlesim.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [MurmurationScene]: dark birds drawn solid against a dusk sky, the field hidden. */
class MurmurationSceneTest {
    @Test
    fun `the birds are drawn as solid silhouettes against the sky, and the ground's particles aren't drawn`() {
        val scene = MurmurationScene()
        val frame = scene.frame(0.0)
        assertNotNull(frame.skyColor)
        assertTrue(frame.solidParticleColors)
        val visible = assertNotNull(frame.visibleIds)
        assertEquals(1501, visible.size, "1500 starlings and the falcon")
        assertTrue(visible.all { it in frame.particleColors && it in frame.sphereRadii })
        assertEquals(4, scene.ids().size - visible.size, "the field's four corners")
        assertEquals(listOf("ground"), frame.meshes.map { it.surface.name })
        assertTrue(frame.meshes.none { it.pickable }, "the ground is scenery - the mouse can't grab it")
    }

    @Test
    fun `the falcon can be called off from its group's panel`() {
        val scene = MurmurationScene()
        scene.handleControl(SceneControlMessage.SetGroupEnabled("falcon", false), 0.0)
        assertTrue("falcon" in scene.frame(0.0).registry.groupEnabled.filterValues { !it }.keys)
    }
}
