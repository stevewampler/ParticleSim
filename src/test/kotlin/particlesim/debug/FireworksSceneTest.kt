package particlesim.debug

import particlesim.examples.FireworksShow
import particlesim.render.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [FireworksScene]: glowing, trailing fireworks against a night sky, over unpickable ground. */
class FireworksSceneTest {
    @Test
    fun `every shell and star is drawn, coloured and trailing, against a night sky`() {
        val scene = FireworksScene()
        var t = 0.0
        repeat(600) { scene.step(t); t += scene.dt }
        val frame = scene.frame(t)
        assertNotNull(frame.skyColor)
        assertTrue(frame.trailSeconds > 0.0)
        assertTrue(!frame.solidParticleColors, "glowing, not solid")
        val visible = assertNotNull(frame.visibleIds)
        assertTrue(visible.size > 50, "10 s in, there's a display going: ${visible.size}")
        assertTrue(visible.all { it in frame.particleColors && it in frame.sphereRadii })
        assertEquals(scene.ids().toSet() - visible, scene.ids().filter { scene.store.position(it).y == 0.0 && it !in visible }.toSet())
        assertEquals(listOf("ground"), frame.meshes.map { it.surface.name })
        assertTrue(frame.meshes.none { it.pickable })
        assertTrue(frame.events.isNotEmpty(), "spawns and burn-outs reach the event channel")
    }

    @Test
    fun `a star flashes white as it lights, then shows its colour, then dims as it burns out`() {
        val red = FireworksShow.PALETTE[0]
        assertEquals(Color(1.0, 1.0, 1.0), FireworksScene.starColorAt(red, 0.0, 2.0))
        assertEquals(red, FireworksScene.starColorAt(red, 0.5, 2.0))
        val last = FireworksScene.starColorAt(red, 2.0, 2.0)
        assertEquals(red.r * 0.15, last.r, 1e-9)
    }
}
