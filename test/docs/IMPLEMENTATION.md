# 구현 설명

이 문서는 `test` 아래의 Next.js 프런트엔드, Spring Boot 백엔드, PostgreSQL 스키마, Service Worker, Docker Compose와 Caddy 설정을 실제 코드 기준으로 설명합니다.

## 목표와 성공 기준

한 개의 공개 HTTPS origin에서 다음을 검증합니다.

1. Android와 iOS에서 웹 앱을 홈 화면에 설치한다.
2. 설치마다 발급된 익명 서버 세션으로 카운터를 `0~10` 범위에서 개별 유지한다.
3. 알림 권한은 사용자가 **알림 활성화** 버튼을 누른 흐름에서만 요청한다.
4. 사용자가 시계 테스트를 시작하면 즉시 알림 없이 `+1분`, `+2분`, `+3분`, `+4분`, `+5분`에 하나씩 총 5개의 현재 시각 Web Push를 생성한다.
5. 다섯 알림은 서로 교체되지 않고 개별 항목으로 쌓인다.
6. Service Worker가 `showNotification()`을 완료한 직후 메시지별 best-effort 수신 ACK를 서버에 보낸다.
7. 앱을 닫았다 다시 열어도 같은 설치의 진행도, 구독, 실행 상태와 알림 이력을 복원한다.

Web Push 전송 성공은 브라우저 push service의 접수를 뜻합니다. 네트워크, 절전, 집중 모드, 알림 요약과 운영체제 정책 때문에 기기 표시가 늦거나 누락될 수 있으므로 초 단위 도착은 성공 조건이 아닙니다.

## 구성과 버전

| 계층 | 구현 | 책임 | 공개 범위 |
|---|---|---|---|
| Edge | Caddy 2 Alpine | 자동 TLS, HTTP→HTTPS, 동일-origin reverse proxy | `80/tcp`, `443/tcp`, `443/udp` |
| UI | Next.js 16.3.3, React 19.2.0, Node 24 Alpine | 설치 gate, 카운터, Push 구독, 상태·이력 UI, Service Worker | Caddy 내부 `3000` |
| API | Spring Boot 4.1.1, Java 21, `web-push` 5.1.2 | 세션·CSRF, 진행도, 구독 검증, 예약·전송·ACK | Caddy 내부 `8080` |
| DB | PostgreSQL 16 Alpine, Flyway | 모든 서버 상태와 전송 결과 영속화 | 내부 `5432` |

Caddy는 `/api/*`를 prefix 그대로 `backend:8080`에 전달하고 나머지 경로를 `frontend:3000`에 전달합니다. 페이지, API, Service Worker와 쿠키가 모두 `https://APP_DOMAIN` 하나에 속합니다.

Docker의 `data` network는 `internal: true`이며 PostgreSQL은 이 network에만 연결됩니다. 백엔드는 `data`로 PostgreSQL에 접근하고 `edge`로 Caddy 및 외부 FCM/Apple Web Push endpoint와 통신합니다. 호스트에는 3000, 8080, 5432를 publish하지 않습니다.

주요 소스 구조는 다음과 같습니다.

```text
test/
  Caddyfile
  docker-compose.yml
  frontend/
    Dockerfile                      Node 24 multi-stage standalone image
    .dockerignore                   node_modules, build output, env 제외
    public/sw.js                    Service Worker, offline shell, Push 표시와 ACK
    src/components/pwa-test-app.tsx 설치 gate와 전체 사용자 흐름
    src/lib/api.ts                  동일-origin API client와 응답 정규화
    src/lib/idb.ts                  설치별 cache와 counter outbox
    src/app/manifest.ts             PWA manifest
  backend/
    Dockerfile                      Java 21 multi-stage JAR image
    .dockerignore                   target, log, env 제외
    src/main/java/.../web/          controller, DTO, API security filter
    src/main/java/.../service/      세션·카운터·구독·시계·전송·ACK 로직
    src/main/java/.../persistence/  JDBC 저장소와 row locking
    src/main/resources/db/migration/V1__create_stamp_push_schema.sql
  docs/
    IMPLEMENTATION.md
    FREE_SERVER_DEPLOYMENT.md
```

## 환경 변수 계약

