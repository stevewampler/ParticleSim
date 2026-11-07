package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.FixedPosition
import particlesim.physics.Force
import particlesim.render.Color
import particlesim.render.Material
import particlesim.render.SurfaceRenderer
import particlesim.surface.Surface
import particlesim.surface.Triangle

/**
 * The water of the Narrows under the Tacoma bridges, drawn as a flat sheet at height [y] - a
 * reusable [SceneComponent], one per scene however many bridges stand over it. It's only the
 * picture: a breakable bridge brings its own collision plane at the same height for falling
 * pieces to land on ([particlesim.examples.TacomaNarrowsScenario.collisions]).
 *
 * The sheet is two triangles on four pinned corner particles (in group [name], hidden), spanning
 * [halfWidth] either side of x = 0 and [halfLength] either side of z = 0 - well past both
 * anchorages (±76).
 */
class TacomaWater(
    store: ParticleStore,
    groups: Groups,
    y: Double,
    halfWidth: Double = 60.0,
    halfLength: Double = 110.0,
    name: String = "water",
) : SceneComponent {
    private val corners = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (sx, sz) ->
        store.create(position = Vector3(sx * halfWidth, y, sz * halfLength), mass = ScalarExpr.of(1.0)).also { groups.add(name, it) }
    }
    val surface = Surface(listOf(Triangle(corners[0], corners[1], corners[2]), Triangle(corners[0], corners[2], corners[3])), name = name)

    override val forces = emptyList<Force>()
    override val constraints = listOf(FixedPosition.atCurrentPositions(name, store, groups, name = name))
    override val surfaces = listOf(surface)
    override val ids = corners
    override val visibleIds = emptySet<Int>()

    private val mesh = SurfaceRenderer(surface, wireframe = false, material = Material(color = WATER_BLUE, roughness = 0.35))

    override fun frame(t: Double) = ComponentFrame(meshes = listOf(mesh))

    private companion object {
        /** Puget Sound's deep blue-green. */
        val WATER_BLUE = Color(0.11, 0.25, 0.32)
    }
}
