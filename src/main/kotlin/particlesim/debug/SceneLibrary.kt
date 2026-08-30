package particlesim.debug

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
 */
class SceneLibrary(
    private val factories: Map<String, () -> DemoScene>,
    defaultSceneName: String,
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
     * [SceneControlMessage.parse] already takes for anything it can't make sense of. */
    fun load(name: String): Boolean {
        val factory = factories[name] ?: return false
        activeName = name
        scene = factory()
        t = 0.0
        step = 0L
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
    fun advanceOneStep() {
        scene.step(t)
        t += scene.dt
        step++
    }
}
