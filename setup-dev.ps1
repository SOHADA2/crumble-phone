# 새 PC 준비 — 안드로이드 개발 도구를 한 폴더에 받는다(시스템은 안 건드린다).
#   사용: powershell -ExecutionPolicy Bypass -File setup-dev.ps1 [-Root D:\android-dev]
#   ※ Android Studio 는 필요 없다. 터미널에서 gradlew 로 빌드한다.
param([string]$Root = 'D:\android-dev')

$ErrorActionPreference = 'Continue'   # ⚠️ 'Stop' 을 쓰면 안 된다: PowerShell 5.1 은 네이티브 exe 의 stderr 를
                                      #    오류로 감싸서(NativeCommandError) java -version 같은 정상 출력에도 죽는다.
$DL = Join-Path $Root '_dl'
New-Item -ItemType Directory -Force $DL | Out-Null

function Get-File($url, $out) {
    if (Test-Path $out) { Write-Host "이미 있음: $(Split-Path $out -Leaf)"; return }
    Write-Host "받는 중: $(Split-Path $out -Leaf)"
    & curl.exe -sL --retry 3 -o $out $url
}
function Unpack($zip, $dest) {
    if (Test-Path $dest) { return }
    $tmp = "$dest._tmp"
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive $zip -DestinationPath $tmp -Force
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    Move-Item $inner.FullName $dest
    Remove-Item $tmp -Recurse -Force
}

Get-File 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' "$DL\jdk17.zip"
Unpack "$DL\jdk17.zip" "$Root\jdk"
Get-File 'https://services.gradle.org/distributions/gradle-8.7-bin.zip' "$DL\gradle.zip"
Unpack "$DL\gradle.zip" "$Root\gradle"
Get-File 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' "$DL\clt.zip"
if (-not (Test-Path "$Root\sdk\cmdline-tools\latest\bin\sdkmanager.bat")) {
    Expand-Archive "$DL\clt.zip" -DestinationPath "$Root\_clt" -Force
    New-Item -ItemType Directory -Force "$Root\sdk\cmdline-tools" | Out-Null
    Move-Item "$Root\_clt\cmdline-tools" "$Root\sdk\cmdline-tools\latest"
    Remove-Item "$Root\_clt" -Recurse -Force
}

# 라이선스는 파일로 직접 기록한다("y" 를 파이프로 미는 방식은 비대화형에서 sdkmanager 표준입력에 안 닿는다).
$lic = "$Root\sdk\licenses"; New-Item -ItemType Directory -Force $lic | Out-Null
@{
    'android-sdk-license'           = @('24333f8a63b6825ea9c5514f83c2829b004d1fee', '8933bad161af4178b1185d1a37fbf41ea5269c55', 'd56f5187479451eabf01fb78af6dfcb131a6481e')
    'android-sdk-preview-license'   = @('84831b9409646a918e30573bab4c9c91346d8abd')
    'android-sdk-arm-dbt-license'   = @('859f317696f67ef3d7f30a50a5560e7834b43903')
}.GetEnumerator() | ForEach-Object { Set-Content (Join-Path $lic $_.Key) ($_.Value -join "`n") -NoNewline -Encoding ASCII }

$env:JAVA_HOME = "$Root\jdk"
& cmd /c "`"$Root\sdk\cmdline-tools\latest\bin\sdkmanager.bat`" --sdk_root=`"$Root\sdk`" platform-tools `"platforms;android-34`" `"build-tools;34.0.0`"" | Select-Object -Last 3

# 프로젝트가 SDK 를 찾도록. ⚠️ 역슬래시는 이스케이프로 먹히니 반드시 슬래시로 쓴다.
Set-Content (Join-Path $PSScriptRoot 'local.properties') ("sdk.dir=" + $Root.Replace('\', '/') + "/sdk") -Encoding ASCII

Write-Host ""
Write-Host "=== 준비 결과 ==="
foreach ($x in @(@('jdk', "$Root\jdk\bin\java.exe"), @('gradle', "$Root\gradle\bin\gradle.bat"),
                 @('adb', "$Root\sdk\platform-tools\adb.exe"), @('platform-34', "$Root\sdk\platforms\android-34"),
                 @('build-tools', "$Root\sdk\build-tools\34.0.0"), @('local.properties', (Join-Path $PSScriptRoot 'local.properties')))) {
    Write-Host ("  {0,-16} {1}" -f $x[0], (Test-Path $x[1]))
}
Write-Host ""
Write-Host "다음: .\dev.ps1 build   (빌드)   /   .\dev.ps1 install   (빌드+폰에 설치)"
