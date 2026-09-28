# 운영·보안 HTTP E2E 상세 케이스

[릴리스 HTTP E2E 개요](release-http-e2e.md) · [위키 홈](../README.md) · 읽는 때: 스탬프 수령 확인, 비공개 계좌 이력·템플릿 process, 관리자 mutation 경계를 릴리스 후보에서 구현·검토할 때

이 문서는 HTTP-28–HTTP-31의 수용 조건과 구현 범위를 기록한다. 과거 후보에서의
테스트 실행 여부는 이번 후보의 통과 또는 출시 완료 증거가 아니다. 계좌와 스탬프 값은 모두 test-only
fixture로 만들고, 비밀 원문을 source, assertion message, child process 출력 캡처, PR 본문에
남기지 않는다.

## 구현 연결과 남은 범위

구현 파일은 `src/test/java/dev/espero/festival/e2e/OperationalBoundariesHttpE2eTest.java`다.
각 method는 별도 Testcontainers PostgreSQL과 실제 catalog CLI로 게시한 후보를 사용한다.
실제 loopback Spring HTTP 서버, 고정 KST clock과 실제 별도 CLI JVM을 사용하며 mock bean은 없다.

| ID | JUnit method | 코드 작성 범위 |
| --- | --- | --- |
| HTTP-28 | `receiptRotationRateLimitsAndUnconfiguredServerPreserveDatabaseAndSecrets` | 이전·신규 코드 및 앞자리 0, 잘못된 형식, 5회 이후 429, XFF 왼쪽 위조 방어·다른 client·12초 refill, 이전 코드 제거 후 재기동, 미설정 503, 전체 DB 무변경·응답/로그 비밀 비노출 |
| HTTP-29 | `legacyGoodsAccountChangesNeverExposePaymentDetails` | 별도 GOODS CLI dry-run/set/stale/clear 이력·version·TICKET 격리, 공개 굿즈 가격 유지·계좌 비노출·payment-guide 404, 재기동 뒤에도 비노출, CLI redaction |
| HTTP-30 | `templateCliReplacementIsAtomicAndPreservesNoticesFromRemovedTemplates` | preview 무변경, 전체 교체·관리자 HTTP 목록/상세, 인증/query 경계, 템플릿 기반 공지 생성, 템플릿 삭제 후 공지/감사 보존, malformed/duplicate preview·confirm 실패 무변경 |
| HTTP-31 | `administratorMutationMatrixRejectsBeforeSideEffectsAndNoticeReplayIsExactlyOnce` | 8개 mutation route의 무인증/손상 bearer·key 누락/형식/중복, notice/product If-Match 누락/형식, notice 생성/수정/삭제 replay·stale·key 재사용, DB·파일 상태 |

HTTP-31의 정상 상품·판매 상태·실제 이미지 업로드 및 replay·교체·detach는 HTTP-26/27의
동적 콘텐츠 lifecycle 검증과 함께 판단한다. 이 클래스의 product/media header 검사는
구조상 유효한 최소 payload와 존재하지 않는 ID, nonempty multipart를 사용해 **header/auth 경계만**
검사하며 이를 정상 media decode·upload·product 생성의 증거로 사용하지 않는다. 상품 stale ETag,
잘못된 enum/media reference, processor/storage failure의 HTTP matrix는 이 클래스에 구현하지 않았다.
HTTP-29의 상품은 이전 계좌 이력과 공개 비노출 검증용 DB fixture이며 상품 생성 경로 검증은 HTTP-26의 책임이다.

중요한 구현 차이: `NoticeController`의 If-Match 검사는 `AdminIdempotencyService`의 별도
reservation transaction 이후다. 따라서 notice의 missing/malformed/stale If-Match 실패는
`IN_PROGRESS` 예약 1건을 남길 수 있으며 업무·감사·`COMPLETED` 응답은 남기지 않는다.
테스트는 이 실제 상태를 구분해 검사한다. 모든 거절이 reservation 자체까지 무변경이라는
아래 원래 설계의 기대는 현재 notice 구현과 맞지 않으므로, 이를 강화하려면 별도 구현 결정이 필요하다.

focused 실행 명령은 다음과 같다. 결과는 후보별 작업 기록에 남긴다.

```powershell
cmd /d /c "mvnw.cmd --batch-mode --no-transfer-progress -Dtest=OperationalBoundariesHttpE2eTest test"
```

