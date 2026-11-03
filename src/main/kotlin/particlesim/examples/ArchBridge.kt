package particlesim.examples

import particlesim.collision.SurfaceCollisionSystem
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.expr.ExpressionParser
import particlesim.physics.Constraint
import particlesim.physics.Damper
import particlesim.physics.Force
import particlesim.physics.MeshSprings
import particlesim.physics.Spring
import particlesim.physics.UniformGravity
import particlesim.physics.Wind
import particlesim.surface.Grid
import particlesim.surface.Surface

/**
 * A through-arch bridge (Sydney Harbour Bridge-style): [buildBridge]'s own stiff "steel" deck
 * ([buildTrainTrestle]'s tuning, reused again rather than re-derived), held up by vertical
 * hangers from two parallel arch trusses overhead instead of standing on bents from below
 * ([buildTrainTrestle]) or hanging from sagging cables ([buildSuspensionBridge]).
 *
 * **The arches are dynamic, not static anchors** - unlike [buildTrainTrestle]'s rigid bents,
 * each side's arch truss (two [buildArch] chords, upper/lower, built with `pinEnds = false`) is
 * only pinned at its two springing points; everything else is a real, massed particle held in
 * shape by its own spring network. That network is built the same way a deck gets its
 * structural/shear/bend springs, just reusing [particlesim.surface.Grid]'s edge-topology
 * helpers against a *2-row* grid (`listOf(upperChordIds, lowerChordIds)`) instead of a deck's
 * many-row one - `Grid.structuralEdges` falls out as each chord's own along-the-curve links plus
 * the vertical struts between the two chords, `Grid.shearEdges` as the diagonal lattice bracing
 * between them, with no new edge-generation code needed for either.
 *
 * **Lateral bracing ties the two sides into one space truss** - rungs between corresponding
 * points of the left and right arches at every row (both chords), X-diagonals across every bay
 * in the upper and lower chord planes, and sway-frame X-diagonals (each side's upper chord to
 * the other side's lower chord) at every row. An earlier version had only rungs, at hanger rows
 * only: every bay between them was then an untriangulated rectangle, a mechanism that let the
 * two arches rack sideways together and twist out of plane until the whole arch folded down
 * onto the deck under its own weight. Fully triangulated, the arch holds its designed shape to
 * within a few centimetres.
 *
 * **The deck now actually hangs from the arch.** Only the deck's two end rows stay
 * [FixedPosition]-pinned (resting on the abutments, same as [buildBridge]'s own default) -
 * every intermediate hanger row that used to be pinned directly via `extraPinnedRows` is now
 * held up *only* by real tension-only [Spring]/[Damper] hangers to the arch's lower chord - a
 * fan of them per side per hanger row (one to the deck edge, plus inclined ones to every deck
 * column on that side's half), since a deck hung only at its edges is a flat spring membrane
 * that sags badly along its centreline.
 * Each hanger's `restLength` is measured from the two ends' own just-built positions
 * (`(archPosition - deckPosition).length()`), not guessed at the way [buildSuspensionBridge]'s
 * own suspenders originally were - since both the arch and the deck are already placed in their
 * own designed target shape before anything moves, the true at-rest gap is just whatever
 * distance already exists between them, so starting every hanger there means zero initial
 * stretch and no first-step force spike to begin with, rather than relying on a careful
 * stiffness/rest-length retune to survive one (§13.1's stability budget cares about *dt* vs.
 * stiffness; this is a separate, cheaper way to avoid a large initial *displacement* from
 * equilibrium in the first place).
 *
 * Stiffness/damping/mass values are in the same "steel" category as the deck's own and run at
 * the deck's own [TRAIN_TRESTLE_DT]; with the arch fully triangulated and the deck fan-hung,
 * the whole structure settles to within a few centimetres of its as-built shape (~0.15 with the load on it) and stays there
 * through a load crossing ([ArchBridgeStabilityTest] pins this down).
 *
 * Also carries a crosswind [Wind] force on the deck's own triangles (§7.2), currently zeroed
 * out (velocity `[0,0,0]`) while the arch's own dynamics are still being worked out - still a
 * real, named, inspectable force (its "show arrows" toggle still works), just with nothing to
 * show right now.
 */
