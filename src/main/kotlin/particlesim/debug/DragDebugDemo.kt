package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.lifecycle.DestructionSystem
import particlesim.physics.Constraint
import particlesim.physics.Damper
import particlesim.physics.Drag
import particlesim.physics.DragConstraint
import particlesim.physics.FixedPosition
import particlesim.physics.Force
import particlesim.physics.Integrator
import particlesim.physics.Spring
import particlesim.physics.UniformGravity
import particlesim.render.ColorBy
import particlesim.render.LineRenderer
import particlesim.render.LineRendering

/**
 * §9.4's interactive drag worked example: the same spring-chain scenario as the default `run`
 * demo (one end pinned, hanging under gravity), extended so any free particle can be
 * click-dragged — the motion propagates through the springs to the rest of the chain, exactly
 * the "probe a simulation's behavior" scenario §9.4 itself uses to motivate the feature.
 * `./gradlew runDragDemo`, then open the URL it prints: click and drag a dot (not the pinned
 * one at the far left), release to throw it.
 *
 * Getting this to actually settle (rather than swing indefinitely, which looked like the whole
 * chain "flying around" and made it impossible to click a dot) took two separate fixes, verified
 * by sampling positions over several seconds through a raw WebSocket client rather than assumed:
 * - **`Damper` alongside every `Spring`** (§5.1's usual pairing) damps *relative* motion between
 *   connected pairs — necessary so the springs themselves don't ring, but this alone left the
 *   whole chain gently swinging like a pendulum for many seconds, because a bulk swing barely
 *   stretches any individual spring (low relative velocity between neighbors even while the
 *   whole chain moves significantly in the lab frame), so per-pair damping barely touches it.
 * - **A `Drag` force on the whole group** damps *absolute* velocity instead, which is what
 *   actually kills that bulk pendulum mode. Both are needed — Damper alone (first attempt)
 *   wasn't enough, confirmed by watching it keep swinging for 8+ seconds without decaying.
 *
 * The starting layout also hangs each link straight down from the anchor (not bunched at one
 * height like the plain `run` demo) — that alone doesn't fix the swinging (dragging and
 * releasing re-introduces the same bulk-swing problem regardless of starting shape, which is
 * why Drag above is still needed), but it does avoid injecting a large, unnecessary initial
 * swing from the moment the demo starts.
 *
 * **Interactive delete** (§14.2's "explicit delete via the viewer, alongside interactive
 * dragging"), via [SceneControlMessage.DeleteParticle]: double-clicking a link in the viewer
 * severs the chain there. Unlike [ParticleCollisionDebugDemo]'s balls, a chain link's deletion
 * actually has something to clean up — [DestructionSystem]'s reported `danglingForces` (the
 * Spring/Damper pair on either side of the deleted link) are pruned from both `forces` (what
 * physics actually sees) and `springs` (what the viewer draws as connecting lines), the same
 * cleanup mechanism §14.3 already established for lifetime/condition/collision-triggered
 * destroys, not a hand-rolled one-off for this demo. The result is two independently-hanging
 * pieces, not a chain that silently keeps simulating a phantom connection. Also emits a
 * [SimEvent.ParticleDestroyed] into §9.1's discrete-event channel for each deleted id — a
 * second, deliberately-triggered destroy consumer alongside [SparksDebugDemo]'s continuous one,
 * proving the channel isn't specific to *how* a particle died.
 *
 * **Breakable structural springs** (§5.4), the first live demo to actually exercise it — every
 * prior chance (`buildFlag`'s structural springs) left `breakThreshold` at its infinite default
 * after reverted attempts elsewhere in this project ran into ringing/overshoot from sub-critical
 * damping falsely tripping the threshold (see [particlesim.debug.FlagDebugDemo]'s own doc
 * comment). This chain's damping is already tuned *above* critical (see above), so a sudden
 * drag reposition doesn't ring the same way — the risk that sank the earlier attempt doesn't
 * apply here. Each [Spring] is individually named (`"link-0"`, `"link-1"`, ...) and colored by
 * [ColorBy.BREAK_PROXIMITY] (blue at rest, shading toward orange as its stretch approaches
 * [springBreakThreshold]) — §10.2's break-proximity line renderer, defined and unit-tested since
 * Phase 9 but never actually driven by a live demo until now. Dragging a link far enough from
 * its neighbor snaps that one spring **and** its paired [Damper] (same cleanup as interactive
 * delete's `danglingForces` — a spring's rest-length spec and its damper are one physical
 * connection, so both go or neither does), splitting the chain into two independently-falling
 * pieces without breaking anything else. Emits a [SimEvent.ForceBreak] naming the snapped
 * spring into §9.1's discrete-event channel — the first live consumer of that variant (spawn/
 * destroy already had `SparksDebugDemo`/this file's own delete path; break never had one).
 * `Integrator.step`'s returned `StepResult.brokenForces` is captured and its forces actually
 * removed from the active list here — a real, separate gap this closes: no demo before this one
 * ever captured that return value at all, so §5.4's "the caller must drop a broken force from
 * its list" contract had never actually been discharged anywhere, only unit-tested.
 *
 * Playback controls (pause/speed/step-once) via [ViewerInput], which also bundles the drag and
 * scene-control queues this demo actually uses — see that class's own doc comment for why every
 * debug demo shares one of these now instead of each hand-rolling its own `onTextMessage`. The
 * viewer's restart button rebuilds the whole chain from scratch (fresh `ParticleStore`/`Groups`,
 * same as [ParticleCollisionDebugDemo]'s restart) — an earlier version of this demo's scene-
 * control handling only matched `DeleteParticle` with a plain `if`, so `Restart` (and any future
 * `SceneControlMessage` case) silently fell through and did nothing; switched to an exhaustive
 * `when` so a case like that can't go quietly unhandled again.
 */