HTTP-28–30은 중앙 release 시나리오 inventory에 구현 상태로 포함되며 HTTP-31은 위 명시 범위의
부분 구현으로 포함된다. 후보별 실행 증거 없이는 PASS 수를 늘리지 않는다. HTTP-26/27
연결과 위 잔여 matrix를 확인한 뒤 최종 gate 통과 여부를 판단한다.

## 공통 harness·격리

- HTTP server는 Testcontainers DB와 published candidate에서 먼저 기동한다. HTTP-29와
  HTTP-30의 CLI는 그 server와 **같은 ephemeral DB**를 향하는 별도 child JVM으로 기동한다.
  CLI가 Flyway를 실행하거나 현재 셸의 datasource를 물려받지 않는지 구성에서 확인한다.
- 과정마다 새 run ID, 별도 admin, distinct rate-limit client identity, temporary input file과
  media root를 쓴다. CLI exit code·stdout·stderr와 HTTP response는 무작정 전부 snapshot하지
  않고 status/code/redaction에 필요한 field만 남긴다.
- expected error에는 status, stable `error.code`, retryable flag, `X-Request-Id`와
  `meta.requestId` 일치를 확인한다. 수령 code/hash, access·refresh token, full account
  number, datasource URL/password를 오류 메시지나 failure report에 찍지 않는다.
- 승인 전 staging 또는 운영 credentials로 이 케이스를 실행하지 않는다. child process·temporary
  JSON·DB/media fixture는 case 종료 후 제거하고, 정리 실패도 test failure로 기록한다.

## HTTP-28 · 스탬프 수령 코드와 abuse 방어 — P0

**목적.** 공개 receipt endpoint의 회전 코드, generic validation error, proxy-aware rate limit과
비밀 비노출을 실제 HTTP filter/controller 경계에서 검증한다.

### 준비물

- published catalog, fixed KST clock, `festival.stamp-receipt.code-sha256`에 test-only 이전·현재
  6자리 code의 SHA-256 hash 두 개를 설정한다. 원문 숫자는 test helper의 local variable에만 두고
  assertion failure에는 식별자만 쓴다.
- deployment와 같은 `trusted-proxy-hops`, stamp-receipt burst 5/12초 refill 정책을 설정한다.
  X-Forwarded-For chain은 configured hop 수를 충족시켜야 하며, rate 사례는 기능 사례와 다른
  client identity를 쓴다.

| 단계 | 요청·입력 | HTTP 수용 조건 | 보안·영속 불변식 |
| --- | --- | --- | --- |
| 28.1 | 이전 hash에 해당하는 code와 현재 hash에 해당하는 code를 각각 `POST /api/v2/stamp-receipt-verifications` | 각각 `200`, `data.verified=true` | endpoint는 익명이다. account/session/participation/reward/audit row가 생기지 않는다. |
| 28.2 | 비숫자, 길이가 다른 값, 형식은 맞지만 일치하지 않는 code | 모두 `422 INVALID_RECEIPT_CODE` | 어느 경우에도 실패 이유·hash 후보 수가 노출되지 않고 DB 상태가 기준과 같다. |
| 28.3 | hash를 비운 새 server instance에서 동일 POST | `503 STAMP_RECEIPT_UNCONFIGURED` | unconfigured와 invalid branch를 구분하되 code/hash 원문은 response·log에 없다. |
| 28.4 | 28.1·28.2와 bucket을 절대 공유하지 않은 새 forwarded client identity에서 wrong numeric code 5회 뒤 1회 더 요청 | 처음 5회 `422`, 6번째 `429 RATE_LIMITED` + numeric `Retry-After` | limiter는 controller보다 앞에서 동작한다. 429도 safe error envelope·request ID를 가진다. |
| 28.5 | 다른 complete forwarded chain으로 valid/invalid request, 이후 12초 refill 뒤 첫 client 재요청 | 두 번째 client는 독립 bucket, refill 뒤 첫 client는 다시 controller 결과 | client key가 socket proxy 하나로 합쳐지거나 XFF의 임의 왼쪽 값을 신뢰하면 실패다. |
| 28.6 | response header/body, audit·관련 DB table, captured application log를 secret scanner로 검사 | code/hash/token string 없음 | controller 응답은 verified boolean 외 수령 기록을 만들지 않는다. |

