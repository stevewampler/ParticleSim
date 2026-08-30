package particlesim.debug

import particlesim.core.ParticleStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A scene with no real physics, just enough state to observe what [SceneLibrary] does to it -
 * §15.3-style component test, isolating the library's own switching/dispatch logic from any
 * real scenario. */
private class StubScene : DemoScene {
    override val dt = 1.0
    override val store = ParticleStore()
    var stepsCalled = 0
    var lastControlMessage: SceneControlMessage? = null

    override fun ids(): List<Int> = emptyList()
    override fun step(t: Double) { stepsCalled++ }
    override fun handleControl(message: SceneControlMessage, t: Double) { lastControlMessage = message }
    override fun frame(t: Double): SceneFrame = SceneFrame()
}

class SceneLibraryTest {

    private fun library() = SceneLibrary(
        factories = mapOf("a" to { StubScene() }, "b" to { StubScene() }),
        defaultSceneName = "a",
    )

    @Test
    fun `starts on the default scene`() {
        val library = library()
        assertEquals("a", library.activeName)
        assertEquals(0.0, library.t)
        assertEquals(0L, library.step)
    }

    @Test
    fun `load switches to a fresh instance of the named scene and resets t and step`() {
        val library = library()
        val original = library.scene
        library.advanceOneStep()
        library.advanceOneStep()
        assertEquals(2.0, library.t)

        assertTrue(library.load("b"))
        assertEquals("b", library.activeName)
        assertNotSame(original, library.scene) // a fresh instance, not the old one reused
        assertEquals(0.0, library.t)
        assertEquals(0L, library.step)
    }

    @Test
    fun `load with an unknown name returns false and changes nothing`() {
        val library = library()
        val scene = library.scene
        library.advanceOneStep()

        assertFalse(library.load("nonexistent"))
        assertEquals("a", library.activeName)
        assertSame(scene, library.scene)
        assertEquals(1.0, library.t)
    }

    @Test
    fun `restart discards the current instance for a fresh one of the same name`() {
        val library = library()
        val original = library.scene as StubScene
        library.advanceOneStep()
        assertEquals(1, original.stepsCalled)

        assertTrue(library.restart())
        assertEquals("a", library.activeName)
        assertNotSame(original, library.scene)
        assertEquals(0, (library.scene as StubScene).stepsCalled)
        assertEquals(0.0, library.t)
    }

    @Test
    fun `handle(LoadScene) switches scenes through the dispatch path`() {
        val library = library()
        library.handle(SceneControlMessage.LoadScene("b"))
        assertEquals("b", library.activeName)
    }

    @Test
    fun `handle(Restart) reloads the active scene`() {
        val library = library()
        val original = library.scene
        library.handle(SceneControlMessage.Restart)
        assertEquals("a", library.activeName)
        assertNotSame(original, library.scene)
    }

    @Test
    fun `handle forwards every other message to the active scene's handleControl`() {
        val library = library()
        val message = SceneControlMessage.SetGroupEnabled("g", false)
        library.handle(message)
        assertEquals(message, (library.scene as StubScene).lastControlMessage)
    }

    @Test
    fun `advanceOneStep steps the scene and advances t and step by the scene's own dt`() {
        val library = library()
        library.advanceOneStep()
        library.advanceOneStep()
        assertEquals(2, (library.scene as StubScene).stepsCalled)
        assertEquals(2.0, library.t)
        assertEquals(2L, library.step)
    }

    @Test
    fun `sceneNames lists every registered scene`() {
        assertEquals(setOf("a", "b"), library().sceneNames.toSet())
    }
}
