package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.Constraint
import particlesim.physics.Damper
import particlesim.physics.FixedPosition
import particlesim.physics.Force
import particlesim.physics.Spring
import particlesim.physics.UniformGravity

/**
 * §4.5's fifth shape-library entry, added for the flag's pole/rope worked example
 * (requirements.md §7.3, new requirement): a flexible chain of particles between two fixed
 * anchor points, both pinned via [FixedPosition] — unlike [buildFlagpole]'s pole, which is
 * entirely static, every particle *between* the two anchors here is free and connected to its
 * neighbors by [Spring]/[Damper], the same individual-per-edge pattern [buildTire] already
 * uses for a similarly small edge count (a rope's handful of segments doesn't need
 * [particlesim.physics.MeshSprings]' one-`Force`-for-thousands-of-edges treatment).
 *
 * **Slack, not rigid**: `compressionStiffness = 0.0` by default — a real rope resists being
 * stretched but offers essentially no resistance to going slack, requirements.md §5.4's own
 * stated reasoning for asymmetric spring stiffness ("a slack rope has nothing to meaningfully
 * break under compression in the first place"). With both ends pinned at different heights and
 * near-zero compression resistance, the rope sags into a natural catenary-like curve under
 * gravity rather than holding a rigid straight line between the anchors — the point of using a
 * rope instead of another static [buildFlagpole]-style rigid segment.
 *
 * **`topAnchor`/`bottomAnchor` are relative to [ShapePlacement.offset]**, the same convention
 * every other shape in this package uses for its own local geometry — not independent absolute
 * positions — so a rope composed alongside a [buildFlagpole] sharing the same placement can
 * express its anchors in the pole's own coordinate frame (e.g. `topAnchor = Vector3(0.0,
 * poleHeight, 0.0)`) without needing to query the pole's particles back out of the store.
 *
 * Stiffness/mass chosen against the same §13.1 stability budget [buildFlag]'s structural
 * springs already use (`2*sqrt(m/k) ≈ 0.02`, ~20x margin over `FLAG_DT = 1e-3`) — not an
 * independent guess — since this shape's first real consumer shares that scene's dt.
 */
data class RopeScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val constraints: List<Constraint>,
    /** Rope particle ids, **top anchor to bottom anchor** — `ropeIds.first()` is pinned at
     * `topAnchor`, `ropeIds.last()` is pinned at `bottomAnchor`, and `ropeIds.zipWithNext()` is
     * the rope's own segment list for rendering as a line, the same convention
     * [FlagpoleScenario.poleIds] documents as "base to top". This ordering is what makes "the
     * rope's top portion" (e.g. for attaching a flag's pole edge, requirements.md §7.3) an
     * unambiguous prefix of this list — `ropeIds.take(n)` — rather than something only clear
     * from a diagram. */
    val ropeIds: List<Int>,
)

fun buildRope(
    topAnchor: Vector3,
    bottomAnchor: Vector3,
    segments: Int = 10,
    massPerParticle: Double = 0.005,
    extensionStiffness: Double = 200.0,
    // Slack by default (see class doc) - a rope's compression resistance is near-zero, unlike
    // a rigid rod, so it droops between its two anchors instead of holding a straight line.
    compressionStiffness: Double = 0.0,
    damping: Double = 1.0,
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): RopeScenario {
    require(segments >= 1) { "segments must be at least 1, was $segments" }

    val ropeGroup = placement.name("rope")
    val anchorsGroup = placement.name("rope-anchors")
    val ropeIds = (0..segments).map { i ->
        val t = i.toDouble() / segments
        val position = topAnchor + (bottomAnchor - topAnchor) * t + placement.offset
        val id = store.create(position = position, mass = ScalarExpr.of(massPerParticle))
        groups.add(ropeGroup, id)
        id
    }
    groups.add(anchorsGroup, ropeIds.first())
    groups.add(anchorsGroup, ropeIds.last())

    val segmentRestLength = (bottomAnchor - topAnchor).length() / segments
    val springs = ropeIds.zipWithNext().mapIndexed { i, (a, b) ->
        Spring(
            a, b, restLength = segmentRestLength, stiffness = extensionStiffness, compressionStiffness = compressionStiffness,
            name = placement.name("segment-spring-$i"),
        )
    }
    val dampers = ropeIds.zipWithNext().mapIndexed { i, (a, b) ->
        Damper(a, b, damping = damping, name = placement.name("segment-damper-$i"))
    }
    val gravity = UniformGravity(ropeGroup, Vector3(0.0, -9.8, 0.0), name = placement.name("gravity"))

    val constraints = listOf(FixedPosition.atCurrentPositions(anchorsGroup, store, groups, name = placement.name("rope-anchor")))

    return RopeScenario(
        store = store,
        groups = groups,
        forces = listOf(gravity) + springs + dampers,
        constraints = constraints,
        ropeIds = ropeIds,
    )
}
