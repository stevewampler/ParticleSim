package particlesim.examples

import particlesim.collision.SurfaceCollisionRule
import particlesim.collision.SurfaceCollisionSystem
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.expr.ExpressionParser
import particlesim.physics.Constraint
import particlesim.physics.Drag
import particlesim.physics.Force
import particlesim.physics.MeshSprings
import particlesim.physics.UniformGravity
import particlesim.physics.Wind
import particlesim.surface.Grid
import particlesim.surface.Surface

/**
 * An old rope suspension bridge - modelled on the "old ropebridge low poly" Sketchfab model by
 * lucq22 (CC BY 4.0, https://sketchfab.com/3d-models/old-ropebridge-low-poly-6e88260f326b488a92778b9ef43fdd67),
 * whose layout this reproduces from its preview image: four wooden posts, two sagging foot
 * ropes carrying a walkway of separate wooden planks with gaps between them, two handrail ropes
 * sagging between the post tops, and a vertical rope at every plank tying each handrail down to
 * its foot rope.
 *
 * There is no stiff deck: the walkway *is* the foot ropes plus the planks lying across them,
 * so it hangs in the ropes' own sag and moves with them. Each plank is four corner particles
 * (two on each foot rope) held rigid by its four sides and both diagonals - six distances
 * between four points, which pins the shape completely. With no interior particles a plank has
 * nothing to sag across its width (the membrane problem [buildFloorBeams] exists to solve for
 * the steel decks). Only plank faces get collision triangles, so the gaps between planks are
 * real gaps. Invisible side netting (collision only) between each foot rope and its handrail
 * keeps the load from rolling off the edge when the walkway sways.
 *
 * Every rope (foot ropes, handrails, verticals) is a tension-only [MeshSprings] - a rope can't
 * push - and everything is **built already in its hanging shape**: the walkway on a parabola
 * [sag] below the posts' foot-rope ties, each handrail [railHeight] above it, with every rest
 * length measured from those as-built positions. A rope started straight would have to drop
 * into its sag through the integrator, a large initial transient; starting it at (nearly) its
 * equilibrium shape means gravity only adds the ropes' own small stretch.
 *
 * The posts are static ([buildFlagpole], every particle pinned).
 */
data class SuspensionBridgeScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val constraints: List<Constraint>,
    val collisions: SurfaceCollisionSystem,
    /** `walkway[row][side]` - two rows per plank (its near and far edge), `side` 0 = left foot
     * rope, 1 = right. Every walkway particle is a plank corner sitting on a foot rope. */
    val walkway: List<List<Int>>,
    /** Plank faces only - no triangles across the gaps between planks. */
    val walkwaySurface: Surface,
    /** The four posts (near-left, near-right, far-left, far-right), base-to-top. */
    val posts: List<List<Int>>,
    /** Each side's foot rope as a polyline, near post tie to far post tie. */
    val footRopes: List<List<Int>>,
    /** Each side's handrail as a polyline, near post top to far post top. */
    val handrails: List<List<Int>>,
    /** `(handrailId, walkwayId)` pairs, one per vertical rope. */
    val verticalConnections: List<Pair<Int, Int>>,
    /** Every plank edge (sides and diagonals) as plain pairs, for rendering. */
    val plankConnections: List<Pair<Int, Int>>,
    val loadId: Int,
    val loadStart: Vector3,
    val loadStartVelocity: Vector3,
)

/** Stiff tension-only ropes on light plank particles need a finer step than [BRIDGE_DT]. */
const val SUSPENSION_BRIDGE_DT = 5e-4

