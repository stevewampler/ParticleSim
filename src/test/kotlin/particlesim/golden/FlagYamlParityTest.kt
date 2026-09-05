package particlesim.golden

import particlesim.physics.Integrator
import particlesim.yaml.YamlLoader
import kotlin.test.Test

/**
 * Proves §4's "both front-ends build the same in-memory model" for the flag scenario, rather
 * than just asserting it: loads `src/main/resources/yaml/flag.yaml` (hand-written to match
 * [particlesim.examples.buildFlag]'s exact parameters and force order — force accumulation
 * order affects the bit-pattern of a floating-point sum, so this isn't just "the same values,"
 * it's "the same sequence of operations"), runs it through
 * [particlesim.golden.FlagGoldenTest]'s identical sampling logic, and asserts the result
 * matches the *same* checked-in `flag.golden.txt` the Kotlin-DSL-built flag already produces.
 * If this ever needs its own separate reference file, the two front-ends have diverged.
 */
class FlagYamlParityTest {

    private fun runScenario(): List<GoldenFile.Sample> {
        val yaml = javaClass.getResourceAsStream("/yaml/flag.yaml")
            ?: throw AssertionError("test resource /yaml/flag.yaml not found on the classpath")
        val scenario = YamlLoader().load(yaml.bufferedReader().readText())
        val integrator = Integrator()

        val grid = scenario.grids.getValue("cloth")
        val poleTop = grid[0][0]
        val freeCorner = grid.last().last()
        val midSheet = grid[4][7]
        val labeled = listOf("pole-top" to poleTop, "free-corner" to freeCorner, "mid-sheet" to midSheet)

        val dt = 1e-3 // matches particlesim.examples.FLAG_DT
        val stepsPerSample = 250
        val sampleCount = 4
        var t = 0.0
        val samples = ArrayList<GoldenFile.Sample>()

        for (sampleIndex in 1..sampleCount) {
            repeat(stepsPerSample) {
                integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, dt)
                t += dt
            }
            samples += sampleParticles(scenario.store, sampleIndex * stepsPerSample * dt, labeled)
        }
        return samples
    }

    @Test
    fun `YAML-loaded flag scenario matches the same golden reference as the Kotlin-built one`() {
        GoldenFile.assertMatchesReference("flag", runScenario())
    }
}
