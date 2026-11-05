package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.expr.ExpressionParser
import particlesim.physics.Constraint
import particlesim.physics.FixedPosition
import particlesim.physics.Force
import particlesim.physics.MeshSprings
import particlesim.physics.Spring
import particlesim.physics.TorsionalFlutter
import particlesim.physics.UniformGravity
import particlesim.physics.Wind
import particlesim.surface.Grid
import particlesim.surface.Surface
import particlesim.surface.Triangle

/**
 * The 1940 Tacoma Narrows Bridge ("Galloping Gertie"), at **1 unit = 10 m**, with real gravity.
 *
 * **Real dimensions used** (approximate; WSDOT "Tacoma Narrows Bridge history", Structure
 * magazine's "Tacoma Narrows Bridge Failure 1940", Britannica):
 * - main span 2,800 ft (853 m), two side spans of 1,100 ft (335 m)
 * - 39 ft (11.9 m) between the two cables; roadway 26 ft
 * - stiffened only by two solid plate girders 8 ft (2.44 m) deep - width/span 1:72, depth/span
 *   1:350, extraordinarily slender for its day
 * - main-cable sag 232 ft (1:12 of the span); suspenders every 50 ft
 * - towers 443 ft above the water, deck 195 ft above the water, so the tower tops stand about
 *   248 ft (75.6 m) above the deck
 * - on 7 Nov 1940, in a ~42 mph (18.8 m/s) wind, it went into a ~0.2 Hz (12 cycles/min)
 *   torsional oscillation that split the main span into two halves twisting in opposite
 *   directions, and collapsed; it had earlier shown vertical oscillations at a similar rate.
 *
 * **Scaling.** Lengths are 1/10 of real; keeping real gravity means Froude scaling, so times
 * scale by 1/√10 and frequencies by √10 (the real 0.2 Hz torsion becomes ~0.63 Hz here) and
 * velocities by 1/√10 (the 18.8 m/s failure wind becomes ~5.9 units/s).
 *
 * **Structure.** Each deck row is an open H cross-section of five particles: the two girder
 * tops (where the hangers attach, in the cable planes), the deck centreline, and the two girder
 * bottoms [girderDepth] below. Springs run along the span for each of the five lines (the
 * girders' chords and the deck), across the deck plane (floor beams + lateral bracing
 * diagonals), down each girder web (verticals + diagonals), and as knee braces from each girder
 * bottom to the deck centreline so the H can't rack. There is deliberately **no bottom lateral
 * bracing** - the section is open, as the real one was, so its torsional stiffness comes mostly
 * from the two cables pulling the girders back level, which is what made the real bridge so
 * soft in torsion. The three deck spans are separate (each pinned at its two ends - towers and
 * anchor piers), hung from two continuous main cables that pass over the static towers to
 * anchorages at the ends of the side spans.
 *
 * **Pretension.** A suspension bridge's cables already carry the deck's weight before anything
 * moves, so cable and hanger rest lengths are set *short* of their as-built lengths by exactly
 * the stretch that weight causes (`rest = length - tension / stiffness`): the main-span cable's
 * horizontal tension is `H = w L² / 8f` for its weight per length `w`, the side spans use the
 * sag `f (Ls/L)²` that gives the same `H` (so the towers aren't pulled sideways), and each hanger
 * carries its row's share of the deck. The bridge therefore starts at (very nearly) its
 * equilibrium shape instead of dropping into it.
 *
 * **Wind.** A [Wind] force on the deck's top surface and both girder webs. Its velocity is an
 * expression, live-editable in the viewer; by default ([TACOMA_GUSTY_WIND]) the failure-day
 * ~5.9 units/s with a ±2 gust pulsing at the symmetric torsion frequency. On its own, this
 * pressure force only leans the deck and slightly damps its twisting - it can't self-excite.
 *
 * **Flutter.** So the deck also carries a [TorsionalFlutter] moment (main span only), driven by
 * the same wind expression: Scanlan's torsional-damping term, `M = ½ρB⁴ω·A2*(Vr)·α̇` per unit
 * span, with an `A2*` that is stabilizing at low reduced velocity `Vr = U/(fB)` and
 * destabilizing above a crossing. Its numbers are **fitted, not measured**: the model's masses
 * and air density aren't physical, so [FLUTTER_DENSITY], [FLUTTER_SLOPE] and
 * [FLUTTER_CRITICAL_VR] were chosen so that the one-node twist starts to grow at about 5.0
 * units/s (15% below the failure wind) and grows at ~7%/s at 5.9 - an e-fold of ~14 s, ~45 s at
 * full scale. Reduced velocity is unchanged by Froude scaling, so the failure wind is at about
 * the same `Vr` (~7.8) here as on the real bridge. With the frequency frozen at the one-node
 * mode's, the symmetric twist goes unstable at nearly the same wind, so which mode develops
 * depends on how each starts: the default gust drives the symmetric one; a steady wind lets the
 * seeded one-node twist ([seedOneNodeTwist]) lead.
 */
