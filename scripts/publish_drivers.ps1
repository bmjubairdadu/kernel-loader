# ============================================================================
# Publishes the built .ko driver database to GitHub ('drivers' branch).
# The Android app then downloads matching loaders from:
#   https://raw.githubusercontent.com/bmjubairdadu/kernel-loader/drivers/drivers.json
#
# Bash needs LF line endings, so a LF copy is made first.
# ============================================================================
$proj = "c:\Users\Administrator\Downloads\DaisyDiverLoder"
$src  = Join-Path $proj "driver\publish_drivers.sh"
$lf   = Join-Path $proj "driver\publish_lf.sh"

Write-Host "=== Kernel Loder: publishing driver database to GitHub ==="
$lfContent = [IO.File]::ReadAllText($src) -replace "`r", ""
[IO.File]::WriteAllText($lf, $lfContent, (New-Object Text.UTF8Encoding($false)))

wsl -d Ubuntu-22.04 -u root -- bash /mnt/c/Users/Administrator/Downloads/DaisyDiverLoder/driver/publish_lf.sh
Write-Host "=== Done. App manifest URL: https://raw.githubusercontent.com/bmjubairdadu/kernel-loader/drivers/drivers.json ==="