fun buildSuspensionBridge(
    planks: Int = 21,
    plankDepth: Double = 0.2,
    plankGap: Double = 0.1,
    width: Double = 1.2,
    sag: Double = 0.6,
    railHeight: Double = 1.0,
    /** Distance from each end plank to its posts. */
    postGap: Double = 0.2,
    plankMassPerParticle: Double = 0.05,
    plankStiffness: Double = 8000.0,
    plankDamping: Double = 6.0,
    ropeMassPerParticle: Double = 0.01,
    ropeStiffness: Double = 20000.0,
    ropeDamping: Double = 4.0,
    loadMass: Double = 1.5,
    loadRadius: Double = 0.14,
    loadSpeed: Double = 3.5,
    loadDragCoefficient: Double = 0.4,
    /** Wind velocity expression (§7.2) on the planks and the side netting - the flag's gusting
     * crosswind by default; `null` for no wind force at all. */
    windVelocity: String? = FLAG_WIND_VELOCITY,
    windDensity: Double = 1.2,
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): SuspensionBridgeScenario {
    require(planks >= 2) { "a bridge needs at least 2 planks, was $planks" }
    val halfWidth = width / 2.0
    val pitch = plankDepth + plankGap
    val walkwayLength = planks * pitch - plankGap
    val zPost = walkwayLength / 2.0 + postGap
    /** The walkway's as-built curve: 0 at the posts, -[sag] at mid-span. */
    fun walkwayY(z: Double) = -sag * (1.0 - (z / zPost) * (z / zPost))

    val walkwayGroup = placement.name("planks")
    val handrailGroup = placement.name("handrails")
    val loadGroup = placement.name("load")

    // Posts: base below the walkway, with particles exactly at the foot-rope tie (y = 0) and
    // the handrail tie (y = railHeight), so ropes attach to real post particles.
    val postSpacing = 0.2
    val postBase = -0.6
    val postTop = railHeight + 0.2
    val postSegments = Math.round((postTop - postBase) / postSpacing).toInt()
    fun postIndexAt(y: Double) = Math.round((y - postBase) / postSpacing).toInt()
    fun postPlacement(name: String, x: Double, z: Double) =
        ShapePlacement(offset = placement.offset + Vector3(x, postBase, z), instanceName = placement.name(name))
    val posts = listOf(
        "post-near-left" to Vector3(-halfWidth, 0.0, -zPost),
        "post-near-right" to Vector3(halfWidth, 0.0, -zPost),
        "post-far-left" to Vector3(-halfWidth, 0.0, zPost),
        "post-far-right" to Vector3(halfWidth, 0.0, zPost),
    ).map { (name, at) ->
        buildFlagpole(height = postTop - postBase, segments = postSegments, store = store, groups = groups, placement = postPlacement(name, at.x, at.z))
    }
    val (nearLeft, nearRight, farLeft, farRight) = posts.map { it.poleIds }
    val footTie = postIndexAt(0.0)
    val railTie = postIndexAt(railHeight)

    // Walkway: two rows (near and far plank edge) per plank, one particle per foot rope.
    val walkway = (0 until planks).flatMap { k ->
        val zNear = -walkwayLength / 2.0 + k * pitch
        listOf(zNear, zNear + plankDepth)
    }.map { z ->
        listOf(-halfWidth, halfWidth).map { x ->
            val id = store.create(position = Vector3(x, walkwayY(z), z) + placement.offset, mass = ScalarExpr.of(plankMassPerParticle))
            groups.add(walkwayGroup, id)
            id
        }
    }

    val plankEdges = ArrayList<Grid.Edge>()
    for (k in 0 until planks) {
        val (a, b) = walkway[2 * k]
        val (c, d) = walkway[2 * k + 1]
        plankEdges += listOf(Grid.Edge(a, b), Grid.Edge(c, d), Grid.Edge(a, c), Grid.Edge(b, d), Grid.Edge(a, d), Grid.Edge(b, c))
    }
    val plankTriangles = (0 until planks).flatMap { k -> Grid.triangles(listOf(walkway[2 * k], walkway[2 * k + 1])) }
    val walkwaySurface = Surface(plankTriangles, name = placement.name("walkway"))

    // Foot ropes: near post tie -> every plank corner on that side -> far post tie. The rope
    // segment *along* a plank is already that plank's own (stiff) side edge, but repeating it
    // here keeps the foot rope one continuous rope for rendering and costs nothing.
    val footRopes = listOf(
        listOf(nearLeft[footTie]) + walkway.map { it[0] } + farLeft[footTie],
        listOf(nearRight[footTie]) + walkway.map { it[1] } + farRight[footTie],
    )

    // Handrails: one particle above each plank's near-edge corner, railHeight above the
    // walkway, strung between the post tops.
    val handrailPoints = listOf(0, 1).map { side ->
        (0 until planks).map { k ->
            val below = store.position(walkway[2 * k][side])
            val id = store.create(position = below + Vector3(0.0, railHeight, 0.0), mass = ScalarExpr.of(ropeMassPerParticle))
            groups.add(handrailGroup, id)
            id
        }
    }
    val handrails = listOf(
        listOf(nearLeft[railTie]) + handrailPoints[0] + farLeft[railTie],
        listOf(nearRight[railTie]) + handrailPoints[1] + farRight[railTie],
    )
    val verticalConnections = listOf(0, 1).flatMap { side ->
        (0 until planks).map { k -> handrailPoints[side][k] to walkway[2 * k][side] }
    }

    fun ropeSprings(edges: List<Grid.Edge>, name: String) = MeshSprings(
        edges, store, stiffness = ropeStiffness, compressionStiffness = 0.0, damping = ropeDamping, name = placement.name(name),
    )
    val forces = listOf(
        MeshSprings(plankEdges, store, stiffness = plankStiffness, damping = plankDamping, name = placement.name("planks")),
        ropeSprings(footRopes.flatMap { rope -> rope.zipWithNext { a, b -> Grid.Edge(a, b) } }, "foot-ropes"),
        ropeSprings(handrails.flatMap { rope -> rope.zipWithNext { a, b -> Grid.Edge(a, b) } }, "handrail-ropes"),
        ropeSprings(verticalConnections.map { (a, b) -> Grid.Edge(a, b) }, "vertical-ropes"),
        UniformGravity(walkwayGroup, Vector3(0.0, -9.8, 0.0), name = placement.name("gravity")),
        UniformGravity(handrailGroup, Vector3(0.0, -9.8, 0.0), name = placement.name("handrail-gravity")),
    )

    // The load starts just above the first plank and rolls downhill into the sag.
    val firstPlankZ = -walkwayLength / 2.0 + plankDepth / 2.0
    val loadStart = Vector3(0.0, walkwayY(firstPlankZ) + loadRadius + 0.15, firstPlankZ) + placement.offset
    val loadStartVelocity = Vector3(0.0, 0.0, loadSpeed)
    val loadId = store.create(position = loadStart, velocity = loadStartVelocity, mass = ScalarExpr.of(loadMass), radius = ScalarExpr.of(loadRadius))
    groups.add(loadGroup, loadId)

    // Side netting, collision only (not rendered): one quad per bay between neighbouring
    // vertical ropes, foot rope to handrail, so the load bumps against the rope sides instead of
    // rolling off the edge the moment the walkway sways or tilts. Real rope bridges are often
    // netted for the same reason; here it just stands in for the verticals and handrail a ball
    // couldn't slip between anyway at this scale.
    val nettingTriangles = listOf(0, 1).flatMap { side ->
        (0 until planks - 1).flatMap { k ->
            Grid.triangles(
                listOf(
                    listOf(walkway[2 * k][side], walkway[2 * k + 2][side]),
                    listOf(handrailPoints[side][k], handrailPoints[side][k + 1]),
                ),
            )
        }
    }
    val sideNetting = Surface(nettingTriangles)

    // Wind on the planks and the side netting: the netting is the bridge's only surface facing
    // a crosswind (the planks lie flat, so they mostly feel the gusts' vertical component), so
    // it's what lets the wind actually push the bridge sideways - much as a real netted rope
    // bridge catches it.
    val wind = windVelocity?.let {
        Wind(plankTriangles + nettingTriangles, ExpressionParser.parseVector(it), density = windDensity, name = placement.name("wind"))
    }

    val collisionRules = listOf(walkwaySurface, sideNetting).map { surface ->
        SurfaceCollisionRule(
            group = loadGroup,
            surface = surface,
            restitution = 0.15,
            compressionDamping = 1.5,
            extensionDamping = 0.3,
        )
    }

    return SuspensionBridgeScenario(
        store = store,
        groups = groups,
        forces = forces + listOfNotNull(
            wind,
            UniformGravity(loadGroup, Vector3(0.0, -9.8, 0.0), name = placement.name("load-gravity")),
            Drag(loadGroup, coefficient = loadDragCoefficient, name = placement.name("load-drag")),
        ),
        constraints = posts.flatMap { it.constraints },
        collisions = SurfaceCollisionSystem(collisionRules),
        walkway = walkway,
        walkwaySurface = walkwaySurface,
        posts = posts.map { it.poleIds },
        footRopes = footRopes,
        handrails = handrails,
        verticalConnections = verticalConnections,
        plankConnections = plankEdges.map { it.a to it.b },
        loadId = loadId,
        loadStart = loadStart,
        loadStartVelocity = loadStartVelocity,
    )
}
