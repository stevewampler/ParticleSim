package particlesim.yaml

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
}
