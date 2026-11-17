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

    fun cleanTempDrivers(context: Context) {
        runCatching {
            val tempDir = File(context.cacheDir, "temp_drivers")
            if (tempDir.exists()) {
                tempDir.listFiles()?.forEach { it.delete() }
            }

            val oldDir = File(context.filesDir, "drivers")
            if (oldDir.exists()) {
                oldDir.deleteRecursively()
            }
        }
    }

    suspend fun loadManifest(context: Context, forceRefresh: Boolean = false): Manifest? = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cachedManifest?.let { return@withContext it }
        }

        val controllerManifestBytes = runCatching {
            val deviceId = ControllerApiClient.getDeviceId(context)
            val lic = ControllerApiClient.getLicenseKey(context)
            val apiUrl = "${ControllerApiClient.getBaseApiUrl(context)}/manifest?device_id=$deviceId&license_key=$lic"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 4000
                setRequestProperty("User-Agent", "KernelLoader/6.0")
            }
            if (conn.responseCode == 200) {
                conn.inputStream.use { it.readBytes() }
            } else if (conn.responseCode == 403) {
                ControllerApiClient.setAccessRevoked(context, true, "Revoked by controller")
                null
            } else null
        }.getOrNull()

        val remoteBytes = controllerManifestBytes ?: runCatching {
            val url = URL(REMOTE_MANIFEST_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 10000
                setRequestProperty("User-Agent", "KernelLoader/6.0")
                setRequestProperty("X-KernelLoader-Client", "Official")
            }
            if (conn.responseCode == 200) {
                conn.inputStream.use { it.readBytes() }
            } else null
        }.getOrNull()

        val rawBytes = remoteBytes ?: runCatching {
            context.assets.open(EMBEDDED_MANIFEST_ASSET).use { it.readBytes() }
        }.getOrNull() ?: return@withContext null

        val decryptedBytes = runCatching {
            DriverCrypto.decrypt(rawBytes, context)
        }.getOrNull() ?: rawBytes

        val text = decryptedBytes.toString(Charsets.UTF_8)
        val parsed = runCatching { parseManifest(text) }.getOrNull() ?: return@withContext null
        cachedManifest = parsed
        parsed
    }

    suspend fun downloadDriver(
        context: Context,
        entry: DriverEntry,
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {

        if (ControllerApiClient.isAccessRevoked(context)) {
            onProgress(ControllerApiClient.REVOKED_POPUP_MESSAGE)
            return@withContext null
        }

        val tempDir = File(context.cacheDir, "temp_drivers").apply { mkdirs() }
        val safeName = entry.file.substringAfterLast('/')
        val targetFile = File(tempDir, safeName)
        val tempFile = File(tempDir, "$safeName.tmp")

        if (targetFile.exists()) targetFile.delete()
        if (tempFile.exists()) tempFile.delete()

        onProgress("Downloading driver directly: ${entry.stem()} (${entry.file})...")

        var downloadedOk = false

        try {
            val deviceId = ControllerApiClient.getDeviceId(context)
            val lic = ControllerApiClient.getLicenseKey(context)
            val controllerDlUrl = "${ControllerApiClient.getBaseApiUrl(context)}/drivers/download/${entry.file}?license_key=$lic&device_id=$deviceId"
            val conn = (URL(controllerDlUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "KernelLoader/6.0")
                setRequestProperty("X-App-Package", context.packageName)
                setRequestProperty("X-App-Signature", DriverCrypto.getAppSignatureHash(context))
            }
            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                downloadedOk = tempFile.exists() && tempFile.length() > 0L
                if (downloadedOk) {
                    onProgress("Downloaded from Controller API (${tempFile.length()} bytes)")
                }
            } else if (code == 403) {
                ControllerApiClient.setAccessRevoked(context, true, "Revoked by controller")
                onProgress(ControllerApiClient.REVOKED_POPUP_MESSAGE)
                tempFile.delete()
                return@withContext null
            } else if (code == 423) {
                onProgress("Driver is frozen by administrator and cannot be downloaded.")
                tempFile.delete()
                return@withContext null
            }
        } catch (_: Exception) {

        }

        if (!downloadedOk) {
            val remoteUrl = "$REMOTE_DRIVER_BASE_URL${entry.file}"
            try {
                val conn = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "KernelLoader/6.0")
                    setRequestProperty("X-KernelLoader-Client", "Official")
                }
                val code = conn.responseCode
                if (code == 200) {
                    conn.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    downloadedOk = tempFile.exists() && tempFile.length() > 0L
                } else {
                    onProgress("HTTP $code error while downloading ${entry.file}")
                }
            } catch (e: Exception) {
                onProgress("Network download error: ${e.message}")
            }
        }

        if (!downloadedOk) {
            onProgress("Attempting root download fallback...")
            try {
                val remoteUrl = "$REMOTE_DRIVER_BASE_URL${entry.file}"
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
        onProgress("Driver staged in temporary storage (${targetFile.length()} bytes)")
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
            compareByDescending<DriverEntry> { normalizeSpaces(it.vermagic) == dVm && dVm.isNotEmpty() }
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
        val pool = if (variant.isBlank()) manifest.drivers else manifest.drivers.filter { it.variant == variant }
        return candidatesForEntries(pool, deviceKernel, deviceVermagic).firstOrNull()
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
