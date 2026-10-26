package com.kernelloader.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateChecker {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val apkSize: Long,
        val notes: String,
        val publishedAt: String
    )

    sealed interface UpdateCheck {
        data class Available(val info: UpdateInfo) : UpdateCheck
        data object UpToDate : UpdateCheck
        data object Offline : UpdateCheck
    }

    private const val UA = "KernelLoder-Updater/1.0"

    fun check(
        releasesPageUrl: String,
        currentVersionCode: Int,
        currentVersionName: String = com.kernelloader.BuildConfig.VERSION_NAME
    ): UpdateCheck {
        checkFromReleasePage(releasesPageUrl, currentVersionCode, currentVersionName)?.let { return it }
        return checkFromApi(apiUrlFromPage(releasesPageUrl), currentVersionCode, currentVersionName)
    }

    private fun parseSemver(v: String): List<Int>? {
        val clean = v.trim().removePrefix("v").removePrefix("V")
        val match = Regex("""^(\d+(?:\.\d+)+)""").find(clean) ?: return null
        return match.groupValues[1].split('.').mapNotNull { it.toIntOrNull() }
    }

    private fun isSemverNewer(remote: String, current: String): Boolean {
        val r = parseSemver(remote) ?: return false
        val c = parseSemver(current) ?: return false
        val maxLen = maxOf(r.size, c.size)
        for (i in 0 until maxLen) {
            val rPart = r.getOrElse(i) { 0 }
            val cPart = c.getOrElse(i) { 0 }
            if (rPart > cPart) return true
            if (rPart < cPart) return false
        }
        return false
    }

    private fun extractVersionCode(tag: String, text: String = ""): Int? {
        val vcRegex = Regex("""(?:versionCode|vc|build)[\s:=]+(\d+)""", RegexOption.IGNORE_CASE)
        vcRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""^v?(\d+)$""").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun isUpdateAvailable(
        tag: String,
        text: String,
        currentVersionCode: Int,
        currentVersionName: String
    ): Boolean {
        if (isSemverNewer(tag, currentVersionName)) return true

        val code = extractVersionCode(tag, text)
        if (code != null && code > currentVersionCode) {
            return true
        }

        return false
    }

    private fun resolveVersionName(tag: String, text: String): String {
        val tagSem = Regex("""^v?(\d+\.\d+(?:\.\d+)*)""").find(tag.trim())
        if (tagSem != null) return tagSem.groupValues[1]

        val textSem = Regex("""(?:Kernel-Loader-|Kernel\s+Loader\s+)?v?(\d+\.\d+(?:\.\d+)*)""", RegexOption.IGNORE_CASE).find(text)
        if (textSem != null) return textSem.groupValues[1]

        return tag.removePrefix("v").ifBlank { tag }
    }

    private fun resolveVersionCode(tag: String, text: String, currentVersionCode: Int, versionName: String): Int {
        extractVersionCode(tag, text)?.let { return it }
        val sem = parseSemver(versionName) ?: parseSemver(tag)
        if (sem != null && sem.isNotEmpty()) {
            val major = sem.getOrElse(0) { 1 }
            val minor = sem.getOrElse(1) { 0 }
            val patch = sem.getOrElse(2) { 0 }
            val synthetic = major * 1000 + minor * 100 + patch
            if (synthetic > currentVersionCode) return synthetic
        }
        return currentVersionCode + 1
    }

    private fun checkFromReleasePage(
        pageUrl: String,
        currentVersionCode: Int,
        currentVersionName: String
    ): UpdateCheck? {
        val page = pageUrl.trimEnd('/')

        val (_, finalUrl) = fetch("$page/latest") ?: return null
        val tag = latestTagFromUrl(finalUrl) ?: return null
        val assetsBody = fetch("$page/expanded_assets/$tag")?.first ?: ""

        if (!isUpdateAvailable(tag, assetsBody, currentVersionCode, currentVersionName)) {
            return UpdateCheck.UpToDate
        }

        val apkHref = Regex("""href="([^"]+/releases/download/[^"]+\.apk)"""")
            .findAll(assetsBody)
            .firstOrNull()?.groupValues?.get(1)
            ?: return null
        val apkUrl = if (apkHref.startsWith("/")) "https://github.com$apkHref" else apkHref

        val resolvedName = resolveVersionName(tag, assetsBody)
        val resolvedCode = resolveVersionCode(tag, assetsBody, currentVersionCode, resolvedName)

        return UpdateCheck.Available(
            UpdateInfo(
                versionCode = resolvedCode,
                versionName = resolvedName,
                apkUrl = apkUrl,
                apkSize = headContentLength(apkUrl) ?: 0L,
                notes = "",
                publishedAt = ""
            )
        )
    }

    private fun apiUrlFromPage(pageUrl: String): String {
        val repo = Regex("""github\.com/([^/]+/[^/]+)""")
            .find(pageUrl)?.groupValues?.get(1) ?: return ""
        return "https://api.github.com/repos/$repo/releases/latest"
    }

    private fun latestTagFromUrl(url: String): String? =
        Regex("""/releases/tag/([^/?#]+)""").find(url)?.groupValues?.get(1)

    private fun fetch(url: String): Pair<String, String>? = try {
        val conn = com.kernelloader.security.SecurityShield.openSecureConnection(url).apply {
            connectTimeout = 12000; readTimeout = 12000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html,*/*")
        }
        conn.connect()
        val ok = conn.responseCode in 200..299
        val body = if (ok) conn.inputStream.bufferedReader().readText() else null
        val finalUrl = conn.url.toString()
        conn.disconnect()
        body?.let { it to finalUrl }
    } catch (e: Exception) {
        null
    }

    private fun headContentLength(url: String): Long? = try {
        val conn = com.kernelloader.security.SecurityShield.openSecureConnection(url).apply {
            connectTimeout = 12000; readTimeout = 12000
            requestMethod = "HEAD"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
        }
        conn.connect()
        val len = if (conn.responseCode in 200..299) {
            conn.contentLengthLong.takeIf { it > 0 }
        } else null
        conn.disconnect()
        len
    } catch (e: Exception) {
        null
    }

    private fun checkFromApi(
        apiUrl: String,
        currentVersionCode: Int,
        currentVersionName: String
    ): UpdateCheck {
        if (apiUrl.isBlank()) return UpdateCheck.Offline
        val text = try {
            val conn = com.kernelloader.security.SecurityShield.openSecureConnection(apiUrl).apply {
                connectTimeout = 12000; readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            conn.connect()
            val ok = conn.responseCode in 200..299
            val body = if (ok) conn.inputStream.bufferedReader().readText() else null
            conn.disconnect()
            body ?: return UpdateCheck.Offline
        } catch (e: Exception) {
            return UpdateCheck.Offline
        }

        return try {
            val rel = JSONObject(text)
            val tag = rel.optString("tag_name", "")
            val name = rel.optString("name", "")
            val body = rel.optString("body", "")
            val fullText = "$name\n$body"

            if (!isUpdateAvailable(tag, fullText, currentVersionCode, currentVersionName)) {
                return UpdateCheck.UpToDate
            }

            val assets = rel.optJSONArray("assets") ?: return UpdateCheck.UpToDate
            var apkUrl = ""
            var apkSize = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val aName = a.optString("name", "")
                if (aName.endsWith(".apk", true)) {
                    if (aName.contains("release", true) || apkUrl.isBlank()) {
                        apkUrl = a.optString("browser_download_url", "")
                        apkSize = a.optLong("size", 0L)
                    }
                }
            }
            if (apkUrl.isBlank()) return UpdateCheck.UpToDate

            val resolvedName = resolveVersionName(tag, fullText).ifBlank {
                rel.optString("name", tag.removePrefix("v"))
            }
            val resolvedCode = resolveVersionCode(tag, fullText, currentVersionCode, resolvedName)

            UpdateCheck.Available(
                UpdateInfo(
                    versionCode = resolvedCode,
                    versionName = resolvedName,
                    apkUrl = apkUrl,
                    apkSize = apkSize,
                    notes = rel.optString("body", ""),
                    publishedAt = rel.optString("published_at", "")
                )
            )
        } catch (e: Exception) {
            UpdateCheck.Offline
        }
    }

    fun getDownloadedApk(context: Context, info: UpdateInfo): File? {
        val dir = File(context.cacheDir, "updates")
        val file = File(dir, "kloader_update_${info.versionCode}.apk")
        if (file.exists() && file.isFile && file.length() >= 1024L) {
            if (info.apkSize > 0 && file.length() != info.apkSize) {
                return null
            }
            return file
        }
        return null
    }

    fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = runCatching {
        getDownloadedApk(context, info)?.let { return@runCatching it }

        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { file ->
            if (file.name != "kloader_update_${info.versionCode}.apk") {
                file.delete()
            }
        }

        val finalFile = File(dir, "kloader_update_${info.versionCode}.apk")
        val tempFile = File(dir, "kloader_update_${info.versionCode}.apk.part")
        if (tempFile.exists()) tempFile.delete()

        val conn = com.kernelloader.security.SecurityShield.openSecureConnection(info.apkUrl).apply {
            connectTimeout = 15000; readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
        }
        conn.connect()
        val httpCode = conn.responseCode
        if (httpCode !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP $httpCode")
        }

        val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
        conn.inputStream.use { input ->
            FileOutputStream(tempFile).use { output ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                var n: Int
                var lastPct = -1
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    read += n
                    if (total > 0) {
                        val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        conn.disconnect()

        if (tempFile.length() < 1024L) {
            tempFile.delete()
            throw IllegalStateException("Downloaded file too small")
        }
        if (info.apkSize > 0 && tempFile.length() != info.apkSize) {
            tempFile.delete()
            throw IllegalStateException("Size mismatch (expected ${info.apkSize}, got ${tempFile.length()})")
        }

        if (finalFile.exists()) finalFile.delete()
        if (!tempFile.renameTo(finalFile)) {
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()
        }
        onProgress(100)
        finalFile
    }

    fun downloadAndInstall(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit = {}): String {
        val result = downloadApk(context, info, onProgress)
        return result.fold(
            onSuccess = { out ->
                installApk(context, out)
                "UPDATE: APK downloaded (${out.length() / 1024} KB) - installer opened"
            },
            onFailure = { e ->
                "UPDATE: download error - ${e.message}"
            }
        )
    }

    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.packageManager.canRequestPackageInstalls()
        else true

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
