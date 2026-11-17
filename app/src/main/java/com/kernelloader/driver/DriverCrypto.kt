package com.kernelloader.driver

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object DriverCrypto {

    private val MAGIC = byteArrayOf(0x4B, 0x4C, 0x45, 0x4E, 0x43, 0x30, 0x30, 0x31)
    const val AUTHORIZED_PACKAGE_NAME = "com.kernelloader"
    const val AUTHORIZED_SIGNATURE_SHA256 = "abf2d7a42765af2a7edbb9633ea96092fb7228454b2ac3599e04daee611782cd"

    private val M1 = byteArrayOf(
        (-94).toByte(), (-17).toByte(), (-88).toByte(), (126).toByte(),
        (37).toByte(), (47).toByte(), (-29).toByte(), (42).toByte(),
        (-39).toByte(), (25).toByte(), (-89).toByte(), (-36).toByte(),
        (-93).toByte(), (-41).toByte(), (109).toByte(), (-68).toByte(),
        (-25).toByte(), (-52).toByte(), (-80).toByte(), (1).toByte(),
        (-93).toByte(), (0).toByte(), (46).toByte(), (-100).toByte(),
        (40).toByte(), (63).toByte(), (121).toByte(), (37).toByte(),
        (-125).toByte(), (114).toByte(), (-65).toByte(), (-74).toByte()
    )

    private val M2 = byteArrayOf(
        (6).toByte(), (-113).toByte(), (89).toByte(), (-114).toByte(),
        (8).toByte(), (-9).toByte(), (61).toByte(), (66).toByte(),
        (-57).toByte(), (45).toByte(), (100).toByte(), (111).toByte(),
        (-71).toByte(), (-107).toByte(), (79).toByte(), (23).toByte(),
        (13).toByte(), (0).toByte(), (126).toByte(), (-109).toByte(),
        (-92).toByte(), (-69).toByte(), (101).toByte(), (-77).toByte(),
        (39).toByte(), (116).toByte(), (116).toByte(), (41).toByte(),
        (9).toByte(), (48).toByte(), (73).toByte(), (-67).toByte()
    )

    fun getAppSignatureHash(context: Context): String = try {
        val pm = context.packageManager
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            pi.signingInfo?.apkContentsSigners ?: pi.signingInfo?.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            pi.signatures
        }
        if (!signatures.isNullOrEmpty()) {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(signatures[0].toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } else ""
    } catch (_: Exception) {
        ""
    }

    fun isAuthorizedCaller(context: Context?): Boolean {
        if (context == null) return false
        if (context.packageName != AUTHORIZED_PACKAGE_NAME) return false
        val sigHash = getAppSignatureHash(context)
        return sigHash.equals(AUTHORIZED_SIGNATURE_SHA256, ignoreCase = true)
    }

    fun generateDriverLoadAuthToken(context: Context): String {
        if (!isAuthorizedCaller(context)) {
            throw SecurityException("Unauthorized caller application")
        }
        val uid = context.applicationInfo.uid
        val pid = android.os.Process.myPid()
        val ts = System.currentTimeMillis() / 1000
        val expiry = ts + 60
        val payload = "$AUTHORIZED_PACKAGE_NAME:$uid:$pid:$expiry"
        val master = getMasterKey(context)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master, "HmacSHA256"))
        val sig = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val sigHex = sig.joinToString("") { "%02x".format(it) }
        return "$payload:$sigHex"
    }

    private fun getMasterKey(context: Context? = null): ByteArray {
        if (context == null || !isAuthorizedCaller(context)) {
            throw SecurityException("Unauthorized caller package: Driver loading is exclusively locked to official Kernel Loader app.")
        }
        val key = ByteArray(32)
        for (i in 0 until 32) {
            key[i] = (M1[i].toInt() xor M2[i].toInt()).toByte()
        }
        return key
    }

    fun isEncrypted(data: ByteArray): Boolean {
        if (data.size < 20) return false
        for (i in 0 until 8) {
            if (data[i] != MAGIC[i]) return false
        }
        return true
    }

    fun decrypt(data: ByteArray, context: Context? = null): ByteArray {
        if (!isEncrypted(data)) {
            return data
        }
        val nonce = ByteArray(12)
        System.arraycopy(data, 8, nonce, 0, 12)
        val ctLen = data.size - 20
        val ct = ByteArray(ctLen)
        System.arraycopy(data, 20, ct, 0, ctLen)

        val key = getMasterKey(context)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ct)
    }

    fun encrypt(data: ByteArray, context: Context? = null): ByteArray {
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)
        val key = getMasterKey(context)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(data)
        val out = ByteArray(8 + 12 + ct.size)
        System.arraycopy(MAGIC, 0, out, 0, 8)
        System.arraycopy(nonce, 0, out, 8, 12)
        System.arraycopy(ct, 0, out, 20, ct.size)
        return out
    }
}
