# 스파이크 APK 를 폰에 설치하고, 앱이 남긴 화면 캡처를 PC 로 가져온다.
#   사용: powershell -ExecutionPolicy Bypass -File D:\crumble-phone\install.ps1
$ErrorActionPreference = 'Continue'
$ADB = 'D:\android-dev\sdk\platform-tools\adb.exe'
$SERIAL = 'R3CY104DTGF'          # 갤럭시 S25 울트라(USB)
$APK = 'D:\crumble-phone\app\build\outputs\apk\debug\app-debug.apk'
$OUT = 'D:\crumble-phone\shots'

if (-not (Test-Path $APK)) { Write-Host "APK 가 없습니다. 먼저 빌드하세요."; return }
Write-Host "설치 중..."
& $ADB -s $SERIAL install -r -g $APK
Write-Host "실행..."
& $ADB -s $SERIAL shell am start -n com.sohada.crumblephone/.MainActivity
Write-Host ""
Write-Host "폰에서: 1(접근성) → 2(화면 읽기 허용) → 3(게임 켜고 검사) 순서로 눌러 주세요."
Write-Host "끝나면 이 스크립트를 다시 돌리면 캡처를 가져옵니다."
New-Item -ItemType Directory -Force $OUT | Out-Null
& $ADB -s $SERIAL pull /sdcard/Android/data/com.sohada.crumblephone/files/shots $OUT 2>&1 | Select-Object -Last 2
