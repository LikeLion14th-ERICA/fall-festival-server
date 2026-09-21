# 백엔드 보안 출시 체크리스트

[위키 홈](../README.md) · 읽는 때: backend release 후보 검증, 관리자 인증·미디어·운영 보안 변경

이 목록은 루트 Spring Boot 서버와 현재 [API v2](../../../api-v2/README.md) 계약에 맞춘다.
`자동 회귀 대상`은 테스트가 있다는 뜻일 뿐 이번 release 통과를 뜻하지 않는다. release 기록에는
실제 실행 결과를 남기고, `실측 게이트`는 배포 환경의 증거 없이는 통과로 표시하지 않는다.

## 현재 범위와 제외 항목

- 모든 경로는 `/api/v2`다. 공개 조회는 로그인 없이 제공하고 `/api/v2/admin/**`는 단일
  `ADMIN` authority가 필요하다. 실제 경로는 [API v2 경로표](../../../api-v2/ENDPOINTS.md)를 따른다.
- 관리자는 `username/password` 로그인, 15분 access JWT와 7일 회전 refresh cookie를 쓴다.
  이메일 로그인, `VIEWER`·`EDITOR`·`PUBLISHER`, festival scope와 공개 회원가입은 현재 범위가 아니다.
- 관리자 HTTP 범위는 혼잡도·공지·굿즈·세션이다. catalog 게시·rollback은 개발자 CLI이며
  게시 뒤 controlled restart가 있어야 공개 snapshot에 반영된다.
- 챗봇, 관리자 공연·지도 편집, CDN purge와 다중 인스턴스 전파는 활성 기능이 아니다.
  Cloudflare/Caddy의 실제 cache 정책은 배포 실측 게이트로만 다룬다.
- 공지는 plain string과 host가 있는 `https` URI 링크만 사용한다. 서버가 임의 외부 URL을 fetch하지 않으므로
  metadata endpoint SSRF 테스트는 적용하지 않는다. 클라이언트는 공지 문자열을 HTML로 렌더하지
  않는지 별도 프런트 검증으로 확인한다.
- 공지 제목·본문·링크의 길이·개수 정책은 아직 [API v2 결정 대기](../../../api-v2/DECISIONS.md)에
  남아 있다. 원천 계약의 200/10,000 값은 목 입력 검증 제안이므로 운영 제한으로 추측해 적용하지
  않으며, 현재는 JSON 본문 64KiB 경계만 강제한다.

## P0 자동 회귀 대상

| 영역 | 현재 보호 대상 | release 전에 다시 실행할 근거 |
| --- | --- | --- |
| 공개·관리자 경계 | 공개 `GET`은 anonymous, 관리자 요청은 `401 UNAUTHORIZED` 또는 `403 FORBIDDEN` | `PublicCatalogSecurityIntegrationTest`, `SecurityConfigurationTest` |
| JWT·세션 | 변조·만료·다른 서명 JWT, disabled 계정, refresh 회전·재사용·logout | `AdminTokenServiceTest`, `AdminJwtAuthenticationFilterTest`, `AdminAuthApiIntegrationTest` |
| Cookie CSRF·CORS | login·refresh·logout의 정확한 `Origin`, 단일 `ADMIN_ALLOWED_ORIGIN` | `AdminCookieCsrfFilterTest`, `AdminCorsConfigurationTest`, `AdminAuthStartupValidatorTest` |
| 현재 회차 리소스 경계 | notice, goods, option, media와 catalog revision의 교차 회차 접근 은닉 | 관리자 goods·media flow와 `CatalogWorkbenchIntegrationTest` |
| 입력·미디어 | API 계약의 `additionalProperties: false`와 invalid input, 상품 이미지 magic bytes·decode·크기·pixel·animated WebP·경로 | `AdminAuthApiIntegrationTest`, `AdminGoodsProductCreationFlowIntegrationTest`, `GoodsInputValidatorTest`, `GoodsImageInspectorTest`, media storage tests |
| SQL 입력 경계 | SQL 형태의 관리자 공지 제목이 parameter binding을 거쳐 원문 그대로 저장·재조회되고 기존 공지를 바꾸지 않음 | `AdminSessionReleaseE2eTest` |
| 요청 제한 | public/admin/login/stamp token bucket, trusted proxy hop, `429 RATE_LIMITED`와 `Retry-After` | `RateLimitTest`, release E2E HTTP-21·22 |
| idempotency·동시성 | 같은 key replay, 다른 body `409 IDEMPOTENCY_KEY_REUSED`, in-flight 충돌, `If-Match` | `AdminIdempotencyServiceIntegrationTest`, `AdminMutationPreconditionsTest`, `CrowdingConcurrencyE2eTest` |
| JSON 본문 경계 | `/api/v2`의 JSON `POST`·`PUT`·`PATCH`·`DELETE` 요청은 declared/chunked 여부와 관계없이 64KiB 이하이며, multipart 이미지는 별도 10MiB 제한 | `JsonRequestBodyLimitFilterTest`, `SecurityConfigurationTest` |
| 수령 인증·동적 결제 안내 | 수령 코드는 공백을 제거하지 않은 정확한 6자리 숫자만 허용하고, 성공 수령 인증과 `GET /api/v2/goods/{goodsId}/payment-guide`는 `Cache-Control: no-store` | `StampReceiptVerifierTest`, `CatalogControllerOpenApiTest`, `GoodsFlowIntegrationTest` |
| 게시·공개 분리 | validated published revision만 노출, draft/rollback 원자성, restart 뒤 revision 전환 | `CatalogPublicationLifecycleE2eTest`, 운영 E2E OPS-01~20 |
| 인증·관리자 cache | 모든 `/api/v2/admin/**` 성공·401·403·CORS/CSRF 오류 응답은 `Cache-Control: no-store`; Spring Security와 선행 rate/JSON 거부 오류도 `nosniff`·`DENY` 헤더 유지 | `SecurityConfigurationTest`, `RateLimitTest` |
| 오류·로그 | 오류 envelope와 startup/cleanup/API 오류 로그에 connection string·비밀값·stack trace를 넣지 않음 | `GlobalApiExceptionHandlerTest`, `CatalogSnapshotProviderTest`, `CleanupJobSafetyTest` |
| 의존성·비밀값·이미지 | 루트 backend filesystem(최상위 `test/` 제외)과 root runtime image의 high/critical 취약점, 루트 backend 비밀값 | `security-filesystem`과 `docker-build`의 Trivy SARIF scan |

