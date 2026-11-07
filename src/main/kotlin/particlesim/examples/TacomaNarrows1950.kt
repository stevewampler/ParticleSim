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
import kotlin.math.sin

/**
 * The 1950 Tacoma Narrows Bridge - the replacement for "Galloping Gertie", built on the old
 * piers (it still carries westbound SR 16) - at the same **1 unit = 10 m** Froude scale and real
 * gravity as [buildTacomaNarrows], so the two can stand side by side.
 *
 * **Real dimensions used** (WSDOT "Tacoma Narrows Bridge history: Tale of three bridges - 1950"
 * and its 1940/1950 comparison table; Wikipedia, "Tacoma Narrows Bridge (1950)"):
 * - main span 2,800 ft (853 m), side spans 1,100 ft (335 m) - the same piers as 1940
 * - the two main cables 60 ft (18.3 m) apart (1940: 39 ft), each 20¼ in thick (1940: 17½ in)
 * - stiffened by two **33 ft (10.1 m) deep open Warren trusses** (1940: 8 ft solid plate
 *   girders) - depth/span 1:85 instead of 1:350. (Some comparison graphics give 25 ft; WSDOT and
 *   Wikipedia both say 33.)
 * - roadway 49 ft 10 in between the curbs: four 9⅝ ft lanes, three 33 in open steel grates
 *   between them and a 19 in grate along each curb (4·9.625 + 3·2.75 + 2·1.583 = 49.9 ft), plus a
 *   3 ft sidewalk each side
 * - suspenders every 32 ft (9.75 m), at the floor-beam panel points (1940: every 50 ft)
 * - cable sag 1:10 of the span, 280 ft (1940: 1:12, 232 ft) - the deeper sag is most of why the
 *   towers stand 307 ft (93.6 m) above the roadway (1940: 248 ft); towers 507 ft above the
 *   water, so the roadway is ~200 ft (61 m) above it
 * - dead load: not published as such, but the cables' design load rose from 28 to 36 million
 *   lb, and with `H = wL²/8f` at the deeper sag that's `(36/28)·(280/232) ≈ 1.55x` the 1940
 *   deck's weight per foot
 *
 * **Structure.** Each deck row (a panel point, every 32 ft) is the same five-particle
 * cross-section as the 1940 model ([DeckRow]): the two truss top chords (in the cable planes,
 * where the suspenders attach, at roadway level), the roadway centreline, and the two bottom
 * chords [trussDepth] below. Along the span: the four truss chords, a centreline stringer, and
 * lateral bracing in the roadway plane. Each truss side is a **Warren truss** - one diagonal
 * per panel, alternating direction - plus a vertical at every panel point, drawn as open
 * members rather than a solid web. Each panel point carries a cross-frame (floor beam, bottom
 * strut and X bracing). The decisive difference from 1940 is the **bottom lateral bracing** (an
 * X in the bottom-chord plane of every panel): with lateral systems in both the top and bottom
 * planes, the deck is a closed box in torsion, where the 1940 deck was an open H that relied on
 * the cables to resist twisting.
 *
 * **Masses and stiffnesses.** Kept in proportion to the 1940 model so the comparison means
 * something: the weight per unit length is 1.55x the 1940 model's (above), split between the
 * heavy trusses and the roadway; each cable is 1.34x as heavy per length (the cross-section
 * ratio (20.25/17.5)²). The truss chords and deck lattice have the same axial stiffness *per
 * unit length* (EA) as the 1940 girders and lattice, so all of the new deck's extra bending and
 * torsional stiffness comes from its geometry - the deeper trusses and the closed section - not
 * from stronger members. The main cables are as stiff as the 1 ms timestep comfortably allows
 * at this finer panel spacing (in EA terms somewhat softer than the 1940 model's, where the real
 * ones were stiffer).
 *
 * **Wind.** A [Wind] on the roadway and on both truss faces, with its density scaled by
 * [TACOMA_1950_TRUSS_SOLIDITY]: an open truss presents only its members to the wind. The
 * sideways drag comes out about the same as the 1940 girders' per unit length - a deeper but
 * open section.
 *
 * **Flutter.** The same [TorsionalFlutter] model and fitted coefficients as the 1940 bridge -
 * deliberately, so the comparison is not decided by construction: the H-section's A2* curve is
 * kept (a truss section's measured A2* stays stabilizing to far higher reduced velocities, so
 * this is the conservative assumption). What changes is everything the formula is fed: the deck
 * is 1.54x wider and its torsion modes ([TACOMA_1950_TORSION_MODE_FREQUENCIES], measured on this
 * model) are several times stiffer, so the same wind is at a much lower reduced velocity
 * `U/(fB)`.
 *
 * **Stress.** [TacomaNarrows1950Scenario.utilization] measures each cable segment, suspender and
 * truss chord against a breaking limit, as for the 1940 bridge - the cables' derived from the
 * 1950 figures ([TACOMA_1950_CABLE_ULTIMATE_TO_DEAD]), the suspenders' and the chords' steel
 * strain limits the 1940 report's, standing in. Nothing breaks: the limits are only shown.
 */
