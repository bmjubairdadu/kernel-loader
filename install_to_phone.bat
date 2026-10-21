@echo off
chcp 65001 >nul
title Kernel Loader APK Installer
color 0A

echo ========================================================
echo        Kernel Loader - Phone APK Installer
echo ========================================================
echo.

set "ADB_BIN=adb"
where adb >nul 2>nul
if %errorlevel% neq 0 (
    if exist "C:\AndroidSdk\platform-tools\adb.exe" (
        set "ADB_BIN=C:\AndroidSdk\platform-tools\adb.exe"
    ) else (
        color 0C
        echo [ERROR] ADB পাওয়া যায়নি!
        echo অনুগ্রহ করে আপনার Android SDK বা Platform Tools চেক করুন।
        echo.
        pause
        exit /b 1
    )
)

echo [1/3] কানেক্টেড ডিভাইস চেক করা হচ্ছে...
"%ADB_BIN%" devices
echo.

set "APK_FILE=%~dp0app\build\outputs\apk\release\app-release.apk"
if not exist "%APK_FILE%" (
    set "APK_FILE=%~dp0app\build\outputs\apk\debug\app-debug.apk"
)
if not exist "%APK_FILE%" (
    color 0C
    echo [ERROR] APK ফাইল পাওয়া যায়নি!
    echo অনুগ্রহ করে আগে APK বিল্ড করুন।
    echo.
    pause
    exit /b 1
)

echo [2/3] ফোনে ইন্সটল করা হচ্ছে: %APK_FILE%
echo অনুগ্রহ করে ফোনের স্ক্রিনে কোনো পারমিশন প্রম্পট আসলে 'Allow' বা 'Install' দিন...
echo.

"%ADB_BIN%" install -r -d "%APK_FILE%"
if %errorlevel% equ 0 (
    echo.
    echo ========================================================
    echo    [SUCCESS] অ্যাপ্লিকেশন সফলভাবে ইন্সটল হয়েছে!
    echo ========================================================
    echo.
    echo অ্যাপটি লঞ্চ করা হচ্ছে...
    "%ADB_BIN%" shell am start -n com.kernelloader/.MainActivity >nul 2>nul
) else (
    color 0C
    echo.
    echo ========================================================
    echo    [FAILED] ইন্সটল ব্যর্থ হয়েছে!
    echo ========================================================
    echo কারণ হতে পারে:
    echo 1. USB Debugging অন করা নেই বা স্ক্রিনে Allow দেওয়া হয়নি।
    echo 2. ফোনটি ক্যাবল দিয়ে কানেক্ট করা নেই।
    echo.
)

pause