fun main() {
    val linkCount = 12
    val spacing = 0.4
    val mass = 0.2
    val stiffness = 80.0
    val damping = 12.0 // above critical (2*sqrt(stiffness*mass) ~= 8.0) for the spring/damper pairs
    // Chosen empirically (live in Chrome, not derived): loose enough that the chain's own resting
    // sag under gravity - which, near the anchor, must support every link below it - never gets
    // close, tight enough that a deliberate drag reaches it in a couple of visible seconds rather
    // than requiring the link be dragged off-screen.
    val springBreakThreshold = 0.5
    val drag = Drag("chain", coefficient = 1.5) // damps bulk/pendulum-style motion Damper can't reach; a
    // group-targeted force needs no rebuilding on restart, unlike everything below that holds
    // particle ids directly.
    val destruction = DestructionSystem() // stateless (just triggers/rules) - reused across restarts

    // One spring + one damper per adjacent pair, index-aligned (same pair at the same index in
    // both lists) so a broken spring's index tells us exactly which damper is now dangling too -
    // see the break-handling below. Each spring is individually named so a ForceBreak event can
    // say *which* link snapped, not just that something did.
    fun buildChain(chainIds: List<Int>): Pair<List<Spring>, List<Damper>> {
        val pairs = chainIds.zipWithNext()
        val springs = pairs.mapIndexed { i, (a, b) ->
            Spring(a, b, restLength = spacing, stiffness = stiffness, breakThreshold = springBreakThreshold, name = "link-$i")
        }
        val dampers = pairs.map { (a, b) -> Damper(a, b, damping = damping) }
        return springs to dampers
    }

    // Everything below is rebuilt wholesale on restart - grouped as `var`s (not `val`s) for
    // exactly that reason, all reassigned together in one place rather than mutated piecemeal
    // (same pattern ParticleCollisionDebugDemo's own restart already established).
    var store = ParticleStore()
    var groups = Groups()
    var ids = (0 until linkCount).map { i ->
        store.create(position = Vector3(0.0, 4.0 - i * spacing, 0.0), mass = ScalarExpr.of(mass))
    }
    var anchorId = ids.first()
    groups.add("anchor", anchorId)
    groups.add("chain", anchorId)
    ids.drop(1).forEach { groups.add("chain", it) }
    var (springs, dampers) = buildChain(ids)
    var forces: List<Force> = listOf(UniformGravity("chain", Vector3(0.0, -9.8, 0.0)), drag) + springs + dampers
    var fixedConstraints = listOf(FixedPosition("anchor", store.position(anchorId)))

    val viewerInput = ViewerInput()
    val renderer = DebugRenderer(onTextMessage = viewerInput::onTextMessage)
    renderer.start()

    val integrator = Integrator()
    val dt = 1e-3
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / dt).toInt())

    var t = 0.0
    var step = 0L
    var activeDrag: DragConstraint? = null
    val frameNanos = 1_000_000_000L / framesPerSecond

    while (true) {
        val frameStart = System.nanoTime()
        val events = mutableListOf<SimEvent>()
        for (message in viewerInput.sceneControlQueue.drainAll()) {
            when (message) {
                is SceneControlMessage.DeleteParticle -> {
                    // The pinned anchor can be deleted too - FixedPosition just becomes a no-op
                    // for a group with no members left in it, no special-casing needed (§10.3's
                    // collider removal already established this "let it happen" stance).
                    val result = destruction.resolve(store, groups, forces, t, dt, explicitIds = setOf(message.particleId))
                    if (result.destroyedIds.isNotEmpty()) {
                        val destroyedSet = result.destroyedIds.toSet()
                        ids = ids.filter { it !in destroyedSet }
                        val danglingSet = result.danglingForces.toSet()
                        forces = forces.filter { it !in danglingSet }
                        springs = springs.filter { it !in danglingSet }
                        if (activeDrag?.particleId in destroyedSet) activeDrag = null
                        // §9.1's discrete-event channel: a second, user-triggered destroy
                        // consumer alongside SparksDebugDemo's continuous one - confirms the
                        // channel isn't tied to a specific *cause* of destruction.
                        for (id in result.destroyedIds) events += SimEvent.ParticleDestroyed(id)
                    }
                }
                SceneControlMessage.Restart -> {
                    store = ParticleStore()
                    groups = Groups()
                    ids = (0 until linkCount).map { i ->
                        store.create(position = Vector3(0.0, 4.0 - i * spacing, 0.0), mass = ScalarExpr.of(mass))
                    }
                    anchorId = ids.first()
                    groups.add("anchor", anchorId)
                    groups.add("chain", anchorId)
                    ids.drop(1).forEach { groups.add("chain", it) }
                    val (rebuiltSprings, rebuiltDampers) = buildChain(ids)
                    springs = rebuiltSprings
                    dampers = rebuiltDampers
                    forces = listOf(UniformGravity("chain", Vector3(0.0, -9.8, 0.0)), drag) + springs + dampers
                    fixedConstraints = listOf(FixedPosition("anchor", store.position(anchorId)))
                    activeDrag = null
                    t = 0.0
                    step = 0L
                }
                is SceneControlMessage.RemoveCollider -> {} // this demo has no colliders
            }
        }
        repeat(viewerInput.timeControl.stepsThisFrame(stepsPerFrame)) {
            for (message in viewerInput.dragQueue.drainAll()) {
                when (message) {
                    is DragMessage.Start -> {
                        // The pinned anchor can't be dragged - it would just fight FixedPosition.
                        // store.contains guards against a real race, not just defensively:
                        // SceneControlMessages (delete) drain once per *frame*, DragMessages
                        // drain once per physics *step* inside this same frame's repeat loop -
                        // a double-click's own stray drag_start/drag_end (see this file's own
                        // doc comment) can still be sitting queued when the frame's delete
                        // already ran, so a queued drag_start can arrive already targeting a
                        // just-destroyed id.
                        if (message.particleId != anchorId && store.contains(message.particleId)) {
                            activeDrag = DragConstraint(message.particleId, message.target)
                        }
                    }
                    is DragMessage.Move -> activeDrag?.updateTarget(message.target, dt)
                    is DragMessage.End -> {
                        activeDrag?.let { if (store.contains(it.particleId)) store.setVelocity(it.particleId, it.releaseVelocity()) }
                        activeDrag = null
                    }
                }
            }
            val constraints: List<Constraint> = activeDrag?.let { fixedConstraints + it } ?: fixedConstraints
            // §5.4's break check is once-per-physics-step, not once-per-frame - checked and
            // handled here (inside the repeat loop), not after it, so a spring that snaps on,
            // say, this frame's 3rd of 16 steps stops contributing force for the remaining 13
            // rather than a stale reference lingering until the next broadcast.
            val result = integrator.step(store, groups, forces, constraints, t, dt)
            val brokenSprings = result.brokenForces.filterIsInstance<Spring>()
            if (brokenSprings.isNotEmpty()) {
                // Index-aligned with dampers (see buildChain) - a broken spring's index is
                // exactly its paired damper's index, the same "one physical connection, both
                // forces go together" reasoning DeleteParticle's danglingForces already applies.
                val brokenIndices = springs.withIndex().filter { it.value in brokenSprings }.map { it.index }.toSet()
                val danglingDampers = brokenIndices.map { dampers[it] }.toSet()
                val brokenSet = brokenSprings.toSet()
                forces = forces.filter { it !in brokenSet && it !in danglingDampers }
                springs = springs.filterIndexed { i, _ -> i !in brokenIndices }
                dampers = dampers.filterIndexed { i, _ -> i !in brokenIndices }
                for (spring in brokenSprings) events += SimEvent.ForceBreak(spring.name ?: "")
            }
            t += dt
            step++
        }
        // Break-proximity coloring (§10.2): blue at rest, shading to orange as a spring's
        // current stretch approaches springBreakThreshold - the first live demo to actually
        // drive this renderer (see this file's own doc comment). colorFor's only null case is
        // colorBy=NONE, which this never uses, so the assertion is safe.
        val lineColors = springs.associate { spring ->
            (spring.particleA to spring.particleB) to LineRendering.colorFor(LineRenderer(spring, ColorBy.BREAK_PROXIMITY), store)!!
        }
        renderer.broadcast(t, step, store, ids, springs.map { it.particleA to it.particleB }, lineColors = lineColors, events = events)
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
