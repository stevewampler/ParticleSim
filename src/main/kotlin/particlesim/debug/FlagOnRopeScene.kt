package particlesim.debug

import particlesim.collision.SurfaceCollisionRule
import particlesim.collision.SurfaceCollisionSystem
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.FLAG_DT
import particlesim.examples.FlagpoleScenario
import particlesim.examples.RopeScenario
import particlesim.examples.ShapePlacement
import particlesim.examples.buildFlag
import particlesim.examples.buildFlagpole
import particlesim.examples.buildRope
import particlesim.physics.Constraint
import particlesim.physics.Damper
import particlesim.physics.Force
import particlesim.physics.Integrator
import particlesim.physics.MeshSprings
import particlesim.physics.Spring
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer
import particlesim.render.TextureAssets
import particlesim.surface.Surface

/**
 * requirements.md §7.3's "connect the flag to the top portion of the rope" requirement: composes
 * [buildFlagpole] + [buildRope] + [buildFlag], the same "compose by placement" pattern
 * [PoleRopeScene]/[MultiShapeScene] already use, but deliberately does **not** forward
 * [particlesim.examples.FlagScenario.constraints] (just the flag's own `pole-anchor`
 * [particlesim.physics.FixedPosition] pin) into [FlagOnRopeScenario.constraints] below - that
 * pin is exactly what a rope attachment replaces. Instead, each flag pole-edge particle
 * (`flagGrid[row][0]`) is connected to the rope's corresponding top-portion particle
 * (`ropeIds[row]`) by its own [Spring]/[Damper] pair, so the flag now hangs from a dynamic rope
 * rather than a rigid pin - it can sway with the rope instead of holding a frozen vertical edge.
 *
 * A plain top-level function (not another `examples/build*` shape) since, unlike [buildRope]/
 * [buildFlagpole]/[buildFlag], this composition has exactly one consumer ([FlagOnRopeScene]) and
 * isn't meant to be composed further itself - it exists as a function at all (rather than being
 * written directly into the scene class) purely so [FlagOnRopeSceneTest] can assert on its
 * internals without reaching through [particlesim.debug.DemoScene]'s narrow public surface.
 *
 * **Row-for-row vertical alignment is exact, by construction, not by luck**: [buildRope] spaces
 * its particles evenly between its two anchors, so choosing `ropeSegments` and the anchors' `y`
 * such that `(topAnchor.y - bottomAnchor.y) / ropeSegments == flagSpacing` makes every rope
 * particle's height match the corresponding flag row's height exactly (`poleHeight -
 * row * flagSpacing` on both sides) for every row the flag actually reaches. Only a small,
 * deliberate sideways gap remains (the rope's `x` drifts toward `ropeBottomOffsetX` while the
 * flag's own edge stays at `x = 0`), which is what gives the attachment springs a small nonzero
 * rest length and lets the flag actually pull against the rope instead of the two starting
 * perfectly coincident everywhere.
 *
 * The rope is built with more segments than the flag has rows (`ropeSegments > flagRows - 1`),
 * so its bottom portion continues on past the flag's lowest attached row down to its own real
 * anchor "partway up the pole" - the requirement's literal "top portion of the rope" phrasing,
 * kept visually distinct from the flag-covered top portion in [FlagOnRopeScene.frame]'s
 * connection list.
 */
data class FlagOnRopeScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val constraints: List<Constraint>,
    /** Particle-vs-triangulated-surface collision (§12.4) keeping the pole and rope from
     * passing through the flag's own cloth mesh, wired to the same
     * [particlesim.collision.SurfaceCollisionSystem]/[SurfaceCollisionRule] mechanism the
     * trampoline worked example already proved out - see [buildFlagOnRopeScenario]'s own doc
     * comment for why this is wiring, not new physics. */
    val collisions: SurfaceCollisionSystem,
    val flagpole: FlagpoleScenario,
    val rope: RopeScenario,
    /** `flagGrid[row][col]` - same layout as [particlesim.examples.FlagScenario.grid]. */
    val flagGrid: List<List<Int>>,
    val flagSurface: Surface,
    val flagStructural: MeshSprings,
)

