package particlesim.debug

import particlesim.core.Vector3
import particlesim.examples.TRAMPOLINE_DT
import particlesim.examples.buildTrampoline
import particlesim.physics.Integrator
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer

/**
 * A visible run of §12.8's trampoline worked example: `./gradlew runTrampolineDemo`, then open
 * the URL it prints. Mirrors [BallBounceDebugDemo]'s "collision resolves as a separate call
 * after each integrator step" structure, but against
 * [particlesim.collision.SurfaceCollisionSystem] instead of
 * [particlesim.collision.CollisionSystem] — the mat is a real deforming [particlesim.surface.Surface],
 * not a static collider, so the ball's bounce comes from the mesh's own spring response.
 *
 * Same re-triggering drop cycle as [BallBounceDebugDemo], for the same reason: the ball
 * eventually settles resting on the mat, and a fixed reset cycle (well past settle time) means
 * there's always another bounce coming soon rather than requiring the viewer to be open at the
 * exact moment `main` starts.
 *
 * Playback controls (pause/speed/step-once) via [ViewerInput] — every debug demo gets these
 * the same way now, see that class's own doc comment for why.
 */
fun main() {
    val scenario = buildTrampoline()
    val dropPosition = scenario.store.position(scenario.ballId)
    val cycleSeconds = 10.0

    val matMesh = SurfaceRenderer(scenario.surface, wireframe = false)
    val registry = SceneRegistry.build(
        forces = scenario.forces,
        constraints = scenario.constraints,
        surfaces = listOf(scenario.surface),
        groups = scenario.groups,
    )

    val viewerInput = ViewerInput()
    val renderer = DebugRenderer(onTextMessage = viewerInput::onTextMessage)
    renderer.start()

    val integrator = Integrator()
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / TRAMPOLINE_DT).toInt())

    var t = 0.0
    var step = 0L
    var cycleStart = 0.0
    val frameNanos = 1_000_000_000L / framesPerSecond
    val allIds = scenario.grid.flatten() + scenario.ballId
    // Interior mat particles carry mesh-vertex data only (no dot of their own, same
    // `visibleIds` trick FlagDebugDemo uses) - only the pinned rim gets a small sphere so the
    // frame is visible, plus the ball itself. The rim override is load-bearing (those particles
    // carry no ParticleStore radius of their own, so without it they'd fall back to the
    // viewer's plain default dot size, §10.4); the ball's happens to match its own physics
    // radius already and is harmless either way, since this demo has no live radius editing.
    val rimIds = scenario.groups.membersOf("rim")
    val sphereRadii = rimIds.associateWith { 0.03 } + (scenario.ballId to 0.12)
    val visibleIds = rimIds + scenario.ballId
    while (true) {
        val frameStart = System.nanoTime()
        repeat(viewerInput.timeControl.stepsThisFrame(stepsPerFrame)) {
            if (t - cycleStart >= cycleSeconds) {
                scenario.store.setPosition(scenario.ballId, dropPosition)
                scenario.store.setVelocity(scenario.ballId, Vector3.ZERO)
                cycleStart = t
            }
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, TRAMPOLINE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, TRAMPOLINE_DT)
            t += TRAMPOLINE_DT
            step++
        }
        renderer.broadcast(
            t, step, scenario.store, allIds,
            connections = emptyList(),
            sphereRadii = sphereRadii,
            meshes = listOf(matMesh),
            visibleIds = visibleIds,
            registry = registry,
        )
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
