# Y700 3세대 설치 안내

이 ZIP 파일은 APK가 아니라 안드로이드 프로젝트 소스입니다. 이전 v2 베타와 같은 `com.btc.analyzer` 앱이지만, 디버그 빌드 서명키가 다르면 기존 설치 앱 위로 덮어쓸 수 없습니다. 이 경우 기존 앱 데이터를 별도 보관한 뒤 제거하고 새 APK를 설치해야 할 수 있습니다.

1. 태블릿에서 ZIP을 다운로드합니다.
2. 이미 열려 있는 GitHub Codespaces에서 탐색기 상단 `…` → `Upload...` 메뉴가 **실제로 있는 경우** ZIP을 업로드합니다. 이 메뉴가 없다면 태블릿의 브라우저 데스크톱 모드에서 Codespaces 파일 탐색기의 업로드 아이콘을 확인하세요. 업로드 기능 자체가 없다면 파일을 GitHub 웹 저장소의 `Add file` → `Upload files`로 업로드하되, ZIP을 올리기 전에 태블릿 파일 앱에서 압축을 풀어 내용물을 업로드하세요. GitHub 저장소는 ZIP 내부의 `app`, `.github`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`가 최상위에 있어야 합니다.
3. ZIP을 Codespaces 루트에 올렸다면 터미널 명령: `unzip -o bybit-futures-analyzer-v2.1-smart-plan.zip`
4. 확인: `ls -la` 와 `ls app/src/main/java/com/btc/analyzer`.
5. `git add . && git commit -m "Add Smart Trade Plan" && git push`.
6. GitHub 저장소 → Actions → Build Bybit Futures APK → 성공 표시 확인 → Artifacts에서 debug apk ZIP 다운로드 → 압축 해제 → app-debug.apk 설치.
7. 앱 실행 후 선물 종목 선택 → 차트 분석 버튼 → LONG/SHORT, 진입가·수수료 입력 → 계산 → 결과 확인 → 원할 때만 가디언 감시 시작. 알림 권한 및 Y700 배터리 백그라운드 제한 확인.

**주의:** 백그라운드 감시는 보장되지 않습니다. 거래소 손절 주문을 반드시 따로 등록하세요. 이 코드의 APK 빌드/실기기 실행은 아직 확인되지 않았습니다.
