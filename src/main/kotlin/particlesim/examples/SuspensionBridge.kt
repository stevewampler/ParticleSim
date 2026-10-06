package particlesim.examples

import particlesim.collision.SurfaceCollisionSystem
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.physics.Constraint
import particlesim.physics.Damper
import particlesim.physics.Force
import particlesim.physics.Spring
import particlesim.surface.Surface

/**
 * A suspension bridge: [buildBridge]'s own deck (`pinEnds = false`), held up along its whole
 * length by vertical suspender [Spring]/[Damper] pairs to two main cables ([buildRope], one
 * down each side) draped between four towers ([buildFlagpole], two per end - a real bridge's
 * single portal-frame tower is approximated here as two independent legs, one under each
 * cable, with no cross-brace between them). Composes four existing shape-library pieces by
 * placement (§4.5) rather than inventing new deck/cable/tower machinery - the only genuinely
 * new piece is the suspender springs themselves.
 *
 * **Suspenders are deliberately not engineering-accurate lengths.** A real suspension bridge's
 * suspender rods are each cut to a length that makes the deck come out level once the cable has
 * settled into its final parabola - computing that requires knowing the cable's loaded
 * equilibrium shape in advance, circular for a scenario that's supposed to *discover* that
 * shape by simulating it. Instead every suspender gets the same short, tension-only
 * (`compressionStiffness = 0.0`, same "a suspender can't push" reasoning [buildRope] already
 * applies to a rope under compression) rest length, well short of the cables' initial flat
 * (unsagged) height above the deck - so every suspender starts in tension and pulls the cable
 * down toward the deck (and the deck up toward the cable) everywhere at once, settling into a
 * real, physically-coupled equilibrium even though no single suspender's rest length was
 * individually tuned.
 */
data class SuspensionBridgeScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val constraints: List<Constraint>,
    val collisions: SurfaceCollisionSystem,
    /** `deckGrid[row][col]` particle ids - same layout [BridgeScenario.grid] documents. */
    val deckGrid: List<List<Int>>,
    val deckSurface: Surface,
    /** All four tower legs' particle ids flattened, base-to-top within each leg, for rendering
     * them as line segments (`zipWithNext` per leg - see [SuspensionBridgeScene] for how the
     * four legs are kept separate for that). */
    val towerLegs: List<List<Int>>,
    /** Main cable particle ids, near tower to far tower - index `r` is directly above
     * `deckGrid[r]`'s edge on that side (both built with `segments = rows - 1`/`rows` rows, so
     * they line up one-to-one), which is what makes pairing a cable point with its deck point
     * for a suspender just `cableLeft[r]`/`deckGrid[r][0]` rather than a search. */
    val cableLeft: List<Int>,
    val cableRight: List<Int>,
    /** `(cableId, deckId)` pairs, one per suspender, for rendering them as line segments. */
    val suspenderConnections: List<Pair<Int, Int>>,
    val loadId: Int,
    val loadStart: Vector3,
    val loadStartVelocity: Vector3,
)

