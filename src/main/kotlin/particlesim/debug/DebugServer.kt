package particlesim.debug

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/**
 * One-way WebSocket broadcast of debug frames (§9.1, partial) — every connected viewer gets
 * every frame via [broadcastFrame]; nothing comes back yet. Phase 8's bidirectional upgrade
 * (drag targets, §9.4) is an [onMessage] override on this same class, not a new one.
 */
class DebugServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}
    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {}
    override fun onMessage(conn: WebSocket, message: String) {}
    override fun onError(conn: WebSocket?, ex: Exception) {
        System.err.println("debug server error: ${ex.message}")
    }
    override fun onStart() {}

    fun broadcastFrame(json: String) {
        broadcast(json)
    }
}
