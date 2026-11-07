package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.TACOMA_DECK_HEIGHT_ABOVE_WATER
import particlesim.examples.TACOMA_DT
import particlesim.examples.TACOMA_FAILURE_WIND
import particlesim.examples.TacomaSharedForces
import particlesim.examples.ShapePlacement
import particlesim.examples.buildTacomaNarrows
import particlesim.examples.buildTacomaNarrows1950
import particlesim.examples.seedOneNodeTwist
import particlesim.physics.ChunkAccumulator
import particlesim.physics.FieldValue
import particlesim.physics.Force
import particlesim.physics.Wind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Both Tacoma bridges sharing one wind, gravity and flutter force ([TacomaSharedForces]). */
class TacomaComparisonSceneTest {
    private val wind = "[5.9, 0, 0]"

    private fun forceOn(force: Force, store: ParticleStore, groups: Groups, ids: List<Int>): List<Vector3> {
        val chunk = ChunkAccumulator(store.capacity)
        force.accumulate(store, groups, 0.0, chunk, 0, 1)
        return ids.map { chunk.at(store.slotOf(it)) }
    }

    @Test
    fun `sharing the forces changes nothing physically - each bridge gets what its own forces gave`() {
        // Each bridge alone, and both sharing (1940 built first in both, so its ids match; the
        // 1950 bridge's ids are shifted by the 1940 bridge's particle count).
        val oldStore = ParticleStore(); val oldGroups = Groups()
        val oldAlone = buildTacomaNarrows(windVelocity = wind, store = oldStore, groups = oldGroups)
        val newStore = ParticleStore(); val newGroups = Groups()
        val newAlone = buildTacomaNarrows1950(windVelocity = wind, store = newStore, groups = newGroups)
        val store = ParticleStore(); val groups = Groups()
        val shared = TacomaSharedForces(wind)
        val old = buildTacomaNarrows(shared = shared, store = store, groups = groups)
        val new = buildTacomaNarrows1950(shared = shared, store = store, groups = groups, placement = ShapePlacement(instanceName = "new"))
        val shift = new.spans[0][0].topLeft - newAlone.spans[0][0].topLeft
        // Twist both, the same way in each pair of stores.
        oldAlone.seedOneNodeTwist(0.3); old.seedOneNodeTwist(0.3)
        newAlone.seedOneNodeTwist(0.3); new.seedOneNodeTwist(0.3)

        val nOld = oldAlone.flutter!!.moments(oldStore, 0.0)
        val nNew = newAlone.flutter!!.moments(newStore, 0.0)
        val together = shared.flutter.moments(store, 0.0)
        assertEquals(nOld.size + nNew.size, together.size)
        for (i in nOld.indices) assertEquals(nOld[i], together[i], 1e-12)
        for (i in nNew.indices) assertEquals(nNew[i], together[nOld.size + i], 1e-12)

        val oldIds = oldAlone.spans.flatten().flatMap { it.all }
        val newIds = newAlone.spans.flatten().flatMap { it.all }
        val sharedWind = forceOn(shared.wind, store, groups, oldIds + newIds.map { it + shift })
        val separateWind = forceOn(oldAlone.wind, oldStore, oldGroups, oldIds) + forceOn(newAlone.wind, newStore, newGroups, newIds)
        for (i in sharedWind.indices) assertTrue((sharedWind[i] - separateWind[i]).length() < 1e-12, "wind on particle $i: ${sharedWind[i]} vs ${separateWind[i]}")

        val gravity = forceOn(shared.gravity, store, groups, oldIds + newIds.map { it + shift })
        val ownGravity = oldAlone.forces.filter { it.name == "gravity" || it.name == "cable-gravity" }.let { fs ->
            oldIds.map { id -> fs.map { forceOn(it, oldStore, oldGroups, listOf(id))[0] }.reduce { a, b -> a + b } }
        }
        for (i in oldIds.indices) assertTrue((gravity[i] - ownGravity[i]).length() < 1e-12)
    }

