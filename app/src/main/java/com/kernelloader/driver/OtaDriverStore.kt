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
        fun stem(): String =
            if (label.isNotBlank()) label
            else file.substringAfterLast('/').removeSuffix(".ko")

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
        val drivers: List<DriverEntry>,
        val killRebootDrivers: List<String> = emptyList()
    )

    @Volatile private var cachedManifest: Manifest? = null
    @Volatile private var latestLoadedCommitSha: String = ""

    private fun fetchAuthorityCommitSha(): String? {
        val gitRefUrl = "https://github.com/bmjubairdadu/kernel-loader.git/info/refs?service=git-upload-pack"
        val shaFromGit = runCatching {
            val conn = (URL(gitRefUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 1200
                readTimeout = 2000
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "git/2.0")
            }
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val m = Regex("""([0-9a-f]{40})\s+refs/heads/drivers""").find(text)
                m?.groupValues?.get(1)
            } else null
        }.getOrNull()

        if (!shaFromGit.isNullOrBlank()) {
            return shaFromGit
        }

        val atomUrl = "https://github.com/bmjubairdadu/kernel-loader/commits/drivers.atom"
        return runCatching {
            val conn = (URL(atomUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 1200
                readTimeout = 2000
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "KernelLoader/6.8")
            }
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val m = Regex("""Commit/([0-9a-f]{40})""").find(text)
                m?.groupValues?.get(1)
            } else null
        }.getOrNull()
    }

    fun cleanTempDrivers(context: Context) {
        runCatching {
            File(context.cacheDir, "temp_drivers").deleteRecursively()
            File(context.filesDir, "drivers").deleteRecursively()
            File(context.cacheDir, "ftl").deleteRecursively()
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".ko", ignoreCase = true) ||
                        f.name.endsWith(".bin", ignoreCase = true) ||
                        f.name.endsWith(".dat", ignoreCase = true) ||
                        f.name.endsWith(".tmp", ignoreCase = true))
                ) {
                    f.delete()
                }
            }
            context.filesDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".ko", ignoreCase = true) ||
                        f.name.endsWith(".bin", ignoreCase = true) ||
                        f.name.endsWith(".dat", ignoreCase = true) ||
                        f.name.endsWith(".tmp", ignoreCase = true))
                ) {
                    f.delete()
                }
            }
        }
    }

    suspend fun loadManifest(context: Context, forceRefresh: Boolean = false): Manifest? = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cachedManifest?.let { return@withContext it }
        }

        var remoteBytes: ByteArray? = null

        val localControllerUrl = "http://127.0.0.1:8088/api/v1/manifest?_ts=${System.currentTimeMillis()}"
        remoteBytes = runCatching {
            val conn = (URL(localControllerUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 80
                readTimeout = 150
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "KernelLoader/6.8")
            }
            if (conn.responseCode == 200) {
                conn.inputStream.use { it.readBytes() }
            } else null
        }.getOrNull()

        if (remoteBytes == null || remoteBytes.isEmpty()) {
            val currentRemoteSha = fetchAuthorityCommitSha()
            if (!currentRemoteSha.isNullOrBlank()) {
                if (currentRemoteSha == latestLoadedCommitSha && cachedManifest != null) {
                    return@withContext cachedManifest
                }

                val commitManifestUrl = "https://raw.githubusercontent.com/bmjubairdadu/kernel-loader/$currentRemoteSha/drivers/drivers.json"
                val bytes = runCatching {
                    val conn = (URL(commitManifestUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2000
                        readTimeout = 2500
                        useCaches = false
                        setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                        setRequestProperty("Pragma", "no-cache")
                        setRequestProperty("User-Agent", "KernelLoader/6.8")
                    }
                    if (conn.responseCode == 200) {
                        conn.inputStream.use { it.readBytes() }
                    } else null
                }.getOrNull()

                if (bytes != null && bytes.isNotEmpty()) {
                    remoteBytes = bytes
                    latestLoadedCommitSha = currentRemoteSha
                }
            }
        }

        if (remoteBytes == null || remoteBytes.isEmpty()) {
            val remoteUrls = listOf(
                "https://raw.githubusercontent.com/bmjubairdadu/kernel-loader/drivers/drivers/drivers.json",
                "https://raw.githubusercontent.com/bmjubairdadu/kernel-loader/drivers/drivers.json",
                "https://github.com/bmjubairdadu/kernel-loader/raw/drivers/drivers/drivers.json"
            )

            for (rawUrl in remoteUrls) {
                val nocacheUrl = "$rawUrl?_ts=${System.currentTimeMillis()}"
                val bytes = runCatching {
                    val url = URL(nocacheUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2500
                        readTimeout = 3000
                        useCaches = false
                        setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                        setRequestProperty("Pragma", "no-cache")
                        setRequestProperty("User-Agent", "KernelLoader/6.8")
                        setRequestProperty("X-KernelLoader-Client", "Official")
                    }
                    if (conn.responseCode == 200) {
                        conn.inputStream.use { it.readBytes() }
                    } else null
                }.getOrNull()

                if (bytes != null && bytes.isNotEmpty()) {
                    remoteBytes = bytes
                    break
                }
            }
        }

        if (remoteBytes == null && cachedManifest != null) {
            return@withContext cachedManifest
        }

        val rawBytes = remoteBytes ?: return@withContext cachedManifest

        val decryptedBytes = runCatching {
            DriverCrypto.decrypt(rawBytes, context)
        }.getOrNull() ?: rawBytes

        val text = decryptedBytes.toString(Charsets.UTF_8)
        val parsed = runCatching { parseManifest(text) }.getOrNull() ?: return@withContext cachedManifest
        cachedManifest = parsed

        val (sessionActive, _) = DriverSessionManager.isSessionActive(context)
        val curFile = DriverSessionManager.getLoadedDriverFile(context)
        val curSha = DriverSessionManager.getLoadedDriverSha(context)
        val curLabel = DriverSessionManager.getLoadedDriverLabel(context)
        val isLoaded = sessionActive || curFile.isNotBlank() || curSha.isNotBlank()

        if (isLoaded && parsed.killRebootDrivers.isNotEmpty()) {
            val devRel = com.kernelloader.root.RootChecker.getKernelRelease().orEmpty()
            val devVm = com.kernelloader.root.RootChecker.getKernelVermagic()

            val inKillList = parsed.killRebootDrivers.any { k ->
                k.isNotBlank() && (
                    (curFile.isNotBlank() && (curFile.contains(k, ignoreCase = true) || k.contains(curFile, ignoreCase = true))) ||
                    (curSha.isNotBlank() && curSha.equals(k, ignoreCase = true)) ||
                    (curLabel.isNotBlank() && curLabel.equals(k, ignoreCase = true)) ||
                    (devRel.isNotBlank() && (devRel.contains(k, ignoreCase = true) || k.contains(devRel, ignoreCase = true))) ||
                    (devVm.isNotBlank() && (devVm.contains(k, ignoreCase = true) || k.contains(devVm, ignoreCase = true)))
                )
            }

            if (inKillList) {
                DriverSessionManager.triggerDeviceReboot(context, "Driver was explicitly revoked by administrator.")
            }
        }

        parsed
    }

    suspend fun downloadDriverBytes(
        context: Context,
        entry: DriverEntry,
        onProgress: (String) -> Unit = {}
    ): ByteArray? = withContext(Dispatchers.IO) {

        cleanTempDrivers(context)
        if (!entry.file.endsWith(".ko", ignoreCase = true)) {
            onProgress("Unsupported driver format: only .ko supported")
            return@withContext null
        }

        onProgress("Downloading driver: ${entry.stem()} (${entry.file})...")

        var downloadedBytes: ByteArray? = null

        val localControllerDl = "http://127.0.0.1:8088/api/v1/drivers/download/${entry.file}"
        try {
            val conn = (URL(localControllerDl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 300
                readTimeout = 4000
                useCaches = false
                setRequestProperty("User-Agent", "KernelLoader/6.8")
            }
            if (conn.responseCode == 200) {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isNotEmpty() && matchesDriverShaBytes(bytes, entry.sha256, context)) {
                    downloadedBytes = bytes
                    onProgress("Downloaded from local controller (${bytes.size} bytes)")
                } else {
                    bytes.fill(0)
                }
            }
        } catch (_: Exception) {
        }

        if (downloadedBytes == null) {
            val mirrors = mutableListOf<String>()
            if (latestLoadedCommitSha.isNotBlank()) {
                mirrors.add("https://raw.githubusercontent.com/bmjubairdadu/kernel-loader/$latestLoadedCommitSha/drivers/${entry.file}")
            }
            mirrors.add("https://raw.githubusercontent.com/bmjubairdadu/kernel-loader/drivers/drivers/${entry.file}")
            mirrors.add("https://cdn.jsdelivr.net/gh/bmjubairdadu/kernel-loader@drivers/drivers/${entry.file}")
            mirrors.add("https://fastly.jsdelivr.net/gh/bmjubairdadu/kernel-loader@drivers/drivers/${entry.file}")
            mirrors.add("https://gcore.jsdelivr.net/gh/bmjubairdadu/kernel-loader@drivers/drivers/${entry.file}")

            for (mirrorUrl in mirrors) {
                try {
                    var curUrl = mirrorUrl
                    var bytes: ByteArray? = null
                    for (redir in 0..2) {
                        val conn = (URL(curUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 5000
                            readTimeout = 15000
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", "KernelLoader/6.8")
                            setRequestProperty("X-KernelLoader-Client", "Official")
                            setRequestProperty("Cache-Control", "no-cache")
                        }
                        val code = conn.responseCode
                        if (code in 300..399) {
                            val loc = conn.getHeaderField("Location") ?: break
                            curUrl = if (loc.startsWith("http")) loc else URL(URL(curUrl), loc).toString()
                            conn.disconnect()
                            continue
                        }
                        if (code == 200) {
                            bytes = conn.inputStream.use { it.readBytes() }
                        }
                        break
                    }
                    if (bytes != null && bytes.isNotEmpty() && matchesDriverShaBytes(bytes, entry.sha256, context)) {
                        downloadedBytes = bytes
                        onProgress("Downloaded in RAM (${bytes.size} bytes)")
                        break
                    } else {
                        bytes?.fill(0)
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (downloadedBytes == null) {
            onProgress("Attempting root memory stream fallback...")
            val rootMirrors = mutableListOf<String>()
            if (latestLoadedCommitSha.isNotBlank()) {
                rootMirrors.add("https://raw.githubusercontent.com/bmjubairdadu/kernel-loader/$latestLoadedCommitSha/drivers/${entry.file}")
            }
            rootMirrors.add("https://raw.githubusercontent.com/bmjubairdadu/kernel-loader/drivers/drivers/${entry.file}")
            rootMirrors.add("https://cdn.jsdelivr.net/gh/bmjubairdadu/kernel-loader@drivers/drivers/${entry.file}")
            rootMirrors.add("https://fastly.jsdelivr.net/gh/bmjubairdadu/kernel-loader@drivers/drivers/${entry.file}")
            for (rm in rootMirrors) {
                try {
                    val dlCmd = "curl -fsSLk --connect-timeout 5 '$rm' 2>/dev/null || wget --no-check-certificate -qO- --timeout=5 '$rm' 2>/dev/null"
                    val p = ProcessBuilder("su", "-c", dlCmd).start()
                    val bytes = p.inputStream.use { it.readBytes() }
                    val rc = p.waitFor()
                    if (rc == 0 && bytes.isNotEmpty() && matchesDriverShaBytes(bytes, entry.sha256, context)) {
                        downloadedBytes = bytes
                        onProgress("Streamed in RAM (${bytes.size} bytes)")
                        break
                    } else {
                        bytes.fill(0)
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (downloadedBytes == null) {
            onProgress("Driver download failed")
            return@withContext null
        }

        downloadedBytes
    }

    private fun matchesDriverShaBytes(rawBytes: ByteArray, expectedSha: String, context: Context): Boolean {
        if (rawBytes.isEmpty()) return false
        if (expectedSha.isBlank()) return true

        val rawSha = computeSha256Bytes(rawBytes)
        if (rawSha.equals(expectedSha, ignoreCase = true)) return true

        if (DriverCrypto.isEncrypted(rawBytes)) {
            val decBytes = runCatching { DriverCrypto.decrypt(rawBytes, context) }.getOrNull()
            if (decBytes != null && decBytes.isNotEmpty()) {
                val decSha = computeSha256Bytes(decBytes)
                decBytes.fill(0)
                if (decSha.equals(expectedSha, ignoreCase = true)) return true
            }
        }

        return false
    }

    private fun computeSha256Bytes(bytes: ByteArray): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        md.digest(bytes).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }

    fun cachedCandidates(deviceKernel: String, deviceVermagic: String = ""): List<DriverEntry> {
        val entries = cachedManifest?.drivers.orEmpty()
        if (entries.isEmpty()) return emptyList()
        return candidatesForEntries(entries, deviceKernel, deviceVermagic)
    }

    private fun normalizeSpaces(s: String): String =
        s.trim().replace(Regex("\\s+"), " ")

    private fun extractGkiKmi(releaseToken: String): String {
        val m = Regex("""^(\d+\.\d+(?:\.\d+)?-android\d+-\d+)\b""").find(releaseToken.trim())
        return m?.groupValues?.getOrNull(1) ?: ""
    }

    private fun vermagicFlagsTail(vm: String): String {
        val norm = normalizeSpaces(vm)
        val sp = norm.indexOf(' ')
        return if (sp >= 0) norm.substring(sp + 1) else ""
    }

    fun candidatesForEntries(
        entries: List<DriverEntry>,
        deviceKernel: String,
        deviceVermagic: String = ""
    ): List<DriverEntry> {
        val exact = entries.filter { isExactMatch(it, deviceKernel, deviceVermagic) }
        if (exact.isEmpty()) return emptyList()
        val dVm = normalizeSpaces(deviceVermagic)
        val dRel = deviceKernel.trim().substringBefore(' ')
        return exact.sortedWith(
            compareByDescending<DriverEntry> { it.file.endsWith(".ko", ignoreCase = true) }
                .thenByDescending { normalizeSpaces(it.vermagic) == dVm && dVm.isNotEmpty() }
                .thenByDescending { it.kernel.trim().substringBefore(' ') == dRel || it.shortVermagic() == dRel }
                .thenBy { it.stem() }
        )
    }

    fun isExactMatch(
        entry: DriverEntry,
        deviceKernel: String,
        deviceVermagic: String = ""
    ): Boolean {
        val dVm = normalizeSpaces(deviceVermagic)
        val eVm = normalizeSpaces(entry.vermagic)
        val dFlags = vermagicFlagsTail(dVm)
        val eFlags = vermagicFlagsTail(eVm)

        if (dFlags.isNotEmpty() && eFlags.isNotEmpty() && dFlags != eFlags) {
            return false
        }

        if (dVm.isNotEmpty() && eVm.isNotEmpty() && dVm == eVm) {
            return true
        }

        val dRel = deviceKernel.trim().substringBefore(' ')
        val dShort = dVm.substringBefore(' ')
        val eRel = entry.kernel.trim().substringBefore(' ')
        val eShort = entry.shortVermagic()
        val devReleases = listOf(dRel, dShort).filter { it.isNotEmpty() }
        val entReleases = listOf(eRel, eShort).filter { it.isNotEmpty() }
        if (devReleases.any { dr -> entReleases.any { er -> dr == er } }) {
            return true
        }

        val devKmis = devReleases.map { extractGkiKmi(it) }.filter { it.isNotEmpty() }
        val entKmis = entReleases.map { extractGkiKmi(it) }.filter { it.isNotEmpty() }
        if (devKmis.isNotEmpty() && entKmis.isNotEmpty() && devKmis.any { dk -> entKmis.any { ek -> dk == ek } }) {
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
        val killList = manifest.killRebootDrivers
        val dRel = deviceKernel.trim().substringBefore(' ')

        if (killList.isNotEmpty()) {
            val deviceRevoked = killList.any { k ->
                k.isNotBlank() && (
                    dRel.equals(k, ignoreCase = true) ||
                    (deviceKernel.isNotBlank() && (deviceKernel.contains(k, ignoreCase = true) || k.contains(dRel, ignoreCase = true))) ||
                    (deviceVermagic.isNotBlank() && (deviceVermagic.contains(k, ignoreCase = true) || k.contains(deviceVermagic, ignoreCase = true)))
                )
            }
            if (deviceRevoked) {
                return null
            }
        }

        val pool = if (variant.isBlank()) manifest.drivers else manifest.drivers.filter { it.variant == variant }
        val filteredPool = if (killList.isEmpty()) pool else pool.filterNot { entry ->
            killList.any { k ->
                k.isNotBlank() && (
                    entry.file.contains(k, ignoreCase = true) || k.contains(entry.file, ignoreCase = true) ||
                    entry.label.equals(k, ignoreCase = true) ||
                    entry.sha256.equals(k, ignoreCase = true) ||
                    entry.kernel.contains(k, ignoreCase = true) ||
                    k.contains(entry.kernel, ignoreCase = true) ||
                    entry.vermagic.contains(k, ignoreCase = true)
                )
            }
        }

        return candidatesForEntries(filteredPool, deviceKernel, deviceVermagic).firstOrNull()
    }

    fun supportedEntries(manifest: Manifest, variant: String = ""): List<DriverEntry> {
        val killList = manifest.killRebootDrivers
        val pool = if (variant.isBlank()) manifest.drivers else manifest.drivers.filter { it.variant == variant }
        val filteredPool = if (killList.isEmpty()) pool else pool.filterNot { entry ->
            killList.any { k ->
                k.isNotBlank() && (
                    entry.file.contains(k, ignoreCase = true) || k.contains(entry.file, ignoreCase = true) ||
                    entry.label.equals(k, ignoreCase = true) ||
                    entry.sha256.equals(k, ignoreCase = true) ||
                    entry.kernel.contains(k, ignoreCase = true) ||
                    k.contains(entry.kernel, ignoreCase = true) ||
                    entry.vermagic.contains(k, ignoreCase = true)
                )
            }
        }

        return filteredPool.sortedWith(
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
        val killArr = root.optJSONArray("kill_reboot_drivers") ?: JSONArray()
        val killList = ArrayList<String>(killArr.length())
        for (i in 0 until killArr.length()) {
            val s = killArr.optString(i, "")
            if (s.isNotBlank()) killList.add(s)
        }

        val arr = root.optJSONArray("drivers") ?: JSONArray()
        val out = ArrayList<DriverEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val file = o.optString("file", "")
            val status = o.optString("status", "")
            val note = o.optString("note", "")
            val rebootRequired = o.optBoolean("reboot_required", false)

            val isFrozenOrDisabled = status.equals("Frozen", ignoreCase = true) ||
                status.equals("Disabled", ignoreCase = true) ||
                status.equals("Revoked", ignoreCase = true) ||
                status.equals("Deleted", ignoreCase = true) ||
                note.contains("FROZEN", ignoreCase = true) ||
                (rebootRequired && (status.isBlank() || !status.equals("Active", ignoreCase = true))) ||
                killList.any { k ->
                    k.isNotBlank() && (
                        file.contains(k, ignoreCase = true) || k.contains(file, ignoreCase = true) ||
                        k.equals(o.optString("sha256", ""), ignoreCase = true) ||
                        k.equals(o.optString("label", ""), ignoreCase = true) ||
                        o.optString("kernel", "").contains(k, ignoreCase = true) ||
                        k.contains(o.optString("kernel", ""), ignoreCase = true) ||
                        o.optString("vermagic", "").contains(k, ignoreCase = true)
                    )
                }

            if (isFrozenOrDisabled) {
                continue
            }

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
        return Manifest(schema = schema, generatedAt = gen, drivers = out, killRebootDrivers = killList)
    }
}
