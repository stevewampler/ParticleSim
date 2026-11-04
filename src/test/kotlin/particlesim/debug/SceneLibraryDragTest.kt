package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scene that knows nothing about dragging: one particle falling, one pinned in place by the
 * scene's own step - standing in for any real scene's free and pinned particles. */
private class FallingScene : DemoScene {
    override val dt = 0.01
    override val store = ParticleStore()
    val freeId = store.create(position = Vector3(0.0, 0.0, 0.0))
    val pinnedId = store.create(position = Vector3(5.0, 0.0, 0.0))
    private val pinnedAt = store.position(pinnedId)

    override fun ids(): List<Int> = listOf(freeId, pinnedId)
    override fun step(t: Double) {
        store.setVelocity(freeId, store.velocity(freeId) + Vector3(0.0, -9.8 * dt, 0.0))
        store.setPosition(freeId, store.position(freeId) + store.velocity(freeId) * dt)
        store.setPosition(pinnedId, pinnedAt)
        store.setVelocity(pinnedId, Vector3.ZERO)
    }
    override fun frame(t: Double): SceneFrame = SceneFrame()
}

class SceneLibraryDragTest {
    private val queue = DragMessageQueue()
    private val library = SceneLibrary(mapOf("falling" to { FallingScene() }, "other" to { FallingScene() }), "falling", queue)
    private val scene get() = library.scene as FallingScene

    @Test
    fun `any scene's particle follows the drag target, then is thrown with the drag's velocity`() {
        val id = scene.freeId
        queue.offer(DragMessage.Start(id, 0L, Vector3(1.0, 2.0, 0.0)))
        library.advanceOneStep()
        assertEquals(Vector3(1.0, 2.0, 0.0), scene.store.position(id), "gravity must not move a held particle")

        queue.offer(DragMessage.Move(1L, Vector3(1.0, 2.1, 0.0)))
        library.advanceOneStep()
        assertEquals(Vector3(1.0, 2.1, 0.0), scene.store.position(id))

        queue.offer(DragMessage.End(2L))
        library.advanceOneStep()
        assertNull(library.draggedParticleId)
        // Released moving upward at ~0.1 / dt = 10 m/s, then one step of gravity.
        assertTrue(scene.store.velocity(id).y > 9.0, "release velocity ${scene.store.velocity(id)} lost the throw")
    }

    @Test
    fun `a particle the scene pins can be dragged and snaps back to its pin on release`() {
        val id = scene.pinnedId
        val pin = scene.store.position(id)
        queue.offer(DragMessage.Start(id, 0L, Vector3(5.0, 3.0, 0.0)))
        library.advanceOneStep()
        assertEquals(Vector3(5.0, 3.0, 0.0), scene.store.position(id))

        queue.offer(DragMessage.End(1L))
        library.advanceOneStep()
        assertEquals(pin, scene.store.position(id))
    }

    @Test
    fun `switching scenes drops a drag in progress`() {
        queue.offer(DragMessage.Start(scene.freeId, 0L, Vector3(1.0, 2.0, 0.0)))
        library.advanceOneStep()
        assertEquals(scene.freeId, library.draggedParticleId)

        library.load("other")
        assertNull(library.draggedParticleId)
        library.advanceOneStep()
        assertTrue(scene.store.position(scene.freeId).y < 0.0, "the new scene's particle should just fall")
    }

    @Test
    fun `a drag on a particle that no longer exists is ignored`() {
        queue.offer(DragMessage.Start(9999, 0L, Vector3(1.0, 2.0, 0.0)))
        library.advanceOneStep()
        assertNull(library.draggedParticleId)
    }
}
