package particlesim.examples

import particlesim.collision.SurfaceCollisionRule
import particlesim.collision.SurfaceCollisionSystem
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.Constraint
import particlesim.physics.Damper
import particlesim.physics.Drag
import particlesim.physics.Force
import particlesim.physics.Spring
import particlesim.physics.UniformGravity
import particlesim.surface.Surface

/**
 * A stiff steel deck carried by a row of braced trestle bents (two rigid [buildFlagpole] legs
 * per bent, X-braced between them for the "superstructure" look real steel trestles use for
 * lateral strength) standing on the ground, rather than hung from cables
 * ([buildSuspensionBridge]) or simply spanning between two end abutments ([buildBridge]'s own
 * default). A bent's legs are already perfectly rigid (every [buildFlagpole] particle is
 * [particlesim.physics.FixedPosition]-pinned, never touched by a force), so unlike
 * [buildSuspensionBridge]'s suspenders a bent needs no connecting spring to the deck above it -
 * the deck row directly over each bent is simply pinned at its own position too
 * ([buildBridge]'s `extraPinnedRows`), giving the same rigid-support effect with none of the
 * spring-tuning risk (an aggressive initial stretch nearly sent the suspension bridge's
 * suspenders into a first-step blowup before that tuning settled, see TODO.md) - appropriate
 * for a "stiff, steel" structure that shouldn't noticeably sag at all, unlike either previous
 * bridge.
 *
 * The load is a short coupled train - several heavy particles in a line, held together by
 * stiff, symmetric [Spring]/[Damper] couplings (resisting both push *and* pull, unlike a rope's
 * tension-only segments or a suspender's) - rather than [buildBridge]'s own single ball, since
 * "train trestle" calls for something that reads as a train crossing, not a ball bouncing
 * across.
 */
data class TrainTrestleScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val constraints: List<Constraint>,
    val collisions: SurfaceCollisionSystem,
    /** `deckGrid[row][col]` particle ids - same layout [BridgeScenario.grid] documents. */
    val deckGrid: List<List<Int>>,
    val deckSurface: Surface,
    /** One entry per bent, each a `(leftLegIds, rightLegIds)` pair, base-to-top - for rendering
     * both legs and the X-braces between them (see [particlesim.debug.TrainTrestleScene]). */
    val bents: List<Pair<List<Int>, List<Int>>>,
    /** Train car particle ids, front (leading) to back. */
    val trainIds: List<Int>,
    val trainStarts: List<Vector3>,
    val trainStartVelocity: Vector3,
)

/**
 * `dt` follows the same §13.1 budget [BRIDGE_DT]/[ORBITAL_DT] etc. do, just against a much
 * stiffer structure: `massPerParticle` (0.08, heavier than [BRIDGE_DT]'s 0.05 - steel plate, not
 * wood planking) over `structuralStiffness` (6000, 4x [BRIDGE_DT]'s own) gives
 * `2*sqrt(0.08/6000) ~= 0.0073s`; `TRAIN_TRESTLE_DT = 5e-4` (the same value [TRAMPOLINE_DT]
 * already uses for its own similarly-stiff mesh, not an independently re-derived number) keeps
 * roughly a 14.6x margin, more conservative than [BRIDGE_DT]'s own ~11.5x since this deck is
 * additionally pinned at every bent - a shorter *effective* free span between rigid supports
 * than a single-spring estimate accounts for (§13.1's own "a coupled mesh's true bound runs
 * tighter" caveat). [TrainTrestleStabilityTest] is the empirical check.
 */
const val TRAIN_TRESTLE_DT = 5e-4