**중단·정리.** rate case가 prior request 때문에 이미 429이거나 XFF chain이 trust rule을 만족하지
않으면 PASS로 해석하지 말고 fixture isolation failure로 끝낸다. hash 제거 instance와 limiter
state를 각각 폐기한다.

## HTTP-29 · 이전 GOODS 계좌 CLI와 공개 비노출 — P0

**목적.** 이전 운영 계좌 설정과 변경 이력은 보존하면서 공개 상품 API에 계좌나 송금
안내가 다시 나타나지 않는지 확인한다.

- 임시 DB에 두 상품과 test-only GOODS 계좌 설정을 준비한다. 별도 CLI JVM의 dry-run,
  confirm set, stale expected-version 거절, clear와 서버 재시작을 순서대로 검증한다.
- 각 단계의 `GET /api/v2/goods`·`GET /api/v2/goods/{goodsId}`에는 가격·판매 상태만
  있으며 계좌·예금주·송금 링크가 없다. 이전
  `GET /api/v2/goods/{goodsId}/payment-guide`는 `404`다.
- dry-run·stale 실패는 DB 이력을 늘리지 않고, 유효 set·clear는 해당 이력만 남긴다.
  CLI 출력에는 계좌 원문이나 datasource 비밀값이 없어야 한다.
- 서버를 재시작한 뒤에도 같은 비노출을 확인한다. 구 이미지로 rollback할 때는 이전
  계좌 설정이 재공개될 수 있으므로 D의 `clear`·검증 없이 기동하지 않는다.

## HTTP-30 · 공지 템플릿 process → HTTP 경계 — P1

**목적.** full-set replacement CLI와 읽기 전용 관리자 template API, template에서 시작한
공지의 독립성을 실제 process/HTTP 경계에서 확인한다.

### 준비물

- template set A와 B를 별도 temporary JSON file로 준비한다. A에는 test template id, ko와
  선택 locale의 번역이 있고 B에는 그 id가 없다. JSON 원문에는 실제 운영 제목·URL을 넣지 않는다.
- 실제 `NoticeTemplateCliApplication` child process와 관리자 bearer session을 준비한다.
  public notice visibility를 보려면 공지 payload도 ko·en을 모두 갖춘다. 등록 날짜와 관계없이 노출된다.

| 단계 | CLI 또는 HTTP 흐름 | 수용 조건 | 상태 불변식 |
| --- | --- | --- | --- |
| 30.1 | set A를 `replace --input-file`만으로 실행 | preview exit 성공, “Nothing was written” | template table은 baseline 그대로다. |
| 30.2 | set A를 `replace --input-file --confirm`으로 실행 | exit 성공 | template set 전체가 A와 일치하고 partial leftover가 없다. |
| 30.3 | `GET /api/v2/admin/notice-templates`와 `/{templateId}` | `200`, id/name/번역 shape가 A와 일치 | unauthenticated/malformed bearer는 `401`이고 data가 없다. query가 있으면 `400 INVALID_QUERY`다. |
| 30.4 | A template id로 `POST /api/v2/admin/notices`를 생성하고 admin/public 읽기 | `201` + Location, 두 read가 의도한 content를 보인다 | notice의 template association과 create audit이 생긴다. template data는 public response에 노출되지 않는다. |
| 30.5 | set B를 confirmed replacement로 실행 | exit 성공 | removed template detail은 `404 NOT_FOUND`, list에 없다. |
| 30.6 | 30.4 notice를 admin/public에서 다시 읽는다 | notice는 여전히 읽히고 public visibility 유지 | template association만 null로 해제되고 notice content/link/audit는 보존된다. |
| 30.7 | malformed·duplicate id input을 dry-run/confirm 경로에서 각각 시도 | stable template CLI error code, non-zero failure | A/B 현재 set은 atomic하게 보존되고 HTTP list/detail도 변하지 않는다. |

**중단·정리.** CLI preview가 write하거나 failed replacement가 일부 template만 바꾸면 실패다.
각 temporary JSON과 child process를 제거하며, template deletion 이후 notice가 함께 사라질
것이라고 기대하지 않는다.

## HTTP-31 · 공지·굿즈·media 관리자 mutation 경계 — P1

**목적.** 인증, header/precondition, parser와 storage failure가 business mutation보다 먼저
차단되는지 endpoint matrix로 검증한다. Origin policy를 임의로 일반화하지 않는다.

