package particlesim.examples

import particlesim.core.ParticleStore
import particlesim.physics.FieldValue
import particlesim.physics.Force
import particlesim.physics.MeshSprings
import particlesim.physics.Spring
import particlesim.physics.TorsionalFlutter
import particlesim.physics.Wind
import particlesim.surface.Triangle

/**
 * Real breaking limits for the 1940 Tacoma Narrows model, all taken from the Board of
 * Engineers' report, *The Failure of the Tacoma Narrows Bridge* (Ammann, von Kármán, Woodruff,
 * Federal Works Agency, 28 March 1941), pp. 48-60:
 *
 * - **Suspenders** (hangers): each 50 ft suspender was four 1¼ in galvanized ropes, 165,400 lb
 *   ultimate each (661,600 lb together), carrying 106,000 lb of dead load - so they break at
 *   **6.24x their dead-load tension** ([HANGER_ULTIMATE_TO_DEAD]).
 * - **Main cables**: 67,700 psi from dead load against wire tested at 235,000 psi - **3.47x**
 *   ([CABLE_ULTIMATE_TO_DEAD]).
 * - **Stiffening girders**: silicon steel, ultimate 80,000 psi; the compression flange's
 *   critical buckling stress 40,000 psi. With steel's E = 29,000,000 psi (the standard value,
 *   not from the report) those are strains of **0.00276 in tension and 0.00138 in compression**
 *   ([GIRDER_ULTIMATE_STRAIN], [GIRDER_BUCKLING_STRAIN]), applied to the girder chords.
 *
 * Why these can be used here although the model's masses and member stiffnesses aren't
 * physical: cables and hangers break at a known *multiple of their dead-load tension*, which
 * the model knows exactly from its pretension (a member's dead-load stretch `δ₀ = T₀/k`, so it
 * breaks past `SF·δ₀`). A girder chord's strain is geometric - the girders' curvature times
 * their half-depth, and the model is geometrically to scale - so a steel strain limit means
 * roughly the same thing here as there. In still air the model's chords sit at ~0.00017
 * (~5,000 psi-equivalent): small, as the real girders' dead-load stress was, since a
 * suspension bridge's cables carry its weight (the report's girder stresses, p. 48, come from
 * live load, temperature and wind: up to ~27,000 psi).
 *
 * What is **not** given a limit: the rest of the deck lattice (floor beams, bracing, webs).
 * Its members' stiffnesses were chosen to make the deck behave right overall, not to match
 * real members, and in still air some already sit at strains of 0.002-0.003 - past steel's
 * yield - so a material limit on them would be meaningless. Instead, a **bay separates** (all
 * its lattice edges along the span break together, and its roadway/girder panels disappear)
 * once *both* of its girders have failed - a girder fails when either of its chords in that
 * bay breaks. That's a modeling choice, standing in for the deck tearing apart once both
 * girders have buckled. Not modeled at all: the concrete slab's shear failure (the report's
 * first failure), the towers' buckling (they're static here), and the mid-span cable band
 * slipping.
 *
 * Thresholds start **unarmed** (infinite), because the model rings for several seconds after
 * it's built - hanger tension briefly reaches ~2.1x dead load in still air - so a scene settles
 * first and then calls [arm].
 */