data class TacomaNarrows1950Scenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val constraints: List<Constraint>,
    /** One list of deck rows per span (west side span, main span, east side span). */
    val spans: List<List<DeckRow>>,
    /** The roadway (top chord to top chord), textured. */
    val roadwaySurface: Surface,
    /** Each main cable as a polyline, west anchorage to east anchorage (left, right). */
    val cables: List<List<Int>>,
    /** The four tower legs (west-left, west-right, east-left, east-right), base to top. */
    val towerLegs: List<List<Int>>,
    /** The towers' struts and X bracing (render-only - the towers are static). */
    val towerBracing: List<Pair<Int, Int>>,
    val hangerConnections: List<Pair<Int, Int>>,
    /** Both trusses' chords. */
    val trussChords: List<Pair<Int, Int>>,
    /** Both trusses' verticals and Warren diagonals. */
    val trussWebs: List<Pair<Int, Int>>,
    /** Floor beams and the roadway-plane lateral bracing. */
    val floorSystem: List<Pair<Int, Int>>,
    /** Bottom struts, cross-frame diagonals and the bottom lateral bracing. */
    val bottomBracing: List<Pair<Int, Int>>,
    val wind: Wind,
    /** The self-excited twisting moment on the deck, or null when built with `flutter = false`. */
    val flutter: TorsionalFlutter?,
    val hangerMembers: List<TacomaBreakage.Member>,
    val cableMembers: List<TacomaBreakage.Member>,
    /** The four truss chords' springs (the edges of [trussChords]). */
    val chords: MeshSprings,
) {
    val mainSpan: List<DeckRow> get() = spans[1]

    /**
     * How close each cable segment, hanger and truss chord is to its breaking limit right now,
     * `0` unstressed to `1` at breaking - the same measure as the 1940 bridge's
     * [TacomaBreakage.utilization], with the limits below. Nothing actually breaks.
     */
    fun utilization(): Map<Pair<Int, Int>, Double> = tacomaMemberUtilization(
        store, hangerMembers, TACOMA_1950_HANGER_ULTIMATE_TO_DEAD, cableMembers, TACOMA_1950_CABLE_ULTIMATE_TO_DEAD, chords,
    )
}

/**
 * The 1950 main cables' breaking tension over their dead-load tension. Not published as such, so
 * derived: each cable is 8,705 No. 6 gauge (0.192 in) wires, 252 in² of steel (Wikipedia), and the
 * two cables' design load is 36 million lb (WSDOT) - 71,400 psi. Taking the dead load as 6/7 of
 * the total cable stress, as in the 1940 design (Structure magazine; the 1941 report's 67,700 psi
 * dead-load stress bears that out), leaves ~61,200 psi from dead load; against wire of the 1940
 * cables' tested 235,000 psi (an assumption - the 1950 wire's strength isn't given), **3.84x**.
 */
const val TACOMA_1950_CABLE_ULTIMATE_TO_DEAD = 235_000.0 / (36_000_000.0 / (2 * 8_705 * Math.PI / 4 * 0.192 * 0.192) * 6.0 / 7.0)

/** The 1950 suspenders' breaking tension over their dead-load tension - no source found, so the
 * 1940 suspenders' ratio ([TacomaBreakage.HANGER_ULTIMATE_TO_DEAD], 6.24x) stands in. */
const val TACOMA_1950_HANGER_ULTIMATE_TO_DEAD = TacomaBreakage.HANGER_ULTIMATE_TO_DEAD

/** How much of a truss face is steel rather than air - typical for a stiffening truss. */
const val TACOMA_1950_TRUSS_SOLIDITY = 0.3

/** The 1950 deck's [Wind] density: the 1940 model's calibrated air density times
 * [TACOMA_1950_TRUSS_SOLIDITY]. */
const val TACOMA_1950_WIND_DENSITY = TACOMA_WIND_DENSITY * TACOMA_1950_TRUSS_SOLIDITY

