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

    /**
     * The REST API rate-limits unauthenticated clients per IP, so everyone on
     * the same carrier NAT shares one tiny budget and sees "Update Check
     * Offline". The public release pages are not rate-limited: /releases/latest
     * redirects to the tag, /releases/expanded_assets/<tag> lists the assets.
     * The API JSON flow is kept only as a fallback for a page-layout change.
     */
    fun check(releasesPageUrl: String, currentVersionCode: Int): UpdateCheck {
        checkFromReleasePage(releasesPageUrl, currentVersionCode)?.let { return it }
        return checkFromApi(apiUrlFromPage(releasesPageUrl), currentVersionCode)
    }

    private fun checkFromReleasePage(pageUrl: String, currentVersionCode: Int): UpdateCheck? {
        val page = pageUrl.trimEnd('/')
        // only the final redirect URL matters here; the page body does not
        val (_, finalUrl) = fetch("$page/latest") ?: return null
        val tag = latestTagFromUrl(finalUrl) ?: return null
        val code = Regex("""v?(\d+)""").find(tag)?.groupValues?.last()?.toIntOrNull()
            ?: return null
        if (code <= currentVersionCode) return UpdateCheck.UpToDate

        val assetsBody = fetch("$page/expanded_assets/$tag")?.first ?: return null
        val apkHref = Regex("""href="([^"]+/releases/download/[^"]+\.apk)"""")
            .findAll(assetsBody)
            .firstOrNull()?.groupValues?.get(1)
            ?: return null
        val apkUrl = if (apkHref.startsWith("/")) "https://github.com$apkHref" else apkHref

        return UpdateCheck.Available(
            UpdateInfo(
                versionCode = code,
                versionName = tag.removePrefix("v").ifBlank { tag },
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
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
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
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
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

    private fun checkFromApi(apiUrl: String, currentVersionCode: Int): UpdateCheck {
        if (apiUrl.isBlank()) return UpdateCheck.Offline
        val text = try {
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
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

            val code = Regex("""v?(\d+)""").find(tag)?.groupValues?.last()?.toIntOrNull()
                ?: rel.optInt("id", 0)
            if (code <= currentVersionCode) return UpdateCheck.UpToDate

            val assets = rel.optJSONArray("assets") ?: return UpdateCheck.UpToDate
            var apkUrl = ""
            var apkSize = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name.endsWith(".apk", true)) {

                    if (name.contains("release", true) || apkUrl.isBlank()) {
                        apkUrl = a.optString("browser_download_url", "")
                        apkSize = a.optLong("size", 0L)
                    }
                }
            }
            if (apkUrl.isBlank()) return UpdateCheck.UpToDate

            UpdateCheck.Available(
                UpdateInfo(
                    versionCode = code,
                    versionName = tag.removePrefix("v").ifBlank {
                        rel.optString("name", "v$code")
                    },
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

    fun downloadAndInstall(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit = {}): String {
        return try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "kloader_update_${info.versionCode}.apk")
            val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
            }
            conn.connect()
            val httpCode = conn.responseCode
            if (httpCode !in 200..299) {
                conn.disconnect()
                return "UPDATE: download failed (HTTP $httpCode)"
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            conn.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var n: Int
                    var lastPct = -1
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            conn.disconnect()
            if (out.length() < 1024L) {
                out.delete()
                return "UPDATE: downloaded file too small - invalid APK"
            }
            installApk(context, out)
            "UPDATE: APK downloaded (${out.length() / 1024} KB) - installer opened"
        } catch (e: Exception) {
            "UPDATE: download error - ${e.message}"
        }
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
