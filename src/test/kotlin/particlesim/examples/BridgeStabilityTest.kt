package particlesim.examples

import particlesim.physics.Integrator
import kotlin.test.Test
import kotlin.test.assertTrue

/** §13.1/§13.5: `BRIDGE_DT` and the deck's structural/shear/bend stiffness were picked from the
 * stability budget (see [BRIDGE_DT]'s own doc comment), not tuned for looks alone - this is the
 * empirical check that the margin actually holds, mirroring [FlagStabilityTest]/
 * [TrampolineStabilityTest]. Runs the load's full crossing (not just the deck settling at rest)
 * since that's when the mesh sees its largest local deformation - a stable rest sag doesn't
 * prove the stiffer, faster deformation under an actively arriving load is also stable. */
class BridgeStabilityTest {

    @Test
    fun `bridge scenario runs through a full load crossing without blowing up`() {
        val scenario = buildBridge()
        val integrator = Integrator()

        var t = 0.0
        var maxSpeed = 0.0
        val steps = (8.0 / BRIDGE_DT).toInt() // 8s - long enough for the load to fully cross and settle

        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, BRIDGE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, BRIDGE_DT)
            t += BRIDGE_DT
            for (id in scenario.store.liveIds()) {
                val speed = scenario.store.velocity(id).length()
                if (speed > maxSpeed) maxSpeed = speed
            }
        }

        assertTrue(maxSpeed < 50.0, "max particle speed $maxSpeed m/s suggests the deck is unstable at dt=$BRIDGE_DT")
    }

    @Test
    fun `the deck's two end rows stay fixed at their anchored positions throughout`() {
        val scenario = buildBridge()
        val integrator = Integrator()
        val endRows = listOf(scenario.grid.first(), scenario.grid.last())
        val startPositions = endRows.flatten().associateWith { scenario.store.position(it) }

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
                "abutment particle $id moved from $start to ${scenario.store.position(id)}",
            )
        }
    }
}
