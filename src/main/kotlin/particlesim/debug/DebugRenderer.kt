package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.render.CameraPose
import particlesim.render.Color

/**
 * The debug-render-all viewer entry point (§10.2's `--render-all`): starts the viewer's HTTP
 * page and WebSocket stream, and broadcasts a [BinaryFrame] each time the caller's physics
 * loop calls [broadcast]. Every particle draws as a dot, every connection line passed in as a
 * line — there's no renderer-declaration mechanism wired in here (§10.2's real opt-in system
 * exists as `particlesim.render` types, but nothing consumes them over the wire yet), and
 * colliders/surfaces aren't in `--render-all` yet.
 */
class DebugRenderer(
    private val webSocketPort: Int = 8887,
    private val httpPort: Int = 8888,
    onTextMessage: ((String) -> Unit)? = null,
) {
    private val wsServer = DebugServer(webSocketPort, onTextMessage)
    private val httpServer = ViewerHttpServer(httpPort)

    fun start() {
        wsServer.start()
        httpServer.start()
        println("debug viewer: http://localhost:$httpPort  (state stream: ws://localhost:$webSocketPort)")
    }

    fun broadcast(
        t: Double,
        step: Long,
        store: ParticleStore,
        ids: List<Int>,
        connections: List<Pair<Int, Int>>,
        camera: CameraPose? = null,
        lineColors: Map<Pair<Int, Int>, Color> = emptyMap(),
    ) {
        wsServer.broadcastFrame(BinaryFrame.encode(t, step, store, ids, connections, camera, lineColors))
    }

    fun stop() {
        wsServer.stop()
        httpServer.stop()
    }
}