| 변수 | 값/기본 | 용도 |
|---|---|---|
| `APP_DOMAIN` | 필수 | scheme·port·path 없는 Caddy 공개 hostname |
| `APP_ALLOWED_ORIGINS` | 필수 | 쉼표 구분 허용 origin. 기본 배포는 `https://APP_DOMAIN` 하나 |
| `POSTGRES_DB` | `espero_push` | DB 이름 |
| `POSTGRES_USER` | `espero_push` | 애플리케이션 DB 사용자 |
| `POSTGRES_PASSWORD` | 필수 비밀 | DB 비밀번호 |
| `TEST_ACCESS_CODE` | 필수 비밀 | 신규 익명 세션 발급용 공유 gate. 사용자 ID나 관리자 암호가 아님 |
| `APP_TOKEN_PEPPER` | 필수 비밀 | 세션 token HMAC과 알림 ACK HMAC에 쓰는 최소 32 UTF-8 byte 고엔트로피 값 |
| `APP_VAPID_PUBLIC_KEY` | 필수 | 브라우저의 Push 구독 application server key |
| `APP_VAPID_PRIVATE_KEY` | 필수 비밀 | Web Push VAPID 서명 key |
| `APP_VAPID_SUBJECT` | 필수 | `mailto:` 또는 HTTPS 운영자 연락처 |
| `APP_SESSION_TTL` | `P7D` | 익명 세션과 participant의 고정 수명 |
| `APP_SESSION_CLEANUP_DELAY` | `PT1H` | 만료 participant 삭제 주기 |
| `APP_COOKIE_SECURE` | 운영에서 `true` | 세션 쿠키의 `Secure`와 쿠키 이름 결정 |
| `APP_PUSH_ENABLED` | Compose에서 `true` | 외부 Push kill switch |
| `APP_CLOCK_ZONE` | `Asia/Seoul` | API·알림 timestamp와 본문 시계 zone |
| `APP_PUSH_ALLOWED_ENDPOINT_HOST_SUFFIXES` | Google, Apple, Mozilla, Microsoft WNS 기본값 | Push endpoint SSRF allowlist |
| `APP_ACCESS_MAX_FAILURES` | `5` | IP별 접근 코드 실패 한도 |
| `APP_ACCESS_FAILURE_WINDOW` | `PT15M` | 접근 코드 fixed-window 제한 시간 |
| `APP_ACK_MAX_REQUESTS` | `60` | IP별 ACK 요청 한도 |
| `APP_ACK_RATE_WINDOW` | `PT1M` | ACK fixed-window 제한 시간 |
| `APP_PUSH_TTL` | `PT70S` | 각 Push message의 push service 보관 TTL |
| `APP_PUSH_MAX_ATTEMPTS` | `3` | subscription delivery 최대 시도 횟수 |
| `APP_PUSH_REQUEST_TIMEOUT` | `PT15S` | push service HTTP 요청 1회 제한 시간 |
| `APP_PUSH_WORKER_DELAY` | `PT5S` | due delivery worker 주기 |
| `APP_CLOCK_SCHEDULER_DELAY` | `PT5S` | due clock run dispatcher 주기 |
| `APP_SCHEDULER_POOL_SIZE` | `2` | Spring scheduler thread pool 크기 |
| `SPRING_DATASOURCE_MAXIMUM_POOL_SIZE` | `10` | Hikari 최대 connection 수 |
| `SPRING_DATASOURCE_MINIMUM_IDLE` | `1` | Hikari 최소 idle connection 수 |
| `SPRING_PROFILES_ACTIVE` | Compose에서 `prod` | secure cookie와 비노출 health detail 설정 |
| `SERVER_FORWARD_HEADERS_STRATEGY` | Compose에서 `framework` | Caddy의 forwarded HTTPS 정보 반영 |
| `NEXT_PUBLIC_API_BASE_PATH` | build 시 `/api/v1` | 프런트 동일-origin API prefix. 절대 URL은 허용하지 않음 |

Compose는 `NEXT_PUBLIC_API_BASE_PATH=/api/v1`을 frontend image의 build argument로 넘기고 Dockerfile은 builder stage의 환경 값으로 설정합니다. 이는 Next.js client bundle에 compile되는 공개 경로이므로 비밀값을 넣지 않습니다. 양쪽 `.dockerignore`는 local build output, log와 `.env*`를 build context에서 제외하며 frontend만 비밀이 없는 `.env.example`을 다시 허용합니다. 실제 `.env`가 image layer로 복사되지 않게 하는 설정입니다.

`ACK_TOKEN`은 환경 변수가 아닙니다. 서버가 메시지마다 아래 값으로 결정적으로 계산하고 DB에는 raw ACK token을 저장하지 않습니다.

```text
Base64url-no-padding(
  HMAC-SHA-256(APP_TOKEN_PEPPER, UTF8("ack:" + notificationId))
)
```

ACK 수신 시 서버는 같은 값을 다시 계산해 `MessageDigest.isEqual`로 상수시간 비교합니다. `APP_TOKEN_PEPPER`를 회전하면 기존 익명 세션과 아직 ACK되지 않은 알림 token이 함께 무효화되므로 배포·복원 간 동일하게 보관해야 합니다.

## 설치와 bootstrap 흐름

브라우저 탭에서는 설치 방법과 기능 진단만 표시합니다. `display-mode: standalone` 또는 iOS의 `navigator.standalone`이 확인된 홈 화면 앱에서만 session bootstrap과 기능 UI를 시작합니다.

