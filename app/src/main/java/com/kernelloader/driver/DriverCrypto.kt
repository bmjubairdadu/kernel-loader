package com.kernelloader.driver

import android.content.Context
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object DriverCrypto {

    private val MAGIC = byteArrayOf(0x4B, 0x4C, 0x45, 0x4E, 0x43, 0x30, 0x30, 0x31)

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

    private fun getMasterKey(context: Context? = null): ByteArray {
        if (context != null && context.packageName != "com.kernelloader") {
            throw SecurityException("Unauthorized caller package: ${context.packageName}")
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
