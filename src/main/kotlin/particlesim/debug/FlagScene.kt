package particlesim.debug

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

    private val integrator = Integrator()
    private var activeDrag: DragConstraint? = null
    private val allIds = scenario.grid.flatten()

    override val dt = FLAG_DT
    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = allIds

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, scenario.forces, scenario.constraints)) return
        when (message) {
            is SceneControlMessage.SetGroupEnabled -> scenario.groups.setEnabled(message.name, message.enabled)
            is SceneControlMessage.SetParticleScalarField -> {
                if (scenario.store.contains(message.particleId)) {
                    when (message.field) {
                        "mass" -> scenario.store.setMass(message.particleId, message.expr, t)
                        "radius" -> scenario.store.setRadius(message.particleId, message.expr, t)
                    }
                }
            }
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
