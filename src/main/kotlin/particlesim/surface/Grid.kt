package particlesim.surface

/**
 * Grid-only mesh generation for [Surface] (§7.1) — arbitrary/general mesh triangulation is
 * out of scope; a rectangular grid of particles (`ids[row][col]`, same layout the Kotlin
 * DSL's `particles.grid(...)` produces) is all §7.3's flag needs and all this project
 * targets for now.
 */
object Grid {
    /**
     * Two triangles per grid cell, wound consistently: for a flat sheet laid out with `col`
     * along +X and `row` along +Y (the DSL grid builder's own default layout), every
     * triangle's normal points toward +Z.
     */
    fun triangles(ids: List<List<Int>>): List<Triangle> {
        val rows = ids.size
        require(rows >= 2) { "grid needs at least 2 rows, got $rows" }
        val cols = ids[0].size
        require(cols >= 2) { "grid needs at least 2 cols, got $cols" }

        val result = ArrayList<Triangle>((rows - 1) * (cols - 1) * 2)
        for (r in 0 until rows - 1) {
            for (c in 0 until cols - 1) {
                val v00 = ids[r][c]
                val v01 = ids[r][c + 1]
                val v10 = ids[r + 1][c]
                val v11 = ids[r + 1][c + 1]
                result += Triangle(v00, v01, v10)
                result += Triangle(v01, v11, v10)
            }
        }
        return result
    }

    /**
     * Per-vertex UV coordinates for a grid mesh (§10.2's texture-mapped surfaces) — `u` along
     * columns, `v` along rows, each linearly normalized to `[0,1]` over the grid's own extent
     * (a single-row or single-column grid, though [triangles] already rejects it, would divide
     * by zero here too, hence the same `rows >= 2`/`cols >= 2` guard). Falls out directly from
     * grid position with no separate authoring needed, exactly the case requirements.md §10.2
     * calls out as the practical starting point for texture mapping.
     */
    fun uvs(ids: List<List<Int>>): Map<Int, UV> {
        val rows = ids.size
        require(rows >= 2) { "grid needs at least 2 rows, got $rows" }
        val cols = ids[0].size
        require(cols >= 2) { "grid needs at least 2 cols, got $cols" }

        val result = HashMap<Int, UV>(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) {
            result[ids[r][c]] = UV(c.toDouble() / (cols - 1), r.toDouble() / (rows - 1))
        }
        return result
    }

    /** An edge between two particle ids — direction doesn't matter, used for spring topology. */
    data class Edge(val a: Int, val b: Int)

    /** Grid-adjacent edges (horizontal + vertical) — the standard cloth "structural" springs (§7.1). */
    fun structuralEdges(ids: List<List<Int>>): List<Edge> {
        val rows = ids.size
        val cols = ids[0].size
        val edges = ArrayList<Edge>()
        for (r in 0 until rows) for (c in 0 until cols - 1) edges += Edge(ids[r][c], ids[r][c + 1])
        for (r in 0 until rows - 1) for (c in 0 until cols) edges += Edge(ids[r][c], ids[r + 1][c])
        return edges
    }

    /** Per-cell diagonals — the "shear" springs that resist a square cell collapsing into a rhombus. */
    fun shearEdges(ids: List<List<Int>>): List<Edge> {
        val rows = ids.size
        val cols = ids[0].size
        val edges = ArrayList<Edge>()
        for (r in 0 until rows - 1) for (c in 0 until cols - 1) {
            edges += Edge(ids[r][c], ids[r + 1][c + 1])
            edges += Edge(ids[r][c + 1], ids[r + 1][c])
        }
        return edges
    }

    /** Skip-one-vertex edges — the "bend" springs that resist the sheet folding along a row/column. */
    fun bendEdges(ids: List<List<Int>>): List<Edge> {
        val rows = ids.size
        val cols = ids[0].size
        val edges = ArrayList<Edge>()
        for (r in 0 until rows) for (c in 0 until cols - 2) edges += Edge(ids[r][c], ids[r][c + 2])
        for (r in 0 until rows - 2) for (c in 0 until cols) edges += Edge(ids[r][c], ids[r + 2][c])
        return edges
    }
}