data class TacomaNarrowsScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val constraints: List<Constraint>,
    /** One list of deck rows per span (west side span, main span, east side span), each row
     * the five cross-section particles in [DeckRow] order. */
    val spans: List<List<DeckRow>>,
    /** Deck top surface and the two girder webs - rendered, and what the wind pushes on. */
    val deckSurface: Surface,
    val girderSurface: Surface,
    /** Each main cable as a polyline, west anchorage to east anchorage (left, right). */
    val cables: List<List<Int>>,
    /** The four tower legs (west-left, west-right, east-left, east-right), base to top. */
    val towerLegs: List<List<Int>>,
    /** Static tower crossbeams (render-only). */
    val towerBeamConnections: List<Pair<Int, Int>>,
    val hangerConnections: List<Pair<Int, Int>>,
    /** Every deck/girder spring as plain pairs, for rendering. */
    val deckConnections: List<Pair<Int, Int>>,
    val wind: Wind,
    /** The self-excited twisting moment on the deck, or null when built with `flutter = false`. */
    val flutter: TorsionalFlutter?,
) {
    val mainSpan: List<DeckRow> get() = spans[1]
}

/** One deck cross-section: girder tops (left/right, in the cable planes), deck centreline,
 * girder bottoms. */
data class DeckRow(val topLeft: Int, val centre: Int, val topRight: Int, val bottomLeft: Int, val bottomRight: Int) {
    val all get() = listOf(topLeft, centre, topRight, bottomLeft, bottomRight)
}

const val TACOMA_DT = 1e-3

/** The failure-day wind (~42 mph, 18.8 m/s) Froude-scaled to this model: 18.8 / √10. */
const val TACOMA_FAILURE_WIND = 5.9

/** The scene's default wind: the failure-day speed with a gust pulsing at 4.65 rad/s
 * (0.74 Hz), the model's symmetric torsion frequency. */
const val TACOMA_GUSTY_WIND = "[$TACOMA_FAILURE_WIND + 2.0*sin(t*4.65), 0.0, 0.0]"

/** The model's one-node (antisymmetric) torsion frequency in still air, Hz - the real 0.2 Hz
 * times √10. The flutter force's frozen frequency. */
const val TACOMA_TORSION_FREQUENCY = 0.64

/** The one-node twist rate (rad/s) the scene starts with - a stand-in for the mid-span cable
 * band that slipped on the day (see [seedOneNodeTwist]). */
const val TACOMA_SCENE_SEED_RATE = 0.3

