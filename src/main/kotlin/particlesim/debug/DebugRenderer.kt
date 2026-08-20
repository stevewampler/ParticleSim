package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.physics.PairwiseForce

/**
 * The Phase 3 debug renderer entry point (§10.2's `--render-all`): starts the viewer's HTTP
 * page and WebSocket stream, and broadcasts a [DebugFrame] each time the caller's physics
 * loop calls [broadcast]. Every particle draws as a dot, every pairwise force as a line —
 * there's no renderer-declaration mechanism yet (that's the real opt-in system, Phase 9), and
 * colliders/surfaces aren't in `--render-all` yet because neither exists before Phase 4/5.
 */
class DebugRenderer(private val webSocketPort: Int = 8887, private val httpPort: Int = 8888) {
    private val wsServer = DebugServer(webSocketPort)
    private val httpServer = ViewerHttpServer(httpPort)

    fun start() {
        wsServer.start()
        httpServer.start()
        println("debug viewer: http://localhost:$httpPort  (state stream: ws://localhost:$webSocketPort)")
    }

    fun broadcast(t: Double, store: ParticleStore, ids: List<Int>, connections: List<PairwiseForce>) {
        wsServer.broadcastFrame(DebugFrame.render(t, store, ids, connections))
    }

    fun stop() {
        wsServer.stop()
        httpServer.stop()
    }
}
