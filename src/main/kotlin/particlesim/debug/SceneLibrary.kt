package particlesim.debug

import particlesim.physics.DragConstraint

/**
 * §9.6's scene library and switching mechanism: holds a named catalog of [DemoScene] factories
 * and exactly one live [scene] at a time, built from whichever factory was most recently
 * [load]ed. [load]/[restart] both discard the current scene and construct a fresh instance
 * from its factory rather than mutating it in place - no scene implements its own reset logic
 * (see [DemoScene]'s own doc comment) - and reset [t]/[step] to zero, since a switched-to scene
 * always starts from its own initial state, never mid-flight from the outgoing scene's clock.
 *
 * [handle] is the one dispatch path every scene's control-message input goes through: it
 * intercepts [SceneControlMessage.LoadScene]/[SceneControlMessage.Restart] itself (only this
 * class, not any individual scene, knows about the library or the currently active name) and
 * forwards everything else to the active scene's own [DemoScene.handleControl] - the "one
 * dispatch/reset path, not one per scene" requirement from §9.6.
 *
 * **Interactive drag (§9.4) lives here too, for every scene.** [dragQueue]'s messages are
 * drained once per physics step in [advanceOneStep], and the active [DragConstraint] is applied
 * right after the scene's own [DemoScene.step] - exactly where an [particlesim.physics.Integrator]
 * applies its constraints (at the end of the step), so this matches passing the constraint into
 * the scene's own integrator call without every scene having to know about dragging. Any live
 * particle can be dragged, including ones a scene pins: the drag wins while held, and the
 * scene's own pin puts the particle back the step after release. One small difference from a
 * scene-internal drag: a scene's collision passes that read [particlesim.physics.Constraint.pinnedIds]
 * (particle-particle/self collision) see the dragged particle as free rather than pinned, which
 * only changes how a collision with it splits the impulse - its position is overwritten by the
 * drag right afterward either way.
 */
class SceneLibrary(
    private val factories: Map<String, () -> DemoScene>,
    defaultSceneName: String,
    private val dragQueue: DragMessageQueue = DragMessageQueue(),
) {
    init {
        require(defaultSceneName in factories) { "no such scene: $defaultSceneName" }
    }

    var activeName: String = defaultSceneName
        private set
    var scene: DemoScene = factories.getValue(defaultSceneName)()
        private set
    var t: Double = 0.0
        private set
    var step: Long = 0L
        private set

    val sceneNames: List<String> get() = factories.keys.toList()

    /** Returns `false` (and changes nothing) for an unrecognized name - a client asking for a
     * scene that doesn't exist is a malformed request, the same "ignore it" stance
     * [SceneControlMessage.parse] already takes for anything it can't make sense of.
     *
     * `factory()` runs *before* [activeName]/[scene] are touched - if it throws, this class is
     * left exactly as it was (still on the previous scene, consistently), not half-switched with
     * [activeName] naming a scene [scene] was never actually rebuilt to match. That inconsistency
     * was real and reachable: a caller broadcasting a frame after a failed [load] would read
     * [activeName] and [scene] from two different scenes at once, with nothing to say so. */
    fun load(name: String): Boolean {
        val factory = factories[name] ?: return false
        val newScene = factory()
        activeName = name
        scene = newScene
        t = 0.0
        step = 0L
        // A drag in progress belonged to the outgoing scene's particles; any still-queued drag
        // messages did too.
        activeDrag = null
        dragQueue.drainAll()
        return true
    }

    fun restart(): Boolean = load(activeName)

    fun handle(message: SceneControlMessage) {
        when (message) {
            is SceneControlMessage.LoadScene -> load(message.name)
            SceneControlMessage.Restart -> restart()
            else -> scene.handleControl(message, t)
        }
    }

    /** Advances the active scene by exactly its own [DemoScene.dt] and increments [t]/[step] to
     * match - the runner calls this in a loop, never touching a scene's clock directly. */
    private var activeDrag: DragConstraint? = null

    /** The particle currently being dragged, if any. */
    val draggedParticleId: Int? get() = activeDrag?.particleId

    fun advanceOneStep() {
        handleDragMessages()
        scene.step(t)
        // Re-checked after the step, not just at drag_start: emitter/expiry scenes (sparks,
        // fire) and §10.4's delete-particle can remove the dragged particle mid-drag.
        activeDrag?.let { drag -> if (scene.store.contains(drag.particleId)) drag.applyTo(scene.store) else activeDrag = null }
        t += scene.dt
        step++
    }

    private fun handleDragMessages() {
        for (message in dragQueue.drainAll()) {
            when (message) {
                is DragMessage.Start ->
                    activeDrag = if (scene.store.contains(message.particleId)) DragConstraint(message.particleId, message.target) else null
                is DragMessage.Move -> activeDrag?.updateTarget(message.target, scene.dt)
                is DragMessage.End -> {
                    activeDrag?.let { if (scene.store.contains(it.particleId)) scene.store.setVelocity(it.particleId, it.releaseVelocity()) }
                    activeDrag = null
                }
            }
        }
    }
}
