package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.Force
import particlesim.physics.NBodyGravity
import kotlin.math.sqrt

/**
 * §5.2's N-body gravity worked example — a central star plus several planets in roughly
 * circular orbits. [NBodyGravity] already has its own analytic stability proof
 * ([particlesim.physics.TwoBodyOrbitTest], §15.1); this is the first scene to actually *show*
 * it rather than just assert on it. Every body — star included — is one [NBodyGravity] group,
 * so planet-planet and star-planet terms are both real, not a simplified fixed-star setup: the
 * star is simply heavy enough relative to the planets that its own drift stays negligible, the
 * same approximation that test's own doc comment already makes.
 *
 * Each planet starts on a circular orbit in the XZ plane (§11's ground plane) at its own
 * [orbitalRadii] entry, given `v = sqrt(G*M_star/r)` — exact for one planet around a fixed
 * star; with every planet's own (tiny) mutual pull layered on top, real paths drift slightly
 * from a perfect circle over many periods, which is the point of building this on real N-body
 * gravity rather than scripting an ellipse directly.
 *
 * Also §4.5's shape-library convention (alongside `buildFlag`/`buildBallBounce`/...): accepts
 * a shared `store`/`groups` and a `placement` so an orbital system can coexist with other
 * shapes in one composed scene — see `ShapePlacement`'s own doc comment.
 */
data class OrbitalScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val starId: Int,
    val planetIds: List<Int>,
)

/**
 * `dt` picked for a watchable-on-screen orbital period, not stability margin alone —
 * [TwoBodyOrbitTest][particlesim.physics.TwoBodyOrbitTest]'s own `g`/mass/radius combination
 * gives a ~0.2s period, appropriate for a 20-period analytic proof but far too fast to read as
 * an orbit on screen. This scenario's defaults instead target periods in the few-seconds-to-
 * tens-of-seconds range (innermost ~3s, outermost ~12s at the default radii), with `dt` small
 * enough relative to even the innermost period (~3000 steps/orbit) that [OrbitalStabilityTest]
 * holds comfortably — see that test for the empirical margin check this project always pairs
 * with a `*_DT` choice (§13.1).
 */
const val ORBITAL_DT = 1e-3

fun buildOrbital(
    g: Double = 1.0,
    starMass: Double = 500.0,
    planetMass: Double = 0.001,
    orbitalRadii: List<Double> = listOf(5.0, 8.0, 12.0),
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): OrbitalScenario {
    val bodyGroup = placement.name("bodies")

    val starId = store.create(position = placement.offset, mass = ScalarExpr.of(starMass))
    groups.add(bodyGroup, starId)

    // Tangential velocity in the XZ plane: a planet starts out along +X from the star, so its
    // circular-orbit velocity (perpendicular to that radius, within the same plane) is along Z.
    val planetIds = orbitalRadii.map { r ->
        val v = sqrt(g * starMass / r)
        val id = store.create(
            position = placement.offset + Vector3(r, 0.0, 0.0),
            velocity = Vector3(0.0, 0.0, v),
            mass = ScalarExpr.of(planetMass),
        )
        groups.add(bodyGroup, id)
        id
    }

    val gravity = NBodyGravity(bodyGroup, g = g, name = placement.name("gravity"))

    return OrbitalScenario(
        store = store,
        groups = groups,
        forces = listOf(gravity),
        starId = starId,
        planetIds = planetIds,
    )
}
