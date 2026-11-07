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
import particlesim.physics.TorsionalFlutter
import particlesim.physics.UniformGravity
import particlesim.physics.Wind
import particlesim.surface.Grid
import particlesim.surface.Surface
import particlesim.surface.Triangle
import particlesim.collision.CollisionSystem
import particlesim.collision.ParticleColliderRule
import particlesim.collision.PlaneCollider
import particlesim.core.VectorExpr
import kotlin.math.sin

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
 * ~5.9 units/s with a ±2 gust pulsing at 0.74 Hz. On its own, this
 * pressure force only leans the deck and slightly damps its twisting - it can't self-excite.
 *
 * **Flutter.** So the deck also carries a [TorsionalFlutter] moment (main span only), driven by
 * the same wind expression: Scanlan's torsional-damping term, `M = ½ρB⁴ω·A2*(Vr)·α̇` per unit
 * span, with an `A2*` that is stabilizing at low reduced velocity `Vr = U/(fB)` and
 * destabilizing above a crossing. Its numbers are **fitted, not measured**: the model's masses
 * and air density aren't physical, so [FLUTTER_DENSITY], [FLUTTER_SLOPE] and
 * [FLUTTER_CRITICAL_VR] were chosen so that the one-node twist starts to grow a little below
 * the failure wind - now ~4.7 units/s, ~20% below, with the plain wind at its calibrated
 * [TACOMA_WIND_DENSITY] - and grows at ~11%/s at 5.9 (an e-fold of ~9 s, ~29 s at full scale).
 * [FLUTTER_SATURATION_TWIST] sets the amplitude it levels off at, fitted to the 1941 report's
 * "alternating 45° torsion in the final one-node motion" (p. 59): ~45° at the quarter points
 * in the steady failure wind. Reduced velocity is unchanged by Froude scaling, so the failure wind is at
 * about the same `Vr` (~7.8) here as on the real bridge.
 *
 * **Which mode.** The force uses per-mode reduced velocity ([TACOMA_TORSION_MODE_FREQUENCIES],
 * measured on this model): the whole-span twist has to stretch the main cables, so it's stiffer
 * (0.835 Hz vs the one-node 0.645 Hz), sits at a lower reduced velocity in the same wind, and
 * doesn't go unstable until ~7.3 units/s. So at the failure wind only the one-node mode - the
 * collapse-day mode - flutters, without that being tuned in: measured growth at 5.9 is +0.108/s
 * for the one-node twist and −0.081/s for the whole-span twist. (That depends on the cables being
 * realistically stiff: at the earlier [cableStiffness] of 300,000 the cables stretched ~6x too
 * easily compared with the real steel ones - EA/(wL) ~130 vs an estimated ~860 - which made the
 * whole-span twist the *softer* mode. 1,000,000 is as stiff as the 1 ms timestep comfortably
 * allows, EA/(wL) ~450.) The one-node mode still needs a disturbance to grow from
 * ([seedOneNodeTwist]): in steady wind it settles into a one-node limit cycle of ~0.78 rad (45°)
 * at the quarter points; the default gust gives ~44°.
 *
 * **Breaking.** Every scenario carries the 1941 report's member limits in [TacomaBreakage],
 * inert until armed; `breakable = true` adds the water below for falling pieces to land on.
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
    /** The real breaking limits - inert until [TacomaBreakage.arm] is called. */
    val breakage: TacomaBreakage,
    /** The water below, for a breakable bridge (`breakable = true`); null otherwise. */
    val collisions: CollisionSystem?,
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

/** The scene's default wind: the failure-day speed with a ±2 gust pulsing at 4.65 rad/s
 * (0.74 Hz). (Originally aimed at the whole-span twist mode; with the stiffer cables that mode
 * is now 0.835 Hz, so the gust drives it off-resonance.) */
const val TACOMA_GUSTY_WIND = "[$TACOMA_FAILURE_WIND + 2.0*sin(t*4.65), 0.0, 0.0]"

/** The model's one-node (antisymmetric) torsion frequency in still air, Hz - the real 0.2 Hz
 * times √10. */
const val TACOMA_TORSION_FREQUENCY = 0.645

/**
 * The main span's first four torsion modes, as measured on this model in still air (seed each
 * shape `sin(nπz/L)`, take the spectrum's peak): n = 1 (whole span twisting together), 2 (the
 * one-node, collapse-day mode), 3, 4, in Hz. The odd (symmetric) ones are stiff because twisting
 * that way has to stretch the main cables; the one-node mode doesn't, so it's the softest - as
 * on the real bridge. The flutter force uses these for per-mode reduced velocity.
 */
