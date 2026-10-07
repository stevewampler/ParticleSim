package particlesim.examples

import particlesim.collision.SurfaceCollisionSystem
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.expr.ExpressionParser
import particlesim.physics.Constraint
import particlesim.physics.Force
import particlesim.physics.Wind
import particlesim.surface.Surface

/**
 * A through-arch bridge (Sydney Harbour Bridge-style): [buildBridge]'s own stiff "steel" deck
 * ([buildTrainTrestle]'s tuning, reused again rather than re-derived), held up by vertical
 * hangers from two parallel arch trusses overhead instead of standing on bents from below
 * ([buildTrainTrestle]) or hanging from sagging cables ([buildSuspensionBridge]). Each side's
 * arch truss is two [buildArch] chords (upper/lower, offset by [archChordGap]) laced together
 * with diagonal bracing - the same "two rigid chords plus a lattice between them" shape
 * [buildTrainTrestle]'s own bent legs use, just curved instead of straight - standing on short
 * [buildFlagpole] pylon legs at each end.
 *
 * **Hangers are plain rendered connections, not springs** - both ends (a rigid arch point, a
 * rigid deck-support point - see [buildBridge]'s `extraPinnedRows`) are already pinned, so
 * there's nothing compliant for a spring to usefully model, the same reasoning
 * [buildTrainTrestle]'s bent-to-deck connection already established. This is also what made the
 * suspension bridge's own suspenders the risky part of that scenario (an aggressive initial
 * stretch nearly produced a first-step blowup) - an arch sidesteps that risk entirely by
 * construction, not by careful tuning.
 *
 * Each arch chord is built with `segments = rows - 1`, so `archIds[r]` sits at exactly the same
 * Z as `deck.grid[r]` - the same index-alignment trick [buildSuspensionBridge]'s own main
 * cables use, which is what makes a hanger just `archLowerChord[r] to deck.grid[r][edge]`
 * rather than a nearest-point search.
 *
 * Also carries a crosswind [Wind] force on the deck's own triangles (§7.2) - blowing across the
 * span (+X, the deck's width direction) rather than along it, the way an actual crosswind would
 * load a real bridge deck. Barely moves this particular deck (the whole point of "stiff, steel"
 * - §13.5's energy/momentum framing still holds, a well-engineered structure just doesn't show
 * it), but it's a real, named [particlesim.physics.UniformFieldForce] all the same - reachable
 * and inspectable from the outliner, and its "show arrows" toggle (§10.2/§10.3) is real for free
 * via the engine's type-driven default (`ArrowSampling.defaultGroupsFor`), the same as every
 * other named field force in this codebase, with no scene-specific wiring needed for it.
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
    /** One entry per side, each a `(upperChordIds, lowerChordIds)` pair - for rendering both
     * chords and the lattice bracing between them (see [particlesim.debug.ArchBridgeScene]). */
    val archSides: List<Pair<List<Int>, List<Int>>>,
    /** `(archLowerChordId, deckId)` pairs, one per hanger, for rendering them as line segments. */
    val hangerConnections: List<Pair<Int, Int>>,
    val loadId: Int,
    val loadStart: Vector3,
    val loadStartVelocity: Vector3,
)

/** Shares [TRAIN_TRESTLE_DT] - the deck is the exact same stiffness category ("steel"), just
 * held up differently, so there's no new stability budget to re-derive. */
const val ARCH_BRIDGE_DT = TRAIN_TRESTLE_DT

fun buildArchBridge(
    rows: Int = 25,
    cols: Int = 7,
    spacing: Double = 0.3,
    deckHeight: Double = 0.0,
    pylonHeight: Double = 1.0,
    archRise: Double = 3.3,
    archChordGap: Double = 0.35,
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
    val archSides = listOf(-halfWidth, halfWidth).map { x ->
        val sideName = if (x < 0) "left" else "right"
        val upper = buildArch(
            span = halfSpan * 2.0, rise = archRise + archChordGap, segments = rows - 1, baseHeight = archBaseHeight + archChordGap,
            store = store, groups = groups, placement = sidePlacement("arch-upper-$sideName", x),
        )
        val lower = buildArch(
            span = halfSpan * 2.0, rise = archRise, segments = rows - 1, baseHeight = archBaseHeight,
            store = store, groups = groups, placement = sidePlacement("arch-lower-$sideName", x),
        )
        archConstraints += upper.constraints + lower.constraints
        upper.archIds to lower.archIds
    }

    // One hanger per side per hanger row, from that row's lower-chord point straight down to
    // the deck's own edge particle on the same side - both already pinned, so this is purely a
    // rendered connection (see this file's own doc comment on why no spring is involved).
    val hangerConnections = hangerRows.flatMap { r ->
        archSides.mapIndexed { i, (_, lower) -> lower[r] to deck.grid[r][if (i == 0) 0 else cols - 1] }
    }

    // A real air density (1.2 kg/m³, same literal value buildFlag's own wind already uses, not
    // a re-guessed number), gusting across the span rather than along it - a crosswind, the
    // direction a real bridge deck actually has to resist. Built from a parsed expression
    // string, not a native Kotlin lambda, so §10.4's live-editing panel has a real formula to
    // show from the moment the scene loads - the same reasoning buildFlag's own wind already
    // follows (see that file's own doc comment).
    val wind = Wind(
        deck.surface.triangles,
        ExpressionParser.parseVector("[4.0 + 1.5*sin(t*0.5), 0.0, 0.0]"),
        density = 1.2,
        name = placement.name("wind"),
    )

    return ArchBridgeScenario(
        store = store,
        groups = groups,
        forces = deck.forces + wind,
        constraints = deck.constraints + pylonLegs.flatMap { it.constraints } + archConstraints,
        collisions = deck.collisions,
        deckGrid = deck.grid,
        deckSurface = deck.surface,
        pylonLegs = pylonLegs.map { it.poleIds },
        archSides = archSides,
        hangerConnections = hangerConnections,
        loadId = deck.loadId,
        loadStart = deck.loadStart,
        loadStartVelocity = deck.loadStartVelocity,
    )
}
