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

/**
 * §9.3's shared spatial-partitioning grid ([particlesim.collision.SpatialGrid]), demonstrated at
 * the scale that motivated it: `./gradlew runSpatialGridDemo`. [ballCount] particles bounce
 * around inside a sealed box, colliding with the six walls and with each other — the concrete
 * consumer TODO.md's own deferral note asked for ("revisit once a scenario's particle count
 * makes O(n²) pairwise checks the actual bottleneck").
 *
 * **A floating cloud, not a pile-up** — deliberately *not* [ParticleCollisionDebugDemo] scaled
 * up. That demo's own doc comment already recorded what happens at high density under this
 * project's single-pass-per-step collision resolution (§12.4's deliberate simplicity, not an
 * iterative constraint solver): several deep, simultaneous overlaps compound rather than
 * cancel, and the pile blows apart. A deep pile of thousands is exactly that failure mode by
 * construction, not a broad-phase showcase. Zero gravity and a sealed box (floor, ceiling, and
 * four walls) instead keep the scene permanently sparse relative to the box volume — plenty of
 * genuine ball-vs-ball and ball-vs-wall contacts happen continuously, but never a compressed
 * stack. This is still a fair test: candidate-pair generation cost (what the grid actually
 * accelerates) is paid every physics step *regardless of whether any pair is actually
 * overlapping* — a brute-force double loop is exactly as expensive here as in a packed pile of
 * the same particle count. Measured directly (scratch benchmark, not shipped as a test — timing
 * assertions are flaky): at [ballCount] balls, generating candidate pairs the old brute-force
 * way took several tens of milliseconds *per call*; the grid-accelerated [ParticleCollisionSystem
 * .resolve] (candidate generation, narrow phase, and response together) took low single-digit
 * milliseconds. See TODO.md for the full N-scaling table.
 *
 * **Live in Chrome, this demo does not actually hold 60fps real-time on the dev machine it was
 * verified on** — confirmed this is not a broad-phase regression: [ParticleCollisionDebugDemo]
 * (18 balls, brute-force-scale, completely unmodified by this work) shows the *same* ~75%-of-
 * real-time "lag" stat live in the viewer. Whatever the actual ceiling is (this environment's
 * `Thread.sleep` granularity, the WebSocket broadcast path, general VM scheduling — not
 * profiled, since it's orthogonal to this task), it is a fixed per-frame cost that exists
 * identically at 18 particles and at 2000, not something that scales with candidate-pair count.
 * The thing this demo actually needs to show — that grid-accelerated [ParticleCollisionSystem
 * .resolve] handles [ballCount] particles without the physics itself becoming the bottleneck —
 * is established separately, by the scratch benchmark cited above, not by this demo's on-screen
 * frame rate.
 *
 * Otherwise follows [ParticleCollisionDebugDemo]'s established shape: removable colliders and
 * restart via [SceneControlMessage], interactive delete via double-click, all applied only on
 * the physics loop's own thread through [SceneControlMessageQueue.drainAll].
 */
private data class BoxColliderRule(val collider: PlaneCollider, val rule: ParticleColliderRule)

