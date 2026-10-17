# update-fork.ps1 - Updates this Fold fork to the latest official Telegram source
# (DrKLO/Telegram master), re-applies the fork's patches via rebase, builds the APK
# and stores it in releases\.
#
# Usage:   .\update-fork.ps1            check for an update, build if there is one
#          .\update-fork.ps1 -Install   additionally install on a connected Galaxy Fold via ADB
#          .\update-fork.ps1 -Force     build even if upstream hasn't changed
#
# JDK: uses .local-jdk\jdk-* inside the repo if present, otherwise JAVA_HOME.
param(
    [switch]$Install,
    [switch]$Force
)

# git/adb write progress to stderr; with "Stop", PowerShell 5.1 would treat that
# as an error. Exit codes are checked manually after each critical step instead.
$ErrorActionPreference = "Continue"
$repo = $PSScriptRoot

$jdk = $null
$jdkRoot = Join-Path $repo ".local-jdk"
if (Test-Path $jdkRoot) {
    $jdk = (Get-ChildItem $jdkRoot -Directory | Where-Object Name -like "jdk-*" | Select-Object -First 1).FullName
}
if (-not $jdk -and $env:JAVA_HOME) { $jdk = $env:JAVA_HOME }
if (-not $jdk -or -not (Test-Path "$jdk\bin\java.exe")) {
    Write-Host "ERROR: No JDK found. Install JDK 17-21 and set JAVA_HOME, or put one into .local-jdk\." -ForegroundColor Red
    exit 1
}

Set-Location $repo

# 0) Working tree must be clean, otherwise the rebase could clobber local changes
$dirty = git status --porcelain
if ($dirty) {
    Write-Host "ERROR: Uncommitted changes in the repo - commit or discard them first:" -ForegroundColor Red
    $dirty | ForEach-Object { Write-Host "  $_" }
    exit 1
}

# 1) Fetch the latest official source
Write-Host "Checking for a new Telegram version..." -ForegroundColor Cyan
$upstreamUrl = "https://github.com/DrKLO/Telegram.git"
git fetch --depth 1 --quiet $upstreamUrl master
if ($LASTEXITCODE -ne 0) { Write-Host "ERROR: git fetch failed (internet / GitHub reachable?)." -ForegroundColor Red; exit 1 }
$newHead = (git rev-parse FETCH_HEAD).Trim()
$oldBase = (git rev-parse "fold7-base^{commit}").Trim()
$newVersionMsg = (git log -1 --format=%s FETCH_HEAD).Trim()

if ($newHead -eq $oldBase -and -not $Force) {
    Write-Host "No update: upstream is unchanged ($newVersionMsg)." -ForegroundColor Green
    exit 0
}

if ($newHead -ne $oldBase) {
    Write-Host "New upstream version: $newVersionMsg" -ForegroundColor Yellow

    # 2) Rebase the fork's patches onto the new upstream
    Write-Host "Re-applying fork patches (rebase)..." -ForegroundColor Cyan
    git checkout --quiet fold7
    git rebase --onto $newHead fold7-base fold7
    if ($LASTEXITCODE -ne 0) {
        git rebase --abort
        Write-Host "ERROR: Rebase conflict - the new Telegram code collides with the fork's patches." -ForegroundColor Red
        Write-Host "Resolve the conflict manually (git rebase --onto $newHead fold7-base fold7) and run again." -ForegroundColor Red
        exit 1
    }

    # 3) Move the base tag and update submodules
    git tag -f fold7-base $newHead | Out-Null
    Write-Host "Updating submodules..." -ForegroundColor Cyan
    git submodule update --init --recursive --depth 1 --jobs 4
}

# 4) Read the version from gradle.properties
$verName = ((Get-Content "$repo\gradle.properties") | Where-Object { $_ -match "^APP_VERSION_NAME=" }) -replace "APP_VERSION_NAME=", ""
Write-Host "Building Telegram $verName (arm64)..." -ForegroundColor Cyan

# 5) Build
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
& "$jdk\bin\java.exe" -cp "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain :TMessagesProj_AppStandalone:assembleAfatStandalone
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Build failed - see the log above." -ForegroundColor Red
    exit 1
}

# 6) Store the APK
if (-not (Test-Path "$repo\releases")) { New-Item -ItemType Directory "$repo\releases" | Out-Null }
$apkSrc = "$repo\TMessagesProj_AppStandalone\build\outputs\apk\afat\standalone\app.apk"
$apkDst = "$repo\releases\Telegram-$verName-fold7-arm64.apk"
Copy-Item $apkSrc $apkDst -Force
Write-Host "Done: $apkDst" -ForegroundColor Green

# 7) Optionally install
if ($Install) {
    Write-Host "Looking for a Galaxy Fold via ADB..." -ForegroundColor Cyan
    $fold = $null
    foreach ($line in (adb devices | Select-Object -Skip 1)) {
        $serial = ($line -split "\s+")[0]
        if (-not $serial) { continue }
        $model = (adb -s $serial shell getprop ro.product.model)
        if ($model -match "SM-F9") { $fold = $serial; break }
    }
    if ($fold) {
        Write-Host "Installing on $fold..." -ForegroundColor Cyan
        adb -s $fold install -r $apkDst
    } else {
        Write-Host "No Galaxy Fold connected - the APK is in releases\." -ForegroundColor Yellow
    }
}
