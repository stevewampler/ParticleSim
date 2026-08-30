package particlesim.debug

import particlesim.collision.CollisionSystem
import particlesim.collision.ParticleCollisionRule
import particlesim.collision.ParticleCollisionSystem
import particlesim.collision.ParticleColliderRule
import particlesim.collision.PlaneCollider
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.lifecycle.DestructionSystem
import particlesim.physics.Force
import particlesim.physics.Integrator
import particlesim.render.SceneRegistry
import kotlin.random.Random

private data class SpatialGridColliderRule(val collider: PlaneCollider, val rule: ParticleColliderRule)

/**
 * §9.6 scene-library wrapping of [SpatialGridDebugDemo]'s worked example - see that file's own
 * doc comment for why a floating cloud (not a pile-up) is the fair stress test for §9.3's
 * spatial-partitioning grid. Same restart simplification as [ParticleCollisionScene]: a scene
 * switch/restart is a fresh instance now, so the standalone demo's hand-rolled rebuild-every-var
 * branch is gone - only `liveColliderRules`/`floorCollisions`/`ids` stay mutable, for collider
 * removal and interactive delete within this instance's own lifetime.
 */
class SpatialGridScene : DemoScene {
    private val ballGroup = "balls"
    private val radius = 0.05
    private val ballCount = 2000
    private val boxExtent = 3.0

    private val floor = PlaneCollider(VectorExpr.of(Vector3(0.0, -boxExtent, 0.0)), normal = Vector3(0.0, 1.0, 0.0), name = "floor")
    private val ceiling = PlaneCollider(VectorExpr.of(Vector3(0.0, boxExtent, 0.0)), normal = Vector3(0.0, -1.0, 0.0), name = "ceiling")
    private val walls = listOf(
        PlaneCollider(VectorExpr.of(Vector3(-boxExtent, 0.0, 0.0)), normal = Vector3(1.0, 0.0, 0.0), name = "wall-x-neg"),
        PlaneCollider(VectorExpr.of(Vector3(boxExtent, 0.0, 0.0)), normal = Vector3(-1.0, 0.0, 0.0), name = "wall-x-pos"),
        PlaneCollider(VectorExpr.of(Vector3(0.0, 0.0, -boxExtent)), normal = Vector3(0.0, 0.0, 1.0), name = "wall-z-neg"),
        PlaneCollider(VectorExpr.of(Vector3(0.0, 0.0, boxExtent)), normal = Vector3(0.0, 0.0, -1.0), name = "wall-z-pos"),
    )
    private val allPlanes = listOf(floor, ceiling) + walls
    private val allColliderRules = allPlanes.map {
        SpatialGridColliderRule(it, ParticleColliderRule(group = ballGroup, collider = it, restitution = 0.98))
    }

    private val particleRule = ParticleCollisionRule(groupA = ballGroup, restitution = 0.98)
    private val particleCollisions = ParticleCollisionSystem(listOf(particleRule))
    private val destruction = DestructionSystem()

    override val store = ParticleStore()
    private val groups = Groups()
    private val random = Random(seed = 1)

    init {
        // Random position within the box (margin so nothing spawns embedded in a wall) and a
        // random moderate-speed velocity, so the cloud starts already in motion rather than
        // needing gravity to get anything moving.
        repeat(ballCount) {
            val margin = boxExtent - 4.0 * radius
            val position = Vector3(
                random.nextDouble(-margin, margin),
                random.nextDouble(-margin, margin),
                random.nextDouble(-margin, margin),
            )
            val speed = 1.5
            val velocity = Vector3(
                random.nextDouble(-speed, speed),
                random.nextDouble(-speed, speed),
                random.nextDouble(-speed, speed),
            )
            val id = store.create(position = position, velocity = velocity, radius = ScalarExpr.of(radius), mass = ScalarExpr.of(1.0))
            groups.add(ballGroup, id)
        }
    }

    private var liveColliderRules = allColliderRules
    private var floorCollisions = CollisionSystem(liveColliderRules.map { it.rule })
    private var ids = ArrayList(groups.membersOf(ballGroup).toList())
    private val integrator = Integrator()
    private val events = mutableListOf<SimEvent>()

    override val dt = 4e-3

    override fun ids(): List<Int> = ids

    override fun handleControl(message: SceneControlMessage, t: Double) {
        when (message) {
            is SceneControlMessage.RemoveCollider -> {
                liveColliderRules = liveColliderRules.filter { it.collider.name != message.name }
                floorCollisions = CollisionSystem(liveColliderRules.map { it.rule })
            }
            is SceneControlMessage.SetColliderActive -> {
                liveColliderRules.find { it.collider.name == message.name }?.collider?.active = message.active
            }
            is SceneControlMessage.SetGroupEnabled -> groups.setEnabled(message.name, message.enabled)
            is SceneControlMessage.SetParticleScalarField -> {
                if (store.contains(message.particleId)) {
                    when (message.field) {
                        "mass" -> store.setMass(message.particleId, message.expr, t)
                        "radius" -> store.setRadius(message.particleId, message.expr, t)
                    }
                }
            }
            is SceneControlMessage.DeleteParticle -> {
                val result = destruction.resolve(store, groups, emptyList<Force>(), t, dt, explicitIds = setOf(message.particleId))
                ids.removeAll(result.destroyedIds.toSet())
                for (id in result.destroyedIds) events += SimEvent.ParticleDestroyed(id)
            }
            else -> {} // zero gravity, no named forces/constraints - see ParticleCollisionScene for the wired equivalent
        }
    }

    override fun step(t: Double) {
        integrator.step(store, groups, emptyList(), emptyList(), t, dt)
        floorCollisions.resolve(store, groups, t, dt)
        particleCollisions.resolve(store, groups, emptyList())
    }

    override fun frame(t: Double): SceneFrame {
        val frame = SceneFrame(
            // No sphereRadii override - every ball's dot renders at its own live
            // ParticleStore.radius (§10.4), so an edited radius shows up immediately.
            colliders = liveColliderRules.map { it.collider },
            registry = SceneRegistry.build(groups = groups, colliders = liveColliderRules.map { it.collider }),
            events = events.toList(),
        )
        events.clear()
        return frame
    }
}
