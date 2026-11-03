package particlesim.examples

import particlesim.core.Vector3
import particlesim.physics.Integrator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §13.1/§13.5, mirroring [ArchBridgeStabilityTest]. */
class CableStayedBridgeStabilityTest {

    private fun run(scenario: CableStayedBridgeScenario, seconds: Double, launchAt: Double? = null, onSample: () -> Unit = {}) {
        val integrator = Integrator()
        var t = 0.0
        val steps = (seconds / CABLE_STAYED_BRIDGE_DT).toInt()
        val launchStep = launchAt?.let { (it / CABLE_STAYED_BRIDGE_DT).toInt() }
        for (i in 0 until steps) {
            if (launchStep != null && i < launchStep) {
                scenario.store.setPosition(scenario.loadId, scenario.loadStart)
                scenario.store.setVelocity(scenario.loadId, Vector3.ZERO)
            } else if (i == launchStep) {
                scenario.store.setVelocity(scenario.loadId, scenario.loadStartVelocity)
            }
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, CABLE_STAYED_BRIDGE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, CABLE_STAYED_BRIDGE_DT)
            t += CABLE_STAYED_BRIDGE_DT
            if (i % 100 == 0) onSample()
        }
    }

    @Test
    fun `the deck holds its shape through a load crossing`() {
        val scenario = buildCableStayedBridge()
        val ids = scenario.deckGrid.flatten() + scenario.floorBeamIds
        val start = ids.associateWith { scenario.store.position(it) }
        var maxDisplacement = 0.0
        var maxSpeed = 0.0
        run(scenario, seconds = 8.0, launchAt = 1.0) {
            for (id in ids) maxDisplacement = maxOf(maxDisplacement, (scenario.store.position(id) - start.getValue(id)).length())
            for (id in scenario.store.liveIds()) maxSpeed = maxOf(maxSpeed, scenario.store.velocity(id).length())
        }
        assertTrue(maxSpeed < 50.0, "max particle speed $maxSpeed suggests instability at dt=$CABLE_STAYED_BRIDGE_DT")
        assertTrue(maxDisplacement < 0.25, "deck particle moved $maxDisplacement from its built position - the deck is collapsing")
    }

    @Test
    fun `the deck stays level across its width between the stays`() {
        val scenario = buildCableStayedBridge()
        run(scenario, seconds = 3.0)
        // Mid-way out along one fan: a stay row and the row next to it.
        val r = scenario.deckGrid.size / 4
        for (row in listOf(scenario.deckGrid[r], scenario.deckGrid[r + 1])) {
            val ys = row.map { scenario.store.position(it).y }
            assertTrue(ys.max() - ys.min() < 0.03, "deck row isn't level across its width: heights $ys")
        }
    }

    @Test
    fun `stays attach only at the deck edges, both sides of the tower, and never cross`() {
        val scenario = buildCableStayedBridge()
        val edgeIds = scenario.deckGrid.flatMap { listOf(it.first(), it.last()) }.toSet()
        val towerZ = scenario.store.position(scenario.towerLegs[0][0]).z
        val stays = scenario.stayConnections.map { (towerId, deckId) ->
            assertTrue(deckId in edgeIds, "stay to deck particle $deckId isn't at a deck edge")
            scenario.store.position(towerId) to scenario.store.position(deckId)
        }
        assertEquals(stays.count { it.second.z < towerZ }, stays.count { it.second.z > towerZ }, "stays should be split evenly either side of the tower")
        // Semi-fan: the further along the deck a stay lands, the higher on the tower it starts.
        val oneFan = stays.filter { it.second.x < 0 && it.second.z > towerZ }.sortedBy { it.second.z }
        for ((a, b) in oneFan.zipWithNext()) assertTrue(b.first.y > a.first.y, "stays cross: $a and $b")
    }

    @Test
    fun `the tower stays fixed`() {
        val scenario = buildCableStayedBridge()
        val ids = scenario.towerLegs.flatten()
        val start = ids.associateWith { scenario.store.position(it) }
        run(scenario, seconds = 2.0)
        for (id in ids) assertTrue(abs((scenario.store.position(id) - start.getValue(id)).length()) < 1e-9, "tower particle $id moved")
    }
}
