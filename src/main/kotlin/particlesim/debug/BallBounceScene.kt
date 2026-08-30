package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.BALL_BOUNCE_DT
import particlesim.examples.buildBallBounce
import particlesim.physics.Integrator

/**
 * §9.6 scene-library wrapping of [BallBounceDebugDemo]'s worked example - see that file's own
 * doc comment for the re-drop cycle's reasoning (unchanged here, just moved from local `var`s
 * to instance fields so it survives across [step] calls within the same scene instance).
 */
class BallBounceScene : DemoScene {
    private val scenario = buildBallBounce()
    private val dropPosition = scenario.store.position(scenario.ballId)
    private val cycleSeconds = 8.0
    private val integrator = Integrator()
    private var cycleStart = 0.0
    private val ids = listOf(scenario.ballId)

    override val dt = BALL_BOUNCE_DT
    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = ids

    override fun step(t: Double) {
        if (t - cycleStart >= cycleSeconds) {
            scenario.store.setPosition(scenario.ballId, dropPosition)
            scenario.store.setVelocity(scenario.ballId, Vector3.ZERO)
            cycleStart = t
        }
        integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, dt)
        scenario.collisions.resolve(scenario.store, scenario.groups, t, dt)
    }

    override fun frame(t: Double): SceneFrame = SceneFrame()
}
