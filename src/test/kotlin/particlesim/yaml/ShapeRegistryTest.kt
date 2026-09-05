package particlesim.yaml

import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Phase 8 of the YAML front-end's second pass: §4.5's shape library/registry, exercised through
 * the full [YamlLoader.load] pipeline (this is a pre-processing pass over the raw document, not
 * a separate parser - see [ShapeRegistry]'s own doc comment) rather than by calling
 * [ShapeRegistry] directly, so these tests prove the same thing a real scene author sees.
 *
 * Each YAML fixture is one self-contained `.trimIndent()`'d literal per test, not shared via
 * string interpolation across tests - `YamlLoaderTest.minimalGrid`'s own doc comment already
 * warns why: an already-`trimIndent()`'d, zero-indent fragment interpolated into a second
 * `.trimIndent()`'d string throws off that second call's common-indentation calculation and
 * silently produces malformed YAML (confirmed the hard way while first writing this file - the
 * fix was exactly "don't share trimmed fragments," not a YAML content bug). */
class ShapeRegistryTest {

    private fun ballBounceDefinition() = """
        shape_definitions:
          - name: ball_bounce
            params:
              drop_height: { type: double, default: 5.0 }
              restitution: { type: double, default: 0.7 }
            body:
              particles:
                - single: { name: ball, position: [0.0, "${'$'}drop_height", 0.0], mass: 1.0, radius: 0.2 }
              colliders:
                - plane: { name: floor, position: [0.0, 0.0, 0.0], normal: [0.0, 1.0, 0.0] }
              forces:
                - gravity: { group: ball, acceleration: [0.0, -9.8, 0.0] }
              collisions:
                rules:
                  - particle_collider: { group: ball, collider: floor, restitution: "${'$'}restitution" }
    """.trimIndent()

    @Test
    fun `a single shape instance round-trips through the normal section loaders`() {
        val yaml = ballBounceDefinition() + "\n" + """
            shapes:
              - use: ball_bounce
        """.trimIndent()
        val scenario = YamlLoader().load("version: 1\n$yaml")
        assertEquals(1, scenario.store.size)
        val id = scenario.groups.membersOf("ball").single()
        assertEquals(Vector3(0.0, 5.0, 0.0), scenario.store.position(id))
        assertTrue(scenario.colliders.containsKey("floor"))
        assertEquals(1, scenario.forces.size)
        assertTrue(scenario.collisionSystem != null)
    }

    @Test
    fun `params override the definition's own defaults`() {
        val yaml = ballBounceDefinition() + "\n" + """
            shapes:
              - use: ball_bounce
                params:
                  drop_height: 3.0
        """.trimIndent()
        val scenario = YamlLoader().load("version: 1\n$yaml")
        val id = scenario.groups.membersOf("ball").single()
        assertEquals(Vector3(0.0, 3.0, 0.0), scenario.store.position(id))
    }

    @Test
    fun `two instances of the same shape get distinct namespaced groups and colliders, not colliding`() {
        val yaml = ballBounceDefinition() + "\n" + """
            shapes:
              - use: ball_bounce
                instance: ball1
              - use: ball_bounce
                instance: ball2
                offset: [3.0, 0.0, 0.0]
        """.trimIndent()
        val scenario = YamlLoader().load("version: 1\n$yaml")
        assertEquals(2, scenario.store.size)
        val ball1 = scenario.groups.membersOf("ball1.ball").single()
        val ball2 = scenario.groups.membersOf("ball2.ball").single()
        assertTrue(ball1 != ball2)
        assertEquals(Vector3(0.0, 5.0, 0.0), scenario.store.position(ball1))
        assertEquals(Vector3(3.0, 5.0, 0.0), scenario.store.position(ball2)) // offset applied
        assertTrue(scenario.colliders.containsKey("ball1.floor"))
        assertTrue(scenario.colliders.containsKey("ball2.floor"))
        assertEquals(Vector3(0.0, 0.0, 0.0), scenario.colliders.getValue("ball1.floor").position)
        assertEquals(Vector3(3.0, 0.0, 0.0), scenario.colliders.getValue("ball2.floor").position) // offset applied to the collider too
        assertEquals(2, scenario.forces.size) // one gravity per instance, distinct groups
    }

    @Test
    fun `a grid-based shape's origin is translated by the instance offset`() {
        val yaml = """
            version: 1
            shape_definitions:
              - name: patch
                params:
                  rows: { type: int, default: 2 }
                body:
                  particles:
                    - grid: { name: cloth, rows: "${'$'}rows", cols: 2, mass: 1.0 }
            shapes:
              - use: patch
                instance: p1
                offset: [10.0, 0.0, 0.0]
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        val grid = scenario.grids.getValue("p1.cloth")
        assertEquals(10.0, scenario.store.position(grid[0][0]).x, 1e-12)
    }

    @Test
    fun `a missing required parameter with no default is a load-time error`() {
        val yaml = """
            version: 1
            shape_definitions:
              - name: needs_param
                params:
                  size: { type: double }
                body:
                  particles:
                    - single: { name: p, position: [0.0, 0.0, 0.0], radius: "${'$'}size" }
            shapes:
              - use: needs_param
        """.trimIndent()
        val ex = assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
        assertTrue(ex.message!!.contains("size"))
    }

    @Test
    fun `instantiating an unknown shape name is a load-time error`() {
        val yaml = ballBounceDefinition() + "\n" + """
            shapes:
              - use: nonexistent
        """.trimIndent()
        val ex = assertFailsWith<YamlLoadException> { YamlLoader().load("version: 1\n$yaml") }
        assertTrue(ex.message!!.contains("nonexistent"))
    }

    @Test
    fun `duplicate shape definition names are a load-time error`() {
        val yaml = """
            version: 1
            shape_definitions:
              - name: dup
                body: { particles: [{ single: { name: p, position: [0.0, 0.0, 0.0] } }] }
              - name: dup
                body: { particles: [{ single: { name: q, position: [0.0, 0.0, 0.0] } }] }
        """.trimIndent()
        val ex = assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
        assertTrue(ex.message!!.contains("dup"))
    }

    @Test
    fun `a document with no shapes or shape_definitions is unaffected`() {
        val scenario = YamlLoader().load(
            """
            version: 1
            particles:
              grid: { name: g, rows: 1, cols: 1, mass: 1.0 }
            """.trimIndent(),
        )
        assertEquals(1, scenario.store.size)
    }
}
