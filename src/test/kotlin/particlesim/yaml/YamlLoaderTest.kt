package particlesim.yaml

import particlesim.core.Vector3
import particlesim.physics.ConstantForce
import particlesim.physics.Drag
import particlesim.physics.FieldValue
import particlesim.physics.Integrator
import particlesim.physics.MeshSprings
import particlesim.physics.NBodyGravity
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** §15.3: YAML schema validation — malformed input produces the expected error, without a
 * running simulation. §4.2's two required semantic checks (zero-match selector warning,
 * unknown-name load error) get their own dedicated tests below, plus the version field and
 * "load-time rejection of a statically-checkable bad value" (a literal negative mass). */
class YamlLoaderTest {

    // Built with appendLine rather than a multi-line string literal so a caller's own
    // (already-trimIndent()'d, zero-indent) `extra` block can't interact with this function's
    // own indentation via nested trimIndent() — that combination silently produces malformed
    // YAML (extra's continuation lines lose their indentation relative to the whole document).
    private fun minimalGrid(extra: String = "") = buildString {
        appendLine("version: 1")
        appendLine("particles:")
        appendLine("  grid:")
        appendLine("    name: g")
        appendLine("    rows: 2")
        appendLine("    cols: 2")
        appendLine("    mass: 1.0")
        if (extra.isNotEmpty()) appendLine(extra)
    }

    @Test
    fun `a minimal valid scenario loads without error`() {
        val scenario = YamlLoader().load(minimalGrid())
        assertEquals(4, scenario.store.size)
        assertEquals(4, scenario.groups.membersOf("g").size)
    }

    // --- version field -----------------------------------------------------------------------

    @Test
    fun `missing version field is a load-time error`() {
        val ex = assertFailsWith<YamlLoadException> {
            YamlLoader().load("particles:\n  grid:\n    name: g\n    rows: 1\n    cols: 1\n    mass: 1.0\n")
        }
        assertTrue(ex.message!!.contains("version"))
    }

    @Test
    fun `unsupported version is a load-time error`() {
        val ex = assertFailsWith<YamlLoadException> {
            YamlLoader().load("version: 2\nparticles:\n  grid:\n    name: g\n    rows: 1\n    cols: 1\n    mass: 1.0\n")
        }
        assertTrue(ex.message!!.contains("version"))
    }

    // --- unknown-name load error (§4.2) ------------------------------------------------------

    @Test
    fun `a force referencing an undeclared group is a load-time error`() {
        val yaml = minimalGrid(
            """
            forces:
              - gravity:
                  group: nonexistent
                  acceleration: [0.0, -9.8, 0.0]
            """.trimIndent(),
        )
        val ex = assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
        assertTrue(ex.message!!.contains("nonexistent"))
    }

    @Test
    fun `a constraint referencing an undeclared group is a load-time error`() {
        val yaml = minimalGrid(
            """
            constraints:
              - fixed_position:
                  group: typoed
                  at_current_positions: true
            """.trimIndent(),
        )
        assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
    }

    // --- zero-match selector warning (§4.2) ---------------------------------------------------

    @Test
    fun `a declared group with zero members warns, but still loads`() {
        val warnings = mutableListOf<String>()
        val yaml = minimalGrid(
            """
            groups: [g, unused]
            """.trimIndent(),
        )
        val scenario = YamlLoader(onWarning = { warnings.add(it) }).load(yaml)
        assertEquals(4, scenario.store.size) // still loaded successfully
        assertTrue(warnings.any { it.contains("unused") })
        assertTrue(warnings.none { it.contains("'g'") }) // 'g' has members, shouldn't warn
    }

    @Test
    fun `an undeclared name used only in forces is an error, not a warning`() {
        // Contrast with the above: 'unused' was explicitly declared (known-but-empty -> warn).
        // A name that was never declared anywhere is a typo, not a "not yet populated" case.
        val yaml = minimalGrid(
            """
            forces:
              - gravity:
                  group: neverDeclared
                  acceleration: [0.0, -1.0, 0.0]
            """.trimIndent(),
        )
        assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
    }

    // --- load-time rejection of a statically-checkable bad value (§4.2) -----------------------

