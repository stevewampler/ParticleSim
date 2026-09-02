package particlesim.surface

/** A texture coordinate for one mesh vertex (§10.2's texture-mapped surfaces) — `u`/`v` each
 * normalized to `[0,1]` over the image, the standard convention every texture-mapping library
 * (including three.js on the viewer side) expects. Not related to [TriangleClosestPoint]'s
 * `u`/`v`/`w`, which are barycentric closest-point weights, not texture coordinates. */
data class UV(val u: Double, val v: Double)

/**
 * A named collection of triangles forming one renderable/targetable mesh (§7) — the identity a
 * scene's surface is known by, distinct from [Grid.triangles]' raw geometry output. Optional
 * name, the same convention [particlesim.physics.Force] and [particlesim.collision.Collider]
 * already use: an unnamed [Surface] still works everywhere a plain `List<Triangle>` did before
 * (e.g. [particlesim.physics.Wind], which keeps taking raw triangles since it only ever acts on
 * geometry and has its own identity via its own `name`), but won't appear in §10.3's outliner —
 * "nothing shows up unless it opts in," the same policy §10.2 already applies to renderers.
 *
 * [uvs] is `null` by default (no texture-mapping data) — sparse, id-keyed the same way other
 * per-particle-but-not-every-particle data (e.g. `ParticleStore`'s mass/radius source maps)
 * already is in this codebase, rather than a parallel array indexed like `triangles`. A
 * shape builder may attach it unconditionally (`buildFlag` does, via [Grid.uvs], the same way
 * it computes `triangles` once regardless of who ends up rendering the surface) — that's just
 * static geometry metadata, cheap either way. What's actually gated behind "nothing ships unless
 * it opts in" is the *wire* cost: `particlesim.debug.BinaryFrame` only sends a mesh's `uvs` over
 * the per-frame protocol when its own [particlesim.render.SurfaceRenderer.textureName] is set,
 * regardless of whether this field happens to be populated — see that class's own doc comment.
 */
data class Surface(val triangles: List<Triangle>, val name: String? = null, val uvs: Map<Int, UV>? = null)
