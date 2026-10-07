package particlesim.examples

import particlesim.collision.SurfaceCollisionRule
import particlesim.collision.SurfaceCollisionSystem
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.Constraint
import particlesim.physics.Drag
import particlesim.physics.FixedPosition
import particlesim.physics.Force
import particlesim.physics.MeshSprings
import particlesim.physics.UniformGravity
import particlesim.surface.Grid
import particlesim.surface.Surface

/**
 * A deck spanning a gap, pinned only at its two short ends (the "abutments") rather than all
 * four edges like [buildTrampoline]'s rim — a simply-supported beam, not a taut drum skin, so
 * it sags under its own weight and visibly deepens under a load crossing it, instead of staying
 * flat or bouncing a load back off. Reuses [buildTrampoline]'s own mechanism almost entirely
 * (a [Surface] with structural/shear/bend [MeshSprings], [FixedPosition] anchors,
 * [particlesim.collision.SurfaceCollisionSystem] for the load) - the real difference is *which*
 * rows get pinned (the two ends of [rows], not the whole border) and a load tuned to settle
 * onto the deck (low restitution) and travel across it (given horizontal velocity, slowed by
 * [Drag] - this engine has no tangential/rolling-friction model, so [Drag] is standing in for
 * it, same "model the visible effect, not the exact mechanism" choice [buildFire]'s buoyancy
 * already makes) rather than drop straight down and rest at one point.
 *
 * `row` maps to the span direction (the gap being crossed, laid out along +Z like
 * [buildTrampoline]'s own `row -> depth` convention), `col` to the deck's width (+X) - so
 * `rows` should be the larger of the two for a bridge-shaped (long, narrow) deck, not square.
 *
 * [pinEnds] (default `true`) and [extraPinnedRows] (default empty) are what make this scenario
 * reusable as *just* the deck+load piece of a larger composition instead of duplicating the
 * grid/structural-springs/load/collision construction: [buildSuspensionBridge] builds the same
 * deck with `pinEnds = false` and holds it up with suspender springs to its own main cables
 * instead, and [buildTrainTrestle] pins a regular sequence of intermediate rows via
 * [extraPinnedRows] - one per trestle bent - on top of the usual two end rows, rather than
 * relying on springs (a bent's own legs are already rigid, so there's nothing compliant for a
 * spring-based connection to usefully model there the way a suspender's slack cable has).
 *
 * `massPerParticle`/the three mesh-spring stiffness-damping pairs are parameters, not baked
 * into this function, for the same reason: [buildTrainTrestle]'s whole point is a *much* stiffer
 * "steel" deck than this function's own flag-adjacent defaults, and threading new parameters
 * through is simpler than a second, nearly-identical deck builder.
 */
data class BridgeScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val constraints: List<Constraint>,
    val collisions: SurfaceCollisionSystem,
    /** `grid[row][col]` particle ids of the deck - `row` runs along the span. */
    val grid: List<List<Int>>,
    val surface: Surface,
    val meshSprings: List<MeshSprings>,
    val loadId: Int,
    /** The load's starting position/velocity, so a scene can re-launch it across the deck on a
     * cycle (same re-drop-cycle pattern [buildBallBounce]/[buildTrampoline]'s own scenes use). */
    val loadStart: Vector3,
    val loadStartVelocity: Vector3,
)

/**
 * Structural stiffness (1500) sits between the flag's (200, meant to billow) and the
 * trampoline's (2000, meant to feel taut and bouncy back) - a bridge deck should hold its own
 * weight with only a slight rest sag, then visibly deepen under the load without feeling like a
 * hammock. `dt` follows §13.1's budget the same way `FLAG_DT`/`TRAMPOLINE_DT` do:
 * `massPerParticle` (0.05) over `structuralStiffness` (1500) gives `2*sqrt(0.05/1500) ~=
 * 0.0115s`; `BRIDGE_DT = 1e-3` keeps roughly an 11.5x margin under that single-spring estimate,
 * in the same conservative neighborhood as the flag's ~10x and trampoline's ~12.6x (§13.1's own
 * caveat: a coupled mesh's true bound runs tighter than any single-spring number, hence erring
 * conservative rather than using the raw formula). [BridgeStabilityTest] is the empirical check
 * that this margin actually holds, mirroring [FlagStabilityTest]/[TrampolineStabilityTest].
 */
const val BRIDGE_DT = 1e-3