    @Test
    fun `a literal negative mass is rejected at load time`() {
        val yaml = """
            version: 1
            particles:
              grid:
                name: g
                rows: 1
                cols: 1
                mass: -1.0
        """.trimIndent()
        val ex = assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
        assertTrue(ex.message!!.contains("mass"))
    }

    // --- structural errors --------------------------------------------------------------------

    @Test
    fun `a non-mapping root document is a load-time error`() {
        assertFailsWith<YamlLoadException> { YamlLoader().load("- 1\n- 2\n") }
    }

    @Test
    fun `a missing required field names the field`() {
        val ex = assertFailsWith<YamlLoadException> {
            YamlLoader().load("version: 1\nparticles:\n  grid:\n    name: g\n    rows: 1\n    cols: 1\n")
        }
        assertTrue(ex.message!!.contains("mass"))
    }

    @Test
    fun `an unknown force type is a load-time error`() {
        val yaml = minimalGrid(
            """
            forces:
              - not_a_real_force: {}
            """.trimIndent(),
        )
        assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
    }

    @Test
    fun `a malformed expression string is a load-time error, not a runtime one`() {
        val yaml = minimalGrid(
            """
            forces:
              - wind:
                  grid: g
                  velocity: "[1, 2"
            """.trimIndent(),
        )
        assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
    }

    @Test
    fun `wind referencing an unknown grid name is a load-time error`() {
        val yaml = minimalGrid(
            """
            forces:
              - wind:
                  grid: nope
                  velocity: [1.0, 0.0, 0.0]
            """.trimIndent(),
        )
        val ex = assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
        assertTrue(ex.message!!.contains("nope"))
    }

    // --- expression-capable fields accept both literals and expression strings ----------------

    @Test
    fun `mass accepts an expression string as well as a literal`() {
        val yaml = """
            version: 1
            particles:
              grid:
                name: g
                rows: 1
                cols: 1
                mass: "0.5 + 0.5"
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        assertEquals(1.0, scenario.store.mass(scenario.grids.getValue("g")[0][0]), 1e-12)
    }

    // --- Phase 1 of the YAML second pass: bulk generation beyond a grid, plus tags/ids --------

    @Test
    fun `particles as a list containing a single grid entry behaves identically to the map shorthand`() {
        val yaml = """
            version: 1
            particles:
              - grid:
                  name: g
                  rows: 2
                  cols: 2
                  mass: 1.0
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        assertEquals(4, scenario.store.size)
        assertEquals(4, scenario.groups.membersOf("g").size)
    }

    @Test
    fun `a particles list entry with an unknown generator kind is a load-time error`() {
        val yaml = """
            version: 1
            particles:
              - not_a_real_generator: {}
        """.trimIndent()
        assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
    }

    @Test
    fun `random_volume with a box shape generates the requested count within bounds`() {
        val yaml = """
            version: 1
            particles:
              - random_volume:
                  name: dust
                  count: 50
                  seed: 7
                  mass: 0.01
                  tags: [dust]
                  shape:
                    box: { center: [0.0, 0.0, 0.0], half_extents: [2.0, 1.0, 3.0] }
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        val ids = scenario.groups.membersOf("dust")
        assertEquals(50, ids.size)
        for (id in ids) {
            val p = scenario.store.position(id)
            assertTrue(abs(p.x) <= 2.0 && abs(p.y) <= 1.0 && abs(p.z) <= 3.0)
            assertEquals(0.01, scenario.store.mass(id), 1e-12)
        }
    }

    @Test
    fun `random_volume with a sphere shape generates points within the radius`() {
        val yaml = """
            version: 1
            particles:
              - random_volume:
                  name: dust
                  count: 50
                  seed: 3
                  shape:
                    sphere: { center: [1.0, 0.0, 0.0], radius: 2.0 }
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        for (id in scenario.groups.membersOf("dust")) {
            val p = scenario.store.position(id)
            assertTrue((p - Vector3(1.0, 0.0, 0.0)).length() <= 2.0 + 1e-9)
        }
    }

