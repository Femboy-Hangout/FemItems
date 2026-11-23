package dev.femitems.plugin.live

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Manages downloading/caching Paper 26.3 or Folia 26.3 from PaperMC Fill v3 API
 * and booting an isolated live server with the shaded FemItems JAR installed.
 */
class LiveServerHarness(
    val platform: String,
    val mcVersion: String,
    private val repoRoot: Path,
    private val pluginJar: Path,
) : AutoCloseable {

    val port: Int = pickFreePort()
    val serverDir: Path = repoRoot.resolve(".mc-test").resolve("live-$platform")
    private val cacheDir: Path = repoRoot.resolve(".mc-test").resolve("cache")
    private val latestLog: Path = serverDir.resolve("logs").resolve("latest.log")

    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null

    fun start(timeout: Duration = Duration.ofSeconds(180)) {
        require(Files.isRegularFile(pluginJar)) { "Shaded plugin JAR not found at $pluginJar" }

        val serverJar = ensureServerJar()
        val pluginsDir = serverDir.resolve("plugins")
        Files.createDirectories(pluginsDir)

        // Reset previous plugin JAR, config, logs, ops, and playerdata for deterministic runs
        Files.list(pluginsDir).use { entries ->
            entries.filter { it.fileName.toString().startsWith("femitems-") && it.fileName.toString().endsWith(".jar") }
                .forEach { Files.deleteIfExists(it) }
        }
        deleteRecursively(pluginsDir.resolve("FemItems"))
        deleteRecursively(serverDir.resolve("logs"))
        Files.deleteIfExists(serverDir.resolve("ops.json"))
        deleteRecursively(serverDir.resolve("world").resolve("players"))
        deleteRecursively(serverDir.resolve("world").resolve("playerdata"))
        deleteRecursively(serverDir.resolve("world").resolve("advancements"))
        deleteRecursively(serverDir.resolve("world").resolve("stats"))
        // The world persists between runs; drop its saved entities so mobs (flat-world slimes split and
        // multiply) and dropped items never pile up from one run into the next.
        deleteRecursively(serverDir.resolve("world").resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("entities"))

        Files.copy(
            pluginJar,
            pluginsDir.resolve(pluginJar.fileName),
            StandardCopyOption.REPLACE_EXISTING,
        )

        Files.writeString(serverDir.resolve("eula.txt"), "eula=true\n", StandardCharsets.US_ASCII)
        Files.writeString(
            serverDir.resolve("server.properties"),
            """
            online-mode=false
            white-list=false
            enforce-whitelist=false
            level-type=minecraft\:flat
            generate-structures=false
            allow-nether=false
            spawn-monsters=false
            spawn-animals=false
            spawn-npcs=false
            spawn-protection=0
            view-distance=2
            simulation-distance=2
            server-port=$port
            pause-when-empty-seconds=0
            """.trimIndent() + "\n",
            StandardCharsets.US_ASCII,
        )
        Files.writeString(
            serverDir.resolve("bukkit.yml"),
            """
            settings:
              allow-end: false
            """.trimIndent() + "\n",
            StandardCharsets.US_ASCII,
        )

        val stdoutLog = serverDir.resolve("live-stdout.log")
        val stderrLog = serverDir.resolve("live-stderr.log")
        Files.deleteIfExists(stdoutLog)
        Files.deleteIfExists(stderrLog)

        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val pb = ProcessBuilder(
            javaBin,
            "-Xms512M",
            "-Xmx1536M",
            "-jar",
            serverJar.toAbsolutePath().toString(),
            "--nogui",
        )
            .directory(serverDir.toFile())
            .redirectOutput(stdoutLog.toFile())
            .redirectError(stderrLog.toFile())

        val startedProc = pb.start()
        process = startedProc
        stdinWriter = BufferedWriter(OutputStreamWriter(startedProc.outputStream, StandardCharsets.UTF_8))

        awaitLog(Regex("""Done \([0-9.,]+s\)!"""), timeout = timeout)

        // 26.x replaced the `spawn-monsters` server property with this game rule; without it slimes keep
        // spawning on the flat world, killing fake players and blocking block placement.
        val logBeforeRule = readLog().length
        sendConsoleCommand("gamerule spawn_monsters false")
        awaitLog(Regex("""spawn_monsters.*false"""), fromOffset = logBeforeRule)
    }

    fun pluginConfigFile(): Path = serverDir.resolve("plugins").resolve("FemItems").resolve("config.yml")

    fun pluginDataDir(): Path = serverDir.resolve("plugins").resolve("FemItems")

    fun jukeboxSongsDir(): Path = serverDir.resolve("plugins").resolve("FemItems").resolve("jukebox").resolve("gnbs")

    fun sendConsoleCommand(command: String) {
        val writer = checkNotNull(stdinWriter) { "Server '$platform' is not running" }
        writer.write(command)
        writer.newLine()
        writer.flush()
    }

    fun readLog(): String =
        if (Files.isRegularFile(latestLog)) Files.readString(latestLog, StandardCharsets.UTF_8) else ""

    fun awaitLog(pattern: Regex, timeout: Duration = Duration.ofSeconds(30), fromOffset: Int = 0): String {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            val content = readLog()
            val slice = if (fromOffset in 0..content.length) content.substring(fromOffset) else ""
            if (pattern.containsMatchIn(slice)) {
                return content
            }
            val proc = process
            if (proc != null && !proc.isAlive) {
                throw AssertionError(
                    "[$platform] Server exited prematurely (code=${proc.exitValue()}) while waiting for $pattern.\n" +
                        "--- latest.log ---\n$content",
                )
            }
            Thread.sleep(250)
        }
        throw AssertionError(
            "[$platform] Timed out waiting for log pattern $pattern.\n--- latest.log ---\n${readLog()}",
        )
    }

    fun stopAndAssertClean(timeout: Duration = Duration.ofSeconds(60)): String {
        val proc = process ?: return readLog()
        if (proc.isAlive) {
            runCatching { sendConsoleCommand("stop") }
            val exited = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!exited) {
                proc.destroyForcibly()
                throw AssertionError("[$platform] Server failed to shut down cleanly within ${timeout.seconds}s.")
            }
        }
        val exitCode = proc.exitValue()
        val logText = readLog()
        if (exitCode != 0) {
            throw AssertionError("[$platform] Server exited with non-zero code $exitCode.\n--- latest.log ---\n$logText")
        }

        val forbidden = listOf(
            Regex("Could not load plugin"),
            Regex("Error occurred while enabling FemItems"),
            Regex("Error occurred while disabling FemItems"),
            Regex("UnsupportedOperationException"),
            Regex("(?is)exception.{0,200}dev\\.femitems"),
            Regex("(?is)dev\\.femitems.{0,200}exception"),
            Regex("(?is)off owning region"),
        )
        for (pattern in forbidden) {
            if (pattern.containsMatchIn(logText)) {
                throw AssertionError("[$platform] Forbidden error pattern matched ($pattern).\n--- latest.log ---\n$logText")
            }
        }
        return logText
    }

    override fun close() {
        val proc = process ?: return
        if (proc.isAlive) {
            runCatching { sendConsoleCommand("stop") }
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        }
        runCatching { stdinWriter?.close() }
        process = null
        stdinWriter = null
    }

    private fun ensureServerJar(): Path {
        Files.createDirectories(cacheDir)
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build()

        val apiUrl = "https://fill.papermc.io/v3/projects/$platform/versions/$mcVersion/builds/latest"
        val metaRequest = HttpRequest.newBuilder(URI.create(apiUrl))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val metaResponse = client.send(metaRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        check(metaResponse.statusCode() == 200) {
            "[$platform] PaperMC Fill v3 API returned HTTP ${metaResponse.statusCode()} for $apiUrl"
        }

        val body = metaResponse.body()
        val defaultSection = body.substringAfter("\"server:default\"")
        val jarName = extractJsonString(defaultSection, "name")
        val jarUrl = extractJsonString(defaultSection, "url")
        val expectedSha256 = extractJsonString(defaultSection, "sha256").lowercase(Locale.ROOT)
        val jarPath = cacheDir.resolve(jarName)

        if (Files.isRegularFile(jarPath) && sha256(jarPath) == expectedSha256) {
            return jarPath
        }

        val downloadRequest = HttpRequest.newBuilder(URI.create(jarUrl))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val tempPath = cacheDir.resolve("$jarName.part")
        val downloadResponse = client.send(downloadRequest, HttpResponse.BodyHandlers.ofFile(tempPath))
        check(downloadResponse.statusCode() == 200) {
            Files.deleteIfExists(tempPath)
            "[$platform] Failed to download $jarName (HTTP ${downloadResponse.statusCode()})"
        }
        val actualSha = sha256(tempPath)
        if (actualSha != expectedSha256) {
            Files.deleteIfExists(tempPath)
            throw IllegalStateException("[$platform] SHA-256 mismatch for $jarName: expected $expectedSha256, got $actualSha")
        }
        Files.move(tempPath, jarPath, StandardCopyOption.REPLACE_EXISTING)
        return jarPath
    }

    private fun extractJsonString(json: String, field: String): String {
        val regex = Regex(""""$field"\s*:\s*"([^"]+)"""")
        return regex.find(json)?.groupValues?.get(1)
            ?: throw IllegalStateException("Could not extract JSON field '$field' from Fill API response")
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun pickFreePort(): Int = ServerSocket(0).use { it.localPort }

    companion object {
        private const val USER_AGENT = "FemItems-LiveTest/1.0 (https://github.com/Femboy-Hangout/FemItems)"
    }
}
