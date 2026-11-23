package dev.femitems.plugin.items

import com.sun.net.httpserver.HttpServer
import org.bukkit.configuration.ConfigurationSection
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger

/** `resource-pack:` section of config.yml. */
data class ResourcePackSettings(
    val enabled: Boolean = true,
    val port: Int = 8163,
    /** Base URL players download from; blank = `http://<address the player connected with>:<port>/`. */
    val publicUrl: String = "",
    val required: Boolean = false,
    val prompt: String = "&dFemItems custom item textures",
) {
    /** The URL a player downloads [pack] from, given the host name they used to join (e.g. `play.example.com`). */
    fun urlFor(pack: BuiltResourcePack, joinHost: String?): String {
        val base = publicUrl.trim().ifEmpty { "http://${hostForUrl(joinHost)}:$port" }.trimEnd('/')
        return "$base/${pack.sha1}.zip"
    }

    private fun hostForUrl(host: String?): String {
        val clean = host?.trim()?.removeSuffix(".")?.takeIf(String::isNotEmpty) ?: "localhost"
        return if (':' in clean && !clean.startsWith("[")) "[$clean]" else clean // IPv6 literal
    }

    companion object {
        fun from(config: ConfigurationSection?): ResourcePackSettings {
            if (config == null) return ResourcePackSettings()
            val port = config.getInt("port", 8163)
            require(port in 1..65535) { "resource-pack.port must be between 1 and 65535" }
            val publicUrl = config.getString("public-url").orEmpty().trim()
            require(publicUrl.isEmpty() || publicUrl.startsWith("http://") || publicUrl.startsWith("https://")) {
                "resource-pack.public-url must start with http:// or https://"
            }
            return ResourcePackSettings(
                enabled = config.getBoolean("enabled", true),
                port = port,
                publicUrl = publicUrl,
                required = config.getBoolean("required", false),
                prompt = config.getString("prompt") ?: ResourcePackSettings().prompt,
            )
        }
    }
}

/**
 * Minimal built-in HTTP server that serves the current pack at `/<sha1>.zip` (any `.zip` path returns the
 * current pack, so an old link still works). Runs on its own small thread pool, never on a server thread.
 */
class PackServer(private val logger: Logger) {
    @Volatile
    var pack: BuiltResourcePack? = null

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    var port: Int = -1
        private set

    val running: Boolean get() = server != null

    @Synchronized
    fun start(port: Int) {
        if (server != null && this.port == port) return
        stop()
        val http = HttpServer.create(InetSocketAddress(port), 0)
        http.createContext("/") { exchange ->
            exchange.use {
                val current = pack
                val path = exchange.requestURI.path
                if (exchange.requestMethod !in setOf("GET", "HEAD") || current == null || !path.endsWith(".zip")) {
                    exchange.sendResponseHeaders(404, -1)
                    return@use
                }
                exchange.responseHeaders.add("Content-Type", "application/zip")
                exchange.responseHeaders.add("Cache-Control", "no-cache")
                if (exchange.requestMethod == "HEAD") {
                    exchange.sendResponseHeaders(200, -1)
                } else {
                    exchange.sendResponseHeaders(200, current.size.toLong())
                    exchange.responseBody.write(current.bytes)
                }
            }
        }
        val pool = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "FemItems-pack-http").apply { isDaemon = true } }
        http.executor = pool
        http.start()
        server = http
        executor = pool
        this.port = port
        logger.info("Serving the custom item resource pack on port $port.")
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        executor?.shutdownNow()
        server = null
        executor = null
        port = -1
    }
}
