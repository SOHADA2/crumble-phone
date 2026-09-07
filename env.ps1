# 개발 도구(JDK 17 · 안드로이드 SDK · Gradle) 가 어디 있는지 찾아서 환경변수로 세팅한다.
#
# ⚠️ **경로를 한 군데에 박아 두지 않는다.** 예전엔 `D:\android-dev` 가 dev.ps1 · setup-dev.ps1 ·
#    local.properties · README 네 곳에 박혀 있어서, D 드라이브가 없는 컴퓨터에서는 클론해도 못 돌렸다.
#    이제 이 파일 하나가 찾아 주고 나머지는 전부 여기를 부른다.
#
# 찾는 순서 (먼저 잡히는 것을 쓴다):
#   1. -Root 인자              — 손으로 지정
#   2. $env:CRUMBLE_DEV_ROOT   — 이 컴퓨터에서만 다르게 쓰고 싶을 때
#   3. local.properties 의 sdk.dir 가 가리키는 곳 (이미 한 번 준비된 PC)
#   4. D:\android-dev / C:\android-dev / %LOCALAPPDATA%\android-dev  (setup-dev.ps1 이 만드는 자리)
#   5. 시스템에 이미 깔린 것 — $env:JAVA_HOME · Android Studio 의 jbr · $env:ANDROID_HOME 등
#
# 반환: @{ Root; Jdk; Sdk; Adb; Gradlew; Ok; Missing }

$ErrorActionPreference = 'Continue'   # ⚠️ 'Stop' 금지 — PS 5.1 은 네이티브 exe 의 stderr 를 오류로 감싼다.

function Get-RootCandidates([string]$Root) {
    $c = New-Object System.Collections.ArrayList
    if ($Root) { [void]$c.Add($Root) }
    if ($env:CRUMBLE_DEV_ROOT) { [void]$c.Add($env:CRUMBLE_DEV_ROOT) }

    # 이미 준비된 PC 면 local.properties 가 SDK 를 가리키고 있다. 그 부모가 root 다.
    $lp = Join-Path $PSScriptRoot 'local.properties'
    if (Test-Path $lp) {
        foreach ($ln in (Get-Content $lp -ErrorAction SilentlyContinue)) {
            if ($ln -match '^\s*sdk\.dir\s*=\s*(.+?)\s*$') {
                $sdk = $matches[1].Replace('\\', '\').Replace('/', '\')
                if (Test-Path $sdk) { [void]$c.Add((Split-Path $sdk -Parent)) }
            }
        }
    }

    foreach ($p in @('D:\android-dev', 'C:\android-dev')) { [void]$c.Add($p) }
    if ($env:LOCALAPPDATA) { [void]$c.Add((Join-Path $env:LOCALAPPDATA 'android-dev')) }
    return ($c | Select-Object -Unique)
}

# ⚠️ 없는 드라이브(Z: 같은)에 Join-Path 를 쓰면 PS 5.1 이 DriveNotFound 오류를 뱉는다.
#    후보를 훑는 게 목적이라 조용히 넘어가야 한다 → 문자열로 붙이고 Test-Path 로만 본다.
function Has-Path([string]$base, [string]$leaf) {
    if (-not $base) { return $false }
    $p = $base.TrimEnd('\', '/') + '\' + $leaf
    return (Test-Path -LiteralPath $p -ErrorAction SilentlyContinue)
}
function Sub-Path([string]$base, [string]$leaf) {
    return $base.TrimEnd('\', '/') + '\' + $leaf
}

function Find-Jdk($roots) {
    foreach ($r in $roots) {
        if (Has-Path (Sub-Path $r 'jdk') 'bin\java.exe') { return (Sub-Path $r 'jdk') }
    }
    # 시스템에 이미 있는 것들
    if (Has-Path $env:JAVA_HOME 'bin\java.exe') { return $env:JAVA_HOME }
    foreach ($p in @("$env:ProgramFiles\Android\Android Studio\jbr",
                     "$env:LOCALAPPDATA\Programs\Android Studio\jbr")) {
        if (Has-Path $p 'bin\java.exe') { return $p }
    }
    return $null
}

function Find-Sdk($roots) {
    foreach ($r in $roots) {
        if (Has-Path (Sub-Path $r 'sdk') 'platform-tools\adb.exe') { return (Sub-Path $r 'sdk') }
    }
    foreach ($p in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")) {
        if (Has-Path $p 'platform-tools\adb.exe') { return $p }
    }
    return $null
}

function Resolve-DevEnv([string]$Root = '') {
    $roots = Get-RootCandidates $Root
    $jdk = Find-Jdk $roots
    $sdk = Find-Sdk $roots

    # root = 실제로 찾은 것들의 부모. 아무것도 없으면 '앞으로 설치할 자리'를 고른다.
    $resolved = $null
    if ($sdk) { $resolved = Split-Path $sdk -Parent }
    elseif ($jdk) { $resolved = Split-Path $jdk -Parent }
    if (-not $resolved) {
        # 있는 드라이브 위의 첫 후보를 고른다. D 드라이브가 없는 PC 에 D:\android-dev 를 만들려 들면 안 된다.
        foreach ($r in $roots) {
            $q = Split-Path $r -Qualifier -ErrorAction SilentlyContinue
            if ($q -and (Test-Path -LiteralPath ($q + '\') -ErrorAction SilentlyContinue)) { $resolved = $r; break }
        }
        if (-not $resolved) { $resolved = 'C:\android-dev' }
    }

    $missing = @()
    if (-not $jdk) { $missing += 'JDK 17' }
    if (-not $sdk) { $missing += '안드로이드 SDK' }

    if ($jdk) { $env:JAVA_HOME = $jdk }
    if ($sdk) { $env:ANDROID_HOME = $sdk; $env:ANDROID_SDK_ROOT = $sdk }
    # Gradle 캐시는 도구 폴더 안에 둔다(사용자 홈이 작은 PC 배려). 시스템 설치본을 쓰는 경우엔 건드리지 않는다.
    if ($sdk -and (Has-Path $resolved 'jdk')) { $env:GRADLE_USER_HOME = Sub-Path $resolved '.gradle' }

    $adb = 'adb'
    if ($sdk) { $adb = Join-Path $sdk 'platform-tools\adb.exe' }

    return @{
        Root    = $resolved
        Jdk     = $jdk
        Sdk     = $sdk
        Adb     = $adb
        Gradlew = (Join-Path $PSScriptRoot 'gradlew.bat')
        Ok      = ($missing.Count -eq 0)
        Missing = $missing
    }
}

# local.properties 는 gitignore 라 새 클론에는 없다. SDK 를 찾았으면 여기서 만들어 준다.
# ⚠️ 역슬래시는 이 파일에서 이스케이프로 먹힌다 → 반드시 슬래시로 쓴다.
function Sync-LocalProperties($sdk) {
    if (-not $sdk) { return $false }
    $lp = Join-Path $PSScriptRoot 'local.properties'
    $want = "sdk.dir=" + $sdk.Replace('\', '/')
    $cur = ''
    if (Test-Path $lp) { $cur = (Get-Content $lp -Raw -ErrorAction SilentlyContinue).Trim() }
    if ($cur -eq $want) { return $false }
    Set-Content $lp $want -Encoding ASCII
    return $true
}
