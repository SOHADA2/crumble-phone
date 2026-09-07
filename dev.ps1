# 빌드·설치 도우미.  사용: .\dev.ps1 check | compile | build | install | log | shot
#   기기는 자동으로 찾는다(에뮬레이터가 아닌 실제 폰을 고른다).
#   도구 위치는 env.ps1 이 찾는다 — 컴퓨터마다 다를 수 있어서 경로를 여기 박지 않는다.
param([string]$Cmd = 'install', [string]$Root = '')

$ErrorActionPreference = 'Continue'

. (Join-Path $PSScriptRoot 'env.ps1')
$DEV = Resolve-DevEnv $Root
$ADB = $DEV.Adb
$PKG = 'com.sohada.crumblephone'

# 빌드하기 전에 도구가 있는지 확인하고, 새 클론이면 local.properties 를 만들어 준다.
function Assert-Ready {
    if ($DEV.Ok) {
        if (Sync-LocalProperties $DEV.Sdk) { Write-Host "local.properties 를 맞췄어요 -> $($DEV.Sdk)" }
        return $true
    }
    Write-Host "개발 도구가 없어요: $($DEV.Missing -join ', ')"
    Write-Host "  준비:  powershell -ExecutionPolicy Bypass -File setup-dev.ps1"
    Write-Host "  (다른 자리에 깔려면 -Root 로 지정)"
    return $false
}
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
    'check' {
        Write-Host "=== 개발 환경 점검 ==="
        Write-Host ("  {0,-10} {1}" -f 'root', $DEV.Root)
        $jdkTxt = '없음'; if ($DEV.Jdk) { $jdkTxt = $DEV.Jdk }
        $sdkTxt = '없음'; if ($DEV.Sdk) { $sdkTxt = $DEV.Sdk }
        Write-Host ("  {0,-10} {1}" -f 'JDK', $jdkTxt)
        Write-Host ("  {0,-10} {1}" -f 'SDK', $sdkTxt)
        Write-Host ("  {0,-10} {1}" -f 'adb', $ADB)
        if ($DEV.Ok) {
            Sync-LocalProperties $DEV.Sdk | Out-Null
            $S = Get-Phone
            $ph = '안 붙어 있음 (USB 디버깅 확인 · 빌드는 됩니다)'; if ($S) { $ph = $S }
            Write-Host ("  {0,-10} {1}" -f '폰', $ph)
            Write-Host ""
            Write-Host "준비됐어요.  .\dev.ps1 compile (빠른 문법 확인) / build / install"
        }
        else {
            Write-Host ""
            Write-Host "없는 것: $($DEV.Missing -join ', ')"
            Write-Host "  powershell -ExecutionPolicy Bypass -File setup-dev.ps1"
        }
    }
    'compile' {
        # 코틀린만 컴파일한다(APK 는 안 만든다). 코드 고치고 문법·타입만 빠르게 볼 때.
        if (-not (Assert-Ready)) { break }
        & (Join-Path $PSScriptRoot 'gradlew.bat') compileDebugKotlin --console=plain -q
        if ($?) { Write-Host "컴파일 통과" }
    }
    'build' {
        if (-not (Assert-Ready)) { break }
        & (Join-Path $PSScriptRoot 'gradlew.bat') assembleDebug --no-daemon
    }
    'install' {
        if (-not (Assert-Ready)) { break }
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
    default { Write-Host "사용: .\dev.ps1 check | compile | build | install | log | shot" }
}