class TacomaBreakage internal constructor(
    val hangers: List<Member>,
    val cables: List<Member>,
    private val chords: MeshSprings,
    private val deck: MeshSprings,
    private val bays: List<Bay>,
    /** The flutter force and which of its strips each main-span row is (row index -> strip
     * index), so a torn-loose section stops being driven. */
    private val flutter: TorsionalFlutter?,
    private val flutterStripOfMainRow: Map<Int, Int>,
    private val wind: Wind,
) {
    /** A cable segment or hanger: its spring, its two particles, its dead-load stretch T₀/k and
     * its spring's (unstressed) rest length. */
    class Member(val spring: Spring, val connection: Pair<Int, Int>, val deadLoadStretch: Double, val restLength: Double)

    /** One bay of one span: its chord edges (in the chords MeshSprings), per girder; its other
     * span-wise lattice edges (in the deck MeshSprings); its roadway and girder triangles. */
    class Bay(
        val span: Int,
        val index: Int,
        val leftChords: IntArray,
        val rightChords: IntArray,
        val deckEdges: IntArray,
        val triangles: Set<Triangle>,
        /** Whether this bay touches a support (tower or anchor pier). Its girders can't fail:
         * the model pins the support row's particles rigidly, which concentrates strain in the
         * neighbouring bay far beyond what the real bearings did (they failed there within 2 s
         * of the twist being seeded - an artifact). */
        val atSupport: Boolean,
    )

    var armed = false
        private set

    private val brokenSprings = HashSet<Spring>()
    private val failedGirders = HashSet<Pair<Bay, Boolean>>() // (bay, isLeft)
    private val separated = HashSet<Bay>()

    /** Triangles of every separated bay - for hiding them. */
    val hiddenTriangles: Set<Triangle> get() = separated.flatMapTo(HashSet()) { it.triangles }

    fun isBroken(spring: Spring) = spring in brokenSprings

    val brokenHangerCount get() = hangers.count { it.spring in brokenSprings }
    val brokenCableCount get() = cables.count { it.spring in brokenSprings }
    val failedGirderCount get() = failedGirders.size
    val separatedBayCount get() = separated.size

    /**
     * How close each unbroken breakable member is to its real limit right now, `0` unstressed to
     * `1` at breaking - the same scale as a spring's break proximity, but computed from the real
     * limits whether or not they're [armed] (so the stress shows in every scene). Cables and
     * hangers: tension over ultimate tension (a slack one is 0; at rest a hanger sits at
     * 1/6.24 ≈ 0.16, a cable at 1/3.47 ≈ 0.29). Girder chords: strain over the steel strain
     * limit for its direction - ultimate in tension, flange buckling in compression.
     */
    fun utilization(store: ParticleStore): Map<Pair<Int, Int>, Double> = tacomaMemberUtilization(
        store, hangers, HANGER_ULTIMATE_TO_DEAD, cables, CABLE_ULTIMATE_TO_DEAD, chords, isBroken = ::isBroken,
    )

    /** Turns the real limits on. */
    fun arm() {
        for (m in hangers) m.spring.setField("extensionBreakThreshold", FieldValue.Scalar(HANGER_ULTIMATE_TO_DEAD * m.deadLoadStretch))
        for (m in cables) m.spring.setField("extensionBreakThreshold", FieldValue.Scalar(CABLE_ULTIMATE_TO_DEAD * m.deadLoadStretch))
        chords.setField("extensionBreakThreshold", FieldValue.Scalar(GIRDER_ULTIMATE_STRAIN))
        chords.setField("compressionBreakThreshold", FieldValue.Scalar(GIRDER_BUCKLING_STRAIN))
        armed = true
    }

    /**
     * Call after every step with that step's broken forces (the integrator's
     * `StepResult.brokenForces`, which the caller must also drop from its force list). Records
     * them, fails girders whose chords broke, separates bays whose girders have both failed, and
     * returns a description of each new failure, in a fixed order.
     */
    fun afterStep(brokenForces: List<Force>): List<String> {
        val events = ArrayList<String>()
        for (force in brokenForces) {
            if (force !is Spring || !brokenSprings.add(force)) continue
            val hanger = hangers.find { it.spring === force }
            if (hanger != null) events += "hanger broke"
            else if (cables.any { it.spring === force }) events += "main cable broke"
        }
        for (bay in bays) {
            if (bay in separated || bay.atSupport) continue
            for ((isLeft, edges) in listOf(true to bay.leftChords, false to bay.rightChords)) {
                if ((bay to isLeft) !in failedGirders && edges.any { !chords.isActive(it) }) {
                    failedGirders += bay to isLeft
                    events += "${if (isLeft) "left" else "right"} girder failed in ${SPAN_NAMES[bay.span]} bay ${bay.index}"
                }
            }
            if ((bay to true) in failedGirders && (bay to false) in failedGirders) {
                separated += bay
                for (e in bay.deckEdges) deck.breakEdge(e)
                for (e in bay.leftChords + bay.rightChords) chords.breakEdge(e)
                wind.removeTriangles(bay.triangles)
                if (bay.span == 1) for (row in listOf(bay.index, bay.index + 1)) flutterStripOfMainRow[row]?.let { flutter?.deactivateStrip(it) }
                events += "deck tore apart in ${SPAN_NAMES[bay.span]} bay ${bay.index}"
            }
        }
        return events
    }

    companion object {
        /** Report p. 51: 4 x 165,400 lb ultimate / 106,000 lb dead load. */
        const val HANGER_ULTIMATE_TO_DEAD = 4 * 165_400.0 / 106_000.0

        /** Report p. 52: 235,000 psi tested / 67,700 psi dead load. */
        const val CABLE_ULTIMATE_TO_DEAD = 235_000.0 / 67_700.0

        private const val STEEL_E_PSI = 29_000_000.0

        /** Report p. 48: silicon steel ultimate 80,000 psi. */
        const val GIRDER_ULTIMATE_STRAIN = 80_000.0 / STEEL_E_PSI

        /** Report p. 48: compression flange critical buckling stress 40,000 psi. */
        const val GIRDER_BUCKLING_STRAIN = 40_000.0 / STEEL_E_PSI

        private val SPAN_NAMES = listOf("west side span", "main span", "east side span")
    }
}
