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
 * **Lateral cross-bracing ties the two sides together** - plain [Spring]/[Damper] struts
 * between corresponding points of the left and right arches (`leftChord[r] to rightChord[r]`,
 * both chords), at the same rows [hangerStrideRows] already places a hanger at. Without them
 * the two arch trusses would have no structural reason to stay in sync with each other at all
 * once they're free to move independently - the real "portal bracing" a twin-arch bridge needs
 * for exactly this reason.
 *
 * The deck itself is unchanged from the static-arch version: still pinned directly at every
 * hanger row via [buildBridge]'s own `extraPinnedRows`, so it doesn't yet depend on the arch
 * (via the hangers, still plain rendered connections, not springs) to hold it up - making the
 * arch dynamic is step one, not a full "the deck now hangs from a flexible arch" redesign in
 * the same pass. Stability tuning for the arch's own new spring network is explicitly deferred
 * (per the user's own framing, not this function's usual "tune empirically before shipping"
 * habit) - the stiffness/damping/mass values below are a reasonable first guess in the same
 * "steel" category as the deck's own, not independently verified the way every other number in
 * this file's siblings was before landing.
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
     * left-to-right cross-bracing) as plain pairs, for rendering as line segments (see
     * [particlesim.debug.ArchBridgeScene]) - recomputed once here rather than read back from
     * the `MeshSprings`/`Spring` objects themselves since none of them are breakable, so the
     * connection list never actually changes frame to frame. */
    val archConnections: List<Pair<Int, Int>>,
    /** `(archLowerChordId, deckId)` pairs, one per hanger, for rendering them as line segments. */
    val hangerConnections: List<Pair<Int, Int>>,
    val loadId: Int,
    val loadStart: Vector3,
    val loadStartVelocity: Vector3,
)

/** Shares [TRAIN_TRESTLE_DT] - the deck is the exact same stiffness category ("steel"), just
 * held up differently, so there's no new stability budget to re-derive. Not re-derived for the
 * arch's own new dynamics either, per this file's own doc comment on why. */
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
        extraPinnedRows = hangerRows.toSet(),
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

    // One hanger per side per hanger row, from that row's lower-chord point straight down to
    // the deck's own edge particle on the same side - the deck is still independently pinned at
    // this row (buildBridge's extraPinnedRows above), so this stays a plain rendered connection,
    // not a spring - the arch moving doesn't (yet) change what's actually holding the deck up.
    val hangerConnections = hangerRows.flatMap { r ->
        archSides.mapIndexed { i, (_, lower) -> lower[r] to deck.grid[r][if (i == 0) 0 else cols - 1] }
    }

    // Lateral cross-bracing: ties the left and right arch trusses together at every hanger row,
    // both chords - without this the two sides would have no structural connection to each
    // other at all once the arch is free to move, the real "portal bracing" role in a twin-arch
    // bridge. A plain horizontal strut (both chords sit at the same height on either side at
    // any given row), restLength = the known X separation (2*halfWidth) rather than measured
    // back from positions, since it's the same for every row by construction.
    val (leftUpper, leftLower) = archSides[0]
    val (rightUpper, rightLower) = archSides[1]
    val crossBraceSprings = ArrayList<Spring>()
    val crossBraceDampers = ArrayList<Damper>()
    for (r in hangerRows) {
        for ((leftId, rightId, chordName) in listOf(Triple(leftUpper[r], rightUpper[r], "upper"), Triple(leftLower[r], rightLower[r], "lower"))) {
            crossBraceSprings += Spring(leftId, rightId, restLength = halfWidth * 2.0, stiffness = crossBraceStiffness, name = placement.name("cross-brace-$chordName-$r"))
            crossBraceDampers += Damper(leftId, rightId, damping = crossBraceDamping, name = placement.name("cross-brace-$chordName-damper-$r"))
        }
    }

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
        forces = deck.forces + wind + archForces + archGravities + crossBraceSprings + crossBraceDampers,
        constraints = deck.constraints + pylonLegs.flatMap { it.constraints } + archConstraints,
        collisions = deck.collisions,
        deckGrid = deck.grid,
        deckSurface = deck.surface,
        pylonLegs = pylonLegs.map { it.poleIds },
        archSides = archSides,
        archConnections = archConnections + crossBraceSprings.map { it.particleA to it.particleB },
        hangerConnections = hangerConnections,
        loadId = deck.loadId,
        loadStart = deck.loadStart,
        loadStartVelocity = deck.loadStartVelocity,
    )
}
