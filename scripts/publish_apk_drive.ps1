# 빌드한 debug APK를 Google Drive 동기화 폴더에 복사한다.
# 폰의 Drive 앱에서 Dashboard-latest.apk를 열어 덮어 설치한다.
# 사용: android/에서 assembleDebug 후
#   powershell -ExecutionPolicy Bypass -File scripts/publish_apk_drive.ps1
param(
    [string]$DriveDir = $(if ($env:DASHBOARD_APK_DRIVE_DIR) { $env:DASHBOARD_APK_DRIVE_DIR } else { "G:\내 드라이브\Dashboard-APK" })
)
$ErrorActionPreference = "Stop"

$apkDir = Join-Path $PSScriptRoot "..\android\app\build\outputs\apk\debug"
$meta = Get-Content (Join-Path $apkDir "output-metadata.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$element = $meta.elements[0]
$apk = Join-Path $apkDir $element.outputFile
if (-not (Test-Path $apk)) { throw "APK가 없습니다: $apk (먼저 gradlew assembleDebug)" }

New-Item -ItemType Directory -Force -Path $DriveDir | Out-Null
$version = $element.versionName
$hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()

Copy-Item $apk (Join-Path $DriveDir "Dashboard-$version.apk") -Force
Copy-Item $apk (Join-Path $DriveDir "Dashboard-latest.apk") -Force
$note = "version $version (versionCode $($element.versionCode))`nsha256 $hash`ncopied $(Get-Date -Format 'yyyy-MM-dd HH:mm')`n"
[IO.File]::WriteAllText((Join-Path $DriveDir "Dashboard-latest.txt"), $note, (New-Object Text.UTF8Encoding $false))

Write-Output "Drive에 복사: Dashboard-$version.apk, Dashboard-latest.apk"
Write-Output "sha256 $hash"
