package particlesim.debug

import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared viewer-driven time control (§9.1/§10.3: pause, speed multiplier, step-once), applied
 * uniformly across a demo's own physics loop rather than each one reimplementing this logic
 * independently — the same "one reusable mechanism" stance already taken for drag ([DragConstraint])
 * and mesh-derived structural springs.
 *
 * **§9.1's pacing policy is the one hard constraint this can't violate**: a speed multiplier
 * changes how many fixed-`dt` steps run per wall-clock frame, never the size of any one step —
 * coarsening `dt` itself "would silently break determinism" (§11), the exact thing this section
 * calls out. [stepsThisFrame] only ever returns whole-step counts for that reason, using
 * [stepDebt] to carry a fractional remainder between calls so a non-integer multiplier (0.33x,
 * say) still averages out correctly over many frames instead of always rounding the same way
 * every single one.
 *
 * **Thread model** mirrors [DragMessageQueue]: [apply] runs on [DebugServer]'s WebSocket I/O
 * thread as messages arrive, [stepsThisFrame] runs on the physics loop's own thread once per
 * broadcast tick. `paused`/`speedMultiplier` are `@Volatile` for that cross-thread visibility;
 * [stepDebt] is never touched by [apply], only by [stepsThisFrame], so it needs no
 * synchronization of its own.
 *
 * A caller's `onTextMessage` callback needs to try [DragMessage.parse], this class's own
 * [TimeControlMessage.parse], and [SceneControlMessage.parse] on every incoming message (each
 * returns `null` for a `type` it doesn't recognize) rather than assuming just one — the same
 * bidirectional channel carries all three kinds of viewer input. [ViewerInput] does exactly
 * this dispatch already; use it instead of hand-rolling the same three-way parse in a new demo.
 */
class TimeControl {
    @Volatile private var paused = false
    @Volatile private var speedMultiplier = 1.0
    private val stepOnceRequests = AtomicInteger(0)
    private var stepDebt = 0.0

    fun apply(message: TimeControlMessage) {
        when (message) {
            TimeControlMessage.Pause -> paused = true
            TimeControlMessage.Resume -> paused = false
            is TimeControlMessage.SetSpeed -> speedMultiplier = message.multiplier
            TimeControlMessage.StepOnce -> stepOnceRequests.incrementAndGet()
        }
    }

    /**
     * How many physics steps to actually run this broadcast-frame tick, given
     * [nominalStepsPerFrame] — what a normal, unpaused, 1x-speed frame would run.
     *
     * A queued step-once request always wins over the pause/speed state, runs exactly
     * [nominalStepsPerFrame] steps ("one frame's worth," not one raw `dt`-step — a single 1e-3s
     * step is visually imperceptible, and step-once exists specifically to be a visible
     * debugging aid), and **leaves [paused] `true` afterward regardless of what it was before** —
     * so "step" always means "advance by exactly one visible increment, then hold there," a
     * single predictable outcome whether it was already paused or running when clicked. Multiple
     * step-once requests queue rather than coalesce (each click is a distinct step forward).
     *
     * Otherwise, while paused, returns `0` — the physics loop shouldn't call [particlesim.physics.Integrator.step]
     * at all, which also means camera/wind-sampling functions keyed on `t` freeze along with it
     * for free, since the caller never advances `t` on a zero-step frame.
     */
    fun stepsThisFrame(nominalStepsPerFrame: Int): Int {
        if (stepOnceRequests.get() > 0) {
            stepOnceRequests.decrementAndGet()
            paused = true
            return nominalStepsPerFrame
        }
        if (paused) return 0

        stepDebt += nominalStepsPerFrame * speedMultiplier
        val steps = stepDebt.toInt()
        stepDebt -= steps
        return steps
    }
}
