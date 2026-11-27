package dev.femitems.plugin.items

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A built resource pack: zip bytes plus the SHA-1 clients use to verify and cache it. */
class BuiltResourcePack(val bytes: ByteArray) {
    val sha1: String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    val size: Int get() = bytes.size
}

/**
 * Builds the client resource pack for all custom items (Minecraft 26.3, resource pack format 97):
 * - `assets/<ns>/items/<id>.json`      item model definition (what the `item_model` component points at)
 * - `assets/<ns>/models/item/<id>.json` flat 2D model using the item's texture
 * - `assets/<ns>/textures/item/...`    item textures (kept under `item/` so they land in the item atlas)
 * - `assets/<ns>/equipment/<name>.json` + `textures/entity/equipment/humanoid[_leggings]/<name>.png`
 *   for worn armour.
 *
 * Output is deterministic (sorted entries, fixed timestamps), so an unchanged config keeps the same hash and
 * players are not asked to download it again.
 */
object ResourcePackBuilder {
    const val PACK_FORMAT = 97
    private const val FIXED_TIME = 315532800000L // 1980-01-01, the earliest zip timestamp

    fun build(packs: List<ContentPack>, description: String = "FemItems custom items", extra: IncludedFiles = IncludedFiles.EMPTY): BuiltResourcePack =
        BuiltResourcePack(zip(entries(packs, description, extra)))

    /** All pack files as path -> bytes; exposed for tests. [extra] files are added as-is (see [IncludedFiles]). */
    fun entries(packs: List<ContentPack>, description: String = "FemItems custom items", extra: IncludedFiles = IncludedFiles.EMPTY): Map<String, ByteArray> {
        val files = TreeMap<String, ByteArray>()
        files["pack.mcmeta"] = json(
            """{"pack":{"description":${quote(description)},"pack_format":$PACK_FORMAT,"min_format":$PACK_FORMAT,"max_format":$PACK_FORMAT}}""",
        )
        for (pack in packs) {
            val ns = pack.namespace
            for (item in pack.items) {
                val textureRef = "$ns:${itemTexturePath(item.texture)}"
                files["assets/$ns/items/${item.key}.json"] =
                    json("""{"model":{"type":"minecraft:model","model":${quote("$ns:item/${item.key}")}}}""")
                files["assets/$ns/models/item/${item.key}.json"] =
                    json("""{"parent":${quote(item.modelParent)},"textures":{"layer0":${quote(textureRef)}}}""")
                files["assets/$ns/textures/${itemTexturePath(item.texture)}.png"] = Files.readAllBytes(pack.texturePath(item.texture))
            }
            for (equipment in pack.equipment) {
                val layers = ArrayList<String>()
                equipment.layer1?.let { layer ->
                    files["assets/$ns/textures/entity/equipment/humanoid/${equipment.key}.png"] = Files.readAllBytes(pack.texturePath(layer))
                    layers += """"humanoid":[{"texture":${quote("$ns:${equipment.key}")}}]"""
                }
                equipment.layer2?.let { layer ->
                    files["assets/$ns/textures/entity/equipment/humanoid_leggings/${equipment.key}.png"] = Files.readAllBytes(pack.texturePath(layer))
                    layers += """"humanoid_leggings":[{"texture":${quote("$ns:${equipment.key}")}}]"""
                }
                files["assets/$ns/equipment/${equipment.key}.json"] = json("""{"layers":{${layers.joinToString(",")}}}""")
            }
        }
        for ((path, data) in extra.files) {
            if (path in files) throw ContentPackException(extra.fileFor(path), "resourcepack/include/$path clashes with a file FemItems generates for custom items; rename or remove it")
            files[path] = data
        }
        return files
    }

    /** Item textures must live under `textures/item/` to be stitched into the item atlas. */
    fun itemTexturePath(texture: String): String = if (texture.startsWith("item/")) texture else "item/$texture"

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, data) in files) {
                val entry = ZipEntry(path)
                entry.time = FIXED_TIME
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun json(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

    fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}

/**
 * Raw resource pack files from `resourcepack/include/` (e.g. music disc sounds, `sounds.json`, lang
 * overrides), added unchanged to every pack FemItems builds. Only an `assets/...` tree is used: the pack's
 * own `pack.mcmeta`/`pack.png` and anything else at the top level are ignored and listed in [ignored].
 */
class IncludedFiles(val folder: Path?, val files: Map<String, ByteArray>, val ignored: List<String>) {
    val size: Long get() = files.values.sumOf { it.size.toLong() }

    /** Errors point at the include folder (not a binary file the error-line lookup would try to read). */
    fun fileFor(@Suppress("UNUSED_PARAMETER") path: String): File = folder?.toFile() ?: File("resourcepack/include")

    companion object {
        val EMPTY = IncludedFiles(null, emptyMap(), emptyList())

        /** The client refuses server packs larger than this. */
        const val MAX_TOTAL_BYTES = 250L * 1024 * 1024
        private val ASSET_PATH = Regex("assets/[a-z0-9_.-]+/[a-z0-9_./-]+")

        /** Reads [folder] (missing = nothing included). Throws [ContentPackException] for unusable files. */
        fun load(folder: Path): IncludedFiles {
            if (!Files.isDirectory(folder)) return EMPTY
            val files = TreeMap<String, ByteArray>()
            val ignored = ArrayList<String>()
            var total = 0L
            val paths = Files.walk(folder).use { stream ->
                stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.sorted().toList()
            }
            for (path in paths) {
                val relative = folder.relativize(path).joinToString("/") { it.toString() }
                if (!relative.startsWith("assets/")) {
                    ignored += relative
                    continue
                }
                if (!ASSET_PATH.matches(relative) || relative.split('/').any { it == ".." || it == "." || it.isEmpty() }) {
                    throw ContentPackException(
                        folder.toFile(),
                        "resourcepack/include/$relative: Minecraft only loads lowercase paths (a-z, 0-9, _ . - /); rename it",
                    )
                }
                total += Files.size(path)
                if (total > MAX_TOTAL_BYTES) {
                    throw ContentPackException(folder.toFile(), "resourcepack/include is larger than 250 MB, which clients refuse to download")
                }
                files[relative] = Files.readAllBytes(path)
            }
            return IncludedFiles(folder, files, ignored)
        }
    }
}
