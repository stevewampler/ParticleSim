package particlesim.render

import particlesim.core.Vector3

/**
 * §10.2's `[stretch]` "Lighting & materials": configurable light sources for the debug viewer —
 * the standard three.js set (ambient, directional, point), since §9.1/§10 already commits to
 * three.js as the sole first-class viewer. A scene supplies these via `SceneFrame.lights`; an
 * empty list (every scene built before this feature existed, and the default) means the viewer
 * keeps using its own hardcoded default lighting rather than going dark — see `viewer.html`'s
 * own comment on that fallback.
 *
 * [Directional] and [Point] both carry a [position] rather than a direction/target pair,
 * matching three.js's own API directly — a `DirectionalLight` shines toward its target, which
 * defaults to the origin, exactly the "position it at (5,10,7) and look at the origin" shape the
 * viewer's previous hardcoded sun light already used. No separate "aim" concept to invent.
 */
sealed interface Light {
    val color: Color
    val intensity: Double

    data class Ambient(override val color: Color = Color(1.0, 1.0, 1.0), override val intensity: Double = 1.0) : Light

    data class Directional(
        val position: Vector3,
        override val color: Color = Color(1.0, 1.0, 1.0),
        override val intensity: Double = 1.0,
    ) : Light

    data class Point(
        val position: Vector3,
        override val color: Color = Color(1.0, 1.0, 1.0),
        override val intensity: Double = 1.0,
    ) : Light
}
