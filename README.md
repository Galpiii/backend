# galpi

Spring Boot base template with common configurations

## GitHub App 설정

이 서비스의 안전성과 동작 여부는 코드가 아니라 **GitHub App 설정에 달려 있다.** 아래 표는 문서가
아니라 운영 계약이다. 값이 다르면 코드가 같아도 동작이 달라진다.

App 설정은 `Settings → Developer settings → GitHub Apps → {App}`에서 바꾼다.

| 설정 | 값 | 이유 |
|---|---|---|
| Callback URL | `{APP_SERVER_URL}/auth/github/callback` | 1A OAuth 콜백 |
| Setup URL | `{APP_SERVER_URL}/auth/github/setup/callback` | 설치 완료 콜백 |
| **Redirect on update** | **끔** | 켜면 저장소 추가·제거 후에도 setup 콜백이 오는데, 그 경로에는 `state`가 없어 `GITHUB-008`로 끝난다. 저장소 선택 변경은 콜백이 아니라 목록 새로고침으로 반영한다 |
| **Webhook Active** | **끔** | MVP는 웹훅을 쓰지 않는다. 권한 회수·설치 삭제는 웹훅이 아니라 **API 호출 실패로 감지**한다. 엔드포인트·서명 검증(`X-Hub-Signature-256`)·멱등성 처리는 Phase 2 |
| **Expire user authorization tokens** | **켬** | 끄면 `expires_in`이 응답에 오지 않아 만료 처리 전체가 무력해진다. 만료를 끄는 것이 더 편해 보이지만 보안상 더 나쁜 선택이다 |
| **Request user authorization (OAuth) during installation** | **끔** | 켜면 GitHub이 Setup URL을 비활성화하고 OAuth Callback URL로 보내므로 설치 state 검증 흐름이 동작하지 않는다. 로그인은 설치 전에 별도 OAuth 흐름으로 끝낸다 |

### 권한 (최소)

| 범위 | 권한 | 용도 |
|---|---|---|
| Repository → Metadata | Read-only | 저장소 목록·이름·기본 브랜치 |
| Repository → Contents | Read-only | Phase 1C 수집 |
| Repository → Pull requests | Read-only | Phase 1C 수집 |

### 토큰 정책

- **user access token은 8시간 후 만료된다.** 만료되면 GitHub 의존 기능이 `401 GITHUB-001
  (GITHUB_REAUTH_REQUIRED)`를 반환한다. 프론트는 이 코드를 갈피 로그아웃이 아니라 **GitHub
  재연결 배너**로 처리한다. 이미 승인된 앱이라 재인증은 클릭 한 번이다.
- **refresh token은 저장하지 않는다.** 받는 즉시 버리고 DB에 컬럼도 두지 않는다. 만료 시
  복구 경로는 OAuth 재인증뿐이다. 의도된 정책이며 Phase 2에서 재검토한다.
- 저장하는 access token은 `TOKEN_ENCRYPTION_KEY_V*`로 암호화한다. 로그에는 마스킹된 값만 남는다.

### 알려진 한계 (Phase 2 이전)

- **조직 관리자 승인 시점을 서버가 알 수 없다.** 승인 후의 setup 콜백은 (오더라도) 승인한 관리자
  브라우저로 가고 `state`도 없어 `GITHUB-008`로 끝난다. 요청한 사용자는 승인 뒤 목록을 새로고침하면
  설치가 보인다 — 목록은 `GET /user/installations`로 GitHub에 직접 묻기 때문이다. 승인 통지를
  서버가 직접 받으려면 Phase 2의 웹훅(`installation.created`)이 필요하다.
- 권한 회수·설치 삭제가 즉시 반영되지 않는다. `repositories.access_status`를 `INACCESSIBLE`로
  바꾸는 것은 Phase 1C의 수집 경로다. 그전까지 목록은 마지막으로 성공한 조회 결과를 보여준다.
- 저장소 연결 요청은 사용자의 installation을 순회한다. 요청한 저장소를 모두 찾으면 멈추며,
  존재하지 않는 id가 섞여도 요청 전체 API 호출 수와 deadline을 넘으면 중단한다. 서버 인스턴스
  하나에서 동일 사용자의 GitHub 조회도 설정된 개수 이상 병렬 실행되지 않는다.
- `GITHUB_MAX_PAGES`는 API 한 종류의 페이지 상한이고, `GITHUB_OPERATION_MAX_REQUESTS`는
  설치 목록부터 저장소 목록까지 한 사용자 작업 전체의 요청 상한이다.
- `GITHUB_OPERATION_TIMEOUT`은 다음 외부 요청을 시작할지 판단하는 soft deadline이다.
  이미 전송된 요청은 중단하지 않아 read timeout만큼 실제 종료가 늦어질 수 있다.

## 환경 변수

`.env.example`을 복사해 채운다. 위 표와 관련된 값:

