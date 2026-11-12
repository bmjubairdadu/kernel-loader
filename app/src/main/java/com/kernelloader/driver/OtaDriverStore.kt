package com.kernelloader.driver

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object OtaDriverStore {

    private const val EMBEDDED_MANIFEST_ASSET = "drivers/drivers.json"
    private const val REMOTE_MANIFEST_URL = "https://raw.githubusercontent.com/bmjubairdadu/kernel-loader/drivers/drivers/drivers.json"
    private const val REMOTE_DRIVER_BASE_URL = "https://raw.githubusercontent.com/bmjubairdadu/kernel-loader/drivers/drivers/"

    data class DriverEntry(
        val kernel: String,
        val vermagic: String,
        val variant: String,
        val label: String,
        val file: String,
        val sha256: String,
        val size: Long,
        val device: String = "",
        val model: String = ""
    ) {
        fun isScript(): Boolean = file.endsWith(".sh", ignoreCase = true)

        fun stem(): String =
            if (label.isNotBlank()) label
            else file.substringAfterLast('/').removeSuffix(".sh").removeSuffix(".ko")

        fun coreVersion(): String = stem().trimEnd { it in 'a'..'z' }

        fun shortVermagic(): String {
            val raw = vermagic.trim()
            if (raw.isNotEmpty()) {
                val first = raw.split(Regex("\\s+")).firstOrNull().orEmpty()
                if (first.isNotEmpty()) return first
            }
            return kernel.trim().ifEmpty { stem() }
        }
    }

    data class Manifest(
        val schema: Int,
        val generatedAt: String,
        val drivers: List<DriverEntry>
    )

    @Volatile private var cachedManifest: Manifest? = null

    suspend fun loadManifest(context: Context, forceRefresh: Boolean = false): Manifest? = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cachedManifest?.let { return@withContext it }
        }
        val manifestFile = File(context.filesDir, "drivers_manifest.json")
        val remoteText = runCatching {
            val url = URL(REMOTE_MANIFEST_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 10000
                setRequestProperty("User-Agent", "KernelLoader/5.1")
            }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        }.getOrNull()

        val text = if (!remoteText.isNullOrBlank()) {
            runCatching { manifestFile.writeText(remoteText) }
            remoteText
        } else if (manifestFile.exists() && manifestFile.length() > 0) {
            runCatching { manifestFile.readText() }.getOrNull()
        } else {
            runCatching {
                context.assets.open(EMBEDDED_MANIFEST_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: return@withContext null

        val parsed = runCatching { parseManifest(text) }.getOrNull() ?: return@withContext null
        cachedManifest = parsed
        parsed
    }

    suspend fun downloadDriver(
        context: Context,
        entry: DriverEntry,
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val driversDir = File(context.filesDir, "drivers").apply { mkdirs() }
        val safeName = entry.file.substringAfterLast('/')
        val targetFile = File(driversDir, safeName)

        if (targetFile.exists()) {
            if (targetFile.length() > 0L && (entry.size <= 0L || targetFile.length() == entry.size)) {
                if (entry.sha256.isBlank() || computeSha256(targetFile).equals(entry.sha256, ignoreCase = true)) {
                    onProgress("Found cached driver: $safeName (${targetFile.length()} bytes)")
                    return@withContext targetFile
                }
            }
            targetFile.delete()
        }

        val remoteUrl = "$REMOTE_DRIVER_BASE_URL${entry.file}"
        onProgress("Downloading driver: ${entry.stem()} (${entry.file})...")
        val tempFile = File(driversDir, "$safeName.tmp")
        if (tempFile.exists()) tempFile.delete()

        var downloadedOk = false
        try {
            val conn = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "KernelLoader/5.1")
            }
            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var r: Int
                        while (input.read(buf).also { r = it } != -1) {
                            output.write(buf, 0, r)
                        }
                        output.flush()
                    }
                }
                downloadedOk = tempFile.exists() && tempFile.length() > 0L
            } else {
                onProgress("HTTP $code error while downloading ${entry.file}")
            }
        } catch (e: Exception) {
            onProgress("Network download error: ${e.message}")
        }

        if (!downloadedOk) {
            onProgress("Attempting root download fallback...")
            try {
                val dlCmd = "curl -k -sL -o '${tempFile.absolutePath}' '$remoteUrl' || wget --no-check-certificate -q -O '${tempFile.absolutePath}' '$remoteUrl'"
                val p = ProcessBuilder("su", "-c", dlCmd).start()
                p.waitFor()
                downloadedOk = tempFile.exists() && tempFile.length() > 0L
            } catch (_: Exception) {
            }
        }

        if (!downloadedOk) {
            onProgress("Driver download failed")
            if (tempFile.exists()) tempFile.delete()
            return@withContext null
        }

        if (entry.sha256.isNotBlank()) {
            val actualSha = computeSha256(tempFile)
            if (!actualSha.equals(entry.sha256, ignoreCase = true)) {
                onProgress("SHA256 mismatch! Expected: ${entry.sha256.take(8)}... Got: ${actualSha.take(8)}...")
                tempFile.delete()
                return@withContext null
            }
        }

        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
        onProgress("Driver downloaded successfully (${targetFile.length()} bytes)")
        targetFile
    }

    private fun computeSha256(file: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var r: Int
            while (input.read(buf).also { r = it } != -1) {
                md.update(buf, 0, r)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }

    fun candidatesForEntries(entries: List<DriverEntry>, deviceKernel: String): List<DriverEntry> {
        val v = deviceKernel.trim().substringBefore(' ').substringBefore('-').trim()
        if (v.isEmpty()) return emptyList()
        val scored = entries
            .map { it to it.coreVersion() }
            .filter { (_, core) -> core.isNotEmpty() && (v == core || v.startsWith("$core.")) }
        if (scored.isEmpty()) return emptyList()
        val best = scored.maxOf { it.second.length }
        return scored
            .filter { it.second.length == best }
            .sortedBy { it.first.stem() }
            .map { it.first }
    }

    fun isExactMatch(
        entry: DriverEntry,
        deviceKernel: String,
        deviceVermagic: String = ""
    ): Boolean {
        val v = deviceKernel.trim().substringBefore(' ').substringBefore('-').trim()
        val core = entry.coreVersion()
        if (v.isNotEmpty() && core.isNotEmpty() && (v == core || v.startsWith("$core."))) {
            return true
        }
        val dVm = deviceVermagic.trim()
        val eVm = entry.vermagic.trim()
        if (dVm.isNotEmpty() && eVm.isNotEmpty() && dVm == eVm) {
            return true
        }
        return false
    }

    fun exactFor(
        manifest: Manifest,
        deviceKernel: String,
        variant: String = "",
        deviceVermagic: String = ""
    ): DriverEntry? {
        val pool = if (variant.isBlank()) manifest.drivers else manifest.drivers.filter { it.variant == variant }
        val candidates = candidatesForEntries(pool, deviceKernel)
        if (candidates.isNotEmpty()) return candidates.first()
        return pool.firstOrNull { isExactMatch(it, deviceKernel, deviceVermagic) }
    }

    fun supportedEntries(manifest: Manifest, variant: String = ""): List<DriverEntry> {
        val pool = if (variant.isBlank()) manifest.drivers else manifest.drivers.filter { it.variant == variant }
        return pool.sortedWith(
            compareBy<DriverEntry>(
                { verParts(it.coreVersion())?.first ?: 0 },
                { verParts(it.coreVersion())?.second ?: 0 },
                { verParts(it.coreVersion())?.third ?: 0 },
                { it.stem() }
            )
        )
    }

    fun verParts(k: String): Triple<Int, Int, Int>? {
        val m = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?""").find(k.trim()) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        val c = m.groupValues[3].toIntOrNull() ?: 0
        return Triple(a, b, c)
    }

    private fun parseManifest(json: String): Manifest {
        val root = JSONObject(json)
        val schema = root.optInt("schema", 1)
        val gen = root.optString("generated_at", "")
        val arr = root.optJSONArray("drivers") ?: JSONArray()
        val out = ArrayList<DriverEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val file = o.optString("file", "")
            val vm = o.optString("vermagic", "").ifBlank { o.optString("version", "") }
            val k = o.optString("kernel", "")
                .ifBlank { o.optString("short_vermagic", "") }
                .ifBlank { vm.split(' ').firstOrNull().orEmpty() }
                .ifBlank { o.optString("label", "") }
            if (file.isBlank() || (k.isBlank() && vm.isBlank())) continue
            out += DriverEntry(
                kernel = k,
                vermagic = vm,
                variant = o.optString("variant", "a"),
                label = o.optString("label", ""),
                file = file,
                sha256 = o.optString("sha256", "").lowercase(),
                size = o.optLong("size", 0L),
                device = o.optString("device", ""),
                model = o.optString("model", "")
            )
        }
        return Manifest(schema = schema, generatedAt = gen, drivers = out)
    }
}