    @Test
    fun `random_volume with the same seed reproduces identical positions`() {
        val yaml = """
            version: 1
            particles:
              - random_volume:
                  name: dust
                  count: 10
                  seed: 42
                  shape:
                    box: { center: [0.0, 0.0, 0.0], half_extents: [1.0, 1.0, 1.0] }
        """.trimIndent()
        val a = YamlLoader().load(yaml)
        val b = YamlLoader().load(yaml)
        val positionsA = a.groups.membersOf("dust").sorted().map { a.store.position(it) }
        val positionsB = b.groups.membersOf("dust").sorted().map { b.store.position(it) }
        assertEquals(positionsA, positionsB)
    }

    @Test
    fun `random_volume requires a seed`() {
        val yaml = """
            version: 1
            particles:
              - random_volume:
                  name: dust
                  count: 1
                  shape:
                    box: { center: [0.0, 0.0, 0.0], half_extents: [1.0, 1.0, 1.0] }
        """.trimIndent()
        val ex = assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
        assertTrue(ex.message!!.contains("seed"))
    }

    @Test
    fun `random_volume rejects an unknown shape kind`() {
        val yaml = """
            version: 1
            particles:
              - random_volume:
                  name: dust
                  count: 1
                  seed: 1
                  shape:
                    cylinder: {}
        """.trimIndent()
        assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
    }

    @Test
    fun `list generator creates particles with per-entry fields`() {
        val yaml = """
            version: 1
            particles:
              - list:
                  name: debris
                  particles:
                    - id: p1
                      position: [1.0, 2.0, 0.0]
                      mass: 0.1
                      tags: [big]
                    - position: [4.0, 5.0, 0.0]
                      mass: 0.2
                      radius: 0.05
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        val ids = scenario.groups.membersOf("debris").sorted()
        assertEquals(2, ids.size)
        assertEquals(Vector3(1.0, 2.0, 0.0), scenario.store.position(ids[0]))
        assertEquals(0.1, scenario.store.mass(ids[0]), 1e-12)
        assertEquals(0.2, scenario.store.mass(ids[1]), 1e-12)
        assertEquals(0.05, scenario.store.radius(ids[1]))
    }

    @Test
    fun `list generator rejects a duplicate author id`() {
        val yaml = """
            version: 1
            particles:
              - list:
                  name: debris
                  particles:
                    - id: p1
                      position: [0.0, 0.0, 0.0]
                    - id: p1
                      position: [1.0, 0.0, 0.0]
        """.trimIndent()
        val ex = assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
        assertTrue(ex.message!!.contains("p1"))
    }

    @Test
    fun `list generator defaults mass to 1_0, matching ParticleStore's own default`() {
        val yaml = """
            version: 1
            particles:
              - list:
                  name: debris
                  particles:
                    - position: [0.0, 0.0, 0.0]
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        assertEquals(1.0, scenario.store.mass(scenario.groups.membersOf("debris").single()), 1e-12)
    }

    @Test
    fun `single generator creates one particle with the given fields`() {
        val yaml = """
            version: 1
            particles:
              - single:
                  name: anchor
                  position: [0.0, 3.0, 0.0]
                  mass: 2.0
                  tags: [anchor]
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        val id = scenario.groups.membersOf("anchor").single()
        assertEquals(Vector3(0.0, 3.0, 0.0), scenario.store.position(id))
        assertEquals(2.0, scenario.store.mass(id), 1e-12)
    }

    @Test
    fun `particles as a list can combine multiple generator kinds in one scene`() {
        val yaml = """
            version: 1
            particles:
              - grid:
                  name: g
                  rows: 1
                  cols: 2
                  mass: 1.0
              - single:
                  name: anchor
                  position: [0.0, 0.0, 0.0]
                  mass: 1.0
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        assertEquals(3, scenario.store.size)
        assertEquals(2, scenario.groups.membersOf("g").size)
        assertEquals(1, scenario.groups.membersOf("anchor").size)
    }

    // --- Phase 2 of the YAML second pass: the tag/id/range selector language ------------------

