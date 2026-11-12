package com.kernelloader.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kernelloader.security.SecurityShield
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class UiStrings(
    val langCode: String,
    val appName: String = "Kernel Loader",
    val subtitle: String,
    val splashInitializing: String,
    val switchToEnglish: String,
    val rootTitle: String,
    val rootGranted: String,
    val rootDenied: String,
    val rootGrantedSub: String,
    val rootDeniedSub: String,
    val vermagicTitle: String,
    val vermagicDetecting: String,
    val vermagicSub: String,
    val supportedTitle: String,
    val supportedYes: String,
    val supportedNo: String,
    val supportedChecking: String,
    val supportedYesSub: String,
    val supportedNoSub: String,
    val loadBtn: String,
    val loadingBtn: String,
    val stopBtn: String,
    val restartBtn: String,
    val rebootingBtn: String,
    val driverStatusLoaded: String,
    val driverStatusHidden: String,
    val driverStatusNotLoaded: String,
    val driverStatusLoadedHint: String,
    val driverStatusNotLoadedHint: String,
    val verifyBtn: String,
    val verifyingBtn: String,
    val verifyPassText: String,
    val verifyFailText: String,
    val customScriptTitle: String,
    val customScriptSub: String,
    val customScriptSelected: String,
    val customScriptPickBtn: String,
    val customScriptLoadBtn: String,
    val customScriptChangeBtn: String,
    val customScriptClearBtn: String,
    val sigOffTitle: String,
    val sigOffSub: String,
    val sigOffBtn: String,
    val sigOffPatchingBtn: String,
    val statusTerminalTitle: String,
    val statusTerminalHint: String,
    val supportedKernelTerminalTitle: String,
    val supportedKernelTerminalSub: String,
    val thisDeviceBadge: String,
    val scriptBadge: String,
    val driverBadge: String,
    val reportSectionTitle: String,
    val reportSectionSub: String,
    val reportHowToTitle: String,
    val reportStep1: String,
    val reportStep2: String,
    val reportStep3: String,
    val sendTelegramBtn: String,
    val sendWhatsappBtn: String,
    val copyReportBtn: String,
    val collectingReport: String,
    val languageDialogTitle: String,
    val autoIpDetectOption: String
)

object IpLanguageManager {
    private const val PREFS_NAME = "kl_lang_prefs"
    private const val KEY_DETECTED_LANG = "detected_lang"
    private const val KEY_DETECTED_COUNTRY = "detected_country"
    private const val KEY_USER_LANG = "user_lang"

    @Volatile
    private var ipCheckedThisSession = false

    var manualOverrideLang by mutableStateOf<String?>(null)
        private set

    var detectedLangCode by mutableStateOf("en")
        private set

    var detectedCountryCode by mutableStateOf("BD")
        private set

    val activeLangCode: String
        get() = manualOverrideLang ?: detectedLangCode

    val strings: UiStrings
        get() = stringsFor(activeLangCode)