fun buildTacomaNarrows(
    mainSpan: Double = 85.34,
    sideSpan: Double = 33.53,
    width: Double = 1.189,
    girderDepth: Double = 0.244,
    sag: Double = 7.07,
    towerHeightAboveDeck: Double = 7.56,
    towerDepthBelowDeck: Double = 5.94,
    hangerSpacing: Double = 1.524,
    /** Mass of each of the four girder-line particles (girder tops and bottoms) per row. */
    girderMassPerParticle: Double = 0.5,
    /** Mass of the roadway centreline particle per row - the deck slab and floor system. Most of
     * the deck's mass sits near the centreline, which is what sets the torsion/vertical
     * frequency ratio (see [buildTacomaNarrows]'s tuning note). */
    roadwayMassPerParticle: Double = 6.0,
    cableMassPerParticle: Double = 2.0,
    cableStiffness: Double = 300_000.0,
    hangerStiffness: Double = 20_000.0,
    deckStiffness: Double = 100_000.0,
    /** The girders' top/bottom chords along the span - what sets the deck's bending (and, for
     * this open section, most of its own torsional) stiffness, separately from the rest of the
     * deck lattice. */
    girderChordStiffness: Double = 100_000.0,
    /** Low on purpose - real structural damping was small, and heavy damping would hide any
     * aerodynamic effect. */
    structuralDamping: Double = 3.0,
    windVelocity: String = TACOMA_GUSTY_WIND,
    windDensity: Double = 1.0,
    /** Add the [TorsionalFlutter] moment to the deck - what the plain [Wind] can't do. */
    flutter: Boolean = true,
    flutterDensity: Double = FLUTTER_DENSITY,
    flutterSlope: Double = FLUTTER_SLOPE,
    flutterCriticalReducedVelocity: Double = FLUTTER_CRITICAL_VR,
    flutterSaturationTwist: Double = 0.3,
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): TacomaNarrowsScenario {
    val g = 9.8
    val mainBays = Math.round(mainSpan / hangerSpacing).toInt()
    val sideBays = Math.round(sideSpan / hangerSpacing).toInt()
    val mainBay = mainSpan / mainBays
    val sideBay = sideSpan / sideBays
    val halfWidth = width / 2.0
    val zWestTower = -mainSpan / 2.0
    val zEastTower = mainSpan / 2.0
    val zWestEnd = zWestTower - sideSpan
    val zEastEnd = zEastTower + sideSpan

    val deckGroup = placement.name("deck")
    val cableGroup = placement.name("cables")
    val anchorGroup = placement.name("deck-supports")
    val cableAnchorGroup = placement.name("cable-anchorages")
    fun at(x: Double, y: Double, z: Double) = Vector3(x, y, z) + placement.offset

    // --- Deck: three separate spans of H cross-sections -----------------------------------
    fun buildSpan(zStart: Double, bays: Int, bay: Double): List<DeckRow> = (0..bays).map { r ->
        val z = zStart + r * bay
        fun p(x: Double, y: Double, mass: Double = girderMassPerParticle): Int {
            val id = store.create(position = at(x, y, z), mass = ScalarExpr.of(mass))
            groups.add(deckGroup, id)
            return id
        }
        DeckRow(p(-halfWidth, 0.0), p(0.0, 0.0, roadwayMassPerParticle), p(halfWidth, 0.0), p(-halfWidth, -girderDepth), p(halfWidth, -girderDepth))
    }
    val spans = listOf(
        buildSpan(zWestEnd, sideBays, sideBay),
        buildSpan(zWestTower, mainBays, mainBay),
        buildSpan(zEastTower, sideBays, sideBay),
    )
    // Each span rests on its two end supports (tower / anchor pier).
    for (span in spans) for (row in listOf(span.first(), span.last())) for (id in row.all) groups.add(anchorGroup, id)

    val deckEdges = ArrayList<Grid.Edge>()
    val chordEdges = ArrayList<Grid.Edge>()
    val deckTriangles = ArrayList<Triangle>()
    val girderTriangles = ArrayList<Triangle>()
    for (span in spans) {
        for (row in span) {
            // Floor beams across the deck, girder-web verticals, knee braces.
            deckEdges += Grid.Edge(row.topLeft, row.centre)
            deckEdges += Grid.Edge(row.centre, row.topRight)
            deckEdges += Grid.Edge(row.topLeft, row.bottomLeft)
            deckEdges += Grid.Edge(row.topRight, row.bottomRight)
            deckEdges += Grid.Edge(row.bottomLeft, row.centre)
            deckEdges += Grid.Edge(row.bottomRight, row.centre)
            // Cross-frame diagonals: the real floor beams were deep, rigid cross girders, so each
            // cross-section holds its shape in its own plane. Without these the centreline sags
            // freely between the girders (two collinear floor-beam springs can't carry it).
            deckEdges += Grid.Edge(row.bottomLeft, row.topRight)
            deckEdges += Grid.Edge(row.bottomRight, row.topLeft)
        }
        for ((a, b) in span.zipWithNext()) {
            // Chords along the span: the deck centreline here, the four girder chords below.
            deckEdges += Grid.Edge(a.centre, b.centre)
            for ((x, y) in listOf(a.topLeft to b.topLeft, a.topRight to b.topRight, a.bottomLeft to b.bottomLeft, a.bottomRight to b.bottomRight)) {
                chordEdges += Grid.Edge(x, y)
            }
            // Deck-plane lateral bracing (X in each half of the bay).
            deckEdges += Grid.Edge(a.topLeft, b.centre); deckEdges += Grid.Edge(a.centre, b.topLeft)
            deckEdges += Grid.Edge(a.centre, b.topRight); deckEdges += Grid.Edge(a.topRight, b.centre)
            // Girder-web diagonals (X per bay, per girder). No bottom lateral bracing: open section.
            deckEdges += Grid.Edge(a.topLeft, b.bottomLeft); deckEdges += Grid.Edge(a.bottomLeft, b.topLeft)
            deckEdges += Grid.Edge(a.topRight, b.bottomRight); deckEdges += Grid.Edge(a.bottomRight, b.topRight)

            deckTriangles += Grid.triangles(listOf(listOf(a.topLeft, a.centre, a.topRight), listOf(b.topLeft, b.centre, b.topRight)))
            girderTriangles += Grid.triangles(listOf(listOf(a.topLeft, b.topLeft), listOf(a.bottomLeft, b.bottomLeft)))
            girderTriangles += Grid.triangles(listOf(listOf(a.topRight, b.topRight), listOf(a.bottomRight, b.bottomRight)))
        }
    }
    val deckSurface = Surface(deckTriangles, name = placement.name("roadway"))
    val girderSurface = Surface(girderTriangles, name = placement.name("girders"))

    // --- Towers (static) --------------------------------------------------------------------
    val legSegments = 12
    val towerLegs = listOf(
        "tower-west-left" to Vector3(-halfWidth, 0.0, zWestTower), "tower-west-right" to Vector3(halfWidth, 0.0, zWestTower),
        "tower-east-left" to Vector3(-halfWidth, 0.0, zEastTower), "tower-east-right" to Vector3(halfWidth, 0.0, zEastTower),
    ).map { (name, base) ->
        buildFlagpole(
            height = towerDepthBelowDeck + towerHeightAboveDeck, segments = legSegments, store = store, groups = groups,
            placement = ShapePlacement(offset = placement.offset + Vector3(base.x, -towerDepthBelowDeck, base.z), instanceName = placement.name(name)),
        )
    }
    val legIds = towerLegs.map { it.poleIds }
    fun legIndexAt(y: Double) = Math.round((y + towerDepthBelowDeck) / (towerDepthBelowDeck + towerHeightAboveDeck) * legSegments).toInt()
    val towerBeamConnections = listOf(0 to 1, 2 to 3).flatMap { (l, r) ->
        listOf(
            legIds[l].last() to legIds[r].last(),
            legIds[l][legIndexAt(towerHeightAboveDeck * 0.5)] to legIds[r][legIndexAt(towerHeightAboveDeck * 0.5)],
            legIds[l][legIndexAt(-girderDepth)] to legIds[r][legIndexAt(-girderDepth)],
        )
    }

    // --- Cables (pretensioned) and hangers ---------------------------------------------------
    // Weight per unit length each cable carries: its own particles plus half of each deck row
    // (the two girder-side particles on its side, half the centreline).
    val deckWeightPerHanger = (2.0 * girderMassPerParticle + 0.5 * roadwayMassPerParticle) * g
    val w = (deckWeightPerHanger + cableMassPerParticle * g) / mainBay
    val horizontalTension = w * mainSpan * mainSpan / (8.0 * sag)
    val sideSag = sag * (sideSpan / mainSpan) * (sideSpan / mainSpan)
    val towerTopY = towerHeightAboveDeck
    val anchorY = 0.4

    val cableForces = ArrayList<Force>()
    val cables = ArrayList<List<Int>>()
    val hangerConnections = ArrayList<Pair<Int, Int>>()
    val hangerForces = ArrayList<Force>()
    for ((side, x) in listOf("left" to -halfWidth, "right" to halfWidth)) {
        val westTop = legIds[if (side == "left") 0 else 1].last()
        val eastTop = legIds[if (side == "left") 2 else 3].last()
        fun cablePoint(y: Double, z: Double): Int {
            val id = store.create(position = at(x, y, z), mass = ScalarExpr.of(cableMassPerParticle))
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
        val springs = cable.zipWithNext().map { (a, b) ->
            val d = store.position(b) - store.position(a)
            val length = d.length()
            val tension = horizontalTension * length / kotlin.math.abs(d.z)
            // Unnamed: ~400 individually named cable/hanger springs would flood the viewer's
            // outliner and every frame's registry (they were most of a 180 KB frame).
            Spring(a, b, restLength = length - tension / cableStiffness, stiffness = cableStiffness, compressionStiffness = 0.0)
        }
        cableForces += springs

        // Hangers: every interior row of every span, cable point straight down to the girder top.
        for ((spanIndex, span) in spans.withIndex()) {
            val cableIds = listOf(westSide, main, eastSide)[spanIndex]
            for (r in 1 until span.lastIndex) {
                val deckId = if (side == "left") span[r].topLeft else span[r].topRight
                val cableId = cableIds[r]
                val length = (store.position(cableId) - store.position(deckId)).length()
                hangerForces += Spring(
                    cableId, deckId, restLength = length - deckWeightPerHanger / hangerStiffness, stiffness = hangerStiffness,
                    compressionStiffness = 0.0,
                )
                hangerConnections += cableId to deckId
            }
        }
    }

    val wind = Wind(deckTriangles + girderTriangles, ExpressionParser.parseVector(windVelocity), density = windDensity, name = placement.name("wind"))

    // One flutter strip per interior main-span row (end rows are pinned), each standing for one
    // bay. Main span only: the force's frozen frequency is the main span's torsion frequency, and
    // applying it to the side spans (whose own torsion frequency isn't the same) let them run
    // away in a flutter of their own.
    val flutterForce = if (!flutter) null else TorsionalFlutter(
        strips = spans[1].subList(1, spans[1].lastIndex).map { TorsionalFlutter.Strip(it.topLeft, it.topRight, mainBay) },
        windVelocity = wind::currentVelocity,
        frequency = TACOMA_TORSION_FREQUENCY,
        density = flutterDensity,
        slope = flutterSlope,
        criticalReducedVelocity = flutterCriticalReducedVelocity,
        saturationTwist = flutterSaturationTwist,
        name = placement.name("flutter"),
    )

    val forces = listOfNotNull(
        MeshSprings(deckEdges, store, stiffness = deckStiffness, damping = structuralDamping, name = placement.name("deck-structure")),
        MeshSprings(chordEdges, store, stiffness = girderChordStiffness, damping = structuralDamping, name = placement.name("girder-chords")),
        UniformGravity(deckGroup, Vector3(0.0, -g, 0.0), name = placement.name("gravity")),
        UniformGravity(cableGroup, Vector3(0.0, -g, 0.0), name = placement.name("cable-gravity")),
        wind,
        flutterForce,
    ) + cableForces + hangerForces

    val constraints = towerLegs.flatMap { it.constraints } + listOf(
        FixedPosition.atCurrentPositions(anchorGroup, store, groups, name = placement.name("deck-supports")),
        FixedPosition.atCurrentPositions(cableAnchorGroup, store, groups, name = placement.name("cable-anchorages")),
    )

    return TacomaNarrowsScenario(
        store = store,
        groups = groups,
        forces = forces,
        constraints = constraints,
        spans = spans,
        deckSurface = deckSurface,
        girderSurface = girderSurface,
        cables = cables,
        towerLegs = legIds,
        towerBeamConnections = towerBeamConnections,
        hangerConnections = hangerConnections,
        deckConnections = (deckEdges + chordEdges).map { it.a to it.b },
        wind = wind,
        flutter = flutterForce,
    )
}

// Fitted, not measured - see [buildTacomaNarrows]'s flutter note. The density is per the
// model's own (non-physical) mass units.
const val FLUTTER_DENSITY = 0.16
const val FLUTTER_SLOPE = 0.3
const val FLUTTER_CRITICAL_VR = 5.9

/**
 * Starts the main span twisting in its one-node (antisymmetric) shape - the two halves rotating
 * in opposite directions, the collapse-day mode - at peak rate [rate] rad/s, about each row's
 * centreline. The model and a span-uniform wind are both symmetric, so without a disturbance
 * like this the antisymmetric mode has nothing to grow from; on the real bridge, the source
 * accounts say the one-node twist set in after a cable band at mid-span slipped.
 */
fun TacomaNarrowsScenario.seedOneNodeTwist(rate: Double) {
    for ((r, row) in mainSpan.withIndex()) {
        val k = rate * kotlin.math.sin(2 * Math.PI * r / mainSpan.lastIndex)
        val c = store.position(row.centre)
        for (id in row.all) {
            val p = store.position(id) - c
            store.setVelocity(id, store.velocity(id) + Vector3(-k * p.y, k * p.x, 0.0))
        }
    }
}
