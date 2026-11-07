package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.Spring
import particlesim.surface.UV

/*
 * Pieces shared by the two Tacoma Narrows models - the 1940 bridge ([buildTacomaNarrows]) and its
 * 1950 replacement ([buildTacomaNarrows1950]). Both are suspension bridges on the same piers
 * with the same spans, so their towers, pretensioned main cables and hangers are built the same
 * way; only the dimensions, masses and the deck between the cables differ.
 */

/** The four static tower legs (west-left, west-right, east-left, east-right), base to top, in
 * the two cable planes at ±[halfWidth]. Each leg reaches [depthBelowDeck] below the deck and
 * [heightAboveDeck] above it. */
internal fun buildTacomaTowerLegs(
    halfWidth: Double,
    zWestTower: Double,
    zEastTower: Double,
    depthBelowDeck: Double,
    heightAboveDeck: Double,
    segments: Int,
    store: ParticleStore,
    groups: Groups,
    placement: ShapePlacement,
): List<FlagpoleScenario> = listOf(
    "tower-west-left" to Vector3(-halfWidth, 0.0, zWestTower), "tower-west-right" to Vector3(halfWidth, 0.0, zWestTower),
    "tower-east-left" to Vector3(-halfWidth, 0.0, zEastTower), "tower-east-right" to Vector3(halfWidth, 0.0, zEastTower),
).map { (name, base) ->
    buildFlagpole(
        height = depthBelowDeck + heightAboveDeck, segments = segments, store = store, groups = groups,
        placement = ShapePlacement(offset = placement.offset + Vector3(base.x, -depthBelowDeck, base.z), instanceName = placement.name(name)),
    )
}

/** Two main cables and their hangers, as built by [hangTacomaCables]. */
internal class TacomaCables(
    /** Each main cable as a polyline, west anchorage to east anchorage (left, right). */
    val cables: List<List<Int>>,
    val cableSprings: List<Spring>,
    val hangerSprings: List<Spring>,
    val hangerConnections: List<Pair<Int, Int>>,
    val cableMembers: List<TacomaBreakage.Member>,
    val hangerMembers: List<TacomaBreakage.Member>,
)

/**
 * Hangs two pretensioned main cables over the tower tops ([towerTops]: west-left, west-right,
 * east-left, east-right) to anchorages [anchorY] above deck level at the far ends of the side
 * spans, with a hanger from the cable straight down to the deck's top edge at every interior row
 * of every span. A suspension bridge's cables already carry the deck's weight before anything
 * moves, so rest lengths are set *short* of the as-built lengths by exactly the stretch that
 * weight causes (`rest = length - tension / stiffness`): the main-span cable's horizontal tension
 * is `H = w L² / 8f` for its weight per length `w`, the side spans use the sag `f (Ls/L)²` that
 * gives the same `H` (so the towers aren't pulled sideways), and each hanger carries
 * [deckWeightPerHanger] - its row's share of the deck. The bridge therefore starts at (very
 * nearly) its equilibrium shape instead of dropping into it.
 */
