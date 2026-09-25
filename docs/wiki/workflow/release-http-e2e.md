# 릴리스 HTTP E2E 시나리오

[위키 홈](../README.md) · 읽는 때: 릴리스 후보 HTTP 검증을 설계·확장·실행하거나, 공개·관리자 API의 출시 영향을 검토할 때

이 페이지는 실제 HTTP release E2E의 현재 범위, 보강 우선순위와 상세 케이스의 진입점이다.
실행 명령과 현재 자동 검증 결과는 [검증 명령과 CI](validation.md), 배포·복구 실행 절차는
[행사 당일 운영 절차서](festival-day-runbook.md)를 따른다. 시나리오 정의, 구현 상태,
실행 증거, 릴리스 승인은 서로 다른 상태다.

## 현재 HTTP release E2E 인벤토리

모든 현재 대상은 JUnit 5, Testcontainers PostgreSQL, 랜덤 포트 Spring 서버, Java `HttpClient`를
사용한다. 후보 catalog는 실제 CLI로 import·publish하며, 원격 DB·계정·배포 환경에는 연결하지
않는다.

| 테스트 | 유형 | JUnit method |
| --- | --- | ---: |
| `ReleaseReadinessHttpE2eTest` | 후보 catalog·공개 사용자·관리자 기본 여정과 격리 | 9 |
| `ReleaseFailureModesHttpE2eTest` | 미게시 후보, readiness, rate limit·request ID 복구 | 1 |
| `AdminSessionReleaseE2eTest` | 로그인·refresh rotation·logout·인증 경계 | 3 |
| `CrowdingConcurrencyE2eTest` | 혼잡도 동시 저장·멱등·시간 경계 | 5 |
| `OperationalAccountPropagationE2eTest` | TICKET 계좌 CLI → HTTP 반영·송금 시간 경계 | 2 |
| `CatalogPublicationLifecycleE2eTest` | 게시·재시작·rollback의 HTTP 노출 | 1 |
| DynamicContentReleaseHttpE2eTest | 공지·상품·판매 상태·미디어 lifecycle과 실패 복구 | 5 |
| OperationalBoundariesHttpE2eTest | 수령 제한, GOODS account/template CLI, 관리자 mutation 경계 | 4 |
| `ArtistHypedHttpE2eTest` | 익명 Hyped HTTP·게시 아티스트·축제일 경계, hot-row 읽기/쓰기 부하 | 2 (기능 1, 부하 1) |

따라서 현재 실제 HTTP E2E는 9개 class, 기능 검증 31개와 부하 검증 1개 JUnit method다. 하나의 method가 관계된 요청을 함께 묶으므로 위험 시나리오 기준 수는 HTTP-01–HTTP-32, 즉 32개다. 운영자·개발자 도구 시나리오 OPS-01–OPS-20은 별도 process E2E이며 HTTP 수에 포함하지 않는다.

## 출시 준비 판단

기존 gate는 catalog 탐색, 지도·공연 관계, 티켓, 관리자 세션, 혼잡도, 게시·rollback,
조건부 읽기와 제한을 검증한다. 여기에 공지·굿즈·굿즈 이미지·스탬프 수령 확인과 운영
boundary를 실제 HTTP server·security filter·serialization·runtime storage 흐름으로 추가했다.

HTTP-25–30은 구현됐고, HTTP-31은 명시된 인증·idempotency·notice boundary만 부분
구현됐다. 2026-09-25 기준 PR #97의 Java 21 CI에서 HTTP-25–30 및 HTTP-32 class가
건너뜀 없이 통과했다. 이는 이 후보의 자동 검증 근거이며, 전체 릴리스 승인은 제품 공개 gate,
required CI, migration/운영 승인과 staging 증거를 함께 요구한다.

## 상세 케이스와 상태

| 범위 | ID | 우선순위 | 상세 수용 조건 |
| --- | --- | --- | --- |
| 공지 lifecycle | HTTP-25 | P0 | [동적 콘텐츠 상세](release-http-e2e-dynamic-content.md) |
| 상품·판매 상태 | HTTP-26 | P0 | [동적 콘텐츠 상세](release-http-e2e-dynamic-content.md) |
| 굿즈 image upload/delivery | HTTP-27 | P0 | [동적 콘텐츠 상세](release-http-e2e-dynamic-content.md) |
| 수령 code·rate limit | HTTP-28 | P0 | [운영·보안 상세](release-http-e2e-operational-boundaries.md) |
| GOODS account CLI | HTTP-29 | P0 | [운영·보안 상세](release-http-e2e-operational-boundaries.md) |
| notice template CLI | HTTP-30 | P1 | [운영·보안 상세](release-http-e2e-operational-boundaries.md) |
| admin mutation boundary | HTTP-31 | P1 | [운영·보안 상세](release-http-e2e-operational-boundaries.md) |
| 익명 아티스트 Hyped | HTTP-32 | P0 | 실제 게시 아티스트의 반복 클릭·누적 조회·CONTEST 제외·KST 날짜 경계·no-store와 DB 일치 |
| ingress/browser/media/load rehearsal | STAGE-01–05 | release gate | [staging·운영 리허설 상세](release-http-e2e-staging.md) |

