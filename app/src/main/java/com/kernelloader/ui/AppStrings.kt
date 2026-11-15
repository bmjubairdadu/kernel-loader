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
    val sigOffTitle: String = "",
    val sigOffSub: String = "",
    val sigOffBtn: String = "",
    val sigOffPatchingBtn: String = "",
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
    val autoIpDetectOption: String,
    val checkKernelTitle: String = "Kernel Driver Compatibility Audit",
    val checkKernelSub: String = "Scan kernel barriers, signatures & devtmpfs configuration",
    val checkKernelBtn: String = "CHECK KERNEL FOR DRIVER",
    val checkKernelCheckingBtn: String = "AUDITING KERNEL...",
    val checkKernelPassBanner: String = "KERNEL READY: ALL CHECKS PASSED",
    val checkKernelIssuesBanner: String = "ISSUES DETECTED: SEE TERMINAL FOR FIXES"
)

data class LanguageItem(
    val code: String,
    val name: String,
    val nativeName: String,
    val flag: String,
    val defaultCountry: String
)

object IpLanguageManager {
    private const val PREFS_NAME = "kl_lang_prefs"
    private const val KEY_DETECTED_LANG = "detected_lang"
    private const val KEY_DETECTED_COUNTRY = "detected_country"
    private const val KEY_USER_LANG = "user_lang"

    val SupportedLanguages = listOf(
        LanguageItem("en", "English", "English", "🇺🇸", "US"),
        LanguageItem("bn", "Bengali", "বাংলা", "🇧🇩", "BD"),
        LanguageItem("hi", "Hindi", "हिन्दी", "🇮🇳", "IN"),
        LanguageItem("ur", "Urdu", "اردو", "🇵🇰", "PK"),
        LanguageItem("id", "Indonesian", "Bahasa Indonesia", "🇮🇩", "ID"),
        LanguageItem("ms", "Malay", "Bahasa Melayu", "🇲🇾", "MY"),
        LanguageItem("ar", "Arabic", "العربية", "🇸🇦", "SA"),
        LanguageItem("fa", "Persian", "فارسی", "🇮🇷", "IR"),
        LanguageItem("pt", "Portuguese", "Português", "🇧🇷", "BR"),
        LanguageItem("ru", "Russian", "Русский", "🇷🇺", "RU"),
        LanguageItem("uk", "Ukrainian", "Українська", "🇺🇦", "UA"),
        LanguageItem("es", "Spanish", "Español", "🇪🇸", "ES"),
        LanguageItem("fr", "French", "Français", "🇫🇷", "FR"),
        LanguageItem("de", "German", "Deutsch", "🇩🇪", "DE"),
        LanguageItem("it", "Italian", "Italiano", "🇮🇹", "IT"),
        LanguageItem("tr", "Turkish", "Türkçe", "🇹🇷", "TR"),
        LanguageItem("vi", "Vietnamese", "Tiếng Việt", "🇻🇳", "VN"),
        LanguageItem("th", "Thai", "ภาษาไทย", "🇹🇭", "TH"),
        LanguageItem("fil", "Filipino", "Tagalog", "🇵🇭", "PH"),
        LanguageItem("zh", "Chinese", "简体中文", "🇨🇳", "CN"),
        LanguageItem("ja", "Japanese", "日本語", "🇯🇵", "JP"),
        LanguageItem("ko", "Korean", "한국어", "🇰🇷", "KR"),
        LanguageItem("pl", "Polish", "Polski", "🇵🇱", "PL"),
        LanguageItem("ne", "Nepali", "नेपाली", "🇳🇵", "NP"),
        LanguageItem("my", "Burmese", "မြန်မာဘာသာ", "🇲🇲", "MM"),
        LanguageItem("km", "Khmer", "ភាសាខ្មែរ", "🇰🇭", "KH"),
        LanguageItem("si", "Sinhala", "සිංහල", "🇱🇰", "LK")
    )

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
        val active = activeLangCode
        if (active != "en") {
            val found = SupportedLanguages.firstOrNull { it.code == active }
            if (found != null) return found.flag
        }
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

