package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Self-excited aerodynamic twisting moment on a bridge deck - the force behind torsional
 * flutter, which the plain [Wind] pressure force can't produce (§7.2): [Wind] pushes every
 * point of a surface by the same rule regardless of how fast the section is twisting, so on a
 * symmetric deck it never feeds energy into a twist. Real bluff decks (the 1940 Tacoma Narrows
 * H-section above all) shed vortices off their edges in step with the twisting, which produces
 * a moment *in phase with the twist rate* - negative aerodynamic damping - once the wind is fast
 * enough relative to the twisting.
 *
 * **Model.** Scanlan's flutter-derivative form, keeping only the torsional damping term (single-
 * degree-of-freedom torsional flutter, the Tacoma mechanism). Per unit span:
 *
 * `M = ½ ρ U² B² · K A2*(K) · B α̇ / U`, with reduced frequency `K = Bω / U`,
 *
 * which simplifies to `M = ½ ρ B⁴ ω A2*(Vr) α̇` - U appears only through `A2*`'s dependence on the
 * reduced velocity `Vr = U / (f B)`. That's why A2* *must* vary with Vr: a constant A2* would give
 * a wind-independent damping and no critical speed. Here
 *
 * `A2*(Vr) = slope · (Vr − Vr₀) · min(1, Vr / Vr₀)`,
 *
 * negative (stabilizing) below the zero crossing [criticalReducedVelocity] `Vr₀`, growing
 * positive above it, and zero in still air (so it adds nothing when there's no wind). The shape
 * - stabilizing at low reduced velocity, destabilizing above a crossing - is that of measured
 * H-section derivatives; the numbers are **fitted, not measured** (see the scene that uses it).
 *
 * **Which frequency.** Flutter derivatives are measured per frequency, but a time-domain force
 * sees a mix of modes. Two options:
 * - **Frozen** (no [modes]): one [frequency] `f` for everything - the usual simplification.
 *   Every twisting pattern then sees the same `Vr` and `A2*`, so different twist modes go unstable
 *   at almost the same wind and only their starting conditions decide which one develops.
 * - **Per mode** ([modes]): the strips' twist rates are projected onto the given mode shapes each
 *   step (a least-squares fit, `q̇ₙ = Σ φₙᵢ ℓᵢ α̇ᵢ / Σ φₙᵢ² ℓᵢ`), and each mode's part of the motion
 *   gets the moment for its *own* frequency `fₙ` and so its own `Vr = U/(fₙB)`; whatever the modes
 *   don't capture uses [frequency]. A stiffer, higher-frequency mode sits at a lower reduced
 *   velocity than a softer one in the same wind, so it goes unstable at a higher wind - the
 *   physical reason one mode wins. Still stateless: every chunk redoes the (small) projection in
 *   the same fixed order, so it stays deterministic.
 *
 * **Saturation.** Real H-section flutter settles into a large limit cycle rather than growing
 * forever (and Tacoma's ended when the structure failed, which isn't modelled here). A positive
 * A2* is scaled by `1 − (α/αs)²` for the section's tilt α ([saturationTwist] αs): it feeds energy
 * in at small twist and takes it out beyond αs (the factor bottoms out at −1). Over a sinusoidal cycle of amplitude A the
 * aerodynamic work balances at `A = 2αs·√(1 − A2*crit/A2*)`, where `A2*crit` is the value that
 * just cancels the structural damping - so the twist levels off at a finite amplitude of up to
 * `2αs` instead of running away.
 *
 * Each [Strip] is one deck cross-section: two particles on the edges of the deck (the moment is
 * applied as a pure vertical couple between them - zero net force) and the span length it
 * stands for. `U` is the horizontal component of [windVelocity] across the deck, so pass the
 * scene's own [Wind]'s velocity and a single live-editable wind expression drives both.
 *
 * Stateless: twist and twist rate come from the current positions and velocities each step, so
 * it chunks and checkpoints like every other force.
 */
class TorsionalFlutter(
    strips: List<Strip>,
    private val windVelocity: (Double) -> Vector3,
    private var frequency: Double,
    private var density: Double = 1.0,
    private var slope: Double = 0.1,
    private var criticalReducedVelocity: Double = 5.0,
    private var saturationTwist: Double = 0.5,
    /** Unit vector along the deck's span. */
    private val spanAxis: Vector3 = Vector3(0.0, 0.0, 1.0),
    /** Mode shapes (one value per strip) and their frequencies, for per-mode reduced velocity;
     * empty = frozen [frequency] for everything. */
    modes: List<Mode> = emptyList(),
    override val name: String? = null,
) : Force, EditableFields {
    data class Strip(val left: Int, val right: Int, val spanLength: Double)

    /** A twisting mode: [shape] has one entry per strip, in strip order; [frequency] in Hz. */
    class Mode(val shape: DoubleArray, val frequency: Double)

    init {
        for (mode in modes) require(mode.shape.size == strips.size) { "a mode shape needs one value per strip" }
    }

    private val strips = ArrayList(strips)
    // Every mode's shape spans every strip (zero over strips of other sections).
    private val modes = ArrayList(modes)
    // Per strip: its section's own residual frequency, or null for [frequency].
    private val residualFrequencies = ArrayList<Double?>(List(strips.size) { null })
    private var started = false

    // Each mode's Σ φ² ℓ over the active strips - fixed until a strip is switched off.
    private var stripActive = BooleanArray(strips.size) { true }
    private var modeNorms = computeModeNorms()

    private fun computeModeNorms() = modes.map { m ->
        strips.indices.filter { stripActive[it] }.sumOf { m.shape[it] * m.shape[it] * strips[it].spanLength }
    }

    /**
     * Adds a separately built deck - [strips] with its own [modes] (shapes over these strips
     * only) and [residualFrequency] - to this force, so one force (and one set of editable
     * coefficients) drives several structures in one wind, e.g. two bridges side by side. Each
     * section's modes are zero over every other section's strips, so a section's motion is
     * projected only onto its own modes: the moments are exactly what a separate force per
     * section would give. Returns the index of the first added strip. Only before the first step.
     */
    fun addSection(strips: List<Strip>, modes: List<Mode>, residualFrequency: Double): Int {
        check(!started) { "add sections before the first step" }
        for (mode in modes) require(mode.shape.size == strips.size) { "a mode shape needs one value per strip" }
        val offset = this.strips.size
        val total = offset + strips.size
        for (i in this.modes.indices) this.modes[i] = Mode(this.modes[i].shape.copyOf(total), this.modes[i].frequency)
        for (mode in modes) this.modes += Mode(DoubleArray(total) { if (it >= offset) mode.shape[it - offset] else 0.0 }, mode.frequency)
        this.strips += strips
        repeat(strips.size) { residualFrequencies += residualFrequency }
        stripActive = stripActive.copyOf(total).also { for (i in offset until total) it[i] = true }
        modeNorms = computeModeNorms()
        return offset
    }

    /**
     * Stops applying the moment to strip [index] for good - for a deck section that has torn
     * loose, where the flutter model (aerodynamics of an *intact* deck twisting as a whole)
     * no longer applies: left on, a free fragment's own spin would be fed without limit. Call
     * between steps, not during [accumulate].
     */
    fun isStripActive(index: Int): Boolean = stripActive[index]

    fun deactivateStrip(index: Int) {
        if (!stripActive[index]) return
        stripActive[index] = false
        modeNorms = computeModeNorms()
    }

    // "frequency" only while some strip uses it (one added by [addSection] has its own).
    override fun editableFields(): Map<String, FieldValue> = (if (residualFrequencies.any { it == null }) mapOf("frequency" to FieldValue.Scalar(frequency)) else emptyMap()) + mapOf(
        "density" to FieldValue.Scalar(density),
        "a2Slope" to FieldValue.Scalar(slope),
        "criticalReducedVelocity" to FieldValue.Scalar(criticalReducedVelocity),
        "saturationTwist" to FieldValue.Scalar(saturationTwist),
    )

    override fun setField(field: String, value: FieldValue): Boolean {
        if (value !is FieldValue.Scalar) return false
        val v = value.value
        when (field) {
            "frequency" -> if (v > 0.0) frequency = v else return false
            "density" -> density = v
            "a2Slope" -> slope = v
            "criticalReducedVelocity" -> if (v > 0.0) criticalReducedVelocity = v else return false
            "saturationTwist" -> if (v > 0.0) saturationTwist = v else return false
            else -> return false
        }
        return true
    }

    /** `A2*` at reduced velocity [vr] (before saturation). */
    fun a2(vr: Double): Double = slope * (vr - criticalReducedVelocity) * minOf(1.0, vr / criticalReducedVelocity)

    /** The twisting moment on [strip] right now: positive raises the [Strip.right] edge. */
    fun moment(store: ParticleStore, strip: Strip, t: Double): Double {
        val i = strips.indexOf(strip)
        require(i >= 0) { "not one of this force's strips" }
        return compute(store, windVelocity(t)).moments[i]
    }

    /** Every strip's moment right now, in strip order. */
    fun moments(store: ParticleStore, t: Double): DoubleArray = compute(store, windVelocity(t)).moments

    /** One strip's couple right now, for drawing it: [force] is what the [Strip.right] edge gets
     * (the left gets its negative); [feeding] is whether it's putting energy into the twist
     * (moment and twist rate the same way) rather than taking it out. */
    data class Couple(val strip: Strip, val force: Vector3, val feeding: Boolean)

    fun couples(store: ParticleStore, t: Double): List<Couple> {
        val state = compute(store, windVelocity(t))
        return strips.indices.mapNotNull { i ->
            val normal = state.normals[i] ?: return@mapNotNull null
            Couple(strips[i], normal * (state.moments[i] / state.chords[i]), state.moments[i] * state.rates[i] > 0.0)
        }
    }

    private class State(val moments: DoubleArray, val chords: DoubleArray, val normals: Array<Vector3?>, val rates: DoubleArray)

    private fun compute(store: ParticleStore, wind: Vector3): State {
        val n = strips.size
        val chords = DoubleArray(n)
        val normals = arrayOfNulls<Vector3>(n)
        val rates = DoubleArray(n)
        val tilts = DoubleArray(n)
        for ((i, strip) in strips.withIndex()) {
            if (!stripActive[i]) continue
            val d = store.position(strip.right) - store.position(strip.left)
            // The cross-section plane is perpendicular to the span; work within it, so a section
            // twisted through any angle (even past vertical) still has a well-defined twist.
            val inPlane = d - spanAxis * d.dot(spanAxis)
            val chord = inPlane.length()
            if (chord <= 0.0) continue
            val across = inPlane * (1.0 / chord)
            // A quarter-turn from `across` within the section plane: the direction the right edge
            // moves when the section twists positively (+y for a level deck spanning +z, right
            // edge at +x).
            val normal = spanAxis.cross(across)
            chords[i] = chord
            normals[i] = normal
            tilts[i] = asin(across.y.coerceIn(-1.0, 1.0))
            rates[i] = (store.velocity(strip.right) - store.velocity(strip.left)).dot(normal) / chord
        }
        val crossWind = wind - spanAxis * wind.dot(spanAxis)
        val u = sqrt(crossWind.x * crossWind.x + crossWind.z * crossWind.z)

        // Split each strip's twist rate into per-mode parts plus a residual.
        val modalRates = DoubleArray(modes.size) { m ->
            var sum = 0.0
            for (i in 0 until n) sum += modes[m].shape[i] * strips[i].spanLength * rates[i]
            if (modeNorms[m] > 0.0) sum / modeNorms[m] else 0.0
        }
        val moments = DoubleArray(n)
        for (i in 0 until n) {
            val chord = chords[i]
            if (chord <= 0.0) continue
            // Clamped at -1: past αs√2 the moment damps the twist as strongly as it fed it at
            // small twist, and no more - unbounded, a deck flipped far past αs (e.g. dragged)
            // would get huge moments.
            val saturation = maxOf(-1.0, 1.0 - (tilts[i] / saturationTwist) * (tilts[i] / saturationTwist))
            // ω·A2*(Vr) for a motion component at frequency f, saturated when destabilizing.
            fun gain(f: Double): Double {
                val a2 = a2(u / (f * chord))
                return 2.0 * PI * f * (if (a2 > 0.0) a2 * saturation else a2)
            }
            var residual = rates[i]
            var sum = 0.0
            for (m in modes.indices) {
                val part = modes[m].shape[i] * modalRates[m]
                residual -= part
                sum += gain(modes[m].frequency) * part
            }
            sum += gain(residualFrequencies[i] ?: frequency) * residual
            val b2 = chord * chord
            moments[i] = 0.5 * density * b2 * b2 * sum * strips[i].spanLength
        }
        return State(moments, chords, normals, rates)
    }

    override fun accumulate(
        store: ParticleStore, groups: Groups, t: Double,
        chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
    ) {
        started = true
        val state = compute(store, windVelocity(t))
        var i = chunkIndex
        while (i < strips.size) {
            val m = state.moments[i]
            val normal = state.normals[i]
            if (m != 0.0 && normal != null) {
                // A pure couple: ±M/chord on the two edges, perpendicular to the chord.
                val f = normal * (m / state.chords[i])
                chunk.add(store.slotOf(strips[i].right), f)
                chunk.add(store.slotOf(strips[i].left), f * -1.0)
            }
            i += chunkCount
        }
    }
}