internal fun hangTacomaCables(
    spans: List<List<DeckRow>>,
    towerTops: List<Int>,
    halfWidth: Double,
    mainSpan: Double,
    sideSpan: Double,
    sag: Double,
    towerTopY: Double,
    anchorY: Double,
    deckWeightPerHanger: Double,
    cableMassPerParticle: Double,
    cableStiffness: Double,
    hangerStiffness: Double,
    g: Double,
    cableGroup: String,
    cableAnchorGroup: String,
    store: ParticleStore,
    groups: Groups,
    placement: ShapePlacement,
): TacomaCables {
    val mainBay = mainSpan / (spans[1].size - 1)
    // Weight per unit length each cable carries: its own particles plus its side's share of each
    // deck row.
    val w = (deckWeightPerHanger + cableMassPerParticle * g) / mainBay
    val horizontalTension = w * mainSpan * mainSpan / (8.0 * sag)
    val sideSag = sag * (sideSpan / mainSpan) * (sideSpan / mainSpan)

    val cableSprings = ArrayList<Spring>()
    val cables = ArrayList<List<Int>>()
    val hangerConnections = ArrayList<Pair<Int, Int>>()
    val hangerSprings = ArrayList<Spring>()
    val cableMembers = ArrayList<TacomaBreakage.Member>()
    val hangerMembers = ArrayList<TacomaBreakage.Member>()
    for ((side, x) in listOf("left" to -halfWidth, "right" to halfWidth)) {
        val westTop = towerTops[if (side == "left") 0 else 1]
        val eastTop = towerTops[if (side == "left") 2 else 3]
        fun cablePoint(y: Double, z: Double): Int {
            val id = store.create(position = Vector3(x, y, z) + placement.offset, mass = ScalarExpr.of(cableMassPerParticle))
            groups.add(cableGroup, id)
            return id
        }
        // Parabola between two supports at heights yA, yB over horizontal length `len`, with
        // mid-chord sag `f`: y(u) = yA + (yB - yA) u - 4 f u (1 - u).
        fun spanCable(rows: List<DeckRow>, yA: Double, yB: Double, f: Double, startId: Int?, endId: Int?): List<Int> {
            val zA = store.position(rows.first().topLeft).z
            val zB = store.position(rows.last().topLeft).z
            return rows.mapIndexed { r, row ->
                val z = store.position(row.topLeft).z
                val u = (z - zA) / (zB - zA)
                when {
                    r == 0 && startId != null -> startId
                    r == rows.lastIndex && endId != null -> endId
                    else -> cablePoint(yA + (yB - yA) * u - 4.0 * f * u * (1.0 - u), z - placement.offset.z)
                }
            }
        }
        val westSide = spanCable(spans[0], anchorY, towerTopY, sideSag, null, westTop)
        val main = spanCable(spans[1], towerTopY, towerTopY, sag, westTop, eastTop)
        val eastSide = spanCable(spans[2], towerTopY, anchorY, sideSag, eastTop, null)
        groups.add(cableAnchorGroup, westSide.first())
        groups.add(cableAnchorGroup, eastSide.last())
        val cable = westSide + main.drop(1) + eastSide.drop(1)
        cables += cable

        // Segment tension = H / cos(slope) = H * length / horizontal length; rest = length - T/k.
        cableSprings += cable.zipWithNext().map { (a, b) ->
            val d = store.position(b) - store.position(a)
            val length = d.length()
            val tension = horizontalTension * length / kotlin.math.abs(d.z)
            // Unnamed: hundreds of individually named cable/hanger springs would flood the
            // viewer's outliner and every frame's registry.
            Spring(a, b, restLength = length - tension / cableStiffness, stiffness = cableStiffness, compressionStiffness = 0.0)
                .also { cableMembers += TacomaBreakage.Member(it, a to b, tension / cableStiffness, length - tension / cableStiffness) }
        }

        // Hangers: every interior row of every span, cable point straight down to the deck edge.
        for ((spanIndex, span) in spans.withIndex()) {
            val cableIds = listOf(westSide, main, eastSide)[spanIndex]
            for (r in 1 until span.lastIndex) {
                val deckId = if (side == "left") span[r].topLeft else span[r].topRight
                val cableId = cableIds[r]
                val length = (store.position(cableId) - store.position(deckId)).length()
                hangerSprings += Spring(
                    cableId, deckId, restLength = length - deckWeightPerHanger / hangerStiffness, stiffness = hangerStiffness,
                    compressionStiffness = 0.0,
                ).also { hangerMembers += TacomaBreakage.Member(it, cableId to deckId, deckWeightPerHanger / hangerStiffness, length - deckWeightPerHanger / hangerStiffness) }
                hangerConnections += cableId to deckId
            }
        }
    }
    return TacomaCables(cables, cableSprings, hangerSprings, hangerConnections, cableMembers, hangerMembers)
}

/** Length of road one repeat of a Tacoma roadway texture covers: 40 ft, one 10 ft dash plus its
 * 30 ft gap (the US standard broken line). */
const val TACOMA_ROADWAY_TILE_LENGTH = 1.2192

/**
 * Texture coordinates for a roadway built of [DeckRow]s whose top surface is the triangles
 * `topLeft - centre - topRight`: `u` across the deck (0 at the left edge, ½ on the centreline,
 * 1 at the right edge), `v` along the span in road-texture repeats ([TACOMA_ROADWAY_TILE_LENGTH]
 * each, measured from the bridge's own origin so the markings run on unbroken from span to span).
 * `v` runs well past 1 - the viewer repeats a texture whose coordinates leave `[0, 1]`.
 */
internal fun tacomaRoadwayUvs(spans: List<List<DeckRow>>, store: ParticleStore, placement: ShapePlacement): Map<Int, UV> {
    val uvs = HashMap<Int, UV>()
    for (row in spans.flatten()) {
        val v = (store.position(row.centre).z - placement.offset.z) / TACOMA_ROADWAY_TILE_LENGTH
        uvs[row.topLeft] = UV(0.0, v)
        uvs[row.centre] = UV(0.5, v)
        uvs[row.topRight] = UV(1.0, v)
    }
    return uvs
}
