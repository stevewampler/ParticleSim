package particlesim.debug

import particlesim.render.Color
import particlesim.render.ConnectionShape
import particlesim.render.ConnectionStyle
import particlesim.render.Material

/**
 * A small shared palette of realistic [ConnectionStyle]s for the demo scenes, so the same kind
 * of thing looks the same everywhere (every rope the same hemp, every steel member the same
 * grey). Radii are in scene units - for scale, the bridge decks' particle spacing is 0.3.
 */
object ConnectionStyles {
    private val STEEL = Color(0.55, 0.57, 0.6)
    private val WOOD = Color(0.38, 0.27, 0.17)
    private val HEMP = Color(0.74, 0.6, 0.4)
    private val CONCRETE = Color(0.62, 0.6, 0.56)
    private val ALUMINIUM = Color(0.78, 0.79, 0.8)
    private val RUBBER = Color(0.08, 0.08, 0.09)

    fun steel(name: String, radius: Double, joints: Boolean = true) =
        ConnectionStyle(name, ConnectionShape.CYLINDER, radius, Material(STEEL, roughness = 0.45), joints)

    fun paintedSteel(name: String, color: Color, radius: Double, joints: Boolean = true) =
        ConnectionStyle(name, ConnectionShape.CYLINDER, radius, Material(color, roughness = 0.5), joints)

    fun wood(name: String, radius: Double, joints: Boolean = true) =
        ConnectionStyle(name, ConnectionShape.CYLINDER, radius, Material(WOOD, roughness = 0.9), joints)

    /** Ropes sag and bend at every particle, so joints default on to keep them continuous. */
    fun rope(name: String, radius: Double, joints: Boolean = true) =
        ConnectionStyle(name, ConnectionShape.CYLINDER, radius, Material(HEMP, roughness = 0.95), joints)

    fun concrete(name: String, radius: Double, joints: Boolean = true) =
        ConnectionStyle(name, ConnectionShape.CYLINDER, radius, Material(CONCRETE, roughness = 0.95), joints)

    fun aluminium(name: String, radius: Double, joints: Boolean = true) =
        ConnectionStyle(name, ConnectionShape.CYLINDER, radius, Material(ALUMINIUM, roughness = 0.3), joints)

    fun rubber(name: String, radius: Double, joints: Boolean = true) =
        ConnectionStyle(name, ConnectionShape.CYLINDER, radius, Material(RUBBER, roughness = 0.85), joints)

    /** Thin steel cable - stays, hangers - straight between its ends, so no joints by default. */
    fun cable(name: String, color: Color = STEEL, radius: Double = 0.012, joints: Boolean = false) =
        ConnectionStyle(name, ConnectionShape.CYLINDER, radius, Material(color, roughness = 0.4), joints)
}

/** Every connection in [connections] drawn with [style] - for building a scene's
 * [SceneFrame.connectionStyles] one piece of the structure at a time. */
infix fun List<Pair<Int, Int>>.styledAs(style: ConnectionStyle): Map<Pair<Int, Int>, ConnectionStyle> =
    associateWith { style }
