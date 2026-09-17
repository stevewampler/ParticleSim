package particlesim.physics

import particlesim.collision.SpatialGrid
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.math.PI
import kotlin.math.pow

/**
 * Smoothed Particle Hydrodynamics (SPH), Muller et al. 2003's real-time formulation: each
 * particle's local density is estimated from nearby particles via a smoothing kernel, and that
 * density (compared against [restDensity]) drives a pressure force that pushes overcrowded
 * regions apart, plus a viscosity force that damps relative velocity between neighbors.
 *
 * Reuses [particlesim.collision.SpatialGrid] for neighbor search, per requirements.md's
 * "shared spatial partitioning" intent - it's a collision-broad-phase type by package, but its
 * only real dependency is [Vector3], and SPH's smoothing radius is exactly the kind of "real,
 * physical cutoff" that class's own doc comment says it's valid for (unlike N-body gravity's
 * unbounded pairwise sum, which it explicitly isn't). Importing across `physics`/`collision`
 * this way doesn't introduce a new circular dependency: `collision` already imports
 * `physics.Constraint` for collider pinning, so the two packages already reference each other
 * within this one Gradle module.
 *
 * Unlike most [Force]s, this one has two sequential passes over the same neighborhoods - every
 * particle's density must be known before any particle's pressure force can be computed - and
 * [Force.accumulate] has no barrier between chunks to enforce that ordering. Rather than trying
 * to make this stride across chunks, [accumulate] does the whole two-pass computation serially
 * and only contributes on `chunkIndex == 0`, the same mechanism `Spring`/`Damper` use for having
 * only one atomic unit of work (see [Force]'s own doc comment) - here the "atomic unit" is the
 * whole fluid, not a single connection, but skipping every other chunk is identical.
 *
 * Pressure is clamped to non-negative (`max(0, gasConstant * (rho - restDensity))`): a sparse
 * neighborhood - a fluid's free surface, where there simply isn't a neighbor on one side - would
 * otherwise report `rho < restDensity` and go negative there, pulling surface particles inward.
 * That's genuine SPH surface tension, but for a real-time simulation it also tends to collapse a
 * free surface into clumps rather than settle into a fluid, so this trades that tension away for
 * stability - a standard simplification for this style of simulation, not an oversight.
 */
class SphFluid(
    private val group: String,
    val restDensity: Double,
    private val gasConstant: Double,
    private val viscosity: Double,
    private val smoothingRadius: Double,
    override val name: String? = null,
) : Force {
    private val h = smoothingRadius
    private val h2 = h * h
    private val poly6Coeff = poly6Coefficient(h)
    private val spikyGradCoeff = -45.0 / (PI * h.pow(6))
    private val viscLapCoeff = 45.0 / (PI * h.pow(6))

    override fun accumulate(
        store: ParticleStore,
        groups: Groups,
        t: Double,
        chunk: ChunkAccumulator,
        chunkIndex: Int,
        chunkCount: Int,
    ) {
        if (chunkIndex != 0) return
        if (!groups.isEnabled(group)) return
        val members = groups.membersOf(group).toList()
        if (members.isEmpty()) return

        // Broad-phase only, rebuilt fresh from this step's positions - see SpatialGrid's own
        // doc comment on why that's correct (not just cheap) for a fixed physical cutoff.
        val grid = SpatialGrid(h)
        for (id in members) grid.insert(id, store.position(id))

        val density = HashMap<Int, Double>(members.size)
        val neighborsOf = HashMap<Int, List<Int>>(members.size)
        for (id in members) {
            val pos = store.position(id)
            var rho = 0.0
            val actual = ArrayList<Int>()
            for (j in grid.neighbors(pos)) {
                val r2 = (store.position(j) - pos).lengthSquared()
                if (r2 < h2) {
                    actual.add(j)
                    rho += store.mass(j) * poly6Coeff * (h2 - r2).pow(3)
                }
            }
            density[id] = rho
            neighborsOf[id] = actual
        }

        for (id in members) {
            val posI = store.position(id)
            val velI = store.velocity(id)
            val pressureI = (gasConstant * (density.getValue(id) - restDensity)).coerceAtLeast(0.0)
            var force = Vector3.ZERO
            for (j in neighborsOf.getValue(id)) {
                if (j == id) continue
                val posJ = store.position(j)
                val diff = posI - posJ
                val r = diff.length()
                if (r < 1e-9 || r >= h) continue
                val rhoJ = density.getValue(j)
                val pressureJ = (gasConstant * (rhoJ - restDensity)).coerceAtLeast(0.0)
                val dir = diff * (1.0 / r)

                val spiky = spikyGradCoeff * (h - r) * (h - r)
                force += dir * (-store.mass(j) * (pressureI + pressureJ) / (2.0 * rhoJ) * spiky)

                val viscLap = viscLapCoeff * (h - r)
                force += (store.velocity(j) - velI) * (viscosity * store.mass(j) / rhoJ * viscLap)
            }
            chunk.add(store.slotOf(id), force)
        }
    }

    companion object {
        private fun poly6Coefficient(h: Double) = 315.0 / (64.0 * PI * h.pow(9))

        /**
         * The density this kernel reports for a particle at [at], given a fixed snapshot of
         * [positions] each carrying [particleMass] - a brute-force, one-off measurement (no
         * spatial grid; this runs once at scene-construction time, not every step) meant to
         * calibrate [restDensity] from the packing a scene actually uses, rather than guessing a
         * real-world figure like water's 1000 kg/m^3 that has nothing to do with this
         * simulation's particle spacing, mass, or smoothing radius - see `buildFluid`'s use of
         * this to set its own `restDensity`.
         */
        fun measureDensity(positions: List<Vector3>, at: Vector3, particleMass: Double, smoothingRadius: Double): Double {
            val h2 = smoothingRadius * smoothingRadius
            val coeff = poly6Coefficient(smoothingRadius)
            var rho = 0.0
            for (p in positions) {
                val r2 = (p - at).lengthSquared()
                if (r2 < h2) rho += particleMass * coeff * (h2 - r2).pow(3)
            }
            return rho
        }
    }
}
