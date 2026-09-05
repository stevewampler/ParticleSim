package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.lifecycle.DestructionSystem
import particlesim.physics.Force
import particlesim.physics.Integrator
import particlesim.physics.MeshSprings
import particlesim.physics.Spring
import particlesim.render.SceneRegistry
import particlesim.yaml.YamlLoader

/**
 * §9.6 scene-library wrapping of a demo file under `src/main/resources/yaml/` — the bridge
 * TODO.md flagged as missing when the YAML front-end's second pass finished ("no path from a
 * loaded YAML scenario to the viewer"). One generic [DemoScene] over any [particlesim.yaml.YamlScenario],
 * not one hand-written wrapper per file: unlike `FlagScene`/`TrampolineScene`/etc. (which each
 * add real viewer-only decoration — a camera function, a textured/materialed
 * [particlesim.render.SurfaceRenderer], drag interactivity, `SurfaceSelfCollisionSystem`), a
 * YAML file carries no such thing to begin with (§4.2's scope stops at the physics scenario), so
 * there's nothing scene-specific left to hand-write here — every field a [YamlScenario] can
 * produce (forces/constraints/colliders/three collision systems/destruction/emitters/lights) is
 * handled uniformly.
 *
 * **What this deliberately doesn't render**: no [particlesim.render.SurfaceRenderer] mesh for a
 * `grid:`-generated surface (e.g. the flag/trampoline mat's cloth) — a grid's own
 * `mesh_springs` forces already render as visible line connections (see [frame]'s own
 * `connections` below), which is enough for "reachable and inspectable," matching how
 * `DragScene`/`ParticleCollisionScene`/`SpatialGridScene` already render as dots+lines with no
 * mesh either. Building a synthesized wireframe mesh generically would be a real addition, not
 * "wiring in what's already there."
 *
 * **Interactivity**: every §10.4 live-editing message (`SetScalarField`/`SetVectorField`/
 * particle mass-radius/emitter edits) works generically via [applyEditableFieldMessage]/
 * [applyEmitterMessage], plus `SetGroupEnabled`/`SetColliderActive`/`DeleteParticle` (every one
 * of these needs only a name/id lookup against state a [particlesim.yaml.YamlScenario] already
 * exposes). `RemoveCollider` is the one message left unhandled — the three collision systems
 * built by [particlesim.yaml.YamlLoader] hold their own rule lists privately with no way to
 * rebuild a filtered copy from outside, the same limitation `FlagScene`/`BallBounceScene`/
 * `TrampolineScene` (every scene built from a reusable `buildX()` function, as opposed to
 * `ParticleCollisionScene`/`SpatialGridScene`'s own ad hoc `liveColliderRules`) already have.
 * There's no drag support either, for the same "nothing to hand it a `DragMessageQueue` for"
 * reason plain [buildX()]-backed scenes don't have one.
 *
 * **No re-drop cycle**: `BallBounceScene`/`TrampolineScene` reset their ball to its drop height
 * every 8-10 seconds so the demo keeps demonstrating something instead of sitting settled
 * forever - viewer-loop state with no YAML representation, so `ballBounce.yaml`/
 * `trampoline.yaml` here just settle once and stay settled. Not a bug: picking one of these
 * right after its Kotlin-DSL namesake and seeing it go still is this difference, not a broken
 * load.
 */
class YamlDemoScene(resourceName: String, override val dt: Double) : DemoScene {
    private val scenario = run {
        val text = javaClass.getResourceAsStream("/yaml/$resourceName.yaml")
            ?.bufferedReader()?.readText()
            ?: throw IllegalStateException("YAML demo resource not found: /yaml/$resourceName.yaml")
        YamlLoader(onWarning = { System.err.println("[$resourceName.yaml] $it") }).load(text)
    }

    private val integrator = Integrator()
    // A YAML scenario with no destroy: section still gets a real (empty-condition)
    // DestructionSystem, purely so DeleteParticle works uniformly - resolving against zero
    // conditions plus an explicit id set is exactly what an interactive delete needs, the same
    // shape DragScene's own hand-rolled DestructionSystem() already takes for the same reason.
    private val destruction = scenario.destruction ?: DestructionSystem()
    private var forces: List<Force> = scenario.forces
    private var constraints = scenario.constraints
    private val pendingDeletes = mutableSetOf<Int>()
    private val events = mutableListOf<SimEvent>()

    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = store.liveIds()

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, forces, constraints, store, t, scenario.lights)) return
        if (applyEmitterMessage(message, scenario.emitters)) return
        when (message) {
            is SceneControlMessage.SetGroupEnabled -> scenario.groups.setEnabled(message.name, message.enabled)
            is SceneControlMessage.SetColliderActive -> scenario.colliders[message.name]?.active = message.active
            is SceneControlMessage.DeleteParticle -> pendingDeletes += message.particleId
            else -> {} // RemoveCollider/dragging aren't supported - see class doc comment for why
        }
    }

    override fun step(t: Double) {
        val stepResult = integrator.step(store, scenario.groups, forces, constraints, t, dt)
        if (stepResult.brokenForces.isNotEmpty()) {
            val broken = stepResult.brokenForces.toSet()
            forces = forces.filter { it !in broken }
            for (force in stepResult.brokenForces) events += SimEvent.ForceBreak(force.name ?: "")
        }

        scenario.collisionSystem?.resolve(store, scenario.groups, t, dt)
        scenario.particleCollisionSystem?.resolve(store, scenario.groups, constraints)
        scenario.surfaceCollisionSystem?.resolve(store, scenario.groups, t, dt)

        val explicit = pendingDeletes.toSet()
        pendingDeletes.clear()
        val destroyed = destruction.resolve(store, scenario.groups, forces, t, dt, explicitIds = explicit)
        if (destroyed.destroyedIds.isNotEmpty()) {
            val dangling = destroyed.danglingForces.toSet()
            forces = forces.filter { it !in dangling }
            for (id in destroyed.destroyedIds) events += SimEvent.ParticleDestroyed(id)
        }

        for (emitter in scenario.emitters) {
            val emitted = emitter.update(store, scenario.groups, t, dt)
            for (id in emitted.spawnedIds) events += SimEvent.ParticleSpawned(id)
            for (id in emitted.evictedIds) events += SimEvent.ParticleDestroyed(id)
        }
    }

    override fun frame(t: Double): SceneFrame {
        // MeshSprings.activeConnections() for a mesh's own edges; a standalone Spring renders as
        // one connection per instance. Damper deliberately excluded - it shares its pair's
        // endpoints with a parallel Spring in every current YAML demo, so including it would draw
        // the same line segment twice (the same reasoning DragScene's own `connections` already
        // applies by building its line list from `springs`, not `springs + dampers`).
        val connections = forces.flatMap { force ->
            when (force) {
                is MeshSprings -> force.activeConnections()
                is Spring -> listOf(force.particleA to force.particleB)
                else -> emptyList()
            }
        }
        val frame = SceneFrame(
            connections = connections,
            registry = SceneRegistry.build(
                forces = forces, constraints = constraints, groups = scenario.groups,
                colliders = scenario.colliders.values.toList(), emitters = scenario.emitters, lights = scenario.lights,
            ),
            colliders = scenario.colliders.values.toList(),
            lights = scenario.lights,
            events = events.toList(),
        )
        events.clear()
        return frame
    }
}
