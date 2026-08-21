package particlesim.debug

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * WebSocket broadcast of per-frame state (§9.1), bidirectional (§9.4): every connected viewer
 * gets every frame via [broadcastFrame] (binary, §9.1's compact framing — [BinaryFrame]), and
 * text sent back by a viewer (drag input) is forwarded verbatim to [onTextMessage] — the exact
 * [onMessage] upgrade Phase 3 anticipated, not a new class. Deliberately doesn't know about
 * [DragMessage] itself: parsing/routing is the caller's job (see the drag demo), so this class
 * stays reusable for any future viewer input, not just drag.
 */
class DebugServer(port: Int, private val onTextMessage: ((String) -> Unit)? = null) : WebSocketServer(InetSocketAddress(port)) {

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}
    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {}
    override fun onMessage(conn: WebSocket, message: String) {
        onTextMessage?.invoke(message)
    }
    override fun onError(conn: WebSocket?, ex: Exception) {
        System.err.println("debug server error: ${ex.message}")
    }
    override fun onStart() {}

    fun broadcastFrame(buffer: ByteBuffer) {
        broadcast(buffer)
    }
}
