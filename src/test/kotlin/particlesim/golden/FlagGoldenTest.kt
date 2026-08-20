package particlesim.golden

import particlesim.examples.FLAG_DT
import particlesim.examples.buildFlag
import particlesim.physics.Integrator
import kotlin.test.Test

/**
 * §7.3 + §15.2 golden-file scenario: the flag worked example, sampled at a few times. Only
 * 3 named vertices are sampled, not the whole ~100-particle sheet — a pinned one (sanity
 * check that pinning holds, though it never moves), the free corner (farthest from the pole,
 * moves the most), and one mid-sheet vertex.
 */
class FlagGoldenTest {

    private fun runScenario(): List<GoldenFile.Sample> {
        val scenario = buildFlag(rows = 8, cols = 14)
        val integrator = Integrator()

        val poleTop = scenario.grid[0][0]
        val freeCorner = scenario.grid.last().last()
        val midSheet = scenario.grid[4][7]
        val labeled = listOf("pole-top" to poleTop, "free-corner" to freeCorner, "mid-sheet" to midSheet)

        // Sample on exact step counts, not accumulated float time (see NBodyGoldenTest).
        val stepsPerSample = 250 // 0.25s per sample at FLAG_DT = 1e-3
        val sampleCount = 4
        var t = 0.0
        val samples = ArrayList<GoldenFile.Sample>()

        for (sampleIndex in 1..sampleCount) {
            repeat(stepsPerSample) {
                integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, FLAG_DT)
                t += FLAG_DT
            }
            samples += sampleParticles(scenario.store, sampleIndex * stepsPerSample * FLAG_DT, labeled)
        }
        return samples
    }

    @Test
    fun `flag scenario matches checked-in golden reference`() {
        GoldenFile.assertMatchesReference("flag", runScenario())
    }
}