fun main() {
    val ballGroup = "balls"
    val radius = 0.05
    val ballCount = 2000
    val boxExtent = 3.0

    val floor = PlaneCollider(VectorExpr.of(Vector3(0.0, -boxExtent, 0.0)), normal = Vector3(0.0, 1.0, 0.0), name = "floor")
    val ceiling = PlaneCollider(VectorExpr.of(Vector3(0.0, boxExtent, 0.0)), normal = Vector3(0.0, -1.0, 0.0), name = "ceiling")
    val walls = listOf(
        PlaneCollider(VectorExpr.of(Vector3(-boxExtent, 0.0, 0.0)), normal = Vector3(1.0, 0.0, 0.0), name = "wall-x-neg"),
        PlaneCollider(VectorExpr.of(Vector3(boxExtent, 0.0, 0.0)), normal = Vector3(-1.0, 0.0, 0.0), name = "wall-x-pos"),
        PlaneCollider(VectorExpr.of(Vector3(0.0, 0.0, -boxExtent)), normal = Vector3(0.0, 0.0, 1.0), name = "wall-z-neg"),
        PlaneCollider(VectorExpr.of(Vector3(0.0, 0.0, boxExtent)), normal = Vector3(0.0, 0.0, -1.0), name = "wall-z-pos"),
    )
    val allPlanes = listOf(floor, ceiling) + walls
    // Restitution 0.98, essentially elastic: kept energetic and chaotic-looking rather than
    // settling to rest, since a continuously-moving cloud is the more informative real-time
    // stress test (contacts keep happening for the whole run, not just at the start).
    val allColliderRules = allPlanes.map {
        BoxColliderRule(it, ParticleColliderRule(group = ballGroup, collider = it, restitution = 0.98))
    }

    val particleRule = ParticleCollisionRule(groupA = ballGroup, restitution = 0.98)
    val particleCollisions = ParticleCollisionSystem(listOf(particleRule))
    val destruction = DestructionSystem()

    val viewerInput = ViewerInput()
    val renderer = DebugRenderer(onTextMessage = viewerInput::onTextMessage)
    renderer.start()

    val integrator = Integrator()
    val dt = 4e-3
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / dt).toInt())
    val frameNanos = 1_000_000_000L / framesPerSecond

    fun buildScene(random: Random): Pair<ParticleStore, Groups> {
        val store = ParticleStore()
        val groups = Groups()
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
        return store to groups
    }

    var random = Random(seed = 1)
    var (store, groups) = buildScene(random)
    var liveColliderRules = allColliderRules
    var floorCollisions = CollisionSystem(liveColliderRules.map { it.rule })
    var ids = ArrayList(groups.membersOf(ballGroup).toList())
    var t = 0.0
    var step = 0L

    while (true) {
        val frameStart = System.nanoTime()
        val events = mutableListOf<SimEvent>()
        for (message in viewerInput.sceneControlQueue.drainAll()) {
            when (message) {
                is SceneControlMessage.RemoveCollider -> {
                    liveColliderRules = liveColliderRules.filter { it.collider.name != message.name }
                    floorCollisions = CollisionSystem(liveColliderRules.map { it.rule })
                }
                is SceneControlMessage.SetColliderActive -> {
                    liveColliderRules.find { it.collider.name == message.name }?.collider?.active = message.active
                }
                is SceneControlMessage.SetGroupEnabled -> groups.setEnabled(message.name, message.enabled)
                // This demo has zero gravity and no named forces/constraints (see its own doc
                // comment) - see ParticleCollisionDebugDemo for the wired field-edit equivalent.
                is SceneControlMessage.SetScalarField -> {}
                is SceneControlMessage.SetVectorField -> {}
                // Particles are id-addressed, unlike the name-addressed fields above, so this
                // works here even though this demo names no forces/constraints.
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
                SceneControlMessage.Restart -> {
                    random = Random(seed = 1)
                    val (newStore, newGroups) = buildScene(random)
                    store = newStore
                    groups = newGroups
                    liveColliderRules = allColliderRules
                    floorCollisions = CollisionSystem(liveColliderRules.map { it.rule })
                    ids = ArrayList(groups.membersOf(ballGroup).toList())
                    t = 0.0
                    step = 0L
                }
            }
        }
        repeat(viewerInput.timeControl.stepsThisFrame(stepsPerFrame)) {
            integrator.step(store, groups, emptyList(), emptyList(), t, dt)
            floorCollisions.resolve(store, groups, t, dt)
            particleCollisions.resolve(store, groups, emptyList())
            t += dt
            step++
        }
        renderer.broadcast(
            t, step, store, ids, emptyList(),
            // No sphereRadii override - every ball's dot now renders at its own live
            // ParticleStore.radius (the viewer's client-side default, §10.4), so an edited
            // radius shows up immediately instead of being masked by a static render size.
            colliders = liveColliderRules.map { it.collider },
            registry = SceneRegistry.build(groups = groups, colliders = liveColliderRules.map { it.collider }),
            events = events,
        )
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
