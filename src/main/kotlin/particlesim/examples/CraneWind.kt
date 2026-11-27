package particlesim.examples

import particlesim.core.Vector3
import particlesim.physics.Truss
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The wind a crane works in, as the crane scenes set it: a mean [speed] at 10 m above the ground,
 * blowing toward [direction] degrees (0 along +x, positive turning +x toward -z, as the cranes
 * slew), with [gusts] or without. What [velocity] gives is fed to the crane's
 * [particlesim.physics.TrussWind], which makes it blow harder higher up.
 *
 * A new speed or direction is reached gradually - the speed at [SPEED_RAMP] m/s each second, the
 * direction at [TURN_RATE] degrees a second the short way round - so moving a slider doesn't hit
 * the crane with a step load. [advance] does that, once a step.
 *
 * Gusts are a pure function of time (a sum of three incommensurate sine waves, no random numbers,
 * so a run repeats exactly): the speed swings up to [GUST_INTENSITY] either side of the mean, and
 * the direction wanders a few degrees.
 */
class CraneWind {
    /** The mean speed it's heading for, m/s at 10 m. */
    var speedTarget: Double = 0.0
        set(value) { require(value >= 0.0 && value.isFinite()) { "wind speed must be non-negative, was $value" }; field = value }
    /** The direction it's heading for, degrees. */
    var directionTarget: Double = 0.0
        set(value) { require(value.isFinite()); field = value }
    var gusts: Boolean = false

    /** The mean speed blowing now, m/s at 10 m. */
    var speed: Double = 0.0
        private set
    /** The mean direction now, degrees. */
    var direction: Double = 0.0
        private set

    /** Starts at [speed] toward [direction], already there. */
    fun set(speed: Double, direction: Double = this.direction) {
        speedTarget = speed; directionTarget = direction
        this.speed = speed; this.direction = direction
    }

    fun advance(dt: Double) {
        val ds = speedTarget - speed
        speed = if (abs(ds) <= SPEED_RAMP * dt) speedTarget else speed + sign(ds) * SPEED_RAMP * dt
        // The short way round.
        val dd = ((directionTarget - direction) % 360 + 540) % 360 - 180
        direction = if (abs(dd) <= TURN_RATE * dt) directionTarget else direction + sign(dd) * TURN_RATE * dt
    }

    /** The gusts' swing about the mean, -1..1, at time [t]. */
    fun gust(t: Double): Double =
        (sin(2 * PI * t / 11.3) + 0.7 * sin(2 * PI * t / 4.7 + 1.3) + 0.5 * sin(2 * PI * t / 2.3 + 0.4)) / 2.2

    /** The speed at 10 m at time [t], gusts and all. */
    fun speedAt(t: Double): Double = speed * (if (gusts) 1 + GUST_INTENSITY * gust(t) else 1.0)

    /** The wind at 10 m at time [t]. */
    fun velocity(t: Double): Vector3 {
        val s = speedAt(t)
        if (s == 0.0) return Vector3.ZERO
        val degrees = direction + (if (gusts) GUST_WANDER * sin(2 * PI * t / 17.0 + 0.7) else 0.0)
        val theta = degrees * PI / 180
        return Vector3(cos(theta), 0.0, -sin(theta)) * s
    }

    companion object {
        /** m/s each second. */
        const val SPEED_RAMP = 2.0
        /** Degrees a second. */
        const val TURN_RATE = 15.0
        /** How far gusts take the speed either side of the mean, as a fraction of it - a peak gust
         * of 1.35 times the mean. */
        const val GUST_INTENSITY = 0.35
        /** Degrees the direction wanders in gusts. */
        const val GUST_WANDER = 8.0

        /** The wind profile's power-law exponent: the classic 1/7 for open country. */
        const val PROFILE_EXPONENT = 1.0 / 7

        /** Drag coefficient of a round tube or a wire rope in cross-flow (below the drag crisis). */
        const val CD_CYLINDER = 1.2
        /** Drag coefficient of a squat block - a ballast stack, a cab, a crate. */
        const val CD_BLOCK = 1.1
        /** Wire rope's steel area over its diameter squared (16 mm rope has 1.2 cm², 20 mm 1.9). */
        const val ROPE_FILL = 0.47

        /** `Cd·D` for a member of [area] taken as the round tube its buckling assumes. */
        fun tubeDragWidth(area: Double, diameterOverThickness: Double = 20.0): Double =
            CD_CYLINDER * Truss.tubeDiameter(area, diameterOverThickness)

        /** `Cd·D` for [falls] falls of rope with [area] of steel between them, side by side. */
        fun ropeDragWidth(area: Double, falls: Int): Double =
            CD_CYLINDER * falls * sqrt(area / falls / ROPE_FILL)

        /** `Cd·A` for a block [length] by [width] (in plan) by [height]: the mean of its two sides,
         * since the wind may come from any side. */
        fun blockDragArea(length: Double, width: Double, height: Double): Double =
            CD_BLOCK * (length + width) / 2 * height
    }
}

/**
 * The crate a crane scene hangs on its hook: [length] by [width] in plan, as deep as its tonnes
 * need at [density] t/m³ (between 0.25 m and [maxDepth]) - shared by the scene that draws it and the
 * crane that feels the wind on it.
 */
data class LoadCrate(val length: Double, val width: Double, val density: Double, val maxDepth: Double = 3.0) {
    fun depth(tonnes: Double): Double = (tonnes / (density * length * width)).coerceIn(0.25, maxDepth)

    /** `Cd·A` of the crate carrying [tonnes] - nothing with no load. */
    fun dragArea(tonnes: Double): Double = if (tonnes <= 0.0) 0.0 else CraneWind.blockDragArea(length, width, depth(tonnes))
}
