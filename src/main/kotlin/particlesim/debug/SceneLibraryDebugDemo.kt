package particlesim.debug

/**
 * §9.6's scene library, live: `./gradlew runSceneLibraryDemo`, then open the URL it prints.
 * One always-on WebSocket/HTTP server hosting every scene that already has a reusable
 * `buildX(): XScenario` function - `flag`, `ballBounce`, `trampoline`, `sparks` - switchable at
 * any time via `SceneControlMessage.LoadScene` without dropping the viewer connection (§9.6).
 * The other four demos (`Drag`, `ParticleCollision`, `SpatialGrid`, `MultiShape`) build their
 * scenario ad hoc inline in `main()` today and have real demo-specific interactive logic
 * (spawn timers, collider rules, drag-exclusion) that doesn't yet reduce to [DemoScene] - they
 * stay standalone `run*Demo` gradle tasks until a later pass wraps them too, rather than forcing
 * an invasive rewrite now for the sake of a "complete" library on day one.
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
