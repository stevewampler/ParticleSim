package particlesim.debug

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/** [DebugServer]: a restart can bind the same port while the last run's connections linger. */
class DebugServerTest {
    /** A [DebugServer] whose start, and failure to bind, a test can wait on. */
    private class Watched(port: Int) {
        val started = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val server = object : DebugServer(port) {
            override fun onStart() { started.countDown() }
            override fun onError(conn: org.java_websocket.WebSocket?, ex: Exception) { if (conn == null) failed.countDown() }
        }
    }

    @Test
    fun `a restarted server binds the same port even with the last run's connections in TIME_WAIT`() {
        val port = ServerSocket(0).use { it.localPort }
        val first = Watched(port)
        first.server.start()
        assertTrue(first.started.await(5, TimeUnit.SECONDS))
        // A client connects; the server then closes first, leaving its side in TIME_WAIT.
        val client = Socket()
        client.connect(InetSocketAddress("localhost", port), 2000)
        first.server.stop(1000)
        client.close()

        val second = Watched(port)
        second.server.start()
        val bound = second.started.await(5, TimeUnit.SECONDS)
        second.server.stop(1000)
        assertTrue(bound && second.failed.count == 1L, "second server failed to bind port $port")
    }
}