```text
설치 앱 시작
  -> IndexedDB cache 확인
  -> POST /api/v1/session {} + 기존 HttpOnly cookie
     -> 유효한 고정 수명 세션: state, csrfToken, ownerKey 반환
     -> 유효 세션 없음: 403 후 접근 코드 화면
  -> 사용자가 TEST_ACCESS_CODE 제출
  -> POST /api/v1/session {accessCode}
  -> 서버가 participant/counter/session 생성, cookie 설정
  -> ownerKey가 같은 IndexedDB outbox만 복원·전송
```

세션의 raw 32-byte random token은 브라우저 쿠키에만 있고 서버에는 `HMAC-SHA-256(APP_TOKEN_PEPPER, "session:" + rawToken)`의 hex 값만 저장됩니다. 쿠키 속성은 다음과 같습니다.

- 운영 이름: `__Host-stamp_sid`; `APP_COOKIE_SECURE=false`인 로컬 개발은 `stamp_sid`
- `HttpOnly`, `Path=/`, `SameSite=Lax`
- 운영 profile에서 `Secure=true`
- `Max-Age`는 발급 시점부터 남은 `APP_SESSION_TTL`; 요청할 때 연장되지 않는 고정 만료

유효 세션 요청은 `last_seen_at`만 갱신합니다. 만료 participant는 기본 한 시간마다 삭제되며 foreign key cascade로 진행도, 세션, 구독, 실행, 알림과 delivery도 함께 삭제됩니다.

`ownerKey`는 IndexedDB outbox가 쿠키 삭제 뒤 새 participant에게 잘못 재생되는 것을 막는 client-side partition key입니다. 인증 정보나 복구 코드가 아닙니다. 사이트 데이터/PWA 삭제, 쿠키 삭제, 다른 브라우저·기기로 변경한 뒤에는 기존 participant를 복구할 수 없습니다.

## API 보안 규칙

- 모든 API 응답은 `Cache-Control: no-store`입니다.
- `POST`, `PUT`, `DELETE`는 ACK 예외를 제외하고 요청 `Origin`이 `APP_ALLOWED_ORIGINS`의 정확한 문자열과 일치해야 합니다.
- `/api/v1/me/**`는 ACK 예외를 제외하고 유효 세션 쿠키가 필요합니다.
- `/api/v1/me/**`의 상태 변경 요청은 bootstrap 응답의 `csrfToken`을 `X-CSRF-Token`에 넣습니다.
- ACK endpoint는 Service Worker가 cookie 없이 호출할 수 있도록 세션·Origin·CSRF 대신 메시지 capability token과 IP rate limit을 사용합니다.
- 신규 접근 코드 오입력 제한은 기본 IP당 15분에 5회입니다. 코드가 빠진 최초 bootstrap은 `403 ACCESS_CODE_REQUIRED`를 반환하되 실패 횟수를 소비하지 않습니다. 제한 상태와 값은 메모리에 있으므로 단일 backend replica용 방어입니다.
- ACK 제한은 기본 IP당 1분에 60회입니다.

오류 JSON은 모든 controller/filter 오류에서 같은 envelope를 사용합니다.

```json
{
  "error": {
    "code": "CSRF_TOKEN_INVALID",
    "message": "The CSRF token is missing or invalid.",
    "details": [],
    "timestamp": "2026-09-05T00:00:00+09:00",
    "path": "/api/v1/me/counter-operations"
  }
}
```

validation 오류의 `details`는 `{ "field": "...", "message": "..." }` 배열입니다. rate-limit 응답에는 초 단위 `Retry-After` header가 붙습니다.

## API 계약

공통 prefix는 `/api/v1`입니다. 아래 응답은 `data` wrapper 없이 JSON 객체로 반환됩니다.

| 기능 | method/path | 요청 body | 응답 |
|---|---|---|---|
| 공개 설정 | `GET /public-config` | 없음 | `PublicConfigResponse` |
| 세션 확인·생성 | `POST /session` | `{ "accessCode": "..." }`; 기존 세션은 `{}` 가능 | `SessionResponse` + `Set-Cookie` |
| 전체 상태 | `GET /me/state` | 없음 | `StateResponse` |
| 카운터 연산 | `POST /me/counter-operations` | `{ "operationId": UUID, "delta": -1 또는 1 }` | `{ "counter": Counter, "duplicate": boolean }` |
| 구독 등록·갱신 | `POST /me/push-subscriptions` | endpoint, expirationTime, keys, locale, timeZone | `{ "subscription": PushSubscription }` |
| 구독 해제 | `DELETE /me/push-subscriptions/{subscriptionId}` | 없음 | `204 No Content` |
| 즉시 알림 | `POST /me/notifications/test-now` | `{}` | `{ "notification": Notification }` |
| 수신 ACK | `POST /me/notifications/ack` | notificationId, ackToken, receivedAt | `{ "notification": Notification }` |
| 시계 테스트 시작 | `PUT /me/clock-test` | `{ "durationMinutes": 5 }` | `{ "clockTest": ClockTest }` |
| 최근 시계 테스트 | `GET /me/clock-test` | 없음 | `{ "clockTest": ClockTest 또는 null }` |
| 시계 테스트 중지 | `DELETE /me/clock-test` | 없음 | `{ "clockTest": ClockTest 또는 null }` |

