# wow cam — on-device smoke test (Windows PowerShell)
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\device-smoke-test.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\device-smoke-test.ps1 -Serial R58M123ABC
#
# Requires: USB debugging enabled + one physical device visible in `adb devices`.
# Emulators are not used here because CameraX video recording is unreliable on
# emulated camera HALs; this script is meant for a real phone.

param(
    [string]$Serial = "",
    [string]$Apk = ""
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot

function Invoke-Adb {
    $adbExe = "C:\platform-tools\adb.exe"
    $oldPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($script:Serial) {
            $out = & $adbExe -s $script:Serial @args 2>&1
        } else {
            $out = & $adbExe @args 2>&1
        }
    } finally {
        $ErrorActionPreference = $oldPref
    }
    
    # Ignore harmless MIUI theme warnings on old Redmi devices
    $realErrors = @($out | Where-Object { $_ -notmatch "theme_compatibility\.xml" -and $_ -notmatch "ENOENT" })
    if ($LASTEXITCODE -ne 0 -and $realErrors.Count -gt 0) {
        throw "adb failed"
    }
    return $out
}

function Save-Screenshot {
    param([string]$Name)
    $remote = "/sdcard/wowcam-$Name.png"
    Invoke-Adb shell screencap -p $remote | Out-Null
    Invoke-Adb pull $remote (Join-Path $script:Artifacts "$Name.png") | Out-Null
    Invoke-Adb shell rm $remote | Out-Null
    Write-Host "  screenshot: $Name.png"
}

function Get-UiXml {
    Invoke-Adb shell uiautomator dump /sdcard/wowcam-ui.xml | Out-Null
    return (Invoke-Adb shell cat /sdcard/wowcam-ui.xml) -join "`n"
}

function Get-NodeCenter {
    param([string]$Id)
    $xml = Get-UiXml
    $pattern = '<node[^>]*resource-id="com\.wowcam\.app:id/' + [regex]::Escape($Id) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($xml, $pattern)
    if (-not $m.Success) {
        throw "UI node '$Id' not found. Current screen may not be the camera screen."
    }
    $x1 = [int]$m.Groups[1].Value
    $y1 = [int]$m.Groups[2].Value
    $x2 = [int]$m.Groups[3].Value
    $y2 = [int]$m.Groups[4].Value
    return @([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2), $x1, $y1, $x2, $y2)
}

function Tap-Node {
    param([string]$Id)
    $c = Get-NodeCenter $Id
    Invoke-Adb shell input tap $c[0] $c[1] | Out-Null
    Start-Sleep -Milliseconds 900
}

function Swipe-Zoom {
    $c = Get-NodeCenter "zoomSlider"
    $y = [int](($c[3] + $c[5]) / 2)
    $startX = [int]($c[2] + ($c[4] - $c[2]) * 0.15)
    $endX = [int]($c[2] + ($c[4] - $c[2]) * 0.85)
    Invoke-Adb shell input swipe $startX $y $endX $y 450 | Out-Null
    Start-Sleep -Milliseconds 600
}

# --- setup -------------------------------------------------------------------
$script:Adb = "C:\platform-tools\adb.exe"
if (-not $Apk) {
    $Apk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
}
if (-not (Test-Path $Apk)) {
    throw "APK not found: $Apk`nRun: gradlew assembleDebug"
}

$devices = & $script:Adb devices
$online = @($devices | Select-String -Pattern '^\S+\s+device$')
if ($online.Count -eq 0) {
    Write-Host ""
    Write-Host "NO DEVICE CONNECTED." -ForegroundColor Yellow
    Write-Host "Connect a phone with USB debugging enabled, then re-run this script."
    exit 2
}

if (-not $Serial) {
    $Serial = ($online[0].Line -split '\s+')[0]
}
$script:Serial = $Serial
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:Artifacts = Join-Path $ProjectRoot "artifacts\device-$stamp"
New-Item -ItemType Directory -Force -Path $script:Artifacts | Out-Null

Write-Host "wow cam device smoke test"
Write-Host "  adb    : $script:Adb"
Write-Host "  device : $Serial"
Write-Host "  apk    : $Apk"
Write-Host "  output : $script:Artifacts"

