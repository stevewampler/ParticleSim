package particlesim.golden

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.SPARKS_DT
import particlesim.physics.Integrator
import particlesim.yaml.YamlLoader
import kotlin.test.Test

/**
 * Proves §4's "both front-ends build the same in-memory model" for the spark-fountain scenario,
 * the same way [FlagYamlParityTest] does for the flag: loads `src/test/resources/yaml/sparks.yaml`
 * (hand-written to match [particlesim.examples.buildSparks]'s exact parameters and force
 * order), runs it through [SparksGoldenTest]'s identical per-step loop order
 * (`integrator.step` → `destruction.resolve` → `emitter.update`) and aggregate-sample function,
 * and asserts the result matches the *same* checked-in `sparks.golden.txt`.
 */
class SparksYamlParityTest {

    private fun aggregateSample(store: ParticleStore, t: Double): GoldenFile.Sample {
        val ids = store.liveIds()
        val meanPos = if (ids.isEmpty()) Vector3.ZERO else ids.fold(Vector3.ZERO) { acc, id -> acc + store.position(id) } * (1.0 / ids.size)
        val meanVel = if (ids.isEmpty()) Vector3.ZERO else ids.fold(Vector3.ZERO) { acc, id -> acc + store.velocity(id) } * (1.0 / ids.size)
        return GoldenFile.Sample(t, "alive=${ids.size}", meanPos, meanVel)
    }

    private fun runScenario(): List<GoldenFile.Sample> {
        val yaml = javaClass.getResourceAsStream("/yaml/sparks.yaml")
            ?: throw AssertionError("test resource /yaml/sparks.yaml not found on the classpath")
        val scenario = YamlLoader().load(yaml.bufferedReader().readText())
        val emitter = scenario.emitters.single()
        val destruction = scenario.destruction!!
        val integrator = Integrator()

        val stepsPerSample = 250 // matches SparksGoldenTest, 0.25s per sample at SPARKS_DT = 1e-3
        val sampleCount = 4
        var t = 0.0
        val samples = ArrayList<GoldenFile.Sample>()

        for (sampleIndex in 1..sampleCount) {
            repeat(stepsPerSample) {
                integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, SPARKS_DT)
                destruction.resolve(scenario.store, scenario.groups, scenario.forces, t, SPARKS_DT)
                emitter.update(scenario.store, scenario.groups, t, SPARKS_DT)
                t += SPARKS_DT
            }
            samples += aggregateSample(scenario.store, sampleIndex * stepsPerSample * SPARKS_DT)
        }
        return samples
    }

    @Test
    fun `YAML-loaded sparks scenario matches the same golden reference as the Kotlin-built one`() {
        GoldenFile.assertMatchesReference("sparks", runScenario())
    }
}
