package com.kernelloader.driver

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.File

object UniversalKernelLoader {

    private fun extractAssetToTmp(context: Context, name: String): Boolean = try {
        val local = File(context.cacheDir, name)
        context.assets.open(name).use { input ->
            val bytes = input.readBytes()
            if (!local.exists() || local.length() != bytes.size.toLong()) local.writeBytes(bytes)
        }
        val target = "/data/local/tmp/$name"
        Shell.cmd(
            "cp \"${local.absolutePath}\" $target 2>/dev/null",
            "chmod 755 $target 2>/dev/null"
        ).exec().isSuccess
    } catch (_: Exception) {
        false
    }

    fun autoUnlockSignatureStandaloneDetail(
        context: Context? = null,
        vm: DriverViewModel? = null
    ): Pair<Boolean, String> {
        if (context != null) {
            extractAssetToTmp(context, "magiskboot")
            extractAssetToTmp(context, "sigpatch")
        }
        return try {
            val d = "$"
            val script = """
                slot=${d}(getprop ro.boot.slot_suffix 2>/dev/null)
                [ -z "${d}slot" ] && slot=${d}(grep -o 'androidboot.slot_suffix=[^ ]*' /proc/cmdline 2>/dev/null | cut -d= -f2)
                if [ -z "${d}slot" ]; then
                  s=${d}(getprop ro.boot.slot 2>/dev/null)
                  [ -n "${d}s" ] && slot="_${d}s"
                fi

                find_boot() {
                  for name in "boot${d}slot" "boot" "boot_a" "boot_b"; do
                    for dir in /dev/block/by-name \
                               /dev/block/bootdevice/by-name \
                               /dev/block/platform/*/by-name \
                               /dev/block/platform/*/*/by-name \
                               /dev/block/mapper; do
                      if [ -b "${d}dir/${d}name" ]; then
                        readlink -f "${d}dir/${d}name" 2>/dev/null || echo "${d}dir/${d}name"
                        return 0
                      fi
                    done
                  done

                  for b in /sys/class/block/*; do
                    uevent="${d}b/uevent"
                    if [ -f "${d}uevent" ]; then
                      if grep -q -E "PARTNAME=boot${d}slot$|PARTNAME=boot$" "${d}uevent" 2>/dev/null; then
                        devname=${d}(basename "${d}b")
                        if [ -b "/dev/block/${d}devname" ]; then
                          echo "/dev/block/${d}devname"
                          return 0
                        fi
                      fi
                    fi
                  done

                  dev=${d}(find /dev/block -name "boot${d}slot" -o -name "boot" 2>/dev/null | head -n 1)
                  if [ -n "${d}dev" ] && [ -b "${d}dev" ]; then
                    readlink -f "${d}dev" 2>/dev/null || echo "${d}dev"
                    return 0
                  fi

                  return 1
                }

                BOOT_DEV=${d}(find_boot)
                if [ -z "${d}BOOT_DEV" ] || [ ! -b "${d}BOOT_DEV" ]; then
                  echo "BOOT_DEV_NOT_FOUND"
                  exit 1
                fi

                MB=""
                for b in /data/local/tmp/magiskboot \
                         /data/adb/ap/bin/magiskboot \
                         /data/adb/magisk/magiskboot \
                         /data/adb/ksu/bin/magiskboot \
                         /system/bin/magiskboot; do
                  if [ -x "${d}b" ]; then
                    MB="${d}b"
                    break
                  fi
                done

                if [ -z "${d}MB" ] && [ -f /data/local/tmp/magiskboot ]; then
                  chmod 0755 /data/local/tmp/magiskboot 2>/dev/null
                  [ -x /data/local/tmp/magiskboot ] && MB="/data/local/tmp/magiskboot"
                fi

                if [ -z "${d}MB" ]; then
                  echo "MAGISKBOOT_NOT_FOUND"
                  exit 2
                fi

                WORK="/data/local/tmp/kl_sig_unlock"
                rm -rf "${d}WORK"
                mkdir -p "${d}WORK"
                cd "${d}WORK" || exit 3

                dd if="${d}BOOT_DEV" of="${d}WORK/boot.img" bs=4096 2>/dev/null
                if [ ! -s "${d}WORK/boot.img" ]; then
                  echo "DUMP_FAILED"
                  rm -rf "${d}WORK"
                  exit 4
                fi

                "${d}MB" unpack "${d}WORK/boot.img" >/dev/null 2>&1
                if [ ! -f "${d}WORK/kernel" ]; then
                  echo "UNPACK_FAILED"
                  rm -rf "${d}WORK"
                  exit 5
                fi

                PATCHED=0
                P="00008052c0035fd61f2003d5"

                chmod 0755 /data/local/tmp/sigpatch 2>/dev/null
                if [ -x /data/local/tmp/sigpatch ]; then
                  /data/local/tmp/sigpatch "${d}WORK/kernel" >/dev/null 2>&1
                  if [ ${d}? -eq 0 ]; then
                    PATCHED=1
                  fi
                fi

                if [ ${d}PATCHED -eq 0 ]; then
                  "${d}MB" hexpatch "${d}WORK/kernel" 4841019000c14339c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  "${d}MB" hexpatch "${d}WORK/kernel" e8d000b000614239c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  "${d}MB" hexpatch "${d}WORK/kernel" c80601f000415039c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  "${d}MB" hexpatch "${d}WORK/kernel" 2835019000816c39c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  "${d}MB" hexpatch "${d}WORK/kernel" a8f6009000817439c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  "${d}MB" hexpatch "${d}WORK/kernel" c839019000c15739c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  "${d}MB" hexpatch "${d}WORK/kernel" c8f000d000614739c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  "${d}MB" hexpatch "${d}WORK/kernel" c2030035f50300aaa90e41f83f7500f143030054080440f9 f40301aaf30300aa390000141f2003d51f2003d51f2003d5 >/dev/null 2>&1 && PATCHED=1
                  "${d}MB" hexpatch "${d}WORK/kernel" 0828019000214c39c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  "${d}MB" hexpatch "${d}WORK/kernel" 281a01b000817f39c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  "${d}MB" hexpatch "${d}WORK/kernel" 82030035c00640f91f7000f1 23000014c00640f91f7000f1 >/dev/null 2>&1 && PATCHED=1
                fi

                if [ ${d}PATCHED -eq 0 ]; then
                  CUR_BOOT_HASH=${d}(sha256sum "${d}WORK/boot.img" | cut -d' ' -f1)
                  if grep -q "patched_boot_sha256=${d}CUR_BOOT_HASH" /data/adb/kernel_loader/status.prop 2>/dev/null; then
                    echo "ALREADY_PATCHED"
                    echo "PATCH_SUCCESS"
                    rm -rf "${d}WORK"
                    exit 0
                  fi
                  echo "PATTERN_NOT_FOUND"
                  rm -rf "${d}WORK"
                  exit 6
                fi

                "${d}MB" repack "${d}WORK/boot.img" >/dev/null 2>&1
                if [ ! -f "${d}WORK/new-boot.img" ] || [ ! -s "${d}WORK/new-boot.img" ]; then
                  echo "REPACK_FAILED"
                  rm -rf "${d}WORK"
                  exit 7
                fi

                blockdev --setrw "${d}BOOT_DEV" 2>/dev/null
                chmod 660 "${d}BOOT_DEV" 2>/dev/null
                dd if="${d}WORK/new-boot.img" of="${d}BOOT_DEV" bs=4096 2>"${d}WORK/flash_err.txt"
                FLASH_RC=${d}?
                if [ ${d}FLASH_RC -ne 0 ]; then
                  ERR_TXT=${d}(cat "${d}WORK/flash_err.txt" 2>/dev/null | tr '\n' ' ')
                  cp -f "${d}WORK/new-boot.img" /sdcard/patched_boot.img 2>/dev/null
                  echo "FLASH_WRITE_FAILED: ${d}ERR_TXT"
                  rm -rf "${d}WORK"
                  exit 8
                fi
                sync

                NEW_SIZE=${d}(wc -c < "${d}WORK/new-boot.img")
                NEW_HASH=${d}(sha256sum "${d}WORK/new-boot.img" | cut -d' ' -f1)
                COUNT=${d}(( (NEW_SIZE + 4095) / 4096 ))
                dd if="${d}BOOT_DEV" of="${d}WORK/readback.img" bs=4096 count="${d}COUNT" 2>/dev/null
                head -c "${d}NEW_SIZE" "${d}WORK/readback.img" > "${d}WORK/readback_exact.img" 2>/dev/null || dd if="${d}WORK/readback.img" of="${d}WORK/readback_exact.img" bs=1 count="${d}NEW_SIZE" 2>/dev/null
                READ_HASH=${d}(sha256sum "${d}WORK/readback_exact.img" | cut -d' ' -f1)

                if [ "${d}NEW_HASH" != "${d}READ_HASH" ]; then
                  cp -f "${d}WORK/new-boot.img" /sdcard/patched_boot.img 2>/dev/null
                  echo "FLASH_VERIFY_FAILED: OEM block protection prevented writing to boot partition"
                  rm -rf "${d}WORK"
                  exit 9
                fi

                mkdir -p /data/adb/kernel_loader 2>/dev/null
                echo "sig_unlocked=true" > /data/adb/kernel_loader/status.prop 2>/dev/null
                echo "patched_boot_sha256=${d}NEW_HASH" >> /data/adb/kernel_loader/status.prop 2>/dev/null
                echo "boot_dev=${d}BOOT_DEV" >> /data/adb/kernel_loader/status.prop 2>/dev/null
                rm -rf "${d}WORK"
                echo "PATCH_SUCCESS"
            """.trimIndent()

            val res = Shell.cmd(script).exec()
            val out = (res.out + res.err).joinToString("\n").trim()

            if (vm != null) {
                if (out.contains("PATCH_SUCCESS")) {
                    vm.tlog("[SIG-UNLOCK] Module signature verification disabled & verified in boot partition", "OK")
                } else if (out.isNotEmpty()) {
                    vm.tlog("[SIG-UNLOCK] Patcher result: $out", "WARN")
                    if (out.contains("FLASH_WRITE_FAILED") || out.contains("FLASH_VERIFY_FAILED")) {
                        vm.tlog("[SIG-UNLOCK] Boot partition is write-protected on this ROM.", "WARN")
                        vm.tlog("[SIG-UNLOCK] Patched boot image saved to /sdcard/patched_boot.img", "INFO")
                    }
                }
            }

            Pair(out.contains("PATCH_SUCCESS"), out)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Exception")
        } finally {
            Shell.cmd("rm -f /data/local/tmp/magiskboot /data/local/tmp/sigpatch 2>/dev/null").exec()
        }
    }
}