공개 설정은 VAPID private key나 비밀값을 포함하지 않습니다. `pushEnabled`는 단순 env 값이 아니라 enabled, public/private key, subject가 모두 갖춰졌는지를 나타냅니다.

```json
{
  "vapidPublicKey": "B...",
  "pushEnabled": true,
  "clockZone": "Asia/Seoul",
  "clockTestDurationMinutes": 5,
  "clockTestNotificationCount": 5
}
```

세션 응답의 `state`와 `GET /me/state`의 shape는 같습니다. 서버는 보안상 구독 endpoint와 key를 state 응답으로 돌려주지 않습니다. 알림 이력은 최신 25건입니다.

```json
{
  "csrfToken": "base64url-random-token",
  "ownerKey": "base64url-random-owner-key",
  "state": {
    "counter": { "value": 0, "version": 0 },
    "pushSubscriptions": [
      {
        "id": "00000000-0000-0000-0000-000000000001",
        "status": "ACTIVE",
        "expirationTime": null,
        "locale": "ko",
        "timeZone": "Asia/Seoul",
        "createdAt": "2026-09-05T00:00:00+09:00",
        "updatedAt": "2026-09-05T00:00:00+09:00",
        "lastAcceptedAt": null,
        "deactivatedAt": null,
        "deactivationReason": null
      }
    ],
    "clockTest": null,
    "notificationHistory": [],
    "serverTime": "2026-09-05T00:00:00+09:00"
  }
}
```

구독 등록 body에서 `expirationTime`은 epoch milliseconds 또는 `null`입니다.

```json
{
  "endpoint": "https://fcm.googleapis.com/fcm/send/...",
  "expirationTime": null,
  "keys": {
    "p256dh": "base64url-p256-public-key",
    "auth": "base64url-16-byte-secret"
  },
  "locale": "ko",
  "timeZone": "Asia/Seoul"
}
```

알림과 시계 상태 DTO는 다음 필드를 사용합니다.

```json
{
  "notification": {
    "id": "notification-uuid",
    "kind": "CLOCK",
    "sequence": 1,
    "status": "ACKNOWLEDGED",
    "messageId": "independent-message-uuid",
    "notificationTag": "clock-run-uuid-1",
    "scheduledAt": "2026-09-05T00:01:00+09:00",
    "sentAt": "2026-09-05T00:01:02+09:00",
    "acceptedAt": "2026-09-05T00:01:02+09:00",
    "acknowledgedAt": "2026-09-05T00:01:04+09:00",
    "errorCode": null
  }
}
```

```json
{
  "clockTest": {
    "status": "ACTIVE",
    "runId": "run-uuid",
    "startedAt": "2026-09-05T00:00:00+09:00",
    "endsAt": "2026-09-05T00:05:00+09:00",
    "nextScheduledAt": "2026-09-05T00:02:00+09:00",
    "sentCount": 1,
    "failedCount": 0
  }
}
```

`ClockTest.status`는 `ACTIVE`, `COMPLETED`, `STOPPED` 중 하나입니다. `Notification.status`는 `SCHEDULED`, `SENDING`, `ACCEPTED`, `FAILED`, `ACKNOWLEDGED` 중 하나입니다.

## 카운터와 오프라인 outbox

버튼은 화면에 투영된 값이 0이면 `-`, 10이면 `+`를 비활성화합니다. 각 조작은 `crypto.randomUUID()` operation ID와 설치의 `ownerKey`를 가진 IndexedDB 항목으로 먼저 기록됩니다.

```text
표시 값 = clamp(서버 값 + ownerKey가 같은 미전송 delta를 FIFO 순서로 적용, 0, 10)
```

IndexedDB `espero-pwa-test` version 1은 다음 store를 사용합니다.

- `counter-operations`: auto-increment `sequence`, unique `operationId`, non-unique `ownerKey` index
- `meta`: `cached-session`에 ownerKey, csrfToken, 마지막 AppState, cachedAt 저장

온라인이거나 foreground로 돌아오면 outbox를 한 번에 하나의 flush로 직렬 처리합니다. 성공한 항목은 삭제합니다. 400/409/422는 영구 오류로 보고 해당 항목을 버린 뒤 서버 상태를 다시 읽고, 네트워크 오류·5xx는 항목을 남깁니다. 401/403이면 세션 재확인 화면으로 돌아갑니다.

백엔드는 `(participant_id, operation_id)` unique constraint와 아래 순서로 중복 재전송을 안전하게 처리합니다.

