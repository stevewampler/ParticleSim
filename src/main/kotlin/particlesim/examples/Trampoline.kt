package particlesim.examples

import particlesim.collision.SurfaceCollisionRule
import particlesim.collision.SurfaceCollisionSystem
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.Constraint
import particlesim.physics.FixedPosition
import particlesim.physics.Force
import particlesim.physics.MeshSprings
import particlesim.physics.UniformGravity
import particlesim.surface.Grid
import particlesim.surface.Surface

/**
 * §12.8's worked example: a taut, rim-pinned sheet that a dropped ball actually bounces off of
 * via the sheet's own per-step deformation — the concrete scenario that promotes particle-vs-
 * triangulated-surface collision ([particlesim.collision.SurfaceCollisionSystem]) out of pure
 * speculation. Everything else here already existed: a [Surface] (§7) with structural/shear/
 * bend springs (§7.1) is [buildFlag]'s own mechanism, a rim pinned via [FixedPosition] is the
 * same mechanism the flag's pole edge already uses (just applied to all four border rows/
 * columns instead of one edge), and the ball is a single collidable particle exactly like
 * [buildBallBounce]'s — built inline here rather than by calling that function, since this
 * ball's collision partner is a [SurfaceCollisionRule] against a live mesh, not a
 * [particlesim.collision.ParticleColliderRule] against a static [particlesim.collision.Collider].
 */
data class TrampolineScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val constraints: List<Constraint>,
    val collisions: SurfaceCollisionSystem,
    /** `grid[row][col]` particle ids of the trampoline mat. */
    val grid: List<List<Int>>,
    val surface: Surface,
    val meshSprings: List<MeshSprings>,
    val ballId: Int,
)

/**
 * Structural stiffness ten times [buildFlag]'s (2000 vs 200) — "much stiffer" per §12.8, since
 * a trampoline mat should feel taut, not slack like a hanging flag. `dt` is picked the same
 * way `FLAG_DT` was, from §13.1's `dt < 2*sqrt(m/k)` budget: `massPerParticle` (0.02, four
 * times the flag's, since a real trampoline mat is much heavier than sailcloth) over
 * `structuralStiffness` (2000) gives `2*sqrt(0.02/2000) ~= 0.0063s`; `TRAMPOLINE_DT = 5e-4`
 * keeps a ~12.6x margin under that single-spring estimate, slightly more conservative than the
 * flag's own ~10x, since a coupled mesh's true bound runs tighter than the single-spring
 * number (§13.1's own caveat) and this mesh is stiffer to begin with.
 * [TrampolineStabilityTest] is the empirical check that this margin actually holds, mirroring
 * [FlagStabilityTest].
 */
const val TRAMPOLINE_DT = 5e-4

fun buildTrampoline(
    rows: Int = 10,
    cols: Int = 10,
    spacing: Double = 0.2,
    dropHeight: Double = 1.0,
    ballRadius: Double = 0.12,
    restitution: Double = 0.85,
    compressionDamping: Double = 0.5,
    extensionDamping: Double = 0.1,
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): TrampolineScenario {
    val massPerParticle = 0.02
    val matGroup = placement.name("mat")
    val rimGroup = placement.name("rim")
    val ballGroup = placement.name("ball")

    val halfWidth = (cols - 1) * spacing / 2.0
    val halfDepth = (rows - 1) * spacing / 2.0
    val grid = (0 until rows).map { r ->
        (0 until cols).map { c ->
            val position = Vector3(c * spacing - halfWidth, 0.0, r * spacing - halfDepth) + placement.offset
            val id = store.create(position = position, mass = ScalarExpr.of(massPerParticle))
            groups.add(matGroup, id)
            id
        }
    }
    // Every border row/column, not just one edge like the flag's pole - a trampoline's whole
    // rim is pinned to its frame (§12.8).
    for (r in 0 until rows) for (c in 0 until cols) {
        if (r == 0 || r == rows - 1 || c == 0 || c == cols - 1) {
            groups.add(rimGroup, grid[r][c])
        }
    }

    val structural = MeshSprings(
        Grid.structuralEdges(grid), store,
        stiffness = 2000.0, damping = 4.0,
    )
    val shear = MeshSprings(
        Grid.shearEdges(grid), store,
        stiffness = 1000.0, damping = 2.0,
    )
    val bend = MeshSprings(
        Grid.bendEdges(grid), store,
        stiffness = 200.0, damping = 0.5,
    )

    val triangles = Grid.triangles(grid)
    val surface = Surface(triangles, name = placement.name("mat-surface"))
    val gravity = UniformGravity(matGroup, Vector3(0.0, -9.8, 0.0))

    val rimAnchor = FixedPosition.atCurrentPositions(rimGroup, store, groups, name = placement.name("rim-anchor"))

    val ballId = store.create(
        position = Vector3(0.0, dropHeight, 0.0) + placement.offset,
        radius = ScalarExpr.of(ballRadius),
    )
    groups.add(ballGroup, ballId)
    val ballGravity = UniformGravity(ballGroup, Vector3(0.0, -9.8, 0.0))

    val collisionRule = SurfaceCollisionRule(
        group = ballGroup,
        surface = surface,
        restitution = restitution,
        compressionDamping = compressionDamping,
        extensionDamping = extensionDamping,
    )

    return TrampolineScenario(
        store = store,
        groups = groups,
        forces = listOf(gravity, ballGravity, structural, shear, bend),
        constraints = listOf(rimAnchor),
        collisions = SurfaceCollisionSystem(listOf(collisionRule)),
        grid = grid,
        surface = surface,
        meshSprings = listOf(structural, shear, bend),
        ballId = ballId,
    )
}
