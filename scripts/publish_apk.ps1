# Publishes the Kernel Loader release APK as a GitHub Release asset (Windows / PowerShell)
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/publish_apk.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/publish_apk.ps1 -Tag v8
#   powershell -ExecutionPolicy Bypass -File scripts/publish_apk.ps1 -Tag v8 -ApkPath app\build\outputs\apk\release\app-release.apk -Title "Kernel Loader v2.3" -Notes "OTA kernel loader release"
#
# Requirements: GitHub CLI (gh) installed and authenticated (gh auth login).
# The asset is uploaded as "Kernel-Loader-v<versionName>.apk" (the versionName
# is read from app/build.gradle.kts). The in-app auto-updater
# (AppUpdateChecker -> releases/latest API) accepts any .apk asset.
param(
    [string]$Tag = "v8",
    [string]$ApkPath = "",
    [string]$Title = "",
    [string]$Notes = "",
    [string]$Repo = "bmjubairdadu/kernel-loader"
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
}
if (-not (Test-Path $ApkPath)) {
    Write-Error "APK not found: $ApkPath. Build it first: .\gradlew.bat :app:assembleRelease"
}
$apkItem = Get-Item $ApkPath
Write-Host ("APK: {0} ({1:N2} MB)" -f $apkItem.FullName, ($apkItem.Length / 1MB))

# Upload under the branded asset name used by every release:
# Kernel-Loader-v<versionName>.apk (matches the v2.0 release naming).
$gradleFile = Join-Path $root 'app\build.gradle.kts'
$VersionName = ""
if (Test-Path $gradleFile) {
    $gradleText = Get-Content $gradleFile -Raw
    if ($gradleText -match 'versionName\s*=\s*"([^"]+)"') { $VersionName = $Matches[1] }
}
if ([string]::IsNullOrWhiteSpace($VersionName)) {
    Write-Error "Could not read versionName from app\build.gradle.kts"
}
$assetName = "Kernel-Loader-v$VersionName.apk"
$assetPath = $apkItem.FullName
if ($apkItem.Name -ne $assetName) {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) 'kernelloader-publish'
    New-Item -ItemType Directory -Path $tmp -Force | Out-Null
    $assetPath = Join-Path $tmp $assetName
    Copy-Item $apkItem.FullName $assetPath -Force
    Write-Host "Renamed asset copy -> $assetPath"
}

$haveGh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $haveGh) {
    Write-Error "GitHub CLI (gh) not found in PATH. Install it from https://cli.github.com/ and run: gh auth login"
}
& gh auth status --hostname github.com
if ($LASTEXITCODE -ne 0) {
    Write-Error "gh is not authenticated. Run: gh auth login"
}

if ([string]::IsNullOrWhiteSpace($Title)) { $Title = "Kernel Loader $Tag" }
if ([string]::IsNullOrWhiteSpace($Notes)) {
    $Notes = "Kernel Loader $Tag. OTA kernel loader with the RT → QX → built-in load pipeline and one-tap build reports."
}

$existing = & gh release view $Tag --repo $Repo --json tagName 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Host "Release $Tag already exists - uploading asset (clobber)..."
    & gh release upload $Tag $assetPath --repo $Repo --clobber
    if ($LASTEXITCODE -ne 0) { Write-Error "gh release upload failed (exit $LASTEXITCODE)" }
} else {
    Write-Host "Creating public release $Tag ..."
    & gh release create $Tag $assetPath --repo $Repo --title $Title --notes $Notes --latest
    if ($LASTEXITCODE -ne 0) { Write-Error "gh release create failed (exit $LASTEXITCODE)" }
}

Write-Host ""
Write-Host "Release published. Verifying..." -ForegroundColor Green
& gh release view $Tag --repo $Repo --json tagName,name,isLatest,assets --jq '{tag: .tagName, title: .name, latest: .isLatest, assets: [.assets[].name]}'
Write-Host ""
Write-Host "Latest API:" -ForegroundColor Green
& gh api ("repos/{0}/releases/latest" -f $Repo) --jq '{tag: .tag_name, assets: [.assets[].name]}'