    @Test
    fun `a tags selector matches every particle carrying all listed tags, AND not OR`() {
        val yaml = """
            version: 1
            particles:
              - list:
                  name: debris
                  particles:
                    - position: [0.0, 0.0, 0.0]
                      tags: [big, hot]
                    - position: [1.0, 0.0, 0.0]
                      tags: [big]
                    - position: [2.0, 0.0, 0.0]
                      tags: [hot]
            groups:
              - name: hot_big
                select:
                  tags: [big, hot]
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        val debris = scenario.groups.membersOf("debris").sorted()
        assertEquals(setOf(debris[0]), scenario.groups.membersOf("hot_big"))
    }

    @Test
    fun `an ids selector matches particles by their declared author id`() {
        val yaml = """
            version: 1
            particles:
              - list:
                  name: debris
                  particles:
                    - id: p1
                      position: [0.0, 0.0, 0.0]
                    - id: p2
                      position: [1.0, 0.0, 0.0]
            groups:
              - name: picked
                select:
                  ids: [p2]
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        val debris = scenario.groups.membersOf("debris").sorted()
        assertEquals(setOf(debris[1]), scenario.groups.membersOf("picked"))
    }

    @Test
    fun `an ids selector referencing an unknown author id is a load-time error`() {
        val yaml = """
            version: 1
            particles:
              - single: { name: anchor, position: [0.0, 0.0, 0.0] }
            groups:
              - name: picked
                select:
                  ids: [nonexistent]
        """.trimIndent()
        val ex = assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
        assertTrue(ex.message!!.contains("nonexistent"))
    }

    @Test
    fun `a range selector matches an inclusive rectangular block of a named grid`() {
        val yaml = """
            version: 1
            particles:
              grid:
                name: g
                rows: 3
                cols: 3
                mass: 1.0
            groups:
              - name: leading_col
                select:
                  range: { grid: g, cols: [0, 0] }
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        val grid = scenario.grids.getValue("g")
        val expected = grid.map { it[0] }.toSet()
        assertEquals(expected, scenario.groups.membersOf("leading_col"))
    }

    @Test
    fun `a range selector with no rows or cols given selects the whole grid`() {
        val yaml = """
            version: 1
            particles:
              grid:
                name: g
                rows: 2
                cols: 2
                mass: 1.0
            groups:
              - name: whole
                select:
                  range: { grid: g }
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        assertEquals(4, scenario.groups.membersOf("whole").size)
    }

    @Test
    fun `a range selector out of bounds is a load-time error`() {
        val yaml = """
            version: 1
            particles:
              grid:
                name: g
                rows: 2
                cols: 2
                mass: 1.0
            groups:
              - name: bad
                select:
                  range: { grid: g, rows: [0, 5] }
        """.trimIndent()
        assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
    }

    @Test
    fun `a selector matching zero particles warns but still loads, same as a stale plain-string entry`() {
        val warnings = mutableListOf<String>()
        val yaml = """
            version: 1
            particles:
              - single: { name: anchor, position: [0.0, 0.0, 0.0], tags: [known] }
            groups:
              - name: empty_selector
                select:
                  tags: [nonexistent_tag]
        """.trimIndent()
        val scenario = YamlLoader(onWarning = { warnings.add(it) }).load(yaml)
        assertEquals(1, scenario.store.size) // still loaded successfully
        assertTrue(warnings.any { it.contains("empty_selector") })
    }

    @Test
    fun `mixing plain-string and selector entries in one groups list works`() {
        val yaml = """
            version: 1
            particles:
              - single: { name: anchor, position: [0.0, 0.0, 0.0], tags: [known] }
            groups:
              - anchor
              - name: by_tag
                select:
                  tags: [known]
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        assertEquals(scenario.groups.membersOf("anchor"), scenario.groups.membersOf("by_tag"))
    }

    @Test
    fun `a select block with none of tags, ids, range is a load-time error`() {
        val yaml = """
            version: 1
            particles:
              - single: { name: anchor, position: [0.0, 0.0, 0.0] }
            groups:
              - name: bad
                select: {}
        """.trimIndent()
        assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
    }

    @Test
    fun `a group reference elsewhere by a selector-defined name resolves correctly`() {
        val yaml = """
            version: 1
            particles:
              - single: { name: anchor, position: [0.0, 0.0, 0.0], tags: [known] }
            groups:
              - name: by_tag
                select:
                  tags: [known]
            forces:
              - gravity:
                  group: by_tag
                  acceleration: [0.0, -9.8, 0.0]
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        assertEquals(1, scenario.forces.size)
    }

