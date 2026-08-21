package particlesim.yaml

import org.yaml.snakeyaml.Yaml
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.physics.Constraint
import particlesim.physics.FixedPosition
import particlesim.physics.Force
import particlesim.physics.MeshSprings
import particlesim.physics.UniformGravity
import particlesim.physics.Wind
import particlesim.surface.Grid

data class YamlScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val constraints: List<Constraint>,
    /** Each declared particle grid's `List<List<Int>>` ids, keyed by its `name` — lets a
     * caller (e.g. a golden-file test) sample specific `grid[row][col]` vertices the same way
     * [particlesim.examples.FlagScenario.grid] does, without hardcoding id arithmetic. */
    val grids: Map<String, List<List<Int>>>,
)

/**
 * The YAML front-end (§4.2), scoped for this pass to exactly what §7.3's flag worked example
 * needs: a particle grid, structural/shear/bend mesh springs, wind, gravity, and a
 * fixed-position constraint. [particlesim.golden.FlagYamlParityTest] loads this scenario from
 * YAML and asserts it matches the *same* checked-in `flag.golden.txt` the Kotlin-built
 * [particlesim.examples.buildFlag] already produces — proving both front-ends build the same
 * in-memory model (§4), not just asserting it. Colliders, emitters, destroy rules, breakable
 * thresholds, N-body/ball-bounce/sparks scenarios, and general bulk-generation shapes
 * (uniform-random-in-volume, explicit particle lists, individual particle declarations) are
 * real §4.2 scope but not covered here — noted in TODO.md as a deliberate second pass, the
 * same framing used for every other phase's worked-example-first scoping.
 *
 * **Group model, simplified for this schema**: there's no tag/id/range selector language yet
 * (§4.2's "selector"), just direct named-group assignment from a particle grid's own `name`/
 * `edge_groups`, plus an optional top-level `groups:` list purely to make a group's "declared
 * but currently unmatched" state distinguishable from "never declared" (§4.2's two required
 * semantic checks): a name in `groups:` with zero members after loading is a **warning**; a
 * `group:` reference anywhere else to a name that was never produced by *any* declaration is a
 * load-time **error**. A real selector system would give the zero-match warning a more natural
 * home; this is the narrowest thing that demonstrates both checks precisely as specified.
 */
class YamlLoader(private val onWarning: (String) -> Unit = { System.err.println(it) }) {

    fun load(yamlText: String): YamlScenario {
        val root = (Yaml().load<Any?>(yamlText) as? Map<*, *>)
            ?: throw YamlLoadException("root document must be a mapping")

        val version = root["version"] ?: throw YamlLoadException("missing required top-level field 'version'")
        if (version != 1) throw YamlLoadException("unsupported version '$version' (only version 1 is supported)")

        val store = ParticleStore()
        val groups = Groups()
        val grids = HashMap<String, List<List<Int>>>()
        val declaredGroups = LinkedHashSet<String>()

        val explicitGroups = root.requireListOrEmpty("groups", "root").map {
            it as? String ?: throw YamlLoadException("groups: each entry must be a string")
        }
        declaredGroups += explicitGroups

        loadParticles(root, store, groups, grids, declaredGroups)

        fun requireKnownGroup(name: String, context: String) {
            if (name !in declaredGroups) throw YamlLoadException("$context: unknown group '$name'")
        }

        val forces = loadForces(root, store, grids, ::requireKnownGroup)
        val constraints = loadConstraints(root, store, groups, ::requireKnownGroup)

        for (name in explicitGroups) {
            if (groups.membersOf(name).isEmpty()) onWarning("group '$name' matches zero particles")
        }

        return YamlScenario(store, groups, forces, constraints, grids)
    }

