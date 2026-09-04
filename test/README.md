# PWA 스탬프·Web Push 테스트 서비스

Next.js PWA와 Spring Boot API를 동일한 HTTPS origin으로 서비스하는 실기기 검증용 프로젝트입니다. 설치별 익명 세션으로 `0~10` 카운터를 유지하고, 사용자가 시작한 뒤 1분마다 한 개씩 5분간 총 5개의 현재 시각 알림을 각각 쌓아 Android와 iOS에서 확인합니다.

이 프로젝트는 전달 보장 시스템이 아닙니다. Web Push 서버의 성공 응답은 브라우저 푸시 서비스가 메시지를 접수했다는 뜻이며, 휴대전화에 즉시 표시됐거나 사용자가 확인했다는 뜻은 아닙니다. 정확한 초 단위 예약, 긴급 알림, 결제·보안 승인에는 사용하지 마세요.

## 구성

```text
휴대전화 HTTPS
      |
    Caddy :80/:443
      |-- /api/* --> Spring Boot :8080 --> PostgreSQL :5432
      `-- 그 외 ----> Next.js :3000

Spring Boot --> 브라우저별 Web Push endpoint (HTTPS outbound)
```

일반 profile은 Caddy의 `80/tcp`, `443/tcp`, 선택적 HTTP/3용 `443/udp`만 공개합니다. E2.1.Micro profile은 메모리를 아끼기 위해 `80/tcp`, `443/tcp`만 공개합니다. 두 profile 모두 API와 데이터베이스 포트는 Docker 네트워크에만 노출합니다.

## 빠른 시작

필요 조건은 Docker Engine과 Compose 플러그인, 공개 도메인, 해당 도메인을 가리키는 공인 IP, 외부에서 접근 가능한 TCP 80/443입니다. iPhone 실기기 테스트에는 공인 인증서가 적용된 HTTPS가 필요합니다. 아래 기본 명령은 빌드할 메모리가 충분한 호스트용입니다. OCI `VM.Standard.E2.1.Micro`에서는 서버 빌드를 실행하지 말고 다음 절의 전용 profile을 사용합니다.

1. 환경 파일을 만듭니다.

   ```bash
   cp .env.example .env
   ```

2. 비밀값을 로컬에서 생성합니다. 아래 명령은 각각 별도로 실행하고 결과를 `.env`에 옮깁니다.

   ```bash
   openssl rand -hex 32
   openssl rand -hex 24
   openssl rand -hex 32
   docker run --rm node:24-alpine npx --yes web-push generate-vapid-keys --json
   ```

   첫 결과는 `POSTGRES_PASSWORD`, 둘째 결과는 `TEST_ACCESS_CODE`, 셋째 결과는 `APP_TOKEN_PEPPER`에 사용합니다. Pepper는 최소 32바이트 고엔트로피 비밀이어야 하며 배포 간 유지합니다. VAPID 출력의 public/private 키는 반드시 같은 쌍을 사용합니다. `APP_VAPID_SUBJECT`에는 운영자가 실제로 받는 `mailto:` 주소 또는 HTTPS URL을 넣습니다. VAPID 키는 한 번 생성한 뒤 안정적으로 보관하며, 특히 private key를 Git, 채팅, 화면 캡처, CI 로그에 남기지 않습니다.

3. `.env`의 `APP_DOMAIN`과 `APP_ALLOWED_ORIGINS`를 같은 도메인으로 맞춥니다.

   ```dotenv
   APP_DOMAIN=pwa.example.com
   APP_ALLOWED_ORIGINS=https://pwa.example.com
   ```

4. **E2.1.Micro가 아닌 메모리 여유가 있는 호스트에서만** 설정을 검증하고 빌드·기동합니다. E2 사용자는 이 단계를 건너뛰고 바로 아래 전용 절차를 사용합니다.

   ```bash
   docker compose config --quiet
   docker compose up -d --build
   docker compose ps
   docker compose logs --tail=100 caddy backend frontend
   ```

5. 데스크톱에서 `https://APP_DOMAIN/healthz`와 메인 화면을 확인한 뒤 휴대전화 설치 테스트를 진행합니다. 배포 환경 준비는 [무료 서버 배포 가이드](docs/FREE_SERVER_DEPLOYMENT.md), 실기기 확인은 아래 테스트 핵심 절차를 따릅니다.

