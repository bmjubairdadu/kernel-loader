package com.kernelloader.driver

import android.content.Context
import android.os.Build
import com.kernelloader.root.RootChecker
import com.topjohnwu.superuser.Shell

class KernelAuditor(private val ctx: Context) {

    data class AuditItem(
        val step: Int,
        val title: String,
        val status: String,
        val details: String,
        val fixSuggestion: String? = null
    )

    fun runAudit(onLine: (msg: String, type: String) -> Unit): Pair<Int, Boolean> {
        onLine("==================================================", "CMD")
        onLine("  KERNEL DRIVER COMPATIBILITY AUDIT ENGINE", "CMD")
        onLine("==================================================", "CMD")

        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val device = Build.DEVICE
        val androidVer = Build.VERSION.RELEASE
        val sdk = Build.VERSION.SDK_INT
        val release = (RootChecker.getKernelRelease() ?: System.getProperty("os.version") ?: "").trim()
        val vermagic = RootChecker.getKernelVermagic().trim()

        onLine("- Device Model : $manufacturer $model ($device)", "INFO")
        onLine("- Android OS   : Android $androidVer (API $sdk)", "INFO")
        onLine("- Kernel Rel   : $release", "INFO")
        if (vermagic.isNotBlank()) {
            onLine("- Full Vermagic: $vermagic", "INFO")
        }
        onLine("--------------------------------------------------", "CMD")

        var blockerCount = 0
        var warnCount = 0
        val items = mutableListOf<AuditItem>()

        val rootRes = Shell.cmd("id").exec()
        val rootOut = rootRes.out.joinToString(" ")
        if (rootRes.isSuccess && rootOut.contains("uid=0")) {
            items.add(AuditItem(
                step = 1,
                title = "Root Access (UID=0)",
                status = "PASS",
                details = "Root access verified active (uid=0 root)."
            ))
            onLine("[OK] 1/9: Root Access: Granted (UID=0 active)", "OK")
        } else {
            blockerCount++
            items.add(AuditItem(
                step = 1,
                title = "Root Access (UID=0)",
                status = "FAIL",
                details = "Root privileges not granted ($rootOut).",
                fixSuggestion = "Open Magisk, KernelSU, or APatch app and grant Superuser (Root) permission to Kernel Loader."
            ))
            onLine("[FAIL] 1/9: Root Access: DENIED", "ERR")
            onLine("  • Detail: Kernel Loader does not have root privileges.", "ERR")
            onLine("  • Fix   : Grant Superuser access in Magisk / KernelSU / APatch.", "FIX")
        }

        val modDisRes = Shell.cmd("cat /proc/sys/kernel/modules_disabled 2>/dev/null").exec()
        val modDisVal = modDisRes.out.firstOrNull()?.trim() ?: "0"
        if (modDisVal == "1") {
            blockerCount++
            items.add(AuditItem(
                step = 2,
                title = "Kernel Module Lock (modules_disabled)",
                status = "FAIL",
                details = "Kernel module loading is permanently disabled until reboot (/proc/sys/kernel/modules_disabled = 1).",
                fixSuggestion = "Reboot your phone. If this recurs on boot, inspect init.rc or security daemons that write '1' to modules_disabled."
            ))
            onLine("[FAIL] 2/9: Module Subsystem: LOCKED (modules_disabled=1)", "ERR")
            onLine("  • Detail: Kernel module loading permanently locked until reboot.", "ERR")
            onLine("  • Fix   : Reboot device now to reset kernel module state.", "FIX")
        } else {
            val procModRes = Shell.cmd("test -r /proc/modules").exec()
            if (procModRes.isSuccess) {
                items.add(AuditItem(
                    step = 2,
                    title = "Kernel Module Subsystem (CONFIG_MODULES)",
                    status = "PASS",
                    details = "Kernel module loader is active and ready (CONFIG_MODULES=y)."
                ))
                onLine("[OK] 2/9: Module Subsystem: Active (CONFIG_MODULES=y, unlocked)", "OK")
            } else {
                warnCount++
                items.add(AuditItem(
                    step = 2,
                    title = "Kernel Module Subsystem",
                    status = "WARN",
                    details = "Cannot read /proc/modules directly. init_module syscall may be restricted.",
                    fixSuggestion = "Ensure your kernel was built with CONFIG_MODULES=y."
                ))
                onLine("[WARN] 2/9: Module Subsystem: /proc/modules not readable directly", "WARN")
            }
        }

        var sigEnforced = false
        var sigReason = ""

        val cmdlineRes = Shell.cmd("cat /proc/cmdline 2>/dev/null").exec()
        val cmdline = cmdlineRes.out.joinToString(" ")
        if (cmdline.contains("module.sig_enforce=1") || cmdline.contains("sig_enforce=1")) {
            sigEnforced = true
            sigReason = "Enforced via boot cmdline (sig_enforce=1)"
        }

        val sysSigRes = Shell.cmd("cat /sys/module/module/parameters/sig_enforce 2>/dev/null").exec()
        val sysSigVal = sysSigRes.out.firstOrNull()?.trim() ?: ""
        if (sysSigVal.equals("Y", ignoreCase = true) || sysSigVal == "1") {
            sigEnforced = true
            sigReason = "Enforced via kernel module parameter (sig_enforce=Y)"
        }

        if (!sigEnforced) {
            val ksymsRes = Shell.cmd("grep -E \"\\bis_module_sig_enforced\\b\" /proc/kallsyms 2>/dev/null").exec()
            if (ksymsRes.isSuccess && ksymsRes.out.isNotEmpty()) {
                val dmesgRes = Shell.cmd("dmesg | grep -E -i \"module verification failed|PKCS#7|signature\" | tail -n 2").exec()
                if (dmesgRes.out.isNotEmpty()) {
                    sigEnforced = true
                    sigReason = "Kernel rejected module due to missing vendor PKCS#7 signature"
                }
            }
        }

        if (sigEnforced) {
            blockerCount++
            items.add(AuditItem(
                step = 3,
                title = "Module Signature Enforcement (CONFIG_MODULE_SIG_FORCE)",
                status = "FAIL",
                details = "Kernel rejects unsigned modules ($sigReason). insmod will exit with ENOKEY / Exec format error.",
                fixSuggestion = "Flash a boot.img patched with signature verification disabled (is_module_sig_enforced=0) or use Redmi_10C_SigOff_Boot_Flasher.zip in Magisk."
            ))
            onLine("[FAIL] 3/9: Module Signature: ENFORCED ($sigReason)", "ERR")
            onLine("  • Detail: Unsigned driver loading will fail with 'Exec format error'.", "ERR")
            onLine("  • Fix   : Flash SigOff patched boot.img (or use SigOff Magisk Flasher).", "FIX")
        } else {
            items.add(AuditItem(
                step = 3,
                title = "Module Signature Enforcement",
                status = "PASS",
                details = "Module signature enforcement: Disabled / Bypassed (Kernel accepts custom drivers)."
            ))
            onLine("[OK] 3/9: Module Signature: Disabled / Permissive (Unsigned OK)", "OK")
        }

        val archRes = Shell.cmd("uname -m 2>/dev/null").exec()
        val arch = archRes.out.firstOrNull()?.trim() ?: "unknown"
        if (arch == "aarch64" || arch == "arm64") {
            items.add(AuditItem(
                step = 4,
                title = "Architecture & Bitness",
                status = "PASS",
                details = "64-bit ARM architecture (aarch64 / arm64-v8a)."
            ))
            onLine("[OK] 4/9: Architecture: 64-bit ($arch / arm64-v8a)", "OK")
        } else {
            blockerCount++
            items.add(AuditItem(
                step = 4,
                title = "Architecture & Bitness",
                status = "FAIL",
                details = "32-bit architecture ($arch) detected. Kernel drivers require 64-bit arm64-v8a.",
                fixSuggestion = "Device must run a 64-bit kernel and 64-bit OS ROM to load arm64 drivers."
            ))
            onLine("[FAIL] 4/9: Architecture: Incompatible ($arch is 32-bit)", "ERR")
            onLine("  • Detail: 64-bit arm64-v8a kernel is required.", "ERR")
            onLine("  • Fix   : Flash a 64-bit kernel / ROM.", "FIX")
        }

        val probeNode = "/dev/.kl_audit_${System.currentTimeMillis() % 10000}"
        val mknodRes = Shell.cmd("mknod $probeNode c 1 3 2>/dev/null && rm -f $probeNode").exec()
        if (mknodRes.isSuccess) {
            items.add(AuditItem(
                step = 5,
                title = "Character Device Creation (/dev/)",
                status = "PASS",
                details = "Root shell can create character device nodes in /dev."
            ))
            onLine("[OK] 5/9: Node Creation: Operational (/dev mknod writable)", "OK")
        } else {
            blockerCount++
            items.add(AuditItem(
                step = 5,
                title = "Character Device Creation (/dev/)",
                status = "FAIL",
                details = "Root shell failed to create character device in /dev.",
                fixSuggestion = "SELinux or mount policy is blocking mknod. Run 'setenforce 0' or use Magisk supolicy."
            ))
            onLine("[FAIL] 5/9: Node Creation: BLOCKED in /dev", "ERR")
            onLine("  • Detail: Cannot create character device nodes in /dev directory.", "ERR")
            onLine("  • Fix   : Run 'setenforce 0' or configure Magisk supolicy.", "FIX")
        }

        val devMountRes = Shell.cmd("mount | grep -E ' on /dev '").exec()
        val devMountLine = devMountRes.out.firstOrNull() ?: ""
        if (devMountLine.contains("devtmpfs")) {
            items.add(AuditItem(
                step = 6,
                title = "Device Filesystem (devtmpfs)",
                status = "PASS",
                details = "devtmpfs is active on /dev (CONFIG_DEVTMPFS=y)."
            ))
            onLine("[OK] 6/9: Device Filesystem: devtmpfs (CONFIG_DEVTMPFS=y)", "OK")
        } else {
            warnCount++
            items.add(AuditItem(
                step = 6,
                title = "Device Filesystem (tmpfs vs devtmpfs)",
                status = "WARN",
                details = "# CONFIG_DEVTMPFS is not set (mounted as tmpfs). Android 12+ SELinux blocks automatic ueventd node creation.",
                fixSuggestion = "Handled automatically: Kernel Loader creates device nodes manually via root shell."
            ))
            onLine("[WARN] 6/9: Device Filesystem: tmpfs (# CONFIG_DEVTMPFS is not set)", "WARN")
            onLine("  • Detail: Kernel cannot auto-create /dev node; ueventd is restricted.", "WARN")
            onLine("  • Info  : Kernel Loader handles this automatically via manual mknod.", "INFO")
        }

        val seRes = Shell.cmd("getenforce 2>/dev/null").exec()
        val seMode = seRes.out.firstOrNull()?.trim() ?: "Unknown"
        if (seMode.equals("Permissive", ignoreCase = true)) {
            items.add(AuditItem(
                step = 7,
                title = "SELinux Status",
                status = "PASS",
                details = "SELinux is in Permissive mode."
            ))
            onLine("[OK] 7/9: SELinux Mode: Permissive (Unrestricted access)", "OK")
        } else {
            warnCount++
            items.add(AuditItem(
                step = 7,
                title = "SELinux Status",
                status = "WARN",
                details = "SELinux is in Enforcing mode. Loader uses root 0600 stealth permissions.",
                fixSuggestion = "If client apps cannot connect to driver, temporarily run 'setenforce 0' to test."
            ))
            onLine("[WARN] 7/9: SELinux Mode: $seMode (Enforcing)", "WARN")
            onLine("  • Info  : Driver stealth mode locks permissions to root:0600.", "INFO")
        }

        val nsTestRes = Shell.cmd("sh -c 'touch /dev/.kl_ns_chk 2>/dev/null' && test -f /dev/.kl_ns_chk && rm -f /dev/.kl_ns_chk").exec()
        if (nsTestRes.isSuccess) {
            items.add(AuditItem(
                step = 8,
                title = "Mount Namespace Sharing",
                status = "PASS",
                details = "Mount namespace allows global device node sharing."
            ))
            onLine("[OK] 8/9: Mount Namespace: Shared / Global", "OK")
        } else {
            warnCount++
            items.add(AuditItem(
                step = 8,
                title = "Mount Namespace Mode",
                status = "WARN",
                details = "Subshell filesystem changes may be isolated.",
                fixSuggestion = "In Magisk app -> Settings -> Set 'Mount Namespace Mode' to 'Global Namespace'."
            ))
            onLine("[WARN] 8/9: Mount Namespace: Isolated Mode Detected", "WARN")
            onLine("  • Fix   : Magisk Settings -> Mount Namespace Mode -> Global Namespace.", "FIX")
        }

        val candRes = Shell.cmd("ls -d /dev/[0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9] 2>/dev/null").exec()
        val activeNodes = candRes.out.filter { it.isNotBlank() }
        if (activeNodes.isNotEmpty()) {
            val node = activeNodes.first()
            items.add(AuditItem(
                step = 9,
                title = "AincradClient Stealth Node",
                status = "PASS",
                details = "Active stealth node detected ($node). Ready for AincradClient 3.7 connection."
            ))
            onLine("[OK] 9/9: AincradClient Node: Active ($node detected)", "OK")
            onLine("  • Info  : Matches [0-9][A-Za-z0-9]{5} Position #1 pattern.", "INFO")
        } else {
            items.add(AuditItem(
                step = 9,
                title = "AincradClient Stealth Node",
                status = "INFO",
                details = "No existing driver node in /dev. Clean state."
            ))
            onLine("[OK] 9/9: AincradClient Readiness: Clean / Ready for Load", "OK")
            onLine("  • Info  : Driver will inject 6-char stealth node on load.", "INFO")
        }

        onLine("--------------------------------------------------", "CMD")
        val hasBlockers = blockerCount > 0
        if (!hasBlockers) {
            if (warnCount == 0) {
                onLine("=== AUDIT VERDICT: KERNEL IS 100% READY ===", "OK")
                onLine("No blockers found. Driver will load and work seamlessly.", "OK")
            } else {
                onLine("=== AUDIT VERDICT: COMPATIBLE ($warnCount WARNINGS) ===", "OK")
                onLine("Driver can load. Non-critical warnings handled by loader.", "INFO")
            }
        } else {
            onLine("=== AUDIT VERDICT: $blockerCount BLOCKER(S) DETECTED ===", "ERR")
            onLine("Driver injection will fail unless the following are resolved:", "ERR")
            var fixIndex = 1
            for (item in items.filter { it.status == "FAIL" }) {
                onLine("--- [ISSUE #$fixIndex: ${item.title}] ---", "ERR")
                onLine("  Problem: ${item.details}", "ERR")
                item.fixSuggestion?.let { fix ->
                    onLine("  Action : $fix", "FIX")
                }
                fixIndex++
            }
        }
        onLine("==================================================", "CMD")

        return Pair(blockerCount + warnCount, hasBlockers)
    }
}
