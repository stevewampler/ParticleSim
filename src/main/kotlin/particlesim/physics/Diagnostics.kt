package particlesim.physics

import particlesim.core.ParticleStore
import particlesim.core.Vector3

/**
 * Energy/momentum diagnostics (§11, §13.5) — core, not stretch: cheap to compute, and the
 * cheapest available correctness check (a spring-only system without damping should
 * conserve energy; if it doesn't, something's wrong). Kinetic energy and momentum are
 * general; potential energy is force-specific (see [Spring.potentialEnergy]) since not
 * every force has a well-defined one (drag is dissipative by design).
 */
object Diagnostics {
    fun kineticEnergy(store: ParticleStore, ids: List<Int> = store.liveIds()): Double =
        ids.sumOf { id -> 0.5 * store.mass(id) * store.velocity(id).lengthSquared() }

    fun momentum(store: ParticleStore, ids: List<Int> = store.liveIds()): Vector3 =
        ids.fold(Vector3.ZERO) { acc, id -> acc + store.velocity(id) * store.mass(id) }
}
