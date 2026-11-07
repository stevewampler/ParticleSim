package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.ShapePlacement
import particlesim.examples.TACOMA_DT
import particlesim.physics.MeshSprings
import particlesim.render.CameraFunction
import particlesim.render.CameraPose
import particlesim.render.TextureAssets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The two Tacoma Narrows bridges as reusable [SceneComponent]s. */
class TacomaBridgeComponentsTest {
    @Test
    fun `both bridges go side by side in one scene`() {
        val store = ParticleStore()
        val groups = Groups()
        val old = Tacoma1940Bridge(store, groups, ShapePlacement(Vector3(-12.0, 0.0, 0.0), "bridge-1940"))
        val new = Tacoma1950Bridge(store, groups, ShapePlacement(Vector3(12.0, 0.0, 0.0), "bridge-1950"))
        // Building the registry would throw on any clashing force/constraint/surface name.
        val scene = ComponentScene(store, groups, listOf(old, new), TACOMA_DT, CameraFunction { _, _ -> CameraPose(Vector3(0.0, 5.0, -60.0), Vector3.ZERO) })
        var t = 0.0
        repeat(2000) { scene.step(t); t += TACOMA_DT }
        val frame = scene.frame(t)
        assertEquals(setOf(TextureAssets.TACOMA_1940_ROADWAY, TextureAssets.TACOMA_1950_ROADWAY), frame.meshes.mapNotNull { it.textureName }.toSet())
        assertEquals(setOf("bridge-1940.flutter", "bridge-1950.flutter"), frame.shownByDefaultArrows)
        assertTrue(scene.ids().all { store.position(it).let { p -> p.x.isFinite() && p.y.isFinite() } })
        // Each bridge stays on its own side.
        assertTrue(old.scenario.mainSpan.all { store.position(it.centre).x < -10.0 })
        assertTrue(new.scenario.mainSpan.all { store.position(it.centre).x > 10.0 })
    }

    @Test
    fun `a 1940 bay that tears away keeps the rest of the roadway textured`() {
        val store = ParticleStore()
        val bridge = Tacoma1940Bridge(store, Groups(), breakable = true)
        val chords = bridge.scenario.forces.filterIsInstance<MeshSprings>().single { it.name == "girder-chords" }
        val (a, b) = bridge.scenario.mainSpan[20] to bridge.scenario.mainSpan[21]
        chords.breakEdge(chords.edgeIndexOf(a.topLeft, b.topLeft))
        chords.breakEdge(chords.edgeIndexOf(a.topRight, b.topRight))
        val events = bridge.afterStep(0.0, TACOMA_DT, emptyList())
        assertTrue(events.any { it is SimEvent.ForceBreak && it.name.contains("deck tore apart") }, "events: $events")
        val roadway = bridge.currentMeshes().single { it.textureName == TextureAssets.TACOMA_1940_ROADWAY }
        assertEquals(bridge.scenario.deckSurface.triangles.size - 4, roadway.surface.triangles.size)
        assertEquals(bridge.scenario.deckSurface.uvs, roadway.surface.uvs)
    }
}