    private fun loadParticles(
        root: Map<*, *>, store: ParticleStore, groups: Groups,
        grids: MutableMap<String, List<List<Int>>>, declaredGroups: MutableSet<String>,
    ) {
        val particlesSection = root.optionalMap("particles") ?: return
        val gridSection = particlesSection.optionalMap("grid") ?: return

        val name = gridSection.requireString("name", "particles.grid")
        val rows = gridSection.requireInt("rows", "particles.grid")
        val cols = gridSection.requireInt("cols", "particles.grid")
        val spacing = gridSection.optionalDouble("spacing", 1.0)
        val massExpr = gridSection.requireScalarExpr("mass", "particles.grid")

        val grid = (0 until rows).map { r ->
            (0 until cols).map { c ->
                val id = try {
                    store.create(position = Vector3(c * spacing, -r * spacing, 0.0), mass = massExpr)
                } catch (e: IllegalArgumentException) {
                    throw YamlLoadException("particles.grid.mass: ${e.message}")
                }
                groups.add(name, id)
                id
            }
        }
        grids[name] = grid
        declaredGroups += name

        for (entry in gridSection.requireListOrEmpty("edge_groups", "particles.grid")) {
            val eg = entry as? Map<*, *> ?: throw YamlLoadException("particles.grid.edge_groups: each entry must be a mapping")
            val edge = eg.requireString("edge", "particles.grid.edge_groups")
            val groupName = eg.requireString("group", "particles.grid.edge_groups")
            val ids = when (edge) {
                "left" -> grid.map { it.first() }
                "right" -> grid.map { it.last() }
                "top" -> grid.first()
                "bottom" -> grid.last()
                else -> throw YamlLoadException("particles.grid.edge_groups.edge: unknown edge '$edge' (expected left/right/top/bottom)")
            }
            ids.forEach { groups.add(groupName, it) }
            declaredGroups += groupName
        }
    }

    private fun loadForces(
        root: Map<*, *>, store: ParticleStore, grids: Map<String, List<List<Int>>>,
        requireKnownGroup: (String, String) -> Unit,
    ): List<Force> {
        val forces = ArrayList<Force>()
        for ((index, entry) in root.requireListOrEmpty("forces", "root").withIndex()) {
            val map = entry as? Map<*, *> ?: throw YamlLoadException("forces[$index]: expected a mapping")
            val context = "forces[$index]"
            when {
                map.containsKey("gravity") -> {
                    val f = map.requireMap("gravity", context)
                    val group = f.requireString("group", "$context.gravity")
                    requireKnownGroup(group, "$context.gravity.group")
                    val accel = f.requireVectorLiteral("acceleration", "$context.gravity")
                    forces += UniformGravity(group, accel)
                }
                map.containsKey("mesh_springs") -> {
                    val f = map.requireMap("mesh_springs", context)
                    val grid = resolveGrid(f, grids, "$context.mesh_springs")
                    val edgeType = f.requireString("edges", "$context.mesh_springs")
                    val edges = when (edgeType) {
                        "structural" -> Grid.structuralEdges(grid)
                        "shear" -> Grid.shearEdges(grid)
                        "bend" -> Grid.bendEdges(grid)
                        else -> throw YamlLoadException(
                            "$context.mesh_springs.edges: unknown edge type '$edgeType' (expected structural/shear/bend)",
                        )
                    }
                    val stiffness = f.requireDouble("stiffness", "$context.mesh_springs")
                    val damping = f.optionalDouble("damping", 0.0)
                    forces += MeshSprings(edges, store, stiffness = stiffness, damping = damping)
                }
                map.containsKey("wind") -> {
                    val f = map.requireMap("wind", context)
                    val grid = resolveGrid(f, grids, "$context.wind")
                    val triangles = Grid.triangles(grid)
                    val velocity = f.requireVectorExpr("velocity", "$context.wind")
                    val density = f.optionalDouble("density", 1.0)
                    forces += Wind(triangles, velocity, density = density)
                }
                else -> throw YamlLoadException("$context: unknown force type (expected one of: gravity, mesh_springs, wind)")
            }
        }
        return forces
    }

    private fun loadConstraints(
        root: Map<*, *>, store: ParticleStore, groups: Groups,
        requireKnownGroup: (String, String) -> Unit,
    ): List<Constraint> {
        val constraints = ArrayList<Constraint>()
        for ((index, entry) in root.requireListOrEmpty("constraints", "root").withIndex()) {
            val map = entry as? Map<*, *> ?: throw YamlLoadException("constraints[$index]: expected a mapping")
            val context = "constraints[$index]"
            when {
                map.containsKey("fixed_position") -> {
                    val f = map.requireMap("fixed_position", context)
                    val group = f.requireString("group", "$context.fixed_position")
                    requireKnownGroup(group, "$context.fixed_position.group")
                    constraints += if (f.optionalBoolean("at_current_positions", false)) {
                        FixedPosition.atCurrentPositions(group, store, groups)
                    } else {
                        FixedPosition(group, f.requireVectorLiteral("position", "$context.fixed_position"))
                    }
                }
                else -> throw YamlLoadException("$context: unknown constraint type (expected: fixed_position)")
            }
        }
        return constraints
    }

    private fun resolveGrid(f: Map<*, *>, grids: Map<String, List<List<Int>>>, context: String): List<List<Int>> {
        val name = f.requireString("grid", context)
        return grids[name] ?: throw YamlLoadException("$context.grid: unknown grid '$name'")
    }
}
