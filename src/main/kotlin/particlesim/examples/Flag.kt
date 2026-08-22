package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.physics.Constraint
import particlesim.physics.FixedPosition
import particlesim.physics.Force
import particlesim.physics.MeshSprings
import particlesim.physics.UniformGravity
import particlesim.physics.Wind
import particlesim.surface.Grid
import particlesim.surface.Triangle
import kotlin.math.cos
import kotlin.math.sin

/**
 * §7.3's worked example: a rectangular sheet, pinned along its left edge to a pole,
 * held together by structural/shear/bend springs (§7.1), and blown by time-varying wind
 * (§7.2) — exercises surfaces, constraints, structural springs, and variable field forces
 * together, as the spec calls for.
 *
 * Also §4.5's first shape-library proof: `buildFlag` accepts a shared `store`/`groups` and a
 * `placement`, so more than one flag (or a flag alongside a ball-bounce, `buildBallBounce`) can
 * coexist in one composed scene — see `ShapePlacement`'s own doc comment for the defaulting/
 * namespacing rules.
 */
data class FlagScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val constraints: List<Constraint>,
    /** `grid[row][col]` particle ids — row 0..rows-1, col 0 is the pole edge. */
    val grid: List<List<Int>>,
    val triangles: List<Triangle>,
    val meshSprings: List<MeshSprings>,
)

/**
 * `dt` for this scenario was chosen from §13.1's stability budget, not picked for looks
 * first: `dt < 2*sqrt(m/k)` per spring, safety-factored 0.1-0.5x, using the *stiffest*
 * spring type (structural) and lightest mass — `2*sqrt(massPerParticle / structuralStiffness)
 * ≈ 0.01s`, so `dt = 1e-3` sits inside the single-spring estimate with margin for the
 * coupled mesh's tighter true bound (§13.1 warns the single-spring number is optimistic).
 * [FlagStabilityTest] is the empirical check that this margin actually holds — it's also
 * what caught `massPerParticle` needing to be light enough for wind to keep the sheet
 * extended instead of just drooping into a curtain under gravity, a balance no amount of
 * arithmetic alone would have gotten right on the first try.
 */
const val FLAG_DT = 1e-3

fun buildFlag(
    rows: Int = 8,
    cols: Int = 14,
    spacing: Double = 0.15,
    // Infinite by default (never breaks) — matches this function's original, already-golden-
    // tested behavior exactly, so every existing caller (FlagGoldenTest, FlagYamlParityTest)
    // is unaffected. An earlier version of FlagDebugDemo passed a finite value to demonstrate
    // §10.2's breakProximity line-renderer coloring; reverted (see TODO.md) once combined with
    // dragging, since a sudden reposition's overshoot broke edges even a generous threshold
    // couldn't survive — kept as a parameter since the underlying mechanism is still real and
    // tested, just not exercised by any current caller.
    structuralBreakThreshold: Double = Double.POSITIVE_INFINITY,
    // §4.5's shape-library params: a shared store/groups (so more than one shape's particles
    // can coexist with distinct ids in one scene) and a placement (offset + instance-name
    // namespacing — see ShapePlacement's own doc comment). Defaults reproduce this function's
    // original single-instance behavior exactly, so every existing caller/golden-file needed
    // zero changes.
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): FlagScenario {
    val massPerParticle = 0.005
    val clothGroup = placement.name("cloth")
    val poleGroup = placement.name("pole")

    val grid = (0 until rows).map { r ->
        (0 until cols).map { c ->
            val position = Vector3(c * spacing, -r * spacing, 0.0) + placement.offset
            val id = store.create(position = position, mass = ScalarExpr.of(massPerParticle))
            groups.add(clothGroup, id)
            id
        }
    }
    val poleEdge = grid.map { row -> row[0] }
    poleEdge.forEach { groups.add(poleGroup, it) }

    val structural = MeshSprings(
        Grid.structuralEdges(grid), store,
        stiffness = 200.0, damping = 1.0,
        breakThreshold = structuralBreakThreshold,
    )
    val shear = MeshSprings(
        Grid.shearEdges(grid), store,
        stiffness = 100.0, damping = 0.5,
    )
    val bend = MeshSprings(
        Grid.bendEdges(grid), store,
        stiffness = 20.0, damping = 0.2,
    )

    val triangles = Grid.triangles(grid)
    val wind = Wind(
        triangles,
        VectorExpr.of { t -> Vector3(6.0 + 2.0 * sin(t * 0.7), 0.3 * sin(t * 1.3), 0.8 * cos(t * 0.9)) },
        density = 1.2,
    )
    val gravity = UniformGravity(clothGroup, Vector3(0.0, -9.8, 0.0))

    val constraints = listOf(FixedPosition.atCurrentPositions(poleGroup, store, groups))

    return FlagScenario(
        store = store,
        groups = groups,
        forces = listOf(gravity, wind, structural, shear, bend),
        constraints = constraints,
        grid = grid,
        triangles = triangles,
        meshSprings = listOf(structural, shear, bend),
    )
}
