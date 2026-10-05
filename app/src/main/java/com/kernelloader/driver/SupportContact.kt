package com.kernelloader.driver

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLEncoder

object SupportContact {
    
    const val APP_NAME = "Kernel Loader"

    const val WHATSAPP_NUMBER = "8801785917145"

    const val WHATSAPP_DISPLAY = "+880 1785-917145"

    fun defaultMessage(kernelRelease: String?): String {
        val model = (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).trim()
        val k = kernelRelease?.takeIf { it.isNotBlank() } ?: "unknown"
        val android = Build.VERSION.RELEASE ?: "?"
        val sdk = Build.VERSION.SDK_INT
        return buildString {
            appendLine("╔══════════════════════════╗")
            appendLine("   ⚡  KERNEL LOADER SUPPORT  ⚡")
            appendLine("╚══════════════════════════╝")
            appendLine()
            appendLine("📱 Device  : $model")
            appendLine("🐧 Kernel  : $k")
            appendLine("🤖 Android : $android (API $sdk)")
            appendLine("📦 App     : $APP_NAME")
            appendLine()
            appendLine("❗ No matching loader for this kernel version in the database.")
            appendLine("🛠️ Please build a custom loader (.ko) for this kernel.")
            appendLine()
            append("➖➖➖➖➖➖➖➖➖➖➖➖")
        }
    }

    fun messageWithCustom(kernelRelease: String?, custom: String): String {
        val base = defaultMessage(kernelRelease)
        val extra = custom.trim()
        return if (extra.isEmpty()) base
        else base + "\n\n📝 My note:\n" + extra + "\n\n➖➖➖➖➖➖➖➖➖➖➖➖"
    }

    fun failureReport(
        kernelRelease: String?,
        appVersion: String,
        result: String,
        logText: String,
        maxLogChars: Int = 2200
    ): String {
        val model = (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).trim()
        val k = kernelRelease?.takeIf { it.isNotBlank() } ?: "unknown"
        val android = Build.VERSION.RELEASE ?: "?"
        val tail = logText.lines()
            .filter { it.isNotBlank() }
            .takeLast(30)
            .joinToString("\n")
            .takeLast(maxLogChars)
        return buildString {
            appendLine("╔══════════════════════════╗")
            appendLine("   ⚡ KERNEL LOADER REPORT ⚡")
            appendLine("╚══════════════════════════╝")
            appendLine()
            appendLine("📱 Device  : $model")
            appendLine("🐧 Kernel  : $k")
            appendLine("🤖 Android : $android (API ${Build.VERSION.SDK_INT})")
            appendLine("📦 App     : $APP_NAME $appVersion")
            appendLine("📊 Result  : ${result.take(120)}")
            appendLine()
            appendLine("📝 LOG:")
            append(tail.ifBlank { "(empty)" })
        }
    }

    fun waLinkFull(kernelRelease: String?, appVersion: String, result: String, logText: String): String =
        "https://wa.me/$WHATSAPP_NUMBER?text=" +
                URLEncoder.encode(failureReport(kernelRelease, appVersion, result, logText), "UTF-8")

    fun waLinkReport(reportText: String): String =
        "https://wa.me/$WHATSAPP_NUMBER?text=" +
                URLEncoder.encode(reportText, "UTF-8")

    /**
     * Opens WhatsApp with the report ZIP attached and a one-line caption.
     * jid extra targets the dev chat directly on most WhatsApp builds; when the
     * build ignores it, WhatsApp's own send-to screen opens with everything filled.
     * Returns false when no WhatsApp installation could be launched.
     */
    fun sendZipViaWhatsApp(context: Context, zip: File, caption: String): Boolean {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", zip
        )
        fun newIntent(): Intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            putExtra("jid", "$WHATSAPP_NUMBER@s.whatsapp.net")
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        val targets = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (pkg in targets) {
            val intent = newIntent().apply { setPackage(pkg) }
            val resolved = try {
                context.packageManager.resolveActivity(intent, 0)
            } catch (_: Exception) {
                null
            }
            if (resolved != null) {
                return try {
                    context.startActivity(intent)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
        return try {
            context.startActivity(Intent.createChooser(newIntent(), "Send report via WhatsApp"))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun issueUrlFromReport(reportText: String, issueTitle: String): String =
        "https://github.com/bmjubairdadu/kernel-loader/issues/new?title=" +
                URLEncoder.encode(issueTitle, "UTF-8") + "&body=" +
                URLEncoder.encode(
                    "Auto build request from $APP_NAME — device info collected on-device.\n\n```\n" +
                            reportText + "\n```",
                    "UTF-8"
                )

    fun issueUrl(kernelRelease: String?, appVersion: String, result: String, logText: String): String {
        val short = kernelRelease?.let {
            Regex("""(\d+)\.(\d+)\.(\d+)""").find(it)?.value
        } ?: "unknown-kernel"
        val title = "[AUTO-REPORT] load failed on $short"
        val body = "Auto failure report from $APP_NAME $appVersion.\n\n```\n" +
                failureReport(kernelRelease, appVersion, result, logText) + "\n```"
        return "https://github.com/bmjubairdadu/kernel-loader/issues/new?title=" +
                URLEncoder.encode(title, "UTF-8") + "&body=" +
                URLEncoder.encode(body, "UTF-8")
    }
}
