package com.kernelloader.driver

import android.content.Context
import com.kernelloader.root.RootChecker
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object OtaDriverStore {

    var manifestUrl: String =
        "https://raw.githubusercontent.com/bmjubairdadu/kernel-loder/drivers/drivers.json"

    val fallbackManifestUrls: List<String> = listOf(
        
        "https://cdn.jsdelivr.net/gh/bmjubairdadu/kernel-loder@drivers/drivers.json",
        "https://github.com/bmjubairdadu/kernel-loder/releases/latest/download/drivers.json"
    )

    const val RT = "rt"
    const val QX = "qx"

    data class DriverEntry(
        val version: String,
        val file: String,
        val sha256: String = "",
        val size: Long = 0L,
        val buildDate: String = "",
        
        val variant: String = ""
    )

    fun variantLabel(variant: String): String = when (variant.lowercase()) {
        RT -> "RT"
        QX -> "QX"
        else -> "ANY"
    }

    fun variantMatches(entry: DriverEntry, variant: String): Boolean {
        val want = variant.trim().lowercase()
        if (want.isEmpty()) return true
        val have = entry.variant.trim().lowercase()
        if (have.isEmpty()) return true
        return have == want
    }

    fun variantsFor(manifest: Manifest, variant: String): List<DriverEntry> {
        val want = variant.trim().lowercase()
        val exact = manifest.drivers.filter { it.variant.trim().lowercase() == want }
        val generic = manifest.drivers.filter { it.variant.isBlank() }
        return exact + generic
    }

    data class Manifest(
        val updated: String = "",
        val baseUrl: String = "",
        val drivers: List<DriverEntry> = emptyList()
    )
    sealed interface ResolveResult {
        data class Exact(val entry: DriverEntry) : ResolveResult
        data class Near(val entry: DriverEntry, val distance: Int) : ResolveResult
        data object None : ResolveResult
    }

    fun verParts(v: String): Triple<Int, Int, Int>? {
        val m = Regex("""(\d+)\.(\d+)\.(\d+)""").find(v) ?: return null
        return Triple(
            m.groupValues[1].toIntOrNull() ?: return null,
            m.groupValues[2].toIntOrNull() ?: return null,
            m.groupValues[3].toIntOrNull() ?: return null
        )
    }

    fun distance(a: String, b: String): Int {
        val pa = verParts(a) ?: return Int.MAX_VALUE
        val pb = verParts(b) ?: return Int.MAX_VALUE
        if (pa.first != pb.first) return 1000000 + kotlin.math.abs(pa.first - pb.first) * 10000
        if (pa.second != pb.second) return 100000 + kotlin.math.abs(pa.second - pb.second) * 1000
        return kotlin.math.abs(pa.third - pb.third)
    }

    fun resolve(manifest: Manifest, kernelRelease: String, variant: String = ""): ResolveResult {
        val pool = variantsFor(manifest, variant)
        if (pool.isEmpty()) return ResolveResult.None
        val short = RootChecker.kernelShortVersion(kernelRelease)
        pool.firstOrNull {
            RootChecker.kernelShortVersion(it.version) == short
        }?.let { return ResolveResult.Exact(it) }
        var best: DriverEntry? = null
        var bestD = Int.MAX_VALUE
        for (e in pool) {
            val d = distance(short, RootChecker.kernelShortVersion(e.version))
            if (d < bestD) { bestD = d; best = e }
        }
        val b = best ?: return ResolveResult.None
        val pk = verParts(short) ?: return ResolveResult.None
        val pb = verParts(RootChecker.kernelShortVersion(b.version))
            ?: return ResolveResult.None
        if (pk.first != pb.first) return ResolveResult.None
        return ResolveResult.Near(b, bestD)
    }

    fun supportedVersions(manifest: Manifest, variant: String = ""): List<String> =
        supportedEntries(manifest, variant).map { it.version }

    fun supportedEntries(manifest: Manifest, variant: String = ""): List<DriverEntry> {
        val pool = variantsFor(manifest, variant)
        val withDate = pool.filter { it.buildDate.isNotBlank() }
            .sortedByDescending { it.buildDate }
        val withoutDate = pool.filter { it.buildDate.isBlank() }
            .sortedByDescending { e ->
                verParts(e.version)?.let { it.first * 1000000 + it.second * 1000 + it.third } ?: -1
            }
        return withDate + withoutDate
    }

    fun exactFor(manifest: Manifest, kernelRelease: String, variant: String = ""): DriverEntry? {
        val short = RootChecker.kernelShortVersion(kernelRelease)
        return variantsFor(manifest, variant).firstOrNull {
            RootChecker.kernelShortVersion(it.version) == short
        }
    }

    fun fetchManifest(url: String = manifestUrl): Manifest? {
        return fetchDetailed(url).first
    }

    fun fetchDetailed(url: String = manifestUrl): Pair<Manifest?, String?> {
        val urls = listOf(url) + fallbackManifestUrls.filter { it != url }
        var lastError = "unknown error"
        for (u in urls) {
            val (manifest, error) = fetchOneDetailed(u)
            if (manifest != null) return manifest to null
            lastError = "$u -> ${error ?: "empty response"}"
        }
        return null to lastError
    }

    private fun fetchOne(url: String): Manifest? {
        return fetchOneDetailed(url).first
    }

    private fun fetchOneDetailed(url: String): Pair<Manifest?, String?> {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "KernelLoder-OTA/1.0")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null to "HTTP ${conn.responseCode}"
            }
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val manifest = parseManifest(text)
            if (manifest == null) null to "invalid drivers.json"
            else manifest to null
        } catch (e: java.net.UnknownHostException) {
            null to "no internet / DNS blocked (${e.message})"
        } catch (e: java.net.SocketTimeoutException) {
            null to "connection timed out (slow network?)"
        } catch (e: javax.net.ssl.SSLException) {
            null to "TLS failed - check device date/time (${e.message})"
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun parseManifest(text: String): Manifest? {
        return try {
            val obj = JSONObject(text)
            val arr = obj.optJSONArray("drivers") ?: return null
            val drivers = mutableListOf<DriverEntry>()
            for (i in 0 until arr.length()) {
                
                val d = arr.optJSONObject(i) ?: continue
                val version = d.optString("version", "")
                val file = d.optString("file", "")
                if (version.isEmpty() || file.isEmpty()) continue
                drivers.add(
                    DriverEntry(
                        version = version,
                        file = file,
                        sha256 = d.optString("sha256", ""),
                        size = d.optLong("size", 0L),
                        buildDate = d.optString("buildDate", ""),
                        variant = d.optString("variant", "").trim().lowercase()
                    )
                )
            }
            if (drivers.isEmpty()) return null
            Manifest(
                updated = obj.optString("updated", ""),
                baseUrl = obj.optString("baseUrl", ""),
                drivers = drivers
            )
        } catch (e: Exception) {
            null
        }
    }

    fun cacheDir(context: Context): File =
        File(context.filesDir, "ota_drivers").apply { mkdirs() }

    fun cachedFile(context: Context, entry: DriverEntry): File =
        File(cacheDir(context), entry.file.substringAfterLast('/'))

    fun downloadDriver(
        context: Context,
        baseUrl: String,
        entry: DriverEntry,
        onLog: (String, String) -> Unit
    ): File? {
        return try {
            val out = cachedFile(context, entry)
            if (out.exists() && out.length() > 0 &&
                (entry.size <= 0 || out.length() == entry.size)
            ) {
                onLog("OTA: cache hit ${out.name} (${out.length()} bytes)", "OK")
                return out
            }
            val url = if (entry.file.startsWith("http")) entry.file
                      else baseUrl.trimEnd('/') + "/" + entry.file.trimStart('/')
            onLog("OTA: downloading $url", "INFO")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000; readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "KernelLoder-OTA/1.0")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                onLog("OTA: HTTP ${conn.responseCode} for ${entry.file}", "ERR")
                return null
            }
            out.outputStream().use { o ->
                conn.inputStream.use { i ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n <= 0) break
                        o.write(buf, 0, n)
                    }
                }
            }
            conn.disconnect()
            onLog("OTA: downloaded ${out.name} (${out.length()} bytes)", "OK")
            out
        } catch (e: Exception) {
            onLog("OTA: download failed: ${e.message}", "ERR")
            null
        }
    }
}
