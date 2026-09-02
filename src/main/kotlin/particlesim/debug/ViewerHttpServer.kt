package particlesim.debug

import com.sun.net.httpserver.HttpServer
import particlesim.render.TextureAssets
import java.net.InetSocketAddress

/**
 * Serves the viewer's static HTML page over `http://localhost` (§10.2's
 * `--render-all` mode). A real HTTP origin, not a `file://` page: three.js is loaded as an
 * ES module from a CDN in the page itself, and `file://` origins block module scripts in
 * Chrome — serving over loopback HTTP sidesteps that without vendoring three.js. Uses the
 * JDK's built-in [HttpServer] rather than a new dependency, since a single static file is
 * all this needs.
 */
class ViewerHttpServer(port: Int) {
    private val server = HttpServer.create(InetSocketAddress(port), 0)

    init {
        // Re-read from the classpath on every request rather than caching at startup: a demo
        // process can run for many minutes while the HTML itself is iterated on — re-reading
        // means `./gradlew processResources` (seconds) picks up an edit instead of a full demo
        // restart (a ~20s TIME_WAIT wait for the WebSocket port).
        server.createContext("/") { exchange ->
            val page = javaClass.getResourceAsStream("/particlesim/viewer.html")
                ?.readBytes()
                ?: error("missing bundled resource particlesim/viewer.html")
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            // Same dev-loop reasoning as re-reading the file above: without this, a browser can
            // silently serve a stale cached copy across a reload (no ETag/Last-Modified either,
            // so there's nothing for it to revalidate against) - actively misleading during
            // exactly the kind of edit-reload-retest cycle this server exists to support.
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, page.size.toLong())
            exchange.responseBody.use { it.write(page) }
        }
        // §10.2's texture-mapped surfaces: a name-keyed static asset, served once (cached
        // client-side via the response's Cache-Control, not re-requested every frame the way
        // vertex positions are) rather than pushed through the per-frame binary protocol.
        server.createContext("/textures/") { exchange ->
            val name = exchange.requestURI.path.removePrefix("/textures/").removeSuffix(".png")
            val bytes = TextureAssets.pngBytes(name)
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            } else {
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.responseHeaders.add("Cache-Control", "public, max-age=3600")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.executor = null
    }

    fun start() = server.start()
    fun stop() = server.stop(0)
}
