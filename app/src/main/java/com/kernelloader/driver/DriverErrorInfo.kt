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
    val titleBn: String,
    val rawError: String,
    val descriptionEn: String,
    val descriptionBn: String,
    val solutionStepsEn: List<String>,
    val solutionStepsBn: List<String>,
    val canReboot: Boolean = false
) {
    companion object {
        fun fromError(rawError: String, dmesg: String = "", logs: String = ""): DriverErrorInfo {
            val combined = "$rawError $dmesg $logs".lowercase(Locale.US)

            return when {
                // 0. Auto-Unlocked, Reboot Required
                combined.contains("signature unlocked") ||
                combined.contains("restart phone once") -> {
                    DriverErrorInfo(
                        errorType = DriverErrorType.SIGNATURE_LOCKED,
                        titleEn = "Kernel Signature Unlocked!",
                        titleBn = "কার্নেল সিগনেচার আনলক সম্পন্ন হয়েছে!",
                        rawError = "Kernel signature enforcement unlocked in boot image",
                        descriptionEn = "Kernel signature check has been unlocked in your boot partition! Please restart your phone now. After reboot, tap LOAD and the driver will load instantly without any signature rejection.",
                        descriptionBn = "আপনার ডিভাইসের কার্নেল সিগনেচার লক সফলভাবে আনলক করা হয়েছে! পরিবর্তন কার্যকর করতে এখনই ফোনটি রিস্টার্ট দিন। রিস্টার্টের পর LOAD চাপলেই ড্রাইভার কোনো এরর ছাড়াই লোড হয়ে যাবে।",
                        solutionStepsEn = listOf(
                            "Tap 'Reboot Now' below to restart your phone.",
                            "After rebooting, open Kernel Loader and tap LOAD.",
                            "Your Stealth Engine driver will load cleanly with a green checkmark!"
                        ),
                        solutionStepsBn = listOf(
                            "নিচের 'রিস্টার্ট দিন' বাটনে চাপ দিয়ে এখনই ফোন রিস্টার্ট দিন।",
                            "ফোন চালু হলে Kernel Loader অ্যাপ ওপেন করে LOAD চাপুন।",
                            "কোনো এরর ছাড়াই সাথে সাথে গ্রিন টিক সহ ড্রাইভার লোড হয়ে যাবে!"
                        ),
                        canReboot = true
                    )
                }

                // 1. Signature Locked / Unsigned Module Rejected
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
                        titleBn = "কার্নেল সিগনেচার লক সমস্যা",
                        rawError = if (rawError.isNotBlank()) rawError else "CONFIG_MODULE_SIG_FORCE active (Unsigned module rejected)",
                        descriptionEn = "Your device's kernel enforces strict module signature verification (CONFIG_MODULE_SIG_FORCE=y) and rejects unsigned drivers.",
                        descriptionBn = "আপনার ডিভাইসের কার্নেলে কঠোর সিগনেচার যাচাইকরণ (CONFIG_MODULE_SIG_FORCE) সক্রিয় থাকায় আনসাইন্ড ড্রাইভার রিজেক্ট করা হচ্ছে।",
                        solutionStepsEn = listOf(
                            "Open APatch, FolkPatch, or Magisk app on your phone.",
                            "Go to 'Modules' tab (NOT KPM tab) ➔ Tap 'Install a module' (or 'Install from storage').",
                            "Select and flash 'KernelLoader-Engine.zip' (or 'Universal_Kernel_Sig_Bypass.zip').",
                            "Reboot your phone once, open Kernel Loader, and tap LOAD — it will connect via Engine Bridge and load instantly!"
                        ),
                        solutionStepsBn = listOf(
                            "আপনার ফোনে APatch, FolkPatch বা Magisk অ্যাপ ওপেন করুন।",
                            "KPM ট্যাবে নয়, সোজা 'Modules' (মডিউল) ট্যাবে যান ➔ 'Install a module' (বা Install from storage) চাপুন।",
                            "'KernelLoader-Engine.zip' ফাইলটি সিলেক্ট করে ফ্ল্যাশ করুন।",
                            "ফ্ল্যাশ শেষে ফোন একবার Reboot (রিস্টার্ট) দিন। এরপর Kernel Loader অ্যাপ ওপেন করলেই Engine Bridge কানেক্ট হয়ে সাথে সাথে ড্রাইভার লোড হয়ে যাবে!"
                        )
                    )
                }

                // 2. Root permission denied
                combined.contains("root permission: denied") ||
                combined.contains("root permission denied") ||
                combined.contains("permission denied") ||
                combined.contains("su: not found") ||
                combined.contains("not rooted") -> {
                    DriverErrorInfo(
                        errorType = DriverErrorType.ROOT_DENIED,
                        titleEn = "Root Access Denied",
                        titleBn = "রুট পারমিশন পাওয়া যায়নি",
                        rawError = if (rawError.isNotBlank()) rawError else "Superuser access denied or su missing",
                        descriptionEn = "Kernel Loader requires Superuser (Root) permission to insert and manage driver modules in the Linux kernel.",
                        descriptionBn = "কার্নেলে ড্রাইভার প্রবেশ করানোর জন্য Kernel Loader অ্যাপটির Superuser (Root) পারমিশন প্রয়োজন।",
                        solutionStepsEn = listOf(
                            "Open your root manager app (Magisk, KernelSU, or APatch).",
                            "Go to Superuser permissions and set 'Kernel Loader' to GRANT.",
                            "Return to Kernel Loader and tap LOAD again."
                        ),
                        solutionStepsBn = listOf(
                            "আপনার রুট ম্যানেজার (Magisk, KernelSU, অথবা APatch) অ্যাপ ওপেন করুন।",
                            "Superuser পারমিশন তালিকায় 'Kernel Loader' অ্যাপটিকে Grant (অনুমতি দিন) করুন।",
                            "অ্যাপে ফিরে এসে পুনরায় LOAD বাটনে ট্যাপ করুন।"
                        )
                    )
                }

                // 3. No exact driver in DB
                combined.contains("no exact") ||
                combined.contains("not found in database") ||
                combined.contains("report ready") ||
                combined.contains("no supported") -> {
                    DriverErrorInfo(
                        errorType = DriverErrorType.NO_MATCHING_DRIVER,
                        titleEn = "No Matching Driver in Database",
                        titleBn = "ডাটাবেজে ম্যাচিং ড্রাইভার নেই",
                        rawError = if (rawError.isNotBlank()) rawError else "Exact kernel vermagic driver not found",
                        descriptionEn = "No pre-compiled driver in the database matches your phone's exact kernel build and vermagic.",
                        descriptionBn = "ডাটাবেজে আপনার ডিভাইসের কার্নেল ভার্সন এবং Vermagic-এর সাথে হুবহু মিল থাকা কোনো ড্রাইভার পাওয়া যায়নি।",
                        solutionStepsEn = listOf(
                            "An automated device report pack has been saved to your device storage.",
                            "Send the report pack ZIP or text to the driver builder to build a Pure RT driver for your kernel.",
                            "Once the driver is compiled and added to the database, tap LOAD to automatically download and load it."
                        ),
                        solutionStepsBn = listOf(
                            "অ্যাপটিতে স্বয়ংক্রিয়ভাবে আপনার ডিভাইসের রিপোর্ট ফাইল তৈরি হয়েছে।",
                            "রিপোর্ট ZIP বা টেক্সট ফাইলটি দিয়ে আপনার কার্নেল ভার্সনের জন্য সঠিক Pure RT ড্রাইভার তৈরি করিয়ে নিন।",
                            "ডাটাবেজে ড্রাইভার যুক্ত হলে অ্যাপে LOAD বাটনে ট্যাপ করলেই স্বয়ংক্রিয়ভাবে ডাউনলোড হয়ে লোড হবে।"
                        )
                    )
                }

                // 4. Vermagic mismatch / Exec format error
                combined.contains("exec format error") ||
                combined.contains("invalid module format") ||
                combined.contains("disagrees about version") ||
                combined.contains("vermagic mismatch") -> {
                    DriverErrorInfo(
                        errorType = DriverErrorType.VERMAGIC_MISMATCH,
                        titleEn = "Kernel Vermagic Mismatch",
                        titleBn = "কার্নেল ভার্সন অমিল (Vermagic Mismatch)",
                        rawError = if (rawError.isNotBlank()) rawError else "Exec format error / symbol CRC mismatch",
                        descriptionEn = "The driver module was compiled for a different kernel build or symbol CRC table and cannot be loaded.",
                        descriptionBn = "ড্রাইভারটি আপনার ডিভাইসের কার্নেলের সাথে সিম্বল বা Vermagic-এ অমিল থাকায় কার্নেল এটি লোড করতে পারছে না।",
                        solutionStepsEn = listOf(
                            "Check your running kernel version and vermagic in the Status Console.",
                            "Make sure the driver was built against your phone's exact Module.symvers symbol table.",
                            "Do not use generic or incompatible .ko modules from other phone models."
                        ),
                        solutionStepsBn = listOf(
                            "Status Console থেকে আপনার ডিভাইসের রানিং কার্নেল রিলিজ ও Vermagic চেক করুন।",
                            "আপনার ডিভাইসের নির্দিষ্ট Module.symvers সিম্বল টেবিল দিয়ে ড্রাইভারটি পুনরায় কম্পাইল করতে হবে।",
                            "ভিন্ন মডেলের ড্রাইভার এই ডিভাইসে ব্যবহার করবেন না।"
                        )
                    )
                }

                // 5. General failure
                else -> {
                    DriverErrorInfo(
                        errorType = DriverErrorType.GENERAL,
                        titleEn = "Driver Load Failed",
                        titleBn = "ড্রাইভার লোড ব্যর্থ হয়েছে",
                        rawError = rawError.ifBlank { "Unknown driver insertion error" },
                        descriptionEn = "An error occurred while inserting the driver module into the Linux kernel.",
                        descriptionBn = "কার্নেলে ড্রাইভার মডিউলটি লোড করার সময় অপ্রত্যাশিত ত্রুটি ঘটেছে।",
                        solutionStepsEn = listOf(
                            "Tap 'Copy Log' below to copy full diagnostic details for assistance.",
                            "Reboot your device once and verify that root access is working normally.",
                            "Check the terminal console output for detailed dmesg error logs."
                        ),
                        solutionStepsBn = listOf(
                            "নিচের 'লগ কপি' বাটনে ট্যাপ করে সম্পূর্ণ ডায়াগনস্টিক লগ কপি করে সাহায্য নিন।",
                            "ডিভাইসটি একবার রিস্টার্ট দিয়ে রুট পারমিশন ঠিক আছে কিনা নিশ্চিত করুন।",
                            "কনসোল টার্মিনালে dmesg লগ দেখে বিস্তারিত কারণ যাচাই করুন।"
                        )
                    )
                }
            }
        }
    }
}

