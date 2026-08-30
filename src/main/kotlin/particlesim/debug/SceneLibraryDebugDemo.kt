package particlesim.debug

/**
 * §9.6's scene library, live: `./gradlew runSceneLibraryDemo`, then open the URL it prints.
 * One always-on WebSocket/HTTP server hosting every debug demo this project has, switchable at
 * any time via `SceneControlMessage.LoadScene` without dropping the viewer connection (§9.6).
 * `flag`/`ballBounce`/`trampoline`/`sparks` (backed by a reusable `buildX(): XScenario`
 * function) landed first; `drag`/`particleCollision`/`spatialGrid`/`multiShape` (which build
 * their scenario ad hoc inline in the original standalone `main()`, with real demo-specific
 * interactive logic - spawn timers, collider rules, drag-exclusion) followed once each one's
 * logic had been ported onto [DemoScene] rather than left standalone indefinitely. Every
 * original `run*Demo` gradle task still exists and still works unmodified - this is a second,
 * additional way to reach the same scenarios, not a replacement.
 *
 * The viewer's `#controlPanel` shows a scene picker (§10.3) whenever `availableScenes` is
 * non-empty - every other demo leaves it `emptyList()`/`""` (this method's own defaults), so the
 * picker just stays hidden for them, no per-demo opt-out needed.
 */
fun main() {
    val viewerInput = ViewerInput()
    val library = SceneLibrary(
        factories = linkedMapOf(
            "flag" to { FlagScene(viewerInput.dragQueue) },
            "ballBounce" to { BallBounceScene() },
            "trampoline" to { TrampolineScene() },
            "sparks" to { SparksScene() },
            "drag" to { DragScene(viewerInput.dragQueue) },
            "particleCollision" to { ParticleCollisionScene() },
            "spatialGrid" to { SpatialGridScene() },
            "multiShape" to { MultiShapeScene() },
        ),
        defaultSceneName = "flag",
    )

    val renderer = DebugRenderer(onTextMessage = viewerInput::onTextMessage)
    renderer.start()

    val framesPerSecond = 60
    val frameNanos = 1_000_000_000L / framesPerSecond
    while (true) {
        val frameStart = System.nanoTime()
        for (message in viewerInput.sceneControlQueue.drainAll()) library.handle(message)
        // §9.1's pacing policy, per active scene: stepsPerFrame is recomputed every frame from
        // whichever scene is currently loaded, since a scene switch can change dt (FLAG_DT and
        // TRAMPOLINE_DT differ by more than 10x).
        val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / library.scene.dt).toInt())
        repeat(viewerInput.timeControl.stepsThisFrame(stepsPerFrame)) { library.advanceOneStep() }
        val frame = library.scene.frame(library.t)
        renderer.broadcast(
            library.t, library.step, library.scene.store, library.scene.ids(), frame.connections,
            camera = frame.camera,
            lineColors = frame.lineColors,
            connectionNames = frame.connectionNames,
            sphereRadii = frame.sphereRadii,
            meshes = frame.meshes,
            arrowGroups = frame.arrowGroups,
            visibleIds = frame.visibleIds,
            registry = frame.registry,
            colliders = frame.colliders,
            events = frame.events,
            availableScenes = library.sceneNames,
            activeScene = library.activeName,
        )
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
