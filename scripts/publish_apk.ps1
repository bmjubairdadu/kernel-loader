# Publishes the Kernel Loder release APK as a GitHub Release asset (Windows / PowerShell)
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/publish_apk.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/publish_apk.ps1 -Tag v8
#   powershell -ExecutionPolicy Bypass -File scripts/publish_apk.ps1 -Tag v8 -ApkPath app\build\outputs\apk\release\app-release.apk -Title "Kernel Loder v2.3" -Notes "OTA kernel loader release"
#
# Requirements: GitHub CLI (gh) installed and authenticated (gh auth login).
# The asset name on the release MUST be exactly "app-release.apk" so the
# in-app auto-updater (AppUpdateChecker -> releases/latest API) keeps working.
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

# The upload asset filename must be exactly app-release.apk.
$assetPath = $apkItem.FullName
if ($apkItem.Name -ne 'app-release.apk') {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) 'kernelloader-publish'
    New-Item -ItemType Directory -Path $tmp -Force | Out-Null
    $assetPath = Join-Path $tmp 'app-release.apk'
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

if ([string]::IsNullOrWhiteSpace($Title)) { $Title = "Kernel Loder $Tag" }
if ([string]::IsNullOrWhiteSpace($Notes)) {
    $Notes = "Kernel Loder $Tag (versionName 2.3-universal, versionCode 8). OTA kernel loader with SafetyGuard force-load protection."
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
