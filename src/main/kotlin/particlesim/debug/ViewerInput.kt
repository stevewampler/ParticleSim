package particlesim.debug

/**
 * Shared viewer-input wiring for every debug demo (§10.3): parses all three kinds of message
 * that arrive on the bidirectional WebSocket channel — [TimeControlMessage] (pause/speed/
 * step-once), [DragMessage] (per-particle drag), and [SceneControlMessage] (collider removal,
 * particle delete, restart) — and routes each to its own queue/handler.
 *
 * Exists so a new demo gets pause/speed/step-once *unconditionally* rather than as something
 * its author has to remember to opt into by hand-rolling the same `onTextMessage` dispatch and
 * threading `timeControl.stepsThisFrame(...)` through their own loop. That was the actual
 * failure mode this class fixes: several demos (`BallBounceDebugDemo`, `SparksDebugDemo`,
 * `TrampolineDebugDemo`, `MultiShapeDebugDemo`, and the original `DebugRendererDemo`) were built
 * before time controls existed and simply never got updated, so their playback buttons silently
 * did nothing — not a bug in [TimeControl] itself, just a wiring step every demo needed and one
 * of them was always going to skip eventually. Bundling [dragQueue]/[sceneControlQueue] here
 * too, even for a demo that never drains them, is the cheaper failure mode than the reverse: an
 * unused queue costs nothing, while a demo silently missing a control it should have had is
 * exactly what happened here.
 *
 * Usage: `val viewerInput = ViewerInput(); val renderer = DebugRenderer(onTextMessage =
 * viewerInput::onTextMessage)`, then use `viewerInput.timeControl.stepsThisFrame(stepsPerFrame)`
 * in place of a bare `stepsPerFrame` wherever the physics loop calls `repeat(...)`. A demo that
 * doesn't support dragging or scene control simply never touches [dragQueue]/[sceneControlQueue]
 * — nothing requires draining a queue nothing offers to.
 */
class ViewerInput {
    val timeControl = TimeControl()
    val dragQueue = DragMessageQueue()
    val sceneControlQueue = SceneControlMessageQueue()

    fun onTextMessage(text: String) {
        DragMessage.parse(text)?.let(dragQueue::offer)
        TimeControlMessage.parse(text)?.let(timeControl::apply)
        SceneControlMessage.parse(text)?.let(sceneControlQueue::offer)
    }
}
