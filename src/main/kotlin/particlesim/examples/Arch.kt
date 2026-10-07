package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.Constraint
import particlesim.physics.FixedPosition

/**
 * A static parabolic arch — [segments] particles tracing a curve from one "springing point"
 * (ground/pylon level) up to a peak [rise] above it and back down to a second springing point
 * [span] away, pinned in place with [FixedPosition] and never otherwise touched by a force,
 * the same "purely a visual/structural anchor" role [buildFlagpole] plays for a straight pole.
 *
 * **Rigid by construction, not a settled shape** - unlike [buildRope], which starts on a
 * straight line between two anchors and only reaches its catenary curve by actually sagging
 * under gravity through the integrator, every arch particle is placed directly on the target
 * parabola and pinned there immediately. This is deliberate, not a missed opportunity to reuse
 * [buildRope]: a real arch is a *compression* structure that holds its shape through geometry
 * and rigidity, the structural opposite of a cable's tension - and reusing a tension-only
 * sagging rope for it would also reintroduce exactly the initial-stretch instability risk
 * [buildTrainTrestle]'s own doc comment describes sidestepping by preferring rigid pins over a
 * second spring-coupling system.
 *
 * Curves in the local Y-Z plane (X stays 0 relative to [ShapePlacement.offset]) - a scene
 * wanting two parallel arches (either side of a deck, say) builds two instances with different
 * `placement.offset.x`, the same "give each instance its own flat placement" composition
 * [buildSuspensionBridge]'s own two main cables already use rather than this function taking an
 * X offset itself.
 */
data class ArchScenario(
    val store: ParticleStore,
    val groups: Groups,
    val constraints: List<Constraint>,
    /** Arch particle ids, one springing point to the other - `archIds.first()`/`archIds.last()`
     * sit at `baseHeight`, `archIds[archIds.size / 2]` at the peak (`baseHeight + rise`). */
    val archIds: List<Int>,
)

fun buildArch(
    span: Double,
    rise: Double,
    segments: Int = 20,
    baseHeight: Double = 0.0,
    // Null by default (not collidable, §12.1), matching ParticleStore.create's own convention -
    // same as buildFlagpole/buildRope.
    particleRadius: Double? = null,
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): ArchScenario {
    require(segments >= 2) { "segments must be at least 2, was $segments" }

    val archGroup = placement.name("arch")
    val radiusExpr = particleRadius?.let { ScalarExpr.of(it) }
    val archIds = (0..segments).map { i ->
        val u = -1.0 + 2.0 * i / segments // -1 at the near springing point, 0 at the peak, +1 at the far one
        val position = Vector3(0.0, baseHeight + rise * (1.0 - u * u), u * span / 2.0) + placement.offset
        val id = store.create(position = position, radius = radiusExpr)
        groups.add(archGroup, id)
        id
    }

    val constraints = listOf(FixedPosition.atCurrentPositions(archGroup, store, groups, name = placement.name("arch-anchor")))

    return ArchScenario(
        store = store,
        groups = groups,
        constraints = constraints,
        archIds = archIds,
    )
}
