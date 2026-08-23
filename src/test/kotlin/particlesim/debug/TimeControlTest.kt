package particlesim.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeControlTest {

    @Test
    fun `at default 1x speed, unpaused, returns exactly the nominal step count every frame`() {
        val control = TimeControl()
        repeat(5) {
            assertEquals(10, control.stepsThisFrame(10))
        }
    }

    @Test
    fun `pausing returns zero steps until resumed`() {
        val control = TimeControl()
        control.apply(TimeControlMessage.Pause)

        repeat(3) { assertEquals(0, control.stepsThisFrame(10)) }

        control.apply(TimeControlMessage.Resume)
        assertEquals(10, control.stepsThisFrame(10))
    }

    @Test
    fun `a whole-number speed multiplier scales steps exactly, every frame`() {
        val control = TimeControl()
        control.apply(TimeControlMessage.SetSpeed(2.0))
        repeat(5) { assertEquals(20, control.stepsThisFrame(10)) }

        control.apply(TimeControlMessage.SetSpeed(0.5))
        repeat(5) { assertEquals(5, control.stepsThisFrame(10)) }
    }

    @Test
    fun `a fractional speed multiplier averages out correctly across many frames, dt never changes`() {
        // dt itself is never touched by this class at all - stepsThisFrame only ever returns
        // whole numbers of full-dt steps (see its own doc comment on why: coarsening dt would
        // silently break determinism, section 9.1/11). What's under test here is that a
        // multiplier that doesn't divide evenly still averages out over many frames instead of
        // permanently over- or under-running by rounding the same direction every time.
        val control = TimeControl()
        control.apply(TimeControlMessage.SetSpeed(0.33))

        val frameCount = 1000
        var total = 0
        repeat(frameCount) { total += control.stepsThisFrame(10) }

        val expected = 10 * 0.33 * frameCount
        assertTrue(kotlin.math.abs(total - expected) < 10.0, "total=$total expected~=$expected")
    }

    @Test
    fun `step-once always leaves the control paused afterward, whether it was running or already paused`() {
        val runningThenStepped = TimeControl()
        runningThenStepped.apply(TimeControlMessage.StepOnce)
        assertEquals(10, runningThenStepped.stepsThisFrame(10))
        assertEquals(0, runningThenStepped.stepsThisFrame(10), "should be paused now, not still running")

        val pausedThenStepped = TimeControl()
        pausedThenStepped.apply(TimeControlMessage.Pause)
        pausedThenStepped.apply(TimeControlMessage.StepOnce)
        assertEquals(10, pausedThenStepped.stepsThisFrame(10))
        assertEquals(0, pausedThenStepped.stepsThisFrame(10))
    }

    @Test
    fun `multiple step-once requests queue rather than coalesce`() {
        val control = TimeControl()
        control.apply(TimeControlMessage.StepOnce)
        control.apply(TimeControlMessage.StepOnce)

        assertEquals(10, control.stepsThisFrame(10), "first queued step")
        assertEquals(10, control.stepsThisFrame(10), "second queued step")
        assertEquals(0, control.stepsThisFrame(10), "no more queued - paused now")
    }

    @Test
    fun `step-once wins over an active pause and over an active speed multiplier`() {
        val control = TimeControl()
        control.apply(TimeControlMessage.SetSpeed(3.0))
        control.apply(TimeControlMessage.Pause)
        control.apply(TimeControlMessage.StepOnce)

        assertEquals(10, control.stepsThisFrame(10), "exactly one nominal frame, not 30 (speed) or 0 (paused)")
    }
}
