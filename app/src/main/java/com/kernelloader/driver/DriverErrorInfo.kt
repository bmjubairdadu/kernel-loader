package com.kernelloader.driver

import java.util.Locale

enum class DriverErrorType {
    SIGNATURE_LOCKED,
    ROOT_DENIED,
    NO_MATCHING_DRIVER,
    VERMAGIC_MISMATCH,
    GENERAL
}

data class DriverErrorInfo(
    val errorType: DriverErrorType,
    val titleEn: String,
    val titleBn: String = titleEn,
    val rawError: String,
    val descriptionEn: String,
    val descriptionBn: String = descriptionEn,
    val solutionStepsEn: List<String>,
    val solutionStepsBn: List<String> = solutionStepsEn,
    val canReboot: Boolean = false
) {
    companion object {
        fun fromError(rawError: String, dmesg: String = "", logs: String = ""): DriverErrorInfo {
            val combined = "$rawError $dmesg $logs".lowercase(Locale.US)

            return when {
                combined.contains("signature unlocked") ||
                combined.contains("restart phone once") -> {
                    DriverErrorInfo(
                        errorType = DriverErrorType.SIGNATURE_LOCKED,
                        titleEn = "Kernel Signature Unlocked!",
                        rawError = "Kernel signature enforcement unlocked in boot image",
                        descriptionEn = "Kernel signature check has been unlocked in your boot partition! Please restart your phone now. After reboot, tap LOAD and the driver will load instantly without any signature rejection.",
                        solutionStepsEn = listOf(
                            "Tap 'Reboot Now' below to restart your phone.",
                            "After rebooting, open Kernel Loader and tap LOAD.",
                            "Your Stealth Engine driver will load cleanly with a green checkmark!"
                        ),
                        canReboot = true
                    )
                }

                combined.contains("unsigned module") ||
                combined.contains("signature locked") ||
                combined.contains("module_sig_force") ||
                combined.contains("key rejected") ||
                combined.contains("key was rejected") ||
                combined.contains("required key not available") ||
                combined.contains("module verification failed") -> {
                    DriverErrorInfo(
                        errorType = DriverErrorType.SIGNATURE_LOCKED,
                        titleEn = "Kernel Signature Locked",
                        rawError = if (rawError.isNotBlank()) rawError else "CONFIG_MODULE_SIG_FORCE active (Unsigned module rejected)",
                        descriptionEn = "Your device's kernel enforces strict module signature verification (CONFIG_MODULE_SIG_FORCE=y) and rejects unsigned drivers.",
                        solutionStepsEn = listOf(
                            "Open APatch, FolkPatch, or Magisk app on your phone.",
                            "Go to 'Modules' tab (NOT KPM tab) ➔ Tap 'Install a module' (or 'Install from storage').",
                            "Select and flash 'KernelLoader-Engine.zip' (or 'Universal_Kernel_Sig_Bypass.zip').",
                            "Reboot your phone once, open Kernel Loader, and tap LOAD — it will connect via Engine Bridge and load instantly!"
                        )
                    )
                }

                combined.contains("root permission: denied") ||
                combined.contains("root permission denied") ||
                combined.contains("permission denied") ||
                combined.contains("su: not found") ||
                combined.contains("not rooted") -> {
                    DriverErrorInfo(
                        errorType = DriverErrorType.ROOT_DENIED,
                        titleEn = "Root Access Denied",
                        rawError = if (rawError.isNotBlank()) rawError else "Superuser access denied or su missing",
                        descriptionEn = "Kernel Loader requires Superuser (Root) permission to insert and manage driver modules in the Linux kernel.",
                        solutionStepsEn = listOf(
                            "Open your root manager app (Magisk, KernelSU, or APatch).",
                            "Go to Superuser permissions and set 'Kernel Loader' to GRANT.",
                            "Return to Kernel Loader and tap LOAD again."
                        )
                    )
                }

                combined.contains("no exact") ||
                combined.contains("not found in database") ||
                combined.contains("report ready") ||
                combined.contains("no supported") -> {
                    DriverErrorInfo(
                        errorType = DriverErrorType.NO_MATCHING_DRIVER,
                        titleEn = "No Matching Driver in Database",
                        rawError = if (rawError.isNotBlank()) rawError else "Exact kernel vermagic driver not found",
                        descriptionEn = "No pre-compiled driver in the database matches your phone's exact kernel build and vermagic.",
                        solutionStepsEn = listOf(
                            "An automated device report pack has been saved to your device storage.",
                            "Send the report pack ZIP or text to the driver builder to build a Pure RT driver for your kernel.",
                            "Once the driver is compiled and added to the database, tap LOAD to automatically download and load it."
                        )
                    )
                }

                combined.contains("exec format error") ||
                combined.contains("invalid module format") ||
                combined.contains("disagrees about version") ||
                combined.contains("vermagic mismatch") -> {
                    DriverErrorInfo(
                        errorType = DriverErrorType.VERMAGIC_MISMATCH,
                        titleEn = "Kernel Vermagic Mismatch",
                        rawError = if (rawError.isNotBlank()) rawError else "Exec format error / symbol CRC mismatch",
                        descriptionEn = "The driver module was compiled for a different kernel build or symbol CRC table and cannot be loaded.",
                        solutionStepsEn = listOf(
                            "Check your running kernel version and vermagic in the Status Console.",
                            "Make sure the driver was built against your phone's exact Module.symvers symbol table.",
                            "Do not use generic or incompatible .ko modules from other phone models."
                        )
                    )
                }

                else -> {
                    DriverErrorInfo(
                        errorType = DriverErrorType.GENERAL,
                        titleEn = "Driver Load Failed",
                        rawError = rawError.ifBlank { "Unknown driver insertion error" },
                        descriptionEn = "An error occurred while inserting the driver module into the Linux kernel.",
                        solutionStepsEn = listOf(
                            "Tap 'Copy Log' below to copy full diagnostic details for assistance.",
                            "Reboot your device once and verify that root access is working normally.",
                            "Check the terminal console output for detailed dmesg error logs."
                        )
                    )
                }
            }
        }
    }
}
