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
 * **Staggered spawning, not all-at-once** — a first version dropped all 18 balls simultaneously
 * from a packed vertical stack (loose horizontal spread didn't fix it either): every ball
 * landed on the pile within the same fraction of a second, so several deep, simultaneous
 * overlaps got resolved sequentially, pair by pair, within one step. That's a real, known limit
 * of this project's single-pass-per-step resolution (§12.4 chose "simplest to implement and
 * reason about" discrete detection deliberately, not an iterative constraint solver) — resolving
 * pair (a,b) can push b deeper into pair (b,c)'s overlap, compounding rather than cancelling.
 * Confirmed live: balls ended up hundreds of meters out at a constant several-m/s horizontal
 * drift that never decayed, since floor/ball friction isn't implemented yet (§12.5 `[stretch]`)
 * and a frictionless floor has nothing to bleed off sideways velocity once it exists. Every
 * *pairwise* collision was individually verified correct (restitution, damping, momentum,
 * elastic energy conservation, all to 1e-9 - see [particlesim.collision.ParticleCollisionSystemTest]) —
 * this was a many-body, one-step-of-resolution artifact, not a wrong formula. Dropping one ball
 * every [spawnInterval] means each new arrival only ever compresses an already-mostly-settled
 * pile, never several other falling balls at once — it noticeably reduced the drift, but didn't
 * eliminate it: a ball landing on an existing pile still nudges its neighbors sideways, and
 * with no friction that sideways nudge is permanent no matter how gentle the landing. Four wall
 * colliders below pen the pile in rather than chasing that gap further — containing the demo,
 * not fixing physics that's working exactly as documented (§12.5 lists friction `[stretch]`,
 * not "done").
 *
 * §10.3's time controls (pause/resume, speed, step-once) via [TimeControl], same pattern as
 * [FlagDebugDemo] — spawning lives inside the `stepsThisFrame` loop alongside physics, so
 * pausing freezes new arrivals too, not just existing balls. The floor and four walls are also
 * broadcast as [particlesim.collision.Collider]s so the pen is actually visible (§10.2's
 * debug-render-all wireframe) rather than invisible geometry the balls just mysteriously stop
 * at.
 */
fun main() {
    val store = ParticleStore()
    val groups = Groups()
    val ballGroup = "balls"

    val ballCount = 18
    val radius = 0.15
    val spawnInterval = 0.35
    val random = Random(seed = 1)
    var spawned = 0
    val ids = ArrayList<Int>()

    val gravity = UniformGravity(ballGroup, Vector3(0.0, -9.8, 0.0))
    val floor = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0), name = "floor")
    // Four walls penning the pile into a +-1.5m square - without floor/ball friction (§12.5
    // `[stretch]`), nothing else stops a ball nudged sideways during landing from drifting
    // forever, so this keeps the demo visually contained rather than scattering across the
    // scene (see this file's own doc comment for how that was found).
    val wallExtent = 1.5
    val walls = listOf(
        PlaneCollider(VectorExpr.of(Vector3(-wallExtent, 0.0, 0.0)), normal = Vector3(1.0, 0.0, 0.0), name = "wall-x-neg"),
        PlaneCollider(VectorExpr.of(Vector3(wallExtent, 0.0, 0.0)), normal = Vector3(-1.0, 0.0, 0.0), name = "wall-x-pos"),
        PlaneCollider(VectorExpr.of(Vector3(0.0, 0.0, -wallExtent)), normal = Vector3(0.0, 0.0, 1.0), name = "wall-z-neg"),
        PlaneCollider(VectorExpr.of(Vector3(0.0, 0.0, wallExtent)), normal = Vector3(0.0, 0.0, -1.0), name = "wall-z-pos"),
    )
    val floorRule = ParticleColliderRule(group = ballGroup, collider = floor, restitution = 0.5, compressionDamping = 2.0, extensionDamping = 0.3)
    val wallRules = walls.map { ParticleColliderRule(group = ballGroup, collider = it, restitution = 0.5) }
    val floorCollisions = CollisionSystem(listOf(floorRule) + wallRules)

    val particleRule = ParticleCollisionRule(groupA = ballGroup, restitution = 0.6, compressionDamping = 1.0)
    val particleCollisions = ParticleCollisionSystem(listOf(particleRule))

    val timeControl = TimeControl()
    val renderer = DebugRenderer(onTextMessage = { text ->
        TimeControlMessage.parse(text)?.let(timeControl::apply)
    })
    renderer.start()

    val integrator = Integrator()
    val dt = 1e-3
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / dt).toInt())

    var t = 0.0
    var step = 0L
    var nextSpawnT = 0.0
    val frameNanos = 1_000_000_000L / framesPerSecond
    while (true) {
        val frameStart = System.nanoTime()
        repeat(timeControl.stepsThisFrame(stepsPerFrame)) {
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
            colliders = listOf(floor) + walls,
        )
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