    // --- Phase 3 of the YAML second pass: remaining forces -------------------------------------

    @Test
    fun `forces accept an optional name field`() {
        val yaml = minimalGrid(
            """
            forces:
              - gravity:
                  group: g
                  acceleration: [0.0, -1.0, 0.0]
                  name: main-gravity
            """.trimIndent(),
        )
        val scenario = YamlLoader().load(yaml)
        assertEquals("main-gravity", scenario.forces.single().name)
    }

    @Test
    fun `nbody_gravity accepts optional g and softening overrides`() {
        val yaml = """
            version: 1
            particles:
              - single: { name: bodies, position: [0.0, 0.0, 0.0] }
            forces:
              - nbody_gravity:
                  group: bodies
                  g: 1.0
                  softening: 0.1
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        val force = scenario.forces.single() as NBodyGravity
        assertEquals(FieldValue.Scalar(1.0), force.editableFields()["g"])
        assertEquals(FieldValue.Scalar(0.1), force.editableFields()["softening"])
    }

    @Test
    fun `nbody_gravity defaults g and softening when not given`() {
        val yaml = """
            version: 1
            particles:
              - single: { name: bodies, position: [0.0, 0.0, 0.0] }
            forces:
              - nbody_gravity:
                  group: bodies
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        val force = scenario.forces.single() as NBodyGravity
        assertEquals(6.674e-11, (force.editableFields()["g"] as FieldValue.Scalar).value, 1e-20)
        assertEquals(NBodyGravity.DEFAULT_SOFTENING, (force.editableFields()["softening"] as FieldValue.Scalar).value, 1e-12)
    }

    @Test
    fun `drag reduces a moving particle's speed over one integration step`() {
        val yaml = """
            version: 1
            particles:
              - single: { name: p, position: [0.0, 0.0, 0.0], velocity: [10.0, 0.0, 0.0] }
            forces:
              - drag:
                  group: p
                  coefficient: 1.0
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        assertTrue(scenario.forces.single() is Drag)
        Integrator().step(scenario.store, scenario.groups, scenario.forces, emptyList(), 0.0, 1e-3)
        val id = scenario.groups.membersOf("p").single()
        assertTrue(scenario.store.velocity(id).length() < 10.0)
    }

    @Test
    fun `constant_force applies a fixed force regardless of position`() {
        val yaml = """
            version: 1
            particles:
              - single: { name: p, position: [0.0, 0.0, 0.0] }
            forces:
              - constant_force:
                  group: p
                  force: [0.0, 5.0, 0.0]
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        assertTrue(scenario.forces.single() is ConstantForce)
        Integrator().step(scenario.store, scenario.groups, scenario.forces, emptyList(), 0.0, 1e-3)
        val id = scenario.groups.membersOf("p").single()
        assertTrue(scenario.store.velocity(id).y > 0.0)
    }

    @Test
    fun `mesh_springs exposes the direction-dependent stiffness, damping, and break-threshold triple`() {
        val yaml = minimalGrid(
            """
            forces:
              - mesh_springs:
                  grid: g
                  edges: structural
                  stiffness: 100.0
                  extension_stiffness: 120.0
                  compression_stiffness: 80.0
                  damping: 1.0
                  extension_damping: 1.5
                  break_threshold: 0.5
                  extension_break_threshold: 0.3
            """.trimIndent(),
        )
        val scenario = YamlLoader().load(yaml)
        val force = scenario.forces.single() as MeshSprings
        val fields = force.editableFields()
        assertEquals(FieldValue.Scalar(120.0), fields["extensionStiffness"])
        assertEquals(FieldValue.Scalar(80.0), fields["compressionStiffness"])
        assertEquals(FieldValue.Scalar(1.5), fields["extensionDamping"])
        assertEquals(FieldValue.Scalar(1.0), fields["compressionDamping"]) // falls back to damping
        assertEquals(FieldValue.Scalar(0.3), fields["extensionBreakThreshold"])
        assertEquals(FieldValue.Scalar(0.5), fields["compressionBreakThreshold"]) // falls back to break_threshold
    }

