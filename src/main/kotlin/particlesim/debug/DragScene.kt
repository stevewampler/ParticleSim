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
import particlesim.render.SceneRegistry

/**
 * §9.6 scene-library wrapping of [DragDebugDemo]'s worked example - see that file's own doc
 * comment for the swing-damping/break-threshold/interactive-delete reasoning, all unchanged
 * here. The one real simplification switching to [DemoScene] buys: [SceneLibrary.restart] now
 * discards this whole instance and constructs a fresh one from its factory, so the hand-rolled
 * "rebuild every var from scratch" [SceneControlMessage.Restart] branch the standalone demo
 * needed is gone entirely - `store`/`groups`/`anchorId`/`fixedConstraints` go back to being
 * simple properties, only `ids`/`springs`/`dampers`/`forces`/`activeDrag` stay mutable, and only
 * because [DeleteParticle]/a mid-run break can prune them within *this* instance's lifetime.
 */
class DragScene(private val dragQueue: DragMessageQueue) : DemoScene {
    private val linkCount = 12
    private val spacing = 0.4
    private val mass = 0.2
    private val stiffness = 80.0
    private val damping = 12.0 // above critical (2*sqrt(stiffness*mass) ~= 8.0) for the spring/damper pairs
    private val springBreakThreshold = 0.5

    private fun buildChain(chainIds: List<Int>): Pair<List<Spring>, List<Damper>> {
        val pairs = chainIds.zipWithNext()
        val springs = pairs.mapIndexed { i, (a, b) ->
            Spring(a, b, restLength = spacing, stiffness = stiffness, breakThreshold = springBreakThreshold, name = "link-$i")
        }
        val dampers = pairs.map { (a, b) -> Damper(a, b, damping = damping) }
        return springs to dampers
    }

    override val store = ParticleStore()
    private val groups = Groups()
    private var ids = (0 until linkCount).map { i ->
        store.create(position = Vector3(0.0, 4.0 - i * spacing, 0.0), mass = ScalarExpr.of(mass))
    }
    private val anchorId = ids.first()

    init {
        groups.add("anchor", anchorId)
        groups.add("chain", anchorId)
        ids.drop(1).forEach { groups.add("chain", it) }
    }

    // Kotlin doesn't allow a destructuring declaration for a class property (only local
    // vals/vars), so the pair from buildChain is split into two properties via the init block
    // below rather than `var (springs, dampers) = ...` at this position.
    private var springs: List<Spring>
    private var dampers: List<Damper>

    init {
        val (initialSprings, initialDampers) = buildChain(ids)
        springs = initialSprings
        dampers = initialDampers
    }

    private val drag = Drag("chain", coefficient = 1.5)
    private var forces: List<Force> = listOf(UniformGravity("chain", Vector3(0.0, -9.8, 0.0)), drag) + springs + dampers
    // Named (unlike the standalone DragDebugDemo's identical constraint) specifically so §10.4's
    // shared-position editing has a real, reachable target to verify against - a single-particle
    // pin is the one case in this codebase where the shared-position and per-particle variants
    // coincide, but the constructor used here is still the editable shared-position one.
    private val fixedConstraints = listOf(FixedPosition("anchor", store.position(anchorId), name = "anchor"))
    private val destruction = DestructionSystem()
    private val integrator = Integrator()
    private var activeDrag: DragConstraint? = null
    private val events = mutableListOf<SimEvent>()

    override val dt = 1e-3

    override fun ids(): List<Int> = ids

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, forces, fixedConstraints, store, t)) return
        when (message) {
            is SceneControlMessage.DeleteParticle -> {
                // The pinned anchor can be deleted too - FixedPosition just becomes a no-op for
                // a group with no members left in it, no special-casing needed.
                val result = destruction.resolve(store, groups, forces, t, dt, explicitIds = setOf(message.particleId))
                if (result.destroyedIds.isNotEmpty()) {
                    val destroyedSet = result.destroyedIds.toSet()
                    ids = ids.filter { it !in destroyedSet }
                    val danglingSet = result.danglingForces.toSet()
                    forces = forces.filter { it !in danglingSet }
                    springs = springs.filter { it !in danglingSet }
                    if (activeDrag?.particleId in destroyedSet) activeDrag = null
                    for (id in result.destroyedIds) events += SimEvent.ParticleDestroyed(id)
                }
            }
            is SceneControlMessage.SetGroupEnabled -> groups.setEnabled(message.name, message.enabled)
            else -> {} // no colliders in this scene
        }
    }

    override fun step(t: Double) {
        for (message in dragQueue.drainAll()) {
            when (message) {
                is DragMessage.Start -> {
                    // store.contains guards against a real race, not just defensively: scene
                    // control messages drain once per *frame*, drag messages drain once per
                    // physics *step* inside this same frame - a queued drag_start can still
                    // arrive already targeting an id this frame's delete just removed.
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
        // §5.4's break check is once-per-physics-step, not once-per-frame - handled here, inside
        // step, so a spring that snaps partway through a frame's several steps stops
        // contributing force for the rest of them rather than a stale reference lingering.
        val result = integrator.step(store, groups, forces, constraints, t, dt)
        val brokenSprings = result.brokenForces.filterIsInstance<Spring>()
        if (brokenSprings.isNotEmpty()) {
            val brokenIndices = springs.withIndex().filter { it.value in brokenSprings }.map { it.index }.toSet()
            val danglingDampers = brokenIndices.map { dampers[it] }.toSet()
            val brokenSet = brokenSprings.toSet()
            forces = forces.filter { it !in brokenSet && it !in danglingDampers }
            springs = springs.filterIndexed { i, _ -> i !in brokenIndices }
            dampers = dampers.filterIndexed { i, _ -> i !in brokenIndices }
            for (spring in brokenSprings) events += SimEvent.ForceBreak(spring.name ?: "")
        }
    }

    override fun frame(t: Double): SceneFrame {
        val lineColors = springs.associate { spring ->
            (spring.particleA to spring.particleB) to LineRendering.colorFor(LineRenderer(spring, ColorBy.BREAK_PROXIMITY), store)!!
        }
        val connectionNames = springs.mapNotNull { spring -> spring.name?.let { (spring.particleA to spring.particleB) to it } }.toMap()
        val frame = SceneFrame(
            connections = springs.map { it.particleA to it.particleB },
            lineColors = lineColors,
            connectionNames = connectionNames,
            registry = SceneRegistry.build(forces = forces, constraints = fixedConstraints, groups = groups),
            events = events.toList(),
        )
        events.clear()
        return frame
    }
}
