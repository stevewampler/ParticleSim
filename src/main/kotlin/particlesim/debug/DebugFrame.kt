package particlesim.debug

import particlesim.core.ParticleStore
import java.util.Locale

/**
 * Serializes one frame of the Phase 3 debug state stream (§9.1, partial): positions of the
 * given particles and the endpoints of the given connection lines, as JSON text.
 *
 * Connections are plain `(id, id)` pairs, not [particlesim.physics.PairwiseForce] instances
 * directly — [particlesim.physics.MeshSprings] represents many connections as *one* `Force`
 * (§9.3's chunking requirement, see its KDoc), so a single-pair-per-force assumption here
 * would miss a mesh's edges entirely. Callers flatten whatever force shapes they're using
 * (`spring.particleA to spring.particleB`, or `meshSprings.activeConnections()`) into this
 * common list.
 *
 * Deliberately minimal, matching TODO.md's Phase 3 scope — no camera pose, no events, no
 * force/collider magnitude data, no binary framing. The full per-frame contract (§9.1) is
 * Phase 8's job; this only needs to be enough for `--render-all` to draw dots and lines.
 */
object DebugFrame {
    /** [step] is the physics step index this frame was captured after (§9.4) — carried so the
     * viewer can stamp drag messages it sends back with the step they're meant for, without
     * needing to derive it from `t`/`dt` itself (fragile: floating-point `t` accumulation
     * drifting from an integer step count is exactly the kind of mismatch step-stamping exists
     * to avoid). */
    fun render(t: Double, step: Long, store: ParticleStore, ids: List<Int>, connections: List<Pair<Int, Int>>): String {
        val particles = ids.joinToString(",") { id ->
            val p = store.position(id)
            "{\"id\":$id,\"x\":${fmt(p.x)},\"y\":${fmt(p.y)},\"z\":${fmt(p.z)}}"
        }
        val lines = connections.joinToString(",") { (a, b) -> "{\"a\":$a,\"b\":$b}" }
        return "{\"t\":${fmt(t)},\"step\":$step,\"particles\":[$particles],\"connections\":[$lines]}"
    }

    private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.6f", v)
}
