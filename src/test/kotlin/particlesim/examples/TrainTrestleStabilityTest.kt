package particlesim.examples

import particlesim.physics.Integrator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** §13.1/§13.5, mirroring [BridgeStabilityTest]/[SuspensionBridgeStabilityTest]: [TRAIN_TRESTLE_DT]
 * and the deck's much stiffer "steel" springs were picked from the stability budget (see
 * [TRAIN_TRESTLE_DT]'s own doc comment), not tuned for looks alone. */
class TrainTrestleStabilityTest {

    @Test
    fun `trestle scenario runs through a full train crossing without blowing up`() {
        val scenario = buildTrainTrestle()
        val integrator = Integrator()

        var t = 0.0
        var maxSpeed = 0.0
        val steps = (8.0 / TRAIN_TRESTLE_DT).toInt() // 8s - the train's full crossing

        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, TRAIN_TRESTLE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, TRAIN_TRESTLE_DT)
            t += TRAIN_TRESTLE_DT
            for (id in scenario.store.liveIds()) {
                val speed = scenario.store.velocity(id).length()
                if (speed > maxSpeed) maxSpeed = speed
            }
        }

        assertTrue(maxSpeed < 50.0, "max particle speed $maxSpeed m/s suggests the trestle is unstable at dt=$TRAIN_TRESTLE_DT")
    }

    @Test
    fun `every bent leg stays fixed at its anchored position throughout`() {
        val scenario = buildTrainTrestle()
        val integrator = Integrator()
        val startPositions = scenario.bents.flatMap { (l, r) -> l + r }.associateWith { scenario.store.position(it) }

        var t = 0.0
        val steps = (4.0 / TRAIN_TRESTLE_DT).toInt()
        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, TRAIN_TRESTLE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, TRAIN_TRESTLE_DT)
            t += TRAIN_TRESTLE_DT
        }

        for ((id, start) in startPositions) {
            assertTrue(
                (scenario.store.position(id) - start).length() < 1e-9,
                "bent leg particle $id moved from $start to ${scenario.store.position(id)}",
            )
        }
    }

    @Test
    fun `the deck barely deflects under the train's weight, unlike the flexible bridge decks`() {
        val scenario = buildTrainTrestle()
        val integrator = Integrator()
        val midRow = scenario.deckGrid[scenario.deckGrid.size / 2]
        val startY = midRow.map { scenario.store.position(it).y }.average()

        var t = 0.0
        val steps = (6.0 / TRAIN_TRESTLE_DT).toInt()
        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, TRAIN_TRESTLE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, TRAIN_TRESTLE_DT)
            t += TRAIN_TRESTLE_DT
        }

        val endY = midRow.map { scenario.store.position(it).y }.average()
        assertTrue(
            abs(endY - startY) < 0.15,
            "deck mid-span deflected ${abs(endY - startY)} - expected a stiff steel deck to barely move",
        )
    }
}
