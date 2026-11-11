package com.kernelloader.driver

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLEncoder

object SupportContact {

    const val APP_NAME = "Kernel Loader"
    const val WHATSAPP_NUMBER = "8801785917145"
    const val WHATSAPP_DISPLAY = "+880 1785-917145"
    const val TELEGRAM_HANDLE = "@Jubairdadubm"
    const val TELEGRAM_URL = "https://t.me/Jubairdadubm"

    fun waLinkReport(reportText: String): String =
        "https://wa.me/$WHATSAPP_NUMBER?text=" +
            URLEncoder.encode(reportText, "UTF-8")

    fun sendZipViaWhatsApp(context: Context, zip: File, caption: String): Boolean {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", zip
        )
        val targets = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (pkg in targets) {
            try {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        fun newIntent(): Intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(zip.name, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            putExtra("jid", "$WHATSAPP_NUMBER@s.whatsapp.net")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
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
            val fallback = newIntent()
            val chooser = Intent.createChooser(fallback, "Send report via WhatsApp").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendZipViaTelegram(context: Context, zip: File, caption: String): Boolean {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", zip
        )
        val targets = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.thunderdog.challegram",
            "nekox.messenger"
        )
        for (pkg in targets) {
            try {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        fun newIntent(): Intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(zip.name, uri)
            putExtra(Intent.EXTRA_TEXT, "$caption\nTelegram: $TELEGRAM_HANDLE")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
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
            val fallback = newIntent()
            val chooser = Intent.createChooser(fallback, "Send report via Telegram ($TELEGRAM_HANDLE)").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun telegramDirectUrl(): String = TELEGRAM_URL
}
