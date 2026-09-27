package com.kernelloader.driver

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * BOOT AUTO-LOAD
 * ==============
 * A kernel module lives in kernel memory only. Every reboot wipes it, so the
 * user had to tap LOAD again after each restart - and the game-mod client
 * (Aincrad / Angry Mod) could not read game memory until they did.
 *
 * Android has no "modules-load" service, so the supported way to run something
 * as root at boot is a Magisk/KernelSU/APatch `service.d` script:
 *
 *     /data/adb/service.d/90-kloder.sh
 *
 * This class owns that script plus the state it needs:
 *
 *   - which ABI family was loaded (rt / qx)
 *   - the EXACT bytes that were insmod'ed (already vermagic-patched, so the
 *     staged copy is byte-identical to what the kernel accepted)
 *   - the real module name in lsmod (kmem_337, kmem_337_qx, entryi, ... -
 *     never the .ko file name, which is only a label)
 *   - the /dev node the client opens (/dev/wanbai)
 *
 * The script is IDEMPOTENT: it checks /proc/modules first and exits when the
 * driver is already there, so a manual LOAD followed by a reboot is harmless.
 */
object DriverAutoload {

    /** Where the staged .ko + log live. Magisk dir when available, else /data/local/tmp. */
    private const val STAGE_PRIMARY = "/data/adb/kloder"
    private const val STAGE_FALLBACK = "/data/local/tmp/kloder"

    private const val SCRIPT_PRIMARY = "/data/adb/service.d/90-kloder.sh"
    private const val SCRIPT_FALLBACK = "/data/local/tmp/kloder-autoload.sh"

    private const val PREFS = "kloder_autoload"

    // ---------------- persisted state ----------------

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = _enabled
        private set(v) { _enabled = v }

    private var _enabled = false

    /** Driver file that was staged for boot (absolute path), or "". */
    var stagedKo: String = ""
        private set

    /** Real lsmod name of the loaded driver, e.g. "kmem_337". */
    var moduleName: String = ""
        private set

    /** "rt" or "qx". */
    var variant: String = ""
        private set

    /** /dev node the client opens, e.g. "wanbai". */
    var devNode: String = "wanbai"
        private set

    /** Last boot-script run result, shown in the console. */
    var lastBootLog: String = ""
        private set

    fun state(ctx: Context) {
        prefs(ctx).let { p ->
            _enabled = p.getBoolean("enabled", false)
            stagedKo = p.getString("ko", "") ?: ""
            moduleName = p.getString("module", "") ?: ""
            variant = p.getString("variant", "") ?: ""
            devNode = p.getString("node", "wanbai") ?: "wanbai"
        }
    }

    private fun save(ctx: Context) {
        prefs(ctx).edit()
            .putBoolean("enabled", _enabled)
            .putString("ko", stagedKo)
            .putString("module", moduleName)
            .putString("variant", variant)
            .putString("node", devNode)
            .apply()
    }

    // ---------------- where things live ----------------

    private fun hasMagisk(): Boolean =
        try { Shell.cmd("ls -d /data/adb 2>/dev/null").exec().isSuccess } catch (e: Exception) { false }

    fun stageDir(): String = if (hasMagisk()) STAGE_PRIMARY else STAGE_FALLBACK

    fun scriptPath(): String = if (hasMagisk()) SCRIPT_PRIMARY else SCRIPT_FALLBACK

    /**
     * Is there anything that will actually RUN a boot script?
     * Magisk / KernelSU / APatch all execute the shell scripts in
     * /data/adb/service.d at boot. Without that directory the "auto-load at
     * boot" promise cannot be kept, so the app says so instead of silently
     * pretending it will work.
     */
    fun hasBootRunner(): Boolean = try {
        Shell.cmd(
            "ls -d /data/adb/service.d 2>/dev/null || " +
                    "ls -d /data/adb/modules 2>/dev/null || " +
                    "ls /data/adb/magisk 2>/dev/null"
        ).exec().isSuccess
    } catch (e: Exception) {
        false
    }

    // ---------------- the boot script ----------------