    fun detectedCountryFlag(): String {
        val cc = detectedCountryCode.trim().uppercase(Locale.US)
        return if (cc.length == 2 && cc != "US") countryFlag(cc) else countryFlag("BD")
    }

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedDetected = prefs.getString(KEY_DETECTED_LANG, null)
        val savedCountry = prefs.getString(KEY_DETECTED_COUNTRY, null)
        val savedUserLang = prefs.getString(KEY_USER_LANG, null)
        if (!savedDetected.isNullOrBlank()) detectedLangCode = savedDetected
        if (!savedCountry.isNullOrBlank()) detectedCountryCode = savedCountry
        if (!savedUserLang.isNullOrBlank()) manualOverrideLang = savedUserLang
    }

    fun selectCountryLanguage(context: Context? = null) {
        val cc = detectedCountryCode.trim().uppercase(Locale.US)
        val targetLang = countryToLanguage(cc).takeIf { it != "en" } ?: "bn"
        detectedLangCode = targetLang
        manualOverrideLang = targetLang
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_USER_LANG, targetLang)
            ?.apply()
    }

    fun selectEnglish(context: Context? = null) {
        manualOverrideLang = "en"
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_USER_LANG, "en")
            ?.apply()
    }

    suspend fun detectIpAndLanguage(context: Context) {
        init(context)
        if (ipCheckedThisSession) return
        ipCheckedThisSession = true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasUserChoice = !prefs.getString(KEY_USER_LANG, null).isNullOrBlank()
        val hasSavedDetected = !prefs.getString(KEY_DETECTED_LANG, null).isNullOrBlank()
        if (hasUserChoice && hasSavedDetected) return

        withContext(Dispatchers.IO) {
            try {
                val countryCode = fetchCountryCodeOnly()
                if (countryCode != null) {
                    val cc = countryCode.uppercase(Locale.US)
                    val lang = countryToLanguage(cc)
                    withContext(Dispatchers.Main) {
                        detectedCountryCode = cc
                        if (!hasUserChoice) {
                            detectedLangCode = lang
                        }
                    }
                    prefs.edit()
                        .putString(KEY_DETECTED_COUNTRY, cc)
                        .putString(KEY_DETECTED_LANG, lang)
                        .apply()
                } else if (!hasSavedDetected) {
                    val fallbackCountry = Locale.getDefault().country.uppercase(Locale.US)
                    if (fallbackCountry.isNotBlank()) {
                        val lang = countryToLanguage(fallbackCountry)
                        withContext(Dispatchers.Main) {
                            detectedCountryCode = fallbackCountry
                            if (!hasUserChoice) {
                                detectedLangCode = lang
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun fetchCountryCodeOnly(): String? {
        val endpoints = listOf(
            "https://api.country.is/",
            "https://ipwho.is/",
            "https://ipapi.co/json/",
            "https://freeipapi.com/api/json"
        )
        for (url in endpoints) {
            try {
                val conn = try {
                    SecurityShield.openSecureConnection(url)
                } catch (_: Exception) {
                    URL(url).openConnection() as HttpURLConnection
                }
                conn.connectTimeout = 2800
                conn.readTimeout = 2800
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "KernelLoader/4.9")
                conn.connect()
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val obj = JSONObject(body)
                    val cc = obj.optString("country_code")
                        .ifBlank { obj.optString("countryCode") }
                        .ifBlank { obj.optString("country") }
                        .trim()
                        .uppercase(Locale.US)
                    if (cc.length == 2) {
                        return cc
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun countryToLanguage(countryCode: String): String {
        return when (countryCode.trim().uppercase(Locale.US)) {
            "BD" -> "bn"
            "IN" -> "hi"
            "PK" -> "ur"
            "ID", "MY", "BN" -> "id"
            "SA", "AE", "EG", "IQ", "JO", "KW", "LB", "LY", "MA", "OM", "PS", "QA", "SY", "TN", "YE", "BH", "DZ", "SD" -> "ar"
            "BR", "PT", "AO", "MZ" -> "pt"
            "RU", "BY", "KZ", "KG", "TJ", "UA", "UZ" -> "ru"
            "ES", "MX", "AR", "CO", "CL", "PE", "VE", "EC", "GT", "CU", "BO", "DO", "HN", "PY", "SV", "NI", "CR", "PA", "UY" -> "es"
            "TR", "AZ" -> "tr"
            "VN" -> "vi"
            "CN", "TW", "HK", "MO" -> "zh"
            else -> "en"
        }
    }

    fun countryFlag(countryCode: String): String {
        val cc = countryCode.trim().uppercase(Locale.US)
        if (cc.length != 2 || !cc[0].isLetter() || !cc[1].isLetter()) return "🌐"
        val first = Character.codePointAt(cc, 0) - 0x41 + 0x1F1E6
        val second = Character.codePointAt(cc, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    fun stringsFor(code: String): UiStrings = when (code.lowercase(Locale.US)) {
        "bn" -> BnStrings
        "hi" -> HiStrings
        "ur" -> UrStrings
        "id" -> IdStrings
        "ar" -> ArStrings
        "pt" -> PtStrings
        "ru" -> RuStrings
        "es" -> EsStrings
        "tr" -> TrStrings
        "vi" -> ViStrings
        "zh" -> ZhStrings
        else -> EnStrings
    }

    private val EnStrings = UiStrings(
        langCode = "en",
        subtitle = "PREMIUM KERNEL & SCRIPT ENGINE",
        splashInitializing = "Initializing Kernel Stealth Engine...",
        switchToEnglish = "English",
        rootTitle = "ROOT PERMISSION",
        rootGranted = "PERMISSION GRANTED",
        rootDenied = "PERMISSION NOT GRANTED",
        rootGrantedSub = "Superuser (su) access is active",
        rootDeniedSub = "Grant root permission in Magisk / KernelSU / APatch",
        vermagicTitle = "KERNEL VERMAGIC",
        vermagicDetecting = "Detecting vermagic...",
        vermagicSub = "Running kernel short vermagic signature",
        supportedTitle = "DATABASE SUPPORT",
        supportedYes = "SUPPORTED: YES",
        supportedNo = "SUPPORTED: NO",
        supportedChecking = "CHECKING DATABASE...",
        supportedYesSub = "Matching script / driver available in database",
        supportedNoSub = "No script / driver found for this vermagic — send report below",
        loadBtn = "LOAD",
        loadingBtn = "LOADING",
        stopBtn = "STOP",
        restartBtn = "RESTART",
        rebootingBtn = "REBOOTING",
        driverStatusLoaded = "LOADED & ACTIVE",
        driverStatusHidden = "LOADED IN STEALTH MODE",
        driverStatusNotLoaded = "NOT LOADED",
        driverStatusLoadedHint = "Kernel module/script is active until next reboot",
        driverStatusNotLoadedHint = "Tap the animated LOAD circle button above to load",
        verifyBtn = "VERIFY SCRIPT & DRIVER",
        verifyingBtn = "VERIFYING SCRIPT & DRIVER...",
        verifyPassText = "VERIFIED: SCRIPT & DRIVER WORKING",
        verifyFailText = "VERIFY FAILED: SCRIPT NOT ACTIVE",
        customScriptTitle = "Custom .sh Script Loader",
        customScriptSub = "Select and execute a custom .sh script or .ko driver with root",
        customScriptSelected = "Selected:",
        customScriptPickBtn = "LOAD CUSTOM .SH SCRIPT",
        customScriptLoadBtn = "RUN .SH SCRIPT NOW",
        customScriptChangeBtn = "CHANGE",
        customScriptClearBtn = "CLEAR",
        sigOffTitle = "Kernel Signature Verification",
        sigOffSub = "Disable module signature check (sysctl + boot image patch)",
        sigOffBtn = "SIGNATURE VERIFICATION OFF",
        sigOffPatchingBtn = "DISABLING SIGNATURE VERIFICATION...",
        statusTerminalTitle = "STATUS TERMINAL",
        statusTerminalHint = "Ready — application, script (.sh) & module (.ko) status will appear here",
        supportedKernelTerminalTitle = "SUPPORTED KERNEL TERMINAL",
        supportedKernelTerminalSub = "Uploaded scripts (.sh) & drivers (.ko) in database",
        thisDeviceBadge = "THIS DEVICE",
        scriptBadge = "SCRIPT .sh",
        driverBadge = "DRIVER .ko",
        reportSectionTitle = "SEND KERNEL BUILD REPORT",
        reportSectionSub = "Is your kernel vermagic unsupported or failing to load? Send an automated report pack to get your kernel added.",
        reportHowToTitle = "How to Send a Report (<vermagic>.zip):",
        reportStep1 = "1. Make sure Root permission is granted so device_info.json, config.gz, kallsyms.txt, stock_modules/sample.ko & boot/boot.img + kernel_header.txt can be collected.",
        reportStep2 = "2. Tap either the Telegram or WhatsApp button below — the app will automatically build a ZIP named after your kernel vermagic (e.g. 4.9.337-DaisyForGaming.zip).",
        reportStep3 = "3. When Telegram (@Jubairdadubm) or WhatsApp (+880 1785-917145) opens, simply tap Send to deliver the ZIP to the developer.",
        sendTelegramBtn = "Send via Telegram",
        sendWhatsappBtn = "Send via WhatsApp",
        copyReportBtn = "COPY FULL TEXT REPORT",
        collectingReport = "BUILDING REPORT ZIP...",
        languageDialogTitle = "Select Application Language",
        autoIpDetectOption = "Auto Detect by Country IP"
    )

    private val BnStrings = UiStrings(
        langCode = "bn",
        subtitle = "প্রিমিয়াম কার্নেল ও স্ক্রিপ্ট ইঞ্জিন",
        splashInitializing = "কার্নেল স্টিলথ ইঞ্জিন চালু হচ্ছে...",
        switchToEnglish = "English",
        rootTitle = "রুট পারমিশন",
        rootGranted = "পারমিশন দেওয়া আছে",
        rootDenied = "পারমিশন দেওয়া নেই",
        rootGrantedSub = "সুপারইউজার (Root) পারমিশন সক্রিয় আছে",
        rootDeniedSub = "Magisk / KernelSU / APatch থেকে রুট পারমিশন দিন",
        vermagicTitle = "কার্নেল ভারম্যাজিক (VERMAGIC)",
        vermagicDetecting = "ভারম্যাজিক শনাক্ত করা হচ্ছে...",
        vermagicSub = "ডিভাইসের শর্ট কার্নেল ভারম্যাজিক ভার্সন",
        supportedTitle = "ডাটাবেস সাপোর্ট",
        supportedYes = "SUPPORTED: YES",
        supportedNo = "SUPPORTED: NO",
        supportedChecking = "ডাটাবেস চেক করা হচ্ছে...",
        supportedYesSub = "ডাটাবেসে এই ভারম্যাজিকের স্ক্রিপ্ট / ড্রাইভার পাওয়া গেছে",
        supportedNoSub = "ডাটাবেসে এই ভারম্যাজিকের স্ক্রিপ্ট বা ড্রাইভার নেই — নিচে রিপোর্ট পাঠান",
        loadBtn = "LOAD",
        loadingBtn = "লোড হচ্ছে",
        stopBtn = "বন্ধ করুন",
        restartBtn = "রিস্টার্ট",
        rebootingBtn = "রিবুট হচ্ছে",
        driverStatusLoaded = "লোড হয়েছে ও সক্রিয় আছে",
        driverStatusHidden = "স্টিলথ মোডে লোড হয়েছে",
        driverStatusNotLoaded = "লোড করা হয়নি (NOT LOADED)",
        driverStatusLoadedHint = "ফোন রিস্টার্ট না দেওয়া পর্যন্ত ড্রাইভার/স্ক্রিপ্ট সক্রিয় থাকবে",
        driverStatusNotLoadedHint = "ড্রাইভার বা স্ক্রিপ্ট লোড করতে উপরের অ্যানিমেটেড LOAD বাটনে ট্যাপ করুন",
        verifyBtn = "স্ক্রিপ্ট ও ড্রাইভার ভেরিফাই করুন (VERIFY)",
        verifyingBtn = "স্ক্রিপ্ট ও ড্রাইভার চেক করা হচ্ছে...",
        verifyPassText = "ভেরিফাই সফল: স্ক্রিপ্ট ও ড্রাইভার কাজ করছে",
        verifyFailText = "ভেরিফাই ব্যর্থ: স্ক্রিপ্ট/ড্রাইভার সক্রিয় নেই",
        customScriptTitle = "কাস্টম .sh স্ক্রিপ্ট লোডার",
        customScriptSub = "স্টোরেজ থেকে যেকোনো কাস্টম .sh স্ক্রিপ্ট বা .ko ড্রাইভার সিলেক্ট করে লোড করুন",
        customScriptSelected = "সিলেক্ট করা ফাইল:",
        customScriptPickBtn = "CUSTOM .SH SCRIPT লোড করুন",
        customScriptLoadBtn = ".SH স্ক্রিপ্ট রান / লোড করুন",
        customScriptChangeBtn = "পরিবর্তন",
        customScriptClearBtn = "মুছুন",
        sigOffTitle = "কার্নেল সিগনেচার ভেরিফিকেশন",
        sigOffSub = "মডিউল সিগনেচার চেক বন্ধ করুন (sysctl ও বুট ইমেজ প্যাচ)",
        sigOffBtn = "SIGNATURE VERIFICATION OFF",
        sigOffPatchingBtn = "সিগনেচার ভেরিফিকেশন অফ করা হচ্ছে...",
        statusTerminalTitle = "স্ট্যাটাস টার্মিনাল (STATUS TERMINAL)",
        statusTerminalHint = "প্রস্তুত — অ্যাপ্লিকেশন, স্ক্রিপ্ট (.sh) এবং মডিউল (.ko)-এর সব স্ট্যাটাস এখানে দেখাবে",
        supportedKernelTerminalTitle = "সাপোর্টেড কার্নেল টার্মিনাল (SUPPORTED KERNEL TERMINAL)",
        supportedKernelTerminalSub = "ডাটাবেসে আপলোড করা সকল স্ক্রিপ্ট (.sh) ও ড্রাইভার (.ko) তালিকা",
        thisDeviceBadge = "THIS DEVICE",
        scriptBadge = "SCRIPT .sh",
        driverBadge = "DRIVER .ko",
        reportSectionTitle = "রিপোর্ট পাঠান (SEND REPORT)",
        reportSectionSub = "আপনার কার্নেল ভারম্যাজিক সাপোর্টেড না থাকলে বা লোড হতে সমস্যা হলে নিচের নির্দেশনা মেনে রিপোর্ট পাঠান:",
        reportHowToTitle = "ইউজার যেভাবে রিপোর্ট পাঠাবেন (<vermagic>.zip):",
        reportStep1 = "১. প্রথমে অ্যাপে রুট পারমিশন দেওয়া আছে কিনা নিশ্চিত করুন, যাতে অ্যাপটি স্বয়ংক্রিয়ভাবে device_info.json, config.gz, kallsyms.txt, stock_modules/sample.ko এবং boot/boot.img ও kernel_header.txt সংগ্রহ করতে পারে।",
        reportStep2 = "২. নিচের Telegram অথবা WhatsApp যেকোনো একটি বাটনে ক্লিক করুন — অ্যাপটি স্বয়ংক্রিয়ভাবে আপনার কার্নেলের ভারম্যাজিক নামে (যেমন 4.9.337-DaisyForGaming.zip) জিপ তৈরি করবে।",
        reportStep3 = "৩. Telegram (@Jubairdadubm) বা WhatsApp (+880 1785-917145) ওপেন হলে শুধু Send বাটনে চাপ দিয়ে জিপ ফাইলটি ডেভেলপারের কাছে পাঠিয়ে দিন।",
        sendTelegramBtn = "Telegram-এ রিপোর্ট পাঠান",
        sendWhatsappBtn = "WhatsApp-এ রিপোর্ট পাঠান",
        copyReportBtn = "সম্পূর্ণ টেক্সট রিপোর্ট কপি করুন",
        collectingReport = "রিপোর্ট ZIP তৈরি হচ্ছে...",
        languageDialogTitle = "অ্যাপ্লিকেশনের ভাষা নির্বাচন করুন",
        autoIpDetectOption = "আইপি অনুযায়ী অটোমেটিক ভাষা (Auto IP)"
    )

    private val HiStrings = UiStrings(
        langCode = "hi",
        subtitle = "प्रीमियम कर्नेल और स्क्रिप्ट इंजन",
        splashInitializing = "कर्नेल स्टील्थ इंजन प्रारंभ हो रहा है...",
        switchToEnglish = "English",
        rootTitle = "रूट अनुमति (ROOT PERMISSION)",
        rootGranted = "अनुमति प्राप्त है (GRANTED)",
        rootDenied = "अनुमति नहीं दी गई (NOT GRANTED)",
        rootGrantedSub = "सुपरयूज़र (Root) एक्सेस सक्रिय है",
        rootDeniedSub = "Magisk / KernelSU / APatch में रूट अनुमति दें",
        vermagicTitle = "कर्नेल वर्मैजिक (VERMAGIC)",
        vermagicDetecting = "वर्मैजिक की जांच हो रही है...",
        vermagicSub = "डिवाइस का शॉर्ट कर्नेल वर्मैजिक वर्ज़न",
        supportedTitle = "डेटाबेस सपोर्ट (SUPPORTED)",
        supportedYes = "SUPPORTED: YES",
        supportedNo = "SUPPORTED: NO",
        supportedChecking = "डेटाबेस जांचा जा रहा है...",
        supportedYesSub = "डेटाबेस में इस वर्मैजिक के लिए स्क्रिप्ट / ड्राइवर उपलब्ध है",
        supportedNoSub = "इस वर्मैजिक के लिए कोई स्क्रिप्ट / ड्राइवर नहीं मिला — नीचे रिपोर्ट भेजें",
        loadBtn = "LOAD",
        loadingBtn = "लोड हो रहा है",
        stopBtn = "रोकें",
        restartBtn = "रीस्टार्ट",
        rebootingBtn = "रीबूट हो रहा है",
        driverStatusLoaded = "लोड और सक्रिय",
        driverStatusHidden = "स्टील्थ मोड में सक्रिय",
        driverStatusNotLoaded = "लोड नहीं है (NOT LOADED)",
        driverStatusLoadedHint = "अगले रीबूट तक मॉड्यूल सक्रिय रहेगा",
        driverStatusNotLoadedHint = "ड्राइवर लोड करने के लिए ऊपर LOAD बटन दबाएं",
        verifyBtn = "स्क्रिप्ट और ड्राइवर वेरिफाई करें (VERIFY)",
        verifyingBtn = "स्क्रिप्ट और ड्राइवर जांचा जा रहा है...",
        verifyPassText = "वेरिफाई सफल: स्क्रिप्ट और ड्राइवर काम कर रहे हैं",
        verifyFailText = "वेरिफाई विफल: स्क्रिप्ट/ड्राइवर सक्रिय नहीं है",
        customScriptTitle = "कस्टम .sh स्क्रिप्ट लोडर",
        customScriptSub = "स्टोरेज से अपनी कस्टम .sh स्क्रिप्ट या .ko ड्राइवर चुनें और लोड करें",
        customScriptSelected = "चयनित फ़ाइल:",
        customScriptPickBtn = "CUSTOM .SH SCRIPT लोड करें",
        customScriptLoadBtn = ".SH स्क्रिप्ट अभी चलाएं",
        customScriptChangeBtn = "बदलें",
        customScriptClearBtn = "हटाएं",
        sigOffTitle = "कर्नेल सिग्नेचर वेरिफिकेशन",
        sigOffSub = "मॉड्यूल सिग्नेचर जांच बंद करें (sysctl + बूट पैच)",
        sigOffBtn = "SIGNATURE VERIFICATION OFF",
        sigOffPatchingBtn = "सिग्नेचर वेरिफिकेशन बंद हो रहा है...",
        statusTerminalTitle = "स्टेटस टर्मिनल (STATUS TERMINAL)",
        statusTerminalHint = "तैयार — एप्लिकेशन, स्क्रिप्ट (.sh) और मॉड्यूल (.ko) का स्टेटस यहाँ दिखेगा",
        supportedKernelTerminalTitle = "सपोर्टेड कर्नेल टर्मिनल (SUPPORTED KERNEL TERMINAL)",
        supportedKernelTerminalSub = "डेटाबेस में अपलोड किए गए सभी स्क्रिप्ट (.sh) और ड्राइवर (.ko)",
        thisDeviceBadge = "THIS DEVICE",
        scriptBadge = "SCRIPT .sh",
        driverBadge = "DRIVER .ko",
        reportSectionTitle = "कर्नेल रिपोर्ट भेजें (SEND REPORT)",
        reportSectionSub = "यदि आपका कर्नेल सपोर्टेड नहीं है या लोड विफल होता है, तो नया ड्राइवर बनवाने के लिए रिपोर्ट भेजें:",
        reportHowToTitle = "रिपोर्ट कैसे भेजें (विवरण):",
        reportStep1 = "1. सुनिश्चित करें कि रूट अनुमति दी गई है ताकि boot.img, kallsyms और कर्नेल कॉन्फ़िग एकत्र किए जा सकें।",
        reportStep2 = "2. नीचे दिए गए Telegram या WhatsApp बटन पर क्लिक करें — ऐप स्वतः रिपोर्ट ZIP तैयार करेगा।",
        reportStep3 = "3. Telegram (@Jubairdadubm) या WhatsApp खुलने पर डेवलपर को रिपोर्ट ZIP भेजें।",
        sendTelegramBtn = "Telegram से भेजें",
        sendWhatsappBtn = "WhatsApp से भेजें",
        copyReportBtn = "पूरी रिपोर्ट कॉपी करें",
        collectingReport = "रिपोर्ट तैयार हो रही है...",
        languageDialogTitle = "भाषा चुनें (Select Language)",
        autoIpDetectOption = "IP द्वारा ऑटो भाषा (Auto IP)"
    )

    private val UrStrings = UiStrings(
        langCode = "ur",
        subtitle = "پریمیم کرنل اور اسکرپٹ انجن",
        splashInitializing = "کرنل اسٹیلتھ انجن شروع ہو رہا ہے...",
        switchToEnglish = "English",
        rootTitle = "روٹ پرمیشن (ROOT PERMISSION)",
        rootGranted = "روٹ پرمیشن موجود ہے",
        rootDenied = "روٹ پرمیشن نہیں دی گئی",
        rootGrantedSub = "سپر یوزر (Root) رسائی فعال ہے",
        rootDeniedSub = "Magisk / KernelSU / APatch میں روٹ پرمیشن دیں",
        vermagicTitle = "کرنل ورمایجک (VERMAGIC)",
        vermagicDetecting = "ورمایجک چیک کیا جا رہا ہے...",
        vermagicSub = "شارٹ فارم کرنل ورمایجک ورژن",
        supportedTitle = "ڈیٹا بیس سپورٹ (SUPPORTED)",
        supportedYes = "SUPPORTED: YES",
        supportedNo = "SUPPORTED: NO",
        supportedChecking = "ڈیٹا بیس چیک ہو رہا ہے...",
        supportedYesSub = "اس ورمایجک کے لیے اسکرپٹ / ڈرائیور ڈیٹا بیس میں موجود ہے",
        supportedNoSub = "اس ورمایجک کے لیے کوئی ڈرائیور نہیں ملا — نیچے رپورٹ بھیجیں",
        loadBtn = "LOAD",
        loadingBtn = "لوڈ ہو رہا ہے",
        stopBtn = "روکیں",
        restartBtn = "ری اسٹارٹ",
        rebootingBtn = "ری بوٹ ہو رہا ہے",
        driverStatusLoaded = "لوڈ اور فعال ہے",
        driverStatusHidden = "اسٹیلتھ موڈ میں لوڈ ہے",
        driverStatusNotLoaded = "لوڈ نہیں ہوا (NOT LOADED)",
        driverStatusLoadedHint = "فون ری اسٹارٹ ہونے تک ڈرائیور فعال رہے گا",
        driverStatusNotLoadedHint = "ڈرائیور لوڈ کرنے کے لیے اوپر LOAD بٹن دبائیں",
        verifyBtn = "اسکرپٹ اور ڈرائیور ویریفائی کریں (VERIFY)",
        verifyingBtn = "اسکرپٹ اور ڈرائیور چیک ہو رہا ہے...",
        verifyPassText = "ویریفائی کامیاب: اسکرپٹ اور ڈرائیور کام کر رہے ہیں",
        verifyFailText = "ویریفائی ناکام: اسکرپٹ/ڈرائیور فعال نہیں ہے",
        customScriptTitle = "کسٹم .sh اسکرپٹ لوڈر",
        customScriptSub = "اسٹوریج سے اپنی کسٹم .sh اسکرپٹ یا .ko ڈرائیور منتخب کر کے لوڈ کریں",
        customScriptSelected = "منتخب فائل:",
        customScriptPickBtn = "CUSTOM .SH SCRIPT لوڈ کریں",
        customScriptLoadBtn = ".SH اسکرپٹ چلائیں",
        customScriptChangeBtn = "تبدیل کریں",
        customScriptClearBtn = "صاف کریں",
        sigOffTitle = "کرنل سگنیچر ویریفیکیشن",
        sigOffSub = "ماڈیول سگنیچر ویریفیکیشن آف کریں",
        sigOffBtn = "SIGNATURE VERIFICATION OFF",
        sigOffPatchingBtn = "سگنیچر ویریفیکیشن آف ہو رہا ہے...",
        statusTerminalTitle = "اسٹیٹس ٹرمینل (STATUS TERMINAL)",
        statusTerminalHint = "تیار — ایپلیکیشن، اسکرپٹ اور ماڈیول کا تمام اسٹیٹس یہاں نظر آئے گا",
        supportedKernelTerminalTitle = "سپورٹڈ کرنل ٹرمینل (SUPPORTED KERNEL TERMINAL)",
        supportedKernelTerminalSub = "ڈیٹا بیس میں موجود تمام اسکرپٹس (.sh) اور ڈرائیورز (.ko)",
        thisDeviceBadge = "THIS DEVICE",
        scriptBadge = "SCRIPT .sh",
        driverBadge = "DRIVER .ko",
        reportSectionTitle = "رپورٹ بھیجیں (SEND REPORT)",
        reportSectionSub = "اگر آپ کا کرنل سپورٹڈ نہیں ہے یا لوڈ نہیں ہو رہا تو نیچے دیے گئے طریقے سے رپورٹ بھیجیں:",
        reportHowToTitle = "رپورٹ بھیجنے کا طریقہ:",
        reportStep1 = "1. یقینی بنائیں کہ روٹ پرمیشن دی گئی ہے تاکہ boot.img، kallsyms اور لاگز جمع کیے جا سکیں۔",
        reportStep2 = "2. نیچے دیے گئے Telegram یا WhatsApp بٹن پر کلک کریں — ایپ خودکار طور پر رپورٹ ZIP بنائے گی۔",
        reportStep3 = "3. Telegram (@Jubairdadubm) یا WhatsApp کھلنے پر رپورٹ فائل ڈیولپر کو بھیج دیں۔",
        sendTelegramBtn = "Telegram پر بھیجیں",
        sendWhatsappBtn = "WhatsApp پر بھیجیں",
        copyReportBtn = "مکمل رپورٹ کاپی کریں",
        collectingReport = "رپورٹ تیار ہو رہی ہے...",
        languageDialogTitle = "زبان منتخب کریں",
        autoIpDetectOption = "آئی پی کے مطابق خودکار زبان (Auto IP)"
    )

    private val IdStrings = EnStrings.copy(
        langCode = "id",
        subtitle = "MESIN KERNEL & SKRIP PREMIUM",
        splashInitializing = "Memulai Mesin Stealth Kernel...",
        rootTitle = "IZIN ROOT",
        rootGranted = "IZIN ROOT DIBERIKAN",
        rootDenied = "IZIN ROOT TIDAK DIBERIKAN",
        rootGrantedSub = "Akses Superuser (su) aktif",
        rootDeniedSub = "Berikan izin root di Magisk / KernelSU / APatch",
        vermagicTitle = "VERMAGIC KERNEL",
        vermagicSub = "Versi singkat vermagic kernel perangkat",
        supportedTitle = "DUKUNGAN DATABASE",
        supportedYes = "SUPPORTED: YES",
        supportedNo = "SUPPORTED: NO",
        supportedYesSub = "Skrip / driver yang cocok tersedia di database",
        supportedNoSub = "Tidak ada skrip / driver untuk vermagic ini — kirim laporan di bawah",
        customScriptTitle = "Pemuat Skrip .sh Kustom",
        customScriptSub = "Pilih dan jalankan skrip .sh kustom atau driver .ko dengan root",
        customScriptPickBtn = "MUAT SKRIP .SH KUSTOM",
        customScriptLoadBtn = "JALANKAN SKRIP .SH",
        sigOffTitle = "Verifikasi Tanda Tangan Kernel",
        sigOffSub = "Nonaktifkan pemeriksaan tanda tangan modul kernel",
        sigOffBtn = "SIGNATURE VERIFICATION OFF",
        statusTerminalTitle = "TERMINAL STATUS",
        statusTerminalHint = "Siap — semua status aplikasi, skrip (.sh) & modul (.ko) akan tampil di sini",
        supportedKernelTerminalTitle = "TERMINAL KERNEL DIDUKUNG",
        supportedKernelTerminalSub = "Daftar skrip (.sh) & driver (.ko) di dalam database",
        reportSectionTitle = "KIRIM LAPORAN KERNEL",
        reportSectionSub = "Kernel belum didukung atau gagal dimuat? Kirim paket laporan otomatis ke pengembang:",
        reportHowToTitle = "Cara Mengirim Laporan:",
        reportStep1 = "1. Pastikan izin Root aktif agar boot.img, kallsyms, konfigurasi kernel & log dapat dikumpulkan.",
        reportStep2 = "2. Ketuk tombol Telegram atau WhatsApp di bawah — aplikasi akan membuat file ZIP laporan otomatis.",
        reportStep3 = "3. Saat Telegram (@Jubairdadubm) atau WhatsApp (+880 1785-917145) terbuka, kirimkan ZIP tersebut."
    )

    private val ArStrings = EnStrings.copy(
        langCode = "ar",
        subtitle = "محرك الكيرنل والسكربت الاحترافي",
        splashInitializing = "جاري تشغيل محرك الكيرنل...",
        rootTitle = "صلاحية الروت (ROOT)",
        rootGranted = "تم منح صلاحية الروت",
        rootDenied = "صلاحية الروت غير ممنوحة",
        rootGrantedSub = "صلاحية المسؤول (su) نشطة",
        rootDeniedSub = "يرجى منح صلاحية الروت من Magisk أو KernelSU أو APatch",
        vermagicTitle = "توقيع الكيرنل (VERMAGIC)",
        vermagicSub = "النسخة المختصرة لتوقيع الكيرنل",
        supportedTitle = "دعم قاعدة البيانات",
        supportedYes = "SUPPORTED: YES",
        supportedNo = "SUPPORTED: NO",
        supportedYesSub = "يتوفر سكربت أو تعريف مطابق في قاعدة البيانات",
        supportedNoSub = "لا يوجد سكربت أو تعريف لهذا الكيرنل — أرسل تقريراً بالأسفل",
        customScriptTitle = "تحميل سكربت .sh مخصص",
        customScriptSub = "اختر وشغّل سكربت .sh مخصص أو ملف .ko بصلاحية الروت",
        customScriptPickBtn = "تحميل سكربت .SH مخصص",
        customScriptLoadBtn = "تشغيل سكربت .SH الآن",
        sigOffTitle = "التحقق من توقيع الكيرنل",
        sigOffSub = "إيقاف التحقق من توقيع الوحدات في الكيرنل",
        sigOffBtn = "SIGNATURE VERIFICATION OFF",
        statusTerminalTitle = "تيرمينال الحالة (STATUS TERMINAL)",
        statusTerminalHint = "جاهز — ستظهر جميع حالات التطبيق والسكربت والوحدات هنا",
        supportedKernelTerminalTitle = "تيرمينال الأنوية المدعومة",
        supportedKernelTerminalSub = "جميع السكربتات (.sh) والتعريفات (.ko) المرفوعة في قاعدة البيانات",
        reportSectionTitle = "إرسال تقرير الكيرنل",
        reportSectionSub = "إذا كان الكيرنل غير مدعوم أو فشل التحميل، أرسل تقريراً للمطور لبناء تعريف خاص بجهازك:",
        reportHowToTitle = "طريقة إرسال التقرير:",
        reportStep1 = "1. تأكد من منح صلاحية الروت ليتم استخراج boot.img و kallsyms وإعدادات الكيرنل تلقائياً.",
        reportStep2 = "2. اضغط على زر Telegram أو WhatsApp بالأسفل ليقوم التطبيق بإنشاء ملف التقرير المضغوط ZIP.",
        reportStep3 = "3. عند فتح Telegram (@Jubairdadubm) أو WhatsApp أرسل الملف للمطور."
    )

    private val PtStrings = EnStrings.copy(
        langCode = "pt",
        subtitle = "MOTOR PREMIUM DE KERNEL E SCRIPT",
        splashInitializing = "Inicializando motor stealth do kernel...",
        rootTitle = "PERMISSÃO ROOT",
        rootGranted = "PERMISSÃO CONCEDIDA",
        rootDenied = "PERMISSÃO NÃO CONCEDIDA",
        vermagicTitle = "VERMAGIC DO KERNEL",
        supportedTitle = "SUPORTE NO BANCO DE DADOS",
        supportedYes = "SUPPORTED: YES",
        supportedNo = "SUPPORTED: NO",
        customScriptTitle = "Carregar Script .sh Personalizado",
        customScriptPickBtn = "CARREGAR SCRIPT .SH",
        sigOffTitle = "Verificação de Assinatura do Kernel",
        sigOffBtn = "SIGNATURE VERIFICATION OFF",
        statusTerminalTitle = "TERMINAL DE STATUS",
        supportedKernelTerminalTitle = "TERMINAL DE KERNELS SUPORTADOS",
        reportSectionTitle = "ENVIAR RELATÓRIO DE KERNEL",
        reportHowToTitle = "Como enviar um relatório:"
    )

    private val RuStrings = EnStrings.copy(
        langCode = "ru",
        subtitle = "ПРЕМИУМ ЗАГРУЗЧИК ЯДРА И СКРИПТОВ",
        splashInitializing = "Инициализация стелс-движка ядра...",
        rootTitle = "ROOT ДОСТУП",
        rootGranted = "РАЗРЕШЕНИЕ ПОЛУЧЕНО",
        rootDenied = "НЕТ ROOT РАЗРЕШЕНИЯ",
        vermagicTitle = "VERMAGIC ЯДРА",
        supportedTitle = "ПОДДЕРЖКА В БАЗЕ",
        supportedYes = "SUPPORTED: YES",
        supportedNo = "SUPPORTED: NO",
        customScriptTitle = "Загрузка пользовательского .sh скрипта",
        customScriptPickBtn = "ЗАГРУЗИТЬ .SH СКРИПТ",
        sigOffTitle = "Проверка подписи модулей ядра",
        sigOffBtn = "SIGNATURE VERIFICATION OFF",
        statusTerminalTitle = "ТЕРМИНАЛ СТАТУСА",
        supportedKernelTerminalTitle = "ТЕРМИНАЛ ПОДДЕРЖИВАЕМЫХ ЯДЕР",
        reportSectionTitle = "ОТПРАВИТЬ ОТЧЁТ РАЗРАБОТЧИКУ",
        reportHowToTitle = "Как отправить отчёт:"
    )

    private val EsStrings = EnStrings.copy(
        langCode = "es",
        subtitle = "MOTOR PREMIUM DE KERNEL Y SCRIPTS",
        splashInitializing = "Iniciando motor stealth del kernel...",
        rootTitle = "PERMISO ROOT",
        rootGranted = "PERMISO CONCEDIDO",
        rootDenied = "PERMISO NO CONCEDIDO",
        vermagicTitle = "VERMAGIC DEL KERNEL",
        supportedTitle = "SOPORTE EN BASE DE DATOS",
        supportedYes = "SUPPORTED: YES",
        supportedNo = "SUPPORTED: NO",
        customScriptTitle = "Cargador de Script .sh Personalizado",
        customScriptPickBtn = "CARGAR SCRIPT .SH",
        sigOffTitle = "Verificación de Firma del Kernel",
        sigOffBtn = "SIGNATURE VERIFICATION OFF",
        statusTerminalTitle = "TERMINAL DE ESTADO",
        supportedKernelTerminalTitle = "TERMINAL DE KERNELS SOPORTADOS",
        reportSectionTitle = "ENVIAR REPORTE DE KERNEL",
        reportHowToTitle = "Cómo enviar un reporte:"
    )

    private val TrStrings = EnStrings.copy(
        langCode = "tr",
        subtitle = "PREMİUM KERNEL VE SCRİPT MOTORU",
        splashInitializing = "Kernel Motoru Başlatılıyor...",
        rootTitle = "ROOT İZNİ",
        rootGranted = "ROOT İZNİ VERİLDİ",
        rootDenied = "ROOT İZNİ VERİLMEDİ",
        vermagicTitle = "KERNEL VERMAGIC",
        supportedTitle = "VERİTABANI DESTEĞİ",
        supportedYes = "SUPPORTED: YES",
        supportedNo = "SUPPORTED: NO",
        customScriptTitle = "Özel .sh Script Yükleyici",
        customScriptPickBtn = "ÖZEL .SH SCRİPT YÜKLE",
        sigOffTitle = "Kernel İmza Doğrulaması",
        sigOffBtn = "SIGNATURE VERIFICATION OFF",
        statusTerminalTitle = "DURUM TERMİNALİ",
        supportedKernelTerminalTitle = "DESTEKLENEN KERNEL TERMİNALİ",
        reportSectionTitle = "KERNEL RAPORU GÖNDER",
        reportHowToTitle = "Rapor Nasıl Gönderilir:"
    )

    private val ViStrings = EnStrings.copy(
        langCode = "vi",
        subtitle = "CÔNG CỤ NẠP KERNEL & SCRIPT CAO CẤP",
        splashInitializing = "Đang khởi tạo công cụ Kernel...",
        rootTitle = "QUYỀN ROOT",
        rootGranted = "ĐÃ CẤP QUYỀN ROOT",
        rootDenied = "CHƯA CẤP QUYỀN ROOT",
        vermagicTitle = "VERMAGIC KERNEL",
        supportedTitle = "HỖ TRỢ TRONG CƠ SỞ DỮ LIỆU",
        supportedYes = "SUPPORTED: YES",
        supportedNo = "SUPPORTED: NO",
        customScriptTitle = "Nạp Script .sh Tùy Chỉnh",
        customScriptPickBtn = "TẢI SCRIPT .SH TÙY CHỈNH",
        sigOffTitle = "Xác Minh Chữ Ký Kernel",
        sigOffBtn = "SIGNATURE VERIFICATION OFF",
        statusTerminalTitle = "TERMINAL TRẠNG THÁI",
        supportedKernelTerminalTitle = "TERMINAL KERNEL ĐƯỢC HỖ TRỢ",
        reportSectionTitle = "GỬI BÁO CÁO KERNEL",
        reportHowToTitle = "Hướng dẫn gửi báo cáo:"
    )

    private val ZhStrings = EnStrings.copy(
        langCode = "zh",
        subtitle = "高级内核模块与脚本加载引擎",
        splashInitializing = "正在初始化内核隐蔽引擎...",
        rootTitle = "ROOT 权限状态",
        rootGranted = "已授予 ROOT 权限",
        rootDenied = "未授予 ROOT 权限",
        vermagicTitle = "内核 VERMAGIC",
        supportedTitle = "数据库支持状态",
        supportedYes = "SUPPORTED: YES",
        supportedNo = "SUPPORTED: NO",
        customScriptTitle = "自定义 .sh 脚本加载器",
        customScriptPickBtn = "加载自定义 .SH 脚本",
        sigOffTitle = "内核签名验证关闭",
        sigOffBtn = "SIGNATURE VERIFICATION OFF",
        statusTerminalTitle = "状态终端 (STATUS TERMINAL)",
        supportedKernelTerminalTitle = "支持的内核终端 (SUPPORTED KERNELS)",
        reportSectionTitle = "发送内核构建报告",
        reportHowToTitle = "如何发送报告："
    )
}