/**
 * The 1950 main span's first four torsion modes in still air, measured on this model the same
 * way as [TACOMA_TORSION_MODE_FREQUENCIES] (seed `sin(nπz/L)`, take the spectrum's peak), Hz.
 */
val TACOMA_1950_TORSION_MODE_FREQUENCIES = listOf(1.25, 2.03, 3.045, 4.04)

/** For twisting the four modes above don't capture - about the highest of them. */
const val TACOMA_1950_RESIDUAL_TORSION_FREQUENCY = 4.0

fun buildTacomaNarrows1950(
    mainSpan: Double = 85.34,
    sideSpan: Double = 33.53,
    /** Between the cables (and the trusses), 60 ft. */
    width: Double = 1.829,
    trussDepth: Double = 1.006,
    sag: Double = 8.534,
    towerHeightAboveDeck: Double = 9.357,
    towerDepthBelowDeck: Double = TACOMA_1950_DECK_HEIGHT_ABOVE_WATER,
    hangerSpacing: Double = 0.9754,
    /** Mass of each of the four truss-chord particles per row - the trusses are heavy. */
    chordMassPerParticle: Double = 1.0,
    /** Mass of the roadway centreline particle per row - slab, stringers and floor beams. */
    roadwayMassPerParticle: Double = 3.9,
    cableMassPerParticle: Double = 1.7,
    cableStiffness: Double = 1_000_000.0,
    hangerStiffness: Double = 20_000.0,
    /** Per spring at the 32 ft panel length: the 1940 lattice's 100,000 at 50 ft, as EA. */
    deckStiffness: Double = 156_000.0,
    trussChordStiffness: Double = 156_000.0,
    structuralDamping: Double = 3.0,
    windVelocity: String = TACOMA_GUSTY_WIND,
    windDensity: Double = TACOMA_1950_WIND_DENSITY,
    flutter: Boolean = true,
    flutterDensity: Double = FLUTTER_DENSITY,
    flutterSlope: Double = FLUTTER_SLOPE,
    flutterCriticalReducedVelocity: Double = FLUTTER_CRITICAL_VR,
    flutterSaturationTwist: Double = FLUTTER_SATURATION_TWIST,
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): TacomaNarrows1950Scenario {
    val g = 9.8
    val anchorY = 0.4
    require(towerHeightAboveDeck - sag > anchorY.coerceAtLeast(0.0)) {
        "the main cable's low point (${towerHeightAboveDeck - sag} above the deck) must clear the deck"
    }
    val mainBays = Math.round(mainSpan / hangerSpacing).toInt()
    val sideBays = Math.round(sideSpan / hangerSpacing).toInt()
    val mainBay = mainSpan / mainBays
    val sideBay = sideSpan / sideBays
    val halfWidth = width / 2.0
    val zWestTower = -mainSpan / 2.0
    val zEastTower = mainSpan / 2.0
    val zWestEnd = zWestTower - sideSpan

    val deckGroup = placement.name("deck")
    val cableGroup = placement.name("cables")
    val anchorGroup = placement.name("deck-supports")
    val cableAnchorGroup = placement.name("cable-anchorages")

    // --- Deck: three separate spans of box cross-sections ------------------------------------
    fun buildSpan(zStart: Double, bays: Int, bay: Double): List<DeckRow> = (0..bays).map { r ->
        val z = zStart + r * bay
        fun p(x: Double, y: Double, mass: Double = chordMassPerParticle): Int {
            val id = store.create(position = Vector3(x, y, z) + placement.offset, mass = ScalarExpr.of(mass))
            groups.add(deckGroup, id)
            return id
        }
        DeckRow(p(-halfWidth, 0.0), p(0.0, 0.0, roadwayMassPerParticle), p(halfWidth, 0.0), p(-halfWidth, -trussDepth), p(halfWidth, -trussDepth))
    }
    val spans = listOf(
        buildSpan(zWestEnd, sideBays, sideBay),
        buildSpan(zWestTower, mainBays, mainBay),
        buildSpan(zEastTower, sideBays, sideBay),
    )
    for (span in spans) for (row in listOf(span.first(), span.last())) for (id in row.all) groups.add(anchorGroup, id)

    val deckEdges = ArrayList<Grid.Edge>()
    val chordEdges = ArrayList<Grid.Edge>()
    val trussWebs = ArrayList<Pair<Int, Int>>()
    val floorSystem = ArrayList<Pair<Int, Int>>()
    val bottomBracing = ArrayList<Pair<Int, Int>>()
    fun edge(a: Int, b: Int, into: MutableList<Pair<Int, Int>>) { deckEdges += Grid.Edge(a, b); into += a to b }
    val roadwayTriangles = ArrayList<Triangle>()
    val trussFaceTriangles = ArrayList<Triangle>()
    for (span in spans) {
        for (row in span) {
            // The panel point's cross-frame: floor beam, truss verticals, bottom strut, X bracing,
            // and knee braces up to the roadway centreline.
            edge(row.topLeft, row.centre, floorSystem); edge(row.centre, row.topRight, floorSystem)
            edge(row.topLeft, row.bottomLeft, trussWebs); edge(row.topRight, row.bottomRight, trussWebs)
            edge(row.bottomLeft, row.bottomRight, bottomBracing)
            edge(row.bottomLeft, row.topRight, bottomBracing); edge(row.bottomRight, row.topLeft, bottomBracing)
            edge(row.bottomLeft, row.centre, bottomBracing); edge(row.bottomRight, row.centre, bottomBracing)
        }
        for ((bayIndex, pair) in span.zipWithNext().withIndex()) {
            val (a, b) = pair
            // The four truss chords (their own MeshSprings), and the centreline stringer.
            for ((x, y) in listOf(a.topLeft to b.topLeft, a.bottomLeft to b.bottomLeft, a.topRight to b.topRight, a.bottomRight to b.bottomRight)) {
                chordEdges += Grid.Edge(x, y)
            }
            edge(a.centre, b.centre, floorSystem)
            // Roadway-plane lateral bracing.
            edge(a.topLeft, b.centre, floorSystem); edge(a.centre, b.topLeft, floorSystem)
            edge(a.centre, b.topRight, floorSystem); edge(a.topRight, b.centre, floorSystem)
            // Bottom lateral bracing - what closes the section (the 1940 deck had none).
            edge(a.bottomLeft, b.bottomRight, bottomBracing); edge(a.bottomRight, b.bottomLeft, bottomBracing)
            // Warren diagonals: one per panel, alternating, the two trusses in step.
            if (bayIndex % 2 == 0) {
                edge(a.topLeft, b.bottomLeft, trussWebs); edge(a.topRight, b.bottomRight, trussWebs)
            } else {
                edge(a.bottomLeft, b.topLeft, trussWebs); edge(a.bottomRight, b.topRight, trussWebs)
            }

            roadwayTriangles += Grid.triangles(listOf(listOf(a.topLeft, a.centre, a.topRight), listOf(b.topLeft, b.centre, b.topRight)))
            trussFaceTriangles += Grid.triangles(listOf(listOf(a.topLeft, b.topLeft), listOf(a.bottomLeft, b.bottomLeft))) +
                Grid.triangles(listOf(listOf(a.topRight, b.topRight), listOf(a.bottomRight, b.bottomRight)))
        }
    }
    val roadwaySurface = Surface(roadwayTriangles, name = placement.name("roadway"), uvs = tacomaRoadwayUvs(spans, store, placement))

    // --- Towers (static) --------------------------------------------------------------------
    // Fine enough that the struts' ends land within ~0.25 of their real heights.
    val legSegments = 64
    val towerLegs = buildTacomaTowerLegs(
        halfWidth, zWestTower, zEastTower, towerDepthBelowDeck, towerHeightAboveDeck, legSegments, store, groups, placement,
    )
    val legIds = towerLegs.map { it.poleIds }
    fun legIndexAt(y: Double) = Math.round((y + towerDepthBelowDeck) / (towerDepthBelowDeck + towerHeightAboveDeck) * legSegments).toInt()
    // Three double-X-braced struts above the roadway (23 ft 5 in, 24 ft 6 in and 26 ft 6 in
    // deep, the deepest at the top) and one X-braced strut 45 ft deep just below the deck
    // (Wikipedia) - each drawn as a top and bottom member with an X between them. The two lower
    // ones' heights are read off photographs.
    val struts = listOf(
        towerHeightAboveDeck - 0.808 to towerHeightAboveDeck,
        0.62 * towerHeightAboveDeck - 0.375 to 0.62 * towerHeightAboveDeck + 0.375,
        0.3 * towerHeightAboveDeck - 0.357 to 0.3 * towerHeightAboveDeck + 0.357,
        -trussDepth - 1.372 to -trussDepth,
    )
    val towerBracing = listOf(0 to 1, 2 to 3).flatMap { (l, r) ->
        struts.flatMap { (y1, y2) ->
            val l1 = legIds[l][legIndexAt(y1)]; val l2 = legIds[l][legIndexAt(y2)]
            val r1 = legIds[r][legIndexAt(y1)]; val r2 = legIds[r][legIndexAt(y2)]
            listOf(l1 to r1, l2 to r2, l1 to r2, r1 to l2)
        }
    }

    // --- Cables (pretensioned) and hangers ---------------------------------------------------
    val deckWeightPerHanger = (2.0 * chordMassPerParticle + 0.5 * roadwayMassPerParticle) * g
    val suspension = hangTacomaCables(
        spans, towerTops = legIds.map { it.last() }, halfWidth = halfWidth, mainSpan = mainSpan, sideSpan = sideSpan,
        sag = sag, towerTopY = towerHeightAboveDeck, anchorY = anchorY, deckWeightPerHanger = deckWeightPerHanger,
        cableMassPerParticle = cableMassPerParticle, cableStiffness = cableStiffness, hangerStiffness = hangerStiffness, g = g,
        cableGroup = cableGroup, cableAnchorGroup = cableAnchorGroup, store = store, groups = groups, placement = placement,
    )

    val wind = Wind(roadwayTriangles + trussFaceTriangles, ExpressionParser.parseVector(windVelocity), density = windDensity, name = placement.name("wind"))

    val flutterRows = spans[1].subList(1, spans[1].lastIndex)
    val flutterForce = if (!flutter) null else TorsionalFlutter(
        strips = flutterRows.map { TorsionalFlutter.Strip(it.topLeft, it.topRight, mainBay) },
        windVelocity = wind::currentVelocity,
        frequency = TACOMA_1950_RESIDUAL_TORSION_FREQUENCY,
        modes = TACOMA_1950_TORSION_MODE_FREQUENCIES.withIndex().map { (k, f) ->
            TorsionalFlutter.Mode(DoubleArray(flutterRows.size) { sin((k + 1) * Math.PI * (it + 1) / mainBays) }, f)
        },
        density = flutterDensity,
        slope = flutterSlope,
        criticalReducedVelocity = flutterCriticalReducedVelocity,
        saturationTwist = flutterSaturationTwist,
        name = placement.name("flutter"),
    )

    val deckStructure = MeshSprings(deckEdges, store, stiffness = deckStiffness, damping = structuralDamping, name = placement.name("deck-structure"))
    val chords = MeshSprings(chordEdges, store, stiffness = trussChordStiffness, damping = structuralDamping, name = placement.name("truss-chords"))

    val forces = listOfNotNull(
        deckStructure,
        chords,
        UniformGravity(deckGroup, Vector3(0.0, -g, 0.0), name = placement.name("gravity")),
        UniformGravity(cableGroup, Vector3(0.0, -g, 0.0), name = placement.name("cable-gravity")),
        wind,
        flutterForce,
    ) + suspension.cableSprings + suspension.hangerSprings

    val constraints = towerLegs.flatMap { it.constraints } + listOf(
        FixedPosition.atCurrentPositions(anchorGroup, store, groups, name = placement.name("deck-supports")),
        FixedPosition.atCurrentPositions(cableAnchorGroup, store, groups, name = placement.name("cable-anchorages")),
    )

    return TacomaNarrows1950Scenario(
        store = store,
        groups = groups,
        forces = forces,
        constraints = constraints,
        spans = spans,
        roadwaySurface = roadwaySurface,
        cables = suspension.cables,
        towerLegs = legIds,
        towerBracing = towerBracing,
        hangerConnections = suspension.hangerConnections,
        trussChords = chordEdges.map { it.a to it.b },
        trussWebs = trussWebs,
        floorSystem = floorSystem,
        bottomBracing = bottomBracing,
        wind = wind,
        flutter = flutterForce,
        hangerMembers = suspension.hangerMembers,
        cableMembers = suspension.cableMembers,
        chords = chords,
    )
}

/** Roadway height above the water, ~200 ft: the towers' 507 ft above the water less their
 * 307 ft above the roadway (WSDOT). */
const val TACOMA_1950_DECK_HEIGHT_ABOVE_WATER = 6.1

/** Starts the 1950 main span twisting in the shape `sin(halfWaves·πz/L)` at peak rate [rate]
 * rad/s - the same disturbance [seedTwist] gives the 1940 bridge. */
fun TacomaNarrows1950Scenario.seedTwist(halfWaves: Int, rate: Double) = seedTwist(mainSpan, store, halfWaves, rate)

/** [seedTwist] in the one-node (antisymmetric) shape. */
fun TacomaNarrows1950Scenario.seedOneNodeTwist(rate: Double) = seedTwist(halfWaves = 2, rate = rate)
