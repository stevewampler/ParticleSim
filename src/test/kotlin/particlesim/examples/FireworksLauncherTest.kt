package particlesim.examples

import particlesim.physics.FieldValue
import particlesim.record.CheckpointReader
import particlesim.record.CheckpointWriter
import particlesim.record.applyCheckpoint
import particlesim.record.captureCheckpoint
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [FireworksShow] as a [particlesim.lifecycle.CustomEmitter]: its launcher's settings edited
 * live, and its whole state carried through a §9.5 checkpoint. */
class FireworksLauncherTest {
    private fun run(show: FireworksShow, steps: Int, start: Double): Double {
        var t = start
        repeat(steps) { show.step(t, FIREWORKS_DT); t += FIREWORKS_DT }
        return t
    }

    @Test
    fun `the launcher's settings are editable, within sense, and change the display`() {
        val show = FireworksShow()
        assertEquals("launcher", show.name)
        assertEquals(
            listOf("minBreakHeight", "maxBreakHeight", "minLaunchInterval", "maxLaunchInterval", "launchHalfWidth"),
            show.editableFields().keys.toList(),
        )
        assertTrue(show.setField("minBreakHeight", FieldValue.Scalar(150.0)).not(), "a minimum above the maximum")
        assertTrue(show.setField("maxBreakHeight", FieldValue.Scalar(200.0)))
        assertTrue(show.setField("minBreakHeight", FieldValue.Scalar(180.0)))
        assertFalse(show.setField("maxBreakHeight", FieldValue.Scalar(500.0)))
        assertFalse(show.setField("minLaunchInterval", FieldValue.Scalar(0.01)))
        assertFalse(show.setField("launchHalfWidth", FieldValue.Scalar(-1.0)))
        assertFalse(show.setField("launchHalfWidth", FieldValue.Scalar(Double.NaN)))
        assertFalse(show.setField("launchHalfWidth", FieldValue.Vector(particlesim.core.Vector3.ZERO)))
        assertFalse(show.setField("seed", FieldValue.Scalar(2.0)))
        assertTrue(show.setField("maxLaunchInterval", FieldValue.Scalar(3.0)))
        assertTrue(show.setField("minLaunchInterval", FieldValue.Scalar(2.5)))
        assertTrue(show.setField("launchHalfWidth", FieldValue.Scalar(0.0)))
        assertEquals(FieldValue.Scalar(180.0), show.editableFields()["minBreakHeight"])
        // Shells fired from now on are aimed at 180-200 m, all from the middle, 2.5-3 s apart.
        val shells = HashMap<Int, Shell>()
        val launches = mutableListOf<Double>()
        var t = 0.0
        repeat((20.0 / FIREWORKS_DT).toInt()) {
            show.step(t, FIREWORKS_DT); t += FIREWORKS_DT
            for (s in show.liveShells.filter { it.stage == 1 }) if (shells.put(s.id, s) == null) launches += t
        }
        assertTrue(shells.values.all { it.breakHeight in 180.0..200.0 })
        assertTrue(shells.keys.all { show.store.contains(it).not() || show.store.position(it).x.let { x -> kotlin.math.abs(x) < 5.0 } })
        val gaps = launches.distinct().zipWithNext { a, b -> b - a }
        assertTrue(gaps.isNotEmpty() && gaps.all { it in 2.5 - 1e-9..3.0 + FIREWORKS_DT }, "gaps $gaps")
    }

    @Test
    fun `a show resumed from a checkpoint carries on exactly as one never stopped`() {
        val groupNames = listOf(FireworksShow.ALL_GROUP, FireworksShow.SHELL_GROUP, FireworksShow.STAR_GROUP, FireworksShow.SMOKE_GROUP)
        val dir = File.createTempFile("fireworks-checkpoint", "").also { it.delete(); it.mkdirs() }
        try {
            // Long enough for every kind of thing to be in flight: shells, stars, tails, burn-out
            // effects, flashes, smoke - and an edited setting.
            val original = FireworksShow(seed = 11L)
            original.setField("maxLaunchInterval", FieldValue.Scalar(1.2))
            val n = (40.0 / FIREWORKS_DT).toInt()
            val t = run(original, n, 0.0)
            val state = original.captureState()
            assertTrue((state["shells"] as List<*>).isNotEmpty() && (state["star_colors"] as List<*>).isNotEmpty())
            assertTrue(original.smokeIds.isNotEmpty())

            val checkpoint = captureCheckpoint(
                original.store, original.groups, groupNames, emptyList(), emptySet(), t, n.toLong(), customEmitters = listOf(original),
            )
            val base = File(dir, "cp")
            CheckpointWriter.write(checkpoint, base)
            val reloaded = CheckpointReader.read(base)

            val resumed = FireworksShow(seed = 11L)
            applyCheckpoint(resumed.store, resumed.groups, emptyMap(), reloaded, customEmittersByName = mapOf(resumed.name to resumed))
            assertEquals(original.captureState(), resumed.captureState(), "state survives the round trip through the files")

            // 20 s more of each: identical, to the bit.
            val m = (20.0 / FIREWORKS_DT).toInt()
            run(original, m, t)
            run(resumed, m, t)
            assertEquals(original.store.liveIds().sorted(), resumed.store.liveIds().sorted())
            for (id in original.store.liveIds()) {
                assertEquals(original.store.position(id), resumed.store.position(id), "particle $id")
                assertEquals(original.store.velocity(id), resumed.store.velocity(id), "particle $id")
            }
            assertEquals(original.starColorsById, resumed.starColorsById)
            assertEquals(original.captureState(), resumed.captureState())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a checkpoint refuses a show with a different seed`() {
        val a = FireworksShow(seed = 1L)
        run(a, 120, 0.0)
        val b = FireworksShow(seed = 2L)
        val failed = runCatching { b.restoreState(a.captureState()) }
        assertTrue(failed.isFailure)
    }
}
