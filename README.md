# 바이브키 (Vibe-key) 2.0

USB로 연결한 하드웨어 버튼(XIAO ESP32-S3 등)을 누르면 정해 둔 앱이 열리는 안드로이드 앱입니다.
2.0에서 **제미나이 AI 기능**, **어르신용 UI/UX**, **삼성 모드 및 루틴 연동** 세 가지가 새로 들어갔습니다.

---

## 1. 제미나이(Gemini) AI 기능

`GeminiClient.java` 하나로 모든 AI 기능을 처리합니다. (모델: `gemini-2.5-flash`, REST 직접 호출 · 추가 라이브러리 없음)

| 기능 | 어디서 | 하는 일 |
|---|---|---|
| **AI 도우미** | 홈 → `AI 도우미에게 물어보기`<br>기기 버튼 길게 누름<br>알림의 `AI 도우미` | 말로 물어보면 쉬운 한국어로 답하고 **소리로 읽어 줍니다**. 필요하면 "○○ 앱을 열까요?" 하고 앱까지 열어 줍니다. |
| **말로 앱 찾기** | 앱 고르기 → `말로 찾기` | "길 찾고 싶어", "아들한테 전화" 처럼 말하면 설치된 앱 중 알맞은 것을 골라 줍니다. |
| **AI 추천** | 홈 → `AI 추천 받기` | 설치된 앱과 지금 시간대를 보고 1·2·3번 버튼에 넣을 앱을 추천합니다. |
| **연결 확인** | 설정 → `연결 확인하기` | API 키가 실제로 동작하는지 검사합니다. |

**어르신 배려 프롬프트** — 항상 존댓말, 쉬운 낱말, 3문장 이내, 외래어 금지, 응급 상황이면 119 안내를
시스템 지시문(`GeminiClient.PERSONA`)에 고정해 두었습니다.

**AI 없이도 동작합니다** — 키가 없거나 인터넷이 안 되면 `AppRepository`의 한국어 키워드 사전으로
("길·지도·전화·약·병원·은행" 등 30여 개) 오프라인 추측 검색이 대신 동작합니다.

### API 키 넣는 방법 (둘 중 하나)

1. **앱 안에서** — 설정 → 제미나이 API 키에 붙여 넣고 `키 저장하기`.
   키는 이 휴대폰의 SharedPreferences에만 저장되고 밖으로 나가지 않습니다.
2. **빌드할 때** — `gradle.properties`에 아래 한 줄을 넣으면 기본값으로 들어갑니다.
   ```properties
   GEMINI_API_KEY=여기에_키
   ```
   (`BuildConfig.DEFAULT_GEMINI_API_KEY`로 주입됩니다. 이 파일은 깃에 올리지 마세요.)