    /**
     * Written to service.d. Deliberately defensive: service.d scripts run with
     * no ordering guarantees and race the rest of boot, so every step waits
     * for what it needs and every failure is logged instead of aborting.
     */
    private fun buildScript(): String {
        // The shell script is written with '@' standing in for '$' and the
        // marker is swapped at the end. A Kotlin raw string would need every
        // shell '$' escaped, and one missed escape silently produces a broken
        // boot script; this way each character is written exactly once and
        // there is nothing to misread. The script contains no real '@'.
        val dir = stageDir()
        val ko = stagedKo
        val mod = moduleName
        val node = "/dev/$devNode"
        val fam = variant.uppercase()
        val shell = """
#!/system/bin/sh
# ---------------------------------------------------------------
# Kernel Loder - load the memory driver at boot.
# Generated by the Kernel Loder app. Safe to delete.
#
# Module     : @MODNAME@
# ABI family : @FAMILY@
# Device node: @NODE@
# ---------------------------------------------------------------
DIR="@DIR@"
KO="@KO@"
MOD="@MODNAME@"
NODE="@NODE@"
LOG="@DIR@/boot.log"

mkdir -p "@DIR@" 2>/dev/null
say() { echo "@(date '+%H:%M:%S') @*" >> "@LOG@"; }

say "boot auto-load starting (module=@MODNAME@)"

# 1. wait for /data to carry the staged .ko (max 60s)
i=0
while [ ! -f "@KO@" ]; do
    i=@((i+1))
    if [ @i -gt 60 ]; then
        say "ABORT: @KO@ never appeared"
        exit 1
    fi
    sleep 1
done
say "found staged driver"

# 2. let init/vold finish so the driver does not compete with boot
#    (bounded at 90s; a missing getprop must not hang us forever)
i=0
while [ @i -lt 90 ]; do
    getprop sys.boot_completed 2>/dev/null | grep -q '1' && break
    i=@((i+1))
    sleep 1
done
say "boot stage released"

# 3. already loaded? no-op. A module lives in kernel memory until reboot, and
#    a manual load may have run before, so never insmod twice.
if grep -q "^@MODNAME@ " /proc/modules 2>/dev/null; then
    say "SKIP: @MODNAME@ is already loaded"
    chmod 666 "@NODE@" 2>/dev/null
    say "done (node perms refreshed)"
    exit 0
fi

# 4. permissive SELinux: the game-mod client is a separate uid and cannot open
#    the node while SELinux enforces. Best effort.
ENF=@(getenforce 2>/dev/null)
if [ "@ENF@" = "Enforcing" ]; then
    if setenforce 0 2>/dev/null; then
        say "SELinux -> Permissive"
    else
        say "WARN: setenforce 0 denied - game apps may not reach the node"
    fi
else
    say "SELinux already @ENF@"
fi

# 5. load it
chmod 644 "@KO@" 2>/dev/null
if insmod "@KO@" >> "@LOG@" 2>&1; then
    say "OK: insmod succeeded"
else
    say "FAIL: insmod failed (details in @LOG@.why)"
    tail -n 5 "@LOG@" > "@LOG@.why" 2>/dev/null
fi

# 6. the client opens the node as any uid -> world R/W
sleep 1
if [ -e "@NODE@" ]; then
    chmod 666 "@NODE@" 2>/dev/null
    say "OK: @NODE@ present, perms 666"
else
    say "WARN: @NODE@ missing - is the driver really loaded?"
fi
say "boot auto-load finished"
""".trimIndent() + "\n"
        // Every @NAME@ token used in the template MUST appear in this list -
        // an unknown token would survive the first pass and then have its '@'
        // turned into a stray '$', silently corrupting the script.
        val out = shell
            .replace("@MODNAME@", mod)
            .replace("@FAMILY@", fam)
            .replace("@NODE@", node)
            .replace("@KO@", ko)
            .replace("@DIR@", dir)
            .replace("@LOG@", "$dir/boot.log")
            // literal "$ENF" - the shell variable, not the text "ENF"
            .replace("@ENF@", "\$ENF")
            .replace("@", "$")
        // Guard: a leftover '@' means the template grew a token that is not in
        // the list above. Shipping that would write a broken boot script that
        // fails silently at every boot, so fail loudly here instead.
        if (out.contains('@')) {
            val bad = Regex("@[A-Za-z0-9_]*").findAll(out).map { it.value }.distinct()
            throw IllegalStateException("boot script template has unknown placeholders: ${bad.joinToString()}")
        }
        return out
    }

