package particlesim.debug

import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DragMessageQueueTest {

    @Test
    fun `drains messages in the order they were offered`() {
        val queue = DragMessageQueue()
        queue.offer(DragMessage.Start(1, 0L, Vector3.ZERO))
        queue.offer(DragMessage.Move(1L, Vector3(1.0, 0.0, 0.0)))
        queue.offer(DragMessage.End(2L))

        assertEquals(
            listOf(
                DragMessage.Start(1, 0L, Vector3.ZERO),
                DragMessage.Move(1L, Vector3(1.0, 0.0, 0.0)),
                DragMessage.End(2L),
            ),
            queue.drainAll(),
        )
    }

    @Test
    fun `draining empties the queue`() {
        val queue = DragMessageQueue()
        queue.offer(DragMessage.End(0L))
        queue.drainAll()
        assertTrue(queue.drainAll().isEmpty())
    }

    @Test
    fun `draining an empty queue returns an empty list`() {
        assertTrue(DragMessageQueue().drainAll().isEmpty())
    }
}
