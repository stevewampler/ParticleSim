package particlesim.debug

import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DragMessageTest {

    @Test
    fun `parses a drag_start message`() {
        val msg = DragMessage.parse("""{"type":"drag_start","particleId":7,"step":123,"x":1.0,"y":2.5,"z":-3.0}""")
        assertEquals(DragMessage.Start(7, 123L, Vector3(1.0, 2.5, -3.0)), msg)
    }

    @Test
    fun `parses a drag_move message`() {
        val msg = DragMessage.parse("""{"type":"drag_move","step":124,"x":1.1,"y":2.5,"z":-3.0}""")
        assertEquals(DragMessage.Move(124L, Vector3(1.1, 2.5, -3.0)), msg)
    }

    @Test
    fun `parses a drag_end message`() {
        val msg = DragMessage.parse("""{"type":"drag_end","step":130}""")
        assertEquals(DragMessage.End(130L), msg)
    }

    @Test
    fun `an unrecognized type is ignored, not thrown`() {
        assertNull(DragMessage.parse("""{"type":"camera_orbit","step":1}"""))
    }

    @Test
    fun `malformed JSON is ignored, not thrown`() {
        assertNull(DragMessage.parse("not json at all {{{"))
    }

    @Test
    fun `missing required fields are ignored, not thrown`() {
        assertNull(DragMessage.parse("""{"type":"drag_start","step":1}""")) // no particleId/x/y/z
        assertNull(DragMessage.parse("""{"type":"drag_move","x":1.0,"y":2.0,"z":3.0}""")) // no step
    }
}
