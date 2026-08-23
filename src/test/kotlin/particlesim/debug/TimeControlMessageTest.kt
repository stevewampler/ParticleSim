package particlesim.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeControlMessageTest {

    @Test
    fun `parses pause, resume, and step_once`() {
        assertEquals(TimeControlMessage.Pause, TimeControlMessage.parse("""{"type": "pause"}"""))
        assertEquals(TimeControlMessage.Resume, TimeControlMessage.parse("""{"type": "resume"}"""))
        assertEquals(TimeControlMessage.StepOnce, TimeControlMessage.parse("""{"type": "step_once"}"""))
    }

    @Test
    fun `parses set_speed with its multiplier`() {
        assertEquals(TimeControlMessage.SetSpeed(2.0), TimeControlMessage.parse("""{"type": "set_speed", "multiplier": 2.0}"""))
        assertEquals(TimeControlMessage.SetSpeed(0.25), TimeControlMessage.parse("""{"type": "set_speed", "multiplier": 0.25}"""))
    }

    @Test
    fun `rejects a zero or negative multiplier - pause exists for stop, there's no reverse`() {
        assertNull(TimeControlMessage.parse("""{"type": "set_speed", "multiplier": 0.0}"""))
        assertNull(TimeControlMessage.parse("""{"type": "set_speed", "multiplier": -1.0}"""))
    }

    @Test
    fun `rejects set_speed with no multiplier at all`() {
        assertNull(TimeControlMessage.parse("""{"type": "set_speed"}"""))
    }

    @Test
    fun `returns null for malformed or unrecognized input, same stance as DragMessage`() {
        assertNull(TimeControlMessage.parse("not json at all {{{"))
        assertNull(TimeControlMessage.parse("""{"type": "drag_start", "particleId": 1, "step": 0, "x": 0, "y": 0, "z": 0}"""))
        assertNull(TimeControlMessage.parse("""{"type": "unknown_future_type"}"""))
    }
}
