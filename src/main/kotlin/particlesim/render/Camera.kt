package particlesim.render

import particlesim.core.Vector3

/**
 * A camera pose: eye position, look-at target, and up vector (§10.1) — plain numbers, not a
 * matrix, since this is what gets serialized into the per-frame state stream (§9.1) for a
 * viewer (or a future recording, §9.2) to consume directly, without needing to know how the
 * pose was computed.
 */
data class CameraPose(
    val position: Vector3,
    val lookAt: Vector3,
    val up: Vector3 = Vector3(0.0, 1.0, 0.0), // +Y up (§11)
)

/**
 * A scripted camera (§10.1's "automated" mode): a pure function of simulation time and current
 * scene state, evaluated by the **engine**, not the viewer — "follow a particle's position,
 * orbit around a group's centroid... align to a surface's normal" are all just [SceneQuery]
 * calls inside this function, not a separate mechanism. Evaluating server-side (rather than
 * shipping the function to the client) is what makes a recorded run's camera path replay
 * exactly (§9.2) regardless of which front-end authored it (§4.4), and keeps the viewer itself
 * expression/lambda-evaluator-free.
 *
 * A `fun interface` (like [particlesim.core.VectorExpr.OfTime]'s `(Double) -> Vector3`) rather
 * than a full DSL builder — the requirements doc's own example nests `position { t -> ... }`
 * and `lookAt(target = position(...))` as separate sub-builders, but a single function taking
 * both `t` and a [SceneQuery] captures the same capability (time-varying, scene-aware pose)
 * with less machinery, the same "simplify the sugar, keep the semantics" call already made for
 * the Kotlin DSL's `mass(...)`/`mass { t -> ... }` function-call style back in Phase 1. YAML
 * camera expressions (§4.1's scene-query grammar extension) are a deferred second pass, same
 * status as every other post-Phase-7 YAML coverage gap (colliders, emitters, ...) — this phase
 * only wires up the Kotlin-DSL/native-lambda path.
 */
fun interface CameraFunction {
    fun evaluate(t: Double, scene: SceneQuery): CameraPose

    companion object {
        /** A camera that never moves — the common case for a scenario that doesn't need a
         * scripted path, without forcing every demo to write a trivial lambda. */
        fun fixed(pose: CameraPose): CameraFunction = CameraFunction { _, _ -> pose }
    }
}