### 대상 matrix

| 대상 | valid write 예시 | key | If-Match |
| --- | --- | --- | --- |
| notice create | `POST /api/v2/admin/notices` | 필수 | 불필요 |
| notice update/delete | `PUT/DELETE /api/v2/admin/notices/{id}` | 필수 | strong ETag 필수 |
| product create | `POST /api/v2/admin/products` | 필수 | 불필요 |
| product update/delete | `PUT/DELETE /api/v2/admin/products/{id}` | 필수 | strong ETag 필수 |
| combination availability | `PUT /api/v2/admin/goods/{goodsId}/combinations/{combinationId}/availability` | 필수 | **불필요**, last-write-wins |
| goods image upload | `POST /api/v2/admin/media/goods-images` multipart file | 필수 | 불필요 |

각 대상은 유효한 최소 payload와 baseline business/audit/idempotency/association/file count를
준비한다. rate-limit bucket 간섭을 피하려면 case별 client identity를 분리한다.

| 실패 사례 | 기대 status/code | 공통 불변식 |
| --- | --- | --- |
| Authorization 없음 또는 malformed bearer | `401 UNAUTHORIZED` | route·payload·media bytes와 무관하게 business/audit/idempotency/file/association이 기준과 같다. |
| key 누락 | `428 IDEMPOTENCY_KEY_REQUIRED` | reservation/completed record가 생기지 않는다. |
| key duplicate 또는 format 오류 | `400 INVALID_IDEMPOTENCY_KEY` | 동일하다. |
| notice/product update/delete의 If-Match 누락 | 428 PRECONDITION_REQUIRED | product는 기준과 같다. notice는 business·audit·file·association·COMPLETED 응답이 기준과 같고, 구현상 별도 IN_PROGRESS reservation 1건이 남을 수 있다. |
| notice/product update/delete의 malformed If-Match | 400 INVALID_IF_MATCH | product는 기준과 같다. notice는 business·audit·file·association·COMPLETED 응답이 기준과 같고, 구현상 별도 IN_PROGRESS reservation 1건이 남을 수 있다. |
| notice/product current representation을 바꾼 뒤 오래된 strong If-Match | 409 EDIT_CONFLICT | 오래된 request는 새 data·audit·idempotency completion을 만들지 않는다. notice는 구현상 별도 IN_PROGRESS reservation 1건이 남을 수 있다. |
| 잘못된 JSON/enum/media reference 또는 media multipart shape/content type | 계약의 `400/415/422` | parser/storage 실패가 final media file과 DB transaction을 남기지 않는다. |
| 같은 key의 다른 payload | `409 IDEMPOTENCY_KEY_REUSED` | 첫 유효 mutation의 결과만 존재한다. |

- 모든 대상에 대해 한 번의 valid write와 정확한 replay를 별도로 수행해 audit/business row가
  1회만 생김을 확인한다. replay 뒤 public `GET /notices`, goods, availability와 media URL은
  계속 anonymous로 접근 가능해야 한다.
- combination availability는 `If-Match` missing·malformed·stale 사례를 만들지 않는다.
  대신 key missing/malformed/duplicate, 정확한 replay와 같은 key의 다른 payload를 각각
  검증한다.
- missing/foreign `Origin`은 bearer mutation의 일반 precondition이 아니다. 현재 CSRF filter는
  login, refresh, logout cookie flow에만 적용된다. HTTP-31은 Origin만으로 unauthenticated
  mutation이 성공하지 않는지 확인하고, browser cookie/CORS의 허용·거절은
  [staging case STAGE-02](release-http-e2e-staging.md)
  에서 실제 origin으로 검증한다.
- error response와 captured logs에 bearer/cookie, account, upload body, media storage path가
  나오지 않는지 확인한다. token·binary를 assertion failure에 출력하지 않는다.

**중단·정리.** 어느 reject라도 business·audit·file·association 또는 COMPLETED idempotency
상태를 바꾸면 실패다. notice update/delete의 If-Match reject는 위에 명시한 별도
IN_PROGRESS reservation만 허용한다. notice/product 동시성 오류를 availability에 적용하거나,
session CSRF 정책을 bearer write 결과로 오해하면 잘못된 test이므로 구현 전에 matrix를 수정한다.
