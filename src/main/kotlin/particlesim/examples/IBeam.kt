package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.physics.ChunkAccumulator
import particlesim.physics.Constraint
import particlesim.physics.Drag
import particlesim.physics.EditableFields
import particlesim.physics.ElasticLattice
import particlesim.physics.FieldValue
import particlesim.physics.FixedAxis
import particlesim.physics.FixedPosition
import particlesim.physics.Force
import particlesim.physics.LatticeIndex
import kotlin.math.PI
import kotlin.math.sqrt

const val IBEAM_DT = 1e-3
const val STEEL_YOUNGS_MODULUS = 200e9
const val STEEL_DENSITY = 7850.0
/** Mild structural steel's yield stress (S235/A36 are 235-250 MPa). */
const val STEEL_YIELD_STRESS = 250e6
const val STANDARD_GRAVITY = 9.81

/**
 * An I-beam's cross-section and length, counted in lattice cubes of side [spacing]: x along the
 * beam, y up, z across. The web is centred, so [flangeWidthCells] and [webThicknessCells] must be
 * both even or both odd.
 */
data class IBeamShape(
    val lengthCells: Int = 80,
    val depthCells: Int = 10,
    val flangeWidthCells: Int = 6,
    val flangeThicknessCells: Int = 2,
    val webThicknessCells: Int = 2,
    val spacing: Double = 0.05,
) {
    init {
        require((flangeWidthCells - webThicknessCells) % 2 == 0) { "the web can't be centred" }
        require(2 * flangeThicknessCells < depthCells && webThicknessCells <= flangeWidthCells)
    }

    val length: Double get() = lengthCells * spacing
    val depth: Double get() = depthCells * spacing
    val width: Double get() = flangeWidthCells * spacing

    private val webStart: Int get() = (flangeWidthCells - webThicknessCells) / 2

    /** Whether cross-section cube (j up, k across) is steel. */
    fun inSection(j: Int, k: Int): Boolean =
        j in 0 until depthCells && k in 0 until flangeWidthCells &&
            (j < flangeThicknessCells || j >= depthCells - flangeThicknessCells || k in webStart until webStart + webThicknessCells)

    fun cells(): Set<LatticeIndex> = buildSet {
        for (i in 0 until lengthCells) for (j in 0 until depthCells) for (k in 0 until flangeWidthCells) {
            if (inSection(j, k)) add(LatticeIndex(i, j, k))
        }
    }

    private val sectionCells: List<Pair<Int, Int>>
        get() = (0 until depthCells).flatMap { j -> (0 until flangeWidthCells).filter { inSection(j, it) }.map { j to it } }

    val area: Double get() = sectionCells.size * spacing * spacing

    /** Height of the neutral axis above the bottom face (mid-depth: the section is symmetric). */
    val neutralAxis: Double get() = depth / 2

    /** The second moment of area about the horizontal neutral axis, for the steel that fills
     * the cubes: each cube's own `a^4/12` plus `a^2 * d^2`. */
    val secondMomentOfArea: Double
        get() = sectionCells.sumOf { (j, _) ->
            val d = (j + 0.5) * spacing - neutralAxis
            spacing * spacing * d * d + spacing * spacing * spacing * spacing / 12.0
        }
}

/**
 * A weight resting on the beam, of [tonnes], its weight shared evenly by [nodeIds] (the top-face
 * nodes under it). It's lowered on gradually - its weight rises smoothly from nothing over
 * [rampSeconds] - since a weight dropped on at once rings the beam and briefly stresses it to
 * twice what it settles at. Live-editable: `tonnes`.
 */
class RestingWeight(
    nodeIds: Collection<Int>,
    tonnes: Double,
    private val rampSeconds: Double,
    override val name: String? = "weight",
) : Force, EditableFields {
    private val ids = nodeIds.toList()
    var tonnes: Double = tonnes
        private set

    /** The downward force at time [t], newtons. */
    fun weightAt(t: Double): Double = tonnes * 1000.0 * STANDARD_GRAVITY * ramp(t, rampSeconds)

    override fun editableFields(): Map<String, FieldValue> = mapOf("tonnes" to FieldValue.Scalar(tonnes))

    override fun setField(field: String, value: FieldValue): Boolean {
        if (field != "tonnes" || value !is FieldValue.Scalar || !value.value.isFinite() || value.value < 0.0) return false
        tonnes = value.value
        return true
    }

    override fun accumulate(store: ParticleStore, groups: Groups, t: Double, chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int) {
        if (chunkIndex != 0) return
        val each = Vector3(0.0, -weightAt(t) / ids.size, 0.0)
        for (id in ids) chunk.add(store.slotOf(id), each)
    }
}

/** The beam's own weight, from the real steel each node stands for (not from the nodes' mass,
 * which is scaled up for the time step - see [ElasticLattice]), lowered on with the same ramp. */
