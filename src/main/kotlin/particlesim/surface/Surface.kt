package particlesim.surface

/**
 * A named collection of triangles forming one renderable/targetable mesh (§7) — the identity a
 * scene's surface is known by, distinct from [Grid.triangles]' raw geometry output. Optional
 * name, the same convention [particlesim.physics.Force] and [particlesim.collision.Collider]
 * already use: an unnamed [Surface] still works everywhere a plain `List<Triangle>` did before
 * (e.g. [particlesim.physics.Wind], which keeps taking raw triangles since it only ever acts on
 * geometry and has its own identity via its own `name`), but won't appear in §10.3's outliner —
 * "nothing shows up unless it opts in," the same policy §10.2 already applies to renderers.
 */
data class Surface(val triangles: List<Triangle>, val name: String? = null)
