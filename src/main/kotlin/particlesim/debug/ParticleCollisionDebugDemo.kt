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
import kotlin.random.Random

/**
 * A visible run of §12.4/§12.5's particle-vs-particle collision: `./gradlew
 * runParticleCollisionDemo`, then open the URL it prints. Balls drop one at a time onto a
 * plane and pile up, colliding with the floor ([CollisionSystem], unchanged from §12.6) *and*
 * with each other ([ParticleCollisionSystem], the new mechanics) — the scenario neither
 * [BallBounceDebugDemo] (one ball, no peers to hit) nor [TrampolineDebugDemo] (one ball, no
 * self-collision) exercises: several simultaneous contacts resolved sequentially within a
 * single step, which is where visible pile-up behavior (settling, or not) shows up that no
 * isolated two-body component test could catch.
 *
 * **Friction is wired in** (floor, walls, and ball-vs-ball all carry static/kinetic
 * coefficients) — this demo is exactly the concrete consumer that promoted §12.5's friction out
 * of `[stretch]` in the first place (see the walls-and-drift history below), so leaving it
 * frictionless here once friction existed would have been an odd thing to ship. With it, the
 * pile actually comes to a full stop rather than staying merely contained by the walls.
 *
 * **Staggered spawning, not all-at-once** — a first version dropped all 18 balls simultaneously
 * from a packed vertical stack (loose horizontal spread didn't fix it either): every ball
 * landed on the pile within the same fraction of a second, so several deep, simultaneous
 * overlaps got resolved sequentially, pair by pair, within one step. That's a real, known limit
 * of this project's single-pass-per-step resolution (§12.4 chose "simplest to implement and
 * reason about" discrete detection deliberately, not an iterative constraint solver) — resolving
 * pair (a,b) can push b deeper into pair (b,c)'s overlap, compounding rather than cancelling.
 * Confirmed live: balls ended up hundreds of meters out at a constant several-m/s horizontal
 * drift that never decayed, before friction existed to bleed it off. Every *pairwise* collision
 * was individually verified correct (restitution, damping, momentum, elastic energy
 * conservation, all to 1e-9 - see [particlesim.collision.ParticleCollisionSystemTest]) — this
 * was a many-body, one-step-of-resolution artifact, not a wrong formula. Dropping one ball every
 * [spawnInterval] means each new arrival only ever compresses an already-mostly-settled pile,
 * never several other falling balls at once.
 *
 * §10.3's time controls (pause/resume, speed, step-once) via [ViewerInput.timeControl] —
 * spawning lives inside the `stepsThisFrame` loop alongside physics, so pausing freezes new
 * arrivals too, not just existing balls. The floor and walls are also broadcast as
 * [particlesim.collision.Collider]s so the pen is actually visible (§10.2's debug-render-all
 * wireframe) rather than invisible geometry the balls just mysteriously stop at.
 *
 * **Removable colliders, interactive delete, and restart**, via [SceneControlMessage]: the
 * viewer's outliner lists each collider by name with a "remove" action (this is *the* way to
 * answer "what does the pile do without the walls?" live, rather than needing a second demo
 * binary just to try it); double-clicking a ball in the 3D view deletes it outright (§14.2's
 * "explicit delete via the viewer"), via the same [particlesim.lifecycle.DestructionSystem]
 * every lifetime/condition/collision-triggered destroy already uses (§14.3's cleanup, reused
 * rather than hand-rolled — balls have no pairwise forces to dangle, but the mechanism stays
 * uniform); and a "restart" button rebuilds the whole scenario from scratch — brand-new
 * `ParticleStore`/`Groups` (old particle ids from before a restart are meaningless, not reused)
 * plus every collider restored, not just the ones still standing. All three arrive on
 * [DebugServer]'s WebSocket I/O thread but are only ever *applied* on the physics loop's own
 * thread via [SceneControlMessageQueue.drainAll] (reached through [ViewerInput.sceneControlQueue])
 * — replacing `store`/`groups`/the live collider list out from under a concurrently-running
 * physics step would be a real race, not just untidy, the same reasoning [DragMessageQueue]
 * already established for per-particle input.
 */
private data class NamedColliderRule(val collider: PlaneCollider, val rule: ParticleColliderRule)

