# update-fork.ps1 — Aktualisiert den privaten Telegram-Fold7-Fork auf den neuesten
# offiziellen Stand (DrKLO/Telegram master), wendet die eigenen Patches per Rebase an,
# baut die APK und legt sie unter releases\ ab.
#
# Aufruf:   .\update-fork.ps1            nur pruefen + bauen, wenn Update vorhanden
#           .\update-fork.ps1 -Install   danach direkt per ADB auf dem Fold installieren
#           .\update-fork.ps1 -Force     bauen, auch wenn kein neuer Upstream-Stand da ist
param(
    [switch]$Install,
    [switch]$Force
)

## Hinweis: git/adb schreiben Statusmeldungen auf stderr; mit "Stop" wuerde
## PowerShell 5.1 das faelschlich als Fehler werten. Exit-Codes werden daher
## nach jedem kritischen Schritt manuell geprueft.
$ErrorActionPreference = "Continue"
$repo = "C:\Projekte\Telegram"
$jdkRoot = Join-Path $repo ".local-jdk"
$jdk = (Get-ChildItem $jdkRoot -Directory | Where-Object Name -like "jdk-*" | Select-Object -First 1).FullName
if (-not $jdk) { Write-Host "FEHLER: Kein JDK unter $jdkRoot gefunden." -ForegroundColor Red; exit 1 }

Set-Location $repo

# 0) Arbeitsverzeichnis muss sauber sein (sonst wuerde der Rebase lokale Aenderungen gefaehrden)
$dirty = git status --porcelain
if ($dirty) {
    Write-Host "FEHLER: Es gibt uncommittete Aenderungen im Repo - bitte erst committen/verwerfen:" -ForegroundColor Red
    $dirty | ForEach-Object { Write-Host "  $_" }
    exit 1
}

# 1) Neuesten offiziellen Stand holen
Write-Host "Pruefe auf neue Telegram-Version..." -ForegroundColor Cyan
git fetch --depth 1 --quiet origin master
if ($LASTEXITCODE -ne 0) { Write-Host "FEHLER: git fetch fehlgeschlagen (Internet? GitHub erreichbar?)." -ForegroundColor Red; exit 1 }
$newHead = (git rev-parse FETCH_HEAD).Trim()
$oldBase = (git rev-parse "fold7-base^{commit}").Trim()
$newVersionMsg = (git log -1 --format=%s FETCH_HEAD).Trim()

if ($newHead -eq $oldBase -and -not $Force) {
    Write-Host "Kein Update: Upstream ist unveraendert ($newVersionMsg)." -ForegroundColor Green
    exit 0
}

if ($newHead -ne $oldBase) {
    Write-Host "Neuer Upstream-Stand: $newVersionMsg" -ForegroundColor Yellow

    # 2) Eigene Patches auf den neuen Stand rebasen
    Write-Host "Wende eigene Patches an (Rebase)..." -ForegroundColor Cyan
    git checkout --quiet fold7
    git rebase --onto $newHead fold7-base fold7
    if ($LASTEXITCODE -ne 0) {
        git rebase --abort
        Write-Host "FEHLER: Rebase-Konflikt - der neue Telegram-Code kollidiert mit unseren Patches." -ForegroundColor Red
        Write-Host "Loesung: Claude im Projekt C:\Projekte\Telegram bitten, den Konflikt aufzuloesen." -ForegroundColor Red
        exit 1
    }

    # 3) Basis-Tag nachziehen und Submodule aktualisieren
    git tag -f fold7-base $newHead | Out-Null
    Write-Host "Aktualisiere Submodule..." -ForegroundColor Cyan
    git submodule update --init --recursive --depth 1 --jobs 4
}

# 4) Version aus gradle.properties lesen
$verName = ((Get-Content "$repo\gradle.properties") | Where-Object { $_ -match "^APP_VERSION_NAME=" }) -replace "APP_VERSION_NAME=", ""
Write-Host "Baue Telegram $verName (arm64, privat signiert)..." -ForegroundColor Cyan

# 5) Build
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
& "$jdk\bin\java.exe" -cp "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain :TMessagesProj_AppStandalone:assembleAfatStandalone
if ($LASTEXITCODE -ne 0) {
    Write-Host "FEHLER: Build fehlgeschlagen - Log oben pruefen oder Claude fragen." -ForegroundColor Red
    exit 1
}

# 6) APK ablegen
if (-not (Test-Path "$repo\releases")) { New-Item -ItemType Directory "$repo\releases" | Out-Null }
$apkSrc = "$repo\TMessagesProj_AppStandalone\build\outputs\apk\afat\standalone\app.apk"
$apkDst = "$repo\releases\Telegram-$verName-fold7-arm64.apk"
Copy-Item $apkSrc $apkDst -Force
Write-Host "Fertig: $apkDst" -ForegroundColor Green

# 7) Optional direkt installieren
if ($Install) {
    Write-Host "Suche Fold per ADB..." -ForegroundColor Cyan
    $fold = $null
    foreach ($line in (adb devices | Select-Object -Skip 1)) {
        $serial = ($line -split "\s+")[0]
        if (-not $serial) { continue }
        $model = (adb -s $serial shell getprop ro.product.model)
        if ($model -match "SM-F9") { $fold = $serial; break }
    }
    if ($fold) {
        Write-Host "Installiere auf $fold..." -ForegroundColor Cyan
        adb -s $fold install -r $apkDst
    } else {
        Write-Host "Kein Fold verbunden - APK liegt unter releases\ bereit." -ForegroundColor Yellow
    }
}
