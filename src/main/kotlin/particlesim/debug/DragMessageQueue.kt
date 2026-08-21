package particlesim.debug

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe hand-off for viewer drag messages (§9.4): [DebugServer]'s WebSocket I/O thread
 * calls [offer] as messages arrive, the physics loop's own thread calls [drainAll] once per
 * step to pick them up. A plain concurrent queue rather than a "latest wins" reference —
 * drag_start/drag_end *ordering* matters (a click-and-immediately-release shouldn't be
 * coalesced away just because a newer message overwrote it), so every message in between has
 * to survive until the step loop actually processes it.
 */
class DragMessageQueue {
    private val queue = ConcurrentLinkedQueue<DragMessage>()

    fun offer(message: DragMessage) {
        queue.offer(message)
    }

    /** Drains and returns every message queued so far, oldest first. */
    fun drainAll(): List<DragMessage> {
        val result = ArrayList<DragMessage>()
        while (true) {
            result += queue.poll() ?: break
        }
        return result
    }
}