val TACOMA_TORSION_MODE_FREQUENCIES = listOf(0.835, TACOMA_TORSION_FREQUENCY, 1.125, 1.075)

/** What the flutter force uses for any twisting the four modes above don't capture (higher
 * modes) - about the highest of them. */
const val TACOMA_RESIDUAL_TORSION_FREQUENCY = 1.1

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
    towerDepthBelowDeck: Double = TACOMA_DECK_HEIGHT_ABOVE_WATER,
    hangerSpacing: Double = 1.524,
    /** Mass of each of the four girder-line particles (girder tops and bottoms) per row. */
    girderMassPerParticle: Double = 0.5,
    /** Mass of the roadway centreline particle per row - the deck slab and floor system. Most of
     * the deck's mass sits near the centreline, which is what sets the torsion/vertical
     * frequency ratio (see [buildTacomaNarrows]'s tuning note). */
    roadwayMassPerParticle: Double = 6.0,
    cableMassPerParticle: Double = 2.0,
    cableStiffness: Double = 1_000_000.0,
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
    windDensity: Double = TACOMA_WIND_DENSITY,
    /** Add the [TorsionalFlutter] moment to the deck - what the plain [Wind] can't do. */
    flutter: Boolean = true,
    flutterDensity: Double = FLUTTER_DENSITY,
    flutterSlope: Double = FLUTTER_SLOPE,
    flutterCriticalReducedVelocity: Double = FLUTTER_CRITICAL_VR,
    flutterSaturationTwist: Double = FLUTTER_SATURATION_TWIST,
    /** Add the water below, for a bridge whose pieces can fall ([TacomaBreakage]). */
    breakable: Boolean = false,
    /** Use these wind, gravity and flutter forces (shared with another bridge) instead of this
     * bridge's own; the wind and flutter parameters above are then unused. */
    shared: TacomaSharedForces? = null,
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
    // Per-bay bookkeeping for [TacomaBreakage]: (span, bay) -> its edge indices and triangles.
    class BayParts(val span: Int, val index: Int) {
        val left = ArrayList<Int>(); val right = ArrayList<Int>(); val deck = ArrayList<Int>()
        val triangles = HashSet<Triangle>()
    }
    val bayParts = ArrayList<BayParts>()
    val deckTriangles = ArrayList<Triangle>()
    val girderTriangles = ArrayList<Triangle>()
    for ((spanIndex, span) in spans.withIndex()) {
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
        for ((bayIndex, pair) in span.zipWithNext().withIndex()) {
            val (a, b) = pair
            val bay = BayParts(spanIndex, bayIndex).also { bayParts += it }
            fun deckEdge(x: Int, y: Int) { bay.deck += deckEdges.size; deckEdges += Grid.Edge(x, y) }
            // Chords along the span: the deck centreline here, the four girder chords below.
            deckEdge(a.centre, b.centre)
            for ((x, y) in listOf(a.topLeft to b.topLeft, a.bottomLeft to b.bottomLeft)) { bay.left += chordEdges.size; chordEdges += Grid.Edge(x, y) }
            for ((x, y) in listOf(a.topRight to b.topRight, a.bottomRight to b.bottomRight)) { bay.right += chordEdges.size; chordEdges += Grid.Edge(x, y) }
            // Deck-plane lateral bracing (X in each half of the bay).
            deckEdge(a.topLeft, b.centre); deckEdge(a.centre, b.topLeft)
            deckEdge(a.centre, b.topRight); deckEdge(a.topRight, b.centre)
            // Girder-web diagonals (X per bay, per girder). No bottom lateral bracing: open section.
            deckEdge(a.topLeft, b.bottomLeft); deckEdge(a.bottomLeft, b.topLeft)
            deckEdge(a.topRight, b.bottomRight); deckEdge(a.bottomRight, b.topRight)

            val roadway = Grid.triangles(listOf(listOf(a.topLeft, a.centre, a.topRight), listOf(b.topLeft, b.centre, b.topRight)))
            val webs = Grid.triangles(listOf(listOf(a.topLeft, b.topLeft), listOf(a.bottomLeft, b.bottomLeft))) +
                Grid.triangles(listOf(listOf(a.topRight, b.topRight), listOf(a.bottomRight, b.bottomRight)))
            deckTriangles += roadway
            girderTriangles += webs
            bay.triangles += roadway + webs
        }
    }
    val deckSurface = Surface(deckTriangles, name = placement.name("roadway"), uvs = tacomaRoadwayUvs(spans, store, placement))
    val girderSurface = Surface(girderTriangles, name = placement.name("girders"))

    // --- Towers (static) --------------------------------------------------------------------
    val legSegments = 12
    val towerLegs = buildTacomaTowerLegs(
        halfWidth, zWestTower, zEastTower, towerDepthBelowDeck, towerHeightAboveDeck, legSegments, store, groups, placement,
    )
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
    // Each hanger carries half its deck row: the two girder-side particles on its side and half
    // the centreline.
    val deckWeightPerHanger = (2.0 * girderMassPerParticle + 0.5 * roadwayMassPerParticle) * g
    val suspension = hangTacomaCables(
        spans, towerTops = legIds.map { it.last() }, halfWidth = halfWidth, mainSpan = mainSpan, sideSpan = sideSpan,
        sag = sag, towerTopY = towerHeightAboveDeck, anchorY = 0.4, deckWeightPerHanger = deckWeightPerHanger,
        cableMassPerParticle = cableMassPerParticle, cableStiffness = cableStiffness, hangerStiffness = hangerStiffness, g = g,
        cableGroup = cableGroup, cableAnchorGroup = cableAnchorGroup, store = store, groups = groups, placement = placement,
    )
    val cables = suspension.cables
    val hangerConnections = suspension.hangerConnections
    val cableForces = suspension.cableSprings
    val hangerForces = suspension.hangerSprings
    val cableMembers = suspension.cableMembers
    val hangerMembers = suspension.hangerMembers

    // The 1940 surfaces catch the full wind: the shared wind's density is this bridge's own.
    val wind = shared?.wind?.also { it.addTriangles(deckTriangles + girderTriangles, windDensity / TACOMA_WIND_DENSITY) }
        ?: Wind(deckTriangles + girderTriangles, ExpressionParser.parseVector(windVelocity), density = windDensity, name = placement.name("wind"))

    // One flutter strip per interior main-span row (end rows are pinned), each standing for one
    // bay. Main span only: its mode frequencies are the main span's, and applying the main
    // span's frequency to the side spans (whose own torsion frequencies differ) let them run
    // away in a flutter of their own.
    val flutterRows = spans[1].subList(1, spans[1].lastIndex)
    val flutterStrips = flutterRows.map { TorsionalFlutter.Strip(it.topLeft, it.topRight, mainBay) }
    val flutterModes = TACOMA_TORSION_MODE_FREQUENCIES.withIndex().map { (k, f) ->
        TorsionalFlutter.Mode(DoubleArray(flutterRows.size) { sin((k + 1) * Math.PI * (it + 1) / mainBays) }, f)
    }
    // Index of this bridge's first strip in the flutter force (non-zero when it's shared).
    var flutterStripOffset = 0
    val flutterForce = if (!flutter) null else if (shared != null) {
        shared.flutter.also { flutterStripOffset = it.addSection(flutterStrips, flutterModes, TACOMA_RESIDUAL_TORSION_FREQUENCY) }
    } else TorsionalFlutter(
        strips = flutterStrips,
        windVelocity = wind::currentVelocity,
        frequency = TACOMA_RESIDUAL_TORSION_FREQUENCY,
        modes = flutterModes,
        density = flutterDensity,
        slope = flutterSlope,
        criticalReducedVelocity = flutterCriticalReducedVelocity,
        saturationTwist = flutterSaturationTwist,
        name = placement.name("flutter"),
    )

    val deckStructure = MeshSprings(deckEdges, store, stiffness = deckStiffness, damping = structuralDamping, name = placement.name("deck-structure"))
    // Strain break thresholds (armed later by TacomaBreakage), so one steel limit fits every chord.
    val girderChords = MeshSprings(
        chordEdges, store, stiffness = girderChordStiffness, damping = structuralDamping, breakThresholdsAreStrains = true,
        name = placement.name("girder-chords"),
    )
    val breakage = TacomaBreakage(
        hangers = hangerMembers, cables = cableMembers, chords = girderChords, deck = deckStructure,
        bays = bayParts.map {
            TacomaBreakage.Bay(
                it.span, it.index, it.left.toIntArray(), it.right.toIntArray(), it.deck.toIntArray(), it.triangles,
                atSupport = it.index == 0 || it.index == spans[it.span].lastIndex - 1,
            )
        },
        flutter = flutterForce,
        flutterStripOfMainRow = (1 until spans[1].lastIndex).associateWith { flutterStripOffset + it - 1 },
        wind = wind,
    )

    if (shared != null) for (id in groups.membersOf(deckGroup) + groups.membersOf(cableGroup)) groups.add(shared.gravityGroup, id)
    val forces = if (shared != null) listOf(deckStructure, girderChords) + cableForces + hangerForces else listOfNotNull(
        deckStructure,
        girderChords,
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
        breakage = breakage,
        collisions = if (!breakable) null else {
            // A collider only stops particles with a radius - a small contact one.
            for (id in groups.membersOf(deckGroup) + groups.membersOf(cableGroup)) store.setRadius(id, ScalarExpr.of(TACOMA_CONTACT_RADIUS), 0.0)
            // The water of the Narrows, 195 ft (5.94 units) below the deck: falling pieces stop
            // there instead of accelerating forever. (Particles aren't destroyed - the forces and
            // surfaces still reference them.)
            val water = PlaneCollider(VectorExpr.of(Vector3(0.0, -TACOMA_DECK_HEIGHT_ABOVE_WATER, 0.0) + placement.offset), normal = Vector3(0.0, 1.0, 0.0), name = placement.name("water"))
            CollisionSystem(
                listOf(deckGroup, cableGroup).map {
                    ParticleColliderRule(it, water, restitution = 0.0, staticFriction = 0.5, kineticFriction = 1.0)
                },
            )
        },
    )
}

