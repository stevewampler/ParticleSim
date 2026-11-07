package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.examples.ShapePlacement
import particlesim.examples.TACOMA_1950_DECK_HEIGHT_ABOVE_WATER
import particlesim.examples.TACOMA_DECK_HEIGHT_ABOVE_WATER
import particlesim.examples.TACOMA_DT
import particlesim.examples.TACOMA_FAILURE_WIND
import particlesim.examples.TacomaSharedForces
import particlesim.expr.ExpressionParser
import particlesim.physics.Force
import particlesim.render.CameraFunction
import particlesim.render.CameraPose

/**
 * The 1940 bridge (breakable, as `tacomaNarrowsBreakable`) and its 1950 replacement (as
 * `tacomaNarrows1950Stress`) side by side, in **one** wind, under **one** gravity and with **one**
 * flutter force ([TacomaSharedForces], owned here by a [TacomaSharedForcesComponent]): editing
 * `wind`, `gravity` or `flutter` in the outliner changes it for both bridges. Each starts with
 * the same small one-node twist; every cable, hanger/suspender and girder/truss chord is colored
 * by how close it is to its breaking limit. The 1940 bridge's members really break; the 1950
 * bridge's limits are only shown.
 *
 * The layout is a comparison, not history - the two bridges stood on the same piers, ten years
 * apart. The 1950 bridge is upwind (+x is downwind) and [GAP] away, the 1940 deck
 * [TACOMA_DECK_HEIGHT_ABOVE_WATER] and the 1950 roadway [TACOMA_1950_DECK_HEIGHT_ABOVE_WATER]
 * above the same water. The wind blows through both alike: neither shelters the other.
 */
fun tacomaComparisonScene(windVelocity: String = "[$TACOMA_FAILURE_WIND, 0.0, 0.0]"): DemoScene {
    val store = ParticleStore()
    val groups = Groups()
    val shared = TacomaSharedForces(windVelocity)
    val environment = TacomaSharedForcesComponent(shared, store)
    val old = Tacoma1940Bridge(
        store, groups, ShapePlacement(Vector3(GAP / 2, 0.0, 0.0), "1940"), breakable = true, shared = shared,
    )
    val new = Tacoma1950Bridge(
        store, groups,
        ShapePlacement(Vector3(-GAP / 2, TACOMA_1950_DECK_HEIGHT_ABOVE_WATER - TACOMA_DECK_HEIGHT_ABOVE_WATER, 0.0), "1950"),
        showStress = true, shared = shared,
    )
    // Above and downwind of both decks, inside the main span (clear of the towers), looking
    // east along the west half, where the one-node twist is largest.
    val camera = CameraFunction { _, _ -> CameraPose(position = Vector3(11.0, 6.0, -38.0), lookAt = Vector3(0.0, -0.5, -18.0)) }
    return ComponentScene(store, groups, listOf(environment, old, new), TACOMA_DT, camera, tacomaLights())
}

/** Centre to centre, 50 m: clear of both bridges' towers (1940 legs at ±0.59, 1950 at ±0.91). */
private const val GAP = 5.0

/**
 * The [TacomaSharedForces] as a [SceneComponent]: contributes the one wind, gravity and flutter
 * force, draws the flutter's arrows on every bridge, and calms the wind while the scene settles.
 */
class TacomaSharedForcesComponent(private val shared: TacomaSharedForces, private val store: ParticleStore) : SceneComponent {
    override val forces: List<Force> = shared.forces
    override val constraints = emptyList<particlesim.physics.Constraint>()
    override val surfaces = emptyList<particlesim.surface.Surface>()
    override val ids = emptyList<Int>()
    override val visibleIds = emptySet<Int>()

    override fun beginSettle() = shared.wind.setVelocity(VectorExpr.of(Vector3.ZERO))
    override fun endSettle() = shared.wind.setVelocity(ExpressionParser.parseVector(shared.windVelocity))

    override fun frame(t: Double) = ComponentFrame(
        arrowGroups = tacomaFlutterArrows(shared.flutter, store, t),
        shownByDefaultArrows = setOfNotNull(shared.flutter.name),
    )
}
