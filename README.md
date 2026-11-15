# 카르테 (Karte)

주행 중 이상을 느낀 순간 말 한마디로 증상을 남기면, 그 순간의 차량 상태를 함께 저장해 정비소에 그대로 보여 줄 수 있는 Pleos Connect 앱입니다.

현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작입니다.

## 왜 만들었나

- "차는 이상한데 정비소에 가면 증상이 안 나온다"는 경험은 흔합니다. 간헐적으로 나타나는 증상은 입고 시점에 재현되지 않으면 원인을 찾는 데 오래 걸립니다.
- 운전자는 증상을 느낀 순간의 속도, 브레이크, 핸들 상태를 기억하지 못하고, 정비사는 그 정보 없이 재현 조건을 추측해야 합니다.
- 카르테는 증상을 느낀 "그 순간"의 차량 상태를 차가 대신 기억하게 합니다. 휴대폰 앱은 얻을 수 없는 정보입니다.

## 주요 기능

| 기능 | 설명 |
|---|---|
| 음성 증상 기록 | 큰 버튼 하나를 누르고 말하면 문장으로 저장합니다. 음성으로 "기록했어요. 소음 증상 3번째예요."라고 확인해 줍니다. |
| 순간 스냅샷 | 버튼을 누른 시점의 속도, 기어, 브레이크, 조향각, 방향지시등, 주행 모드, 누적 주행거리, 켜진 경고등, 위치, 도로 제한속도를 함께 저장합니다. |
| 재현 조건 찾기 | 같은 분류의 기록이 2건 이상 쌓이면 공통 조건을 "저속(시속 30km 미만) 3/3"처럼 건수와 함께 보여 줍니다. |
| 경고등 자동 기록 | 고장 관련 경고등 15종이 새로 켜지면 말하지 않아도 스냅샷을 남기고 음성으로 알려 줍니다. |
| 정비소용 리포트 | 정차 중에 반복 조건과 전체 기록을 한 화면으로 보여 줍니다. |
| 정비소 길 안내 | 내비게이션 즐겨찾기·최근 목적지에서 단골 정비소를 골라 길 안내를 요청합니다. |

### 주행 중과 정차 중

| 상태 | 화면 |
|---|---|
| 주행 중 (주행 규제 활성) | 증상 말하기 버튼과 차량 상태만 표시. 기록 목록, 직접 입력, 리포트, 길 안내 버튼은 숨깁니다. |
| 정차 중 | 기록 목록, 재현 조건, 리포트, 직접 입력, 정비소 길 안내를 모두 사용합니다. |

주행 규제 여부는 `CarUxRestrictionsManager`가 내려 주는 값으로 판단합니다.

## 화면 디자인

Pleos Connect UI Design Guide와 에뮬레이터 기본 테마의 디자인 토큰 값을 따랐습니다 (`Palette.kt`).

- 무채색 위주의 색, 모서리 반경 0, 테두리 1dp 버튼 (기본 테마 토큰 값)
- 주간·야간 모드에 따라 색상표 전환, 가로·세로 창에 맞춘 배치
- 버튼을 누르면 95%로 줄어드는 눌림 표현
- 증상 분류는 색 태그로, 기록 경로(음성, 자동 기록, 예시)는 테두리 태그로 구분. 태그 색은 배경 대비 4.5:1 이상
- 차량 상태 칸은 높이를 고정하고 값은 한 줄로 표시. 경고등이 여러 개면 "ABS 외 1"처럼 줄여 표시
- 주행 중에는 증상 말하기 버튼을 더 크게 표시

세로 창 배치는 에뮬레이터에서 내비게이션과 화면을 나눈 상태(841×1210 창)로 확인했습니다.

## Pleos Connect SDK 사용

| SDK | 버전 | 사용 위치 | 용도 |
|---|---|---|---|
| Vehicle SDK | 2.3.2 | `VehicleMonitor.kt` | 브레이크 페달, 조향각, 방향지시등, 주행 모드, 누적 주행거리, 경고등(Telltales) 구독 |
| SpeechToText SDK | 3.3.1 | `Voice.kt` | 증상 발화를 문장으로 변환 (ON_DEVICE 모드) |
| TextToSpeech SDK | 3.3.1 | `Voice.kt` | 기록 확인, 경고등 안내 음성 (ON_DEVICE 모드) |
| NaviHelper SDK | 3.0.5 | `NaviBridge.kt` | 현재 주소, 도로 제한속도 수신, 즐겨찾기·최근 목적지 조회, 정비소 길 안내 요청 |
| FusedLocation SDK | 1.1.3 | `LocationMonitor.kt` | 증상이 발생한 위치(위도·경도) 기록 |

Pleos 문서가 안내하는 AAOS 표준 API도 함께 씁니다 (`CarBridge.kt`).

