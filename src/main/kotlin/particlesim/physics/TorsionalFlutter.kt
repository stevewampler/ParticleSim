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
 * **Frozen frequency.** [frequency] `f` is fixed (the structure's torsion frequency), the usual
 * time-domain simplification of frequency-domain derivatives. The moment is a damping-like term,
 * so its effect on a mode scales as `1/ω` of that mode: of two torsion modes the slower one is
 * destabilized more and goes unstable first.
 *
 * **Saturation.** Real H-section flutter settles into a large limit cycle rather than growing
 * forever (and Tacoma's ended when the structure failed, which isn't modelled here). A positive
 * A2* is scaled by `1 − (α/αs)²` for the section's tilt α ([saturationTwist] αs): it feeds energy
 * in at small twist and takes it out beyond αs. Over a sinusoidal cycle of amplitude A the
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
    private val strips: List<Strip>,
    private val windVelocity: (Double) -> Vector3,
    private var frequency: Double,
    private var density: Double = 1.0,
    private var slope: Double = 0.1,
    private var criticalReducedVelocity: Double = 5.0,
    private var saturationTwist: Double = 0.5,
    /** Unit vector along the deck's span. */
    private val spanAxis: Vector3 = Vector3(0.0, 0.0, 1.0),
    override val name: String? = null,
) : Force, EditableFields {
    data class Strip(val left: Int, val right: Int, val spanLength: Double)

    override fun editableFields(): Map<String, FieldValue> = mapOf(
        "frequency" to FieldValue.Scalar(frequency),
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
    fun moment(store: ParticleStore, strip: Strip, t: Double): Double = section(store, strip, windVelocity(t))?.moment ?: 0.0

    private class Section(val moment: Double, val chord: Double, val normal: Vector3)

    private fun section(store: ParticleStore, strip: Strip, wind: Vector3): Section? {
        val d = store.position(strip.right) - store.position(strip.left)
        // The cross-section plane is perpendicular to the span; work within it, so a section
        // twisted through any angle (even past vertical) still has a well-defined twist.
        val inPlane = d - spanAxis * d.dot(spanAxis)
        val chord = inPlane.length()
        if (chord <= 0.0) return null
        val across = inPlane * (1.0 / chord)
        // A quarter-turn from `across` within the section plane: the direction the right edge moves
        // when the section twists positively (+y for a level deck spanning +z, right edge at +x).
        val normal = spanAxis.cross(across)
        val crossWind = wind - spanAxis * wind.dot(spanAxis)
        val u = sqrt(crossWind.x * crossWind.x + crossWind.z * crossWind.z)
        val vr = u / (frequency * chord)
        var a2 = a2(vr)
        val twist = asin(across.y.coerceIn(-1.0, 1.0))
        if (a2 > 0.0) a2 *= 1.0 - (twist / saturationTwist) * (twist / saturationTwist)
        val twistRate = (store.velocity(strip.right) - store.velocity(strip.left)).dot(normal) / chord
        val b2 = chord * chord
        return Section(0.5 * density * b2 * b2 * (2.0 * PI * frequency) * a2 * twistRate * strip.spanLength, chord, normal)
    }

    override fun accumulate(
        store: ParticleStore, groups: Groups, t: Double,
        chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
    ) {
        val wind = windVelocity(t)
        var i = chunkIndex
        while (i < strips.size) {
            val strip = strips[i]
            val section = section(store, strip, wind)
            if (section != null && section.moment != 0.0) {
                // A pure couple: ±M/chord on the two edges, perpendicular to the chord.
                val f = section.normal * (section.moment / section.chord)
                chunk.add(store.slotOf(strip.right), f)
                chunk.add(store.slotOf(strip.left), f * -1.0)
            }
            i += chunkCount
        }
    }
}
