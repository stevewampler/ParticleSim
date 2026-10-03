package particlesim.debug

import particlesim.collision.Collider
import particlesim.core.ParticleStore
import particlesim.render.ArrowSampling
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.Light
import particlesim.render.NamedArrowSamples
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer
import particlesim.render.defaultSurfaceRenderers

/**
 * The debug-render-all viewer entry point (§10.2's `--render-all` and, now, the real opt-in
 * renderer system too — one wire format serves both): starts the viewer's HTTP page and
 * WebSocket stream, and broadcasts a [BinaryFrame] each time the caller's physics loop calls
 * [broadcast]. Every particle draws as a dot and every connection as a plain-blue line by
 * default; a caller opts into §10.2's declared renderers (sphere sizing, shaded/wireframe
 * meshes, arrow-sampled fields, `colorBy`-driven line colors, or hiding a particle's own dot
 * entirely) via this method's trailing parameters, all defaulted to "off" so every demo built
 * before this needs zero changes.
 *
 * [colliders] is filtered to `active` ones only before it reaches [BinaryFrame.encode]'s
 * wireframe section (§10.4) — a demo's own collider list can simply include everything, active
 * or not, without remembering to filter it itself.
 */
class DebugRenderer(
    private val webSocketPort: Int = 8887,
    private val httpPort: Int = 8888,
    onTextMessage: ((String) -> Unit)? = null,
) {
    private val wsServer = DebugServer(webSocketPort, onTextMessage)
    private val httpServer = ViewerHttpServer(httpPort)

    fun start() {
        wsServer.start()
        httpServer.start()
        println("viewer: http://localhost:$httpPort  (state stream: ws://localhost:$webSocketPort)")
    }

    fun broadcast(
        t: Double,
        step: Long,
        store: ParticleStore,
        ids: List<Int>,
        connections: List<Pair<Int, Int>>,
        camera: CameraPose? = null,
        lineColors: Map<Pair<Int, Int>, Color> = emptyMap(),
        connectionNames: Map<Pair<Int, Int>, String> = emptyMap(),
        sphereRadii: Map<Int, Double> = emptyMap(),
        particleColors: Map<Int, Color> = emptyMap(),
        meshes: List<SurfaceRenderer> = emptyList(),
        arrowGroups: List<NamedArrowSamples> = emptyList(),
        visibleIds: Set<Int>? = null,
        registry: SceneRegistry = SceneRegistry.build(),
        colliders: List<Collider> = emptyList(),
        events: List<SimEvent> = emptyList(),
        availableScenes: List<String> = emptyList(),
        activeScene: String = "",
        lights: List<Light> = emptyList(),
    ) {
        // Fills in a default-region arrow group (§10.2) for any named UniformFieldForce the
        // registry carries that arrowGroups didn't already cover - this is the single call site
        // every scene's broadcast passes through (see DemoScene's generic runner), so it's what
        // makes the "show arrows" toggle force-type-specific rather than scene-specific: a scene
        // gets it for free just by naming its field force, with no ArrowRenderer of its own to
        // build. An explicit arrowGroups entry (e.g. FlagScene's hand-tuned windArrows) still wins.
        val allArrowGroups = arrowGroups + ArrowSampling.defaultGroupsFor(
            registry.forces, arrowGroups.map { it.name }.toSet(), store, ids, t,
        )
        // Same "force-type specific, not scene specific" principle applied to surfaces: a named
        // surface the registry carries but meshes didn't cover gets a plain default renderer, so
        // the outliner's "show mesh" toggle is never a no-op just because a scene named a surface
        // without wiring a mesh renderer for it.
        val allMeshes = meshes + defaultSurfaceRenderers(registry.surfaces, meshes.mapNotNull { it.surface.name }.toSet())
        wsServer.broadcastFrame(
            BinaryFrame.encode(
                t, step, store, ids, connections, camera, lineColors, connectionNames, sphereRadii, particleColors, allMeshes,
                allArrowGroups, visibleIds, registry, colliders.filter { it.active }, events, availableScenes, activeScene, lights,
            ),
        )
    }

    fun stop() {
        wsServer.stop()
        httpServer.stop()
    }
}
