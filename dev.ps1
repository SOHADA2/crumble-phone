# 빌드·설치 도우미.  사용: .\dev.ps1 build | install | log | shot
#   기기는 자동으로 찾는다(에뮬레이터가 아닌 실제 폰을 고른다).
param([string]$Cmd = 'install', [string]$Root = 'D:\android-dev')

$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = "$Root\jdk"
$env:ANDROID_HOME = "$Root\sdk"
$env:GRADLE_USER_HOME = "$Root\.gradle"
$ADB = "$Root\sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = 'adb' }
$PKG = 'com.sohada.crumblephone'
$APK = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'

function Get-Phone {
    $lines = & $ADB devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }
    $ids = @($lines | ForEach-Object { ($_ -split "\t")[0] })
    # 에뮬레이터(127.0.0.1 / emulator-)가 아닌 실제 기기를 먼저 고른다
    $real = $ids | Where-Object { $_ -notmatch '^(emulator-|127\.0\.0\.1)' } | Select-Object -First 1
    if ($real) { return $real }
    return ($ids | Select-Object -First 1)
}

switch ($Cmd) {
    'build' {
        & (Join-Path $PSScriptRoot 'gradlew.bat') assembleDebug --no-daemon
    }
    'install' {
        & (Join-Path $PSScriptRoot 'gradlew.bat') assembleDebug --no-daemon
        if (-not (Test-Path $APK)) { Write-Host "빌드 실패"; break }
        $S = Get-Phone
        if (-not $S) { Write-Host "폰이 안 붙어 있어요(USB 디버깅 확인)"; break }
        Write-Host "설치 대상: $S"
        & $ADB -s $S install -r -g $APK
        # 재설치하면 접근성 서비스가 끊긴다 → adb 로 되살린다(오버레이 권한도 함께).
        & $ADB -s $S shell settings put secure enabled_accessibility_services "$PKG/$PKG.TapService"
        & $ADB -s $S shell settings put secure accessibility_enabled 1
        & $ADB -s $S shell appops set $PKG SYSTEM_ALERT_WINDOW allow
        & $ADB -s $S shell am start -n "$PKG/.MainActivity" | Out-Null
        Write-Host ""
        Write-Host "⚠️ 화면 읽기 권한만 사람이 눌러야 합니다:"
        Write-Host "   앱에서 [화면 읽기 허용] → '앱 하나 공유'를 '전체 화면'으로 바꾸고 → [다음] → [지금 시작]"
    }
    'log' {
        $S = Get-Phone
        & $ADB -s $S logcat -d -s CrumblePhone:I | Select-Object -Last 30
    }
    'shot' {
        $S = Get-Phone
        $out = Join-Path $PSScriptRoot 'shots'
        New-Item -ItemType Directory -Force $out | Out-Null
        $f = Join-Path $out ("shot_" + (Get-Date -Format 'HHmmss') + ".png")
        # ⚠️ PowerShell 에서 exec-out 을 파일로 리다이렉트하면 PNG 가 깨진다 → screencap + pull 을 쓴다.
        & $ADB -s $S shell screencap -p /sdcard/_s.png | Out-Null
        & $ADB -s $S pull /sdcard/_s.png $f | Out-Null
        Write-Host $f
    }
    default { Write-Host "사용: .\dev.ps1 build | install | log | shot" }
}
