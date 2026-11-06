package com.kernelloader.driver

import android.content.Context
import com.kernelloader.root.RootChecker
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object OtaDriverStore {

    var manifestUrl: String =
        com.kernelloader.security.SecurityShield.decodeProtectedString("aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL2JtanViYWlyZGFkdS9rZXJuZWwtbG9hZGVyL2RyaXZlcnMvZHJpdmVycy5qc29u")

    val fallbackManifestUrls: List<String> = listOf(
        com.kernelloader.security.SecurityShield.decodeProtectedString("aHR0cHM6Ly9jZG4uanNkZWxpdnIubmV0L2doL2JtanViYWlyZGFkdS9rZXJuZWwtbG9hZGVyQGRyaXZlcnMvZHJpdmVycy5qc29u"),
        com.kernelloader.security.SecurityShield.decodeProtectedString("aHR0cHM6Ly9naXRodWIuY29tL2JtanViYWlyZGFkdS9rZXJuZWwtbG9hZGVyL3JlbGVhc2VzL2xhdGVzdC9kb3dubG9hZC9kcml2ZXJzLmpzb24=")
    )

    const val RT = "rt"
    const val QX = "qx"

    data class DeviceInfo(
        val model: String = "",
        val device: String = "",
        val product: String = "",
        val androidVersion: String = "",
        val buildId: String = "",
        val displayId: String = ""
    )

    data class DriverEntry(
        val version: String,
        val file: String,
        val sha256: String = "",
        val size: Long = 0L,
        val buildDate: String = "",
        val variant: String = "",
        val label: String = "",
        val model: String = "",
        val device: String = "",
        val androidVersion: String = "",
        val buildId: String = "",
        val supportedDevices: List<String> = emptyList()
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

    fun variantsFor(manifest: Manifest, variant: String = ""): List<DriverEntry> {
        val want = variant.trim().lowercase()
        if (want.isEmpty()) return manifest.drivers
        val exact = manifest.drivers.filter { it.variant.trim().lowercase() == want }
        val generic = manifest.drivers.filter { it.variant.isBlank() }
        val res = exact + generic
        return if (res.isNotEmpty()) res else manifest.drivers
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

    fun isExactMatch(entry: DriverEntry, kernelRelease: String, deviceVermagic: String = ""): Boolean {
        val cleanRel = kernelRelease.trim()
        val cleanVerm = deviceVermagic.trim()
        if (cleanRel.isBlank() && cleanVerm.isBlank()) return false

        val eLabel = entry.label.trim()
        val eVer = entry.version.trim()
        val eRel = eVer.substringBefore(" ").trim()

        if (cleanRel.isNotBlank()) {
            val pDev = verParts(cleanRel)
            val pEntry = verParts(eRel)
            if (pDev != null && pEntry != null && pDev != pEntry) {
                return false
            }
        }

        if (cleanVerm.isNotBlank()) {
            if (eVer.equals(cleanVerm, ignoreCase = true)) return true
            val shorterSideIsFullVermagic = listOf(eVer, cleanVerm).minByOrNull { it.length }!!.contains(' ')
            if (shorterSideIsFullVermagic &&
                (eVer.startsWith(cleanVerm, ignoreCase = true) || cleanVerm.startsWith(eVer, ignoreCase = true))
            ) return true
        }

        if (cleanRel.isNotBlank()) {
            if (eLabel.equals(cleanRel, ignoreCase = true) || eRel.equals(cleanRel, ignoreCase = true)) return true
            val relNoDirty = cleanRel.removeSuffix("-dirty")
            val labelNoDirty = eLabel.removeSuffix("-dirty")
            val eRelNoDirty = eRel.removeSuffix("-dirty")
            if (labelNoDirty.equals(relNoDirty, ignoreCase = true) || eRelNoDirty.equals(relNoDirty, ignoreCase = true)) return true
        }

        val gitRe = Regex("""-g([0-9a-f]{7,14})""", RegexOption.IGNORE_CASE)
        val devGit = gitRe.find(cleanRel)?.groupValues?.get(1)?.lowercase()
        val entryGit = gitRe.find(eLabel)?.groupValues?.get(1)?.lowercase()
            ?: gitRe.find(eRel)?.groupValues?.get(1)?.lowercase()

        if (devGit != null && entryGit != null) {
            return devGit == entryGit &&
                RootChecker.kernelShortVersion(cleanRel) == RootChecker.kernelShortVersion(eLabel)
        }

        return false
    }

    fun matchesDevice(entry: DriverEntry, info: DeviceInfo): Boolean {
        val hasDeviceConstraint = entry.device.isNotBlank() || entry.model.isNotBlank() || entry.supportedDevices.isNotEmpty()
        if (!hasDeviceConstraint) return true

        val cleanDev = info.device.trim().lowercase()
        val cleanProd = info.product.trim().lowercase()
        val cleanModel = info.model.trim().lowercase()

        if (entry.device.isNotBlank()) {
            val ed = entry.device.trim().lowercase()
            if (ed == cleanDev || ed == cleanProd || cleanProd.contains(ed)) return true
        }

        if (entry.model.isNotBlank()) {
            val em = entry.model.trim().lowercase()
            if (em == cleanModel || cleanModel.contains(em) || em.contains(cleanModel)) return true
        }

        for (target in entry.supportedDevices) {
            val t = target.trim().lowercase()
            if (t.isNotBlank() && (t == cleanDev || t == cleanProd || t == cleanModel || cleanModel.contains(t) || cleanProd.contains(t))) {
                return true
            }
        }

        return false
    }

    fun isDeviceConflict(entry: DriverEntry, info: DeviceInfo): Boolean {
        if (info.model.isBlank() && info.device.isBlank() && info.product.isBlank()) return false
        val hasDeviceConstraint = entry.device.isNotBlank() || entry.model.isNotBlank() || entry.supportedDevices.isNotEmpty()
        if (!hasDeviceConstraint) return false
        return !matchesDevice(entry, info)
    }

    fun resolve(
        manifest: Manifest,
        kernelRelease: String,
        variant: String = "",
        deviceVermagic: String = "",
        deviceInfo: DeviceInfo = DeviceInfo()
    ): ResolveResult {
        val pool = variantsFor(manifest, variant)
        if (pool.isEmpty()) return ResolveResult.None

        // 1. Exclude drivers that specify a different device (avoid mismatching across devices with same vermagic)
        val nonConflicting = pool.filter { !isDeviceConflict(it, deviceInfo) }
        val exactKernelMatches = nonConflicting.filter { isExactMatch(it, kernelRelease, deviceVermagic) }

        if (exactKernelMatches.isNotEmpty()) {
            // Rank candidates by device model, Android version, and build ID precision
            val ranked = exactKernelMatches.sortedWith(
                compareByDescending<DriverEntry> { entry ->
                    var score = 100
                    val cleanDev = deviceInfo.device.trim().lowercase()
                    val cleanModel = deviceInfo.model.trim().lowercase()
                    val cleanAnd = deviceInfo.androidVersion.trim()

                    // Exact device codename match (+50)
                    if (entry.device.isNotBlank() && (entry.device.equals(cleanDev, true) || deviceInfo.product.contains(entry.device, true))) {
                        score += 50
                    }
                    // Exact model match (+30)
                    if (entry.model.isNotBlank() && (entry.model.equals(cleanModel, true) || cleanModel.contains(entry.model, true))) {
                        score += 30
                    }
                    // Supported devices token match (+25)
                    if (entry.supportedDevices.any { cleanDev.contains(it, true) || cleanModel.contains(it, true) }) {
                        score += 25
                    }
                    // Android version match (+20)
                    if (entry.androidVersion.isNotBlank() && cleanAnd.isNotBlank() &&
                        (entry.androidVersion == cleanAnd || cleanAnd.startsWith(entry.androidVersion))
                    ) {
                        score += 20
                    }
                    // Build ID match (+15)
                    if (entry.buildId.isNotBlank() &&
                        (deviceInfo.buildId.contains(entry.buildId, true) || deviceInfo.displayId.contains(entry.buildId, true))
                    ) {
                        score += 15
                    }
                    score
                }.thenByDescending { it.buildDate }
            )
            return ResolveResult.Exact(ranked.first())
        }

        return ResolveResult.None
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

    fun exactFor(manifest: Manifest, kernelRelease: String, variant: String = "", deviceVermagic: String = ""): DriverEntry? {
        val pool = variantsFor(manifest, variant)
        return pool.firstOrNull { isExactMatch(it, kernelRelease, deviceVermagic) }
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
            val conn = com.kernelloader.security.SecurityShield.openSecureConnection(url).apply {
                connectTimeout = 15000; readTimeout = 15000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", "KernelLoder-OTA/1.0")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
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
                val label = d.optString("label", "").ifBlank { version.substringBefore(" ") }
                val model = d.optString("model", "")
                val device = d.optString("device", "")
                val androidVersion = d.optString("androidVersion", "").ifBlank { d.optString("android", "") }
                val buildId = d.optString("buildId", "").ifBlank { d.optString("displayId", "") }
                val supportedArr = d.optJSONArray("supportedDevices")
                val supportedDevices = mutableListOf<String>()
                if (supportedArr != null) {
                    for (j in 0 until supportedArr.length()) {
                        val s = supportedArr.optString(j, "").trim()
                        if (s.isNotBlank()) supportedDevices.add(s)
                    }
                }
                drivers.add(
                    DriverEntry(
                        version = version,
                        file = file,
                        sha256 = d.optString("sha256", ""),
                        size = d.optLong("size", 0L),
                        buildDate = d.optString("buildDate", ""),
                        variant = d.optString("variant", "").trim().lowercase(),
                        label = label,
                        model = model,
                        device = device,
                        androidVersion = androidVersion,
                        buildId = buildId,
                        supportedDevices = supportedDevices
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
            val expected = entry.sha256.trim().lowercase()
            if (expected.isNotBlank() && out.exists() && out.length() > 0 &&
                (entry.size <= 0 || out.length() == entry.size)
            ) {
                if (hashMatches(out, expected)) {
                    onLog("Driver cached", "OK")
                    return out
                }

                onLog("Cached driver failed sha256 - redownloading", "WARN")
                out.delete()
            }
            val primaryUrl = if (entry.file.startsWith("http")) entry.file
                      else {
                          val base = baseUrl.trimEnd('/')
                          if (base.isBlank()) {
                              manifestUrl.substringBeforeLast('/')
                          } else base
                      } + "/" + entry.file.trimStart('/')
            val cdnFallback = "https://cdn.jsdelivr.net/gh/bmjubairdadu/kernel-loader@drivers/" + entry.file.trimStart('/')
            val candidateUrls = listOf(primaryUrl, cdnFallback).distinct()

            val tmp = File(out.absolutePath + ".part")
            var downloadedOk = false

            for (url in candidateUrls) {
                try {
                    onLog("Downloading driver...", "INFO")
                    val conn = com.kernelloader.security.SecurityShield.openSecureConnection(url).apply {
                        connectTimeout = 20000; readTimeout = 60000
                        instanceFollowRedirects = true
                        useCaches = false
                        setRequestProperty("User-Agent", "KernelLoder-OTA/1.0")
                        setRequestProperty("Cache-Control", "no-cache")
                        setRequestProperty("Pragma", "no-cache")
                    }
                    conn.connect()
                    if (conn.responseCode !in 200..299) {
                        conn.disconnect()
                        onLog("Mirror failed (HTTP ${conn.responseCode}) - trying CDN fallback", "WARN")
                        continue
                    }
                    tmp.outputStream().use { o ->
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
                    if (entry.size > 0 && tmp.length() != entry.size) {
                        onLog("Wrong size (${tmp.length()} != ${entry.size}) - retrying mirror", "WARN")
                        tmp.delete()
                        continue
                    }
                    if (!hashMatches(tmp, expected)) {
                        onLog("Downloaded file failed sha256 - retrying mirror", "WARN")
                        tmp.delete()
                        continue
                    }
                    downloadedOk = true
                    break
                } catch (e: Exception) {
                    onLog("Connection error: ${e.message} - trying mirror", "WARN")
                    tmp.delete()
                }
            }

            if (!downloadedOk || !tmp.exists() || tmp.length() == 0L) {
                onLog("All mirrors failed to download driver", "ERR")
                tmp.delete()
                return null
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            onLog("Driver downloaded", "OK")
            out
        } catch (e: Exception) {
            onLog("Download failed: ${e.message}", "ERR")
            runCatching { cachedFile(context, entry).delete() }
            null
        }
    }

    private fun hashMatches(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        return try { sha256Of(file) == expected } catch (_: Exception) { false }
    }

    fun sha256Of(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
