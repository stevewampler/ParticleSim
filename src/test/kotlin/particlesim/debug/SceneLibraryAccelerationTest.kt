package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A particle whose velocity grows by 1 m/s along x every step, plus one pinned in place: each
 * step's acceleration is known exactly. dt is 0.5, so the moving one accelerates at 2 m/s². */
private class AcceleratingScene : DemoScene {
    override val dt = 0.5
    override val store = ParticleStore()
    val moving = store.create(position = Vector3.ZERO)
    val pinned = store.create(position = Vector3.ZERO)
    var steps = 0

    override fun ids(): List<Int> = listOf(moving, pinned)
    override fun step(t: Double) {
        steps++
        store.setVelocity(moving, store.velocity(moving) + Vector3(1.0, 0.0, 0.0))
    }
    override fun frame(t: Double): SceneFrame = SceneFrame()
}

/** [SceneLibrary]'s acceleration measurement for the viewer's acceleration arrows (§10.3). */
class SceneLibraryAccelerationTest {
    private fun library() = SceneLibrary(mapOf("a" to { AcceleratingScene() }, "b" to { AcceleratingScene() }), "a")
    private val SceneLibrary.accelerating get() = scene as AcceleratingScene

    @Test
    fun `nothing is measured or sent until the viewer asks`() {
        val library = library()
        library.advance(3)
        assertNull(library.accelerations)
    }

    @Test
    fun `each particle's acceleration is its change in velocity over the frame's last step`() {
        val library = library()
        library.handle(SceneControlMessage.SetSendAccelerations(true))
        assertEquals(emptyMap(), library.accelerations, "nothing measured before the first step")

        library.advance(4)
        val accelerations = library.accelerations!!
        assertEquals(Vector3(2.0, 0.0, 0.0), accelerations[library.accelerating.moving])
        assertEquals(Vector3.ZERO, accelerations[library.accelerating.pinned])
        assertEquals(4, library.accelerating.steps)
    }

    @Test
    fun `a paused frame keeps the last measurement`() {
        val library = library()
        library.handle(SceneControlMessage.SetSendAccelerations(true))
        library.advance(2)
        library.advance(0)
        assertEquals(Vector3(2.0, 0.0, 0.0), library.accelerations!![library.accelerating.moving])
    }

    @Test
    fun `the request survives a scene switch, but the old scene's measurements don't`() {
        val library = library()
        library.handle(SceneControlMessage.SetSendAccelerations(true))
        library.advance(1)
        library.load("b")
        assertTrue(library.sendAccelerations)
        assertEquals(emptyMap(), library.accelerations)
    }

    @Test
    fun `turning it off stops sending`() {
        val library = library()
        library.handle(SceneControlMessage.SetSendAccelerations(true))
        library.advance(1)
        library.handle(SceneControlMessage.SetSendAccelerations(false))
        assertNull(library.accelerations)
    }

    @Test
    fun `the viewer's request parses`() {
        assertEquals(SceneControlMessage.SetSendAccelerations(true), SceneControlMessage.parse("""{"type":"set_send_accelerations","enabled":true}"""))
        assertNull(SceneControlMessage.parse("""{"type":"set_send_accelerations"}"""))
    }
}