    @Test
    fun `mesh_springs still requires stiffness`() {
        val yaml = minimalGrid(
            """
            forces:
              - mesh_springs:
                  grid: g
                  edges: structural
            """.trimIndent(),
        )
        val ex = assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
        assertTrue(ex.message!!.contains("stiffness"))
    }

    // --- Phase 4 of the YAML second pass: fixed_velocity constraint, colliders -----------------

    @Test
    fun `fixed_velocity constraint carries the given velocity and optional name`() {
        val yaml = minimalGrid(
            """
            constraints:
              - fixed_velocity:
                  group: g
                  velocity: [1.0, 0.0, 0.0]
                  name: conveyor
            """.trimIndent(),
        )
        val scenario = YamlLoader().load(yaml)
        val constraint = scenario.constraints.single() as particlesim.physics.FixedVelocity
        assertEquals("conveyor", constraint.name)
        assertEquals(FieldValue.Vector(Vector3(1.0, 0.0, 0.0)), constraint.editableFields()["velocity"])
    }

    @Test
    fun `a plane collider is declared with a literal position and normal`() {
        val yaml = minimalGrid(
            """
            colliders:
              - plane:
                  name: floor
                  position: [0.0, 0.0, 0.0]
                  normal: [0.0, 1.0, 0.0]
            """.trimIndent(),
        )
        val scenario = YamlLoader().load(yaml)
        val floor = scenario.colliders.getValue("floor")
        assertTrue(floor is particlesim.collision.PlaneCollider)
        assertEquals(Vector3.ZERO, floor.position)
    }

    @Test
    fun `a sphere collider's position accepts an expression string for moving colliders`() {
        val yaml = minimalGrid(
            """
            colliders:
              - sphere:
                  name: obstacle
                  position: "[0.0, 2.0 + t, 0.0]"
                  radius: 0.5
            """.trimIndent(),
        )
        val scenario = YamlLoader().load(yaml)
        val obstacle = scenario.colliders.getValue("obstacle") as particlesim.collision.SphereCollider
        assertEquals(0.5, obstacle.radius)
        obstacle.advance(3.0, 1e-3)
        assertEquals(Vector3(0.0, 5.0, 0.0), obstacle.position)
    }

    @Test
    fun `a box collider carries its half-extents`() {
        val yaml = minimalGrid(
            """
            colliders:
              - box:
                  name: wall
                  position: [5.0, 0.0, 0.0]
                  half_extents: [0.1, 2.0, 2.0]
            """.trimIndent(),
        )
        val scenario = YamlLoader().load(yaml)
        val wall = scenario.colliders.getValue("wall") as particlesim.collision.BoxCollider
        assertEquals(Vector3(0.1, 2.0, 2.0), wall.halfExtents)
    }

    @Test
    fun `duplicate collider names are a load-time error`() {
        val yaml = minimalGrid(
            """
            colliders:
              - plane: { name: floor, position: [0.0, 0.0, 0.0], normal: [0.0, 1.0, 0.0] }
              - plane: { name: floor, position: [0.0, 1.0, 0.0], normal: [0.0, 1.0, 0.0] }
            """.trimIndent(),
        )
        val ex = assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
        assertTrue(ex.message!!.contains("floor"))
    }

    @Test
    fun `an unknown collider type is a load-time error`() {
        val yaml = minimalGrid(
            """
            colliders:
              - not_a_real_collider: {}
            """.trimIndent(),
        )
        assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
    }

    // --- Phase 5 of the YAML second pass: collision rules and destroy rules -------------------

