package particlesim.golden

import particlesim.examples.BALL_BOUNCE_DT
import particlesim.examples.buildBallBounce
import particlesim.physics.Integrator
import kotlin.test.Test

/**
 * §12.6 + §15.2 golden-file scenario: the ball-bounce worked example, sampled at a few times
 * spanning at least one real bounce off the floor (drop height 5.0m under standard gravity
 * reaches the floor at roughly t=1.01s; four 0.5s-spaced samples out to t=2.0s covers the first
 * bounce and part of the second). Only the ball itself is sampled — a single, statically-known
 * id (unlike [SparksGoldenTest]'s aggregate sampling, this scenario has exactly one particle
 * whose id never changes), matching [FlagGoldenTest]/[NBodyGoldenTest]'s per-id style.
 */
class BallBounceGoldenTest {

    private fun runScenario(): List<GoldenFile.Sample> {
        val scenario = buildBallBounce()
        val integrator = Integrator()
        val labeled = listOf("ball" to scenario.ballId)

        val stepsPerSample = 500 // 0.5s per sample at BALL_BOUNCE_DT = 1e-3
        val sampleCount = 4
        var t = 0.0
        val samples = ArrayList<GoldenFile.Sample>()

        for (sampleIndex in 1..sampleCount) {
            repeat(stepsPerSample) {
                integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, BALL_BOUNCE_DT)
                scenario.collisions.resolve(scenario.store, scenario.groups, t, BALL_BOUNCE_DT)
                t += BALL_BOUNCE_DT
            }
            samples += sampleParticles(scenario.store, sampleIndex * stepsPerSample * BALL_BOUNCE_DT, labeled)
        }
        return samples
    }

    @Test
    fun `ball bounce scenario matches checked-in golden reference`() {
        GoldenFile.assertMatchesReference("ball_bounce", runScenario())
    }
}
