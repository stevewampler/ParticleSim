package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.Constraint
import particlesim.physics.FixedPosition

/**
 * A parabolic arch — [segments] particles tracing a curve from one "springing point"
 * (ground/pylon level) up to a peak [rise] above it and back down to a second springing point
 * [span] away. Every particle is placed directly on the target parabola at construction
 * (unlike [buildRope], which starts on a straight line between two anchors and only reaches its
 * catenary curve by actually sagging under gravity through the integrator) - a real arch is a
 * *compression* structure that holds its shape through geometry, the structural opposite of a
 * cable's tension, so starting it pre-shaped rather than letting it sag into place is the more
 * physically honest choice either way this function gets used.
 *
 * [pinEnds] controls how much of that shape is actually held there:
 * - `true` (the default): every particle is [FixedPosition]-pinned, never otherwise touched by
 *   a force - purely a visual/structural anchor, the same role [buildFlagpole] plays for a
 *   straight pole. No [massPerParticle]/stiffness of its own matters here since nothing ever
 *   moves.
 * - `false`: only the two springing points (`archIds.first()`/`archIds.last()`) are pinned: the
 *   rest are free, dynamic particles with [massPerParticle] mass and no spring connecting them
 *   to each other yet - this function only places and (partially) anchors them, same "purely
 *   geometric" scope either way. A caller wanting an actually self-supporting dynamic arch (not
 *   just a cloud of free particles sharing a start shape) needs to add its own structural
 *   springs/gravity along this chord - [buildArchBridge] does exactly that, connecting each
 *   side's upper/lower chord pair together with [particlesim.surface.Grid]'s own edge-topology
 *   helpers (a 2-row grid, upper chord as row 0 and lower as row 1, falls directly out of
 *   `Grid.structuralEdges`/`shearEdges` without this function needing to know anything about a
 *   second chord at all).
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
    pinEnds: Boolean = true,
    massPerParticle: Double = 1.0,
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
        val id = store.create(position = position, mass = ScalarExpr.of(massPerParticle), radius = radiusExpr)
        groups.add(archGroup, id)
        id
    }

    val pinnedGroup = if (pinEnds) {
        archGroup
    } else {
        val endsGroup = placement.name("arch-ends")
        groups.add(endsGroup, archIds.first())
        groups.add(endsGroup, archIds.last())
        endsGroup
    }
    val constraints = listOf(FixedPosition.atCurrentPositions(pinnedGroup, store, groups, name = placement.name("arch-anchor")))

    return ArchScenario(
        store = store,
        groups = groups,
        constraints = constraints,
        archIds = archIds,
    )
}
