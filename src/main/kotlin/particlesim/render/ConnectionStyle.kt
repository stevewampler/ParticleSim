package particlesim.render

/** How a particle-to-particle connection is drawn (§10.2). */
enum class ConnectionShape {
    /** A 1-pixel line - WebGL ignores line width, so a line has no thickness and isn't shaded by
     * the scene's lights. Cheapest, and right for debug views of dense spring lattices. */
    LINE,

    /** A lit solid cylinder of [ConnectionStyle.radius] between the two particles - what makes a
     * pole, cable, rope or girder read as a real object that the scene's lights shade. */
    CYLINDER,
}

/**
 * The look of a set of particle-to-particle connections (§10.2) - a pole's segments, a bridge's
 * stays, a rope - declared by a scene per connection ([particlesim.debug.SceneFrame.connectionStyles])
 * and rendered by the viewer. Purely visual: it never touches the physics, and a connection with
 * no style draws exactly as before (a plain line in its own resolved color).
 *
 * [name] is how the viewer lists the style (its "connections" outliner section) so a person can
 * restyle it live, so connections sharing a [name] should share the whole style. It's a separate
 * key from a connection's force name (`connectionNames`), which ties it back to a named
 * Spring/MeshSprings - a pole or a tower leg has a style but no force.
 *
 * A [ColorBy] gradient (e.g. break proximity) still wins over [material]'s color, per
 * connection, so a styled breakable spring still visibly reddens as it nears its limit.
 *
 * [joints] adds a sphere of the same radius at every connection endpoint, so a chain of
 * cylinders that bends (a sagging rope or cable) reads as one continuous rope instead of a row
 * of notched segments.
 */
data class ConnectionStyle(
    val name: String,
    val shape: ConnectionShape = ConnectionShape.CYLINDER,
    val radius: Double = 0.02,
    val material: Material = Material(),
    val joints: Boolean = false,
) {
    init {
        require(name.isNotEmpty()) { "a connection style needs a name" }
        require(radius > 0.0) { "radius must be positive, was $radius" }
    }
}