class SelfWeight(
    private val newtonsById: Map<Int, Double>,
    private val rampSeconds: Double,
    override val name: String? = "self weight",
) : Force {
    val total: Double = newtonsById.values.sum()

    override fun accumulate(store: ParticleStore, groups: Groups, t: Double, chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int) {
        if (chunkIndex != 0) return
        val r = ramp(t, rampSeconds)
        for ((id, n) in newtonsById) chunk.add(store.slotOf(id), Vector3(0.0, -n * r, 0.0))
    }
}

/** 0 to 1 over [seconds], smoothly (zero slope at both ends). */
private fun ramp(t: Double, seconds: Double): Double {
    if (seconds <= 0.0) return 1.0
    val f = (t / seconds).coerceIn(0.0, 1.0)
    return f * f * (3.0 - 2.0 * f)
}

/**
 * A steel I-beam ([shape]) built as an [ElasticLattice], simply supported: the bottom edge of one
 * end is pinned (held in place, free to turn), the bottom edge of the other sits on a roller (held
 * up and kept from drifting sideways, free to slide along the beam as it bends). Weights rest on
 * the top flange, each centred at one of [weightPositions] (fractions of the length) and spread
 * over [weightLengthCells] cubes along the beam and the full flange width, sharing [tonnes]
 * between them.
 *
 * Masses are scaled for a time step of [dt] ([ElasticLattice.stableNodeMass]), which leaves the
 * settled stress and sag those of real steel but makes the beam sway far more slowly than real
 * steel would - the motion here is only the beam settling, not a real vibration. Linear [Drag]
 * damps the first bending mode near critically, and spring damping the fast ones, so it comes to
 * rest within a few of its (slowed) sways. Loads ramp on over [rampSeconds], by default two sways.
 */
class IBeam(
    val shape: IBeamShape = IBeamShape(),
    val origin: Vector3 = Vector3.ZERO,
    tonnes: Double = 150.0,
    weightPositions: List<Double> = listOf(0.5),
    weightLengthCells: Int = 6,
    selfWeight: Boolean = true,
    val dt: Double = IBEAM_DT,
    youngsModulus: Double = STEEL_YOUNGS_MODULUS,
    density: Double = STEEL_DENSITY,
    rampSeconds: Double? = null,
) {
    val store = ParticleStore()
    val groups = Groups()
    val nodeMass = ElasticLattice.stableNodeMass(youngsModulus, shape.spacing, dt)

    val lattice = ElasticLattice(
        store, groups, BEAM_GROUP, shape.cells(), shape.spacing, origin, youngsModulus, nodeMass,
        stiffnessDamping = 0.6 * dt, name = "steel",
    )

    /** The first bending mode's angular frequency at the scaled mass (simply supported beam
     * theory, ignoring shear): what the damping and the load ramp are tuned to. */
    val firstModeOmega: Double = run {
        val massPerLength = lattice.nodeIds.size * nodeMass / shape.length
        (PI / shape.length) * (PI / shape.length) * sqrt(youngsModulus * shape.secondMomentOfArea / massPerLength)
    }
    val rampSeconds: Double = rampSeconds ?: (2.0 * 2.0 * PI / firstModeOmega)

    /** The top-face nodes under each weight. */
    val weightNodes: List<List<Int>> = weightPositions.map { at ->
        val centre = kotlin.math.round(at * shape.lengthCells).toInt()
        val from = centre - weightLengthCells / 2
        lattice.nodeIds.filter { id ->
            val n = lattice.indexOf(id)!!
            n.j == shape.depthCells && n.i in from..(from + weightLengthCells)
        }
    }

    val weight = RestingWeight(weightNodes.flatten(), tonnes, this.rampSeconds)
    val selfWeightForce: SelfWeight? = if (selfWeight) {
        SelfWeight(lattice.nodeIds.associateWith { lattice.nodeVolume(it) * density * STANDARD_GRAVITY }, this.rampSeconds)
    } else null

    private fun bottomEdgeAt(i: Int): List<Int> = lattice.nodeIds.filter { val n = lattice.indexOf(it)!!; n.i == i && n.j == 0 }

    val pinnedNodes: List<Int> = bottomEdgeAt(0).onEach { groups.add(PINNED_GROUP, it) }
    val rollerNodes: List<Int> = bottomEdgeAt(shape.lengthCells).onEach { groups.add(ROLLER_GROUP, it) }

    val forces: List<Force> = listOfNotNull(
        lattice,
        Drag(BEAM_GROUP, coefficient = 2.0 * firstModeOmega * nodeMass, name = "settling damping"),
        weight,
        selfWeightForce,
    )
    val constraints: List<Constraint> = listOf(
        FixedPosition.atCurrentPositions(PINNED_GROUP, store, groups, name = "pinned support"),
        FixedAxis(ROLLER_GROUP, Vector3(0.0, 1.0, 0.0), store, groups, name = "roller support"),
        FixedAxis(ROLLER_GROUP, Vector3(0.0, 0.0, 1.0), store, groups),
    )

    companion object {
        const val BEAM_GROUP = "beam"
        const val PINNED_GROUP = "pinned support"
        const val ROLLER_GROUP = "roller support"
    }
}