키는 [Google AI Studio](https://aistudio.google.com)에서 무료로 받을 수 있습니다.

---

## 2. 어르신도 쓸 수 있는 UI/UX

기존 어두운 개발자용 화면을 **밝은 고대비 화면**으로 전부 새로 만들었습니다.

- **글자** 본문 21sp, 제목 27sp, 상태 34sp. 설정의 `글씨 더 크게`를 켜면 전체가 1.3배
  (`BaseActivity.attachBaseContext`에서 `fontScale` 조정).
- **색** 명도 대비 7:1 이상. 연결됨은 초록, 안 됨은 빨강 + 아이콘 + 글자까지 **세 가지로 동시에** 표시
  (색만으로 구분하지 않음).
- **누르는 곳** 최소 72dp, 주요 단추 88dp. 마이크 단추는 140dp.
- **말** "연결 안 됨" → "연결이 안 되었어요", "USB Serial Monitor" → "기기를 휴대폰에 꽂아 주세요"
  처럼 전부 쉬운 말로 바꿨습니다.
- **소리·진동** 중요한 변화는 화면 + 음성(TTS, 0.92배속) + 진동 세 가지로 함께 알립니다.
  화면을 못 보셔도 상태 카드를 누르면 지금 상태를 읽어 드립니다.
- **화면 구조** 홈은 `지금 상태 → 버튼 3개 → 도움 받기` 순서 하나뿐. 나머지는 모두 뒤로 숨겼습니다.
- **되돌리기 쉽게** 앱을 넣기 전 "이 앱이 맞나요?", 앱을 열기 전 "지금 열어 드릴까요?"를 먼저 묻습니다.

관련 파일: `activity_main.xml`, `item_slot.xml`, `activity_ai.xml`, `activity_app_picker.xml`,
`activity_settings.xml`, `activity_routine.xml`, `values/{colors,dimens,strings,themes}.xml`

---

## 3. 삼성 모드 및 루틴 연동

두 방향 모두 됩니다. 앱 안 `설정 → 삼성 루틴 연결` 화면에서 1·2·3단계로 안내하고,
**루틴이 부르는 것과 똑같은 경로로 지금 바로 시험**해 볼 수 있습니다.

### 루틴 → 바이브키 (루틴이 우리 앱을 시킴)

| 방법 | 내용 |
|---|---|
| **앱 바로가기** (가장 쉬움) | 루틴 → 실행할 동작 → `앱 실행` → 바이브키 → `1번 버튼` 선택.<br>`RoutineBridge.refreshShortcuts()`가 동적 바로가기 4개(버튼 1·2·3 + AI 도우미)를 등록하고, 연결 앱이 바뀌면 이름도 따라 바뀝니다. |
| **딥링크** | `vibekey://run/1` · `vibekey://ai` · `vibekey://speak?text=약 드실 시간이에요` |
| **브로드캐스트** | `com.example.vibekey.action.RUN_BUTTON` (extra `button` = 1~3)<br>`…OPEN_AI`, `…SPEAK` (extra `text`) |
| **Routines SDK Provider** | `RoutineActionProvider` (authority `com.example.vibekey.routine.action`)<br>동작 목록은 `res/xml/routine_action.xml`에 선언 |

시험용 명령:
```bash
adb shell am broadcast -a com.example.vibekey.action.RUN_BUTTON --ei button 1 \
  -n com.example.vibekey/.RoutineActionReceiver
adb shell am start -a android.intent.action.VIEW -d "vibekey://run/2"
```

### 바이브키 → 루틴 (우리 앱이 생긴 일을 알림)

| 신호 | 함께 오는 값 |
|---|---|
| `com.example.vibekey.event.BUTTON_PRESSED` | `button`(int), `package`, `label`, `source`(hardware/routine/screen/shortcut) |
| `com.example.vibekey.event.DEVICE_STATE` | `connected`(boolean) |

조건 선언은 `res/xml/routine_condition.xml`에 있습니다. 설정에서 끌 수 있습니다.

> **참고** — 삼성 공식 Routines SDK(.aar)는 배포가 제한되어 있어 이 프로젝트에는 넣지 않았습니다.
> `RoutineActionProvider`는 SDK 없이도 동작하도록 호출 규격만 직접 구현한 것이라,
> 삼성 단말 기종·버전에 따라 동작 목록이 루틴 앱에 안 뜰 수 있습니다.
> **어느 기종에서나 확실히 되는 경로는 앱 바로가기와 딥링크**이며, 앱 안내 화면도 그 방법을 먼저 알려 줍니다.
> 공식 SDK를 구하면 `app/libs/`에 넣고 `RoutineActionProvider`가 `SepRoutineActionProvider`를
> 상속하도록 바꾸면 정식 연동이 됩니다.

---

## 4. 기기(펌웨어)가 보내는 신호

시리얼 115200 8N1, 한 줄에 하나씩 (`\n` 로 끝):

| 보내는 값 | 동작 |
|---|---|
| `True` · `Button1` · `1` | 1번 버튼 앱 실행 |
| `Button2` · `2` | 2번 버튼 앱 실행 |
| `Button3` · `3` | 3번 버튼 앱 실행 |
| `AI` · `Long1` · `ButtonLong1` | **AI 도우미 열고 바로 듣기 시작** |

버튼별로 3초 디바운스가 걸려 있어 손이 떨려 여러 번 눌려도 한 번만 실행됩니다.

---

## 5. 테스트

### 5-1. 앱 안의 테스트(자가진단) 화면 — `설정 → 잘 되는지 검사하기`

이 앱은 하드웨어·인터넷·권한이 모두 맞아야 동작해서, "왜 안 되는지"를 사용자가 스스로 알 수 있어야 합니다.

- **모두 검사하기** — 13가지를 한 번에 검사해 ✅ / ⚠️ 로 보여 줍니다.
  기기 연결 / 다른 앱 위에 표시 / 배터리 절약 예외 / 알림 권한 / 인터넷 /
  말로 입력 / 소리로 읽어 주기(실제로 한 문장 읽어 줌) / 1·2·3번 버튼 앱의 설치 여부 /
  루틴 바로가기 등록 / 오프라인 낱말 사전 / **제미나이 AI 실제 호출**
- **기기 없이 버튼 눌러 보기** — `1번` `2번` `3번` `AI 도우미` 단추가
  `UsbSerialService.simulate()`로 가짜 신호를 넣습니다. 진짜 시리얼로 들어온 것과 **완전히 같은 경로**로
  처리되므로, 보드가 없어도 전체 동작을 시험할 수 있습니다. (테스트 신호는 디바운스를 건너뜁니다.)
- **받은 신호 기록** — 기기가 보낸 원문과 그때 한 일을 시각과 함께 최근 60줄까지 보여 줍니다.
  펌웨어가 이상한 값을 보내고 있는지 눈으로 확인할 수 있습니다.

### 5-2. 자동화된 테스트 코드

```bash
./gradlew :app:testDebugUnitTest          # 순수 로직 30개 (기기 불필요, 1초)
./gradlew :app:connectedDebugAndroidTest  # 기기/에뮬레이터 7개
```

검증된 결과: **단위 30개 + 계측 7개 = 37개 전부 통과.**

| 파일 | 무엇을 지키는가 |
|---|---|
| `SignalParserTest` (8) | 버튼 신호 해석. 특히 `ButtonLong1`(길게 누름)이 `Button1`로 잘못 읽혀 AI 대신 앱만 열리는 사고를 막습니다. 잡음·빈 줄·null도 검사. |
| `GeminiJsonTest` (6) | 제미나이가 ```` ```json ```` 으로 감싸거나 앞뒤에 말을 붙여 답해도 JSON을 꺼내는지. 이게 약하면 AI 답이 통째로 날아갑니다. |
| `KeywordMatcherTest` (7) | AI 없이 쓰는 오프라인 사전. "길 찾고 싶어", "아들한테 전화", "약 먹을 시간" 같은 실제 말투로 검사. |
| `RoutineSlotParseTest` (5) | 루틴이 보내는 버튼 번호(`2`, `button=3`, 딥링크)를 읽고, **범위 밖 번호는 무시**하는지. |
| `PrefsKeyTest` (4) | 저장 키의 하위 호환. 깨지면 업데이트 시 사용자 설정이 전부 사라집니다. |
| `VibeKeyInstrumentedTest` (7) | 실제 저장/읽기, 예전 버전 데이터 호환, 신호 기록, 루틴 바로가기 등록, 기본값(음성·진동 켜짐). |

테스트를 쉽게 하려고 순수 로직을 `SignalParser` · `KeywordMatcher` · `RoutineBridge.parseSlotFromText`로
분리했고, 서비스와 화면은 이 클래스들을 쓰도록 바꿨습니다.

---

## 6. 빌드

```bash
./gradlew :app:assembleDebug     # 개발용
./gradlew :app:assembleRelease   # 배포용 (서명됨)
```

### 배포용 APK

| | |
|---|---|
| 파일 | `VibeKey-2.0.apk` (프로젝트 최상단, 4.4MB) |
| 원본 | `app/build/outputs/apk/release/app-release.apk` |
| 서명 | `vibekey-release.jks` / 별칭 `vibekey` (유효기간 10,000일) |
| 확인 | 서명 검증 통과, 에뮬레이터에 새로 설치 후 실행·크래시 0건 |

휴대폰에 넣는 방법: APK 파일을 휴대폰으로 옮긴 뒤 파일 관리자에서 누르고,
"출처를 알 수 없는 앱 설치"를 한 번 허용해 주면 됩니다.

서명 정보는 `keystore.properties`에 따로 두었고, `app/build.gradle`이 이 파일을 읽습니다.
이 파일과 `*.jks`는 `.gitignore`에 넣어 두어 저장소에는 올라가지 않습니다.
(파일이 없으면 서명 없이 빌드되고, 개발용 debug 빌드는 영향받지 않습니다.)

> ⚠️ **`vibekey-release.jks`와 `keystore.properties`는 따로 백업해 두세요.**
> 저장소에 올라가지 않으므로 이 컴퓨터에만 있습니다. 잃어버리면
> **같은 앱의 업데이트를 배포할 수 없습니다**(사용자가 지우고 다시 깔아야 함).
> 반대로 유출되면 남이 이 앱을 사칭한 업데이트를 만들 수 있으니 공유하지 마세요.

### 빌드가 안 되던 원인과 조치 (해결 완료)

1. **JDK 버전 불일치** — Gradle 8.6 + AGP 8.4는 **JDK 17**에서만 돌아가는데,
   Android Studio가 번들한 최신 JBR(**Java 25**)로 잡혀 있어
   `Unsupported class file major version 69` 로 실패했습니다.
   - Temurin JDK 17을 `~/Library/Java/JavaVirtualMachines/temurin-17.jdk` 에 설치했습니다.
   - `gradle.properties`의 `org.gradle.java.home` 과 `.gradle/config.properties`(Studio용)를
     그 경로로 지정해 두었습니다. **Studio에서 따로 설정할 것 없이 그대로 Sync 하면 됩니다.**
2. **윈도우 경로 잔재** — `app/.gradle/config.properties` 에 `C:\Program Files\...` 가 남아 있어
   맥에서 빌드가 되지 않았습니다. 맥 경로로 고쳤습니다.
3. **윈도우 전용 빌드 폴더** — `buildDir = "C:/tmp/VibeKeyBuild"` 설정을 지웠습니다.

> 다른 컴퓨터에서 빌드할 때는 `org.gradle.java.home` 경로만 그 컴퓨터의 JDK 17 위치로 바꾸면 됩니다.

확인된 결과 (환경변수 없이 `./gradlew clean assembleDebug testDebugUnitTest` 성공):
`app/build/outputs/apk/debug/app-debug.apk`

### 실행이 안 되던 원인과 조치 (해결 완료)

앱이 켜지자마자 튕기던 문제(기존 코드의 버그)를 고쳤습니다.
안드로이드 14부터 `connectedDevice` 형식의 포그라운드 서비스는 **USB 기기가 실제로 꽂혀 있을 때만**
허용되는데, `UsbSerialService`가 무조건 시작해 `SecurityException`으로 즉시 죽었습니다.

- 기기가 꽂혀 있을 때만 포그라운드로 올리고, 연결에 성공한 순간 격상 / 빠지면 해제하도록 바꿨습니다.
- `BootReceiver`도 기기가 있을 때만 서비스를 시작합니다. (없으면 기기를 꽂는 순간 깨어납니다.)
- 격상이 거부돼도 앱이 죽지 않도록 감쌌습니다.

## 6. 꼭 켜 주셔야 하는 권한

설정 화면 맨 아래에서 상태를 확인하고 바로 이동할 수 있습니다.

1. **다른 앱 위에 표시** — 백그라운드에서 앱을 열려면 필요합니다. 없으면 버튼을 눌러도 앱이 안 열립니다.
2. **배터리 절약 예외** — 없으면 한참 뒤에 버튼이 안 먹을 수 있습니다.
3. 알림 권한(안드로이드 13+) — 상시 알림 표시용.
