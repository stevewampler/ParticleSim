package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.Force
import particlesim.physics.MeshSprings
import particlesim.physics.UniformGravity
import particlesim.surface.Grid

/**
 * Triangulated floor beams (cross-girders) under a deck that is hung only at its two edges -
 * [buildArchBridge]'s vertical hangers, [buildCableStayedBridge]'s stays. A deck held only at
 * its edges is otherwise a flat sheet of springs spanning its full width: a membrane with almost
 * no bending stiffness, so its centreline sags far below the hung edges. A floor beam carries
 * the deck's weight across its width out to those edge supports, the job a real bridge's
 * cross-girders do.
 *
 * One beam per entry in [beamRows]: a bottom chord of particles [depth] below that deck row's
 * interior columns, tied to the deck row above by verticals and X-diagonals, with the outermost
 * bottom-chord particles running diagonally up to the deck-edge particles where the hangers or
 * stays attach - so the deck row and its beam form one Pratt-style truss across the width. Rest
 * lengths come from the as-built positions ([MeshSprings]' own convention), so every member
 * starts at zero strain.
 *
 * Neighbouring beams are also braced to each other along the road (a bottom stringer plus an X
 * of diagonals up to the deck under every interior column) - without that, each beam is only a
 * truss across the road and swings forward and back along it like a pendulum.
 *
 * **Depth matters more than stiffness.** On the arch bridge a shallow (0.25) beam with light
 * (0.03) particles snapped through partway through a run - top and bottom chords pulled level
 * with each other - and left the deck sagging ~0.5 at mid-span no matter how stiff its springs
 * were; 0.45 deep held in every mass/stiffness combination tried.
 */
data class FloorBeamsScenario(
    val forces: List<Force>,
    /** Every floor-beam spring as a plain pair, for rendering as line segments. */
    val connections: List<Pair<Int, Int>>,
    /** The new bottom-chord particles (not the deck particles the beams also connect to). */
    val beamIds: List<Int>,
)

fun buildFloorBeams(
    deckGrid: List<List<Int>>,
    beamRows: List<Int>,
    depth: Double = 0.45,
    massPerParticle: Double = 0.08,
    stiffness: Double = 6000.0,
    damping: Double = 8.0,
    store: ParticleStore,
    groups: Groups,
    placement: ShapePlacement = ShapePlacement(),
): FloorBeamsScenario {
    val cols = deckGrid[0].size
    require(cols >= 3) { "a floor beam needs at least one interior deck column, cols was $cols" }

    val group = placement.name("floor-beams")
    val edges = ArrayList<Grid.Edge>()
    val beamIds = ArrayList<Int>()
    val bottoms = ArrayList<Map<Int, Int>>()
    val sortedRows = beamRows.sorted()
    for (r in sortedRows) {
        val top = deckGrid[r]
        val bottom = (1 until cols - 1).associateWith { c ->
            val id = store.create(position = store.position(top[c]) + Vector3(0.0, -depth, 0.0), mass = ScalarExpr.of(massPerParticle))
            groups.add(group, id)
            beamIds += id
            id
        }
        for (c in 1 until cols - 1) edges += Grid.Edge(top[c], bottom.getValue(c))
        for (c in 1 until cols - 2) edges += Grid.Edge(bottom.getValue(c), bottom.getValue(c + 1))
        edges += Grid.Edge(top[0], bottom.getValue(1))
        edges += Grid.Edge(top[cols - 1], bottom.getValue(cols - 2))
        for (c in 1 until cols - 2) {
            edges += Grid.Edge(top[c], bottom.getValue(c + 1))
            edges += Grid.Edge(top[c + 1], bottom.getValue(c))
        }
        bottoms += bottom
    }

    // Longitudinal bracing between neighbouring beams. Each beam on its own is only a truss
    // *across* the road - nothing stops it swinging forward and back along the road like a
    // pendulum hanging off its deck row. So between every pair of neighbouring beams, under
    // every interior column, a bottom stringer ties the two beams' bottom chords together and
    // an X of diagonals runs from each bottom particle up to the other beam's deck particle,
    // triangulating the vertical plane along the road as well.
    for (i in 0 until sortedRows.size - 1) {
        val (rA, rB) = sortedRows[i] to sortedRows[i + 1]
        for (c in 1 until cols - 1) {
            val bottomA = bottoms[i].getValue(c)
            val bottomB = bottoms[i + 1].getValue(c)
            edges += Grid.Edge(bottomA, bottomB)
            edges += Grid.Edge(bottomA, deckGrid[rB][c])
            edges += Grid.Edge(bottomB, deckGrid[rA][c])
        }
    }

    return FloorBeamsScenario(
        forces = listOf(
            MeshSprings(edges, store, stiffness = stiffness, damping = damping, name = placement.name("floor-beams")),
            UniformGravity(group, Vector3(0.0, -9.8, 0.0), name = placement.name("floor-beam-gravity")),
        ),
        connections = edges.map { it.a to it.b },
        beamIds = beamIds,
    )
}