// Fitted, not measured - see [buildTacomaNarrows]'s flutter note. The density is per the
// model's own (non-physical) mass units.
const val FLUTTER_DENSITY = 0.16
const val FLUTTER_SLOPE = 0.3
const val FLUTTER_CRITICAL_VR = 5.9

/** Sets the flutter's limit-cycle amplitude: fitted (see [buildTacomaNarrows]) so the steady
 * failure-day wind settles at about the report's "alternating 45° torsion". */
const val FLUTTER_SATURATION_TWIST = 0.31

/**
 * The plain [Wind] force's density, calibrated so the sideways push on the deck matches the real
 * bridge's: at the failure wind (18.8 m/s, q = ½ρU² ≈ 216 Pa with ρ = 1.225 kg/m³), drag on the
 * 8 ft (2.44 m) girders at a drag coefficient of ~2 (typical for a bluff H-section - an
 * assumption) is ~1.05 kN/m, about 1.3% of the 5,700 lb/ft (83 kN/m) dead load. Here the wind
 * pushes on both girder webs, `windDensity·U·2·girderDepth` per unit span, against ~52 units of
 * weight per unit span - 1.3% at U = 5.9 needs ≈ 0.24. (It was 1.0, ~4x too strong.)
 */
const val TACOMA_WIND_DENSITY = 0.24

