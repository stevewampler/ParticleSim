package particlesim.debug

/**
 * §9.6's scene library, live: `./gradlew runSceneLibraryDemo`, then open the URL it prints.
 * One always-on WebSocket/HTTP server hosting every debug demo this project has, switchable at
 * any time via `SceneControlMessage.LoadScene` without dropping the viewer connection (§9.6).
 * `flag`/`ballBounce`/`trampoline`/`sparks` (backed by a reusable `buildX(): XScenario`
 * function) landed first; `drag`/`particleCollision`/`spatialGrid`/`multiShape` (which build
 * their scenario ad hoc inline in the original standalone `main()`, with real demo-specific
 * interactive logic - spawn timers, collider rules, drag-exclusion) followed once each one's
 * logic had been ported onto [DemoScene] rather than left standalone indefinitely. The original
 * standalone `run*Demo` gradle tasks and their `*DebugDemo.kt` `main()`s (one per scenario) were
 * removed once this picker was confirmed to reach every one of them losslessly - keeping both
 * meant every new interactive/control-message feature (e.g. §10.4's live-editing messages) had
 * to be wired twice, once here and once in each standalone demo's own hand-rolled dispatch.
 *
 * The viewer's `#controlPanel` shows a scene picker (§10.3) whenever `availableScenes` is
 * non-empty - `DebugRendererDemo` (the one demo this doesn't replace - see its own doc comment)
 * leaves it `emptyList()`/`""` (this method's own defaults), so the picker stays hidden there.
 *
 * `args[0]`, if given, is which scene to start on instead of the `flag` default - e.g.
 * `./gradlew runSceneLibraryDemo --args="trampoline"`. An unrecognized name prints the valid
 * list and exits rather than falling back silently, the same "fail fast on a malformed request"
 * stance [SceneLibrary.load] takes at runtime for a bad `load_scene` message, except a CLI typo
 * gets a chance to be corrected before anything starts rather than an ignored no-op.
 */
fun main(args: Array<String>) {
    val viewerInput = ViewerInput()
    val factories = linkedMapOf<String, () -> DemoScene>(
        "flag" to { FlagScene(viewerInput.dragQueue) },
        "ballBounce" to { BallBounceScene() },
        "trampoline" to { TrampolineScene() },
        "sparks" to { SparksScene() },
        "drag" to { DragScene(viewerInput.dragQueue) },
        "particleCollision" to { ParticleCollisionScene() },
        "spatialGrid" to { SpatialGridScene() },
        "multiShape" to { MultiShapeScene() },
    )
    val requestedScene = args.getOrNull(0)
    if (requestedScene != null && requestedScene !in factories) {
        System.err.println(
            "no such scene: '$requestedScene' - available scenes: ${factories.keys.joinToString(", ")}",
        )
        return
    }
    val library = SceneLibrary(factories = factories, defaultSceneName = requestedScene ?: "flag")

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