fun buildBridge(
    rows: Int = 22,
    cols: Int = 5,
    spacing: Double = 0.3,
    deckHeight: Double = 0.0,
    massPerParticle: Double = 0.05,
    structuralStiffness: Double = 1500.0,
    structuralDamping: Double = 3.0,
    shearStiffness: Double = 750.0,
    shearDamping: Double = 1.5,
    bendStiffness: Double = 150.0,
    bendDamping: Double = 0.4,
    loadMass: Double = 1.5,
    loadRadius: Double = 0.14,
    loadSpeed: Double = 1.8,
    restitution: Double = 0.15,
    compressionDamping: Double = 1.5,
    extensionDamping: Double = 0.3,
    loadDragCoefficient: Double = 0.6,
    pinEnds: Boolean = true,
    extraPinnedRows: Set<Int> = emptySet(),
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): BridgeScenario {
    val deckGroup = placement.name("deck")
    val abutmentGroup = placement.name("abutments")
    val loadGroup = placement.name("load")

    val halfWidth = (cols - 1) * spacing / 2.0
    val halfSpan = (rows - 1) * spacing / 2.0
    val grid = (0 until rows).map { r ->
        (0 until cols).map { c ->
            val position = Vector3(c * spacing - halfWidth, deckHeight, r * spacing - halfSpan) + placement.offset
            val id = store.create(position = position, mass = ScalarExpr.of(massPerParticle))
            groups.add(deckGroup, id)
            id
        }
    }
    // The two end rows (if pinEnds) plus any extra intermediate rows a caller names - the
    // deck's two long edges (c == 0 / c == cols - 1) stay free either way, unlike
    // buildTrampoline's whole-rim pin, so whatever span exists *between* pinned rows can still
    // sag.
    for (c in 0 until cols) {
        if (pinEnds) {
            groups.add(abutmentGroup, grid[0][c])
            groups.add(abutmentGroup, grid[rows - 1][c])
        }
        for (r in extraPinnedRows) groups.add(abutmentGroup, grid[r][c])
    }

    val structural = MeshSprings(
        Grid.structuralEdges(grid), store,
        stiffness = structuralStiffness, damping = structuralDamping,
        name = placement.name("structural-springs"),
    )
    val shear = MeshSprings(
        Grid.shearEdges(grid), store,
        stiffness = shearStiffness, damping = shearDamping,
        name = placement.name("shear-springs"),
    )
    val bend = MeshSprings(
        Grid.bendEdges(grid), store,
        stiffness = bendStiffness, damping = bendDamping,
        name = placement.name("bend-springs"),
    )

    val triangles = Grid.triangles(grid)
    val surface = Surface(triangles, name = placement.name("deck-surface"))
    val gravity = UniformGravity(deckGroup, Vector3(0.0, -9.8, 0.0), name = placement.name("gravity"))

    // [pinEnds] = false and an empty [extraPinnedRows] (buildSuspensionBridge's case): the
    // abutment group still exists - a scene can still use it to mark where the ends are - it
    // just isn't pinned, since whatever's holding the deck up that way is the caller's
    // responsibility, not this function's.
    val abutmentAnchor = if (pinEnds || extraPinnedRows.isNotEmpty()) {
        FixedPosition.atCurrentPositions(abutmentGroup, store, groups, name = placement.name("abutment-anchor"))
    } else {
        null
    }

    // Starts a little above the near (row 0) end and a little above deck height, with a
    // horizontal push toward the far end - low enough restitution/high enough compression
    // damping (defaults above) that it settles onto the deck rather than bouncing, and Drag
    // bleeds off that push over the course of the crossing instead of carrying it off the far
    // end at constant speed forever.
    val loadStart = Vector3(0.0, deckHeight + 0.4, -halfSpan + spacing) + placement.offset
    val loadStartVelocity = Vector3(0.0, 0.0, loadSpeed)
    val loadId = store.create(position = loadStart, velocity = loadStartVelocity, mass = ScalarExpr.of(loadMass), radius = ScalarExpr.of(loadRadius))
    groups.add(loadGroup, loadId)
    val loadGravity = UniformGravity(loadGroup, Vector3(0.0, -9.8, 0.0), name = placement.name("load-gravity"))
    val loadDrag = Drag(loadGroup, coefficient = loadDragCoefficient, name = placement.name("load-drag"))

    val collisionRule = SurfaceCollisionRule(
        group = loadGroup,
        surface = surface,
        restitution = restitution,
        compressionDamping = compressionDamping,
        extensionDamping = extensionDamping,
    )

    return BridgeScenario(
        store = store,
        groups = groups,
        forces = listOf(gravity, loadGravity, loadDrag, structural, shear, bend),
        constraints = listOfNotNull(abutmentAnchor),
        collisions = SurfaceCollisionSystem(listOf(collisionRule)),
        grid = grid,
        surface = surface,
        meshSprings = listOf(structural, shear, bend),
        loadId = loadId,
        loadStart = loadStart,
        loadStartVelocity = loadStartVelocity,
    )
}