1. 이미 처리한 operation이면 저장된 `result_value`, `result_version`과 `duplicate=true`를 반환합니다.
2. `counters` row를 `FOR UPDATE`로 잠급니다.
3. lock 대기 사이 생긴 duplicate를 다시 확인합니다.
4. `clamp(current + delta, 0, 10)`을 계산합니다.
5. 값이 실제로 바뀔 때만 counter version을 1 증가시킵니다.
6. 경계에서 무변경이어도 operation 결과를 기록하고 `duplicate=false`로 반환합니다.

따라서 재시도는 값을 두 번 증감하지 않고 동시 요청도 lost update를 만들지 않습니다. 최종 원본은 PostgreSQL이며 IndexedDB 상태는 빠른 표시와 일시적인 오프라인 조작용입니다.

## Push 구독

1. UI가 secure context, Service Worker, PushManager, Notification과 standalone 여부를 기능 감지합니다.
2. **알림 활성화** click handler가 가장 먼저 `Notification.requestPermission()`을 호출합니다.
3. 허용 뒤 `/public-config`의 VAPID public key로 `pushManager.subscribe({userVisibleOnly: true, applicationServerKey})`를 실행합니다.
4. endpoint, `p256dh`, `auth`, 브라우저 locale과 IANA time zone을 서버에 등록합니다.
5. 앱 재실행 때 기존 browser subscription이 있으면 서버로 다시 upsert합니다.
6. 해제 때 서버 subscription을 `INACTIVE`로 바꾸고 browser subscription의 `unsubscribe()`를 호출합니다.

서버 검증은 다음과 같습니다.

- endpoint는 user info가 없고 기본 port를 쓰는 HTTPS URL이어야 합니다.
- 기본 allowlist의 exact host는 `fcm.googleapis.com`, `jmt17.google.com`이고 suffix rule은 `.push.apple.com`, `.push.services.mozilla.com`, `.notify.windows.com`입니다. suffix는 앞에 실제 subdomain이 있어야 하며 문자열 `contains`는 사용하지 않습니다.
- `p256dh`는 base64url decode 후 `0x04`로 시작하는 65-byte uncompressed P-256 public key여야 합니다.
- `auth`는 base64url decode 후 16 byte여야 합니다.
- 만료 시간이 있으면 현재보다 미래여야 합니다.
- locale은 `ko`, `en`, `zh`로 정규화하고 그 밖의 값은 `ko`로 fallback합니다.
- time zone은 `ZoneId`로 검증하며 기본은 `Asia/Seoul`입니다.

endpoint 원문의 SHA-256 hex가 전역 unique key입니다. 동일 endpoint를 다시 등록하면 insert하지 않고 현재 participant에 재연결하고 key·locale·zone·상태를 갱신합니다. endpoint와 암호화 key는 Push 전송에 필요하므로 DB에 저장되며 capability 비밀로 취급해야 합니다.

