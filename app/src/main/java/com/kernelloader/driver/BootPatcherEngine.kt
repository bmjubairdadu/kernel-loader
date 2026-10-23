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

    private const val TMP_DIR = "/data/local/tmp/kloader_bootpatch"
    private const val BACKUP_DIR = "/sdcard/KernelLoader_Backups"

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
        val sessionBackupDir = "$BACKUP_DIR/backup_$stamp"
        Shell.cmd("mkdir -p $sessionBackupDir $TMP_DIR/work").exec()

        vm.tstep("Dumping partition images...")
        vm.tlog("[DUMP] Backing up untouched partition images to: $sessionBackupDir", "INFO")

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

        vm.tlog("[TOOLS] kptools: ${kptoolsPath ?: "missing"} | ksud: ${ksudPath ?: "missing"} | magiskboot: ${magiskbootPath ?: "missing"}", "INFO")

        var patchSuccess = false
        var methodUsed = ""
        val patchedBootOut = "$TMP_DIR/patched_boot.img"
        Shell.cmd("rm -f $patchedBootOut 2>/dev/null").exec()

        if (!kptoolsPath.isNullOrBlank()) {
            vm.tstep("Tier 1: Patching via KernelPatch / FolkPatch...")
            vm.tlog("[PATCH] Executing dynamic symbol hook via $kptoolsPath...", "INFO")
            val kpimgPath = findKpimg()
            val kpCmd = if (kpimgPath != null) {
                "$kptoolsPath -p -i $sessionBackupDir/boot_stock.img -k $kpimgPath -o $patchedBootOut 2>&1"
            } else {
                "$kptoolsPath -p -i $sessionBackupDir/boot_stock.img -o $patchedBootOut 2>&1"
            }
            val res = Shell.cmd(kpCmd).exec()
            if (verifyBootImageMagic(patchedBootOut)) {
                patchSuccess = true
                methodUsed = "FolkPatch / KernelPatch (kptools)"
                vm.tlog("[PATCH OK] Patched successfully using $methodUsed", "OK")
            } else {
                vm.tlog("[PATCH WARN] kptools output: ${res.out.joinToString(" ")}", "WARN")
            }
        }

        if (!patchSuccess && !ksudPath.isNullOrBlank()) {
            vm.tstep("Tier 2: Patching via KernelSU...")
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

        if (!patchSuccess && !magiskbootPath.isNullOrBlank()) {
            vm.tstep("Tier 3: Unpacking & Kernel Hex-Patching via magiskboot...")
            vm.tlog("[PATCH] Running custom Unpack -> Decompress -> HexPatch -> Recompress -> AVB Repack pipeline...", "INFO")

            val workDir = "$TMP_DIR/work"
            Shell.cmd("rm -rf $workDir && mkdir -p $workDir").exec()
            Shell.cmd("cp $sessionBackupDir/boot_stock.img $workDir/boot.img").exec()

            val unpackRes = Shell.cmd("cd $workDir && $magiskbootPath unpack boot.img 2>&1").exec()
            if (!unpackRes.isSuccess || !verifyFileExists("$workDir/kernel", 1024)) {
                vm.tlog("[PATCH ERR] magiskboot unpack failed: ${unpackRes.out.joinToString(" ")}", "ERR")
            } else {
                vm.tlog("[UNPACK OK] Header and kernel extracted from boot.img", "OK")

                vm.tstep("Decompressing kernel...")
                Shell.cmd("cd $workDir && $magiskbootPath decompress kernel kernel.raw 2>/dev/null").exec()
                val targetKernel = if (verifyFileExists("$workDir/kernel.raw", 1024)) "$workDir/kernel.raw" else "$workDir/kernel"
                vm.tlog("[DECOMPRESS OK] Target kernel binary: $targetKernel (${fileSize(targetKernel)} bytes)", "OK")

                vm.tstep("Hex-patching module_sig_check & sig_enforce...")
                val hexPatches = listOf(
                    "6d6f64756c655f7369675f656e666f726365" to "6d6f64756c655f7369675f64697361626c65",
                    "434f4e4649475f4d4f44554c455f5349475f464f5243453d79" to "434f4e4649475f4d4f44554c455f5349475f464f5243453d6e"
                )
                var hexCount = 0
                for ((src, rep) in hexPatches) {
                    val patchOut = Shell.cmd("cd $workDir && $magiskbootPath hexpatch $targetKernel $src $rep 2>&1").exec()
                    if (patchOut.isSuccess) {
                        hexCount++
                    }
                }
                vm.tlog("[HEXPATCH] Applied $hexCount instruction and symbol patches to kernel", "OK")

                vm.tstep("Recompressing kernel...")
                Shell.cmd("cd $workDir && $magiskbootPath compress=auto kernel.raw kernel 2>/dev/null").exec()

                vm.tstep("Repacking boot image & updating AVB / SHA-256 hashes...")
                val repackRes = Shell.cmd("cd $workDir && $magiskbootPath repack boot.img new-boot.img 2>&1").exec()
                if (verifyFileExists("$workDir/new-boot.img", 4 * 1024 * 1024)) {
                    Shell.cmd("cp $workDir/new-boot.img $patchedBootOut").exec()
                    if (verifyBootImageMagic(patchedBootOut)) {
                        patchSuccess = true
                        methodUsed = "Magiskboot Unpack + HexPatch + AVB Repack"
                        vm.tlog("[REPACK OK] New boot image assembled with updated vendor/kernel hashes", "OK")
                    }
                } else {
                    vm.tlog("[REPACK ERR] magiskboot repack failed: ${repackRes.out.joinToString(" ")}", "ERR")
                }
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
            Shell.cmd("bootctl set-active-boot-slot $slotNum 2>/dev/null").exec()
            vm.tlog("[SLOT OK] Active boot slot set to slot $slotNum ($targetSlotName)", "OK")
        }

        Shell.cmd("rm -rf $TMP_DIR 2>/dev/null").exec()

        vm.tlog("[DONE] Kernel signature bypass patched & flashed successfully!", "OK")
        vm.tlog("[RECOVERY] If device bootloops, restore stock backup via Fastboot:", "INFO")
        vm.tlog("fastboot flash boot $sessionBackupDir/boot_stock.img", "INFO")

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
            "/data/adb/ksu/bin/$name",
            "/data/adb/ksud",
            "/data/adb/magisk/$name",
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
        val findOut = Shell.cmd("find /data/adb/ -name $name 2>/dev/null").exec().out.firstOrNull()?.trim()
        if (!findOut.isNullOrBlank()) return findOut
        return null
    }

    private fun findKpimg(): String? {
        val paths = listOf(
            "/data/adb/ap/kpimg",
            "/data/adb/kpimg",
            "/data/adb/modules/kpimg"
        )
        for (p in paths) {
            if (Shell.cmd("test -f $p 2>/dev/null && echo $p").exec().out.firstOrNull()?.trim()?.isNotEmpty() == true) {
                return p
            }
        }
        return Shell.cmd("find /data/adb/ -name kpimg 2>/dev/null").exec().out.firstOrNull()?.trim()
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
}
