package particlesim.yaml

import particlesim.physics.Integrator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke-tests every demo scenario YAML under `src/main/resources/yaml/` that isn't already
 * covered by a golden-parity test (`flag`/`ball_bounce`/`sparks` have their own
 * `*YamlParityTest`s in `particlesim.golden` - not repeated here). These files reproduce each
 * `SceneLibrary` demo's *physics scenario* (particles/forces/constraints/colliders/collisions),
 * not its viewer wrapper (camera, renderer/texture/material choice, drag/delete interactivity,
 * re-drop cycles) - none of that is YAML's scope. `spatialGrid` has no file here: its per-
 * particle random velocity has no `random_volume` equivalent (documented in TODO.md), so it
 * stays Kotlin-DSL-only. There's also no YAML-to-`DemoScene` bridge yet (TODO.md), so these
 * aren't reachable from the scene picker - loadable and simulatable, not yet pickable.
 *
 * Not golden-file parity (these scenarios don't have a pre-existing Kotlin-built golden
 * reference the way flag/ball_bounce/sparks do) - just "loads without error, and doesn't blow
 * up over a real run": particle counts match what the source demo creates, and every particle's
 * position stays finite after stepping.
 */
class DemoYamlSmokeTest {

    private fun load(name: String): YamlScenario {
        val yaml = javaClass.getResourceAsStream("/yaml/$name.yaml")
            ?: throw AssertionError("resource /yaml/$name.yaml not found on the classpath")
        return YamlLoader().load(yaml.bufferedReader().readText())
    }

    private fun assertAllFinite(scenario: YamlScenario) {
        for (id in scenario.store.liveIds()) {
            val p = scenario.store.position(id)
            assertTrue(p.x.isFinite() && p.y.isFinite() && p.z.isFinite(), "particle $id has a non-finite position: $p")
        }
    }

    private fun run(
        scenario: YamlScenario, steps: Int, dt: Double,
        onStep: (Double) -> Unit = {},
    ) {
        val integrator = Integrator()
        var t = 0.0
        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, dt)
            scenario.collisionSystem?.resolve(scenario.store, scenario.groups, t, dt)
            scenario.particleCollisionSystem?.resolve(scenario.store, scenario.groups, emptyList())
            scenario.surfaceCollisionSystem?.resolve(scenario.store, scenario.groups, t, dt)
            onStep(t)
            t += dt
        }
    }

    @Test
    fun `trampoline scenario loads and settles a ball bouncing on the mat without blowing up`() {
        val scenario = load("trampoline")
        assertEquals(101, scenario.store.size) // 10x10 mat + 1 ball
        val ballId = scenario.groups.membersOf("ball").single()
        run(scenario, steps = 6000, dt = 5e-4) // matches particlesim.examples.TRAMPOLINE_DT
        assertAllFinite(scenario)
        // Bounced off the mat (z near 0, the mat's own plane) rather than tunneling through it
        // to some large negative z, and didn't fly off from a blown-up simulation either.
        val z = scenario.store.position(ballId).z
        assertTrue(z > -0.5 && z < 5.0)
    }

    @Test
    fun `drag chain scenario loads and hangs under gravity without blowing up`() {
        val scenario = load("drag")
        assertEquals(12, scenario.store.size)
        run(scenario, steps = 5000, dt = 1e-3)
        assertAllFinite(scenario)
        val anchorId = scenario.groups.membersOf("anchor").single()
        assertEquals(particlesim.core.Vector3(0.0, 4.0, 0.0), scenario.store.position(anchorId))
    }

    @Test
    fun `particleCollision scenario loads and settles balls onto the floor without blowing up`() {
        val scenario = load("particleCollision")
        assertEquals(18, scenario.store.size)
        run(scenario, steps = 4000, dt = 1e-3)
        assertAllFinite(scenario)
        for (id in scenario.groups.membersOf("balls")) {
            // Never tunneled through the floor.
            assertTrue(scenario.store.position(id).y >= 0.15 - 0.05)
        }
    }

    @Test
    fun `multiShape scenario loads all four composed shapes with disjoint, correctly sized groups`() {
        val scenario = load("multiShape")
        assertEquals(7, scenario.groups.membersOf("pole.pole").size)
        assertEquals(8 * 14, scenario.groups.membersOf("flag.cloth").size)
        assertEquals(16, scenario.groups.membersOf("tire.rim").size)
        assertEquals(1, scenario.groups.membersOf("ball.ball").size)
        assertEquals(7 + 8 * 14 + 16 + 1, scenario.store.size)
        run(scenario, steps = 3000, dt = 1e-3)
        assertAllFinite(scenario)
    }

    @Test
    fun `poleRope scenario loads a pole and a sagging rope without blowing up`() {
        val scenario = load("poleRope")
        assertEquals(7 + 11, scenario.store.size)
        run(scenario, steps = 5000, dt = 1e-3)
        assertAllFinite(scenario)
        // The rope's two anchors never move (fixed_position, at_current_positions) - top anchor
        // at [0, 3.5, 0], bottom anchor at [0.25, 1.75, 0].
        val anchorYs = scenario.groups.membersOf("rope-anchors").map { scenario.store.position(it).y }.sorted()
        assertEquals(listOf(1.75, 3.5), anchorYs)
    }

    @Test
    fun `flagOnRope scenario loads a flag stitched to a rope hanging from a pole without blowing up`() {
        val scenario = load("flagOnRope")
        assertEquals(7 + 14 + 8 * 14, scenario.store.size)
        assertTrue(scenario.surfaceCollisionSystem != null)
        val poleTopId = scenario.groups.membersOf("pole").maxByOrNull { scenario.store.position(it).y }!!
        run(scenario, steps = 2000, dt = 1e-3)
        assertAllFinite(scenario)
        // The pole is pinned (fixed_position, at_current_positions), reset to its exact anchor
        // every step - but that reset happens inside Integrator.step, before this frame's
        // surface_collider resolve() call, which (like buildFlagOnRopeScenario's own Kotlin
        // wiring) has no notion of "this particle is pinned" and can nudge it a small, bounded
        // amount before the next step's reset - hence a loose tolerance, not exact equality.
        assertEquals(3.5, scenario.store.position(poleTopId).y, 0.01)
    }
}