    @Test
    fun `the comparison scene has one wind, one gravity and one flutter, driving both bridges`() {
        val scene = tacomaComparisonScene()
        val frame = scene.frame(0.0)
        val forces = frame.registry.forces
        assertEquals(listOf("gravity", "wind", "flutter"), forces.keys.filter { it.endsWith("wind") || it.endsWith("gravity") || it.endsWith("flutter") })
        val wind = forces.getValue("wind") as Wind
        // Both decks catch it: doubling the density doubles the push on a 1940 and a 1950 particle.
        val store = scene.store
        val groups = Groups()
        val ids = scene.ids()
        // A girder/truss particle of each deck (well above the water).
        fun deckParticle(onSide: (Double) -> Boolean) = ids.first { store.position(it).let { p -> onSide(p.x) && p.y < -0.1 && p.y > -2.0 } }
        val sample = listOf(deckParticle { it > 2.0 }, deckParticle { it < -2.0 })
        val before = forceOn(wind, store, groups, sample)
        wind.setField("density", FieldValue.Scalar(2 * (wind.editableFields().getValue("density") as FieldValue.Scalar).value))
        val after = forceOn(wind, store, groups, sample)
        for (i in sample.indices) {
            assertTrue(before[i].length() > 0.0, "particle $i catches no wind")
            assertTrue((after[i] - before[i] * 2.0).length() < 1e-9)
        }
        // Stress colors on both, flutter arrows drawn once.
        assertTrue(frame.lineColors.isNotEmpty())
        assertEquals(listOf("flutter"), frame.arrowGroups.map { it.name })
        var t = 0.0
        repeat(500) { scene.step(t); t += scene.dt }
        assertTrue(scene.ids().all { store.position(it).y.isFinite() })
    }

    private class Comparison {
        val store = ParticleStore()
        val groups = Groups()
        val shared = TacomaSharedForces("[$TACOMA_FAILURE_WIND, 0.0, 0.0]")
        val old = Tacoma1940Bridge(store, groups, ShapePlacement(Vector3(2.5, 0.0, 0.0), "1940"), breakable = true, shared = shared)
        val new = Tacoma1950Bridge(store, groups, ShapePlacement(Vector3(-2.5, 0.16, 0.0), "1950"), showStress = true, shared = shared, breakable = true)
    }

    @Test
    fun `a 1950 bay tearing apart stops only its own flutter strips, not the 1940 bridge's`() {
        val c = Comparison()
        val s = c.new.scenario
        val (a, b) = s.mainSpan[20] to s.mainSpan[21]
        for ((x, y) in s.trussDiagonals.filter { (x, y) -> setOf(x, y).let { e -> listOf(a.all, b.all).let { (ra, rb) -> e.any { it in ra } && e.any { it in rb } } } }) {
            s.diagonals.breakEdge(s.diagonals.edgeIndexOf(x, y))
        }
        val events = c.new.afterStep(0.0, TACOMA_DT, emptyList())
        assertTrue(events.any { it is SimEvent.ForceBreak && it.name == "1950: deck tore apart in main span bay 20" }, "events: $events")
        val oldStrips = c.old.scenario.mainSpan.size - 2
        val newStrips = s.mainSpan.size - 2
        val inactive = (0 until oldStrips + newStrips).filter { !c.shared.flutter.isStripActive(it) }
        // Rows 20 and 21 of the 1950 main span are its strips 19 and 20, after the 1940 bridge's.
        assertEquals(listOf(oldStrips + 19, oldStrips + 20), inactive)
    }

    @Test
    fun `raising the one gravity breaks the 1950 bridge too, and its pieces stop at the shared water`() {
        val scene = tacomaComparisonScene()
        var t = 0.0
        repeat((6.0 / scene.dt).toInt()) { scene.step(t); t += scene.dt }
        scene.handleControl(SceneControlMessage.SetVectorField("force", "gravity", "acceleration", Vector3(0.0, -40.0, 0.0)), t)
        val events = ArrayList<SimEvent>()
        repeat((15.0 / scene.dt).toInt()) { scene.step(t); t += scene.dt; if (it % 100 == 0) events += scene.frame(t).events }
        val names = events.filterIsInstance<SimEvent.ForceBreak>().map { it.name }
        assertTrue(names.any { it.startsWith("1950: ") }, "1950 breaks: $names")
        assertTrue(names.any { it.startsWith("1940: ") }, "1940 breaks: $names")
        // One water surface, at both bridges' collision planes' height; nothing falls through.
        val water = scene.frame(t).meshes.single { it.surface.name == "water" }
        val waterY = -TACOMA_DECK_HEIGHT_ABOVE_WATER
        assertTrue(water.surface.triangles.flatMap { listOf(it.a, it.b, it.c) }.all { scene.store.position(it).y == waterY })
        val corners = water.surface.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet()
        val pieces = scene.ids().filter { it !in corners }
        val lowest = pieces.minBy { scene.store.position(it).y }
        assertTrue(scene.store.position(lowest).y > waterY - 0.05, "lowest: $lowest at ${scene.store.position(lowest)}, groups ${(scene as ComponentScene).groupsOf(lowest)}")
        assertTrue(scene.store.position(lowest).y < waterY + 0.05, "something has landed on the water")
    }
}
