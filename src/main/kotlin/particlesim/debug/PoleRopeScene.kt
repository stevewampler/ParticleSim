package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.ShapePlacement
import particlesim.examples.buildFlagpole
import particlesim.examples.buildRope
import particlesim.physics.Integrator
import particlesim.render.SceneRegistry

/**
 * §9.6 scene-library entry for [buildRope] (requirements.md §7.3's pole/rope worked example,
 * new requirement) — pole and rope only, no flag yet: the flag's pole-edge attachment moving
 * from a direct [particlesim.physics.FixedPosition] pin to this rope is a separate, following
 * step (see TODO.md), kept out of this scene so the rope's own shape/behavior — sagging under
 * gravity between its two anchors, settling without blowing up — can be verified in isolation
 * first, the same staged approach [RopeTest] already takes at the unit level.
 *
 * The rope's top anchor sits at the pole's own top (centerline), its bottom anchor partway up
 * the pole but offset to the side (not at the base, and not directly below the top anchor) —
 * a halyard-style loop, per the requirement. The sideways offset is load-bearing, not
 * decorative: see the comment on [rope] below for why two vertically-stacked anchors would be
 * a degenerate case for a slack chain to sag from.
 */
class PoleRopeScene : DemoScene {
    private val poleHeight = 3.5

    override val store = ParticleStore()
    private val groups = Groups()
    private val flagpole = buildFlagpole(
        height = poleHeight, segments = 6, store = store, groups = groups, placement = ShapePlacement(instanceName = "pole"),
    )

    // Top anchor sits right at the pole's own top (a pulley point on the centerline); the
    // bottom anchor is offset to the side (a cleat mounted on the pole's surface, partway up) -
    // deliberately *not* directly below the top anchor. Two anchors on the same vertical line
    // would make the straight-line rest configuration exactly parallel to gravity, with no
    // lateral direction to sag into - a degenerate, physically-ambiguous case caught by
    // comparing this scene's live behavior (settled with zero average speed, bunched at its
    // initial layout) against RopeTest's own diagonal-anchor setup, which sags as expected.
    private val rope = buildRope(
        topAnchor = Vector3(0.0, poleHeight, 0.0),
        bottomAnchor = Vector3(0.25, poleHeight * 0.5, 0.0),
        segments = 10,
        store = store,
        groups = groups,
        placement = ShapePlacement(instanceName = "rope"),
    )

    private val allIds = flagpole.poleIds + rope.ropeIds
    private val allForces = rope.forces
    private val allConstraints = flagpole.constraints + rope.constraints
    private val registry = SceneRegistry.build(forces = allForces, constraints = allConstraints, groups = groups)
    private val integrator = Integrator()

    override val dt = 1e-3 // matches buildRope's own stability-budget derivation (see its doc comment)

    override fun ids(): List<Int> = allIds

    override fun handleControl(message: SceneControlMessage, t: Double) {
        applyEditableFieldMessage(message, allForces, allConstraints, store, t)
    }

    override fun step(t: Double) {
        integrator.step(store, groups, allForces, allConstraints, t, dt)
    }

    override fun frame(t: Double): SceneFrame = SceneFrame(
        connections = flagpole.poleIds.zipWithNext() + rope.ropeIds.zipWithNext(),
        registry = registry,
    )
}
