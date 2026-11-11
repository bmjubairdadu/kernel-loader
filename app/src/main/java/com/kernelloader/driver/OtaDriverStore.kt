package com.kernelloader.driver

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object OtaDriverStore {

    private const val EMBEDDED_MANIFEST_ASSET = "drivers/drivers.json"

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

    suspend fun loadManifest(context: Context): Manifest? = withContext(Dispatchers.IO) {
        cachedManifest?.let { return@withContext it }
        val text = runCatching {
            context.assets.open(EMBEDDED_MANIFEST_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@withContext null
        val parsed = runCatching { parseManifest(text) }.getOrNull() ?: return@withContext null
        cachedManifest = parsed
        parsed
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
