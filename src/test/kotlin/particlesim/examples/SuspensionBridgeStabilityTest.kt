package particlesim.examples

import particlesim.physics.Integrator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** §13.1/§13.5, mirroring [BridgeStabilityTest]: [buildSuspensionBridge] reuses [BRIDGE_DT] and
 * [buildBridge]'s already-proven deck, but adds a genuinely new coupled system - two sagging
 * main cables plus suspender springs pulling cable and deck toward each other - that needs its
 * own empirical stability check, not just an assumption that reusing proven pieces means their
 * composition is automatically stable too. */
class SuspensionBridgeStabilityTest {

    @Test
    fun `suspension bridge settles and runs through a full load crossing without blowing up`() {
        val scenario = buildSuspensionBridge()
        val integrator = Integrator()

        var t = 0.0
        var maxSpeed = 0.0
        val steps = (10.0 / BRIDGE_DT).toInt() // 10s - cables/deck settling plus a full load crossing

        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, BRIDGE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, BRIDGE_DT)
            t += BRIDGE_DT
            for (id in scenario.store.liveIds()) {
                val speed = scenario.store.velocity(id).length()
                if (speed > maxSpeed) maxSpeed = speed
            }
        }

        assertTrue(maxSpeed < 50.0, "max particle speed $maxSpeed m/s suggests the suspension bridge is unstable at dt=$BRIDGE_DT")
    }

    @Test
    fun `every tower leg stays fixed at its anchored position throughout`() {
        val scenario = buildSuspensionBridge()
        val integrator = Integrator()
        val startPositions = scenario.towerLegs.flatten().associateWith { scenario.store.position(it) }

        var t = 0.0
        val steps = (4.0 / BRIDGE_DT).toInt()
        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, BRIDGE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, BRIDGE_DT)
            t += BRIDGE_DT
        }

        for ((id, start) in startPositions) {
            assertTrue(
                (scenario.store.position(id) - start).length() < 1e-9,
                "tower particle $id moved from $start to ${scenario.store.position(id)}",
            )
        }
    }

    @Test
    fun `the main cables sag below their own tower-top height once settled`() {
        val scenario = buildSuspensionBridge()
        val integrator = Integrator()
        val towerTopY = scenario.towerLegs[0].let { scenario.store.position(it.last()).y }

        var t = 0.0
        val steps = (6.0 / BRIDGE_DT).toInt()
        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, BRIDGE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, BRIDGE_DT)
            t += BRIDGE_DT
        }

        val midIndex = scenario.cableLeft.size / 2
        val midSagY = scenario.store.position(scenario.cableLeft[midIndex]).y
        assertTrue(
            midSagY < towerTopY - 0.1,
            "cable midpoint ($midSagY) should sag meaningfully below the tower top ($towerTopY)",
        )
    }
}
