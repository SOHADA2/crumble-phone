# 크럼블 폰봇 (스파이크)

쿠키런: 크럼블 자동화를 **PC 없이 폰에서** 돌릴 수 있는지 확인하려고 만든 최소 앱.

## 확인 목표와 결과 (2026-09-04, 갤럭시 S25 울트라 SM-S938N / 안드로이드 16)

| 관문 | 결과 |
|---|---|
| MediaProjection 으로 게임 화면이 찍히는가 | ✅ 1440x3120 풀컬러, 검은 화면 아님 (FLAG_SECURE 없음) |
| 접근성 서비스 탭이 게임에 먹히는가 | ✅ `dispatchGesture=true` + 화면이 실제로 바뀜 |
| 접근성 켠 채 게임이 정상 실행되는가 | ✅ 막지 않음 |
| PC 봇 좌표가 그대로 통하는가 | ✅ 폰이 **1440x3120** 로 동일 — 취소(441,2797) 그대로 통함 |
| PC 봇 판정 로직이 그대로 통하는가 | ✅ 독 비율 0.33(PC 0.32) · 닫기버튼 7/7·0/7 로 같은 판정 |

## 구조
- `TapService` — 접근성 서비스. `adb shell input tap/swipe/keyevent 4` 를 대신한다.
- `CaptureService` — MediaProjection 포그라운드 서비스. `adb exec-out screencap` 을 대신한다.
  ⚠️ 안드로이드 14+ 는 `getMediaProjection()` **전에** foregroundServiceType=mediaProjection 으로 전환이 끝나 있어야 한다.
- `MainActivity` — 시험 버튼 5개. PC 봇 `dev.ps1` 의 판정 상수를 그대로 옮겨 담았다.

## 함정 기록
- **패키지 가시성**: 안드로이드 11+ 는 다른 앱을 숨긴다. `<queries>` 에 `com.devsisters.cc` 를 적지 않으면
  `getLaunchIntentForPackage` 가 null 을 주고 "게임이 설치돼 있지 않습니다" 가 뜬다(실제로 겪음).
- **앱을 다시 설치하면 화면 읽기 권한이 초기화된다**(세션 단위). 재설치할 때마다 동의를 다시 받아야 한다.
- **동의 창에서 "전체 화면"을 골라야 한다.** "앱 하나 공유" 는 우리 앱 자신만 찍는다.
- 탭 시험은 **게임을 먼저 띄우고 기다린 뒤** 해야 한다. 앱이 앞에 있을 때 누르면 앱 자신을 누른다.
- `local.properties` 의 `sdk.dir` 는 역슬래시가 이스케이프로 먹힌다 → `D:/android-dev/sdk` 처럼 슬래시로.

## 빌드
```powershell
$env:JAVA_HOME='D:\android-dev\jdk'; $env:ANDROID_HOME='D:\android-dev\sdk'
$env:GRADLE_USER_HOME='D:\android-dev\.gradle'
D:\android-dev\gradle\bin\gradle.bat assembleDebug --no-daemon
D:\android-dev\sdk\platform-tools\adb.exe -s <시리얼> install -r -g app\build\outputs\apk\debug\app-debug.apk
```
