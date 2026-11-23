package dev.femitems.plugin.items

import com.sun.net.httpserver.HttpServer
import org.bukkit.configuration.ConfigurationSection
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger

/** How players get the custom item resource pack. */
enum class PackMode(val configName: String) {
    /** You upload `resourcepack/femitems-items.zip` to a host (e.g. mc-packs.net); FemItems verifies and sends its link. */
    EXTERNAL("external"),

    /** FemItems serves the pack from its own port (only for servers players join directly, not behind a proxy). */
    SELF_HOST("self-host"),

    /** The pack is still built, but never sent. */
    OFF("off"),
}

/** `resource-pack:` section of config.yml. */
data class ResourcePackSettings(
    val mode: PackMode = PackMode.EXTERNAL,
    /** EXTERNAL: direct download link of the uploaded pack. */
    val url: String = "",
    val required: Boolean = false,
    val prompt: String = "&dFemItems custom item textures",
    /** SELF_HOST: port of the built-in web server. */
    val selfHostPort: Int = 8163,
    /** SELF_HOST: base URL players download from; blank = `http://<address the player joined with>:<port>`. */
    val selfHostPublicUrl: String = "",
) {
    /** SELF_HOST: the URL a player downloads [pack] from, given the host name they used to join. */
    fun selfHostUrlFor(pack: BuiltResourcePack, joinHost: String?): String {
        val base = selfHostPublicUrl.trim().ifEmpty { "http://${hostForUrl(joinHost)}:$selfHostPort" }.trimEnd('/')
        return "$base/${pack.sha1}.zip"
    }

    private fun hostForUrl(host: String?): String {
        val clean = host?.trim()?.removeSuffix(".")?.takeIf(String::isNotEmpty) ?: "localhost"
        return if (':' in clean && !clean.startsWith("[")) "[$clean]" else clean // IPv6 literal
    }

    companion object {
        fun from(config: ConfigurationSection?): ResourcePackSettings {
            if (config == null) return ResourcePackSettings()
            val defaults = ResourcePackSettings()
            val selfHost = config.getConfigurationSection("self-host")
            // 2.2.0 configs had enabled/port/public-url at the top level and always self-hosted.
            val legacySelfHost = !config.contains("mode") && (config.contains("port") || config.contains("public-url"))
            val modeName = config.getString("mode")?.trim()?.lowercase(Locale.ROOT)
            val mode = when {
                !config.getBoolean("enabled", true) -> PackMode.OFF
                modeName != null -> PackMode.entries.firstOrNull { it.configName == modeName }
                    ?: throw IllegalArgumentException("resource-pack.mode must be external, self-host or off")
                legacySelfHost -> PackMode.SELF_HOST
                else -> defaults.mode
            }
            val url = config.getString("url").orEmpty().trim()
            require(url.isEmpty() || url.startsWith("https://") || url.startsWith("http://")) {
                "resource-pack.url must start with https:// (or http://)"
            }
            val port = selfHost?.getInt("port", 8163) ?: config.getInt("port", 8163)
            require(port in 1..65535) { "resource-pack.self-host.port must be between 1 and 65535" }
            val publicUrl = (selfHost?.getString("public-url") ?: config.getString("public-url")).orEmpty().trim()
            require(publicUrl.isEmpty() || publicUrl.startsWith("http://") || publicUrl.startsWith("https://")) {
                "resource-pack.self-host.public-url must start with http:// or https://"
            }
            return ResourcePackSettings(
                mode = mode,
                url = url,
                required = config.getBoolean("required", false),
                prompt = config.getString("prompt") ?: defaults.prompt,
                selfHostPort = port,
                selfHostPublicUrl = publicUrl,
            )
        }
    }
}

/** Result of checking that the uploaded pack at the configured link is the pack FemItems just built. */
sealed interface ExternalPackStatus {
    data object NotConfigured : ExternalPackStatus
    data object Checking : ExternalPackStatus
    data class Verified(val url: String, val sha1: String) : ExternalPackStatus
    data class Outdated(val url: String, val remoteSha1: String) : ExternalPackStatus
    data class Unreachable(val url: String, val reason: String) : ExternalPackStatus
}

/**
 * Downloads the pack from the configured link and compares its SHA-1 with the locally built pack.
 * Blocking network I/O: only call from the AsyncScheduler.
 */
object ExternalPackVerifier {
    const val MAX_BYTES = 100L * 1024 * 1024 // mc-packs.net allows 95 MB

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL) // never follows https -> http downgrades
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun verify(url: String, expectedSha1: String): ExternalPackStatus {
        if (url.isBlank()) return ExternalPackStatus.NotConfigured
        return try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "FemItems pack check")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                response.body().close()
                return ExternalPackStatus.Unreachable(url, "HTTP ${response.statusCode()}")
            }
            val digest = MessageDigest.getInstance("SHA-1")
            var total = 0L
            response.body().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) return ExternalPackStatus.Unreachable(url, "file is larger than 100 MB")
                    digest.update(buffer, 0, read)
                }
            }
            val sha1 = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha1 == expectedSha1) ExternalPackStatus.Verified(url, sha1) else ExternalPackStatus.Outdated(url, sha1)
        } catch (e: IllegalArgumentException) {
            ExternalPackStatus.Unreachable(url, "invalid link")
        } catch (e: IOException) {
            ExternalPackStatus.Unreachable(url, e.message ?: e.javaClass.simpleName)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ExternalPackStatus.Unreachable(url, "interrupted")
        }
    }
}

/**
 * Optional built-in HTTP server (mode: self-host). Serves only the current pack at exactly `/<sha1>.zip`;
 * every other path is a 404. Runs on its own two daemon threads, never on a server thread.
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
                val method = exchange.requestMethod
                if (current == null || method !in setOf("GET", "HEAD") || exchange.requestURI.path != "/${current.sha1}.zip") {
                    exchange.sendResponseHeaders(404, -1)
                    return@use
                }
                exchange.responseHeaders.add("Content-Type", "application/zip")
                exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
                if (method == "HEAD") {
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
