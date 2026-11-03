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
    fun `the arch and deck hold their designed shape through a load crossing`() {
        // Regression for the arch folding down onto the deck: with only rungs between the two
        // arch trusses (no bracing diagonals) and hangers only at the deck edges, the arch peak
        // dropped by more than its own rise and the deck centreline sagged ~0.5. Fully
        // triangulated, every arch/deck particle stays within ~0.15 of where it was built, even with the load on it.
        val scenario = buildArchBridge()
        val integrator = Integrator()
        val structureIds = scenario.archSides.flatMap { (u, l) -> u + l } + scenario.deckGrid.flatten()
        val start = structureIds.associateWith { scenario.store.position(it) }

        var t = 0.0
        val settleSteps = (1.0 / ARCH_BRIDGE_DT).toInt()
        val steps = (8.0 / ARCH_BRIDGE_DT).toInt()
        var maxDisplacement = 0.0
        var maxLateral = 0.0
        for (i in 0 until steps) {
            if (i < settleSteps) {
                scenario.store.setPosition(scenario.loadId, scenario.loadStart)
                scenario.store.setVelocity(scenario.loadId, particlesim.core.Vector3.ZERO)
            } else if (i == settleSteps) {
                scenario.store.setVelocity(scenario.loadId, scenario.loadStartVelocity)
            }
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, ARCH_BRIDGE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, ARCH_BRIDGE_DT)
            t += ARCH_BRIDGE_DT
            if (i % 100 == 0) {
                for (id in structureIds) {
                    val d = scenario.store.position(id) - start.getValue(id)
                    maxDisplacement = maxOf(maxDisplacement, d.length())
                    maxLateral = maxOf(maxLateral, abs(d.x))
                }
            }
        }

        assertTrue(maxDisplacement < 0.25, "arch/deck particle moved $maxDisplacement from its built position - the structure is collapsing")
        assertTrue(maxLateral < 0.05, "arch/deck particle drifted $maxLateral sideways - the two arch trusses are racking")
    }

    @Test
    fun `the deck mid-span stays level across its width`() {
        // Edge-only hangers with nothing spanning the deck's width left its centreline sagging
        // far below its hung edges; the floor beams should keep mid-span rows level - both a
        // row with a floor beam under it and the row between two beams.
        val scenario = buildArchBridge()
        val integrator = Integrator()
        val midRow = scenario.deckGrid[scenario.deckGrid.size / 2]

        var t = 0.0
        val steps = (3.0 / ARCH_BRIDGE_DT).toInt()
        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, ARCH_BRIDGE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, ARCH_BRIDGE_DT)
            t += ARCH_BRIDGE_DT
        }

        for (row in listOf(midRow, scenario.deckGrid[scenario.deckGrid.size / 2 - 1])) {
            val ys = row.map { scenario.store.position(it).y }
            assertTrue(ys.max() - ys.min() < 0.03, "deck mid-span row isn't level across its width: heights $ys")
        }
    }

    @Test
    fun `hangers are vertical and attach only to the deck edges`() {
        val scenario = buildArchBridge()
        val edgeIds = scenario.deckGrid.flatMap { listOf(it.first(), it.last()) }.toSet()
        for ((archId, deckId) in scenario.hangerConnections) {
            assertTrue(deckId in edgeIds, "hanger to deck particle $deckId isn't at a deck edge")
            val d = scenario.store.position(archId) - scenario.store.position(deckId)
            assertTrue(abs(d.x) < 1e-9 && abs(d.z) < 1e-9, "hanger $archId-$deckId isn't vertical: offset $d")
        }
    }
}