- 차속: `CarPropertyManager`의 `PERF_VEHICLE_SPEED`
- 기어: `GEAR_SELECTION` (Vehicle SDK의 Gear API가 deprecated되며 안내하는 방식)
- 주행 규제: `CarUxRestrictionsManager`

음성은 차량 내장(ON_DEVICE) 엔진만 사용하며, 증상 기록은 앱 내부 저장소에만 저장하고 차량 밖으로 전송하지 않습니다.

## 실행 방법

### APK 설치

```
adb install apk/Karte_Karte_release.apk
```

실행 중인 Pleos Connect 에뮬레이터 창에 APK를 끌어다 놓아도 설치됩니다. 설치 후 런처에서 "카르테"를 실행하고 권한 요청을 허용합니다.

### 소스 빌드

```
sh gradlew assembleRelease
```

- 결과물: `app/build/outputs/apk/release/app-release.apk`
- 단위 테스트: `sh gradlew testDebugUnitTest`
- 빌드 확인 환경: JDK 25 (Android Studio 내장 JBR), Gradle 9.9.1, Android Gradle Plugin 9.5.1, compileSdk 36, minSdk 28, targetSdk 34
- Pleos SDK는 `https://nexus-playground.pleos.ai/repository/maven-releases/` 에서 내려받습니다 (`settings.gradle.kts`).
- `keystore/karte-contest.jks`는 공모전 제출용 서명 키입니다. 실제 배포에는 쓰지 않습니다.

## 테스트 환경 및 필요한 설정

| 항목 | 값 |
|---|---|
| Pleos Connect 에뮬레이터 버전 | v2.1.0 (AVD `Pleos_Connect_v2.1`, 시스템 이미지 `release_connect_v2.1.0`, arm64) |
| 화면 규격 | 2560 x 1440, 160dpi. 앱은 오른쪽 앱 영역(약 1680 x 1210)에 표시됩니다. |
| Android | 14 (API 34) |

- 화소 밀도가 낮은 차량 디스플레이에 맞춰 창 너비 기준으로 글자와 버튼 크기를 키웁니다 (`MainActivity.rebuildUi`).
- 음성 인식을 쓰려면 Pleos 문서의 "마이크 접근 설정"에 따라 에뮬레이터가 호스트 마이크를 쓰도록 설정합니다.
- 차량 상태는 Pleos Connect Virtual Console로 바꿉니다.
  - Motion: Gear Selection, Brake Pedal, Steering Angle, Odometer. 속도는 가속 페달로 올립니다.
  - Convenience: Turn Signal State
  - Safety: Telltales (ABS, TPMS 등)
- 기어를 D로 바꾸면 주행 규제가 켜져 앱이 주행 화면으로 바뀌고, P로 돌아오면 정차 화면으로 돌아옵니다.

### 에뮬레이터에서 확인한 SDK 동작

개발하면서 실제로 확인한 내용입니다. 구현은 이 동작을 기준으로 합니다.

- 경고등 콜백(`TelltaleInfoListener`)은 바뀐 항목이 아니라 "지금 켜져 있는 경고등 전체"를 매번 전달합니다. 꺼진 경고등은 목록에서 빠집니다.
- 경고등 구독(`registerTelltales`)은 `PRIVATE_CAR_SAFETY` 권한을 요구합니다. 실제 입점 시에는 Pleos App Market과 권한 협의가 필요합니다.
- 조향각은 왼쪽이 음수, 오른쪽이 양수입니다. Virtual Console의 Steering Angle 입력값과는 부호가 반대입니다 (콘솔 -48 입력 시 SDK 값 +48, 클러스터 차선도 오른쪽으로 휨).
- 주행 모드(`DrivingMode`)는 에뮬레이터가 해당 프로퍼티를 지원하지 않아 "확인 불가"로 표시됩니다.
- 길 안내 요청(`requestRoute`)은 내비게이션 앱까지 전달되지만, 에뮬레이터에서는 내비게이션이 "경로 탐색 오류"를 반환합니다.

## 시연 순서

시연 영상: `docs/karte_demo.mp4` (에뮬레이터 화면 녹화, 약 68초. 화면 녹화라 소리는 들어 있지 않습니다. 3번 단계는 실제로 말한 음성이 인식된 장면입니다.)

