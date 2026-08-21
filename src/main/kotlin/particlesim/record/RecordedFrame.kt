package particlesim.record

import particlesim.core.Vector3

data class RecordedParticle(val id: Int, val position: Vector3, val velocity: Vector3)

/** One simulation frame as read back from a recording (§9.2). [step] is the integrator step
 * index at which this frame was captured, [t] the simulation time. */
data class RecordedFrame(val step: Long, val t: Double, val particles: List<RecordedParticle>)
