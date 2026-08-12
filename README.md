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
| **Redirect on update** | **끔** | 켜면 저장소 추가·제거 후에도 setup 콜백이 오는데, 그 경로에는 install-intent가 없어 `GITHUB-008`로 끝난다. 저장소 선택 변경은 콜백이 아니라 목록 새로고침으로 반영한다 |
| **Webhook Active** | **끔** | MVP는 웹훅을 쓰지 않는다. 권한 회수·설치 삭제는 웹훅이 아니라 **API 호출 실패로 감지**한다. 엔드포인트·서명 검증(`X-Hub-Signature-256`)·멱등성 처리는 Phase 2 |
| **Expire user authorization tokens** | **켬** | 끄면 `expires_in`이 응답에 오지 않아 만료 처리 전체가 무력해진다. 만료를 끄는 것이 더 편해 보이지만 보안상 더 나쁜 선택이다 |
| Request user authorization (OAuth) during installation | 켬 | 설치와 로그인을 한 흐름으로 잇는다 |

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

- 권한 회수·설치 삭제가 즉시 반영되지 않는다. `repositories.access_status`를 `INACCESSIBLE`로
  바꾸는 것은 Phase 1C의 수집 경로다. 그전까지 목록은 마지막으로 성공한 조회 결과를 보여준다.
- 저장소 연결 요청은 사용자의 installation을 순회한다. 요청한 저장소를 모두 찾으면 멈추지만,
  존재하지 않는 id가 섞이면 끝까지 훑는다. 사용자별 rate limit과 요청 전체 페이지 budget은
  아직 없다.
- `GITHUB_MAX_PAGES`는 **호출 한 번당** 상한이다. 요청 전체의 상한이 아니다.

## 환경 변수

`.env.example`을 복사해 채운다. 위 표와 관련된 값:

| 변수 | 설명 |
|---|---|
| `APP_SERVER_URL` | 위 Callback/Setup URL의 앞부분 |
| `GITHUB_APP_SLUG` | 설치 URL(`github.com/apps/{slug}/installations/new`)에 쓴다. `GITHUB_APP_ID`(숫자)와 다르다 |
| `GITHUB_ALLOWED_REDIRECT_ORIGINS` | `returnTo` 화이트리스트. 비우면 기본 URI로만 보낸다 |
| `GITHUB_MAX_PAGES` | 페이지네이션 상한. 권한 판정 경로는 이 상한에 걸리면 잘린 목록을 쓰지 않고 `GITHUB-011`로 실패한다 |
