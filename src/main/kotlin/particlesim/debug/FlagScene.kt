package particlesim.debug

import particlesim.collision.SurfaceSelfCollisionRule
import particlesim.collision.SurfaceSelfCollisionSystem
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.FLAG_DT
import particlesim.examples.buildFlag
import particlesim.physics.Constraint
import particlesim.physics.DragConstraint
import particlesim.physics.Integrator
import particlesim.physics.Wind
import particlesim.render.ArrowRenderer
import particlesim.render.ArrowSampling
import particlesim.render.CameraFunction
import particlesim.render.CameraPose
import particlesim.render.NamedArrowSamples
import particlesim.render.SceneQueryImpl
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer
import kotlin.math.cos
import kotlin.math.sin

/**
 * §9.6 scene-library wrapping of [FlagDebugDemo]'s worked example - see that file's own doc
 * comment for the camera/drag/renderer/live-editing reasoning, all unchanged here. [dragQueue]
 * is handed in (not owned) by the runner, since it's the process-wide shared queue every scene
 * would drain from if it supported dragging - see [DemoScene]'s own doc comment for why a scene
 * drains it itself, inside [step], rather than the runner draining it once per frame.
 */
class FlagScene(private val dragQueue: DragMessageQueue) : DemoScene {
    private val scenario = buildFlag(rows = 8, cols = 14)
    private val structural = scenario.meshSprings[0]
    private val wind = scenario.forces.filterIsInstance<Wind>().single()
    private val flagTip = scenario.grid.last().last()
    private val scene = SceneQueryImpl(scenario.store, scenario.groups)
    private val camera = CameraFunction { t, s ->
        CameraPose(
            position = s.centroid("cloth") + Vector3(sin(t * 0.3) * 5.0, 2.0, cos(t * 0.3) * 5.0),
            lookAt = s.position(flagTip),
        )
    }

    private val poleIds = scenario.groups.membersOf("pole")
    private val poleSphereRadii = poleIds.associateWith { 0.03 }
    private val clothMesh = SurfaceRenderer(scenario.surface, wireframe = false)
    private val windArrows = ArrowRenderer(wind, regionMin = Vector3(-0.5, -2.0, -1.0), regionMax = Vector3(2.5, 0.5, 1.0), resolution = 1.0)
    private val arrowVisualScale = 0.15
    private val registry = SceneRegistry.build(
        forces = scenario.forces,
        constraints = scenario.constraints,
        surfaces = listOf(scenario.surface),
        groups = scenario.groups,
    )

    // §12.4's "Surface self-collision" (requirements.md, new requirement) - keeps the flag's own
    // cloth from passing through itself as it billows/folds. Built here rather than inside
    // buildFlag/FlagScenario: buildFlag is also consumed by FlagGoldenTest/FlagYamlParityTest
    // (byte-identical-output proofs that never call any resolve()) and FlagOnRopeScene/
    // MultiShapeScene, none of which should carry this by default - the same "own it where it's
    // actually used" reasoning FlagScene already applies to clothMesh/windArrows/camera below,
    // none of which live on FlagScenario either. thickness/excludeRings tuned empirically against
    // this exact scenario (see todo/TODO.md's entry for the scratch-benchmark method): thickness
    // = 0.05 (~1/3 of the flag's own row spacing of 0.15) catches genuine folds without firing on
    // the sheet's ordinary billowing curvature. excludeRings = 2 is a safety margin, not a
    // measured floor at this thickness - rings 0/1/2 all produced identical results at
    // thickness=0.05 (no local-curvature false positives even at ring 0 here). The floor was
    // only observed at a much larger thickness (0.15, comparable to row spacing): there, rings
    // 0-1 fired every single step (pure local-curvature false positives) and rings 2-4 converged
    // to the same, much smaller contact count. So 2 is known to be sufficient once thickness
    // approaches spacing - raise thickness later without re-checking this, and it may need to.
    private val selfCollisions = SurfaceSelfCollisionSystem(
        listOf(SurfaceSelfCollisionRule(surface = scenario.surface, thickness = 0.05, excludeRings = 2)),
    )

    private val integrator = Integrator()
    private var activeDrag: DragConstraint? = null
    private val allIds = scenario.grid.flatten()

    override val dt = FLAG_DT
    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = allIds

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, scenario.forces, scenario.constraints, scenario.store, t)) return
        when (message) {
            is SceneControlMessage.SetGroupEnabled -> scenario.groups.setEnabled(message.name, message.enabled)
            else -> {} // colliders/delete-particle/restart-scene aren't features of this scene
        }
    }

    override fun step(t: Double) {
        for (message in dragQueue.drainAll()) {
            when (message) {
                is DragMessage.Start -> {
                    // The pole edge is already FixedPosition-constrained — dragging it would
                    // just fight that constraint, same reasoning as DragDebugDemo excluding its
                    // own pinned anchor.
                    if ("pole" !in scenario.groups.groupsOf(message.particleId)) {
                        activeDrag = DragConstraint(message.particleId, message.target)
                    }
                }
                is DragMessage.Move -> activeDrag?.updateTarget(message.target, dt)
                is DragMessage.End -> {
                    activeDrag?.let { scenario.store.setVelocity(it.particleId, it.releaseVelocity()) }
                    activeDrag = null
                }
            }
        }
        val constraints: List<Constraint> = activeDrag?.let { scenario.constraints + it } ?: scenario.constraints
        integrator.step(scenario.store, scenario.groups, scenario.forces, constraints, t, dt)
        selfCollisions.resolve(scenario.store, scenario.groups, constraints)
    }

    override fun frame(t: Double): SceneFrame {
        val arrowSamples = ArrowSampling.sample(windArrows, t).map { it.copy(vector = it.vector * arrowVisualScale) }
        val structuralConnections = structural.activeConnections()
        return SceneFrame(
            connections = structuralConnections,
            connectionNames = structural.name?.let { name -> structuralConnections.associateWith { name } } ?: emptyMap(),
            camera = camera.evaluate(t, scene),
            sphereRadii = poleSphereRadii,
            meshes = listOf(clothMesh),
            arrowGroups = listOf(NamedArrowSamples(wind.name ?: "", arrowSamples)),
            visibleIds = poleIds,
            registry = registry,
        )
    }
}