브라우저 endpoint 근거는 [Chromium push endpoint 상수](https://chromium.googlesource.com/chromium/src/+/refs/tags/141.0.7390.94/components/push_messaging/push_messaging_constants.cc), [Mozilla Web Push 예시](https://blog.mozilla.org/services/2016/04/04/using-vapid-with-webpush/), [Microsoft Edge WNS client 정책](https://learn.microsoft.com/en-us/deployedge/microsoft-edge-browser-policies/forcebuiltinpushmessagingclient), [WNS hostname 검증 안내](https://learn.microsoft.com/en-us/windows/apps/develop/notifications/push-notifications/wns-overview)입니다. WNS 문서처럼 subdomain이 바뀔 수 있으므로 `.notify.windows.com`은 label 경계가 있는 suffix rule로 검사합니다. endpoint는 브라우저가 발급하는 값이므로 provider가 추가되면 실제 hostname을 확인하고 allowlist를 최소 범위로 확장합니다.

Apple 기기에서도 별도 Apple Developer Program 가입이나 APNs 인증서 발급 없이 표준 Web Push 구독과 VAPID key를 사용합니다. 서버가 Apple의 push endpoint로 outbound HTTPS 요청을 보낼 수 있어야 합니다.

## 5분 시계 테스트와 전송 상태

`PUT /me/clock-test`는 정확히 `durationMinutes: 5`와 하나 이상의 만료되지 않은 ACTIVE subscription을 요구합니다. 같은 participant에 ACTIVE run이 이미 있으면 participant row lock 아래 그 run을 그대로 반환해 중복 시작을 막습니다. 첫 예정 시각은 `startedAt + 1분`입니다.

브라우저 page timer나 Service Worker의 지속 실행에 예약을 맡기지 않습니다. 둘은 background에서 suspend될 수 있으므로 Spring Boot scheduler와 PostgreSQL의 `next_scheduled_at`가 기준입니다. UI의 10초 timer는 화면 상태 조회에만 사용됩니다.

dispatcher는 기본 5초마다 due run ID를 최대 100개 조회하고 run row를 잠근 뒤 처리합니다.

```text
sequence 1..5
scheduledAt = startedAt + sequence * 1분

if now >= scheduledAt + 1분:
    notification event를 FAILED / MISSED_SCHEDULE로 기록
    push하지 않고 다음 sequence로 이동
else if now >= scheduledAt:
    notification event와 active subscription별 delivery 생성
    해당 event를 worker에 전달
else:
    다음 tick까지 대기
```

한 tick에서 오래 지난 slot은 모두 MISSED 처리하지만 전송 가능한 현재 slot은 최대 하나만 만듭니다. 서버 중단 중 지난 알림을 재시작 직후 몰아서 보내지 않습니다. sequence 5 event를 생성 또는 MISSED 처리한 시점에 run은 `COMPLETED`가 됩니다. 이 상태는 다섯 slot의 materialization이 끝났다는 뜻이며, 개별 delivery/ACK의 최종 상태는 `sentCount`, `failedCount`와 notification history로 확인합니다. 사용자가 `DELETE /me/clock-test`를 호출하면 ACTIVE run은 `STOPPED`가 되고 다음 예정 시각이 제거됩니다.

각 notification event에는 독립 UUID인 `id`(`notificationId`)와 별도 UUID 문자열 `messageId`가 있습니다. 서버 기록의 `notificationTag`는 시계 알림에서 `clock-<runId>-<sequence>`, 즉시 알림에서 `test-now-<messageId>`입니다. DB unique constraint `(run_id, sequence)`, unique `message_id`, unique `notification_tag`가 중복 생성을 막습니다.

event 하나는 그 시점의 모든 ACTIVE subscription에 delivery 하나씩을 가집니다. delivery 상태는 `PENDING`, `RETRY`, `ACCEPTED`, `FAILED`입니다. Spring scheduler pool은 기본 2 thread라 clock dispatcher와 push worker가 한 개의 느린 작업 때문에 서로 완전히 멈추지 않습니다.

전송 worker는 짧은 첫 DB 트랜잭션에서 due delivery row를 잠그고 검증한 뒤 attempt를 증가시키며 `RETRY / DELIVERY_IN_FLIGHT`와 동적 claim lease를 기록합니다. lease 종료 시각의 불변식은 `min(claimedAt + APP_PUSH_REQUEST_TIMEOUT + 5초, scheduledAt + APP_PUSH_TTL)`입니다. 트랜잭션을 끝낸 다음 외부 push service를 호출하고, 완료 뒤 두 번째 짧은 트랜잭션에서 status·attempt·claim marker가 그대로인지 확인해 결과를 반영합니다. worker가 중간에 종료되면 lease 만료 뒤 같은 delivery를 다시 claim할 수 있습니다. 응답을 받기 전에 연결이 끊기면 provider 접수 여부를 알 수 없어 재시도가 중복 전송을 만들 수 있지만, 같은 event는 같은 `notificationId` 표시 tag를 사용해 하나의 논리 알림으로 교체됩니다.

서버 전송 규칙은 다음과 같습니다.

- Web Push urgency는 `normal`, content encoding은 `Encoding.AES128GCM`, 기본 event TTL은 70초입니다. payload 암호화 형식은 [RFC 8291](https://www.rfc-editor.org/rfc/rfc8291.html)의 modern Web Push encoding을 명시적으로 선택합니다.
- 각 최초 전송과 재시도는 `remainingTtl = scheduledAt + APP_PUSH_TTL - claimedAt`을 계산해 provider의 TTL header에 넘깁니다. 따라서 재시도 메시지도 event 만료 뒤까지 보관되지 않습니다.
- 한 HTTP 요청의 실제 대기 한도는 `min(APP_PUSH_REQUEST_TIMEOUT, remainingTtl)`입니다. `sendAsync()`가 이 시간 안에 완료되지 않으면 future를 cancel하고 transport failure로 처리합니다.
- 성공은 HTTP 2xx입니다. event는 delivery 하나 이상이 접수되면 `ACCEPTED`입니다.
- 404/410은 `SUBSCRIPTION_GONE`으로 delivery를 실패시키고 subscription을 비활성화합니다.
- 429/5xx와 transport error만 TTL과 최대 3회 안에서 재시도합니다.
- 내부 retry delay는 첫 실패 뒤 5초, 그 뒤 15초이며 유효한 `Retry-After`가 있으면 TTL 범위에서 우선합니다.
- 모든 delivery가 실패하면 event는 `FAILED / ALL_DELIVERIES_FAILED`입니다.
- Web Push `Topic` header는 설정하지 않습니다. 같은 Topic의 미전달 메시지 교체를 피합니다.

`sentCount`는 시계 event가 최초로 `ACCEPTED` 또는 유효 ACK에 도달할 때 한 번 증가하고, `failedCount`는 최초로 terminal failure가 될 때 한 번 증가합니다. `terminal_counted`가 중복 집계를 막습니다.

## Push payload, 표시와 수신 ACK

시계 알림 title/body는 구독 locale별 한국어·영어·중국어 message bundle에서 만들며 sequence, 전체 건수, 예정 시각, 실제 전송 시각과 `APP_CLOCK_ZONE` ID를 포함합니다. 실제 CLOCK payload shape는 다음과 같습니다.

```json
{
  "type": "CLOCK",
  "notificationId": "notification-uuid",
  "messageId": "message-uuid",
  "ackToken": "deterministic-hmac-capability",
  "sequence": 1,
  "scheduledAt": "2026-09-05T00:01:00+09:00",
  "sentAt": "2026-09-05T00:01:02+09:00",
  "title": "현재 시간 알림 (1/5)",
  "body": "예정 00:01:00 · 발송 00:01:02 (Asia/Seoul)",
  "tag": "clock-run-uuid-1",
  "renotify": true,
  "icon": "/icon-192.png",
  "badge": "/icon-maskable-512.png",
  "data": {
    "url": "/?notificationId=notification-uuid",
    "notificationId": "notification-uuid",
    "messageId": "message-uuid"
  },
  "notification": {
    "title": "현재 시간 알림 (1/5)",
    "body": "예정 00:01:00 · 발송 00:01:02 (Asia/Seoul)",
    "tag": "clock-run-uuid-1",
    "renotify": true,
    "icon": "/icon-192.png",
    "badge": "/icon-maskable-512.png",
    "data": {
      "url": "/?notificationId=notification-uuid",
      "notificationId": "notification-uuid",
      "messageId": "message-uuid"
    }
  }
}
```

top-level과 nested `notification` 표시 필드는 provider/library 호환을 위해 함께 둡니다. 현재 구현은 iOS/iPadOS 16.4부터 지원되는 일반 Service Worker Web Push이며 Declarative Web Push opt-in 값 `web_push: 8030`은 보내지 않습니다.

Service Worker는 push event에서 top-level 및 nested 값을 읽고 다음처럼 정규화합니다.

- 실제 표시 tag는 항상 `notification-<notificationId>`입니다. notificationId가 없을 때만 `clock-<runId>-<sequence>` 또는 random tag를 사용합니다.
- `renotify=false`, `requireInteraction=false`로 `showNotification()`을 호출합니다.
- icon은 `/icon-192.png`, badge는 `/icon-maskable-512.png`입니다.
- destination은 같은 origin의 상대 URL만 최종 click handler에서 허용합니다.
- `event.waitUntil()`이 표시와 ACK promise를 묶습니다.

모든 push event는 사용자에게 보이는 알림을 표시합니다. 이 채널을 silent background 작업에 사용하지 않으며, 표시 없이 push event만 처리하는 패턴은 WebKit의 Web Push 정책과 맞지 않습니다.

`showNotification()`이 resolve된 뒤 다음 body를 `credentials: omit`으로 ACK endpoint에 보냅니다.

```json
{
  "notificationId": "notification-uuid",
  "ackToken": "deterministic-hmac-capability",
  "receivedAt": "2026-09-05T00:01:04.000Z"
}
```

서버는 token 검증 외에도 `receivedAt`이 서버 현재 시각 기준 과거 24시간부터 미래 5분 사이인지 확인합니다. 잘못된 notification ID와 token은 모두 같은 `404 NOTIFICATION_ACK_NOT_FOUND`로 응답합니다. 유효 ACK는 event를 `ACKNOWLEDGED`로 바꾸며 반복 ACK도 안전합니다.

ACK는 Service Worker가 브라우저 API에 **표시 요청을 완료했다는 신호**입니다. 사용자가 읽었거나 눌렀다는 증거, OS가 최종 UI를 표시했다는 보증은 아닙니다. `notificationclick`은 ACK를 보내지 않고 알림을 닫은 뒤 열린 same-origin 창을 navigate/focus하거나 새 창을 엽니다.

표준 지원 근거는 [WebKit iOS Web Push](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/), [WebKit Web Push](https://webkit.org/blog/12945/meet-web-push/), [MDN Push API](https://developer.mozilla.org/en-US/docs/Web/API/Push_API), [RFC 8030](https://www.rfc-editor.org/rfc/rfc8030.html), [RFC 8292](https://www.rfc-editor.org/rfc/rfc8292.html)입니다. Declarative Web Push와의 차이는 [WebKit 공식 설명](https://webkit.org/blog/16535/meet-declarative-web-push/)을 참고합니다.

## PWA, cache와 화면 동기화

manifest는 stable `id=/`, `start_url=/?source=pwa`, `scope=/`, `display=standalone`과 192/512/maskable PNG 및 SVG icon을 제공합니다. PNG와 Apple 180px icon은 Next.js `ImageResponse` route가 실제 PNG로 생성합니다.

Service Worker cache `espero-pwa-shell-v2`의 정책은 다음과 같습니다.

- install에서 shell URL과 icon을 best-effort precache합니다.
- navigation은 network-first이고 실패하면 해당 cache 또는 `/` shell로 fallback합니다.
- same-origin style/script/image/font는 cache hit을 즉시 반환하고 background network refresh를 best-effort로 시도합니다.
- `/api/*`와 non-GET, cross-origin 요청은 가로채거나 cache하지 않습니다.
- activate에서 이전 이름의 shell cache를 삭제하고 client를 claim합니다.

검증된 session을 얻은 뒤 `navigator.storage.persist()`를 best-effort 요청합니다. 영속 저장 승인은 브라우저 정책이 결정하며 사이트 데이터 삭제를 막아 주지는 않습니다.

ACTIVE 시계 테스트 동안 UI는 10초마다 state를 다시 읽습니다. 다섯 번째 slot 생성으로 run이 `COMPLETED`가 되었더라도 `sentCount + failedCount`가 5보다 작으면 마지막 delivery의 재시도·종결 상태가 확정될 때까지 polling을 계속합니다. 앱이 foreground로 돌아오거나 온라인이 되면 서버 state와 outbox를 재동기화합니다. 서버는 알림 최신 25건을 주고 화면은 그중 최신 20건을 표시합니다. `ACCEPTED`는 push service 접수, `ACKNOWLEDGED`는 Service Worker 표시 요청 뒤 ACK임을 구분합니다.

## PostgreSQL 데이터 모델

| table | 핵심 역할과 제약 |
|---|---|
| `participants` | 설치별 소유 단위, unique `owner_key`, fixed `expires_at` |
| `participant_sessions` | HMAC token hash, CSRF token, 만료와 last-seen; participant cascade |
| `counters` | participant당 한 row, `0<=value<=10`, version |
| `counter_operations` | idempotency ledger, unique `(participant_id, operation_id)` |
| `push_subscriptions` | endpoint/key/locale/zone, unique endpoint SHA-256, ACTIVE/INACTIVE |
| `clock_test_runs` | ACTIVE/COMPLETED/STOPPED, 다음 sequence와 sent/failed 집계 |
| `notification_events` | CLOCK/TEST_NOW, lifecycle, message ID/tag, ACK 시각과 오류 |
| `push_deliveries` | event×subscription별 시도, HTTP 응답과 retry 상태 |

Flyway `V1__create_stamp_push_schema.sql`이 시작 시 schema를 생성합니다. `spring.flyway.clean-disabled=true`이므로 애플리케이션에서 clean을 실행하지 않습니다. PostgreSQL volume이나 migration을 되돌리는 작업은 별도 백업 없이 수행하면 안 됩니다.

## Health와 운영 관찰

- Frontend: `GET /healthz`; 컨테이너의 Node `fetch` healthcheck가 확인합니다.
- Backend: `GET /actuator/health/readiness`; readiness group은 application readiness와 DB를 포함하고 상세를 노출하지 않습니다.
- PostgreSQL: `pg_isready`.
- Caddy: frontend/backend가 healthy가 된 뒤 시작하고 공개 HTTPS를 제공합니다.

Push endpoint, `p256dh`, `auth`, 세션/CSRF/ACK token, VAPID private key는 로그에 원문을 남겨서는 안 됩니다. 서버 시계가 틀리면 시계 알림뿐 아니라 VAPID JWT 유효성도 깨질 수 있으므로 VM NTP를 유지합니다.

## 의도된 한계

- 로그인·복구 QR·계정 연결이 없으므로 다른 기기/브라우저로 진행도를 옮기지 못합니다.
- 기본 session TTL 7일 뒤 participant와 관련 데이터가 삭제됩니다.
- 브라우저 Push service의 2xx는 기기 도착 보장이 아닙니다.
- Chrome은 2026년부터 참여도와 저가치 알림을 기준으로 Push API rate limit을 단계적으로 적용합니다. 이 1분 주기 기능은 짧은 검증용이며 운영 알림 패턴으로 사용하지 않습니다: [Chrome 공식 설명](https://developer.chrome.com/blog/web-push-rate-limits).
- iOS/iPadOS 16.4 이상에서는 **홈 화면에 추가한 웹 앱**에서 직접 사용자 동작으로 권한을 요청해야 합니다. 일반 Safari 탭이나 WKWebView를 같은 지원 대상으로 보지 않습니다.
- VAPID key를 바꾸면 기존 application server key와 달라지므로 기기에서 구독 해제·재구독해야 할 수 있습니다.
- `APP_TOKEN_PEPPER` 회전은 기존 세션과 미ACK capability를 무효화합니다.
- 현재 in-memory rate limiter는 backend 여러 replica 사이에 공유되지 않습니다. 이 Compose는 backend 한 replica를 전제로 합니다.

실제 서버 배포, DNS·방화벽, 비용 위험, iOS/Android 실기기 절차, 백업·복원과 문제 해결은 [무료 서버 배포 가이드](FREE_SERVER_DEPLOYMENT.md)에 이어집니다.