미디어는 UUID 기반 저장 경로와 WebP 재인코딩을 쓴다. 확장자 검사·malware scan·signed upload URL은
현재 계약의 보호 수단이 아니므로 실제 기능을 추가하기 전에는 통과 항목으로 만들지 않는다.

## P0 미완료 또는 실측 게이트

다음은 코드만으로 통과를 주장할 수 없다. 하나라도 해당 release에 영향을 주면 증거를 첨부한다.

- [ ] 운영 admin origin에서 HTTPS login → refresh → logout을 실제 browser cookie 속성까지 확인했다.
- [ ] Cloudflare/Caddy의 TLS, HTTP redirect, security headers, backend host port 비노출과 실제
  `RATE_LIMIT_TRUSTED_PROXY_HOPS`를 확인했다. 검증용 `test/Caddyfile`은 운영 증거가 아니다.
- [ ] runtime·catalog·account·cleanup role의 DB 최소 권한, 인터넷 비노출, DB TLS 정책을 확인했다.
- [ ] DB와 media volume을 같은 recovery set으로 백업했고, 격리 환경 restore 결과를 기록했다.
- [ ] `security-filesystem`과 `docker-build`의 Trivy filesystem·root image high/critical 결과와 대응을 release 기록에 남겼다.
- [ ] 긴 query/header, slow request의 서버·proxy 제한을 실제 설정과 함께 확인했다.
- [ ] 공지·링크를 소비하는 프런트가 raw HTML이나 `javascript:` URL을 실행하지 않는지 확인했다.

현재 요청 제한은 단일 인스턴스 in-memory token bucket이다. 수평 확장 또는 다른 proxy 경로를
도입하면 shared limiter와 client identity 정책을 별도 설계·검증한다. account 기반 brute-force,
refresh session-family revoke, `Sec-Fetch-Site`, malware scan은 현재 계약 밖이므로 위협 모델을
승인한 뒤에만 새 P0 요구로 올린다.

## P1 운영 강화

- [ ] `AdminAuditEvent`·catalog audit·계좌 history가 append-only이며 write transaction과 함께
  rollback되는지 운영 역할로 확인한다. 실패한 write의 audit은 현재 계약상 남기지 않는다.
- [ ] `/healthz`·`/readyz` 응답이 DB host, credential, stack trace를 내보내지 않는지 release
  smoke에서 확인한다.
- [ ] root Dockerfile의 non-root runtime, `/healthz` liveness healthcheck, media volume mount와 `.env` build-context 제외를 image
  build 결과로 확인한다. `test/docker-compose.yml`은 이 운영 gate를 대체하지 않는다.
- [ ] capacity 측정과 별도로 rate limiter를 켠 로그인·public-read 흐름을 실제 proxy/NAT 조건에서
  확인한다. 현재 load-test 목표를 임의의 5,000 동시 사용자 요구로 바꾸지 않는다.
- [ ] CDN이나 다중 인스턴스를 도입하면 cache key, invalidation, shared limiter, revision 전파와
  rollback 반영을 새 release gate로 추가한다.

## 실행 순서와 기록

1. 변경 범위에 맞는 P0 자동 회귀를 먼저 실행한다. backend 변경의 최소 명령은 다음과 같다.

   ```powershell
   .\mvnw.cmd --batch-mode --no-transfer-progress verify
   ```

2. 후보 catalog와 관리자 세션·동시성까지 포함할 때는
   [검증 명령](../workflow/validation.md#릴리스-후보-backend-e2e)의 release E2E를 Docker에서 실행한다.
   Docker가 없으면 해당 결과를 release 통과로 기록하지 않는다.
3. 운영 변경은 [원격 개발 환경 결정](../../dev-deployment-decision.md)과
   [행사 당일 운영 절차서](../workflow/festival-day-runbook.md)의 Caddy, DB, recovery set gate를
   함께 충족한다. 실제 비밀값·connection string·access token은 작업 기록에 복사하지 않는다.
4. release 기록에는 commit, 실행 명령·결과, 실제 admin origin, proxy hop, image digest, DB/media
   recovery set ID, 미실행 사유와 승인자를 남긴다.

## 이번 release 기록용 확인란

- [ ] 변경된 P0 자동 회귀와 `verify`가 통과했다.
- [ ] 운영 영향이 있으면 release E2E와 실측 게이트를 모두 확인했다.
- [ ] 현재 범위 밖 항목을 구현 완료 또는 release 통과로 잘못 표시하지 않았다.
- [ ] 새 보안 요구가 필요하면 먼저 [결정 대기](../product/decisions.md)에 범위와 승인 근거를 남겼다.
