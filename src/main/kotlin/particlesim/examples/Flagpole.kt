package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.Constraint
import particlesim.physics.FixedPosition

/**
 * §4.5's fourth shape-library entry: a static vertical pole — [segments] particles from the
 * ground up to [height], pinned in place with [FixedPosition] and never otherwise touched by
 * a force. Existing purely as a visual/structural anchor (there's nothing for a flag's pole
 * edge to dynamically attach *to* here — it's already self-fixing via
 * `FixedPosition.atCurrentPositions`, §7.3) rather than a physically-simulated object; placing
 * a `buildFlag` instance with an offset near the top of a `buildFlagpole` instance is how a
 * scene author lines the two up, the same "compose by placement" pattern §4.5 already
 * establishes for every other shape pair.
 *
 * Grows *upward* from [ShapePlacement.offset] (offset is the pole's base, not its top) —
 * chosen so a scene author reasons about a flagpole the way they'd plant a real one: pick a
 * spot on the ground, it goes up from there.
 */
data class FlagpoleScenario(
    val store: ParticleStore,
    val groups: Groups,
    val constraints: List<Constraint>,
    /** Pole particle ids, base to top — `poleIds.zipWithNext()` is the pole's own segment
     * list, for rendering it as a vertical line. */
    val poleIds: List<Int>,
)

fun buildFlagpole(
    height: Double = 3.0,
    segments: Int = 6,
    // Null by default (not collidable, §12.1), matching ParticleStore.create's own convention -
    // a scene that wants the pole to participate in particle-vs-surface collision (§12.4) passes
    // an explicit value.
    particleRadius: Double? = null,
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): FlagpoleScenario {
    require(segments >= 1) { "segments must be at least 1, was $segments" }

    val poleGroup = placement.name("pole")
    val radiusExpr = particleRadius?.let { ScalarExpr.of(it) }
    val poleIds = (0..segments).map { i ->
        val position = Vector3(0.0, height * i / segments, 0.0) + placement.offset
        val id = store.create(position = position, radius = radiusExpr)
        groups.add(poleGroup, id)
        id
    }

    val constraints = listOf(FixedPosition.atCurrentPositions(poleGroup, store, groups, name = placement.name("pole-anchor")))

    return FlagpoleScenario(
        store = store,
        groups = groups,
        constraints = constraints,
        poleIds = poleIds,
    )
}
