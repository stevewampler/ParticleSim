package particlesim.golden

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.Integrator
import particlesim.physics.NBodyGravity
import kotlin.test.Test

/**
 * §15.2 golden-file scenario: a small three-body N-body configuration, sampled at a few
 * times over a short run. The flag (§7.3) and ball-bounce (§12.6) scenarios §15.2 also names
 * aren't buildable yet (surfaces/collision are Phase 4/5) — this is the one already
 * exercisable now; add the others as their phases land.
 */
class NBodyGoldenTest {

    private fun runScenario(): List<GoldenFile.Sample> {
        val store = ParticleStore()
        val groups = Groups()
        val p0 = store.create(position = Vector3(0.0, 0.0, 0.0), mass = ScalarExpr.of(100.0))
        val p1 = store.create(position = Vector3(5.0, 0.0, 0.0), velocity = Vector3(0.0, 3.0, 0.0), mass = ScalarExpr.of(10.0))
        val p2 = store.create(position = Vector3(0.0, 7.0, 0.0), velocity = Vector3(-2.0, 0.0, 1.0), mass = ScalarExpr.of(1.0))
        groups.add("bodies", p0)
        groups.add("bodies", p1)
        groups.add("bodies", p2)

        val gravity = NBodyGravity("bodies", g = 1.0, softening = 1e-3)
        val integrator = Integrator()
        val labeled = listOf("p0" to p0, "p1" to p1, "p2" to p2)

        // Sample on exact step counts, not by comparing accumulated float time against a
        // target — `t += dt` drifts over many steps, and comparing against that drifted `t`
        // would make "did we reach the sample point yet" a float-tolerance question instead
        // of an exact one.
        val dt = 1e-3
        val stepsPerSample = 250 // 0.25 time units per sample at dt = 1e-3
        val sampleCount = 4
        var t = 0.0
        val samples = ArrayList<GoldenFile.Sample>()

        for (sampleIndex in 1..sampleCount) {
            repeat(stepsPerSample) {
                integrator.step(store, groups, listOf(gravity), emptyList(), t, dt)
                t += dt
            }
            samples += sampleParticles(store, sampleIndex * stepsPerSample * dt, labeled)
        }
        return samples
    }

    @Test
    fun `three-body scenario matches checked-in golden reference`() {
        GoldenFile.assertMatchesReference("nbody-three-body", runScenario())
    }
}
