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
 * A cable-stayed bridge with a single H-shaped tower at mid-span - modelled on the "Bridge Road
 * Lowpoly" Sketchfab model by 00amza (CC BY 4.0,
 * https://sketchfab.com/3d-models/bridge-road-lowpoly-a91e8f0d30f140e6b7ccbdf3044cd7c5), whose
 * layout this reproduces from its preview image: two tower legs straddling the road, joined by a
 * mid-height and a top crossbeam, with straight stays fanning down from the upper part of each
 * leg to the deck edge on both sides of the tower.
 *
 * Unlike [buildSuspensionBridge] (deck hung from sagging main cables) or [buildArchBridge] (deck
 * hung from an arch overhead), every stay here runs straight from the tower to the deck, so the
 * deck is held up by a fan of inclined tension-only [Spring]/[Damper] stays. The arrangement is
 * a semi-fan: the stay reaching furthest along the deck leaves the tower highest, so stays never
 * cross. Each stay's restLength is measured from its two ends' as-built positions (same reason
 * [buildArchBridge]'s hangers do it): zero initial stretch, no first-step force spike.
 *
 * The **tower is static** ([buildFlagpole] legs, every particle pinned): a real concrete pylon
 * barely moves compared with the deck, and the interesting dynamics here are the deck and its
 * stays. The deck rests on the tower's pier at mid-span (that row is pinned, as are the two end
 * rows on the abutments); everything else hangs from the stays. Stays attach only at the deck
 * edges, so [buildFloorBeams] puts a triangulated cross-girder under the deck at every stay row
 * to carry its width out to them.
 *
 * Deck tuning is [buildArchBridge]'s "steel" deck, run at the same [TRAIN_TRESTLE_DT].
 */
data class CableStayedBridgeScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val constraints: List<Constraint>,
    val collisions: SurfaceCollisionSystem,
    val deckGrid: List<List<Int>>,
    val deckSurface: Surface,
    /** The tower's two legs (left, right), base-to-top. */
    val towerLegs: List<List<Int>>,
    /** Crossbeams between the two legs, plus the short pier links from each leg to the deck's
     * pinned mid-span row - static, rendering only. */
    val towerConnections: List<Pair<Int, Int>>,
    /** `(towerId, deckId)` pairs, one per stay. */
    val stayConnections: List<Pair<Int, Int>>,
    val floorBeamConnections: List<Pair<Int, Int>>,
    val floorBeamIds: List<Int>,
    val loadId: Int,
    val loadStart: Vector3,
    val loadStartVelocity: Vector3,
)

const val CABLE_STAYED_BRIDGE_DT = TRAIN_TRESTLE_DT

