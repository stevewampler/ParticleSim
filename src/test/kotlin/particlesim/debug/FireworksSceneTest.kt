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
    fun `launches and bursts reach the event channel as sounds`() {
        val scene = FireworksScene()
        val sounds = mutableListOf<SimEvent.Sound>()
        var t = 0.0
        repeat(600) {
            scene.step(t); t += scene.dt
            sounds += scene.frame(t).events.filterIsInstance<SimEvent.Sound>()
        }
        assertTrue(sounds.any { it.kind == SoundKind.LAUNCH })
        assertTrue(sounds.any { it.kind == SoundKind.BOOM })
        assertTrue(sounds.all { it.time in 0.0..t })
        // Each frame passes its sounds on once, then forgets them.
        assertTrue(scene.frame(t).events.isEmpty())
    }

    @Test
    fun `glitter twinkles between a quarter and full brightness, the same at the same time`() {
        val gold = FireworksShow.GLITTER_GOLD
        val levels = (0 until 200).map { FireworksScene.twinkle(gold, 17, it / FireworksScene.TWINKLE_RATE).r / gold.r }
        assertTrue(levels.all { it in 0.25..1.0 })
        assertTrue(levels.max() - levels.min() > 0.5, "it actually flickers")
        assertEquals(FireworksScene.twinkle(gold, 17, 3.01), FireworksScene.twinkle(gold, 17, 3.01))
    }

    @Test
    fun `strobes blink, lit about 30% of the time, each at its own phase`() {
        val white = FireworksShow.STROBE_WHITE
        fun lit(id: Int, t: Double) = FireworksScene.strobe(white, id, t) == white
        val samples = (0 until 7000).map { it / 7000.0 * 10.0 }
        val litFraction = samples.count { lit(5, it) }.toDouble() / samples.size
        assertEquals(FireworksScene.STROBE_DUTY, litFraction, 0.02)
        val dark = FireworksScene.strobe(white, 5, samples.first { !lit(5, it) })
        assertEquals(white.r * FireworksScene.STROBE_OFF_LEVEL, dark.r, 1e-12)
        // Different stars blink out of step.
        // Each star's switch-on time within the first cycle, found by a fine scan.
        val fine = (1..20000).map { it * 1e-5 }
        val phases = (1..20).map { id -> fine.first { lit(id, it) && !lit(id, it - 1e-5) } }
        assertTrue(phases.max() - phases.min() > 0.05, "phases $phases")
    }

    @Test
    fun `palm fronds are drawn larger than other stars`() {
        val scene = FireworksScene()
        val show = scene.show
        show.launch(0.0, particlesim.examples.BurstType.PALM)
        var t = 0.0
        while (show.palmFrondIds.isEmpty()) { scene.step(t); t += scene.dt }
        val frame = scene.frame(t)
        assertTrue(show.palmFrondIds.all { frame.sphereRadii[it] == FireworksScene.FROND_DRAW_RADIUS })
        assertTrue(show.starColorsById.keys.filter { it !in show.palmFrondIds }.all { frame.sphereRadii[it] == FireworksScene.STAR_DRAW_RADIUS })
    }
}
