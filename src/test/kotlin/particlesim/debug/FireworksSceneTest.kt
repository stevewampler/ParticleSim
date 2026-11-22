package particlesim.debug

import particlesim.core.Vector3
import particlesim.examples.BurstFlash
import particlesim.examples.FireworksShow
import particlesim.render.Color
import particlesim.render.Light
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

    @Test
    fun `a burst lights the ground with a brief flash in its colour, over a faint moonlight`() {
        val red = FireworksShow.PALETTE[0]
        val flash = BurstFlash(Vector3(3.0, 110.0, -4.0), time = 2.0, color = red)
        assertEquals(null, FireworksScene.flashLight(flash, 1.99))
        val peak = assertNotNull(FireworksScene.flashLight(flash, 2.0))
        assertEquals(flash.position, peak.position)
        assertEquals(FireworksScene.FLASH_PEAK_INTENSITY, peak.intensity, 1e-9)
        assertEquals(Color((red.r + 1) / 2, (red.g + 1) / 2, (red.b + 1) / 2), peak.color)
        val later = assertNotNull(FireworksScene.flashLight(flash, 2.0 + FireworksScene.FLASH_DECAY_SECONDS))
        assertEquals(FireworksScene.FLASH_PEAK_INTENSITY / Math.E, later.intensity, 1e-6)
        assertEquals(null, FireworksScene.flashLight(flash, 2.0 + FireworksShow.FLASH_MEMORY))

        // In the running scene: moonlight always, plus a point light per burst still glowing.
        val scene = FireworksScene()
        var t = 0.0
        var sawFlash = false
        repeat(900) {
            scene.step(t); t += scene.dt
            val lights = scene.frame(t).lights
            assertTrue(lights.first() is Light.Ambient && lights.first().name == "moonlight")
            assertTrue(lights.drop(1).all { it is Light.Point && it.intensity > 0.0 })
            if (lights.size > 1) sawFlash = true
        }
        assertTrue(sawFlash)
    }

    @Test
    fun `glitter twinkles between a quarter and full brightness, the same at the same time`() {
        val gold = FireworksShow.GLITTER_GOLD
        val levels = (0 until 200).map { FireworksScene.twinkle(gold, 17, it / FireworksScene.TWINKLE_RATE).r / gold.r }
        assertTrue(levels.all { it in 0.25..1.0 })
        assertTrue(levels.max() - levels.min() > 0.5, "it actually flickers")
        assertEquals(FireworksScene.twinkle(gold, 17, 3.01), FireworksScene.twinkle(gold, 17, 3.01))
    }
}
