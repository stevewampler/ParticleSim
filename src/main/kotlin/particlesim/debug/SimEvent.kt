package particlesim.debug

import particlesim.core.Vector3

/**
 * §9.1's discrete-event channel: "each frame of state carries continuous data... plus discrete
 * events (e.g. force breaks, §5.4, and particle spawn/destroy, §14.2), so viewers and recordings
 * have one channel to consume rather than several." This pass covers the *interactive* half of
 * that (threaded through [particlesim.debug.BinaryFrame]/[DebugRenderer.broadcast]) — the batch
 * recording format (§9.2) has its own separately-tracked gap (`todo/TODO.md`) and may end up
 * wanting these same three variants, at which point this type is a reasonable thing to share
 * rather than duplicate.
 *
 * Reused directly for both encoding and decoding (unlike, say, [particlesim.collision.Collider]/
 * `DecodedCollider`) — a `SimEvent` carries no engine behavior, only the exact data a viewer or
 * recording needs, so there's nothing a decode-only twin would need to strip out.
 */
sealed class SimEvent {
    /** A [particlesim.physics.Breakable] connection whose threshold was exceeded this step
     * (§5.4) — not yet emitted by any live demo (see `todo/TODO.md`: no interactive demo has a
     * finite break threshold yet, and the caller-side "remove it from the active force list"
     * contract [particlesim.physics.Integrator.step]'s own doc comment describes has never been
     * discharged by a real loop either). [name] is `""` for an unnamed force — same
     * not-individually-traceable convention every other wire-format name already uses. */
    data class ForceBreak(val name: String) : SimEvent()

    /** A particle removed this step, however it happened — [particlesim.lifecycle.DestructionSystem]
     * (lifetime, a destroy condition, a collision-destroy rule, or explicit delete via the
     * viewer, §14.2) or an [particlesim.lifecycle.Emitter]'s [particlesim.lifecycle.EmitterCapPolicy.EVICT_OLDEST]. */
    data class ParticleDestroyed(val particleId: Int) : SimEvent()

    /** A particle created this step by an [particlesim.lifecycle.Emitter] (§14.1). */
    data class ParticleSpawned(val particleId: Int) : SimEvent()

    /** Something that makes a noise (§10.2): a [kind] of sound at [position], [loudness] times
     * its kind's usual level, at sim time [time]. The viewer synthesizes it, delays it by its
     * distance from the camera over the speed of sound, and pans it by where it is. Carries
     * no engine behaviour - sound is viewer-side only. */
    data class Sound(val kind: SoundKind, val position: Vector3, val loudness: Double, val time: Double) : SimEvent()
}

/** The sounds a [SimEvent.Sound] can be. The wire carries the ordinal, so add new kinds at the
 * end. */
enum class SoundKind {
    /** A firework shell leaving its mortar: a thump and a rising whoosh. */
    LAUNCH,
    /** A burst: a sharp crack and a deep, rumbling boom. */
    BOOM,
    /** A crackling star popping. */
    CRACKLE,
}