fun buildFlagOnRopeScenario(
    poleHeight: Double = 3.5,
    flagRows: Int = 8,
    flagCols: Int = 14,
    flagSpacing: Double = 0.15,
    // Chosen so the rope's per-segment vertical step exactly equals flagSpacing (see class doc):
    // 13 segments * 0.15 = 1.95, i.e. the rope's bottom anchor sits 1.95m below its top anchor.
    ropeSegments: Int = 13,
    ropeBottomOffsetX: Double = 0.15,
    // Collision radii (§12.4) - thin enough not to visibly separate the pole/rope from the
    // flag's own attachment springs, which already hold them close, but large enough to give
    // SurfaceCollisionSystem's narrow phase a real, non-degenerate contact volume to detect.
    poleCollisionRadius: Double = 0.03,
    ropeCollisionRadius: Double = 0.015,
): FlagOnRopeScenario {
    require(ropeSegments >= flagRows - 1) {
        "ropeSegments ($ropeSegments) must be at least flagRows - 1 (${flagRows - 1}) so the rope's per-row spacing matches the flag's"
    }

    val store = ParticleStore()
    val groups = Groups()

    val flagpole = buildFlagpole(
        height = poleHeight, segments = 6, particleRadius = poleCollisionRadius,
        store = store, groups = groups, placement = ShapePlacement(instanceName = "pole"),
    )

    val rope = buildRope(
        topAnchor = Vector3(0.0, poleHeight, 0.0),
        bottomAnchor = Vector3(ropeBottomOffsetX, poleHeight - ropeSegments * flagSpacing, 0.0),
        segments = ropeSegments,
        particleRadius = ropeCollisionRadius,
        store = store,
        groups = groups,
        placement = ShapePlacement(instanceName = "rope"),
    )

    val flag = buildFlag(
        rows = flagRows, cols = flagCols, spacing = flagSpacing,
        store = store, groups = groups,
        placement = ShapePlacement(offset = Vector3(0.0, poleHeight, 0.0), instanceName = "flag"),
    )

    // A fresh group rather than reusing "pole.pole"/"rope.rope" directly - SurfaceCollisionRule
    // targets one group per rule, and reconstructing those shapes' own internal group names here
    // would duplicate a literal that already lives inside buildFlagpole/buildRope (§4.5's
    // "groups as the universal selector" reuse, not a parallel targeting scheme: this is just
    // one more group both particle sets happen to also belong to).
    val poleRopeCollidableGroup = "poleRopeCollidable"
    for (id in flagpole.poleIds) groups.add(poleRopeCollidableGroup, id)
    for (id in rope.ropeIds) groups.add(poleRopeCollidableGroup, id)
    val collisions = SurfaceCollisionSystem(
        listOf(
            SurfaceCollisionRule(
                group = poleRopeCollidableGroup,
                surface = flag.surface,
                // Low restitution, moderate damping - this pairing exists to stop visible
                // interpenetration (§12.4's requirement), not to make the pole/rope bounce off
                // the cloth like a dropped ball; a near-immediate settle looks like "can't pass
                // through," not "collided with something springy."
                restitution = 0.1,
                compressionDamping = 2.0,
                extensionDamping = 0.5,
            ),
        ),
    )

    // flag.constraints (just the pole-anchor FixedPosition pin) is deliberately never forwarded
    // into this scenario's own constraints - these springs/dampers replace it.
    val attachmentSprings = (0 until flagRows).map { row ->
        val a = flag.grid[row][0]
        val b = rope.ropeIds[row]
        Spring(a, b, restLength = (store.position(b) - store.position(a)).length(), stiffness = 200.0, name = "attachment-spring-$row")
    }
    val attachmentDampers = (0 until flagRows).map { row ->
        Damper(flag.grid[row][0], rope.ropeIds[row], damping = 1.0, name = "attachment-damper-$row")
    }

    return FlagOnRopeScenario(
        store = store,
        groups = groups,
        forces = rope.forces + flag.forces + attachmentSprings + attachmentDampers,
        constraints = flagpole.constraints + rope.constraints,
        collisions = collisions,
        flagpole = flagpole,
        rope = rope,
        flagGrid = flag.grid,
        flagSurface = flag.surface,
        flagStructural = flag.meshSprings[0],
    )
}

/** §9.6 scene-library wrapping of [buildFlagOnRopeScenario] - see that function's doc comment
 * for the attachment/alignment reasoning. */
class FlagOnRopeScene : DemoScene {
    private val scenario = buildFlagOnRopeScenario()
    private val flagRows = scenario.flagGrid.size
    // §10.2's texture-mapped surfaces, same asset FlagScene uses. scenario.flagSurface already
    // carries Grid.uvs (buildFlag attaches it unconditionally).
    private val clothMesh = SurfaceRenderer(scenario.flagSurface, wireframe = false, textureName = TextureAssets.USA_FLAG)
    private val registry = SceneRegistry.build(
        forces = scenario.forces, constraints = scenario.constraints, surfaces = listOf(scenario.flagSurface), groups = scenario.groups,
    )
    private val integrator = Integrator()
    private val allIds = scenario.flagpole.poleIds + scenario.rope.ropeIds + scenario.flagGrid.flatten()

    override val dt = FLAG_DT
    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = allIds

    override fun handleControl(message: SceneControlMessage, t: Double) {
        applyEditableFieldMessage(message, scenario.forces, scenario.constraints, scenario.store, t)
    }

    override fun step(t: Double) {
        integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, dt)
        scenario.collisions.resolve(scenario.store, scenario.groups, t, dt)
    }

    override fun frame(t: Double): SceneFrame = SceneFrame(
        connections = scenario.flagpole.poleIds.zipWithNext() + scenario.rope.ropeIds.zipWithNext() +
            scenario.flagStructural.activeConnections() +
            (0 until flagRows).map { row -> scenario.flagGrid[row][0] to scenario.rope.ropeIds[row] },
        meshes = listOf(clothMesh),
        // No particle has a dot in this scene by default (§10.3's per-object toggle can't bring
        // one back either - the client's "show particles" checkbox only ever hides *further*,
        // never restores something the server already excluded here). The flag's own dots are
        // covered by the textured mesh; the pole/rope's are only reachable via their connection
        // polylines - a deliberate scope, not an oversight left for later.
        visibleIds = emptySet(),
        registry = registry,
    )
}
