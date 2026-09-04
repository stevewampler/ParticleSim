package particlesim.render

import particlesim.core.Vector3
import particlesim.physics.EditableFields
import particlesim.physics.FieldValue

/**
 * §10.2's `[stretch]` "Lighting & materials": configurable light sources for the debug viewer —
 * the standard three.js set (ambient, directional, point), since §9.1/§10 already commits to
 * three.js as the sole first-class viewer. A scene supplies these via `SceneFrame.lights`; an
 * empty list (every scene built before this feature existed, and the default) means the viewer
 * keeps using its own hardcoded default lighting rather than going dark — see `viewer.html`'s
 * own comment on that fallback.
 *
 * [Positioned] (implemented by [Directional] and [Point]) carries a [Positioned.position] rather
 * than a direction/target pair, matching three.js's own API directly — a `DirectionalLight`
 * shines toward its target, which defaults to the origin, exactly the "position it at (5,10,7)
 * and look at the origin" shape the viewer's previous hardcoded sun light already used. No
 * separate "aim" concept to invent.
 *
 * **Mutable, unlike most model types in this codebase, and named like a [particlesim.physics.Force]/
 * [particlesim.physics.Constraint]**: a named light is reachable from §10.3's outliner
 * ([particlesim.render.SceneRegistry.lights]) and live-editable via [EditableFields]
 * ([particlesim.debug.BinaryFrame]'s per-frame encode reads `color`/`intensity`/`position`
 * straight off the live object, same as [particlesim.physics.Wind]'s `density`), so `color` and
 * `intensity` (and `position` on a [Positioned] light) are `var`s an edit mutates in place rather
 * than a value a scene would need to replace and re-thread through every frame. Despite being
 * mutable, `data class`-ness is kept (with `var` properties, which Kotlin allows) purely for its
 * generated `equals`/`hashCode`/`toString` — convenient for tests and logging — but that also
 * means a `Light`'s hash code changes when it's edited: never use one as a `Map` key or put it in
 * a `Set` the way `SceneRegistry.lights`'s `Map<String, Light>` values are looked up by name, not
 * by the light itself.
 */
sealed interface Light : EditableFields {
    val name: String?
    var color: Color
    var intensity: Double

    /** A light with a physical position in the scene — [Ambient] has none. */
    sealed interface Positioned : Light {
        var position: Vector3
    }

    /** `color` reuses [FieldValue.Vector] (three doubles, same shape as a position) rather than
     * inventing a new wire type for RGB — zero new machinery, and the existing generic x/y/z
     * numeric-input UI ([particlesim.debug.BinaryFrame]'s field-entry section,
     * `renderEditableFields` client-side) already renders it; the tradeoff is that the panel
     * shows a color as three boxes labeled by position semantics (x/y/z, not r/g/b) rather than a
     * dedicated color picker. */
    override fun editableFields(): Map<String, FieldValue> {
        val base = mapOf(
            "color" to FieldValue.Vector(Vector3(color.r, color.g, color.b)),
            "intensity" to FieldValue.Scalar(intensity),
        )
        return if (this is Positioned) base + ("position" to FieldValue.Vector(position)) else base
    }

    override fun setField(field: String, value: FieldValue): Boolean = when {
        field == "color" && value is FieldValue.Vector -> {
            color = Color(value.value.x, value.value.y, value.value.z)
            true
        }
        field == "intensity" && value is FieldValue.Scalar -> {
            intensity = value.value
            true
        }
        field == "position" && value is FieldValue.Vector && this is Positioned -> {
            position = value.value
            true
        }
        else -> false
    }

    data class Ambient(
        override var color: Color = Color(1.0, 1.0, 1.0),
        override var intensity: Double = 1.0,
        override val name: String? = null,
    ) : Light

    data class Directional(
        override var position: Vector3,
        override var color: Color = Color(1.0, 1.0, 1.0),
        override var intensity: Double = 1.0,
        override val name: String? = null,
    ) : Light, Positioned

    data class Point(
        override var position: Vector3,
        override var color: Color = Color(1.0, 1.0, 1.0),
        override var intensity: Double = 1.0,
        override val name: String? = null,
    ) : Light, Positioned
}
