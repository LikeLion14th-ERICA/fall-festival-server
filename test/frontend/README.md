# Espero PWA test frontend

Next.js App Router 기반의 설치형 Web Push/익명 진행도 검증 클라이언트입니다. 브라우저 탭에서는 설치 안내와 기능 진단만 표시하며, standalone으로 실행된 뒤에만 서버 세션을 bootstrap합니다.

## 실행

요구 사항은 Node.js 20.9 이상입니다.

```bash
npm ci
npm run dev
```

기본 API 경로는 동일 origin의 `/api/v1`입니다. 필요할 때만 빌드 시 `NEXT_PUBLIC_API_BASE_PATH`로 다른 path를 지정할 수 있으며, cross-origin URL은 의도적으로 허용하지 않습니다.

전체 정적 검증은 다음 명령으로 실행합니다.

```bash
npm run check
```

컨테이너는 3000 포트를 사용하고 `/healthz`가 readiness/health 응답을 제공합니다.

## 세션 및 API 계약

- 최초 standalone bootstrap: `POST /api/v1/session` body `{}`. 유효한 HttpOnly 세션 쿠키가 없어서 401/403이면 참여 코드 화면을 표시합니다.
- 참여: `POST /api/v1/session` body `{ "accessCode": "..." }`.
- 성공 응답: `{ "data": { "csrfToken": "...", "ownerKey": "...", "state": { ... } } }` 또는 같은 내용의 비포장 객체.
- 이후 상태 변경 요청은 `X-CSRF-Token`을 포함하며 쿠키는 `credentials: include`로 전송합니다.
- 카운터: `POST /me/counter-operations` body `{ operationId, delta }`.
- 구독 등록: `POST /me/push-subscriptions` body `{ endpoint, expirationTime, keys, locale, timeZone }`.
- 즉시 테스트: `POST /me/notifications/test-now` body `{}`.
- 5분 테스트: `PUT /me/clock-test` body `{ durationMinutes: 5 }`; 시작 후 +1분부터 +5분까지 총 5건은 서버가 예약합니다.
- 수신 ACK: `POST /me/notifications/ack` body `{ notificationId, ackToken, receivedAt }`. 메시지별 capability token인 `ackToken`으로 검증하며 쿠키와 CSRF를 사용하지 않습니다. 클라이언트는 이를 저장하지 않습니다.
- 오류 응답: `{ error: { code, message, details, timestamp, path } }`.

`GET /me/state`, `GET /me/clock-test` 응답은 화면 재동기화에 사용합니다. VAPID 공개 키는 state의 `vapidPublicKey`를 우선하고, 없으면 `GET /public-config`에서 조회합니다.

## PWA 실기기 확인

1. 유효한 공개 HTTPS origin에 배포합니다. 개발 머신의 `localhost`가 아닌 휴대폰 접속에는 HTTPS가 필요합니다.
2. Android Chrome에서는 설치 프롬프트 또는 브라우저 메뉴로 설치합니다.
3. iPhone/iPad에서는 Safari의 공유 메뉴에서 **홈 화면에 추가**를 선택한 뒤, 생성된 아이콘으로 앱을 실행합니다.
4. 설치 앱에서 참여 코드를 입력하고 알림 권한을 허용합니다. iOS Web Push는 홈 화면에 설치된 앱에서만 테스트합니다.
5. 즉시 알림을 먼저 확인한 뒤 **알림 검증 시작**을 누릅니다. 알림은 고유 `notificationId` 또는 `runId-sequence` tag를 사용하므로 다섯 건이 서로 교체되지 않습니다.
6. 앱을 백그라운드/종료 상태로 두고 +1분부터 +5분까지 수신되는지 확인한 뒤, 전송 내역의 ACK 상태를 확인합니다.

카운터 변경은 IndexedDB에 owner별 FIFO 작업으로 저장됩니다. 오프라인 변경은 같은 설치 세션에서만 낙관적으로 반영되고, 온라인/포그라운드 복귀 때 순서대로 재전송됩니다. 네트워크 오류나 5xx에서는 새로운 사용자를 만들지 않습니다.

서버 시간/스케줄러, VAPID private key, 세션/CSRF, 운영 HTTPS 및 Web Push 전달 성공 여부는 백엔드·배포 환경의 책임입니다. 브라우저의 push provider 수락은 실제 화면 표시를 보장하지 않으므로 서버 기록과 ACK를 함께 확인해야 합니다.
