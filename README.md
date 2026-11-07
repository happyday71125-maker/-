# Pro Launcher AI (Android Native Project - Kotlin & Jetpack Compose)

[Pro Launcher](https://play.google.com/store/apps/details?id=app.prolauncher&hl=en)([Olauncher](https://play.google.com/store/apps/details?id=app.olauncher&hl=en) 기반) 스타일의 **텍스트 중심 미니멀리즘 안드로이드 홈 런처**에 **온디바이스 로컬 AI 스마트 메모 & 할 일(Notes & Tasks)** 기능을 결합한 안드로이드 네이티브 프로젝트입니다.

## 주요 기능

1. **미니멀 텍스트 홈 화면 (`MinimalHomeScreen`)**
   - 아이콘 배제 텍스트 전용 즐겨찾기 앱 목록(최대 7개), 실시간 시계·날짜 및 오늘 남은 할 일 카운트 표시
   - 좌우 스와이프(`HorizontalPager`)로 **왼쪽: 초성 앱 서랍**, **가운데: 미니멀 홈**, **오른쪽: 로컬 AI 메모 & 할 일** 즉시 전환
2. **한국어 초성 검색 & 자동 실행 (`KoreanChosungMatcher`)**
   - `ㅋㅋㅇㅌ` → `카카오톡`, `ㅇㅌㅂ` → `유튜브` 등 초성 및 부분 문자열 즉시 매칭
   - 검색 결과가 1개일 때 자동으로 앱을 실행하며, [Pro Launcher](https://play.google.com/store/apps/details?id=app.prolauncher&hl=en)와 동일하게 검색어 앞에 공백(`Space`)을 입력하면 자동 실행을 일시 해제합니다.
3. **방해 앱 인터럽트 (`MindfulInterruptOverlay`)**
   - 유튜브·인스타그램 등 시간 소모성 앱 실행 시 5초간 호흡 가다듬기 화면을 표시해 무의식적인 앱 실행을 줄여줍니다.
4. **3단계 하이브리드 로컬 AI 메모 & 할 일 (`LocalAiNoteEngine`)**
   - **1순위 — [Google AI Edge MediaPipe LLM Inference](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android) (`com.google.mediapipe:tasks-genai`)**: 기기 내 `/data/local/tmp/llm/` 또는 앱 내부 저장소에 Gemma-2B/Gemma-3n `.bin`/`.task` 모델 파일이 존재하면 완전 오프라인 온디바이스 LLM 추론을 수행합니다.
   - **2순위 — 로컬 루프백 Ollama 연동 (`http://127.0.0.1:11434`)**: Termux 등으로 로컬 LLM 서버 구동 시 연동됩니다.
   - **3순위 — 내장 한국어 온디바이스 NLP 파서 (기본 내장, 0MB 추가 다운로드)**: 별도 모델 파일 없이도 한국어 날짜/시간(`내일 오후 3시`, `금요일`), 행위 동사(`~하기`, `~사기`, `~제출`), 우선순위(`급함`, `중요`, `마감`), 카테고리 해시태그를 0ms 만에 자동 분류하여 할 일(To-Do) 체크리스트로 변환합니다.

## APK 파일 빌드 방법 (2가지)

### 방법 1: PC에 프로그램 설치 없이 GitHub Actions로 자동 APK 추출 (가장 간편)
1. 본 압축 파일(`pro_launcher_ai_android.zip`)의 압축을 풀고 본인의 GitHub 새 저장소(Repository)에 업로드합니다.
2. 프로젝트 내 `.github/workflows/build-apk.yml` 워크플로우가 자동으로 실행되어 클라우드에서 Gradle 빌드를 수행합니다.
3. GitHub 저장소의 **Actions** 탭 → 완료된 워크플로우 클릭 → 하단 **Artifacts**에서 **`ProLauncherAI-debug-apk`**를 다운로드하면 스마트폰에 바로 설치 가능한 `.apk` 파일을 얻을 수 있습니다.

### 방법 2: Android Studio에서 직접 APK 빌드
1. [Android Studio](https://developer.android.com/studio)에서 이 프로젝트 폴더(`ProLauncherAI`)를 엽니다.
2. Gradle Sync가 완료되면 상단 메뉴에서 **Build > Build Bundle(s) / APK(s) > Build APK(s)**를 클릭합니다.
3. `app/build/outputs/apk/debug/app-debug.apk` 경로에 생성된 APK 파일을 안드로이드 스마트폰으로 전송하여 설치한 뒤, **설정 > 기본 앱 > 홈 앱**에서 **Pro Launcher AI**를 선택합니다.
