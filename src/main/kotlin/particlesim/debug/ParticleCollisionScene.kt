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
import particlesim.physics.Integrator
import particlesim.physics.UniformGravity
import particlesim.render.SceneRegistry
import kotlin.random.Random

private data class ScenePlaneColliderRule(val collider: PlaneCollider, val rule: ParticleColliderRule)

/**
 * §9.6 scene-library wrapping of [ParticleCollisionDebugDemo]'s worked example - see that file's
 * own doc comment for the friction/staggered-spawning/removable-collider reasoning, all
 * unchanged here. As with [DragScene], wrapping onto [DemoScene] removes the standalone demo's
 * hand-rolled [SceneControlMessage.Restart] branch entirely - a restart is just a fresh instance
 * now, so `store`/`groups`/`gravity` go back to being simple properties; only
 * `liveColliderRules`/`floorCollisions`/`ids`/`spawned`/`nextSpawnT`/`random` stay mutable,
 * because collider removal/particle spawn/delete all mutate *within* one instance's lifetime.
 */
class ParticleCollisionScene : DemoScene {
    private val ballGroup = "balls"
    private val radius = 0.15
    private val ballCount = 18
    private val spawnInterval = 0.35
    private val wallExtent = 1.5

    private val floor = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0), name = "floor")
    private val floorRule = ParticleColliderRule(
        group = ballGroup, collider = floor, restitution = 0.5, compressionDamping = 2.0, extensionDamping = 0.3,
        staticFriction = 0.6, kineticFriction = 0.4,
    )
    private val walls = listOf(
        PlaneCollider(VectorExpr.of(Vector3(-wallExtent, 0.0, 0.0)), normal = Vector3(1.0, 0.0, 0.0), name = "wall-x-neg"),
        PlaneCollider(VectorExpr.of(Vector3(wallExtent, 0.0, 0.0)), normal = Vector3(-1.0, 0.0, 0.0), name = "wall-x-pos"),
        PlaneCollider(VectorExpr.of(Vector3(0.0, 0.0, -wallExtent)), normal = Vector3(0.0, 0.0, 1.0), name = "wall-z-neg"),
        PlaneCollider(VectorExpr.of(Vector3(0.0, 0.0, wallExtent)), normal = Vector3(0.0, 0.0, -1.0), name = "wall-z-pos"),
    )
    private val wallRules = walls.map { ParticleColliderRule(group = ballGroup, collider = it, restitution = 0.5, staticFriction = 0.3, kineticFriction = 0.2) }
    private val allColliderRules = listOf(ScenePlaneColliderRule(floor, floorRule)) + walls.zip(wallRules).map { (w, r) -> ScenePlaneColliderRule(w, r) }

    private val particleRule = ParticleCollisionRule(groupA = ballGroup, restitution = 0.6, compressionDamping = 1.0, staticFriction = 0.4, kineticFriction = 0.3)
    private val particleCollisions = ParticleCollisionSystem(listOf(particleRule))
    private val destruction = DestructionSystem()

    private val groups = Groups()
    private val gravity = UniformGravity(ballGroup, Vector3(0.0, -9.8, 0.0), name = "gravity")
    private var liveColliderRules = allColliderRules
    private var floorCollisions = CollisionSystem(liveColliderRules.map { it.rule })
    private var ids = ArrayList<Int>()
    private var spawned = 0
    private var nextSpawnT = 0.0
    private var random = Random(seed = 1)
    private val integrator = Integrator()
    private val events = mutableListOf<SimEvent>()

    override val dt = 1e-3
    override val store = ParticleStore()

    override fun ids(): List<Int> = ids

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, listOf(gravity), emptyList(), store, t)) return
        when (message) {
            is SceneControlMessage.RemoveCollider -> {
                liveColliderRules = liveColliderRules.filter { it.collider.name != message.name }
                floorCollisions = CollisionSystem(liveColliderRules.map { it.rule })
            }
            is SceneControlMessage.SetColliderActive -> {
                liveColliderRules.find { it.collider.name == message.name }?.collider?.active = message.active
            }
            is SceneControlMessage.SetGroupEnabled -> groups.setEnabled(message.name, message.enabled)
            is SceneControlMessage.DeleteParticle -> {
                val result = destruction.resolve(store, groups, listOf(gravity), t, dt, explicitIds = setOf(message.particleId))
                ids.removeAll(result.destroyedIds.toSet())
                for (id in result.destroyedIds) events += SimEvent.ParticleDestroyed(id)
            }
            else -> {}
        }
    }

    override fun step(t: Double) {
        if (spawned < ballCount && t >= nextSpawnT) {
            val position = Vector3((random.nextDouble() - 0.5) * 1.5, 2.0, (random.nextDouble() - 0.5) * 1.5)
            val id = store.create(position = position, radius = ScalarExpr.of(radius))
            groups.add(ballGroup, id)
            ids += id
            spawned++
            nextSpawnT = t + spawnInterval
        }
        integrator.step(store, groups, listOf(gravity), emptyList(), t, dt)
        floorCollisions.resolve(store, groups, t, dt)
        particleCollisions.resolve(store, groups, emptyList())
    }

    override fun frame(t: Double): SceneFrame {
        val frame = SceneFrame(
            // No sphereRadii override - every ball's dot renders at its own live
            // ParticleStore.radius (§10.4), so an edited radius shows up immediately.
            colliders = liveColliderRules.map { it.collider },
            registry = SceneRegistry.build(forces = listOf(gravity), groups = groups, colliders = liveColliderRules.map { it.collider }),
            events = events.toList(),
        )
        events.clear()
        return frame
    }
}
