package particlesim.golden

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.SPARKS_DT
import particlesim.examples.buildSparks
import particlesim.physics.Integrator
import kotlin.test.Test

/**
 * §14's emitter-heavy golden-file scenario (§15.2). Unlike the flag/N-body scenarios, sampling
 * "a few named particles" by id doesn't work here — which particles are still alive at a given
 * sample time depends on randomly-drawn lifetimes and where each one happened to land, so a
 * particle chosen up front has no guarantee of surviving to the next sample. Sampling *live
 * count* plus *mean position/velocity* instead is well-defined at every sample time regardless
 * of individual particle lifecycles, and any regression in spawn/destroy logic still moves
 * these numbers (a broken RNG mix, a destroy condition that fires too early/late, an off-by-one
 * in the accumulator — all shift the population's aggregate state, not just one particle's).
 */
class SparksGoldenTest {

    private fun aggregateSample(store: ParticleStore, t: Double): GoldenFile.Sample {
        val ids = store.liveIds()
        val meanPos = if (ids.isEmpty()) Vector3.ZERO else ids.fold(Vector3.ZERO) { acc, id -> acc + store.position(id) } * (1.0 / ids.size)
        val meanVel = if (ids.isEmpty()) Vector3.ZERO else ids.fold(Vector3.ZERO) { acc, id -> acc + store.velocity(id) } * (1.0 / ids.size)
        return GoldenFile.Sample(t, "alive=${ids.size}", meanPos, meanVel)
    }

    private fun runScenario(): List<GoldenFile.Sample> {
        val scenario = buildSparks(masterSeed = 1L)
        val integrator = Integrator()

        val stepsPerSample = 250 // 0.25s per sample at SPARKS_DT = 1e-3
        val sampleCount = 4
        var t = 0.0
        val samples = ArrayList<GoldenFile.Sample>()

        for (sampleIndex in 1..sampleCount) {
            repeat(stepsPerSample) {
                integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, SPARKS_DT)
                scenario.destruction.resolve(scenario.store, scenario.groups, scenario.forces, t, SPARKS_DT)
                scenario.emitter.update(scenario.store, scenario.groups, t, SPARKS_DT)
                t += SPARKS_DT
            }
            samples += aggregateSample(scenario.store, sampleIndex * stepsPerSample * SPARKS_DT)
        }
        return samples
    }

    @Test
    fun `spark fountain scenario matches checked-in golden reference`() {
        GoldenFile.assertMatchesReference("sparks", runScenario())
    }
}