    // ---------------- enable / disable / status ----------------

    /**
     * Install the boot script. [koFile] must be the EXACT file that was
     * insmod'ed (post vermagic-patch), so the staged copy is byte-identical to
     * what the kernel already accepted.
     */
    fun enable(ctx: Context, koFile: File, modName: String, family: String, node: String, log: (String, String) -> Unit): Boolean {
        if (modName.isBlank() || modName == "Module") {
            log("AUTOLOAD: refusing - the loaded module name is unknown", "ERR")
            return false
        }
        val dir = stageDir()
        val dst = "$dir/driver.ko"
        val res = try {
            Shell.cmd(
                "mkdir -p $dir",
                "cp '${koFile.absolutePath}' $dst",
                "chmod 644 $dst",
                "sync"
            ).exec()
        } catch (e: Exception) {
            log("AUTOLOAD: staging failed: ${e.message}", "ERR")
            return false
        }
        if (!res.isSuccess) {
            log("AUTOLOAD: could not stage the .ko into $dir", "ERR")
            res.err.forEach { if (it.isNotBlank()) log("AUTOLOAD: $it", "ERR") }
            return false
        }

        moduleName = modName.trim()
        variant = family.trim()
        devNode = node.trim().ifEmpty { "wanbai" }
        stagedKo = dst

        val script = try {
            buildScript()
        } catch (e: Exception) {
            log("AUTOLOAD: refusing to install a boot script we could not render", "ERR")
            log("AUTOLOAD: ${e.message}", "ERR")
            _enabled = false
            save(ctx)
            return false
        }
        val scriptPath = scriptPath()
        val w = try {
            // service.d may not exist on a non-Magisk root setup.
            Shell.cmd("mkdir -p \$(dirname $scriptPath)").exec()
            File("/data/local/tmp/kloder-boot.sh").apply {
                writeText(script)
            }.let { tmp ->
                Shell.cmd("cp '${tmp.absolutePath}' $scriptPath", "chmod 755 $scriptPath", "sync").exec()
            }
        } catch (e: Exception) {
            log("AUTOLOAD: could not write $scriptPath: ${e.message}", "ERR")
            return false
        }
        if (!w.isSuccess) {
            w.err.forEach { if (it.isNotBlank()) log("AUTOLOAD: $it", "ERR") }
            log("AUTOLOAD: boot script NOT installed", "ERR")
            return false
        }

        _enabled = true
        save(ctx)
        log("AUTOLOAD: boot script installed -> $scriptPath", "OK")
        log("AUTOLOAD: will insmod '$moduleName' (${variant.uppercase()}) at every boot", "OK")
        log("AUTOLOAD: node $devNode, log $dir/boot.log", "INFO")
        return true
    }

    fun disable(ctx: Context, log: (String, String) -> Unit) {
        val p = scriptPath()
        val dir = stageDir()
        val res = try {
            Shell.cmd("rm -f $p", "$dir/boot.log $dir/boot.log.why 2>/dev/null", "sync").exec()
        } catch (e: Exception) {
            log("AUTOLOAD: remove failed: ${e.message}", "ERR")
            return
        }
        _enabled = false
        save(ctx)
        log(
            if (res.isSuccess) "AUTOLOAD: boot script removed -> driver will NOT auto-load"
            else "AUTOLOAD: could not remove $p (exit ${res.code})",
            if (res.isSuccess) "OK" else "ERR"
        )
    }

    /** One-line status for the home screen. */
    fun statusLine(ctx: Context): String {
        state(ctx)
        if (!_enabled) return "Auto-load at boot: OFF"
        val m = moduleName.ifEmpty { "?" }
        return "Auto-load at boot: ON  ($m at every boot)"
    }

    /** Read the boot log the script wrote, if it ran. */
    fun readBootLog(): String = try {
        Shell.cmd("cat ${stageDir()}/boot.log 2>/dev/null | tail -n 20").exec().out
            .filter { it.isNotBlank() }.joinToString("\n")
    } catch (e: Exception) {
        ""
    }
}