| 변수 | 설명 |
|---|---|
| `APP_SERVER_URL` | 위 Callback/Setup URL의 앞부분 |
| `GITHUB_APP_SLUG` | 설치 URL(`github.com/apps/{slug}/installations/new`)에 쓴다. `GITHUB_APP_ID`(숫자)와 다르다 |
| `GITHUB_ALLOWED_REDIRECT_ORIGINS` | `returnTo` 화이트리스트. 비우면 기본 URI로만 보낸다 |
| `GITHUB_MAX_PAGES` | 페이지네이션 상한. 권한 판정 경로는 이 상한에 걸리면 잘린 목록을 쓰지 않고 `GITHUB-011`로 실패한다 |
| `GITHUB_OPERATION_MAX_REQUESTS` | 한 사용자 작업이 보낼 수 있는 GitHub API 요청 수. 기본 50 |
| `GITHUB_OPERATION_TIMEOUT` | 다음 GitHub 요청 시작을 막는 작업별 soft deadline. 기본 30초 |
| `GITHUB_OPERATION_MAX_CONCURRENT_PER_USER` | 사용자별 동시 GitHub 작업 수. 기본 1 |
| `GITHUB_OPERATION_ACQUIRE_TIMEOUT` | 사용자별 permit을 기다리는 최대 시간. 기본 3초 |
| `PROJECT_MAX_PER_USER` | 사용자당 프로젝트 수 상한. 기본 50. 삭제한 프로젝트는 세지 않는다 |
| `PROJECT_DEFAULT_PAGE_SIZE` / `PROJECT_MAX_PAGE_SIZE` | 프로젝트 목록 페이지 크기의 기본값과 상한. 기본 20 / 100 |
| `PR_DEFAULT_PAGE_SIZE` / `PR_MAX_PAGE_SIZE` | PR 목록 페이지 크기의 기본값과 상한. 기본 20 / 100 |
| `PR_MAX_DETAIL_FILES` / `PR_MAX_DETAIL_COMMITS` | PR 상세에 실을 변경 파일·커밋 수. 기본 200 / 200. 넘으면 각각의 `*Truncated` 값으로 알린다 |
| `OPENAI_SUMMARY_MODEL` | PR 요약 모델. 기본 `gpt-5-mini`. 명세서 추출(`OPENAI_MODEL`)과 나눈 이유는 요약이 저장소당 수백 건이라 모델 선택이 곧 비용이기 때문이다 |
| `OPENAI_SUMMARY_MAX_OUTPUT_TOKENS` | PR 요약의 출력 상한. 기본 2000. gpt-5 계열은 reasoning 토큰이 출력에 함께 과금된다 |
| `OPENAI_SUMMARY_BUDGET` | 요약 한 건의 예산. 기본 3분. OpenAI 호출 타임아웃으로 그대로 걸리므로 요약 한 건의 실제 상한이다. `PR_SUMMARY_LEASE`보다 짧게 둔다 |
| `PR_SUMMARY_WORKER_ENABLED` | PR 요약 워커를 띄울지. 기본 true. API만 서비스하는 인스턴스에서 false |
| `PR_SUMMARY_POLL_INTERVAL` / `PR_SUMMARY_LEASE` / `PR_SUMMARY_RETRY_BACKOFF` | 큐 폴링 주기, 선점 유효 기간, 일시 실패 재시도 간격. 기본 5초 / 10분 / 5초 |
| `PR_SUMMARY_MAX_ATTEMPTS` | 이 횟수를 넘게 시도된 요약은 `FAILED`가 된다. 기본 3. 사용자가 재요약을 누르면 0으로 돌아간다 |
| `PR_SUMMARY_BATCH_SIZE` / `PR_SUMMARY_MAX_CONCURRENCY` | 한 번에 선점할 요약 수와 그중 동시 처리 수. 기본 10 / 4 |
| `PR_SUMMARY_MAX_PATCH_CHARS` / `PR_SUMMARY_MAX_PATCH_CHARS_PER_FILE` | LLM 입력에 담을 diff 상한(문자 수). 기본 60000 / 8000 |
| `PR_SUMMARY_MAX_INPUT_CHARS` | 제목·본문·커밋·파일 목록·diff를 합친 최종 LLM 입력 상한. 기본 90000 |
| `PR_SUMMARY_MAX_FILE_PAGES` | 요약 입력을 만들 때 받아 올 변경 파일 페이지 수(페이지당 100개). 기본 2. 수집(`COLLECTION_MAX_PR_FILE_PAGES`)과 나눈 이유는 뒤쪽 페이지가 입력 상한에서 어차피 잘리기 때문이다 |
| `TASK_SCHEDULING_POOL_SIZE` | `@Scheduled` 워커 수. 기본 3(분석 워커·PR 요약 워커·토큰 폐기 배치) |
