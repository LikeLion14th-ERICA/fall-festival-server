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
| `AdminSessionReleaseE2eTest` | 로그인·refresh rotation·logout·인증 경계 | 2 |
| `CrowdingConcurrencyE2eTest` | 혼잡도 동시 저장·멱등·시간 경계 | 5 |
| `OperationalAccountPropagationE2eTest` | TICKET 계좌 CLI → HTTP 반영·송금 시간 경계 | 2 |
| `CatalogPublicationLifecycleE2eTest` | 게시·재시작·rollback의 HTTP 노출 | 1 |

따라서 현재 실제 HTTP E2E는 **6개 class, 20개 JUnit method**다. 하나의 method가 관계된
요청을 함께 묶으므로 [검증 명령과 CI](validation.md)의 위험 시나리오 기준 수는
`HTTP-01`–`HTTP-24`, 즉 **24개**다. 운영자·개발자 도구 시나리오
`OPS-01`–`OPS-20`은 별도 process E2E이며 HTTP 수에 포함하지 않는다.

## 출시 준비 판단

현재 gate는 catalog 탐색, 지도·공연 관계, 티켓, 관리자 세션, 혼잡도, 게시·rollback,
조건부 읽기와 제한을 폭넓게 검증한다. 공지·굿즈·굿즈 이미지와 스탬프 수령 확인은
MockMvc, 단위 또는 DB 통합 검증이 있어도 실제 HTTP server·security filter·serialization·runtime
storage를 한 흐름으로 확인하지 않는다.

그래서 현재 suite는 **catalog 중심 위험의 자동 검증 증거**다. 전체 릴리스 승인은 아래
계획 케이스의 구현·통과뿐 아니라 제품 공개 gate, required CI, migration/운영 승인과 실제
staging 증거를 함께 판단한다. 계획 ID는 구현과 실행 명령 등록 전까지 현재 24개 수에 더하지
않는다.

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
| STAGE-04 | mobile polling (notice/goods/availability/crowding), offline recovery, navigation, accessibility |
| STAGE-05 | rate-67, 관측 지표, rollback rehearsal |

`readyz`는 해당 festival의 published snapshot 적재를 보는 readiness probe이며 지속적인 DB
health check가 아니다. 연결된 image fixture가 없으면 image delivery stage는 미완료다.
DB/media restore는 일반 HTTP 실패의 기본 대응이 아니며, 실제로 복원할 때만 동일 recovery
set을 함께 사용하고 revision·Flyway·media·public smoke를 재확인한다.

## 구현·실행 기록

`HTTP-25`–`HTTP-31`을 실제 release E2E에 포함한 시점에만
[검증 명령과 CI](validation.md)의 class 목록·scenario 수·focused command를 갱신한다.
문서만 바뀌는 현재 변경은 scenario 설계이며 앱·E2E 명령을 실행하지 않는다.
