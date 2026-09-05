package particlesim.golden

import particlesim.examples.BALL_BOUNCE_DT
import particlesim.physics.Integrator
import particlesim.yaml.YamlLoader
import kotlin.test.Test

/**
 * Proves §4's "both front-ends build the same in-memory model" for the ball-bounce scenario,
 * the same way [FlagYamlParityTest] does for the flag: loads
 * `src/main/resources/yaml/ball_bounce.yaml` (hand-written to match [particlesim.examples.buildBallBounce]'s
 * exact defaults), runs it through [BallBounceGoldenTest]'s identical sampling logic, and
 * asserts the result matches the *same* checked-in `ball_bounce.golden.txt`.
 */
class BallBounceYamlParityTest {

    private fun runScenario(): List<GoldenFile.Sample> {
        val yaml = javaClass.getResourceAsStream("/yaml/ball_bounce.yaml")
            ?: throw AssertionError("test resource /yaml/ball_bounce.yaml not found on the classpath")
        val scenario = YamlLoader().load(yaml.bufferedReader().readText())
        val integrator = Integrator()

        val ballId = scenario.groups.membersOf("ball").single()
        val labeled = listOf("ball" to ballId)

        val stepsPerSample = 500 // matches BallBounceGoldenTest
        val sampleCount = 4
        var t = 0.0
        val samples = ArrayList<GoldenFile.Sample>()

        for (sampleIndex in 1..sampleCount) {
            repeat(stepsPerSample) {
                integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, BALL_BOUNCE_DT)
                scenario.collisionSystem!!.resolve(scenario.store, scenario.groups, t, BALL_BOUNCE_DT)
                t += BALL_BOUNCE_DT
            }
            samples += sampleParticles(scenario.store, sampleIndex * stepsPerSample * BALL_BOUNCE_DT, labeled)
        }
        return samples
    }

    @Test
    fun `YAML-loaded ball bounce scenario matches the same golden reference as the Kotlin-built one`() {
        GoldenFile.assertMatchesReference("ball_bounce", runScenario())
    }
}
