package particlesim.examples

import particlesim.physics.Integrator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** §13.1/§13.5, mirroring [TrainTrestleStabilityTest]: [ARCH_BRIDGE_DT] reuses
 * [TRAIN_TRESTLE_DT]'s own already-proven budget for the same stiffness category of deck, but
 * the arch/hanger composition is new and needs its own empirical check regardless. */
class ArchBridgeStabilityTest {

    @Test
    fun `arch bridge runs through a full load crossing without blowing up`() {
        val scenario = buildArchBridge()
        val integrator = Integrator()

        var t = 0.0
        var maxSpeed = 0.0
        val steps = (8.0 / ARCH_BRIDGE_DT).toInt()

        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, ARCH_BRIDGE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, ARCH_BRIDGE_DT)
            t += ARCH_BRIDGE_DT
            for (id in scenario.store.liveIds()) {
                val speed = scenario.store.velocity(id).length()
                if (speed > maxSpeed) maxSpeed = speed
            }
        }

        assertTrue(maxSpeed < 50.0, "max particle speed $maxSpeed m/s suggests the arch bridge is unstable at dt=$ARCH_BRIDGE_DT")
    }

    @Test
    fun `every pylon leg and arch springing point stays fixed at its anchored position`() {
        // The arch's own interior particles are dynamic now (see buildArchBridge's own doc
        // comment) - only the pylon legs and each chord's two springing points (archIds.first/
        // last) are still genuinely pinned, unlike the earlier fully-static arch this test used
        // to check in its entirety.
        val scenario = buildArchBridge()
        val integrator = Integrator()
        val springingPoints = scenario.archSides.flatMap { (u, l) -> listOf(u.first(), u.last(), l.first(), l.last()) }
        val rigidIds = scenario.pylonLegs.flatten() + springingPoints
        val startPositions = rigidIds.associateWith { scenario.store.position(it) }

        var t = 0.0
        val steps = (4.0 / ARCH_BRIDGE_DT).toInt()
        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, ARCH_BRIDGE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, ARCH_BRIDGE_DT)
            t += ARCH_BRIDGE_DT
        }

        for ((id, start) in startPositions) {
            assertTrue(
                (scenario.store.position(id) - start).length() < 1e-9,
                "rigid particle $id moved from $start to ${scenario.store.position(id)}",
            )
        }
    }

    @Test
    fun `the arch peak sits above the deck by roughly the configured rise`() {
        val scenario = buildArchBridge(archRise = 3.3, pylonHeight = 1.0, deckHeight = 0.0)
        val (_, lower) = scenario.archSides[0]
        val peakY = scenario.store.position(lower[lower.size / 2]).y
        assertTrue(
            abs(peakY - (0.0 + 1.0 + 3.3)) < 0.2,
            "arch peak height $peakY doesn't match the configured pylonHeight + archRise",
        )
    }

    @Test
    fun `the deck barely deflects under the load, same as the train trestle`() {
        val scenario = buildArchBridge()
        val integrator = Integrator()
        val midRow = scenario.deckGrid[scenario.deckGrid.size / 2]
        val startY = midRow.map { scenario.store.position(it).y }.average()

        var t = 0.0
        val steps = (6.0 / ARCH_BRIDGE_DT).toInt()
        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, ARCH_BRIDGE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, ARCH_BRIDGE_DT)
            t += ARCH_BRIDGE_DT
        }

        val endY = midRow.map { scenario.store.position(it).y }.average()
        assertTrue(
            abs(endY - startY) < 0.15,
            "deck mid-span deflected ${abs(endY - startY)} - expected a stiff steel deck to barely move",
        )
    }
}