# --- install & permissions ---------------------------------------------------
Write-Host "`n[1/7] Installing APK"
Invoke-Adb install -r $Apk | Out-Null

Write-Host "[2/7] Granting runtime permissions"
foreach ($p in @("android.permission.CAMERA", "android.permission.RECORD_AUDIO")) {
    try { Invoke-Adb shell pm grant com.wowcam.app $p | Out-Null } catch { }
}
if ([int](Invoke-Adb shell getprop ro.build.version.sdk) -le 28) {
    try { Invoke-Adb shell pm grant com.wowcam.app android.permission.WRITE_EXTERNAL_STORAGE | Out-Null } catch { }
}

Write-Host "[3/7] Launching com.wowcam.app/.MainActivity"
Invoke-Adb shell am force-stop com.wowcam.app | Out-Null
Invoke-Adb logcat -c | Out-Null
Invoke-Adb shell am start -W -n com.wowcam.app/.MainActivity | Out-Null
Start-Sleep -Seconds 4

$ui = Get-UiXml
if ($ui -notmatch 'com\.wowcam\.app:id/previewView') {
    Save-Screenshot "00-launch-failed"
    throw "Camera screen did not appear. See screenshot in $script:Artifacts"
}

Write-Host "[4/7] Capturing idle state + flipping camera"
Save-Screenshot "01-idle"
Tap-Node "flipButton"
Start-Sleep -Seconds 2
Save-Screenshot "02-front-camera"
Tap-Node "flipButton"
Start-Sleep -Seconds 2

Write-Host "[5/7] Torch, filter, zoom"
Tap-Node "flashButton"
Save-Screenshot "03-torch-on"
Tap-Node "flashButton"
Tap-Node "filterChip"
Save-Screenshot "04-filter-vivid"
Tap-Node "filterChip"
Tap-Node "filterChip"
Tap-Node "filterChip"
Swipe-Zoom
Save-Screenshot "05-zoom-5x"

Write-Host "[6/7] 3s timer -> countdown -> record -> stop"
Tap-Node "timerChip"
Tap-Node "recordButton"
Start-Sleep -Milliseconds 500
Save-Screenshot "06-countdown-3s"
Start-Sleep -Seconds 4
Save-Screenshot "07-recording"
Tap-Node "recordButton"
Start-Sleep -Seconds 3
Save-Screenshot "08-stopped"

Write-Host "[7/7] Checking for crashes and saved media"
$log = Invoke-Adb logcat -d -t 400
$fatal = $log | Select-String -Pattern "FATAL EXCEPTION|Process: com\.wowcam\.app"
# The mux + MediaStore publish happens asynchronously after stop, so poll for a
# committed Movies/WowCam entry instead of checking once immediately.
Write-Host "      waiting for the MediaStore entry (up to 90s)..."
$mediaText = ""
$mediaFound = $false
for ($attempt = 1; $attempt -le 30; $attempt += 1) {
    $mediaText = (Invoke-Adb shell content query --uri content://media/external/video/media --projection _display_name:_size:relative_path) -join "`n"
    if ($mediaText -match "Movies/WowCam") {
        $mediaFound = $true
        break
    }
    if ($attempt % 5 -eq 0) { Write-Host "      still processing ($attempt x 3s)..." }
    Start-Sleep -Seconds 3
}
$media = ""
if ($mediaFound) {
    $media = ($mediaText -split "`n" | Select-String "WowCam") -join "`n"
}
$media | Set-Content (Join-Path $script:Artifacts "media-query.txt")
$log | Set-Content (Join-Path $script:Artifacts "logcat.txt")

if ($fatal) {
    Write-Host "`nFAIL: crash detected" -ForegroundColor Red
    $fatal | ForEach-Object { Write-Host $_ }
    exit 1
}
if (-not ($media | Select-String -Pattern "WowCam")) {
    Write-Host "`nWARN: no Movies/WowCam entry in MediaStore yet (check 07-recording.png)." -ForegroundColor Yellow
} else {
    Write-Host "`nMediaStore: Movies/WowCam entry present."
}

Write-Host "`nPASS: app installed, camera controls responded, no FATAL exception." -ForegroundColor Green
Write-Host "Artifacts: $script:Artifacts"
exit 0