/** Contact radius of a breakable bridge's deck and cable particles, for landing on the water
 * (20 cm - small beside the members it stands for). */
const val TACOMA_CONTACT_RADIUS = 0.02

/** Deck height above the water, 195 ft (WSDOT) - 5.94 units, the towers' depth below the deck.
 * (Was 19.5 - feet/10 rather than metres/10 - so falling pieces dropped ~3.3x too far.) */
const val TACOMA_DECK_HEIGHT_ABOVE_WATER = 5.94

/**
 * Starts the main span twisting in its one-node (antisymmetric) shape - the two halves rotating
 * in opposite directions, the collapse-day mode - at peak rate [rate] rad/s, about each row's
 * centreline. The model and a span-uniform wind are both symmetric, so without a disturbance
 * like this the antisymmetric mode has nothing to grow from; on the real bridge, the source
 * accounts say the one-node twist set in after a cable band at mid-span slipped.
 */
fun TacomaNarrowsScenario.seedOneNodeTwist(rate: Double) = seedTwist(halfWaves = 2, rate = rate)

/** Starts the main span twisting in the shape `sin(halfWaves·πz/L)` at peak rate [rate] rad/s,
 * about each row's centreline: 1 = the whole span together, 2 = the one-node mode. */
fun TacomaNarrowsScenario.seedTwist(halfWaves: Int, rate: Double) = seedTwist(mainSpan, store, halfWaves, rate)

/** Adds a twisting velocity in the shape `sin(halfWaves·πz/L)`, peak [rate] rad/s, to a span's
 * [rows], each about its own centreline. */
internal fun seedTwist(rows: List<DeckRow>, store: ParticleStore, halfWaves: Int, rate: Double) {
    for ((r, row) in rows.withIndex()) {
        val k = rate * sin(halfWaves * Math.PI * r / rows.lastIndex)
        val c = store.position(row.centre)
        for (id in row.all) {
            val p = store.position(id) - c
            store.setVelocity(id, store.velocity(id) + Vector3(-k * p.y, k * p.x, 0.0))
        }
    }
}