data class ArchBridgeScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val constraints: List<Constraint>,
    val collisions: SurfaceCollisionSystem,
    val deckGrid: List<List<Int>>,
    val deckSurface: Surface,
    /** The four pylon legs (near-left, near-right, far-left, far-right), base-to-top. */
    val pylonLegs: List<List<Int>>,
    /** One entry per side, each a `(upperChordIds, lowerChordIds)` pair. */
    val archSides: List<Pair<List<Int>, List<Int>>>,
    /** Every arch spring connection (each side's own structural/shear/bend lattice, plus the
     * left-to-right rungs and bracing diagonals) as plain pairs, for rendering as line segments (see
     * [particlesim.debug.ArchBridgeScene]) - recomputed once here rather than read back from
     * the `MeshSprings`/`Spring` objects themselves since none of them are breakable, so the
     * connection list never actually changes frame to frame. */
    val archConnections: List<Pair<Int, Int>>,
    /** `(archLowerChordId, deckId)` pairs, one per hanger - now real tension-only springs (see
     * this file's own class doc comment), not just rendered lines, but still exposed as plain
     * pairs here since that's all [particlesim.debug.ArchBridgeScene] needs to draw them. */
    val hangerConnections: List<Pair<Int, Int>>,
    val loadId: Int,
    val loadStart: Vector3,
    val loadStartVelocity: Vector3,
)

/** Shares [TRAIN_TRESTLE_DT] - the deck is the exact same stiffness category ("steel"), just
 * held up differently, so there's no new stability budget to re-derive. Verified for the arch's own
 * dynamics too by [ArchBridgeStabilityTest]. */
const val ARCH_BRIDGE_DT = TRAIN_TRESTLE_DT