    fun selectLanguage(context: Context? = null, langCode: String) {
        if (langCode == "en") {
            selectEnglish(context)
            return
        }
        val target = SupportedLanguages.firstOrNull { it.code == langCode }?.code ?: "en"
        detectedLangCode = target
        manualOverrideLang = target
        val item = SupportedLanguages.firstOrNull { it.code == target }
        if (item != null) {
            detectedCountryCode = item.defaultCountry
        }
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_USER_LANG, target)
            ?.putString(KEY_DETECTED_LANG, target)
            ?.putString(KEY_DETECTED_COUNTRY, detectedCountryCode)
            ?.apply()
    }

    fun selectAutoIp(context: Context? = null) {
        manualOverrideLang = null
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove(KEY_USER_LANG)
            ?.apply()
        val cc = detectedCountryCode.trim().uppercase(Locale.US)
        val targetLang = countryToLanguage(cc)
        detectedLangCode = targetLang
    }

    fun selectCountryLanguage(context: Context? = null) {
        val cc = detectedCountryCode.trim().uppercase(Locale.US)
        val targetLang = countryToLanguage(cc).takeIf { it != "en" } ?: "bn"
        selectLanguage(context, targetLang)
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
            "ID" -> "id"
            "MY", "BN", "SG" -> "ms"
            "SA", "AE", "EG", "IQ", "JO", "KW", "LB", "LY", "MA", "OM", "PS", "QA", "SY", "TN", "YE", "BH", "DZ", "SD" -> "ar"
            "IR", "AF" -> "fa"
            "BR", "PT", "AO", "MZ" -> "pt"
            "RU", "BY", "KZ", "KG", "TJ", "UZ" -> "ru"
            "UA" -> "uk"
            "ES", "MX", "AR", "CO", "CL", "PE", "VE", "EC", "GT", "CU", "BO", "DO", "HN", "PY", "SV", "NI", "CR", "PA", "UY" -> "es"
            "FR", "BE", "CA", "SN", "CI", "CM", "CD", "MG", "NE", "BF", "ML" -> "fr"
            "DE", "AT", "LI" -> "de"
            "IT", "SM", "VA" -> "it"
            "TR", "AZ" -> "tr"
            "VN" -> "vi"
            "TH" -> "th"
            "PH" -> "fil"
            "CN", "TW", "HK", "MO" -> "zh"
            "JP" -> "ja"
            "KR" -> "ko"
            "PL" -> "pl"
            "NP" -> "ne"
            "MM" -> "my"
            "KH" -> "km"
            "LK" -> "si"
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
        "ms" -> MsStrings
        "ar" -> ArStrings
        "fa" -> FaStrings
        "pt" -> PtStrings
        "ru" -> RuStrings
        "uk" -> UkStrings
        "es" -> EsStrings
        "fr" -> FrStrings
        "de" -> DeStrings
        "it" -> ItStrings
        "tr" -> TrStrings
        "vi" -> ViStrings
        "th" -> ThStrings
        "fil" -> FilStrings
        "zh" -> ZhStrings
        "ja" -> JaStrings
        "ko" -> KoStrings
        "pl" -> PlStrings
        "ne" -> NeStrings
        "my" -> MyStrings
        "km" -> KmStrings
        "si" -> SiStrings
        else -> EnStrings
    }

    private val EnStrings = UiStrings(
        langCode = "en",
        subtitle = "PREMIUM KERNEL & SCRIPT ENGINE",
        splashInitializing = "Initializing Kernel Stealth Engine...",
        switchToEnglish = "English",
        rootTitle = "ROOT PERMISSION",
        rootGranted = "GRANTED",
        rootDenied = "DENIED",
        rootGrantedSub = "Superuser (su) access is active",
        rootDeniedSub = "Grant root permission in Magisk / KernelSU / APatch",
        vermagicTitle = "KERNEL VERMAGIC",
        vermagicDetecting = "Detecting vermagic...",
        vermagicSub = "Running kernel short vermagic signature",
        supportedTitle = "DATABASE",
        supportedYes = "SUPPORTED",
        supportedNo = "UNSUPPORTED",
        supportedChecking = "CHECKING...",
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
        verifyBtn = "VERIFY DRIVER",
        verifyingBtn = "VERIFYING DRIVER...",
        verifyPassText = "VERIFIED: DRIVER ACTIVE",
        verifyFailText = "NOT ACTIVE: DRIVER NOT LOADED",
        customScriptTitle = "Custom .sh Script Loader",
        customScriptSub = "Select and execute a custom .sh script or .ko driver with root",
        customScriptSelected = "Selected:",
        customScriptPickBtn = "LOAD CUSTOM .SH SCRIPT",
        customScriptLoadBtn = "RUN .SH SCRIPT NOW",
        customScriptChangeBtn = "CHANGE",
        customScriptClearBtn = "CLEAR",
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
        autoIpDetectOption = "Auto Detect by Country IP",
        checkKernelTitle = "Kernel Driver Compatibility Audit",
        checkKernelSub = "Scan 9+ kernel barriers, signatures & devtmpfs configuration",
        checkKernelBtn = "CHECK KERNEL FOR DRIVER",
        checkKernelCheckingBtn = "AUDITING KERNEL...",
        checkKernelPassBanner = "KERNEL READY: ALL CHECKS PASSED",
        checkKernelIssuesBanner = "ISSUES DETECTED: SEE TERMINAL FOR FIXES"
    )

    private val BnStrings = UiStrings(
        langCode = "bn",
        subtitle = "প্রিমিয়াম কার্নেল ও স্ক্রিপ্ট ইঞ্জিন",
        splashInitializing = "কার্নেল স্টিলথ ইঞ্জিন চালু হচ্ছে...",
        switchToEnglish = "English",
        rootTitle = "রুট পারমিশন",
        rootGranted = "অনুমোদিত",
        rootDenied = "অনুমতি নেই",
        rootGrantedSub = "সুপারইউজার (Root) পারমিশন সক্রিয় আছে",
        rootDeniedSub = "Magisk / KernelSU / APatch থেকে রুট পারমিশন দিন",
        vermagicTitle = "কার্নেল ভারম্যাজিক",
        vermagicDetecting = "ভারম্যাজিক শনাক্ত করা হচ্ছে...",
        vermagicSub = "ডিভাইসের শর্ট কার্নেল ভারম্যাজিক ভার্সন",
        supportedTitle = "ডাটাবেস",
        supportedYes = "সাপোর্টেড",
        supportedNo = "সাপোর্ট নেই",
        supportedChecking = "চেক হচ্ছে...",
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
        verifyBtn = "ড্রাইভার ভেরিফাই করুন",
        verifyingBtn = "ড্রাইভার ভেরিফাই হচ্ছে...",
        verifyPassText = "ভেরিফাই সফল: ড্রাইভার সক্রিয় আছে",
        verifyFailText = "নিষ্ক্রিয়: ড্রাইভার লোড নেই",
        customScriptTitle = "কাস্টম .sh স্ক্রিপ্ট লোডার",
        customScriptSub = "স্টোরেজ থেকে যেকোনো কাস্টম .sh স্ক্রিপ্ট বা .ko ড্রাইভার সিলেক্ট করে লোড করুন",
        customScriptSelected = "সিলেক্ট করা ফাইল:",
        customScriptPickBtn = "CUSTOM .SH SCRIPT লোড করুন",
        customScriptLoadBtn = ".SH স্ক্রিপ্ট রান / লোড করুন",
        customScriptChangeBtn = "পরিবর্তন",
        customScriptClearBtn = "মুছুন",
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
        autoIpDetectOption = "আইপি অনুযায়ী অটোমেটিক ভাষা (Auto IP)",
        checkKernelTitle = "কার্নেল ড্রাইভার কম্প্যাটিবিলিটি অডিট",
        checkKernelSub = "ড্রাইভার লোড না হওয়ার কারণ, সিগনেচার ও কার্নেল ব্যারিয়ার স্ক্যান",
        checkKernelBtn = "CHECK KERNEL FOR DRIVER",
        checkKernelCheckingBtn = "কার্নেল অডিট করা হচ্ছে...",
        checkKernelPassBanner = "কার্নেল ড্রাইভার লোডের জন্য সম্পূর্ণ প্রস্তুত",
        checkKernelIssuesBanner = "সমস্যা পাওয়া গেছে: সমাধানের জন্য নিচের টার্মিনাল দেখুন"
    )

    private val HiStrings = UiStrings(
        langCode = "hi",
        subtitle = "प्रीमियम कर्नेल और स्क्रिप्ट इंजन",
        splashInitializing = "कर्नेल स्टील्थ इंजन प्रारंभ हो रहा है...",
        switchToEnglish = "English",
        rootTitle = "रूट अनुमति",
        rootGranted = "प्राप्त है",
        rootDenied = "अनुमति नहीं",
        rootGrantedSub = "सुपरयूज़र (Root) एक्सेस सक्रिय है",
        rootDeniedSub = "Magisk / KernelSU / APatch में रूट अनुमति दें",
        vermagicTitle = "कर्नेल वर्मैजिक",
        vermagicDetecting = "वर्मैजिक की जांच हो रही है...",
        vermagicSub = "डिवाइस का शॉर्ट कर्नेल वर्मैजिक वर्ज़न",
        supportedTitle = "डेटाबेस",
        supportedYes = "सपोर्टेड",
        supportedNo = "उपलब्ध नहीं",
        supportedChecking = "जाँच जारी...",
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
        verifyBtn = "ड्राइवर और स्क्रिप्ट वेरिफाई",
        verifyingBtn = "ड्राइवर जांचा जा रहा है...",
        verifyPassText = "वेरिफाई सफल: स्क्रिप्ट और ड्राइवर काम कर रहे हैं",
        verifyFailText = "वेरिफाई विफल: स्क्रिप्ट/ड्राइवर सक्रिय नहीं है",
        customScriptTitle = "कस्टम .sh स्क्रिप्ट लोडर",
        customScriptSub = "स्टोरेज से अपनी कस्टम .sh स्क्रिप्ट या .ko ड्राइवर चुनें और लोड करें",
        customScriptSelected = "चयनित फ़ाइल:",
        customScriptPickBtn = "CUSTOM .SH SCRIPT लोड करें",
        customScriptLoadBtn = ".SH स्क्रिप्ट अभी चलाएं",
        customScriptChangeBtn = "बदलें",
        customScriptClearBtn = "हटाएं",
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
        rootTitle = "روٹ پرمیشن",
        rootGranted = "موجود ہے",
        rootDenied = "غیر دستیاب",
        rootGrantedSub = "سپر یوزر (Root) رسائی فعال ہے",
        rootDeniedSub = "Magisk / KernelSU / APatch میں روٹ پرمیشن دیں",
        vermagicTitle = "کرنل ورمایجک",
        vermagicDetecting = "ورمایجک چیک کیا جا رہا ہے...",
        vermagicSub = "شارٹ فارم کرنل ورمایجک ورژن",
        supportedTitle = "ڈیٹا بیس",
        supportedYes = "سپورٹڈ",
        supportedNo = "دستیاب نہیں",
        supportedChecking = "جانچ جاری...",
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
        verifyBtn = "ڈرائیور اور اسکرپٹ ویریفائی",
        verifyingBtn = "ڈرائیور چیک ہو رہا ہے...",
        verifyPassText = "ویریفائی کامیاب: اسکرپٹ اور ڈرائیور کام کر رہے ہیں",
        verifyFailText = "ویریفائی ناکام: اسکرپٹ/ڈرائیور فعال نہیں ہے",
        customScriptTitle = "کسٹم .sh اسکرپٹ لوڈر",
        customScriptSub = "اسٹوریج سے اپنی کسٹم .sh اسکرپٹ یا .ko ڈرائیور منتخب کر کے لوڈ کریں",
        customScriptSelected = "منتخب فائل:",
        customScriptPickBtn = "CUSTOM .SH SCRIPT لوڈ کریں",
        customScriptLoadBtn = ".SH اسکرپٹ چلائیں",
        customScriptChangeBtn = "تبدیل کریں",
        customScriptClearBtn = "صاف کریں",
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
        supportedChecking = "MEMERIKSA DATABASE...",
        supportedYesSub = "Skrip / driver yang cocok tersedia di database",
        supportedNoSub = "Tidak ada skrip / driver untuk vermagic ini — kirim laporan di bawah",
        loadBtn = "LOAD",
        loadingBtn = "MEMUAT",
        stopBtn = "BERHENTI",
        restartBtn = "RESTART",
        rebootingBtn = "MEMULAI ULANG",
        driverStatusLoaded = "DIMUAT & AKTIF",
        driverStatusHidden = "DIMUAT DALAM MODE STEALTH",
        driverStatusNotLoaded = "BELUM DIMUAT",
        driverStatusLoadedHint = "Modul/skrip aktif hingga perangkat dimulai ulang berikutnya",
        driverStatusNotLoadedHint = "Ketuk tombol LOAD di atas untuk memuat",
        verifyBtn = "VERIFIKASI SKRIP & DRIVER",
        verifyingBtn = "MEMVERIFIKASI DRIVER...",
        verifyPassText = "TERVERIFIKASI: DRIVER BERFUNGSI",
        verifyFailText = "GAGAL: DRIVER TIDAK AKTIF",
        customScriptTitle = "Pemuat Skrip .sh Kustom",
        customScriptSub = "Pilih dan jalankan skrip .sh kustom atau driver .ko dengan root",
        customScriptPickBtn = "MUAT SKRIP .SH KUSTOM",
        customScriptLoadBtn = "JALANKAN SKRIP .SH",
        customScriptChangeBtn = "UBAH",
        customScriptClearBtn = "HAPUS",
        statusTerminalTitle = "TERMINAL STATUS",
        statusTerminalHint = "Siap — semua status aplikasi, skrip (.sh) & modul (.ko) akan tampil di sini",
        supportedKernelTerminalTitle = "TERMINAL KERNEL DIDUKUNG",
        supportedKernelTerminalSub = "Daftar skrip (.sh) & driver (.ko) di dalam database",
        reportSectionTitle = "KIRIM LAPORAN KERNEL",
        sendTelegramBtn = "Kirim lewat Telegram",
        sendWhatsappBtn = "Kirim lewat WhatsApp",
        copyReportBtn = "SALIN LAPORAN TEKS",
        collectingReport = "MEMBUAT LAPORAN ZIP...",
        languageDialogTitle = "Pilih Bahasa Aplikasi",
        autoIpDetectOption = "Deteksi Otomatis Berdasarkan IP Negara"
    )

    private val MsStrings = EnStrings.copy(
        langCode = "ms",
        subtitle = "ENJIN KERNEL & SKRIP PREMIUM",
        splashInitializing = "Memulakan Enjin Stealth Kernel...",
        rootTitle = "KEBENARAN ROOT",
        rootGranted = "KEBENARAN DIBERIKAN",
        rootDenied = "TIADA KEBENARAN ROOT",
        rootGrantedSub = "Akses Superuser (su) aktif",
        rootDeniedSub = "Berikan kebenaran root dalam Magisk / KernelSU / APatch",
        vermagicTitle = "VERMAGIC KERNEL",
        vermagicSub = "Versi vermagic ringkas kernel peranti",
        supportedTitle = "SOKONGAN PANGKALAN DATA",
        supportedYes = "DISOKONG: YA",
        supportedNo = "DISOKONG: TIDAK",
        supportedChecking = "MEMERIKSA PANGKALAN DATA...",
        supportedYesSub = "Skrip / pemandu yang sepadan ada dalam pangkalan data",
        supportedNoSub = "Tiada pemandu ditemui — hantar laporan di bawah",
        loadBtn = "LOAD",
        loadingBtn = "MEMUATKAN",
        stopBtn = "HENTI",
        restartBtn = "MULAKAN SEMULA",
        rebootingBtn = "SEDANG BUT SEMULA",
        driverStatusLoaded = "DIMUATKAN & AKTIF",
        driverStatusHidden = "DIMUATKAN DALAM MOD STEALTH",
        driverStatusNotLoaded = "BELUM DIMUATKAN",
        driverStatusLoadedHint = "Pemandu aktif sehingga but semula peranti seterusnya",
        driverStatusNotLoadedHint = "Ketik butang LOAD di atas untuk memuatkan",
        verifyBtn = "SAHKAN SKRIP & PEMANDU",
        verifyingBtn = "MENGESAHKAN PEMANDU...",
        verifyPassText = "DISAHKAN: PEMANDU BERFUNGSI",
        verifyFailText = "GAGAL: PEMANDU TIDAK AKTIF",
        customScriptTitle = "Pemuat Skrip .sh Tersuai",
        customScriptSub = "Pilih dan jalankan skrip .sh atau pemandu .ko dengan root",
        customScriptPickBtn = "MUATKAN SKRIP .SH",
        customScriptLoadBtn = "JALANKAN SKRIP .SH SEKARANG",
        customScriptChangeBtn = "TUKAR",
        customScriptClearBtn = "PADAM",
        statusTerminalTitle = "TERMINAL STATUS",
        statusTerminalHint = "Sedia — semua status aplikasi dan pemandu akan dipaparkan di sini",
        supportedKernelTerminalTitle = "TERMINAL KERNEL DISOKONG",
        supportedKernelTerminalSub = "Senarai skrip dan pemandu dalam pangkalan data",
        reportSectionTitle = "HANTAR LAPORAN KERNEL",
        sendTelegramBtn = "Hantar melalui Telegram",
        sendWhatsappBtn = "Hantar melalui WhatsApp",
        copyReportBtn = "SALIN LAPORAN TEKS",
        collectingReport = "MEMBINA LAPORAN ZIP...",
        languageDialogTitle = "Pilih Bahasa Aplikasi",
        autoIpDetectOption = "Kesan Automatik Mengikut IP Negara"
    )

    private val ArStrings = EnStrings.copy(
        langCode = "ar",
        subtitle = "محرك الكيرنل والسكربت الاحترافي",
        splashInitializing = "جاري تشغيل محرك الكيرنل...",
        rootTitle = "صلاحية الروت",
        rootGranted = "ممنوحة",
        rootDenied = "غير ممنوحة",
        rootGrantedSub = "صلاحية المسؤول (su) نشطة",
        rootDeniedSub = "يرجى منح صلاحية الروت من Magisk أو KernelSU أو APatch",
        vermagicTitle = "توقيع الكيرنل",
        vermagicSub = "النسخة المختصرة لتوقيع الكيرنل",
        supportedTitle = "قاعدة البيانات",
        supportedYes = "مدعوم",
        supportedNo = "غير مدعوم",
        supportedChecking = "جاري فحص قاعدة البيانات...",
        supportedYesSub = "يتوفر سكربت أو تعريف مطابق في قاعدة البيانات",
        supportedNoSub = "لا يوجد سكربت أو تعريف لهذا الكيرنل — أرسل تقريراً بالأسفل",
        loadBtn = "LOAD",
        loadingBtn = "جاري التحميل",
        stopBtn = "إيقاف",
        restartBtn = "إعادة تشغيل",
        rebootingBtn = "جاري إعادة التشغيل",
        driverStatusLoaded = "تم التحميل ونشط",
        driverStatusHidden = "تم التحميل في الوضع المخفي",
        driverStatusNotLoaded = "لم يتم التحميل",
        driverStatusLoadedHint = "التعريف يظل نشطاً حتى إعادة تشغيل الهاتف التالية",
        driverStatusNotLoadedHint = "اضغط على زر LOAD بالأعلى للتحميل",
        verifyBtn = "التحقق من السكربت والتعريف",
        verifyingBtn = "جاري التحقق...",
        verifyPassText = "تم التحقق: التعريف يعمل بشكل سليم",
        verifyFailText = "فشل التحقق: التعريف غير نشط",
        customScriptTitle = "تحميل سكربت .sh مخصص",
        customScriptSub = "اختر وشغّل سكربت .sh مخصص أو ملف .ko بصلاحية الروت",
        customScriptPickBtn = "تحميل سكربت .SH مخصص",
        customScriptLoadBtn = "تشغيل سكربت .SH الآن",
        customScriptChangeBtn = "تعديل",
        customScriptClearBtn = "مسح",
        statusTerminalTitle = "تيرمينال الحالة (STATUS TERMINAL)",
        statusTerminalHint = "جاهز — ستظهر جميع حالات التطبيق والسكربت والوحدات هنا",
        supportedKernelTerminalTitle = "تيرمينال الأنوية المدعومة",
        supportedKernelTerminalSub = "جميع السكربتات (.sh) والتعريفات (.ko) المرفوعة في قاعدة البيانات",
        reportSectionTitle = "إرسال تقرير الكيرنل",
        sendTelegramBtn = "إرسال عبر تليجرام",
        sendWhatsappBtn = "إرسال عبر واتساب",
        copyReportBtn = "نسخ التقرير كاملاً",
        collectingReport = "جاري إنشاء ملف ZIP للتقرير...",
        languageDialogTitle = "اختر لغة التطبيق",
        autoIpDetectOption = "الكشف التلقائي حسب دولة IP"
    )

    private val FaStrings = EnStrings.copy(
        langCode = "fa",
        subtitle = "موتور حرفه‌ای کرنل و اسکریپت",
        splashInitializing = "در حال راه‌اندازی موتور کرنل...",
        rootTitle = "دسترسی روت",
        rootGranted = "تأیید شد",
        rootDenied = "رد شد",
        rootGrantedSub = "دسترسی Superuser (su) فعال است",
        rootDeniedSub = "دسترسی روت را در Magisk / KernelSU / APatch فعال کنید",
        vermagicTitle = "ورمیک کرنل",
        vermagicSub = "امضای کوتاه ورمیک کرنل دستگاه",
        supportedTitle = "دیتابیس",
        supportedYes = "پشتیبانی شد",
        supportedNo = "پشتیبانی نشد",
        supportedChecking = "در حال بررسی دیتابیس...",
        supportedYesSub = "اسکریپت و درایور منطبق در دیتابیس موجود است",
        supportedNoSub = "درایوری برای این کرنل یافت نشد — گزارش ارسال کنید",
        loadBtn = "LOAD",
        loadingBtn = "در حال بارگذاری",
        stopBtn = "توقف",
        restartBtn = "راه‌اندازی مجدد",
        rebootingBtn = "در حال ریبوت",
        driverStatusLoaded = "بارگذاری‌شده و فعال",
        driverStatusHidden = "بارگذاری‌شده در حالت مخفی",
        driverStatusNotLoaded = "بارگذاری نشده",
        driverStatusLoadedHint = "درایور تا ریبوت بعدی فعال می‌ماند",
        driverStatusNotLoadedHint = "دکمه دایره‌ای LOAD بالا را لمس کنید",
        verifyBtn = "بررسی اسکریپت و درایور",
        verifyingBtn = "در حال بررسی...",
        verifyPassText = "تأیید شد: درایور به درستی کار می‌کند",
        verifyFailText = "خطا: درایور فعال نیست",
        customScriptTitle = "لودر اسکریپت سفارشی .sh",
        customScriptSub = "انتخاب و اجرای اسکریپت .sh یا درایور .ko با روت",
        customScriptPickBtn = "انتخاب اسکریپت .SH",
        customScriptLoadBtn = "اجرای فوری اسکریپت .SH",
        customScriptChangeBtn = "تغییر",
        customScriptClearBtn = "پاک کردن",
        statusTerminalTitle = "ترمینال وضعیت",
        statusTerminalHint = "آماده — وضعیت برنامه و درایورها اینجا نمایش داده می‌شود",
        supportedKernelTerminalTitle = "کرنل‌های پشتیبانی‌شده",
        supportedKernelTerminalSub = "لیست تمام اسکریپت‌ها و درایورها در دیتابیس",
        reportSectionTitle = "ارسال گزارش کرنل",
        sendTelegramBtn = "ارسال با تلگرام",
        sendWhatsappBtn = "ارسال با واتساپ",
        copyReportBtn = "کپی متن گزارش",
        collectingReport = "در حال ساخت ZIP گزارش...",
        languageDialogTitle = "انتخاب زبان برنامه",
        autoIpDetectOption = "تشخیص خودکار بر اساس IP"
    )

    private val PtStrings = EnStrings.copy(
        langCode = "pt",
        subtitle = "MOTOR PREMIUM DE KERNEL E SCRIPT",
        splashInitializing = "Inicializando motor stealth do kernel...",
        rootTitle = "PERMISSÃO ROOT",
        rootGranted = "PERMISSÃO CONCEDIDA",
        rootDenied = "PERMISSÃO NÃO CONCEDIDA",
        rootGrantedSub = "Acesso Superuser (su) ativo",
        rootDeniedSub = "Conceda permissão root no Magisk / KernelSU / APatch",
        vermagicTitle = "VERMAGIC DO KERNEL",
        vermagicSub = "Assinatura curta vermagic do kernel do aparelho",
        supportedTitle = "SUPORTE NO BANCO DE DADOS",
        supportedYes = "SUPORTADO: SIM",
        supportedNo = "SUPORTADO: NÃO",
        supportedChecking = "VERIFICANDO BANCO DE DADOS...",
        supportedYesSub = "Driver ou script compatível disponível",
        supportedNoSub = "Nenhum driver encontrado — envie um relatório abaixo",
        loadBtn = "LOAD",
        loadingBtn = "CARREGANDO",
        stopBtn = "PARAR",
        restartBtn = "REINICIAR",
        rebootingBtn = "REINICIANDO",
        driverStatusLoaded = "CARREGADO E ATIVO",
        driverStatusHidden = "CARREGADO EM MODO STEALTH",
        driverStatusNotLoaded = "NÃO CARREGADO",
        driverStatusLoadedHint = "O módulo fica ativo até a próxima reinicialização",
        driverStatusNotLoadedHint = "Toque no botão LOAD acima para carregar",
        verifyBtn = "VERIFICAR SCRIPT E DRIVER",
        verifyingBtn = "VERIFICANDO DRIVER...",
        verifyPassText = "VERIFICADO: DRIVER FUNCIONANDO",
        verifyFailText = "FALHA: DRIVER NÃO ATIVO",
        customScriptTitle = "Carregar Script .sh Personalizado",
        customScriptSub = "Selecione e execute um script .sh ou driver .ko com root",
        customScriptPickBtn = "CARREGAR SCRIPT .SH",
        customScriptLoadBtn = "EXECUTAR SCRIPT .SH AGORA",
        customScriptChangeBtn = "ALTERAR",
        customScriptClearBtn = "LIMPAR",
        statusTerminalTitle = "TERMINAL DE STATUS",
        statusTerminalHint = "Pronto — o status do kernel e dos módulos aparecerá aqui",
        supportedKernelTerminalTitle = "TERMINAL DE KERNELS SUPORTADOS",
        supportedKernelTerminalSub = "Lista de scripts e drivers disponíveis no banco",
        reportSectionTitle = "ENVIAR RELATÓRIO DE KERNEL",
        sendTelegramBtn = "Enviar pelo Telegram",
        sendWhatsappBtn = "Enviar pelo WhatsApp",
        copyReportBtn = "COPIAR RELATÓRIO DE TEXTO",
        collectingReport = "CRIANDO ZIP DO RELATÓRIO...",
        languageDialogTitle = "Selecionar Idioma do App",
        autoIpDetectOption = "Detecção Automática por IP do País"
    )

    private val RuStrings = EnStrings.copy(
        langCode = "ru",
        subtitle = "ПРЕМИУМ ЗАГРУЗЧИК ЯДРА И СКРИПТОВ",
        splashInitializing = "Инициализация стелс-движка ядра...",
        rootTitle = "ROOT ДОСТУП",
        rootGranted = "РАЗРЕШЕНИЕ ПОЛУЧЕНО",
        rootDenied = "НЕТ ROOT РАЗРЕШЕНИЯ",
        rootGrantedSub = "Superuser (su) доступ активен",
        rootDeniedSub = "Предоставьте root в Magisk / KernelSU / APatch",
        vermagicTitle = "VERMAGIC ЯДРА",
        vermagicSub = "Короткая сигнатура vermagic ядра устройства",
        supportedTitle = "ПОДДЕРЖКА В БАЗЕ",
        supportedYes = "ПОДДЕРЖИВАЕТСЯ: ДА",
        supportedNo = "ПОДДЕРЖИВАЕТСЯ: НЕТ",
        supportedChecking = "ПРОВЕРКА БАЗЫ ДАННЫХ...",
        supportedYesSub = "Совместимый драйвер найден в базе данных",
        supportedNoSub = "Драйвер для этого vermagic не найден — отправьте отчёт",
        loadBtn = "LOAD",
        loadingBtn = "ЗАГРУЗКА",
        stopBtn = "СТОП",
        restartBtn = "ПЕРЕЗАГРУЗКА",
        rebootingBtn = "ПЕРЕЗАГРУЗКА",
        driverStatusLoaded = "ЗАГРУЖЕН И АКТИВЕН",
        driverStatusHidden = "ЗАГРУЖЕН В СТЕЛС-РЕЖИМЕ",
        driverStatusNotLoaded = "НЕ ЗАГРУЖЕН",
        driverStatusLoadedHint = "Модуль активен до следующей перезагрузки",
        driverStatusNotLoadedHint = "Нажмите круглую кнопку LOAD выше для загрузки",
        verifyBtn = "ПРОВЕРИТЬ СКРИПТ И ДРАЙВЕР",
        verifyingBtn = "ПРОВЕРКА ДРАЙВЕРА...",
        verifyPassText = "ПРОВЕРЕНО: ДРАЙВЕР РАБОТАЕТ",
        verifyFailText = "ОШИБКА: ДРАЙВЕР НЕ АКТИВЕН",
        customScriptTitle = "Загрузка пользовательского .sh скрипта",
        customScriptSub = "Выберите и запустите свой .sh скрипт или .ko драйвер с root",
        customScriptPickBtn = "ЗАГРУЗИТЬ .SH СКРИПТ",
        customScriptLoadBtn = "ЗАПУСТИТЬ .SH СКРИПТ СЕЙЧАС",
        customScriptChangeBtn = "ИЗМЕНИТЬ",
        customScriptClearBtn = "ОЧИСТИТЬ",
        statusTerminalTitle = "ТЕРМИНАЛ СТАТУСА",
        statusTerminalHint = "Готово — состояние ядра и скриптов будет отображаться здесь",
        supportedKernelTerminalTitle = "ТЕРМИНАЛ ПОДДЕРЖИВАЕМЫХ ЯДЕР",
        supportedKernelTerminalSub = "Список скриптов и драйверов в базе данных",
        reportSectionTitle = "ОТПРАВИТЬ ОТЧЁТ РАЗРАБОТЧИКУ",
        sendTelegramBtn = "Отправить через Telegram",
        sendWhatsappBtn = "Отправить через WhatsApp",
        copyReportBtn = "СКОПИРОВАТЬ ТЕКСТОВЫЙ ОТЧЁТ",
        collectingReport = "СОЗДАНИЕ ZIP ОТЧЁТА...",
        languageDialogTitle = "Выберите язык приложения",
        autoIpDetectOption = "Автоопределение по IP страны"
    )

    private val UkStrings = EnStrings.copy(
        langCode = "uk",
        subtitle = "ПРЕМІУМ ЗАВАНТАЖУВАЧ ЯДРА ТА СКРИПТІВ",
        splashInitializing = "Ініціалізація стелс-модуля ядра...",
        rootTitle = "ДОСТУП ROOT",
        rootGranted = "ДОЗВІЛ НАДАНО",
        rootDenied = "НЕМАЄ ДОЗВОЛУ ROOT",
        rootGrantedSub = "Superuser (su) доступ активний",
        rootDeniedSub = "Надайте root-дозвіл у Magisk / KernelSU / APatch",
        vermagicTitle = "VERMAGIC ЯДРА",
        vermagicSub = "Коротка версія сигнатури vermagic ядра",
        supportedTitle = "ПІДТРИМКА В БАЗІ ДАНИХ",
        supportedYes = "ПІДТРИМУЄТЬСЯ: ТАК",
        supportedNo = "ПІДТРИМУЄТЬСЯ: НІ",
        supportedChecking = "ПЕРЕВІРКА БАЗИ ДАНИХ...",
        supportedYesSub = "Сумісний драйвер є в базі даних",
        supportedNoSub = "Драйвер не знайдено — надішліть звіт нижче",
        loadBtn = "LOAD",
        loadingBtn = "ЗАВАНТАЖЕННЯ",
        stopBtn = "СТОП",
        restartBtn = "ПЕРЕЗАВАНТАЖЕННЯ",
        rebootingBtn = "ПЕРЕЗАВАНТАЖЕННЯ",
        driverStatusLoaded = "ЗАВАНТАЖЕНО ТА АКТИВНО",
        driverStatusHidden = "ЗАВАНТАЖЕНО В СТЕЛС-РЕЖИМІ",
        driverStatusNotLoaded = "НЕ ЗАВАНТАЖЕНО",
        driverStatusLoadedHint = "Драйвер активний до наступного перезавантаження",
        driverStatusNotLoadedHint = "Натисніть кнопку LOAD вище для завантаження",
        verifyBtn = "ПЕРЕВІРИТИ СКРИПТ ТА ДРАЙВЕР",
        verifyingBtn = "ПЕРЕВІРКА ДРАЙВЕРА...",
        verifyPassText = "ПЕРЕВІРЕНО: ДРАЙВЕР ПРАЦЮЄ",
        verifyFailText = "ПОМИЛКА: ДРАЙВЕР НЕ АКТИВНИЙ",
        customScriptTitle = "Завантажувач .sh скриптів",
        customScriptSub = "Виберіть та запустіть .sh скрипт або .ko драйвер з root",
        customScriptPickBtn = "ЗАВАНТАЖИТИ .SH СКРИПТ",
        customScriptLoadBtn = "ЗАПУСТИТИ .SH СКРИПТ",
        customScriptChangeBtn = "ЗМІНИТИ",
        customScriptClearBtn = "ОЧИСТИТИ",
        statusTerminalTitle = "ТЕРМІНАЛ СТАТУСУ",
        statusTerminalHint = "Готово — логи та статус драйвера відображатимуться тут",
        supportedKernelTerminalTitle = "ПІДТРИМУВАНІ ЯДРА",
        supportedKernelTerminalSub = "Список драйверів у базі даних",
        reportSectionTitle = "НАДІСЛАТИ ЗВІТ РОЗРОБНИКУ",
        sendTelegramBtn = "Надіслати через Telegram",
        sendWhatsappBtn = "Надіслати через WhatsApp",
        copyReportBtn = "СКОПІЮВАТИ ТЕКСТОВИЙ ЗВІТ",
        collectingReport = "СТВОРЕННЯ ZIP ЗВІТУ...",
        languageDialogTitle = "Виберіть мову програми",
        autoIpDetectOption = "Автовизначення за IP"
    )

    private val EsStrings = EnStrings.copy(
        langCode = "es",
        subtitle = "MOTOR PREMIUM DE KERNEL Y SCRIPTS",
        splashInitializing = "Iniciando motor stealth del kernel...",
        rootTitle = "PERMISO ROOT",
        rootGranted = "PERMISO CONCEDIDO",
        rootDenied = "PERMISO NO CONCEDIDO",
        rootGrantedSub = "Acceso Superuser (su) activo",
        rootDeniedSub = "Otorgue permisos root en Magisk / KernelSU / APatch",
        vermagicTitle = "VERMAGIC DEL KERNEL",
        vermagicSub = "Firma vermagic corta del kernel del dispositivo",
        supportedTitle = "SOPORTE EN BASE DE DATOS",
        supportedYes = "SOPORTADO: SÍ",
        supportedNo = "SOPORTADO: NO",
        supportedChecking = "VERIFICANDO BASE DE DATOS...",
        supportedYesSub = "Driver o script compatible disponible en la base de datos",
        supportedNoSub = "No se encontró driver para este kernel — envíe un reporte abajo",
        loadBtn = "LOAD",
        loadingBtn = "CARGANDO",
        stopBtn = "DETENER",
        restartBtn = "REINICIAR",
        rebootingBtn = "REINICIANDO",
        driverStatusLoaded = "CARGADO Y ACTIVO",
        driverStatusHidden = "CARGADO EN MODO STEALTH",
        driverStatusNotLoaded = "NO CARGADO",
        driverStatusLoadedHint = "El módulo permanece activo hasta el próximo reinicio",
        driverStatusNotLoadedHint = "Toque el botón LOAD arriba para cargar el driver",
        verifyBtn = "VERIFICAR SCRIPT Y DRIVER",
        verifyingBtn = "VERIFICANDO DRIVER...",
        verifyPassText = "VERIFICADO: DRIVER FUNCIONANDO",
        verifyFailText = "FALLO: DRIVER NO ACTIVO",
        customScriptTitle = "Cargador de Script .sh Personalizado",
        customScriptSub = "Seleccione y ejecute un script .sh o driver .ko con root",
        customScriptPickBtn = "CARGAR SCRIPT .SH",
        customScriptLoadBtn = "EJECUTAR SCRIPT .SH AHORA",
        customScriptChangeBtn = "CAMBIAR",
        customScriptClearBtn = "LIMPIAR",
        statusTerminalTitle = "TERMINAL DE ESTADO",
        statusTerminalHint = "Listo — el estado de la app y los drivers aparecerá aquí",
        supportedKernelTerminalTitle = "TERMINAL DE KERNELS SOPORTADOS",
        supportedKernelTerminalSub = "Lista de scripts y drivers disponibles en la base de datos",
        reportSectionTitle = "ENVIAR REPORTE DE KERNEL",
        sendTelegramBtn = "Enviar por Telegram",
        sendWhatsappBtn = "Enviar por WhatsApp",
        copyReportBtn = "COPIAR REPORTE DE TEXTO",
        collectingReport = "CREANDO ZIP DE REPORTE...",
        languageDialogTitle = "Seleccionar Idioma de la Aplicación",
        autoIpDetectOption = "Detección Automática por IP del País"
    )

    private val FrStrings = EnStrings.copy(
        langCode = "fr",
        subtitle = "MOTEUR KERNEL ET SCRIPTS PREMIUM",
        splashInitializing = "Initialisation du moteur stealth de kernel...",
        rootTitle = "PERMISSION ROOT",
        rootGranted = "PERMISSION ACCORDÉE",
        rootDenied = "PERMISSION NON ACCORDÉE",
        rootGrantedSub = "L'accès superutilisateur (su) est actif",
        rootDeniedSub = "Accordez la permission root dans Magisk / KernelSU / APatch",
        vermagicTitle = "VERMAGIC DU KERNEL",
        vermagicDetecting = "Détection du vermagic...",
        vermagicSub = "Signature vermagic courte du kernel de l'appareil",
        supportedTitle = "SUPPORT BASE DE DONNÉES",
        supportedYes = "SUPPORTÉ : OUI",
        supportedNo = "SUPPORTÉ : NON",
        supportedChecking = "VÉRIFICATION DE LA BASE...",
        supportedYesSub = "Script ou pilote correspondant disponible dans la base",
        supportedNoSub = "Aucun pilote trouvé — envoyez un rapport ci-dessous",
        loadBtn = "LOAD",
        loadingBtn = "CHARGEMENT",
        stopBtn = "ARRÊTER",
        restartBtn = "REDÉMARRER",
        rebootingBtn = "REDÉMARRAGE",
        driverStatusLoaded = "CHARGÉ ET ACTIF",
        driverStatusHidden = "CHARGÉ EN MODE STEALTH",
        driverStatusNotLoaded = "NON CHARGÉ",
        driverStatusLoadedHint = "Le module reste actif jusqu'au prochain redémarrage",
        driverStatusNotLoadedHint = "Appuyez sur le bouton rond LOAD pour charger",
        verifyBtn = "VÉRIFIER LE SCRIPT ET LE PILOTE",
        verifyingBtn = "VÉRIFICATION DU PILOTE...",
        verifyPassText = "VÉRIFIÉ : PILOTE OPÉRATIONNEL",
        verifyFailText = "ÉCHEC : PILOTE NON ACTIF",
        customScriptTitle = "Chargeur de Script .sh Personnalisé",
        customScriptSub = "Sélectionnez et exécutez un script .sh ou pilote .ko avec root",
        customScriptPickBtn = "CHARGER LE SCRIPT .SH",
        customScriptLoadBtn = "EXÉCUTER LE SCRIPT .SH",
        customScriptChangeBtn = "MODIFIER",
        customScriptClearBtn = "EFFACER",
        statusTerminalTitle = "TERMINAL DE STATUT",
        statusTerminalHint = "Prêt — l'état de l'application et des pilotes s'affichera ici",
        supportedKernelTerminalTitle = "KERNELS SUPPORTÉS",
        supportedKernelTerminalSub = "Liste des scripts et pilotes disponibles",
        reportSectionTitle = "ENVOYER UN RAPPORT KERNEL",
        sendTelegramBtn = "Envoyer via Telegram",
        sendWhatsappBtn = "Envoyer via WhatsApp",
        copyReportBtn = "COPIER LE RAPPORT COMPLET",
        collectingReport = "CRÉATION DU RAPPORT ZIP...",
        languageDialogTitle = "Sélectionner la langue de l'application",
        autoIpDetectOption = "Détection automatique selon l'IP"
    )

    private val DeStrings = EnStrings.copy(
        langCode = "de",
        subtitle = "PREMIUM KERNEL & SCRIPT ENGINE",
        splashInitializing = "Kernel Stealth Engine wird initialisiert...",
        rootTitle = "ROOT-BERECHTIGUNG",
        rootGranted = "BERECHTIGUNG ERTEILT",
        rootDenied = "BERECHTIGUNG NICHT ERTEILT",
        rootGrantedSub = "Superuser-Zugriff (su) ist aktiv",
        rootDeniedSub = "Root-Berechtigung in Magisk / KernelSU / APatch erteilen",
        vermagicTitle = "KERNEL VERMAGIC",
        vermagicDetecting = "Vermagic wird erkannt...",
        vermagicSub = "Kurze Vermagic-Signatur des Kernel-Geräts",
        supportedTitle = "DATENBANK-UNTERSTÜTZUNG",
        supportedYes = "UNTERSTÜTZT: JA",
        supportedNo = "UNTERSTÜTZT: NEIN",
        supportedChecking = "DATENBANK WIRD GEPRÜFT...",
        supportedYesSub = "Passender Treiber in Datenbank verfügbar",
        supportedNoSub = "Kein Treiber gefunden — Bericht unten senden",
        loadBtn = "LOAD",
        loadingBtn = "LÄDT",
        stopBtn = "STOPP",
        restartBtn = "NEUSTART",
        rebootingBtn = "WIRD NEU GESTARTET",
        driverStatusLoaded = "GELADEN & AKTIV",
        driverStatusHidden = "GELADEN IM STEALTH-MODUS",
        driverStatusNotLoaded = "NICHT GELADEN",
        driverStatusLoadedHint = "Modul/Script bleibt bis zum nächsten Neustart aktiv",
        driverStatusNotLoadedHint = "Tippen Sie oben auf LOAD, um zu laden",
        verifyBtn = "SCRIPT & TREIBER ÜBERPRÜFEN",
        verifyingBtn = "TREIBER WIRD ÜBERPRÜFT...",
        verifyPassText = "BESTÄTIGT: TREIBER FUNKTIONIERT",
        verifyFailText = "FEHLER: TREIBER NICHT AKTIV",
        customScriptTitle = "Benutzerdefinierter .sh Script-Loader",
        customScriptSub = "Benutzerdefiniertes .sh Script oder .ko Treiber ausführen",
        customScriptPickBtn = ".SH SCRIPT LADEN",
        customScriptLoadBtn = ".SH SCRIPT JETZT AUSFÜHREN",
        customScriptChangeBtn = "ÄNDERN",
        customScriptClearBtn = "LÖSCHEN",
        statusTerminalTitle = "STATUS-TERMINAL",
        statusTerminalHint = "Bereit — Status von Anwendung, Script und Modulen erscheint hier",
        supportedKernelTerminalTitle = "TERMINAL UNTERSTÜTZTER KERNELS",
        supportedKernelTerminalSub = "Liste aller Scripte und Treiber in der Datenbank",
        reportSectionTitle = "KERNEL-BERICHT SENDEN",
        sendTelegramBtn = "Über Telegram senden",
        sendWhatsappBtn = "Über WhatsApp senden",
        copyReportBtn = "VOLLSTÄNDIGEN BERICHT KOPIEREN",
        collectingReport = "BERICHTS-ZIP WIRD ERSTELLT...",
        languageDialogTitle = "Sprache der App auswählen",
        autoIpDetectOption = "Automatische Erkennung nach Länder-IP"
    )

    private val ItStrings = EnStrings.copy(
        langCode = "it",
        subtitle = "MOTORE PREMIUM DI KERNEL E SCRIPT",
        splashInitializing = "Inizializzazione motore stealth del kernel...",
        rootTitle = "PERMESSI DI ROOT",
        rootGranted = "PERMESSO CONCESSO",
        rootDenied = "PERMESSO NON CONCESSO",
        rootGrantedSub = "Accesso Superuser (su) attivo",
        rootDeniedSub = "Concedi i permessi root in Magisk / KernelSU / APatch",
        vermagicTitle = "VERMAGIC DEL KERNEL",
        vermagicDetecting = "Rilevamento vermagic in corso...",
        vermagicSub = "Firma vermagic breve del kernel del dispositivo",
        supportedTitle = "SUPPORTO DATABASE",
        supportedYes = "SUPPORTATO: SÌ",
        supportedNo = "SUPPORTATO: NO",
        supportedChecking = "VERIFICA DATABASE...",
        supportedYesSub = "Driver o script compatibile disponibile nel database",
        supportedNoSub = "Nessun driver trovato per questo vermagic — invia un report",
        loadBtn = "LOAD",
        loadingBtn = "CARICAMENTO",
        stopBtn = "STOP",
        restartBtn = "RIAVVIA",
        rebootingBtn = "RIAVVIO IN CORSO",
        driverStatusLoaded = "CARICATO E ATTIVO",
        driverStatusHidden = "CARICATO IN MODALITÀ STEALTH",
        driverStatusNotLoaded = "NON CARICATO",
        driverStatusLoadedHint = "Il driver rimane attivo fino al prossimo riavvio del telefono",
        driverStatusNotLoadedHint = "Tocca il pulsante LOAD in alto per caricare",
        verifyBtn = "VERIFICA SCRIPT E DRIVER",
        verifyingBtn = "VERIFICA DRIVER IN CORSO...",
        verifyPassText = "VERIFICATO: DRIVER FUNZIONANTE",
        verifyFailText = "ERRORE: DRIVER NON ATTIVO",
        customScriptTitle = "Caricatore Script .sh Personalizzato",
        customScriptSub = "Seleziona ed esegui script .sh o driver .ko con permessi root",
        customScriptPickBtn = "CARICA SCRIPT .SH",
        customScriptLoadBtn = "ESEGUI SCRIPT .SH ORA",
        customScriptChangeBtn = "MODIFICA",
        customScriptClearBtn = "RIMUOVI",
        statusTerminalTitle = "TERMINALE DI STATO",
        statusTerminalHint = "Pronto — lo stato di app, script e moduli apparirà qui",
        supportedKernelTerminalTitle = "TERMINALE KERNEL SUPPORTATI",
        supportedKernelTerminalSub = "Elenco degli script e driver presenti nel database",
        reportSectionTitle = "INVIA REPORT KERNEL",
        sendTelegramBtn = "Invia tramite Telegram",
        sendWhatsappBtn = "Invia tramite WhatsApp",
        copyReportBtn = "COPIA REPORT TESTO",
        collectingReport = "CREAZIONE ZIP IN CORSO...",
        languageDialogTitle = "Seleziona la lingua dell'applicazione",
        autoIpDetectOption = "Rilevamento automatico per IP"
    )

    private val TrStrings = EnStrings.copy(
        langCode = "tr",
        subtitle = "PREMİUM KERNEL VE SCRİPT MOTORU",
        splashInitializing = "Kernel Motoru Başlatılıyor...",
        rootTitle = "ROOT İZNİ",
        rootGranted = "ROOT İZNİ VERİLDİ",
        rootDenied = "ROOT İZNİ VERİLMEDİ",
        rootGrantedSub = "Superuser (su) erişimi etkin",
        rootDeniedSub = "Magisk / KernelSU / APatch üzerinden root izni verin",
        vermagicTitle = "KERNEL VERMAGIC",
        vermagicSub = "Cihazın kısa vermagic kernel imzası",
        supportedTitle = "VERİTABANI DESTEĞİ",
        supportedYes = "DESTEKLENİYOR: EVET",
        supportedNo = "DESTEKLENMİYOR: HAYIR",
        supportedChecking = "VERİTABANI KONTROL EDİLİYOR...",
        supportedYesSub = "Uyumlu script / sürücü veritabanında mevcut",
        supportedNoSub = "Bu kernel için sürücü bulunamadı — aşağıdan rapor gönderin",
        loadBtn = "LOAD",
        loadingBtn = "YÜKLENİYOR",
        stopBtn = "DURDUR",
        restartBtn = "YENİDEN BAŞLAT",
        rebootingBtn = "YENİDEN BAŞLATILIYOR",
        driverStatusLoaded = "YÜKLENDİ VE AKTİF",
        driverStatusHidden = "GİZLİ (STEALTH) MODDA YÜKLENDİ",
        driverStatusNotLoaded = "YÜKLENMEDİ",
        driverStatusLoadedHint = "Sürücü bir sonraki cihaz yeniden başlatmasına kadar aktiftir",
        driverStatusNotLoadedHint = "Yüklemek için yukarıdaki LOAD butonuna dokunun",
        verifyBtn = "SCRİPT VE SÜRÜCÜYÜ DOĞRULA",
        verifyingBtn = "DOĞRULANIYOR...",
        verifyPassText = "DOĞRULANDI: SÜRÜCÜ ÇALIŞIYOR",
        verifyFailText = "HATA: SÜRÜCÜ AKTİF DEĞİL",
        customScriptTitle = "Özel .sh Script Yükleyici",
        customScriptSub = "Root ile özel .sh veya .ko dosyasını çalıştırın",
        customScriptPickBtn = "ÖZEL .SH SCRİPT SEÇ",
        customScriptLoadBtn = ".SH SCRİPTİ ÇALIŞTIR",
        customScriptChangeBtn = "DEĞİŞTİR",
        customScriptClearBtn = "TEMİZLE",
        statusTerminalTitle = "DURUM TERMİNALİ",
        statusTerminalHint = "Hazır — uygulama ve sürücü durumları burada listelenecektir",
        supportedKernelTerminalTitle = "DESTEKLENEN KERNEL TERMİNALİ",
        supportedKernelTerminalSub = "Veritabanındaki script ve sürücü listesi",
        reportSectionTitle = "KERNEL RAPORU GÖNDER",
        sendTelegramBtn = "Telegram ile Gönder",
        sendWhatsappBtn = "WhatsApp ile Gönder",
        copyReportBtn = "METİN RAPORUNU KOPYALA",
        collectingReport = "RAPOR ZIP OLUŞTURULUYOR...",
        languageDialogTitle = "Uygulama Dilini Seçin",
        autoIpDetectOption = "Ülke IP'sine Göre Otomatik Algıla"
    )

    private val ViStrings = EnStrings.copy(
        langCode = "vi",
        subtitle = "CÔNG CỤ NẠP KERNEL & SCRIPT CAO CẤP",
        splashInitializing = "Đang khởi tạo công cụ Kernel...",
        rootTitle = "QUYỀN ROOT",
        rootGranted = "ĐÃ CẤP QUYỀN ROOT",
        rootDenied = "CHƯA CẤP QUYỀN ROOT",
        rootGrantedSub = "Quyền truy cập Superuser (su) đang hoạt động",
        rootDeniedSub = "Cấp quyền root trong Magisk / KernelSU / APatch",
        vermagicTitle = "VERMAGIC KERNEL",
        vermagicSub = "Chữ ký vermagic rút gọn của thiết bị",
        supportedTitle = "HỖ TRỢ TRONG CƠ SỞ DỮ LIỆU",
        supportedYes = "ĐƯỢC HỖ TRỢ: CÓ",
        supportedNo = "CHƯA HỖ TRỢ: KHÔNG",
        supportedChecking = "ĐANG KIỂM TRA DỮ LIỆU...",
        supportedYesSub = "Đã có driver hoặc script tương thích trong cơ sở dữ liệu",
        supportedNoSub = "Chưa có driver cho vermagic này — vui lòng gửi báo cáo bên dưới",
        loadBtn = "LOAD",
        loadingBtn = "ĐANG NẠP",
        stopBtn = "DỪNG",
        restartBtn = "KHỞI ĐỘNG LẠI",
        rebootingBtn = "ĐANG KHỞI ĐỘNG LẠI",
        driverStatusLoaded = "ĐÃ NẠP & ĐANG HOẠT ĐỘNG",
        driverStatusHidden = "ĐÃ NẠP TRONG CHẾ ĐỘ ẨN",
        driverStatusNotLoaded = "CHƯA ĐƯỢC NẠP",
        driverStatusLoadedHint = "Driver hoạt động cho đến lần khởi động máy tiếp theo",
        driverStatusNotLoadedHint = "Chạm vào nút tròn LOAD phía trên để nạp driver",
        verifyBtn = "XÁC MINH SCRIPT & DRIVER",
        verifyingBtn = "ĐANG XÁC MINH...",
        verifyPassText = "ĐÃ XÁC MINH: DRIVER HOẠT ĐỘNG TỐT",
        verifyFailText = "THẤT BẠI: DRIVER CHƯA HOẠT ĐỘNG",
        customScriptTitle = "Nạp Script .sh Tùy Chỉnh",
        customScriptSub = "Chọn và chạy script .sh hoặc driver .ko với quyền root",
        customScriptPickBtn = "CHỌN SCRIPT .SH",
        customScriptLoadBtn = "CHẠY SCRIPT .SH NGAY",
        customScriptChangeBtn = "ĐỔI",
        customScriptClearBtn = "XÓA",
        statusTerminalTitle = "TERMINAL TRẠNG THÁI",
        statusTerminalHint = "Sẵn sàng — trạng thái ứng dụng và module sẽ hiển thị tại đây",
        supportedKernelTerminalTitle = "TERMINAL KERNEL ĐƯỢC HỖ TRỢ",
        supportedKernelTerminalSub = "Danh sách script và driver có trong cơ sở dữ liệu",
        reportSectionTitle = "GỬI BÁO CÁO KERNEL",
        sendTelegramBtn = "Gửi qua Telegram",
        sendWhatsappBtn = "Gửi qua WhatsApp",
        copyReportBtn = "SAO CHÉP BÁO CÁO",
        collectingReport = "ĐANG TẠO FILE ZIP BÁO CÁO...",
        languageDialogTitle = "Chọn ngôn ngữ ứng dụng",
        autoIpDetectOption = "Tự động phát hiện theo IP quốc gia"
    )

    private val ThStrings = EnStrings.copy(
        langCode = "th",
        subtitle = "เอนจินเคอร์เนลและสคริปต์ระดับพรีเมียม",
        splashInitializing = "กำลังเริ่มระบบเคอร์เนล...",
        rootTitle = "สิทธิ์ ROOT",
        rootGranted = "ได้รับสิทธิ์แล้ว",
        rootDenied = "ไม่ได้รับสิทธิ์",
        rootGrantedSub = "การเข้าถึง Superuser (su) ทำงานอยู่",
        rootDeniedSub = "อนุญาตสิทธิ์ Root ใน Magisk / KernelSU / APatch",
        vermagicTitle = "VERMAGIC ของเคอร์เนล",
        vermagicSub = "ลายเซ็น vermagic แบบย่อของอุปกรณ์",
        supportedTitle = "การรองรับในฐานข้อมูล",
        supportedYes = "รองรับ: ใช่ (YES)",
        supportedNo = "รองรับ: ไม่ (NO)",
        supportedChecking = "กำลังตรวจสอบฐานข้อมูล...",
        supportedYesSub = "มีไดรเวอร์ที่ตรงกันในฐานข้อมูล",
        supportedNoSub = "ยังไม่รองรับเคอร์เนลนี้ — กรุณาส่งรายงานด้านล่าง",
        loadBtn = "LOAD",
        loadingBtn = "กำลังโหลด",
        stopBtn = "หยุด",
        restartBtn = "รีสตาร์ต",
        rebootingBtn = "กำลังรีบูต",
        driverStatusLoaded = "โหลดแล้วและทำงานอยู่",
        driverStatusHidden = "โหลดในโหมด Stealth สำเร็จ",
        driverStatusNotLoaded = "ยังไม่ได้โหลด",
        driverStatusLoadedHint = "ไดรเวอร์จะทำงานจนกว่าเครื่องจะรีบูต",
        driverStatusNotLoadedHint = "แตะปุ่ม LOAD ด้านบนเพื่อโหลดไดรเวอร์",
        verifyBtn = "ตรวจสอบสคริปต์และไดรเวอร์",
        verifyingBtn = "กำลังตรวจสอบ...",
        verifyPassText = "ผ่านการตรวจสอบ: ไดรเวอร์ทำงานปกติ",
        verifyFailText = "ไม่ผ่าน: ไดรเวอร์ไม่ทำงาน",
        customScriptTitle = "ตัวโหลดสคริปต์ .sh กำหนดเอง",
        customScriptSub = "เลือกและรันสคริปต์ .sh หรือ .ko จากที่จัดเก็บข้อมูล",
        customScriptPickBtn = "เลือกไฟล์ .SH กำหนดเอง",
        customScriptLoadBtn = "รันสคริปต์ .SH ตอนนี้",
        customScriptChangeBtn = "เปลี่ยน",
        customScriptClearBtn = "ล้าง",
        statusTerminalTitle = "เทอร์มินัลสถานะ",
        statusTerminalHint = "พร้อมทำงาน — ข้อความสถานะจะปรากฏที่นี่",
        supportedKernelTerminalTitle = "เคอร์เนลที่รองรับ",
        supportedKernelTerminalSub = "รายชื่อสคริปต์และไดรเวอร์ทั้งหมดในฐานข้อมูล",
        reportSectionTitle = "ส่งรายงานเคอร์เนล",
        sendTelegramBtn = "ส่งผ่าน Telegram",
        sendWhatsappBtn = "ส่งผ่าน WhatsApp",
        copyReportBtn = "คัดลอกรายงานข้อความ",
        collectingReport = "กำลังสร้างไฟล์ ZIP...",
        languageDialogTitle = "เลือกภาษาของแอป",
        autoIpDetectOption = "ตรวจจับภาษาอัตโนมัติตาม IP"
    )

    private val FilStrings = EnStrings.copy(
        langCode = "fil",
        subtitle = "PREMIUM NA KERNEL AT SCRIPT ENGINE",
        splashInitializing = "Sinisimulan ang Kernel Stealth Engine...",
        rootTitle = "PERMISO NG ROOT",
        rootGranted = "BINIGYAN NG PERMISO",
        rootDenied = "WALANG PERMISO",
        rootGrantedSub = "Aktibo ang Superuser (su) access",
        rootDeniedSub = "Paganahin ang root sa Magisk / KernelSU / APatch",
        vermagicTitle = "VERMAGIC NG KERNEL",
        vermagicSub = "Maikling bersyon ng vermagic ng device",
        supportedTitle = "SUPORTA SA DATABASE",
        supportedYes = "SUPORTADO: OO",
        supportedNo = "SUPORTADO: HINDI",
        supportedChecking = "TINITINGNAN ANG DATABASE...",
        supportedYesSub = "May katugmang script o driver sa database",
        supportedNoSub = "Walang nahanap na driver — magpadala ng ulat sa ibaba",
        loadBtn = "LOAD",
        loadingBtn = "NILO-LOAD",
        stopBtn = "IHINTO",
        restartBtn = "I-RESTART",
        rebootingBtn = "NAGRE-REBOOT",
        driverStatusLoaded = "NA-LOAD AT AKTIBO",
        driverStatusHidden = "NA-LOAD SA STEALTH MODE",
        driverStatusNotLoaded = "HINDI PA NA-LOAD",
        driverStatusLoadedHint = "Mananatiling aktibo hanggang sa susunod na reboot",
        driverStatusNotLoadedHint = "Pindutin ang LOAD button sa itaas upang mag-load",
        verifyBtn = "I-VERIFY ANG SCRIPT AT DRIVER",
        verifyingBtn = "NINE-VERIFY ANG DRIVER...",
        verifyPassText = "VERIFIED: GUMAGANA ANG DRIVER",
        verifyFailText = "FAIL: HINDI AKTIBO ANG DRIVER",
        customScriptTitle = "Custom .sh Script Loader",
        customScriptSub = "Pumili at magpatakbo ng custom .sh script o .ko driver gamit ang root",
        customScriptPickBtn = "PUMILI NG .SH SCRIPT",
        customScriptLoadBtn = "PATAKBUHIN ANG .SH SCRIPT",
        customScriptChangeBtn = "PALITAN",
        customScriptClearBtn = "ALISIN",
        statusTerminalTitle = "TERMINAL NG STATUS",
        statusTerminalHint = "Handa — lalabas dito ang status ng application at driver",
        supportedKernelTerminalTitle = "MGA SUPORTADONG KERNEL",
        supportedKernelTerminalSub = "Listahan ng mga script at driver sa database",
        reportSectionTitle = "MAGPADALA NG ULAT SA KERNEL",
        sendTelegramBtn = "Ipadala sa Telegram",
        sendWhatsappBtn = "Ipadala sa WhatsApp",
        copyReportBtn = "KOPYAHIN ANG ULAT",
        collectingReport = "GINAGAWA ANG REPORT ZIP...",
        languageDialogTitle = "Pumili ng Wika ng App",
        autoIpDetectOption = "Awtomatikong tukuyin ayon sa IP"
    )

    private val ZhStrings = EnStrings.copy(
        langCode = "zh",
        subtitle = "高级内核模块与脚本加载引擎",
        splashInitializing = "正在初始化内核隐蔽引擎...",
        rootTitle = "ROOT 权限状态",
        rootGranted = "已授予 ROOT 权限",
        rootDenied = "未授予 ROOT 权限",
        rootGrantedSub = "Superuser (su) 根权限正常运行",
        rootDeniedSub = "请在 Magisk / KernelSU / APatch 中授予 Root 权限",
        vermagicTitle = "内核 VERMAGIC",
        vermagicSub = "设备的短格式内核 Vermagic 标识",
        supportedTitle = "数据库支持状态",
        supportedYes = "已支持: 是 (YES)",
        supportedNo = "未支持: 否 (NO)",
        supportedChecking = "正在查询云端数据库...",
        supportedYesSub = "数据库中存在匹配的脚本与驱动模块",
        supportedNoSub = "未找到适配此内核的驱动 — 请在下方提交报告",
        loadBtn = "LOAD",
        loadingBtn = "正在加载",
        stopBtn = "停止",
        restartBtn = "重启手机",
        rebootingBtn = "正在重启",
        driverStatusLoaded = "已加载并正常运行",
        driverStatusHidden = "已在隐蔽模式 (Stealth) 下加载",
        driverStatusNotLoaded = "尚未加载 (NOT LOADED)",
        driverStatusLoadedHint = "内核驱动模块将在设备下次重启前持续生效",
        driverStatusNotLoadedHint = "点击上方带有动画的光环 LOAD 按钮开始加载",
        verifyBtn = "验证脚本与驱动状态",
        verifyingBtn = "正在校验驱动状态...",
        verifyPassText = "验证成功: 驱动模块工作正常",
        verifyFailText = "验证失败: 驱动尚未处于激活状态",
        customScriptTitle = "自定义 .sh 脚本加载器",
        customScriptSub = "从手机存储中选择并以 Root 身份运行自定义 .sh 或 .ko 文件",
        customScriptPickBtn = "选择自定义 .SH 脚本",
        customScriptLoadBtn = "立即运行 .SH 脚本",
        customScriptChangeBtn = "更换文件",
        customScriptClearBtn = "清除选择",
        statusTerminalTitle = "状态终端 (STATUS TERMINAL)",
        statusTerminalHint = "就绪 — 应用、脚本及驱动模块的执行日志将显示于此处",
        supportedKernelTerminalTitle = "支持的内核终端 (SUPPORTED KERNELS)",
        supportedKernelTerminalSub = "数据库已适配的所有脚本及驱动列表",
        reportSectionTitle = "发送内核构建报告",
        sendTelegramBtn = "通过 Telegram 发送",
        sendWhatsappBtn = "通过 WhatsApp 发送",
        copyReportBtn = "复制完整文本报告",
        collectingReport = "正在生成报告压缩包 (ZIP)...",
        languageDialogTitle = "选择应用语言",
        autoIpDetectOption = "根据国家 IP 自动检测语言"
    )

    private val JaStrings = EnStrings.copy(
        langCode = "ja",
        subtitle = "プレミアムカーネル＆スクリプトエンジン",
        splashInitializing = "カーネルステルスエンジンを初期化中...",
        rootTitle = "ROOT 権限",
        rootGranted = "権限付与済み (GRANTED)",
        rootDenied = "権限なし (DENIED)",
        rootGrantedSub = "Superuser (su) アクセスは有効です",
        rootDeniedSub = "Magisk / KernelSU / APatch で Root 権限を許可してください",
        vermagicTitle = "カーネル VERMAGIC",
        vermagicDetecting = "Vermagic を検出中...",
        vermagicSub = "端末のカーネル Vermagic シグネチャ",
        supportedTitle = "データベース対応状況",
        supportedYes = "対応: あり (YES)",
        supportedNo = "対応: なし (NO)",
        supportedChecking = "データベース確認中...",
        supportedYesSub = "一致するスクリプト / ドライバがデータベースに存在します",
        supportedNoSub = "未対応のカーネルです — 下記よりレポートを送信してください",
        loadBtn = "LOAD",
        loadingBtn = "ロード中",
        stopBtn = "停止",
        restartBtn = "再起動",
        rebootingBtn = "再起動中",
        driverStatusLoaded = "ロード完了・動作中",
        driverStatusHidden = "ステルスモードでロード済み",
        driverStatusNotLoaded = "未ロード (NOT LOADED)",
        driverStatusLoadedHint = "次回再起動までドライバは有効です",
        driverStatusNotLoadedHint = "上の LOAD ボタンをタップしてロードしてください",
        verifyBtn = "スクリプト＆ドライバ検証",
        verifyingBtn = "検証中...",
        verifyPassText = "検証成功: ドライバは正常に動作しています",
        verifyFailText = "検証失敗: ドライバが有効ではありません",
        customScriptTitle = "カスタム .sh スクリプトローダー",
        customScriptSub = "ストレージから任意の .sh または .ko ドライバを選択して実行",
        customScriptPickBtn = "カスタム .SH を選択",
        customScriptLoadBtn = ".SH スクリプトを実行",
        customScriptChangeBtn = "変更",
        customScriptClearBtn = "クリア",
        statusTerminalTitle = "ステータスターミナル",
        statusTerminalHint = "準備完了 — 状態ログがここに表示されます",
        supportedKernelTerminalTitle = "対応カーネル一覧",
        supportedKernelTerminalSub = "データベース内のスクリプトおよびドライバ一覧",
        reportSectionTitle = "カーネルレポート送信",
        sendTelegramBtn = "Telegram で送信",
        sendWhatsappBtn = "WhatsApp で送信",
        copyReportBtn = "レポート全文をコピー",
        collectingReport = "レポート ZIP 作成中...",
        languageDialogTitle = "言語を選択 (Select Language)",
        autoIpDetectOption = "IP による自動言語検出"
    )

    private val KoStrings = EnStrings.copy(
        langCode = "ko",
        subtitle = "프리미엄 커널 및 스크립트 엔진",
        splashInitializing = "커널 스텔스 엔진 초기화 중...",
        rootTitle = "루트 권한",
        rootGranted = "획득됨",
        rootDenied = "권한 없음",
        rootGrantedSub = "Superuser (su) 접근 활성화됨",
        rootDeniedSub = "Magisk / KernelSU / APatch에서 루트 권한을 허용하세요",
        vermagicTitle = "커널 VERMAGIC",
        vermagicDetecting = "Vermagic 감지 중...",
        vermagicSub = "기기의 커널 Vermagic 시그니처",
        supportedTitle = "데이터베이스",
        supportedYes = "지원됨",
        supportedNo = "미지원",
        supportedChecking = "데이터베이스 확인 중...",
        supportedYesSub = "일치하는 스크립트 / 드라이버가 데이터베이스에 있습니다",
        supportedNoSub = "지원되지 않는 커널입니다 — 아래에서 리포트를 전송하세요",
        loadBtn = "LOAD",
        loadingBtn = "로드 중",
        stopBtn = "정지",
        restartBtn = "재부팅",
        rebootingBtn = "재부팅 중",
        driverStatusLoaded = "로드됨 및 활성화됨",
        driverStatusHidden = "스텔스 모드로 로드됨",
        driverStatusNotLoaded = "로드되지 않음",
        driverStatusLoadedHint = "다음 기기 재부팅 시까지 드라이버가 활성 유지됩니다",
        driverStatusNotLoadedHint = "위의 원형 LOAD 버튼을 눌러 로드하세요",
        verifyBtn = "스크립트 및 드라이버 검증",
        verifyingBtn = "검증 중...",
        verifyPassText = "검증 성공: 드라이버가 정상 작동 중입니다",
        verifyFailText = "검증 실패: 드라이버가 비활성 상태입니다",
        customScriptTitle = "사용자 정의 .sh 스크립트 로더",
        customScriptSub = "저장소에서 커스텀 .sh 또는 .ko 파일을 선택하여 실행",
        customScriptPickBtn = ".SH 스크립트 선택",
        customScriptLoadBtn = ".SH 스크립트 실행",
        customScriptChangeBtn = "변경",
        customScriptClearBtn = "지우기",
        statusTerminalTitle = "상태 터미널",
        statusTerminalHint = "준비 완료 — 모든 로그가 여기에 표시됩니다",
        supportedKernelTerminalTitle = "지원되는 커널 터미널",
        supportedKernelTerminalSub = "데이터베이스의 지원 드라이버 목록",
        reportSectionTitle = "커널 리포트 전송",
        sendTelegramBtn = "Telegram으로 전송",
        sendWhatsappBtn = "WhatsApp으로 전송",
        copyReportBtn = "텍스트 리포트 복사",
        collectingReport = "리포트 ZIP 생성 중...",
        languageDialogTitle = "앱 언어 선택",
        autoIpDetectOption = "IP 기반 국가 자동 감지"
    )

    private val PlStrings = EnStrings.copy(
        langCode = "pl",
        subtitle = "SILNIK KERNELA I SKRYPTÓW PREMIUM",
        splashInitializing = "Inicjalizacja silnika kernel stealth...",
        rootTitle = "UPRAWNIENIA ROOT",
        rootGranted = "UPRAWNIENIA PRZYZNANE",
        rootDenied = "BRAK UPRAWNIEŃ ROOT",
        rootGrantedSub = "Dostęp Superuser (su) aktywny",
        rootDeniedSub = "Przyznaj uprawnienia root w Magisk / KernelSU / APatch",
        vermagicTitle = "VERMAGIC KERNELA",
        vermagicSub = "Krótka sygnatura vermagic jądra",
        supportedTitle = "WSPARCIE W BAZIE DANYCH",
        supportedYes = "OBSŁUGIWANY: TAK",
        supportedNo = "OBSŁUGIWANY: NIE",
        supportedChecking = "SPRAWDZANIE BAZY DANYCH...",
        supportedYesSub = "Kompatybilny sterownik dostępny w bazie",
        supportedNoSub = "Brak sterownika dla tego vermagic — wyślij raport poniżej",
        loadBtn = "LOAD",
        loadingBtn = "ŁADOWANIE",
        stopBtn = "ZATRZYMAJ",
        restartBtn = "RESTART",
        rebootingBtn = "RESTARTOWANIE",
        driverStatusLoaded = "ZAŁADOWANY I AKTYWNY",
        driverStatusHidden = "ZAŁADOWANY W TRYBIE STEALTH",
        driverStatusNotLoaded = "NIE ZAŁADOWANY",
        driverStatusLoadedHint = "Sterownik aktywny do kolejnego restartu telefonu",
        driverStatusNotLoadedHint = "Naciśnij przycisk LOAD powyżej, aby załadować",
        verifyBtn = "WERYFIKUJ SKRYPT I STEROWNIK",
        verifyingBtn = "WERYFIKACJA...",
        verifyPassText = "ZWERYFIKOWANO: STEROWNIK DZIAŁA",
        verifyFailText = "BŁĄD: STEROWNIK NIEAKTYWNY",
        customScriptTitle = "Własny skrypt .sh",
        customScriptSub = "Wybierz i uruchom własny skrypt .sh lub sterownik .ko",
        customScriptPickBtn = "ZAŁADUJ SKRYPT .SH",
        customScriptLoadBtn = "URUCHOM SKRYPT .SH",
        customScriptChangeBtn = "ZMIEŃ",
        customScriptClearBtn = "WYCZYŚĆ",
        statusTerminalTitle = "TERMINAL STATUSU",
        statusTerminalHint = "Gotowy — logi jądra pojawią się tutaj",
        supportedKernelTerminalTitle = "OBSŁUGIWANE KERNELE",
        supportedKernelTerminalSub = "Lista skryptów i sterowników w bazie danych",
        reportSectionTitle = "WYŚLIJ RAPORT KERNELA",
        sendTelegramBtn = "Wyślij przez Telegram",
        sendWhatsappBtn = "Wyślij przez WhatsApp",
        copyReportBtn = "KOPIUJ RAPORT TEKSTOWY",
        collectingReport = "TWORZENIE ZIP RAPORTU...",
        languageDialogTitle = "Wybierz język aplikacji",
        autoIpDetectOption = "Automatyczne wykrywanie wg IP"
    )

    private val NeStrings = EnStrings.copy(
        langCode = "ne",
        subtitle = "प्रिमियम कर्नेल र स्क्रिप्ट इन्जिन",
        splashInitializing = "कर्नेल इन्जिन सुरु हुँदैछ...",
        rootTitle = "रूट अनुमति",
        rootGranted = "दिइयो",
        rootDenied = "छैन",
        rootGrantedSub = "Superuser (su) पहुँच सक्रिय छ",
        rootDeniedSub = "Magisk / KernelSU / APatch बाट रूट अनुमति दिनुहोस्",
        vermagicTitle = "कर्नेल VERMAGIC",
        vermagicSub = "उपकरणको छोटो कर्नेल vermagic हस्ताक्षर",
        supportedTitle = "डाटाबेस",
        supportedYes = "समर्थित",
        supportedNo = "असमर्थित",
        supportedChecking = "डाटाबेस जाँच गर्दै...",
        supportedYesSub = "मिल्दो स्क्रिप्ट / ड्राइभर डाटाबेसमा उपलब्ध छ",
        supportedNoSub = "कुनै ड्राइभर फेला परेन — तल रिपोर्ट पठाउनुहोस्",
        loadBtn = "LOAD",
        loadingBtn = "लोड हुँदैछ",
        stopBtn = "रोक्नुहोस्",
        restartBtn = "पुनः सुरु",
        rebootingBtn = "रिबुट हुँदैछ",
        driverStatusLoaded = "लोड गरियो र सक्रिय",
        driverStatusHidden = "स्टिल्थ मोडमा सक्रिय",
        driverStatusNotLoaded = "लोड गरिएको छैन",
        driverStatusLoadedHint = "अर्को रिबुट नभएसम्म मोड्युल सक्रिय रहन्छ",
        driverStatusNotLoadedHint = "लोड गर्न माथिको LOAD बटन थिच्नुहोस्",
        verifyBtn = "स्क्रिप्ट र ड्राइभर प्रमाणीकरण",
        verifyingBtn = "जाँच गर्दै...",
        verifyPassText = "सफल: ड्राइभर काम गर्दैछ",
        verifyFailText = "असफल: ड्राइभर सक्रिय छैन",
        customScriptTitle = "कस्टम .sh स्क्रिप्ट लोडर",
        customScriptSub = "आफ्नो .sh वा .ko ड्राइभर चयन गरी चलाउनुहोस्",
        customScriptPickBtn = ".SH स्क्रिप्ट छान्नुहोस्",
        customScriptLoadBtn = ".SH स्क्रिप्ट चलाउनुहोस्",
        customScriptChangeBtn = "परिवर्तन",
        customScriptClearBtn = "हटाउनुहोस्",
        statusTerminalTitle = "स्थिति टर्मिनल",
        statusTerminalHint = "तयार — स्थिति यहाँ देखिनेछ",
        supportedKernelTerminalTitle = "समर्थित कर्नेल सूची",
        supportedKernelTerminalSub = "डाटाबेसमा भएका सबै स्क्रिप्ट र ड्राइभरहरू",
        reportSectionTitle = "कर्नेल रिपोर्ट पठाउनुहोस्",
        sendTelegramBtn = "Telegram मार्फत पठाउनुहोस्",
        sendWhatsappBtn = "WhatsApp मार्फत पठाउनुहोस्",
        copyReportBtn = "रिपोर्ट प्रतिलिपि गर्नुहोस्",
        collectingReport = "रिपोर्ट तयार गर्दै...",
        languageDialogTitle = "भाषा छान्नुहोस्",
        autoIpDetectOption = "IP अनुसार स्वतः भाषा चयन"
    )

    private val MyStrings = EnStrings.copy(
        langCode = "my",
        subtitle = "ပရီမီယံ Kernel နှင့် Script အင်ဂျင်",
        splashInitializing = "Kernel အင်ဂျင် စတင်နေပါသည်...",
        rootTitle = "ROOT ခွင့်ပြုချက်",
        rootGranted = "ခွင့်ပြုချက် ရရှိပြီး",
        rootDenied = "ခွင့်ပြုချက် မရှိပါ",
        rootGrantedSub = "Superuser (su) လုပ်ဆောင်နေပါသည်",
        rootDeniedSub = "Magisk / KernelSU / APatch တွင် Root ခွင့်ပြုချက်ပေးပါ",
        vermagicTitle = "KERNEL VERMAGIC",
        vermagicSub = "စက်၏ Kernel Vermagic အတိုကောက်",
        supportedTitle = "ဒေတာဘေ့စ် ထောက်ပံ့မှု",
        supportedYes = "ထောက်ပံ့ထားသည်: ဟုတ်ကဲ့",
        supportedNo = "မထောက်ပံ့သေးပါ: မရှိပါ",
        supportedChecking = "ဒေတာဘေ့စ် စစ်ဆေးနေသည်...",
        supportedYesSub = "ကိုက်ညီသော ဖိုင် ဒေတာဘေ့စ်တွင် ရှိပါသည်",
        supportedNoSub = "ဖိုင်မတွေ့ပါ — အောက်တွင် အစီရင်ခံစာ ပို့ပါ",
        loadBtn = "LOAD",
        loadingBtn = "စတင်နေသည်",
        stopBtn = "ရပ်တန့်မည်",
        restartBtn = "ပြန်လည်စတင်မည်",
        rebootingBtn = "ပြန်လည်စတင်နေသည်",
        driverStatusLoaded = "ဖွင့်ထားပြီး အလုပ်လုပ်နေသည်",
        driverStatusHidden = "Stealth စနစ်ဖြင့် ဖွင့်ထားသည်",
        driverStatusNotLoaded = "မဖွင့်ရသေးပါ",
        driverStatusLoadedHint = "ဖုန်းပြန်မပိတ်မချင်း အလုပ်လုပ်နေမည်ဖြစ်သည်",
        driverStatusNotLoadedHint = "ဖွင့်ရန် အပေါ်ရှိ LOAD ခလုတ်ကို နှိပ်ပါ",
        verifyBtn = "ဖိုင်များ စစ်ဆေးမည်",
        verifyingBtn = "စစ်ဆေးနေပါသည်...",
        verifyPassText = "စစ်ဆေးပြီး: အလုပ်လုပ်နေပါသည်",
        verifyFailText = "စစ်ဆေးမှု မအောင်မြင်ပါ",
        customScriptTitle = "စိတ်ကြိုက် .sh ဖိုင် တင်ရန်",
        customScriptSub = "မိမိစိတ်ကြိုက် .sh သို့မဟုတ် .ko ဖိုင်ကို ရွေးချယ် run ပါ",
        customScriptPickBtn = ".SH ဖိုင်ရွေးချယ်ရန်",
        customScriptLoadBtn = ".SH ဖိုင် စတင်ရန်",
        customScriptChangeBtn = "ပြောင်းလဲမည်",
        customScriptClearBtn = "ဖျက်မည်",
        statusTerminalTitle = "အခြေအနေပြ မျက်နှာပြင်",
        statusTerminalHint = "အသင့်ဖြစ်နေပါသည် — အချက်အလက်များ ဤနေရာတွင် ပေါ်လာမည်",
        supportedKernelTerminalTitle = "ထောက်ပံ့ထားသော Kernel များ",
        supportedKernelTerminalSub = "ဒေတာဘေ့စ်ရှိ စာရင်းများ",
        reportSectionTitle = "Kernel အစီရင်ခံစာ ပို့ရန်",
        sendTelegramBtn = "Telegram ဖြင့် ပို့မည်",
        sendWhatsappBtn = "WhatsApp ဖြင့် ပို့မည်",
        copyReportBtn = "စာသား အစီရင်ခံစာ ကူးယူမည်",
        collectingReport = "ZIP ဖိုင် ဖန်တီးနေပါသည်...",
        languageDialogTitle = "ဘာသာစကား ရွေးချယ်ပါ",
        autoIpDetectOption = "IP အလိုက် အလိုအလျောက် ရွေးချယ်ရန်"
    )

    private val KmStrings = EnStrings.copy(
        langCode = "km",
        subtitle = "ម៉ាស៊ីន Kernel និង Script កម្រិតខ្ពស់",
        splashInitializing = "កំពុងដំណើរការម៉ាស៊ីន Kernel...",
        rootTitle = "ការអនុញ្ញាត ROOT",
        rootGranted = "បានអនុញ្ញាត",
        rootDenied = "មិនទាន់អនុញ្ញាត",
        rootGrantedSub = "ការចូលប្រើប្រាស់ Superuser (su) សកម្ម",
        rootDeniedSub = "សូមផ្ដល់សិទ្ធិ root ក្នុង Magisk / KernelSU / APatch",
        vermagicTitle = "KERNEL VERMAGIC",
        vermagicSub = "ហត្ថលេខាខ្លី vermagic របស់ឧបករណ៍",
        supportedTitle = "ការគាំទ្រក្នុងទិន្នន័យ",
        supportedYes = "គាំទ្រ: បាទ/ចាស (YES)",
        supportedNo = "មិនទាន់គាំទ្រ (NO)",
        supportedChecking = "កំពុងពិនិត្យទិន្នន័យ...",
        supportedYesSub = "មាន Driver ត្រឹមត្រូវនៅក្នុងទិន្នន័យ",
        supportedNoSub = "រកមិនឃើញ Driver — សូមផ្ញើរបាយការណ៍",
        loadBtn = "LOAD",
        loadingBtn = "កំពុងផ្ទុក",
        stopBtn = "បញ្ឈប់",
        restartBtn = "ចាប់ផ្ដើមឡើងវិញ",
        rebootingBtn = "កំពុងចាប់ផ្ដើមឡើងវិញ",
        driverStatusLoaded = "បានផ្ទុក & សកម្ម",
        driverStatusHidden = "បានផ្ទុកក្នុងទម្រង់ Stealth",
        driverStatusNotLoaded = "មិនទាន់បានផ្ទុក",
        driverStatusLoadedHint = "Driver នឹងដំណើរការរហូតដល់ទូរស័ព្ទ restart លើកក្រោយ",
        driverStatusNotLoadedHint = "ចុចលើប៊ូតុង LOAD ខាងលើដើម្បីផ្ទុក",
        verifyBtn = "ផ្ទៀងផ្ទាត់ Script & Driver",
        verifyingBtn = "កំពុងផ្ទៀងផ្ទាត់...",
        verifyPassText = "ជោគជ័យ: Driver ដំណើរការធម្មតា",
        verifyFailText = "បរាជ័យ: Driver មិនទាន់ដំណើរការ",
        customScriptTitle = "កម្មវិធីផ្ទុក Script .sh",
        customScriptSub = "ជ្រើសរើស និងដំណើរការឯកសារ .sh ឬ .ko ជាមួយ root",
        customScriptPickBtn = "ជ្រើសរើស Script .SH",
        customScriptLoadBtn = "ដំណើរការ Script .SH ឥឡូវនេះ",
        customScriptChangeBtn = "ផ្លាស់ប្ដូរ",
        customScriptClearBtn = "សម្អាត",
        statusTerminalTitle = "ស្ថានីយស្ថានភាព",
        statusTerminalHint = "រួចរាល់ — ស្ថានភាពទាំងអស់នឹងបង្ហាញនៅទីនេះ",
        supportedKernelTerminalTitle = "Kernel ដែលគាំទ្រ",
        supportedKernelTerminalSub = "បញ្ជីឯកសារនៅក្នុងទិន្នន័យ",
        reportSectionTitle = "ផ្ញើរបាយការណ៍ Kernel",
        sendTelegramBtn = "ផ្ញើតាម Telegram",
        sendWhatsappBtn = "ផ្ញើតាម WhatsApp",
        copyReportBtn = "ចម្លងរបាយការណ៍",
        collectingReport = "កំពុងបង្កើត ZIP...",
        languageDialogTitle = "ជ្រើសរើសភាសា",
        autoIpDetectOption = "កំណត់ភាសាស្វ័យប្រវត្តិតាម IP"
    )

    private val SiStrings = EnStrings.copy(
        langCode = "si",
        subtitle = "ප්‍රිමියම් කර්නල් සහ ස්ක්‍රිප්ට් එන්ජිම",
        splashInitializing = "කර්නල් එන්ජිම ආරම්භ වෙමින් පවතී...",
        rootTitle = "ROOT අවසරය",
        rootGranted = "අවසරය ලබා දී ඇත",
        rootDenied = "අවසරය ලබා දී නැත",
        rootGrantedSub = "Superuser (su) ප්‍රවේශය සක්‍රියයි",
        rootDeniedSub = "Magisk / KernelSU / APatch වෙතින් root අවසර දෙන්න",
        vermagicTitle = "කර්නල් VERMAGIC",
        vermagicSub = "උපාංගයේ කෙටි කර්නල් vermagic අත්සන",
        supportedTitle = "දත්ත සමුදාය සහාය",
        supportedYes = "සහාය දක්වයි: ඔව්",
        supportedNo = "සහාය නොදක්වයි: නැත",
        supportedChecking = "දත්ත සමුදාය පරීක්ෂා කරමින්...",
        supportedYesSub = "ගැලපෙන ධාවකය දත්ත සමුදායේ ඇත",
        supportedNoSub = "ධාවකයක් හමු නොවීය — පහතින් වාර්තාවක් එවන්න",
        loadBtn = "LOAD",
        loadingBtn = "පූරණය වෙමින්",
        stopBtn = "නවත්වන්න",
        restartBtn = "නැවත අරඹන්න",
        rebootingBtn = "නැවත පණගැන්වෙමින්",
        driverStatusLoaded = "පූරණය කර ඇති අතර සක්‍රියයි",
        driverStatusHidden = "Stealth ආකාරයෙන් සක්‍රියයි",
        driverStatusNotLoaded = "පූරණය කර නැත",
        driverStatusLoadedHint = "ඊළඟ නැවත පණගැන්වීම තෙක් ධාවකය සක්‍රියව පවතී",
        driverStatusNotLoadedHint = "පූරණය කිරීමට ඉහත LOAD බොත්තම ඔබන්න",
        verifyBtn = "ස්ක්‍රිප්ට් සහ ධාවකය තහවුරු කරන්න",
        verifyingBtn = "තහවුරු කරමින්...",
        verifyPassText = "සාර්ථකයි: ධාවකය ක්‍රියාත්මකයි",
        verifyFailText = "අසාර්ථකයි: ධාවකය අක්‍රියයි",
        customScriptTitle = "අභිරුචි .sh ස්ක්‍රිප්ට් ලෝඩරය",
        customScriptSub = "root අවසර සහිතව .sh හෝ .ko ගොනු ධාවනය කරන්න",
        customScriptPickBtn = ".SH ස්ක්‍රිප්ට් තෝරන්න",
        customScriptLoadBtn = ".SH ස්ක්‍රිප්ට් ක්‍රියාත්මක කරන්න",
        customScriptChangeBtn = "වෙනස් කරන්න",
        customScriptClearBtn = "මකන්න",
        statusTerminalTitle = "තත්ව පර්යන්තය",
        statusTerminalHint = "සූදානම් — තත්ව විස්තර මෙහි දිස්වනු ඇත",
        supportedKernelTerminalTitle = "සහාය දක්වන කර්නල් ලැයිස්තුව",
        supportedKernelTerminalSub = "දත්ත සමුදායේ ඇති සියලුම ධාවක ලැයිස්තුව",
        reportSectionTitle = "කර්නල් වාර්තාව යවන්න",
        sendTelegramBtn = "Telegram හරහා යවන්න",
        sendWhatsappBtn = "WhatsApp හරහා යවන්න",
        copyReportBtn = "සම්පූර්ණ වාර්තාව පිටපත් කරන්න",
        collectingReport = "වාර්තා ZIP සකසමින්...",
        languageDialogTitle = "භාෂාව තෝරන්න",
        autoIpDetectOption = "IP අනුව ස්වයංක්‍රීය භාෂාව"
    )
}
