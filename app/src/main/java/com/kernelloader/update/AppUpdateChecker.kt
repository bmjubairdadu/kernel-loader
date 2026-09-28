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

    fun check(apiUrl: String, currentVersionCode: Int): UpdateCheck {
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