1. 기어 P 상태에서 앱 실행. 왼쪽에 실시간 차량 상태, 오른쪽에 기록 목록이 보입니다.
2. Virtual Console에서 기어 D, 가속으로 속도를 올린 뒤 브레이크와 조향각을 줍니다. 화면이 주행 모드로 바뀌어 목록과 입력 버튼이 숨겨집니다.
3. "증상 말하기"를 누르고 "브레이크 밟을 때 끼익 소리 나"라고 말합니다. 영상에서는 "브레이크 밟을 때 끼익 소리"로 인식되어 기록되고, 음성으로 기록 확인이 나옵니다.
4. Telltales에서 TPMS를 켭니다. 말하지 않아도 경고등 기록이 생기고 음성 안내가 나옵니다.
5. 기어를 P로 바꾸면 목록이 다시 보입니다. "예시 기록"을 누르면 예시 5건이 들어가고 소음 증상과 경고등의 반복 조건이 표시됩니다. 예시 기록은 실제 차량 데이터가 아니며 화면에 "예시"로 표시됩니다.
6. 기록을 누르면 그 순간의 스냅샷(속도, 기어, 브레이크, 핸들, 켜진 경고등, 위치)이 나옵니다.
7. "리포트"로 정비소용 요약을 엽니다.

## 테스트

| 단계 | 방법 | 결과 |
| --- | --- | --- |
| 단위 테스트 | `sh gradlew testDebugUnitTest` | 18건 통과 |
| 통합 테스트 | 같은 명령 (`StoreAnalysisIntegrationTest`) | 5건 통과 |
| 시스템 테스트 | `python3 tests/system/system_test.py` (에뮬레이터 + Virtual Console) | 24개 확인 항목 통과 |
| 인수 테스트 | 기획서 시나리오 기준, 시스템 테스트 + 수동 확인 | 9개 중 7개 통과, 1개 일부 통과, 1개 일부 미확인 |

단계별 범위, 실행 방법, 통과하지 못했거나 확인하지 못한 항목은 [docs/TESTING.md](docs/TESTING.md)에 있습니다. 실차 테스트는 하지 못했습니다.

## 검증 범위와 한계

Pleos Connect 에뮬레이터 v2.1.0에서 확인한 것:

- 차량 상태 실시간 표시: 속도, 기어, 브레이크, 조향각, 방향지시등, 누적 주행거리
- 기어 D/P에 따른 주행·정차 화면 전환
- 경고등 자동 기록과 꺼짐 반영 (TPMS, ABS)
- 음성 인식: 호스트 마이크로 말한 문장이 차량 내장(ON_DEVICE) 엔진으로 인식되어 "브레이크 밟을 때 끼익 소리"로 스냅샷과 함께 기록됨
- 음성 안내(TTS) 재생 요청
- 현재 주소 수신과 스냅샷 반영, 위치 기록
- 기록 상세, 리포트, 직접 입력, 단골 정비소 등록

한계:

- 재현 조건 분석은 학습 모델이 아니라 단순 집계입니다. 값을 얻은 기록 중 70% 이상(최소 2건)에서 같은 조건이면 표시하고, 근거 건수를 항상 함께 보여 줍니다. 로직은 `PatternAnalyzerTest`(5개 테스트)로 확인합니다.
- 경고등은 종류가 다르면 다른 증상으로 보고 경고등별로 따로 묶습니다.
- 차량이 주지 않은 값은 추정하지 않고 "확인 불가"로 남깁니다.
- SDK가 제공하는 값은 정비 장비 수준의 제어기 통신 데이터가 아닙니다. 원인 진단이 아니라 "언제, 어떤 조건에서"를 정확히 전달하는 것이 목적입니다.
- 소리 자체는 녹음하지 않습니다. 공개 SDK에 마이크 원음을 받는 API가 없기 때문입니다.
- 증상 분류는 키워드 규칙 기반입니다.
- 실차에서는 검증하지 않았습니다.

## APK 위치

- `apk/Karte_Karte_release.apk` (release 서명, arm64-v8a / x86_64)

## 폴더 구조

```
app/src/main/java/com/karte/app/
  MainActivity.kt       화면, 주행·정차 전환, 기록 흐름
  VehicleMonitor.kt     Vehicle SDK 구독
  CarBridge.kt          차속, 기어, 주행 규제 (AAOS Car API)
  Voice.kt              SpeechToText / TextToSpeech SDK
  NaviBridge.kt         NaviHelper SDK
  LocationMonitor.kt    FusedLocation SDK
  PatternAnalyzer.kt    재현 조건 분석
  Models.kt             기록·스냅샷 데이터
  RecordStore.kt        앱 내부 저장
  Labels.kt             표시 문구, 자동 기록 대상 경고등
  Palette.kt            Pleos 기본 테마 토큰 기반 주간·야간 색상표
  Samples.kt            예시 기록
app/src/test/java/com/karte/app/
  PatternAnalyzerTest.kt
  LabelsTest.kt         단위 테스트: 표시 문구
  ModelsJsonTest.kt     단위 테스트: JSON 저장 형식, 분류
  StoreAnalysisIntegrationTest.kt  통합 테스트: 저장 → 다시 읽기 → 분석
tests/system/
  system_test.py        시스템 테스트: 에뮬레이터 전체 흐름 자동 확인
apk/                    release APK
docs/                   시연 영상, 테스트 문서(TESTING.md)
keystore/               공모전 제출용 서명 키
```
