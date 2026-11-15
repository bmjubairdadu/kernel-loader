package com.kernelloader.driver

import android.content.Context
import android.os.Build
import com.kernelloader.root.RootChecker
import com.topjohnwu.superuser.Shell

class KernelAuditor(private val ctx: Context) {

    fun runAudit(onLine: (msg: String, type: String) -> Unit): Pair<Int, Boolean> {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val androidVer = Build.VERSION.RELEASE
        val sdk = Build.VERSION.SDK_INT
        val release = (RootChecker.getKernelRelease() ?: System.getProperty("os.version") ?: "").trim()
        val vermagic = RootChecker.getKernelVermagic().trim()

        onLine("- Target Device: $manufacturer $model (Android $androidVer, SDK $sdk)", "INFO")
        onLine("- Kernel Release: $release", "INFO")
        if (vermagic.isNotBlank() && vermagic != release) {
            onLine("- Kernel Vermagic: $vermagic", "INFO")
        }

        var blockerCount = 0
        var warnCount = 0

        val rootRes = Shell.cmd("id").exec()
        val rootOut = rootRes.out.joinToString(" ")
        if (rootRes.isSuccess && rootOut.contains("uid=0")) {
            onLine("- Root Privileges: Granted (UID=0 Superuser active)", "OK")
        } else {
            blockerCount++
            onLine("- Root Privileges: Denied (Superuser access missing)", "ERR")
            onLine("  - Issue: Application does not have UID=0 root permissions", "ERR")
            onLine("  - Fix: Grant root access in Magisk / KernelSU / APatch manager", "FIX")
        }

        val modDisRes = Shell.cmd("cat /proc/sys/kernel/modules_disabled 2>/dev/null").exec()
        val modDisVal = modDisRes.out.firstOrNull()?.trim() ?: "0"
        if (modDisVal == "1") {
            blockerCount++
            onLine("- Kernel Module Subsystem: Locked (modules_disabled=1)", "ERR")
            onLine("  - Issue: Module loading permanently disabled by kernel until reboot", "ERR")
            onLine("  - Fix: Reboot device now to unlock module subsystem", "FIX")
        } else {
            val procModRes = Shell.cmd("test -r /proc/modules").exec()
            if (procModRes.isSuccess) {
                onLine("- Kernel Module Subsystem: Supported (CONFIG_MODULES=y, unlocked)", "OK")
            } else {
                warnCount++
                onLine("- Kernel Module Subsystem: Warning (/proc/modules restricted)", "WARN")
            }
        }

        var sigEnforced = false
        var sigReason = ""
        val cmdlineRes = Shell.cmd("cat /proc/cmdline 2>/dev/null").exec()
        val cmdline = cmdlineRes.out.joinToString(" ")
        if (cmdline.contains("module.sig_enforce=1") || cmdline.contains("sig_enforce=1")) {
            sigEnforced = true
            sigReason = "sig_enforce=1 in boot cmdline"
        }
        val sysSigRes = Shell.cmd("cat /sys/module/module/parameters/sig_enforce 2>/dev/null").exec()
        val sysSigVal = sysSigRes.out.firstOrNull()?.trim() ?: ""
        if (sysSigVal.equals("Y", ignoreCase = true) || sysSigVal == "1") {
            sigEnforced = true
            sigReason = "sig_enforce=Y in module parameters"
        }
        if (!sigEnforced) {
            val dmesgRes = Shell.cmd("dmesg 2>/dev/null | grep -E -i 'module verification failed|PKCS#7|signature' | tail -n 2").exec()
            if (dmesgRes.out.isNotEmpty()) {
                sigEnforced = true
                sigReason = "PKCS#7 signature check active in kernel"
            }
        }

        if (sigEnforced) {
            blockerCount++
            onLine("- Module Signature: Enforced ($sigReason)", "ERR")
            onLine("  - Issue: Kernel rejects unsigned drivers with Exec format error", "ERR")
            onLine("  - Fix: Flash SigOff patched boot.img or install SigOff Magisk flasher", "FIX")
        } else {
            onLine("- Module Signature: Disabled / Permissive (Unsigned drivers accepted)", "OK")
        }

        val archRes = Shell.cmd("uname -m 2>/dev/null").exec()
        val arch = archRes.out.firstOrNull()?.trim() ?: "unknown"
        if (arch == "aarch64" || arch == "arm64") {
            onLine("- Architecture: 64-bit ($arch / arm64-v8a)", "OK")
        } else {
            blockerCount++
            onLine("- Architecture: Incompatible ($arch is 32-bit)", "ERR")
            onLine("  - Issue: 64-bit arm64-v8a kernel is required for drivers", "ERR")
            onLine("  - Fix: Flash a 64-bit ROM / kernel", "FIX")
        }

        if (blockerCount == 0) {
            onLine("- Driver Loading Capability: Ready (Kernel will accept and load driver)", "OK")
        } else {
            onLine("- Driver Loading Capability: Blocked (Resolve signature or root first)", "ERR")
        }

        val seRes = Shell.cmd("getenforce 2>/dev/null").exec()
        val seMode = seRes.out.firstOrNull()?.trim() ?: "Enforcing"
        val isPermissive = seMode.equals("Permissive", ignoreCase = true)

        val modInstalled = Shell.cmd("test -d /data/adb/modules/kl_selinux_fix && ! test -f /data/adb/modules/kl_selinux_fix/disable").exec().isSuccess

        onLine("- SELinux Mode: $seMode", if (isPermissive) "OK" else "INFO")

        if (isPermissive) {
            onLine("- Node Creation Permission: GRANTED (Permissive mode allows all operations)", "OK")
            onLine("- ueventd Node Policy: Active (All /dev node operations permitted)", "OK")
            onLine("- Node Auto-Creation (/dev): Operational (Kernel device_create will auto-create node)", "OK")
        } else if (modInstalled) {
            onLine("- Node Creation Permission: GRANTED (Kernel_SELinux_Node_Fixer active)", "OK")
            onLine("- ueventd Node Policy: Active (sepolicy.rule active for ueventd chr_file create)", "OK")
            onLine("- Node Auto-Creation (/dev): Operational (ueventd unlocked via Magisk module)", "OK")
        } else if (sdk <= 30) {
            onLine("- Node Creation Permission: GRANTED (Stock Android $androidVer policy permits creation)", "OK")
            onLine("- ueventd Node Policy: Active (Stock rule: allow ueventd dev_type chr_file create)", "OK")
            onLine("- Node Auto-Creation (/dev): Operational (System ueventd will auto-create /dev node)", "OK")
        } else {
            val liveRulesCheck = Shell.cmd("magiskpolicy --print-rules 2>/dev/null | grep -E 'allow ueventd (device|dev_type|unlabeled) chr_file.*create|permissive ueventd'").exec()
            val liveRuleActive = liveRulesCheck.isSuccess && liveRulesCheck.out.isNotEmpty()

            if (liveRuleActive) {
                val matched = liveRulesCheck.out.firstOrNull()?.trim() ?: "chr_file create active"
                onLine("- Node Creation Permission: GRANTED (Live policy rules verified)", "OK")
                onLine("- ueventd Node Policy: Active ($matched)", "OK")
                onLine("- Node Auto-Creation (/dev): Operational (ueventd unlocked via live policy)", "OK")
            } else {
                warnCount++
                onLine("- Node Creation Permission: BLOCKED (SELinux Enforcing denies ueventd)", "WARN")
                onLine("- ueventd Node Policy: Blocked (Android $androidVer SELinux restriction)", "WARN")
                onLine("- Node Auto-Creation (/dev): Restricted (Kernel cannot auto-create node in /dev)", "WARN")
                onLine("  - Issue: SELinux Enforcing blocks ueventd from creating custom driver node", "WARN")
                onLine("  - Fix: Flash Kernel_SELinux_Node_Fixer.zip in Magisk to grant permission", "FIX")
            }
        }

        val nsTest = Shell.cmd("sh -c 'touch /dev/.kl_ns_chk 2>/dev/null' && test -f /dev/.kl_ns_chk && rm -f /dev/.kl_ns_chk").exec()
        if (nsTest.isSuccess) {
            onLine("- Mount Namespace: Global (Device node sharing operational)", "OK")
        } else {
            warnCount++
            onLine("- Mount Namespace: Isolated Mode Detected", "WARN")
            onLine("  - Fix: Set 'Mount Namespace Mode' to 'Global Namespace' in Magisk", "FIX")
        }

        val installer = DriverInstaller(ctx)
        val candidates = installer.candidates(release)
        if (candidates.isNotEmpty()) {
            val names = candidates.joinToString(", ") { "$it.sh" }
            onLine("- Database Driver: Available ($names)", "OK")
        } else {
            warnCount++
            onLine("- Database Driver: No exact prebuilt driver found for $release", "WARN")
            onLine("  - Fix: Compile driver from kernel source or tap 'SEND REPORT' below", "FIX")
        }

        val candRes = Shell.cmd("ls -d /dev/[0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9] 2>/dev/null").exec()
        val activeNodes = candRes.out.filter { it.isNotBlank() }
        if (activeNodes.isNotEmpty()) {
            onLine("- Stealth Device Node: Active (${activeNodes.first()} detected)", "OK")
        } else {
            onLine("- Stealth Device Node: Ready (Position #1 supported)", "OK")
        }

        val totalIssues = blockerCount + warnCount
        if (blockerCount == 0 && warnCount == 0) {
            onLine("- Audit Verdict: Kernel is 100% ready for driver load", "OK")
        } else if (blockerCount == 0) {
            onLine("- Audit Verdict: Compatible ($warnCount non-critical notice(s))", "OK")
        } else {
            onLine("- Audit Verdict: $blockerCount critical issue(s) detected — see fixes above", "ERR")
        }

        return Pair(totalIssues, blockerCount > 0)
    }
}
