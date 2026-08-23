package particlesim.debug

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe hand-off for [SceneControlMessage]s, mirroring [DragMessageQueue] exactly and for
 * the same reason: [DebugServer]'s WebSocket I/O thread calls [offer] as messages arrive, but a
 * restart or collider removal replaces/mutates shared state (`ParticleStore`, `Groups`, the live
 * collider list) that the physics loop's own thread is concurrently reading mid-step — applying
 * either directly from the I/O thread would be a real race, not just a style preference. Queuing
 * and draining once per step (like drag input) gives the physics loop a well-defined point to
 * apply them from, with no shared-state access from more than one thread.
 */
class SceneControlMessageQueue {
    private val queue = ConcurrentLinkedQueue<SceneControlMessage>()

    fun offer(message: SceneControlMessage) {
        queue.offer(message)
    }

    /** Drains and returns every message queued so far, oldest first. */
    fun drainAll(): List<SceneControlMessage> {
        val result = ArrayList<SceneControlMessage>()
        while (true) {
            result += queue.poll() ?: break
        }
        return result
    }
}
