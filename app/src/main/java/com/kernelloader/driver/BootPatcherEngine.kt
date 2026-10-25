package com.kernelloader.driver

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BootPatcherEngine {

    data class PartitionInfo(
        val name: String,
        val blockPath: String,
        val slot: String
    )

    data class PatchResult(
        val success: Boolean,
        val message: String,
        val backupPath: String = "",
        val methodUsed: String = ""
    )

    suspend fun runPipeline(
        context: Context,
        vm: DriverViewModel,
        switchSlot: Boolean = false
    ): PatchResult = withContext(Dispatchers.IO) {
        vm.tstep("Initializing Boot Patch Pipeline...")
        vm.tlog("[BOOT] Starting Kernel Signature Bypass pipeline...", "INFO")

        val isRoot = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        if (!isRoot) {
            vm.tlog("[BOOT ERR] Superuser access is required to access boot partitions", "ERR")
            return@withContext PatchResult(false, "Root access missing")
        }

        val slot = Shell.cmd("getprop ro.boot.slot_suffix 2>/dev/null").exec().out.firstOrNull()?.trim() ?: ""
        vm.tlog("[BOOT] Active slot suffix: ${if (slot.isBlank()) "None (Non-A/B)" else slot}", "INFO")

        vm.tstep("Locating boot partitions...")
        val bootPart = findPartition("boot", slot)
        if (bootPart == null) {
            vm.tlog("[BOOT ERR] Cannot find boot partition block device", "ERR")
            return@withContext PatchResult(false, "Boot partition not found")
        }
        vm.tlog("[BOOT] Target boot block: ${bootPart.blockPath}", "OK")

        val initBootPart = findPartition("init_boot", slot)
        if (initBootPart != null) {
            vm.tlog("[BOOT] Found init_boot: ${initBootPart.blockPath}", "INFO")
        }

        val vendorBootPart = findPartition("vendor_boot", slot)
        if (vendorBootPart != null) {
            vm.tlog("[BOOT] Found vendor_boot: ${vendorBootPart.blockPath}", "INFO")
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backupRootDir = File(context.filesDir, "boot_backups").absolutePath
        val tmpDir = File(context.cacheDir, "bootpatch").absolutePath
        val sessionBackupDir = "$backupRootDir/backup_$stamp"
        Shell.cmd("mkdir -p $sessionBackupDir $tmpDir/work").exec()

        vm.tstep("Dumping partition images...")
        vm.tlog("[DUMP] Backing up untouched partition images to: $sessionBackupDir", "INFO")
        val sdcardBackupDir = "/sdcard/Download/kloader_backup_$stamp"
        Shell.cmd("mkdir -p $sdcardBackupDir").exec()

        val dumpBootRes = Shell.cmd("dd if=${bootPart.blockPath} of=$sessionBackupDir/boot_stock.img bs=4096 2>/dev/null").exec()
        if (!dumpBootRes.isSuccess || !verifyFileExists("$sessionBackupDir/boot_stock.img", 4 * 1024 * 1024)) {
            vm.tlog("[DUMP ERR] Failed to dump boot partition safely", "ERR")
            return@withContext PatchResult(false, "Boot dump failed")
        }
        vm.tlog("[DUMP OK] boot_stock.img saved (${fileSize("$sessionBackupDir/boot_stock.img")} bytes)", "OK")

        if (initBootPart != null) {
            Shell.cmd("dd if=${initBootPart.blockPath} of=$sessionBackupDir/init_boot_stock.img bs=4096 2>/dev/null").exec()
            vm.tlog("[DUMP OK] init_boot_stock.img saved", "INFO")
        }

        if (vendorBootPart != null) {
            Shell.cmd("dd if=${vendorBootPart.blockPath} of=$sessionBackupDir/vendor_boot_stock.img bs=4096 2>/dev/null").exec()
            vm.tlog("[DUMP OK] vendor_boot_stock.img saved", "INFO")
        }

        Shell.cmd("cd $sessionBackupDir && sha256sum *.img > checksums.txt 2>/dev/null").exec()
        vm.tlog("[DUMP] SHA-256 integrity digests saved to checksums.txt", "INFO")

        val kptoolsPath = findTool("kptools")
        val ksudPath = findTool("ksud")
        val magiskbootPath = findTool("magiskboot")

        vm.tlog("[TOOLS] magiskboot: ${magiskbootPath ?: "missing"} | kptools: ${kptoolsPath ?: "missing"} | ksud: ${ksudPath ?: "missing"}", "INFO")

        var patchSuccess = false
        var methodUsed = ""
        val patchedBootOut = "$tmpDir/patched_boot.img"
        Shell.cmd("rm -f $patchedBootOut 2>/dev/null").exec()

        if (!magiskbootPath.isNullOrBlank()) {
            vm.tstep("Tier 1: Unpacking & Kernel Hex-Patching via magiskboot...")
            vm.tlog("[PATCH] Executing Signature Bypass: Unpack -> HexPatch (CONFIG_MODULE_SIG_FORCE=n) -> AVB Repack...", "INFO")

            val workDir = "$tmpDir/work"
            Shell.cmd("rm -rf $workDir && mkdir -p $workDir").exec()
            Shell.cmd("cp $sessionBackupDir/boot_stock.img $workDir/boot.img").exec()

            val unpackRes = Shell.cmd("cd $workDir && $magiskbootPath unpack boot.img 2>&1").exec()
            if (!unpackRes.isSuccess || !verifyFileExists("$workDir/kernel", 1024)) {
                vm.tlog("[PATCH WARN] magiskboot unpack failed: ${unpackRes.out.joinToString(" ")}", "WARN")
            } else {
                vm.tlog("[UNPACK OK] Header and kernel extracted from boot.img", "OK")

                vm.tstep("Decompressing kernel...")
                Shell.cmd("cd $workDir && $magiskbootPath decompress kernel kernel.raw 2>/dev/null").exec()
                val targetKernel = if (verifyFileExists("$workDir/kernel.raw", 1024)) "$workDir/kernel.raw" else "$workDir/kernel"
                vm.tlog("[DECOMPRESS OK] Target kernel binary: $targetKernel (${fileSize(targetKernel)} bytes)", "OK")

                vm.tstep("Patching kernel symbols (module_sig_check, check_modinfo, check_version)...")
                val dynamicCount = patchKernelSymbols(targetKernel, vm)
                if (dynamicCount > 0) {
                    vm.tlog("[PATCH OK] ARM64 machine code patched: module signature verification permanently neutralized!", "OK")
                } else {
                    vm.tlog("[PATCH INFO] Falling back to text and cmdline patches", "INFO")
                }

                val hexPatches = listOf(
                    "6d6f64756c655f7369675f656e666f726365" to "6d6f64756c655f7369675f64697361626c65",
                    "434f4e4649475f4d4f44554c455f5349475f464f5243453d79" to "434f4e4649475f4d4f44554c455f5349475f464f5243453d6e",
                    "434f4e4649475f4d4f44554c455f5349475f414c4c3d79" to "434f4e4649475f4d4f44554c455f5349475f414c4c3d6e",
                    "6d6f64756c652e7369675f656e666f7263653d31" to "6d6f64756c652e7369675f656e666f7263653d30"
                )
                var hexCount = 0
                for ((src, rep) in hexPatches) {
                    val patchOut = Shell.cmd("cd $workDir && $magiskbootPath hexpatch $targetKernel $src $rep 2>&1").exec()
                    if (patchOut.isSuccess) {
                        hexCount++
                    }
                }
                Shell.cmd("cd $workDir && sed -i 's/module.sig_enforce=1/module.sig_enforce=0/g' header 2>/dev/null").exec()
                Shell.cmd("cd $workDir && sed -i 's/cmdline=/cmdline=module.sig_enforce=0 /g' header 2>/dev/null").exec()
                vm.tlog("[HEXPATCH] Applied $hexCount kernel signature bypass patches", "OK")

                if (verifyFileExists("$workDir/kernel.raw", 1024)) {
                    vm.tstep("Recompressing kernel...")
                    Shell.cmd("cd $workDir && $magiskbootPath compress=auto kernel.raw kernel 2>/dev/null").exec()
                }

                vm.tstep("Repacking boot image & updating AVB / SHA-256 hashes...")
                val repackRes = Shell.cmd("cd $workDir && $magiskbootPath repack boot.img new-boot.img 2>&1").exec()
                if (verifyFileExists("$workDir/new-boot.img", 4 * 1024 * 1024)) {
                    Shell.cmd("cp $workDir/new-boot.img $patchedBootOut").exec()
                    if (verifyBootImageMagic(patchedBootOut)) {
                        patchSuccess = true
                        methodUsed = "Magiskboot Unpack + HexPatch + AVB Repack"
                        vm.tlog("[REPACK OK] Signature-bypassed boot image assembled successfully", "OK")
                    }
                } else {
                    vm.tlog("[REPACK ERR] magiskboot repack failed: ${repackRes.out.joinToString(" ")}", "ERR")
                }
            }
        }

        if (!patchSuccess && !kptoolsPath.isNullOrBlank()) {
            val kpimgPath = findKpimg()
            if (kpimgPath.isNullOrBlank()) {
                vm.tlog("[PATCH INFO] kptools found but kpimg is missing — skipping Tier 2", "INFO")
            } else {
                vm.tstep("Tier 2: Patching via KernelPatch / FolkPatch (kptools)...")
                vm.tlog("[PATCH] Executing patch via $kptoolsPath with $kpimgPath...", "INFO")
                val kpCmd = "$kptoolsPath -p -i $sessionBackupDir/boot_stock.img -k $kpimgPath -o $patchedBootOut 2>&1"
                val res = Shell.cmd(kpCmd).exec()
                if (verifyBootImageMagic(patchedBootOut)) {
                    patchSuccess = true
                    methodUsed = "FolkPatch / KernelPatch (kptools)"
                    vm.tlog("[PATCH OK] Patched successfully using $methodUsed", "OK")
                } else {
                    vm.tlog("[PATCH WARN] kptools output: ${res.out.joinToString(" ")}", "WARN")
                }
            }
        }

        if (!patchSuccess && !ksudPath.isNullOrBlank()) {
            vm.tstep("Tier 3: Patching via KernelSU (ksud)...")
            vm.tlog("[PATCH] Executing boot patch via $ksudPath...", "INFO")
            val res = Shell.cmd("$ksudPath boot-patch -i $sessionBackupDir/boot_stock.img -o $patchedBootOut 2>&1").exec()
            if (verifyBootImageMagic(patchedBootOut)) {
                patchSuccess = true
                methodUsed = "KernelSU (ksud)"
                vm.tlog("[PATCH OK] Patched successfully using $methodUsed", "OK")
            } else {
                vm.tlog("[PATCH WARN] ksud output: ${res.out.joinToString(" ")}", "WARN")
            }
        }

        if (!patchSuccess || !verifyBootImageMagic(patchedBootOut)) {
            vm.tlog("[ABORT] Failed to generate valid patched boot image. Partitions were NOT touched.", "ERR")
            return@withContext PatchResult(
                success = false,
                message = "Patch failed — stock partitions safely preserved in $sessionBackupDir",
                backupPath = sessionBackupDir
            )
        }

        val patchedSize = fileSize(patchedBootOut)
        val stockSize = fileSize("$sessionBackupDir/boot_stock.img")
        vm.tlog("[PRE-FLIGHT] Patched image: $patchedSize bytes | Stock size: $stockSize bytes", "INFO")
        vm.tlog("[PRE-FLIGHT] Magic header verified (ANDROID!)", "OK")

        var targetBlock = bootPart.blockPath
        var targetSlotName = slot

        if (switchSlot && slot.isNotBlank()) {
            val oppositeSlot = if (slot == "_a") "_b" else "_a"
            val oppositeBoot = findPartition("boot", oppositeSlot)
            if (oppositeBoot != null) {
                targetBlock = oppositeBoot.blockPath
                targetSlotName = oppositeSlot
                vm.tlog("[SLOT] Dual-Slot mode: Targeting inactive slot $targetSlotName (${oppositeBoot.blockPath})", "WARN")
            }
        }

        vm.tstep("Flashing patched boot image to $targetBlock...")
        vm.tlog("[FLASH] Writing to partition: $targetBlock...", "WARN")

        val flashRes = Shell.cmd("dd if=$patchedBootOut of=$targetBlock bs=4096 conv=fsync 2>&1").exec()
        Shell.cmd("sync; sync").exec()

        if (!flashRes.isSuccess) {
            vm.tlog("[FLASH ERR] Flash write failed: ${flashRes.out.joinToString(" ")}", "ERR")
            return@withContext PatchResult(
                success = false,
                message = "Flash write failed. Stock backup preserved at $sessionBackupDir",
                backupPath = sessionBackupDir,
                methodUsed = methodUsed
            )
        }

        if (switchSlot && targetSlotName != slot && targetSlotName.isNotBlank()) {
            val slotNum = if (targetSlotName == "_b") 1 else 0
            vm.tstep("Switching active boot slot to $targetSlotName...")
            val slotRes = Shell.cmd("bootctl set-active-boot-slot $slotNum 2>/dev/null").exec()
            if (slotRes.isSuccess) {
                vm.tlog("[SLOT OK] Active boot slot set to slot $slotNum ($targetSlotName)", "OK")
            } else {
                vm.tlog("[SLOT WARN] bootctl could not switch slots (rc=" + slotRes.code + ") - device may keep booting the UNPATCHED slot", "ERR")
                vm.tlog("[SLOT WARN] Patched image sits in " + targetSlotName + "; switch manually or flash the active slot", "WARN")
            }
        }

        Shell.cmd("rm -rf $tmpDir 2>/dev/null").exec()

        vm.tlog("[DONE] Kernel signature bypass patched & flashed successfully!", "OK")
        Shell.cmd("cp $sessionBackupDir/boot_stock.img $sdcardBackupDir/ 2>/dev/null").exec()
        vm.tlog("[RECOVERY] If device bootloops, restore stock backup via Fastboot:", "INFO")
        vm.tlog("fastboot flash boot $sdcardBackupDir/boot_stock.img", "INFO")
        vm.tlog("[RECOVERY] Backup also kept at (app-private): $sessionBackupDir", "INFO")

        PatchResult(
            success = true,
            message = "Patched successfully using $methodUsed",
            backupPath = sessionBackupDir,
            methodUsed = methodUsed
        )
    }

    private fun findPartition(name: String, slotSuffix: String = ""): PartitionInfo? {
        val candidates = listOf(
            "$name$slotSuffix",
            name
        )
        val dirCandidates = listOf(
            "/dev/block/by-name",
            "/dev/block/bootdevice/by-name",
            "/dev/block/mapper"
        )
        for (dir in dirCandidates) {
            for (c in candidates) {
                val p = "$dir/$c"
                val check = Shell.cmd("test -b $p 2>/dev/null && echo $p").exec().out.firstOrNull()?.trim()
                if (!check.isNullOrBlank()) {
                    return PartitionInfo(name, check, slotSuffix)
                }
            }
        }
        val wildCheck = Shell.cmd("find /dev/block/platform/ -name \"$name$slotSuffix\" 2>/dev/null").exec().out.firstOrNull()?.trim()
        if (!wildCheck.isNullOrBlank()) {
            return PartitionInfo(name, wildCheck, slotSuffix)
        }
        return null
    }

    private fun findTool(name: String): String? {
        val knownPaths = listOf(
            "/data/adb/ap/bin/$name",
            "/data/adb/ap/$name",
            "/data/adb/apatch/$name",
            "/data/adb/apatch/bin/$name",
            "/data/adb/ksu/bin/$name",
            "/data/adb/ksud",
            "/data/adb/magisk/$name",
            "/data/user/0/org.apatch/files/$name",
            "/data/data/org.apatch/files/$name",
            "/data/user/0/com.topjohnwu.magisk/files/$name",
            "/data/data/com.topjohnwu.magisk/files/$name",
            "/data/user/0/io.github.a13e300.ksu/files/$name",
            "/data/data/io.github.a13e300.ksu/files/$name",
            "/system/bin/$name",
            "/system/xbin/$name",
            "/data/local/tmp/$name"
        )
        for (p in knownPaths) {
            if (Shell.cmd("test -x $p 2>/dev/null && echo $p").exec().out.firstOrNull()?.trim()?.isNotEmpty() == true) {
                return p
            }
        }
        val whichOut = Shell.cmd("which $name 2>/dev/null").exec().out.firstOrNull()?.trim()
        if (!whichOut.isNullOrBlank()) return whichOut
        val findOut = Shell.cmd("find /data/ -name $name 2>/dev/null | head -n 1").exec().out.firstOrNull()?.trim()
        if (!findOut.isNullOrBlank()) return findOut
        return null
    }

    private fun findKpimg(): String? {
        val paths = listOf(
            "/data/adb/ap/kpimg",
            "/data/adb/ap/bin/kpimg",
            "/data/adb/kpimg",
            "/data/adb/modules/kpimg",
            "/data/user/0/org.apatch/files/kpimg",
            "/data/data/org.apatch/files/kpimg"
        )
        for (p in paths) {
            if (Shell.cmd("test -f $p 2>/dev/null && echo $p").exec().out.firstOrNull()?.trim()?.isNotEmpty() == true) {
                return p
            }
        }
        return Shell.cmd("find /data/ -name kpimg 2>/dev/null | head -n 1").exec().out.firstOrNull()?.trim()
    }

    private fun verifyFileExists(path: String, minSize: Long = 0): Boolean {
        val res = Shell.cmd("test -f $path 2>/dev/null && stat -c%s $path 2>/dev/null").exec().out.firstOrNull()?.trim() ?: ""
        val size = res.toLongOrNull() ?: 0L
        return size > minSize
    }

    private fun fileSize(path: String): Long {
        val res = Shell.cmd("stat -c%s $path 2>/dev/null").exec().out.firstOrNull()?.trim() ?: ""
        return res.toLongOrNull() ?: 0L
    }

    private fun verifyBootImageMagic(path: String): Boolean {
        val hex = Shell.cmd("dd if=$path bs=1 count=8 2>/dev/null | xxd -p 2>/dev/null || od -A n -N 8 -t x1 $path 2>/dev/null")
            .exec().out.joinToString("").replace(" ", "").trim().lowercase()
        return hex.startsWith("414e44524f494421")
    }

    private fun patchKernelSymbols(targetKernelPath: String, vm: DriverViewModel): Int {

        Shell.cmd("echo 0 > /proc/sys/kernel/kptr_restrict").exec()

        val baseLine = Shell.cmd("head -n 1 /proc/kallsyms").exec().out.firstOrNull()?.trim() ?: ""
        val baseParts = baseLine.split(Regex("\\s+"))
        if (baseParts.isEmpty() || baseParts[0].length < 8) {
            vm.tlog("[PATCH WARN] Could not determine kernel base address from /proc/kallsyms", "WARN")
            return 0
        }
        val baseAddrStr = baseParts[0]
        val baseAddr = try {
            java.lang.Long.parseUnsignedLong(baseAddrStr, 16)
        } catch (e: Exception) {
            vm.tlog("[PATCH WARN] Invalid base address: $baseAddrStr", "WARN")
            return 0
        }
        vm.tlog("[KALLSYMS] Kernel base address: 0x$baseAddrStr", "INFO")

        val targetFile = File(targetKernelPath)
        Shell.cmd("chmod 666 $targetKernelPath").exec()
        if (!targetFile.exists() || targetFile.length() < 1024) {
            vm.tlog("[PATCH ERR] Target kernel does not exist or is too small", "ERR")
            return 0
        }

        val kernelBytes = try {
            targetFile.readBytes()
        } catch (e: Exception) {
            vm.tlog("[PATCH ERR] Could not read target kernel: ${e.message}", "ERR")
            return 0
        }

        val symbolsToPatch = listOf(
            Triple("module_sig_check", "00 00 80 52 C0 03 5F D6", "Bypass module signature check (mov w0, #0; ret)"),
            Triple("mod_verify_sig", "00 00 80 52 C0 03 5F D6", "Bypass module signature verification (mov w0, #0; ret)"),
            Triple("is_module_sig_enforced", "00 00 80 52 C0 03 5F D6", "Disable signature enforcement (mov w0, #0; ret)"),
            Triple("verify_pkcs7_signature", "00 00 80 52 C0 03 5F D6", "Bypass PKCS7 crypto signature (mov w0, #0; ret)"),
            Triple("check_modinfo", "00 00 80 52 C0 03 5F D6", "Bypass vermagic check (mov w0, #0; ret)"),
            Triple("check_version", "20 00 80 52 C0 03 5F D6", "Bypass symbol CRC version check (mov w0, #1; ret)")
        )

        var patchedCount = 0
        val pacPattern = byteArrayOf(0x3F.toByte(), 0x23.toByte(), 0x03.toByte(), 0xD5.toByte())
        val patchedSymbols = mutableSetOf<String>()

        for ((symName, patchHex, desc) in symbolsToPatch) {
            val symLine = Shell.cmd("grep -w -E '[Tt] $symName' /proc/kallsyms | head -n 1").exec().out.firstOrNull()?.trim() ?: ""
            if (symLine.isBlank()) {
                vm.tlog("[KALLSYMS] Symbol '$symName' not found in kallsyms (skipping)", "INFO")
                continue
            }
            val symParts = symLine.split(Regex("\\s+"))
            if (symParts.isEmpty()) continue
            val symAddrStr = symParts[0]
            val symAddr = try {
                java.lang.Long.parseUnsignedLong(symAddrStr, 16)
            } catch (e: Exception) {
                continue
            }

            val offsetLong = symAddr - baseAddr
            if (offsetLong < 0 || offsetLong + 16 > kernelBytes.size) {
                vm.tlog("[PATCH WARN] Symbol '$symName' offset ($offsetLong) out of bounds", "WARN")
                continue
            }
            val offset = offsetLong.toInt()

            val hasPac = (kernelBytes[offset] == pacPattern[0] &&
                          kernelBytes[offset + 1] == pacPattern[1] &&
                          kernelBytes[offset + 2] == pacPattern[2] &&
                          kernelBytes[offset + 3] == pacPattern[3])

            val rawPatch = hexToBytes(patchHex)
            val patchBytes = if (hasPac) {
                byteArrayOf(
                    0x3F.toByte(), 0x23.toByte(), 0x03.toByte(), 0xD5.toByte(),
                    rawPatch[0], rawPatch[1], rawPatch[2], rawPatch[3],
                    0xBF.toByte(), 0x23.toByte(), 0x03.toByte(), 0xD5.toByte(),
                    rawPatch[4], rawPatch[5], rawPatch[6], rawPatch[7]
                )
            } else {
                rawPatch
            }

            System.arraycopy(patchBytes, 0, kernelBytes, offset, patchBytes.size)
            patchedCount++
            patchedSymbols.add(symName)
            vm.tlog("[PATCH OK] Patched $symName at offset 0x${Integer.toHexString(offset)} ($desc, PAC: $hasPac)", "OK")
        }

        // Auto-Discovery Engine: Dynamically scan /proc/kallsyms for ANY signature/enforce verification symbols
        vm.tstep("Scanning /proc/kallsyms for OEM signature verification functions...")
        val discoveryRes = Shell.cmd(
            "grep -E -i ' [Tt] .*([Mm]odule.*[Ss]ig|[Ss]ig.*[Cc]heck|[Vv]erify.*[Ss]ig|[Ss]ig.*[Ee]nforce|[Pp]kcs7|[Ss]ystem_verify|mod_verify)' /proc/kallsyms 2>/dev/null"
        ).exec().out

        for (line in discoveryRes) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            val symAddrStr = parts[0]
            val symName = parts[2]
            if (patchedSymbols.contains(symName)) continue

            val symAddr = try {
                java.lang.Long.parseUnsignedLong(symAddrStr, 16)
            } catch (e: Exception) {
                continue
            }

            val offsetLong = symAddr - baseAddr
            if (offsetLong < 0 || offsetLong + 16 > kernelBytes.size) continue
            val offset = offsetLong.toInt()

            val hasPac = (kernelBytes[offset] == pacPattern[0] &&
                          kernelBytes[offset + 1] == pacPattern[1] &&
                          kernelBytes[offset + 2] == pacPattern[2] &&
                          kernelBytes[offset + 3] == pacPattern[3])

            val rawPatch = hexToBytes("00 00 80 52 C0 03 5F D6") // mov w0, #0; ret
            val patchBytes = if (hasPac) {
                byteArrayOf(
                    0x3F.toByte(), 0x23.toByte(), 0x03.toByte(), 0xD5.toByte(),
                    rawPatch[0], rawPatch[1], rawPatch[2], rawPatch[3],
                    0xBF.toByte(), 0x23.toByte(), 0x03.toByte(), 0xD5.toByte(),
                    rawPatch[4], rawPatch[5], rawPatch[6], rawPatch[7]
                )
            } else {
                rawPatch
            }

            System.arraycopy(patchBytes, 0, kernelBytes, offset, patchBytes.size)
            patchedCount++
            patchedSymbols.add(symName)
            vm.tlog("[AUTO-DISCOVER OK] Found & bypassed signature symbol '$symName' at offset 0x${Integer.toHexString(offset)}", "OK")
        }

        if (patchedCount > 0) {
            try {
                targetFile.writeBytes(kernelBytes)
            } catch (e: Exception) {
                val tempFile = File.createTempFile("kpatch", ".bin", targetFile.parentFile)
                tempFile.writeBytes(kernelBytes)
                Shell.cmd("cp ${tempFile.absolutePath} $targetKernelPath && chmod 644 $targetKernelPath").exec()
                tempFile.delete()
            }
            vm.tlog("[PATCH OK] Applied $patchedCount dynamic symbol patches directly into kernel binary", "OK")
        }
        return patchedCount
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }
}