    @Test
    fun `a particle_collider rule stops a falling particle at the floor`() {
        val yaml = """
            version: 1
            particles:
              - single: { name: ball, position: [0.0, 5.0, 0.0], mass: 1.0, radius: 0.2 }
            colliders:
              - plane: { name: floor, position: [0.0, 0.0, 0.0], normal: [0.0, 1.0, 0.0] }
            forces:
              - gravity: { group: ball, acceleration: [0.0, -9.8, 0.0] }
            collisions:
              rules:
                - particle_collider:
                    group: ball
                    collider: floor
                    restitution: 0.3
                    compression_damping: 3.0
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        assertTrue(scenario.collisionSystem != null)
        val id = scenario.groups.membersOf("ball").single()
        val integrator = Integrator()
        var t = 0.0
        val dt = 1e-3
        repeat(3000) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, dt)
            scenario.collisionSystem!!.resolve(scenario.store, scenario.groups, t, dt)
            t += dt
        }
        // Never tunnels through the floor - stays at or above the ball's own radius.
        assertTrue(scenario.store.position(id).y >= 0.2 - 0.05)
    }

    @Test
    fun `an unknown collider referenced by a particle_collider rule is a load-time error`() {
        val yaml = """
            version: 1
            particles:
              - single: { name: ball, position: [0.0, 0.0, 0.0] }
            collisions:
              rules:
                - particle_collider:
                    group: ball
                    collider: nonexistent
                    restitution: 0.5
        """.trimIndent()
        val ex = assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
        assertTrue(ex.message!!.contains("nonexistent"))
    }

    @Test
    fun `a particle_particle rule defaults group_b to group_a`() {
        val yaml = """
            version: 1
            particles:
              - list:
                  name: debris
                  particles:
                    - { position: [0.0, 0.0, 0.0], radius: 0.1 }
                    - { position: [0.15, 0.0, 0.0], radius: 0.1 }
            collisions:
              rules:
                - particle_particle:
                    group_a: debris
                    restitution: 0.5
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        assertTrue(scenario.particleCollisionSystem != null)
    }

    @Test
    fun `an outside_box destroy condition fires only beyond its bounds`() {
        val yaml = """
            version: 1
            particles:
              - list:
                  name: sparks
                  particles:
                    - position: [0.0, 0.0, 0.0]
                    - position: [20.0, 0.0, 0.0]
            destroy:
              - group: sparks
                outside_box:
                  center: [0.0, 0.0, 0.0]
                  half_extents: [10.0, 1000.0, 10.0]
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        assertTrue(scenario.destruction != null)
        val ids = scenario.groups.membersOf("sparks").sorted() // captured before resolve() removes the far one
        val result = scenario.destruction!!.resolve(scenario.store, scenario.groups, scenario.forces, t = 0.0, dt = 1e-3)
        assertTrue(ids[0] !in result.destroyedIds)
        assertTrue(ids[1] in result.destroyedIds)
    }

    @Test
    fun `an on_collision destroy rule fires when a particle touches the named collider`() {
        val yaml = """
            version: 1
            particles:
              - single: { name: p, position: [0.0, 0.0, 0.0], radius: 0.1 }
            colliders:
              - plane: { name: floor, position: [0.0, 0.0, 0.0], normal: [0.0, 1.0, 0.0] }
            destroy:
              - group: p
                on_collision:
                  collider: floor
        """.trimIndent()
        val scenario = YamlLoader().load(yaml)
        val id = scenario.groups.membersOf("p").single() // captured before resolve() removes it
        val result = scenario.destruction!!.resolve(scenario.store, scenario.groups, scenario.forces, t = 0.0, dt = 1e-3)
        assertTrue(id in result.destroyedIds)
    }

    @Test
    fun `an unknown collider referenced by an on_collision destroy rule is a load-time error`() {
        val yaml = """
            version: 1
            particles:
              - single: { name: p, position: [0.0, 0.0, 0.0] }
            destroy:
              - group: p
                on_collision:
                  collider: nonexistent
        """.trimIndent()
        val ex = assertFailsWith<YamlLoadException> { YamlLoader().load(yaml) }
        assertTrue(ex.message!!.contains("nonexistent"))
    }

    @Test
    fun `collisions and destroy are absent by default`() {
        val scenario = YamlLoader().load(minimalGrid())
        assertEquals(null, scenario.collisionSystem)
        assertEquals(null, scenario.particleCollisionSystem)
        assertEquals(null, scenario.destruction)
    }
}