fun buildCableStayedBridge(
    rows: Int = 33,
    cols: Int = 7,
    spacing: Double = 0.3,
    deckHeight: Double = 0.0,
    /** How far the tower legs stand out from the deck edges. */
    towerLegGap: Double = 0.2,
    /** How far below the deck the tower legs start. */
    towerFootDepth: Double = 1.2,
    towerHeight: Double = 4.5,
    /** Stays per side of the tower, per side of the deck. */
    staysPerFan: Int = 7,
    stayStrideRows: Int = 2,
    stayStiffness: Double = 5000.0,
    stayDamping: Double = 6.0,
    loadMass: Double = 1.8,
    loadRadius: Double = 0.15,
    loadSpeed: Double = 2.0,
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): CableStayedBridgeScenario {
    require(rows % 2 == 1) { "rows must be odd so a deck row sits exactly at the tower, was $rows" }
    val towerRow = rows / 2
    require(staysPerFan * stayStrideRows < towerRow) {
        "$staysPerFan stays every $stayStrideRows rows don't fit between the tower (row $towerRow) and the deck ends"
    }

    val halfWidth = (cols - 1) * spacing / 2.0
    val halfSpan = (rows - 1) * spacing / 2.0

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
        extraPinnedRows = setOf(towerRow),
        store = store, groups = groups, placement = placement,
    )
    val towerZ = store.position(deck.grid[towerRow][0]).z - placement.offset.z

    // Leg particles every `spacing` from the foot to the top, so stay anchors and crossbeams
    // land exactly on leg particles.
    val legSegments = Math.round((towerFootDepth + towerHeight) / spacing).toInt()
    val legSpacing = (towerFootDepth + towerHeight) / legSegments
    val legX = halfWidth + towerLegGap
    val towerLegs = listOf("left" to -legX, "right" to legX).map { (side, x) ->
        buildFlagpole(
            height = towerFootDepth + towerHeight, segments = legSegments,
            store = store, groups = groups,
            placement = ShapePlacement(
                offset = placement.offset + Vector3(x, deckHeight - towerFootDepth, towerZ),
                instanceName = placement.name("tower-$side"),
            ),
        )
    }
    fun legIndexAt(heightAboveDeck: Double) = Math.round((heightAboveDeck + towerFootDepth) / legSpacing).toInt()

    val (leftLeg, rightLeg) = towerLegs.map { it.poleIds }
    val deckLevel = legIndexAt(0.0)
    val towerConnections = towerLegs.flatMap { it.poleIds.zipWithNext() } +
        listOf(
            leftLeg[legIndexAt(towerHeight * 0.47)] to rightLeg[legIndexAt(towerHeight * 0.47)], // mid crossbeam
            leftLeg.last() to rightLeg.last(), // top crossbeam
            leftLeg[deckLevel] to deck.grid[towerRow][0], // pier: deck rests on the tower here
            rightLeg[deckLevel] to deck.grid[towerRow][cols - 1],
        )

    // Semi-fan: the k'th stay out from the tower (k = 1 nearest) reaches deck row
    // towerRow ± k*stride and leaves the tower k-1 leg particles below the top, so the stay
    // reaching furthest along the deck is the highest and none of them cross.
    val staySprings = ArrayList<Spring>()
    val stayDampers = ArrayList<Damper>()
    val stayConnections = ArrayList<Pair<Int, Int>>()
    val stayRows = ArrayList<Int>()
    for (k in 1..staysPerFan) {
        val anchorIndex = leftLeg.size - 1 - (staysPerFan - k)
        for (direction in listOf(-1, 1)) {
            val r = towerRow + direction * k * stayStrideRows
            stayRows += r
            for ((side, leg, deckId) in listOf(
                Triple("left", leftLeg, deck.grid[r][0]),
                Triple("right", rightLeg, deck.grid[r][cols - 1]),
            )) {
                val towerId = leg[anchorIndex]
                val restLength = (store.position(towerId) - store.position(deckId)).length()
                val name = placement.name("stay-$side-$r")
                staySprings += Spring(towerId, deckId, restLength = restLength, stiffness = stayStiffness, compressionStiffness = 0.0, name = name)
                stayDampers += Damper(towerId, deckId, damping = stayDamping, name = "$name-damper")
                stayConnections += towerId to deckId
            }
        }
    }

    val floorBeams = buildFloorBeams(deck.grid, stayRows.sorted(), store = store, groups = groups, placement = placement)

    return CableStayedBridgeScenario(
        store = store,
        groups = groups,
        forces = deck.forces + staySprings + stayDampers + floorBeams.forces,
        constraints = deck.constraints + towerLegs.flatMap { it.constraints },
        collisions = deck.collisions,
        deckGrid = deck.grid,
        deckSurface = deck.surface,
        towerLegs = listOf(leftLeg, rightLeg),
        towerConnections = towerConnections,
        stayConnections = stayConnections,
        floorBeamConnections = floorBeams.connections,
        floorBeamIds = floorBeams.beamIds,
        loadId = deck.loadId,
        loadStart = deck.loadStart,
        loadStartVelocity = deck.loadStartVelocity,
    )
}