fun buildSuspensionBridge(
    rows: Int = 18,
    // Wider than buildBridge's own default (5) - a suspended deck's cross-section isn't held
    // flat end-to-end the way a pinned-ends beam's is, so it can develop a slight side-to-side
    // sway as the two cables settle (the same torsional-flutter tendency real suspension
    // bridges are famously prone to, Tacoma Narrows being the textbook case - not something
    // this engine models deliberately, just an emergent consequence of a narrow flexible deck).
    // Caught live: a 5-wide deck let the load drift sideways far enough to go off the edge and
    // fall through open space entirely, confirmed via the load's own live readout (x around
    // -2.3 against a 5-wide deck's own ~0.6 half-width) rather than guessed at from how it
    // looked. More width is cheap margin against that, on top of the damping/drag increases
    // below.
    cols: Int = 7,
    spacing: Double = 0.3,
    towerHeight: Double = 3.0,
    towerSegments: Int = 6,
    suspenderRestLength: Double = 2.4,
    suspenderStiffness: Double = 150.0,
    // Higher than this scenario's first pass (4.0) - extra resistance to the same side-to-side
    // sway `cols`' own doc comment above describes, since the suspenders are what couple the
    // deck's two edges to each other at all.
    suspenderDamping: Double = 6.0,
    loadMass: Double = 1.5,
    loadRadius: Double = 0.14,
    loadSpeed: Double = 1.8,
    // Higher than buildBridge's own default (0.6) - shortens how long the load spends crossing
    // (and therefore how much lateral drift it has time to accumulate, see `cols`' own doc
    // comment) without changing how it settles once it gets there.
    loadDragCoefficient: Double = 1.2,
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): SuspensionBridgeScenario {
    val halfWidth = (cols - 1) * spacing / 2.0
    val halfSpan = (rows - 1) * spacing / 2.0

    val deck = buildBridge(
        rows = rows, cols = cols, spacing = spacing,
        loadMass = loadMass, loadRadius = loadRadius, loadSpeed = loadSpeed,
        loadDragCoefficient = loadDragCoefficient,
        pinEnds = false,
        store = store, groups = groups, placement = placement,
    )

    fun legPlacement(name: String, x: Double, z: Double) =
        ShapePlacement(offset = placement.offset + Vector3(x, 0.0, z), instanceName = placement.name(name))

    val towerLegs = listOf(
        buildFlagpole(height = towerHeight, segments = towerSegments, store = store, groups = groups, placement = legPlacement("tower-near-left", -halfWidth, -halfSpan)),
        buildFlagpole(height = towerHeight, segments = towerSegments, store = store, groups = groups, placement = legPlacement("tower-near-right", halfWidth, -halfSpan)),
        buildFlagpole(height = towerHeight, segments = towerSegments, store = store, groups = groups, placement = legPlacement("tower-far-left", -halfWidth, halfSpan)),
        buildFlagpole(height = towerHeight, segments = towerSegments, store = store, groups = groups, placement = legPlacement("tower-far-right", halfWidth, halfSpan)),
    )

    // segments = rows - 1 gives exactly `rows` rope particles, index-for-index above
    // deck.grid's own rows - see SuspensionBridgeScenario.cableLeft's own doc comment.
    val cableLeft = buildRope(
        topAnchor = Vector3(-halfWidth, towerHeight, -halfSpan),
        bottomAnchor = Vector3(-halfWidth, towerHeight, halfSpan),
        segments = rows - 1,
        store = store, groups = groups, placement = ShapePlacement(offset = placement.offset, instanceName = placement.name("cable-left")),
    )
    val cableRight = buildRope(
        topAnchor = Vector3(halfWidth, towerHeight, -halfSpan),
        bottomAnchor = Vector3(halfWidth, towerHeight, halfSpan),
        segments = rows - 1,
        store = store, groups = groups, placement = ShapePlacement(offset = placement.offset, instanceName = placement.name("cable-right")),
    )

    val suspenderSprings = ArrayList<Spring>()
    val suspenderDampers = ArrayList<Damper>()
    val suspenderConnections = ArrayList<Pair<Int, Int>>()
    for (r in 0 until rows) {
        val pairs = listOf(cableLeft.ropeIds[r] to deck.grid[r][0], cableRight.ropeIds[r] to deck.grid[r][cols - 1])
        val sides = listOf("left", "right")
        pairs.forEachIndexed { i, (cableId, deckId) ->
            suspenderSprings += Spring(
                cableId, deckId, restLength = suspenderRestLength, stiffness = suspenderStiffness, compressionStiffness = 0.0,
                name = placement.name("suspender-${sides[i]}-$r"),
            )
            suspenderDampers += Damper(cableId, deckId, damping = suspenderDamping, name = placement.name("suspender-${sides[i]}-damper-$r"))
            suspenderConnections += cableId to deckId
        }
    }

    return SuspensionBridgeScenario(
        store = store,
        groups = groups,
        forces = deck.forces + cableLeft.forces + cableRight.forces + suspenderSprings + suspenderDampers,
        constraints = deck.constraints + towerLegs.flatMap { it.constraints } + cableLeft.constraints + cableRight.constraints,
        collisions = deck.collisions,
        deckGrid = deck.grid,
        deckSurface = deck.surface,
        towerLegs = towerLegs.map { it.poleIds },
        cableLeft = cableLeft.ropeIds,
        cableRight = cableRight.ropeIds,
        suspenderConnections = suspenderConnections,
        loadId = deck.loadId,
        loadStart = deck.loadStart,
        loadStartVelocity = deck.loadStartVelocity,
    )
}
