package particlesim.dsl

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3

/**
 * Entry point for the Kotlin DSL front-end (§4.3). Builds a [ParticleStore] + [Groups]
 * pair; forces/constraints/surfaces (§4.3 example) are added by later phases as this
 * builder grows.
 */
fun simulation(block: SimulationBuilder.() -> Unit): SimulationBuilder =
    SimulationBuilder().apply(block)

class SimulationBuilder {
    val store = ParticleStore()
    val groups = Groups()
    val particles = ParticlesBuilder(store, groups)
}

/**
 * Result of a particle-declaring DSL call — behaves like the list of created ids, with
 * [group] to tag every particle at once (matching the `.group("name")` chaining shown
 * in §4.3's flag example). This is a snapshot of the ids *this call* created, not a live
 * view of the named group: once emitters (Phase 6) can add further particles to that
 * group at runtime, `groups.membersOf(name)` on the [Groups] registry is the current
 * membership — this object won't grow to reflect it.
 */
class ParticleGroup(ids: List<Int>, private val groups: Groups) : List<Int> by ids {
    fun group(name: String): ParticleGroup {
        forEach { groups.add(name, it) }
        return this
    }
}

class ParticlesBuilder(private val store: ParticleStore, private val groups: Groups) {

    /** Declares a single particle, returning its id wrapped for `.group(...)` chaining. */
    fun single(block: ParticleBuilder.() -> Unit = {}): ParticleGroup {
        val b = ParticleBuilder().apply(block)
        return ParticleGroup(listOf(create(b)), groups)
    }

    /**
     * Declares a `rows` x `cols` grid of particles (§4.2 bulk generation), laid out in the
     * XY plane at `spacing` apart by default; `block` runs per particle with its (row, col)
     * indices and can override any field, including `position`.
     */
    fun grid(
        rows: Int,
        cols: Int,
        spacing: Double = 1.0,
        block: ParticleBuilder.(row: Int, col: Int) -> Unit = { _, _ -> },
    ): ParticleGroup {
        val ids = ArrayList<Int>(rows * cols)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val b = ParticleBuilder()
                b.position = Vector3(col * spacing, row * spacing, 0.0)
                b.block(row, col)
                ids += create(b)
            }
        }
        return ParticleGroup(ids, groups)
    }

    private fun create(b: ParticleBuilder): Int = store.create(
        position = b.position,
        velocity = b.velocity,
        mass = b.massExpr,
        radius = b.radiusExpr,
        lifetime = b.lifetimeExpr,
    )
}

/**
 * Per-particle field builder. `mass`/`radius`/`lifetime` each accept either a literal or a
 * `(t: Double) -> Double` lambda (§3, §4.3) — function-call style (`mass(2.5)`,
 * `mass { t -> ... }`) rather than `=` assignment, since Kotlin has no implicit conversion
 * from a literal to a wrapper type; revisit if assignment syntax turns out to matter once
 * there's a real scene to author.
 */
class ParticleBuilder {
    var position: Vector3 = Vector3.ZERO
    var velocity: Vector3 = Vector3.ZERO

    internal var massExpr: ScalarExpr = ScalarExpr.of(1.0)
    internal var radiusExpr: ScalarExpr? = null
    internal var lifetimeExpr: ScalarExpr? = null

    fun mass(value: Double) { massExpr = ScalarExpr.of(value) }
    fun mass(fn: (Double) -> Double) { massExpr = ScalarExpr.of(fn) }

    fun radius(value: Double) { radiusExpr = ScalarExpr.of(value) }
    fun radius(fn: (Double) -> Double) { radiusExpr = ScalarExpr.of(fn) }

    fun lifetime(value: Double) { lifetimeExpr = ScalarExpr.of(value) }
    fun lifetime(fn: (Double) -> Double) { lifetimeExpr = ScalarExpr.of(fn) }
}