각 ID는 다음 상태 중 하나로 기록한다. **계획**은 수용 조건만 존재함, **구현**은 코드가
있지만 candidate evidence 없음, **실행 대기**는 staging/승인된 운영 리허설을 기다림,
**PASS**는 같은 candidate의 필요한 증거가 있음, **FAIL**은 수용 조건 위반,
**BLOCKED**는 승인·환경·fixture가 없어 실행할 수 없음을 뜻한다. PASS는 테스트 class가
존재한다는 뜻이 아니다.

## 구현 공통 규칙

- 자동 HTTP 케이스는 Testcontainers 전용 DB, published candidate, random port server,
  fixed KST clock, real login을 재사용한다. staging에서는 fixed clock을 흉내 내지 않고
  실제 ingress·시간·browser를 사용한다.
- 응답 id는 fixture 상수 대신 최초 성공 response에서 읽는다. status, stable error code,
  request ID, ETag/precondition, idempotency replay, business/audit/file state를 함께
  검사한다. secret·token·원문 account·수령 code/hash·binary는 assertion이나 로그에 남기지
  않는다.
- 한 mutation의 replay와 key reuse는 별도다. replay는 같은 payload/key, key reuse는 다른
  payload와 같은 key다. product/notice update·delete는 current strong ETag를, availability는
  last-write-wins policy를 따른다.
- media와 account는 catalog revision 밖의 동적 state다. catalog publish/rollback과 restart가
  어떤 state를 바꾸고 보존하는지 분리해 검증한다.
- 구현 시 API contract, 영향받는 source/test, 이 페이지의 상태·개수·실행 명령을 같은 변경에서
  갱신한다. contract mismatch는 scenario를 느슨하게 만들지 말고 먼저 결정하거나 수정한다.

## 실제 배포 gate

staging/운영 리허설은 다음 목표를 모두 포함한다. 단계별 입력, evidence, 중단·복구 완료
조건은 [staging·운영 리허설 상세](release-http-e2e-staging.md)에 있다.

| ID | 목표 |
| --- | --- |
| STAGE-01 | artifact·migration·published snapshot·public HTTPS probe |
| STAGE-02 | 관리자 browser cookie/CORS와 익명 공개 접근 |
| STAGE-03 | public image delivery, media mount, dynamic state, recovery set |
| STAGE-04 | mobile polling (notice/goods/availability/crowding/Hyped), offline recovery, navigation, accessibility |
| STAGE-05 | rate-67와 warm/cold cache·activation spike·receipt/Hyped limit 부하, 관측 지표, rollback rehearsal |

`readyz`는 해당 festival의 published snapshot 적재를 보는 readiness probe이며 지속적인 DB
health check가 아니다. 연결된 image fixture가 없으면 image delivery stage는 미완료다.
DB/media restore는 일반 HTTP 실패의 기본 대응이 아니며, 실제로 복원할 때만 동일 recovery
set을 함께 사용하고 revision·Flyway·media·public smoke를 재확인한다.

## 구현·실행 기록

HTTP-25–27은 DynamicContentReleaseHttpE2eTest, HTTP-28–31은
OperationalBoundariesHttpE2eTest에 매핑했다. 중앙 OpenAPI release coverage와 focused class
선택기는 HTTP-01–32·OPS-01–20, 총 52개 시나리오를 사용한다.

`ArtistHypedHttpE2eTest`는 같은 disposable PostgreSQL과 loopback HTTP 서버에서
8 writers/4 readers와 24 writers/8 readers를 각각 10초간 실행한다. POST 성공 건수와
최종 HTTP·DB 누적 수의 일치를 검증하고 p95/p99, 오류 표본을
`target/hyped-load-results/summary.json`에 남긴다. 서버 처리량 측정 동안만 요청 수 제한을
끄며, 공개 쓰기 제한 자체는 `RateLimitTest`가 별도로 검증한다. 이 결과는 CI runner와
합성 fixture의 회귀 근거이고 staging·운영 용량을 뜻하지 않는다.

2026-09-25 PR #97의 `5f98cfc` 코드 기준 `api-v2-contract`, Java 21/25 verify,
`artist-hyped-load`, CodeQL, Docker image·filesystem 보안 검사 CI가 통과했다.
Java 21 verify에서 `ArtistHypedHttpE2eTest` 2개,
`DynamicContentReleaseHttpE2eTest` 5개,
`OperationalBoundariesHttpE2eTest` 4개가 모두 건너뜀 없이 통과했다.
Hyped load의 JSON 결과는 해당 CI artifact에 보관하며, 실제 staging·운영 용량과
browser/UI 구현은 아직 검증되지 않았다. candidate별 PASS/BLOCKED 기록과 배포 판정은
[검증 명령과 CI](validation.md) 및 각 상세 시나리오 문서를 따른다.
