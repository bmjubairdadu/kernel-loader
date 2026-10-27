@echo off
chcp 65001 >nul
title Kernel Loader - Build Release APK
color 0A

echo ========================================================
echo        Building Kernel Loader Release APK
echo ========================================================
echo.

cd /d "%~dp0"

echo [1/2] Gradle assembleRelease শুরু হচ্ছে...
echo.

call gradlew.bat :app:assembleRelease --console=plain

if %errorlevel% equ 0 (
    echo.
    echo ========================================================
    echo    [SUCCESS] Release APK সফলভাবে তৈরি হয়েছে!
    echo ========================================================
    echo.
    echo Release APK লোকেশন:
    echo %~dp0app\build\outputs\apk\release\Kernel-Loader-v2.1.apk
    echo.
) else (
    color 0C
    echo.
    echo ========================================================
    echo    [FAILED] বিল্ড ব্যর্থ হয়েছে! (Exit code: %errorlevel%)
    echo ========================================================
    echo.
)

pause