### OCI E2.1.Micro 1 GB

Tokyo home region에서 `VM.Standard.E2.1.Micro`만 Always Free로 제공되는 경우 다음 원칙을 적용합니다.

- 2 GB host swap을 먼저 설정합니다.
- frontend/backend 이미지는 Docker Buildx를 사용할 수 있는 개발 컴퓨터나 CI에서 `linux/amd64`로 빌드합니다. frontend는 `Dockerfile.e2-micro`로 정적 export하여 실행 중 Node를 제거합니다.
- `.env`의 `BACKEND_IMAGE`, `FRONTEND_IMAGE`, `POSTGRES_IMAGE`, `CADDY_IMAGE`를 검증된 image digest로 채웁니다.
- 서버에서는 빌드 항목이 없는 저메모리 Compose만 실행합니다.

```bash
docker compose -f docker-compose.e2-micro.yml config --quiet
docker compose -f docker-compose.e2-micro.yml pull
docker compose -f docker-compose.e2-micro.yml up -d --no-build
docker compose -f docker-compose.e2-micro.yml ps
```

이 profile은 backend 416 MiB, PostgreSQL 160 MiB, 정적 frontend 32 MiB, Caddy 96 MiB로 제한하며 합계는 704 MiB입니다. 소수 실기기의 단기 기능 검증용이며 실제 축제 운영 사양이 아닙니다. swap, 외부 빌드, registry 설정과 장애 진단은 [E2 배포 절차](docs/FREE_SERVER_DEPLOYMENT.md)를 그대로 따릅니다.

## 테스트 핵심 절차

- 신규 익명 세션을 만들 때만 `.env`의 `TEST_ACCESS_CODE`를 입력합니다. 이 코드는 테스트 참가를 제한하는 공유 게이트일 뿐 사용자 식별자나 관리자 인증 수단이 아닙니다.
- iPhone/iPad는 iOS/iPadOS 16.4 이상에서 먼저 홈 화면에 추가하고, 홈 화면 아이콘으로 연 다음 앱 안의 알림 활성화 버튼을 직접 누릅니다. iOS/iPadOS 26에서는 추가 화면의 **Open as Web App(웹 앱으로 열기)** 옵션도 켜 둡니다.
- Android Chrome은 메뉴의 앱 설치/홈 화면 추가로 설치한 뒤 설치된 앱에서 알림을 허용합니다.
- `+`와 `-`로 0~10 경계를 확인하고 앱을 완전히 종료·재실행한 뒤 같은 값이 복원되는지 확인합니다.
- 알림을 활성화한 다음 시계 테스트를 시작합니다. 첫 알림은 시작 1분 뒤, 마지막 알림은 5분 뒤 생성되어 총 5개가 쌓입니다. 서버 event는 고유 `messageId`와 `clock-<runId>-<sequence>` tag를 기록하고 Service Worker는 `notification-<notificationId>` 표시 tag를 사용합니다. Web Push `Topic`은 보내지 않습니다.
- Service Worker가 각 알림 표시 요청을 마친 뒤 해당 메시지의 best-effort 수신 ACK를 서버에 기록하는지 화면에서 확인합니다. 알림을 누르면 같은 origin의 앱을 열거나 기존 창에 focus합니다. ACK 토큰은 서버가 `APP_TOKEN_PEPPER`와 `notificationId`로 계산하므로 별도 환경 변수가 아닙니다.

집중 모드, 알림 요약, 절전, 네트워크 단절, OS 정책 때문에 간격과 표시 시각은 달라질 수 있습니다. 테스트를 반복해 불필요한 알림을 많이 보내면 사용자 경험과 Chrome의 참여도 기반 Push 제한에 악영향을 줄 수 있으므로 공개 운영 기능으로 그대로 유지하지 마세요.

## 자주 쓰는 운영 명령

### OCI E2.1.Micro

E2에서는 아래처럼 모든 명령에 전용 파일을 명시합니다. 일반 `docker compose build`나 파일을 생략한 `up`은 실행하지 않습니다.

