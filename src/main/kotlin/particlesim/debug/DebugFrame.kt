package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.physics.PairwiseForce
import java.util.Locale

/**
 * Serializes one frame of the Phase 3 debug state stream (§9.1, partial): positions of the
 * given particles and the endpoints of the given pairwise-force connections, as JSON text.
 *
 * Deliberately minimal, matching TODO.md's Phase 3 scope — no camera pose, no events, no
 * force/collider magnitude data, no binary framing. The full per-frame contract (§9.1) is
 * Phase 8's job; this only needs to be enough for `--render-all` to draw dots and lines.
 */
object DebugFrame {
    fun render(t: Double, store: ParticleStore, ids: List<Int>, connections: List<PairwiseForce>): String {
        val particles = ids.joinToString(",") { id ->
            val p = store.position(id)
            "{\"id\":$id,\"x\":${fmt(p.x)},\"y\":${fmt(p.y)},\"z\":${fmt(p.z)}}"
        }
        val lines = connections.joinToString(",") { c -> "{\"a\":${c.particleA},\"b\":${c.particleB}}" }
        return "{\"t\":${fmt(t)},\"particles\":[$particles],\"connections\":[$lines]}"
    }

    private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.6f", v)
}