fun buildArchBridge(
    rows: Int = 25,
    cols: Int = 7,
    spacing: Double = 0.3,
    deckHeight: Double = 0.0,
    pylonHeight: Double = 1.0,
    archRise: Double = 3.3,
    archChordGap: Double = 0.35,
    archMassPerParticle: Double = 0.03,
    archStructuralStiffness: Double = 4000.0,
    archStructuralDamping: Double = 6.0,
    archShearStiffness: Double = 2000.0,
    archShearDamping: Double = 3.0,
    archBendStiffness: Double = 400.0,
    archBendDamping: Double = 1.0,
    crossBraceStiffness: Double = 3000.0,
    crossBraceDamping: Double = 4.0,
    hangerStiffness: Double = 3000.0,
    hangerDamping: Double = 4.0,
    hangerStrideRows: Int = 2,
    loadMass: Double = 1.8,
    loadRadius: Double = 0.15,
    loadSpeed: Double = 2.0,
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): ArchBridgeScenario {
    val halfWidth = (cols - 1) * spacing / 2.0
    val halfSpan = (rows - 1) * spacing / 2.0

    // Every hangerStrideRows'th row, excluding the two ends - right at the springing points the
    // arch already sits at (near) deck height, so a hanger there would be close to zero length.
    // Also where the left/right cross-braces attach - the same rows either way, not a second,
    // independently-chosen stride.
    val hangerRows = (hangerStrideRows until rows - 1 step hangerStrideRows).toList()

    val deck = buildBridge(
        rows = rows, cols = cols, spacing = spacing, deckHeight = deckHeight,
        massPerParticle = 0.08,
        structuralStiffness = 6000.0, structuralDamping = 8.0,
        shearStiffness = 3000.0, shearDamping = 4.0,
        bendStiffness = 600.0, bendDamping = 1.0,
        loadMass = loadMass, loadRadius = loadRadius, loadSpeed = loadSpeed,
        restitution = 0.1, compressionDamping = 1.5, extensionDamping = 0.4,
        loadDragCoefficient = 0.6,
        pinEnds = true,
        store = store, groups = groups, placement = placement,
    )

    fun endPlacement(name: String, x: Double, z: Double) =
        ShapePlacement(offset = placement.offset + Vector3(x, 0.0, z), instanceName = placement.name(name))

    val pylonLegs = listOf(
        buildFlagpole(height = pylonHeight, segments = 3, store = store, groups = groups, placement = endPlacement("pylon-near-left", -halfWidth, -halfSpan)),
        buildFlagpole(height = pylonHeight, segments = 3, store = store, groups = groups, placement = endPlacement("pylon-near-right", halfWidth, -halfSpan)),
        buildFlagpole(height = pylonHeight, segments = 3, store = store, groups = groups, placement = endPlacement("pylon-far-left", -halfWidth, halfSpan)),
        buildFlagpole(height = pylonHeight, segments = 3, store = store, groups = groups, placement = endPlacement("pylon-far-right", halfWidth, halfSpan)),
    )

    fun sidePlacement(name: String, x: Double) =
        ShapePlacement(offset = placement.offset + Vector3(x, 0.0, 0.0), instanceName = placement.name(name))

    val archBaseHeight = deckHeight + pylonHeight
    val archConstraints = ArrayList<Constraint>()
    val archForces = ArrayList<Force>()
    val archConnections = ArrayList<Pair<Int, Int>>()
    val archSides = listOf(-halfWidth, halfWidth).map { x ->
        val sideName = if (x < 0) "left" else "right"
        val upper = buildArch(
            span = halfSpan * 2.0, rise = archRise + archChordGap, segments = rows - 1, baseHeight = archBaseHeight + archChordGap,
            pinEnds = false, massPerParticle = archMassPerParticle,
            store = store, groups = groups, placement = sidePlacement("arch-upper-$sideName", x),
        )
        val lower = buildArch(
            span = halfSpan * 2.0, rise = archRise, segments = rows - 1, baseHeight = archBaseHeight,
            pinEnds = false, massPerParticle = archMassPerParticle,
            store = store, groups = groups, placement = sidePlacement("arch-lower-$sideName", x),
        )
        archConstraints += upper.constraints + lower.constraints

        // A 2-row grid (row 0 = upper chord, row 1 = lower chord) - Grid's own edge helpers
        // then give the whole truss network with no bespoke topology code: structural edges
        // are each chord's own along-the-curve links plus the vertical struts between the two
        // chords, shear edges the diagonal lattice between them, bend edges a skip-one stiffener
        // along each chord (the same three-tier structural/shear/bend split every mesh in this
        // codebase already uses - just applied to a 2-row "mesh" instead of a many-row one).
        val archGrid = listOf(upper.archIds, lower.archIds)
        val structuralEdges = Grid.structuralEdges(archGrid)
        val shearEdges = Grid.shearEdges(archGrid)
        val bendEdges = Grid.bendEdges(archGrid)
        archForces += MeshSprings(structuralEdges, store, stiffness = archStructuralStiffness, damping = archStructuralDamping, name = placement.name("arch-$sideName-structural"))
        archForces += MeshSprings(shearEdges, store, stiffness = archShearStiffness, damping = archShearDamping, name = placement.name("arch-$sideName-shear"))
        archForces += MeshSprings(bendEdges, store, stiffness = archBendStiffness, damping = archBendDamping, name = placement.name("arch-$sideName-bend"))
        archConnections += (structuralEdges + shearEdges + bendEdges).map { it.a to it.b }

        upper.archIds to lower.archIds
    }

    // Gravity on each chord's own group - UniformGravity targets by group name (§6's universal
    // selector), and buildArch already put each chord's particles in placement.name("arch")
    // relative to its own per-chord placement (that inner placement's own instanceName being
    // placement.name("arch-upper-left") etc. - see sidePlacement above), so this is just naming
    // those groups directly, through the *outer* placement's own .name(...) so it still composes
    // correctly if buildArchBridge itself is ever placed with a non-default instanceName -
    // rather than inventing a combined group.
    val archGravities = listOf("arch-upper-left", "arch-lower-left", "arch-upper-right", "arch-lower-right").map { side ->
        UniformGravity("${placement.name(side)}.arch", Vector3(0.0, -9.8, 0.0), name = placement.name("$side-gravity"))
    }

    // A fan of hangers per side per hanger row, all from that row's lower-chord point down to
    // the deck: one straight down to the deck's own edge particle on that side, plus inclined
    // ones to every deck column on that side's half (the centre column gets one from each
    // side). Edge-only hangers left the deck spanning its full width between them as a flat
    // sheet of springs - a membrane with almost no bending stiffness, so its centreline sagged
    // badly even with the arch above holding firm. The inclined hangers make each hanger row's
    // cross-section a set of triangles (arch point, deck column, deck column), the job a real
    // arch bridge's cross-girders do.
    //
    // Every hanger is tension-only (compressionStiffness = 0, a hanger can't push, same
    // reasoning buildRope's own compression-free segments use) - the deck is no longer
    // independently pinned at these rows at all, so this is what's actually holding it up.
    // restLength is measured from the two ends' own as-built positions (see this file's own
    // class doc comment on why that - not a guessed constant - is what keeps the initial
    // transient gentle).
    val hangerSprings = ArrayList<Spring>()
    val hangerDampers = ArrayList<Damper>()
    val hangerConnections = ArrayList<Pair<Int, Int>>()
    val centreCol = cols / 2
    for (r in hangerRows) {
        archSides.forEachIndexed { i, (_, lower) ->
            val archId = lower[r]
            val side = if (i == 0) "left" else "right"
            val deckCols = if (i == 0) 0..centreCol else (cols - 1 - centreCol until cols)
            for (c in deckCols) {
                val deckId = deck.grid[r][c]
                val restLength = (store.position(archId) - store.position(deckId)).length()
                hangerSprings += Spring(archId, deckId, restLength = restLength, stiffness = hangerStiffness, compressionStiffness = 0.0, name = placement.name("hanger-$side-$r-$c"))
                hangerDampers += Damper(archId, deckId, damping = hangerDamping, name = placement.name("hanger-$side-damper-$r-$c"))
                hangerConnections += archId to deckId
            }
        }
    }

    // Lateral bracing: turns the two planar arch trusses into one triangulated space truss.
    // Plain rungs alone (left[r] to right[r]) leave every bay between two rungs a rectangle,
    // which is a mechanism - the arches can rack sideways together and twist out of plane with
    // nothing resisting it, which is exactly how this bridge used to fold up under its own
    // weight. So every bay gets triangles in all three bracing planes:
    // - **Upper and lower chord planes** (the real bridge's top/bottom wind bracing): a 2-row
    //   grid of `listOf(leftChord, rightChord)` again falls straight out of Grid's helpers -
    //   the rungs are the `c`-direction structural edges (row-to-row, i.e. left-to-right), and
    //   `shearEdges` gives the X-diagonals across each bay. The along-chord structural edges
    //   are skipped, since each side's own arch lattice above already has them.
    // - **Sway frames** (the cross-section at each row): X-diagonals from each side's upper
    //   chord to the other side's lower chord, so the rectangle formed by the two upper/lower
    //   pairs at a row can't shear into a parallelogram either.
    // Rest lengths come from the as-built positions (MeshSprings' own convention), so every
    // brace starts at zero strain.
    val (leftUpper, leftLower) = archSides[0]
    val (rightUpper, rightLower) = archSides[1]
    val crossBraceRungs = ArrayList<Grid.Edge>()
    val crossBraceDiagonals = ArrayList<Grid.Edge>()
    for ((left, right) in listOf(leftUpper to rightUpper, leftLower to rightLower)) {
        val plane = listOf(left, right)
        crossBraceRungs += left.indices.map { r -> Grid.Edge(left[r], right[r]) }
        crossBraceDiagonals += Grid.shearEdges(plane)
    }
    for (r in leftUpper.indices) {
        crossBraceDiagonals += Grid.Edge(leftUpper[r], rightLower[r])
        crossBraceDiagonals += Grid.Edge(leftLower[r], rightUpper[r])
    }
    val crossBraceForces = listOf(
        MeshSprings(crossBraceRungs, store, stiffness = crossBraceStiffness, damping = crossBraceDamping, name = placement.name("cross-brace-rungs")),
        MeshSprings(crossBraceDiagonals, store, stiffness = crossBraceStiffness, damping = crossBraceDamping, name = placement.name("cross-brace-diagonals")),
    )

    // Set to zero for now (velocity [0,0,0]) while the arch's own dynamics are still being
    // worked out - still a real, named, inspectable field force (§10.2's "show arrows" toggle
    // still works, just with nothing to show), same construction buildFlag's own wind uses.
    val wind = Wind(
        deck.surface.triangles,
        ExpressionParser.parseVector("[0.0, 0.0, 0.0]"),
        density = 1.2,
        name = placement.name("wind"),
    )

    return ArchBridgeScenario(
        store = store,
        groups = groups,
        forces = deck.forces + wind + archForces + archGravities + crossBraceForces + hangerSprings + hangerDampers,
        constraints = deck.constraints + pylonLegs.flatMap { it.constraints } + archConstraints,
        collisions = deck.collisions,
        deckGrid = deck.grid,
        deckSurface = deck.surface,
        pylonLegs = pylonLegs.map { it.poleIds },
        archSides = archSides,
        archConnections = archConnections + (crossBraceRungs + crossBraceDiagonals).map { it.a to it.b },
        hangerConnections = hangerConnections,
        loadId = deck.loadId,
        loadStart = deck.loadStart,
        loadStartVelocity = deck.loadStartVelocity,
    )
}