```bash
docker compose -f docker-compose.e2-micro.yml ps
docker compose -f docker-compose.e2-micro.yml logs -f --tail=200 backend
docker compose -f docker-compose.e2-micro.yml logs -f --tail=200 caddy
docker compose -f docker-compose.e2-micro.yml pull
docker compose -f docker-compose.e2-micro.yml up -d --no-build
docker compose -f docker-compose.e2-micro.yml down
```

### 메모리가 충분한 일반 호스트

E2.1.Micro가 아닌 개발·일반 호스트에서만 다음 기본 Compose 명령을 사용합니다.

```bash
docker compose ps
docker compose logs -f --tail=200 backend
docker compose logs -f --tail=200 caddy
docker compose pull
docker compose build --pull
docker compose up -d
docker compose down
```

같은 profile의 `docker compose down`은 컨테이너와 네트워크만 제거하고 named volume은 유지합니다. `docker compose down -v`는 PostgreSQL과 Caddy 인증서 데이터를 삭제하므로 이 프로젝트 문서에서는 사용하지 않습니다.

## 보안·데이터 한계

- 진행도 원본은 서버의 익명 세션입니다. 같은 설치·같은 브라우저 프로필에서 쿠키가 유지되는 동안 복구됩니다.
- 사이트 데이터 삭제, PWA 삭제, 시크릿 모드 종료, 다른 브라우저/기기로 전환하면 기존 익명 세션을 자동 복구할 수 없습니다. iOS에서는 반드시 첫 진행도 변경 전에 설치하세요.
- `.env`는 절대 커밋하지 않습니다. 유출 시 DB 비밀번호, 테스트 접근 코드, token pepper, VAPID private key를 교체합니다. Pepper 회전은 기존 세션과 아직 확인하지 않은 알림 ACK를 무효화하고, VAPID 교체 후에는 기존 구독을 새 키로 다시 등록해야 할 수 있습니다.
- Push 구독 endpoint와 암호화 키도 capability 정보이므로 로그에 원문을 남기거나 외부에 공개하지 않습니다.
- `APP_PUSH_ALLOWED_ENDPOINT_HOST_SUFFIXES`는 서버가 임의 URL로 요청하는 SSRF를 줄이는 방어선입니다. 기본값은 Chromium/FCM·Google, Apple, Firefox/Mozilla, Windows의 Microsoft Edge/WNS Push endpoint를 포함합니다. 다른 provider는 검증한 뒤 명시적으로 확장하세요.

## 근거와 상세 문서

- Apple은 iOS/iPadOS 16.4부터 홈 화면 웹 앱의 표준 Web Push와 직접 사용자 동작 기반 권한 요청을 지원한다고 설명합니다: [WebKit 공식 안내](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/).
- 이 표준 Web Push 방식은 Apple Developer Program 가입이나 APNs 인증서 발급을 요구하지 않으며, VAPID key pair와 Apple push endpoint로의 HTTPS outbound를 사용합니다.
- Push API는 활성 Service Worker와 `PushSubscription`을 사용합니다: [MDN Push API](https://developer.mozilla.org/en-US/docs/Web/API/Push_API).
- HTTPS와 Service Worker 조건: [MDN Service Worker API](https://developer.mozilla.org/en-US/docs/Web/API/Service_Worker_API).
- VAPID 표준: [RFC 8292](https://www.rfc-editor.org/rfc/rfc8292.html). Web Push 전송 의미와 TTL/Topic: [RFC 8030](https://www.rfc-editor.org/rfc/rfc8030.html).
- 2026년 Chrome의 참여도 기반 Push rate limit: [Chrome for Developers](https://developer.chrome.com/blog/web-push-rate-limits).
- Windows용 Microsoft Edge는 WNS push client를 사용하며 WNS channel hostname의 subdomain은 바뀔 수 있습니다: [Edge WNS 정책](https://learn.microsoft.com/en-us/deployedge/microsoft-edge-browser-policies/forcebuiltinpushmessagingclient), [WNS hostname 검증](https://learn.microsoft.com/en-us/windows/apps/develop/notifications/push-notifications/wns-overview).

배포와 비용 위험은 [docs/FREE_SERVER_DEPLOYMENT.md](docs/FREE_SERVER_DEPLOYMENT.md), 실행·검증 계약은 이 문서와 각 애플리케이션의 README에 정리되어 있습니다.
