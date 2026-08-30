package particlesim.debug

import particlesim.collision.Collider
import particlesim.core.ParticleStore
import particlesim.lifecycle.Emitter
import particlesim.lifecycle.EmitterCapPolicy
import particlesim.physics.Constraint
import particlesim.physics.EditableFields
import particlesim.physics.FieldValue
import particlesim.physics.Force
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.NamedArrowSamples
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer

/**
 * Everything one [DemoScene.frame] call needs to hand [DebugRenderer.broadcast] besides `t`,
 * `step`, `store`, and `ids` (which the runner already has) - one data class per frame, not one
 * `broadcast` call per scene, so every scene's argument marshalling goes through the same single
 * call site in the generic runner instead of each scene independently coupling to
 * [DebugRenderer.broadcast]'s signature (exactly the per-scene-duplication requirements.md §9.6
 * calls out). Every field defaults the same way [DebugRenderer.broadcast]'s own optional
 * parameters do, so a scene with nothing to say about (e.g.) meshes just doesn't set it.
 */
data class SceneFrame(
    val connections: List<Pair<Int, Int>> = emptyList(),
    val camera: CameraPose? = null,
    val lineColors: Map<Pair<Int, Int>, Color> = emptyMap(),
    val connectionNames: Map<Pair<Int, Int>, String> = emptyMap(),
    val sphereRadii: Map<Int, Double> = emptyMap(),
    val meshes: List<SurfaceRenderer> = emptyList(),
    val arrowGroups: List<NamedArrowSamples> = emptyList(),
    val visibleIds: Set<Int>? = null,
    val registry: SceneRegistry = SceneRegistry.build(),
    val colliders: List<Collider> = emptyList(),
    val events: List<SimEvent> = emptyList(),
)

/**
 * §9.6's scene library entry point: one runnable simulation, built fresh by the library's
 * factory function each time it's loaded (see the generic runner) so [LoadScene]/[Restart] are
 * both just "discard this instance, construct a new one" - no scene implements its own reset
 * logic. [dt] and [store] are read once per scene instance (a scene's own fixed timestep and
 * particle store never change identity across its lifetime, unlike `ids()`, which some scenes -
 * e.g. an emitter-driven one - need to recompute every frame as particles spawn/die).
 *
 * Deliberately has no drag hook: [DragMessage.Move]'s target-velocity estimate is dt-sensitive
 * (`DragConstraint.updateTarget`) and needs draining at physics-step cadence, the same cadence
 * [step] already runs at - not once per rendered frame, which could be several steps behind. A
 * scene that supports dragging (see `FlagScene`) is handed the shared `DragMessageQueue`
 * directly by the runner and drains it itself from inside [step], rather than the runner
 * draining it once per frame and losing that per-step granularity.
 *
 * [handleControl] defaults to doing nothing: a scene ignoring a message type it has no use for
 * (most scenes have no colliders to remove) is the correct outcome, the same as the `{}` no-op
 * branches every hand-rolled demo `when` already had - returning a "was this handled" flag would
 * invite a caller-side branch with nothing useful to do in either case, so there isn't one.
 */
interface DemoScene {
    val dt: Double
    val store: ParticleStore

    fun ids(): List<Int>

    /** Advance physics by exactly [dt] - the runner calls this in a loop and increments `t`
     * by [dt] itself, so a scene never advances time on its own. */
    fun step(t: Double)

    fun handleControl(message: SceneControlMessage, t: Double) {}

    fun frame(t: Double): SceneFrame
}

/**
 * §10.4's generic §10.4-editing dispatch, shared by every [DemoScene] instead of each one
 * hand-rolling its own copy - that duplication is exactly what let `FlagDebugDemo` silently
 * drop every field edit for its entire lifetime, and what let `TrampolineScene` drop
 * [SceneControlMessage.SetParticleScalarField] specifically even after the force/constraint
 * half of this was first extracted (caught live: mass edits reverted every frame on the
 * trampoline scene, same symptom, different message type this time). Covers both the
 * name-addressed [EditableFields] path (`SetScalarField`/`SetVectorField`, resolved by `(kind,
 * name)` against whichever force/constraint actually implements it) and the id-addressed
 * particle mass/radius path (`SetParticleScalarField`) - the two other things every scene needs
 * as soon as it has anything selectable, so a scene calls this once from its own
 * [DemoScene.handleControl] and only falls through to its own handling for whatever this
 * returns `false` for (message types this function doesn't recognize at all, e.g.
 * `SetGroupEnabled` or collider messages, which need scene-specific state this function has no
 * business touching).
 */
fun applyEditableFieldMessage(
    message: SceneControlMessage,
    forces: List<Force>,
    constraints: List<Constraint>,
    store: ParticleStore,
    t: Double,
): Boolean {
    fun target(kind: String, name: String): EditableFields? = when (kind) {
        "force" -> forces.find { it.name == name } as? EditableFields
        "constraint" -> constraints.find { it.name == name } as? EditableFields
        else -> null
    }
    return when (message) {
        is SceneControlMessage.SetScalarField -> {
            target(message.kind, message.name)?.setField(message.field, FieldValue.Scalar(message.value))
            true
        }
        is SceneControlMessage.SetVectorField -> {
            target(message.kind, message.name)?.setField(message.field, FieldValue.Vector(message.value))
            true
        }
        is SceneControlMessage.SetParticleScalarField -> {
            if (store.contains(message.particleId)) {
                when (message.field) {
                    "mass" -> store.setMass(message.particleId, message.expr, t)
                    "radius" -> store.setRadius(message.particleId, message.expr, t)
                }
            }
            true
        }
        else -> false
    }
}

/** §10.4's emitter live-editing dispatch - the same one-function-per-scene shape as
 * [applyEditableFieldMessage], kept separate rather than folded into it because emitters aren't
 * [EditableFields]: `rate` is expression-capable (parsed server-side, like a particle's mass/
 * radius) and `capPolicy` is a two-valued enum, neither of which fits the Scalar/Vector
 * [FieldValue] shape that mechanism assumes. A scene with any named [Emitter] calls this once
 * from its own [DemoScene.handleControl], same "call once, fall through on `false`" convention. */
fun applyEmitterMessage(message: SceneControlMessage, emitters: List<Emitter>): Boolean {
    fun target(name: String): Emitter? = emitters.find { it.name == name }
    return when (message) {
        is SceneControlMessage.SetEmitterRate -> {
            target(message.name)?.setRate(message.expr)
            true
        }
        is SceneControlMessage.SetEmitterMaxAlive -> {
            target(message.name)?.setMaxAlive(message.maxAlive)
            true
        }
        is SceneControlMessage.SetEmitterCapPolicy -> {
            target(message.name)?.setCapPolicy(if (message.evictOldest) EmitterCapPolicy.EVICT_OLDEST else EmitterCapPolicy.STOP)
            true
        }
        else -> false
    }
}
