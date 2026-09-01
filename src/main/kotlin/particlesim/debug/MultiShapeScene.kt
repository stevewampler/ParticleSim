package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.ShapePlacement
import particlesim.examples.buildBallBounce
import particlesim.examples.buildFlag
import particlesim.examples.buildFlagpole
import particlesim.examples.buildTire
import particlesim.physics.Integrator
import particlesim.render.SceneRegistry

/**
 * §9.6 scene-library wrapping of [MultiShapeDebugDemo]'s worked example - see that file's own
 * doc comment for the shape-composition/placement reasoning, unchanged here. Unlike the
 * standalone demo (which never drained `sceneControlQueue` or built a [SceneRegistry] at all -
 * a pre-existing gap noted early in this session's own §10.3 work), this wrapping wires both in:
 * the four shapes' own named forces/constraints/surface/colliders become outliner-reachable and
 * editable the same way every other library scene's are, since there's no reason a scene reached
 * through the picker should be a second-class citizen relative to its neighbors.
 */
class MultiShapeScene : DemoScene {
    private val poleHeight = 3.5

    override val store = ParticleStore()
    private val groups = Groups()
    private val flagpole = buildFlagpole(
        height = poleHeight, store = store, groups = groups, placement = ShapePlacement(instanceName = "pole"),
    )
    private val flag = buildFlag(
        rows = 8, cols = 14, store = store, groups = groups,
        placement = ShapePlacement(offset = Vector3(0.0, poleHeight, 0.0), instanceName = "flag"),
    )
    private val tire = buildTire(
        radius = 1.0, segments = 16, dropHeight = 3.0, store = store, groups = groups,
        placement = ShapePlacement(offset = Vector3(3.0, 0.0, 0.0), instanceName = "tire"),
    )
    private val ball = buildBallBounce(
        dropHeight = 4.0, store = store, groups = groups,
        placement = ShapePlacement(offset = Vector3(-2.5, 0.0, 1.5), instanceName = "ball"),
    )

    private val allIds = flagpole.poleIds + flag.grid.flatten() + tire.rimIds + listOf(ball.ballId)
    private val allForces = flag.forces + tire.forces + ball.forces
    private val allConstraints = flagpole.constraints + flag.constraints
    private val allColliders = listOf(tire.floor, ball.floor)
    private val registry = SceneRegistry.build(
        forces = allForces, constraints = allConstraints, surfaces = listOf(flag.surface),
        groups = groups, colliders = allColliders,
    )
    private val integrator = Integrator()

    override val dt = 1e-3 // matches buildFlag's FLAG_DT, buildTire's TIRE_DT, and buildBallBounce's BALL_BOUNCE_DT

    override fun ids(): List<Int> = allIds

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, allForces, allConstraints, store, t)) return
        if (message is SceneControlMessage.SetGroupEnabled) groups.setEnabled(message.name, message.enabled)
    }

    override fun step(t: Double) {
        integrator.step(store, groups, allForces, allConstraints, t, dt)
        tire.collisions.resolve(store, groups, t, dt)
        ball.collisions.resolve(store, groups, t, dt)
    }

    override fun frame(t: Double): SceneFrame = SceneFrame(
        connections = flagpole.poleIds.zipWithNext() +
            flag.meshSprings[0].activeConnections() +
            tire.rimIds.indices.map { i -> tire.rimIds[i] to tire.rimIds[(i + 1) % tire.rimIds.size] },
        registry = registry,
        colliders = allColliders,
    )
}