fun buildTrainTrestle(
    rows: Int = 25,
    cols: Int = 7,
    spacing: Double = 0.3,
    deckHeight: Double = 2.2,
    bentSpacingRows: Int = 4,
    bentLegSegments: Int = 5,
    trainCars: Int = 3,
    carSpacing: Double = 0.5,
    carMass: Double = 2.5,
    carRadius: Double = 0.16,
    trainSpeed: Double = 2.5,
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): TrainTrestleScenario {
    require(rows > bentSpacingRows) { "rows must exceed bentSpacingRows, was $rows/$bentSpacingRows" }

    val halfWidth = (cols - 1) * spacing / 2.0
    val halfSpan = (rows - 1) * spacing / 2.0
    // Every bentSpacingRows'th row, plus the far end if that stride doesn't already land on it -
    // the two ends are pinned as ordinary abutments below (pinEnds = true) regardless, so this
    // list only needs the *intermediate* rows a real bent stands under.
    val bentRows = (0 until rows step bentSpacingRows).toMutableList().also {
        if (it.last() != rows - 1) it += rows - 1
    }
    val intermediateBentRows = bentRows.toSet() - setOf(0, rows - 1)

    val deck = buildBridge(
        rows = rows, cols = cols, spacing = spacing, deckHeight = deckHeight,
        massPerParticle = 0.08,
        structuralStiffness = 6000.0, structuralDamping = 8.0,
        shearStiffness = 3000.0, shearDamping = 4.0,
        bendStiffness = 600.0, bendDamping = 1.0,
        loadMass = carMass, loadRadius = carRadius, loadSpeed = trainSpeed,
        // Low restitution/high damping - a train settles onto the rails, it doesn't bounce -
        // same reasoning buildBridge's own defaults already use, just carried through
        // explicitly since this function overrides most of buildBridge's other defaults too.
        restitution = 0.05, compressionDamping = 2.0, extensionDamping = 0.5,
        loadDragCoefficient = 0.5,
        pinEnds = true,
        extraPinnedRows = intermediateBentRows,
        store = store, groups = groups, placement = placement,
    )

    fun legPlacement(name: String, x: Double, z: Double) =
        ShapePlacement(offset = placement.offset + Vector3(x, 0.0, z), instanceName = placement.name(name))

    val bentConstraints = ArrayList<Constraint>()
    val bents = bentRows.map { r ->
        val z = r * spacing - halfSpan
        val left = buildFlagpole(
            height = deckHeight, segments = bentLegSegments,
            store = store, groups = groups, placement = legPlacement("bent-$r-left", -halfWidth, z),
        )
        val right = buildFlagpole(
            height = deckHeight, segments = bentLegSegments,
            store = store, groups = groups, placement = legPlacement("bent-$r-right", halfWidth, z),
        )
        bentConstraints += left.constraints + right.constraints
        left.poleIds to right.poleIds
    }

    // Short, stiff, symmetric (both push *and* pull resisted, unlike a rope's tension-only
    // segments) couplings - a train's cars don't go slack between each other the way a rope or
    // a suspender does.
    val trainGroup = placement.name("train")
    val startZ = -halfSpan + spacing
    val trainStarts = (0 until trainCars).map { i ->
        Vector3(0.0, deckHeight + 0.4, startZ - i * carSpacing) + placement.offset
    }
    val trainStartVelocity = Vector3(0.0, 0.0, trainSpeed)
    val trainIds = trainStarts.map { start ->
        val id = store.create(position = start, velocity = trainStartVelocity, mass = ScalarExpr.of(carMass), radius = ScalarExpr.of(carRadius))
        groups.add(trainGroup, id)
        id
    }
    val couplingSprings = trainIds.zipWithNext().mapIndexed { i, (a, b) ->
        Spring(a, b, restLength = carSpacing, stiffness = 4000.0, name = placement.name("coupling-$i"))
    }
    val couplingDampers = trainIds.zipWithNext().mapIndexed { i, (a, b) ->
        Damper(a, b, damping = 8.0, name = placement.name("coupling-damper-$i"))
    }
    val trainGravity = UniformGravity(trainGroup, Vector3(0.0, -9.8, 0.0), name = placement.name("train-gravity"))
    val trainDrag = Drag(trainGroup, coefficient = 0.5, name = placement.name("train-drag"))

    val collisionRule = SurfaceCollisionRule(
        group = trainGroup, surface = deck.surface,
        restitution = 0.05, compressionDamping = 2.0, extensionDamping = 0.5,
    )

    return TrainTrestleScenario(
        store = store,
        groups = groups,
        forces = deck.forces + listOf(trainGravity, trainDrag) + couplingSprings + couplingDampers,
        constraints = deck.constraints + bentConstraints,
        collisions = SurfaceCollisionSystem(listOf(collisionRule)),
        deckGrid = deck.grid,
        deckSurface = deck.surface,
        bents = bents,
        trainIds = trainIds,
        trainStarts = trainStarts,
        trainStartVelocity = trainStartVelocity,
    )
}