fun main() {
    val ballGroup = "balls"
    val radius = 0.15
    val ballCount = 18
    val spawnInterval = 0.35
    val wallExtent = 1.5

    // The canonical, never-mutated full set of colliders+rules - a restart resets the live set
    // back to exactly this, restoring any wall (or the floor) a "remove" click took out.
    val floor = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0), name = "floor")
    val floorRule = ParticleColliderRule(
        group = ballGroup, collider = floor, restitution = 0.5, compressionDamping = 2.0, extensionDamping = 0.3,
        staticFriction = 0.6, kineticFriction = 0.4,
    )
    val walls = listOf(
        PlaneCollider(VectorExpr.of(Vector3(-wallExtent, 0.0, 0.0)), normal = Vector3(1.0, 0.0, 0.0), name = "wall-x-neg"),
        PlaneCollider(VectorExpr.of(Vector3(wallExtent, 0.0, 0.0)), normal = Vector3(-1.0, 0.0, 0.0), name = "wall-x-pos"),
        PlaneCollider(VectorExpr.of(Vector3(0.0, 0.0, -wallExtent)), normal = Vector3(0.0, 0.0, 1.0), name = "wall-z-neg"),
        PlaneCollider(VectorExpr.of(Vector3(0.0, 0.0, wallExtent)), normal = Vector3(0.0, 0.0, -1.0), name = "wall-z-pos"),
    )
    val wallRules = walls.map { ParticleColliderRule(group = ballGroup, collider = it, restitution = 0.5, staticFriction = 0.3, kineticFriction = 0.2) }
    val allColliderRules = listOf(NamedColliderRule(floor, floorRule)) + walls.zip(wallRules).map { (w, r) -> NamedColliderRule(w, r) }

    val particleRule = ParticleCollisionRule(groupA = ballGroup, restitution = 0.6, compressionDamping = 1.0, staticFriction = 0.4, kineticFriction = 0.3)
    val particleCollisions = ParticleCollisionSystem(listOf(particleRule))
    // No lifetime/condition/collision triggers - only ever driven by SceneControlMessage.DeleteParticle's
    // explicitIds below. Balls have no pairwise forces to dangle, but reusing DestructionSystem
    // keeps one cleanup mechanism everywhere (§14.3) rather than a second hand-rolled one here.
    val destruction = DestructionSystem()

    val viewerInput = ViewerInput()
    val renderer = DebugRenderer(onTextMessage = viewerInput::onTextMessage)
    renderer.start()

    val integrator = Integrator()
    val dt = 1e-3
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / dt).toInt())
    val frameNanos = 1_000_000_000L / framesPerSecond

    // Everything below is rebuilt wholesale on restart - grouped as `var`s (not `val`s) for
    // exactly that reason, all reassigned together in one place rather than mutated piecemeal.
    var store = ParticleStore()
    var groups = Groups()
    var gravity = UniformGravity(ballGroup, Vector3(0.0, -9.8, 0.0))
    var liveColliderRules = allColliderRules
    var floorCollisions = CollisionSystem(liveColliderRules.map { it.rule })
    var ids = ArrayList<Int>()
    var spawned = 0
    var nextSpawnT = 0.0
    var t = 0.0
    var step = 0L
    // Re-seeded on restart too, not just at startup - a restart is meant to reproduce the exact
    // same run, not continue the same random stream from wherever it happened to be.
    var random = Random(seed = 1)

    while (true) {
        val frameStart = System.nanoTime()
        for (message in viewerInput.sceneControlQueue.drainAll()) {
            when (message) {
                is SceneControlMessage.RemoveCollider -> {
                    liveColliderRules = liveColliderRules.filter { it.collider.name != message.name }
                    floorCollisions = CollisionSystem(liveColliderRules.map { it.rule })
                }
                is SceneControlMessage.DeleteParticle -> {
                    val result = destruction.resolve(store, groups, listOf(gravity), t, dt, explicitIds = setOf(message.particleId))
                    ids.removeAll(result.destroyedIds.toSet())
                }
                SceneControlMessage.Restart -> {
                    store = ParticleStore()
                    groups = Groups()
                    gravity = UniformGravity(ballGroup, Vector3(0.0, -9.8, 0.0))
                    liveColliderRules = allColliderRules
                    floorCollisions = CollisionSystem(liveColliderRules.map { it.rule })
                    ids = ArrayList()
                    spawned = 0
                    nextSpawnT = 0.0
                    t = 0.0
                    step = 0L
                    random = Random(seed = 1)
                }
            }
        }
        repeat(viewerInput.timeControl.stepsThisFrame(stepsPerFrame)) {
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
            t += dt
            step++
        }
        renderer.broadcast(
            t, step, store, ids, emptyList(),
            sphereRadii = ids.associateWith { radius },
            colliders = liveColliderRules.map { it.collider },
        )
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
