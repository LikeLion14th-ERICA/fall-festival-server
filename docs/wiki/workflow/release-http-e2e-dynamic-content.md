# 동적 콘텐츠 HTTP E2E 상세 케이스

[릴리스 HTTP E2E 개요](release-http-e2e.md) · [위키 홈](../README.md) · 읽는 때: 공지·굿즈·굿즈 이미지의 릴리스 후보 HTTP E2E를 구현·검토할 때

이 문서는 `HTTP-25`–`HTTP-27`의 수용 조건과 구현 범위다. 상태는 모두
**구현 · 실행 미확인**이다. `DynamicContentReleaseHttpE2eTest`의 5개 JUnit method가
실제 HTTP 경계와 DB·파일 불변식을 검증한다. 이번 작업에서는 사용자 지시에 따라
사용자 지시에 따라 Maven·Docker·CI 기반 테스트는 실행하지 않았으므로 PASS 증거는 없다. 공통 HTTP 계약은
[API v2 endpoint index](../../../api-v2/ENDPOINTS.md)와
[관리자 변경 계약](../../../api-v2/ADMIN-CHANGES.md)을 정본으로 삼는다.

## 공통 harness·증거 규칙

- 각 케이스는 Testcontainers PostgreSQL, 후보 catalog를 실제 CLI로 게시한 랜덤 포트 서버,
  고정된 Asia/Seoul clock, 실제 관리자 login을 사용한다. 원격 DB·계정·운영 media volume은
  사용하지 않는다.
- catalog CLI는 셸·JVM 환경 property source를 제거하고 전용 Testcontainers datasource를
  직접 구성한다. HTTP context도 같은 전용 datasource를 사용하고 cleanup scheduler와
  전용 cleanup datasource는 비활성화한다. media root와 multipart directory는 run별 임시 경로다.
- 이미지 입력 검사·PNG decode·resize·processor orchestration·`FileSystemMediaStorage`는 실제
  구현을 사용한다. `ReleaseMediaTestConfiguration`은 기존 `GoodsImageProcessorTest`의
  deterministic `WebpTools` 경계를 재사용하므로 외부 `/usr/bin/cwebp`를 호출하지 않는다.
  출력은 전달·캐시 검증용 fixture bytes이며 유효한 WebP 인코딩 증거가 아니다. 네이티브 codec은
  기존 opt-in `GoodsImageProcessorAlpineIntegrationTest`와 `STAGE-03`에서 별도로 확인한다.
- 테스트용 festival·관리자·idempotency key·URL·이미지와 임시 media root에는 case run ID를
  넣어 서로 구분한다. 실운영 제목, 링크, 수령 코드, 계좌, 토큰을 fixture·assertion·로그에
  복사하지 않는다.
- 성공·실패 모두 status, 안정 error code와 request ID를 검증한다. 일반 응답과 error
  envelope는 `X-Request-Id`와 body의 `meta.requestId`가 같아야 한다. conditional representation과
  공지 생성·수정의 `ConditionalApiMeta`에는 request-specific field가 없으므로 header를 확인한다. 저장된 raw
  response를 재생하는 멱등 replay는 새 `X-Request-Id`와 저장된 body meta를 각각
  기록하고 둘의 byte 동일성을 요구하지 않는다. 사람용 오류 문구나 server time의 byte
  동일성은 비교하지 않는다.
- 멱등성 검증은 **완전히 같은 method·route·resource·payload**와 같은 key를 재전송한다.
  같은 key에 다른 payload를 붙이는 검증은 별도 request로 하고 `409 IDEMPOTENCY_KEY_REUSED`와
  무변경을 기대한다.
- `If-Match`가 필요한 변경은 바로 앞 관리자 detail GET의 strong ETag를 사용한다. 공개 목록의
  ETag와 관리자 detail의 ETag를 섞지 않으며, 유효 변경 뒤에는 다음 변경 전에 ETag를 다시 읽는다.
- DB 검증은 business row, `admin_audit_events`, idempotency record, association을 action별로
  세고, 파일 검증은 final variant와 `.staging`을 구분한다. Testcontainers 폐기 전 검증을
  끝내고, 임시 root·input file·child process를 정리한다.

## HTTP-25 · 공지 생성·수정·삭제의 공개 반영 — P0

**목적.** 공지의 실제 HTTP, 인증, 조건부 읽기, optimistic concurrency, soft delete와
감사·멱등 처리를 한 흐름으로 검증한다. `GENERAL`은 생성일의 KST 공개 창에서만 보이므로
고정 clock의 당일에 생성한다.

### 준비물

- 빈 공지 상태와 유효한 bearer admin session을 준비한다.
- 입력은 `type: GENERAL`, **ko·en 모두**의 title/body, HTTPS test URL 한 개를 가진다.
  link `labels` map은 `ko`, `en`, `zh-Hans: null`, `ja: null`의 네 locale key를
  정확히 모두 넣는다. `NoticeInput`은 한국어만으로는 유효하지 않으며, translations에서는
  optional locale key를 생략할 수 있어도 link `labels`에서는 생략할 수 없다.
- 공개 `GET /api/v2/notices`의 baseline ETag와 항목 수를 기록한다. 관리자 detail ETag는
  생성 뒤 `GET /api/v2/admin/notices/{id}`에서 별도로 읽는다.

| 단계 | 요청·입력 | HTTP 수용 조건 | 영속·공개 불변식 |
| --- | --- | --- | --- |
| 25.1 | baseline 공개 목록과 같은 `If-None-Match` 재조회 | 첫 요청 `200`, 미변경 요청 `304` | baseline은 이후 공개 ETag 비교용이며 다른 fixture 공지를 삭제하지 않는다. |
| 25.2 | `POST /api/v2/admin/notices` + bearer, JSON, 새 key; 인증된 `GET /api/v2/admin/notices` | POST `201`, `Location: /api/v2/admin/notices/{id}`, 응답 id 일치; 목록 `200`과 생성 notice id·`GENERAL`·ko title 확인 | notice 1건, translation·link 행, `NOTICE_CREATED` audit 1건과 completed idempotency 1건만 증가한다. |
| 25.3 | 25.2와 정확히 같은 POST·key 재전송 | `201`, 같은 id·Location·business data | 새 `X-Request-Id`와 저장된 body `meta.requestId`를 각각 기록하며 둘의 일치를 요구하지 않는다. notice·audit·idempotency 행은 추가되지 않는다. |
| 25.4 | 같은 POST key에 title 또는 link만 바꾼 payload | `409 IDEMPOTENCY_KEY_REUSED` | 25.2 상태와 public ETag가 변하지 않고 추가 audit이 없다. |
| 25.5 | 공개 목록 재조회 후 새 ETag로 conditional GET | `200`에 새 id와 ko 내용, 이어서 `304` | create 전 ETag와 달라야 한다. `visibleIds`와 items가 같은 id를 포함한다. |
| 25.6 | 관리자 detail ETag A를 얻은 뒤 새 key로 `PUT /api/v2/admin/notices/{id}`, 이어서 A와 다른 새 key로 stale PUT | 유효 PUT `200`; stale PUT `409 EDIT_CONFLICT` | 유효 변경만 `NOTICE_UPDATED` audit 1건, translation/link 전체 교체와 `updated_at` 변경을 만든다. stale 요청은 모든 row·audit·idempotency completion을 보존한다. |
| 25.7 | 유효 PUT 뒤 재조회한 관리자 ETag B와 새 key로 `DELETE /api/v2/admin/notices/{id}`; 같은 DELETE·key replay; 다른 새 key DELETE | 첫 두 요청 `200`; 새 key DELETE는 `409 ALREADY_DELETED` | 첫 DELETE만 `NOTICE_DELETED` audit 1건을 만들며 replay는 추가 side effect가 없다. |
| 25.8 | 삭제 뒤 public list·admin detail·DB를 읽는다 | public에서 id 없음, admin detail `404 NOT_FOUND` | `deleted_at` 설정, `created_at` 보존, link/translation 행 보존을 확인한다. create·update·delete audit은 각각 정확히 한 건이다. |

**중단·정리.** 생성 응답의 Location/id가 불일치하거나 create/update/delete 중 하나라도 public
ETag를 바꾸지 않으면 실패다. deletion 직후 admin detail이 보이거나 public list에 남아도
실패다. 테스트가 만든 row만 FK 역순으로 정리하며, 공통 container 소멸 전 audit과
idempotency count를 기록한다.

## HTTP-26 · 굿즈 운영 변경의 공개 구매 여정 — P0

**목적.** 상품 입력, 이미지 연결, 조합 판매 상태, conditional availability, product concurrency,
hard delete와 media lifecycle을 실제 HTTP에서 함께 검증한다.

### 준비물과 계약 주의

- `HTTP-27`의 실제 upload helper로 1–2개의 **unattached** media ID와 모든 variant를 준비한다.
  DB에 임의 UUID만 넣지 않는다. helper를 공유할 수 없으면 media asset·세 variant를 함께 만든
  fixture임을 명시한다.
- `OPTIONS` 상품은 ko·en 상품/색상/사이즈 번역, ko·en image alt, 실제로 모두 사용되는
  색상·사이즈, 자동 곱집합이 아닌 최소 세 조합을 갖는다. image는 1–2개여야 한다.
- public route는 `GET /api/v2/goods`, `GET /api/v2/goods/{id}`,
  `GET /api/v2/goods-availability`, `GET /api/v2/goods/{id}/availability`이고,
  관리자 route는 `/api/v2/admin/products*`와
  `/api/v2/admin/goods/{goodsId}/combinations/{combinationId}/availability`다.
- API v2 공통 계약의 creation `Location`에 맞춰 `AdminGoodsController` POST와 replay에
  `/api/v2/admin/products/{id}` header를 추가했다. 테스트는 둘의 id·Location 일치를 요구한다.

| 단계 | 요청·입력 | HTTP 수용 조건 | 영속·공개 불변식 |
| --- | --- | --- | --- |
| 26.1 | 익명 `GET /api/v2/goods`와 `GET /api/v2/goods-availability` baseline, 후자의 ETag로 같은 endpoint conditional GET | 두 baseline은 `200`, `GET /api/v2/goods-availability`의 미변경 conditional read는 `304` | `/goods`는 이 사례에서 ordinary `200` baseline만 확인한다. 기존 상품과 test 상품을 id로 구분한다. |
| 26.2 | 유효 OPTIONS 상품을 새 key로 `POST /api/v2/admin/products`하고 인증된 `GET /api/v2/admin/products`, `GET /api/v2/admin/goods`, 동일 request/key replay | POST·replay `201`, 같은 goods id·business data·Location; 두 목록 `200`과 생성 goods id·상품명·세 조합·`ON_SALE` 상태 확인 | create audit·상품/옵션/연결 행은 1회만 생긴다. replay의 requestId/server time은 새로워질 수 있으므로 data와 side effect만 비교한다. |
| 26.3 | 26.2의 `POST /api/v2/admin/products` key에 다른 가격 또는 option payload를 보낸다 | `409 IDEMPOTENCY_KEY_REUSED` | 상품·audit·media association은 26.2 후와 같다. |
| 26.4 | 공개 `GET /api/v2/goods`·`GET /api/v2/goods/{goodsId}`·`GET /api/v2/goods-availability`와 `GET /api/v2/admin/products/{goodsId}`를 읽는다 | 모두 익명/관리자 계약대로 `200`; `GET /api/v2/goods-availability` ETag를 얻는다 | 상품·이미지 URL·세 조합이 보이고 모두 `ON_SALE`, `allSoldOut=false`다. |
| 26.5 | 각 조합을 서로 다른 key로 `PUT /api/v2/admin/goods/{goodsId}/combinations/{combinationId}/availability`의 `SOLD_OUT`으로 바꾸고, 마지막 조합은 같은 key replay한다 | availability PUT은 `If-Match` 없이 `200`; replay도 `200` | replay는 새 `X-Request-Id`와 저장된 body meta를 기록한다. 대상 조합의 status/timestamp만 바뀌고 상품 `updated_at`과 다른 조합은 유지된다. 마지막 변경 뒤 `allSoldOut=true`, audit은 유효 변경당 1건이다. |
| 26.6 | 한 조합을 새 key로 같은 availability PUT의 `ON_SALE`로 되돌리고 `GET /api/v2/goods-availability`를 직전 ETag로 conditional GET한다 | `200`, 새 ETag, `allSoldOut=false` | 재판매 조합만 바뀌며 같은 key에 다른 status를 보내면 `409 IDEMPOTENCY_KEY_REUSED`다. |
| 26.7 | availability 후 다시 읽은 admin product ETag C로 `PUT /api/v2/admin/products/{goodsId}`에 유지·삭제·신규 조합을 섞고, 이어서 C와 새 key로 stale PUT | 유효 PUT `200`; stale PUT `409 EDIT_CONFLICT` | retained 조합 id/status/timestamp 보존, removed 조합·상태 행 삭제, new 조합은 새 id+`ON_SALE`이다. mode 전환은 조합 reset이므로 이 사례와 분리한다. |
| 26.8 | 업데이트 후 다시 읽은 ETag D+새 key로 `DELETE /api/v2/admin/products/{goodsId}`; 같은 DELETE/key replay; 다른 key DELETE | 첫 두 요청 `200`; 새 key는 `404 NOT_FOUND` | goods와 dependent option/association 행은 hard delete된다. media asset·final file은 남고 `attached_at`은 보존, `detached_at`은 설정되며 public media GET은 `404`다. |

**중단·정리.** availability에 `If-Match`를 요구하거나 product update 전에 stale ETag를
재사용하면 서로 다른 concurrency 정책을 가리는 잘못된 테스트가 된다. update·delete의
`If-Match`는 항상 관리자 detail에서 새로 읽는다. container 안에서만 product/media/audit
state를 정리하고, detached final media의 즉시 물리 삭제를 기대하지 않는다.

## HTTP-27 · 굿즈 이미지 업로드·공개 media 전달 — P0

**목적.** multipart parsing, 실제 processor·storage, idempotency, 연결 전 비공개성,
variant cache/security header와 detach lifecycle을 검증한다.

### 준비물

- `festival.media.storage-root`를 case 전용 임시 directory로 설정하고, production과 같은
  image processor·storage 경로가 실제로 동작하게 한다. codec 실행만 위 deterministic test seam으로
  대체한다. 이 property가 없으면 controller가 `503
  MEDIA_STORAGE_UNCONFIGURED`을 내므로 이 happy path를 대체하지 않는다.
- 관리자 bearer session, 유효한 작은 test image, 비어 있는 `media_assets`·audit·`.staging`
  기준 상태를 만든다. replacement 검증용 두 번째 유효 image bytes도 준비한다. 업로드는
  query/form field 없이 정확히 하나의 nonempty `file` part와 한 개의
  `Idempotency-Key`를 보낸다.

| 단계 | 요청·입력 | HTTP 수용 조건 | DB·파일 불변식 |
| --- | --- | --- | --- |
| 27.1 | `POST /api/v2/admin/media/goods-images` multipart + 새 key | `201`, opaque mediaId | media asset 1건, `GOODS_IMAGE_UPLOADED` audit 1건, master/320/640 variant set 1개, empty staging이다. 자동 HTTP 검증의 파일 내용은 codec fixture이며 creation response에 Location은 요구하지 않는다. |
| 27.2 | 27.1과 같은 bytes/key replay | `201`, 같은 mediaId | inspection/processing은 replay 전에 다시 일어날 수 있다. processor 호출 횟수는 비교하지 않고 DB·final variant·audit가 각각 1개이고 staging이 비었는지만 확인한다. |
| 27.3 | 같은 key에 다른 bytes | `409 IDEMPOTENCY_KEY_REUSED` | 추가 media/audit/final/staging side effect가 없다. |
| 27.4 | 연결 전 `GET /api/v2/media/goods-images/{mediaId}/master` | `404 NOT_FOUND` | unattached asset은 public으로 스트리밍할 수 없다. |
| 27.5 | 27.1 mediaId를 가진 상품을 `POST /api/v2/admin/products`로 생성한 뒤 `master`, `320`, `640` GET | 각 `200`, `image/webp`, `Content-Disposition: inline`, `X-Content-Type-Options: nosniff`, immutable cache control·strong ETag | 상품 응답 URL과 요청 variant가 일치하고 association/attached lifecycle이 설정된다. |
| 27.6 | 각 variant의 strong ETag로 strong·weak·multi·wildcard `If-None-Match` 재요청 | `304`, body 없음, 같은 `ETag`·immutable cache control·`X-Content-Type-Options`·request ID | `Content-Type`과 `Content-Disposition`은 `200` representation에서만 요구한다. variant가 실제로 열리므로 missing file은 conditional 요청도 `503`이어야 한다. |
| 27.7a | 두 번째 image bytes를 새 key로 실제 upload하여 unattached mediaId B를 얻고, 현재 ETag+새 key의 `PUT /api/v2/admin/products/{goodsId}`로 27.1 mediaId A를 B로 교체 | B의 public variant GET은 `200`, A의 public variant GET은 `404 NOT_FOUND` | A association만 제거되고 A의 `detached_at`은 설정된다. A/B final variants는 보존되고 B association/attached lifecycle이 설정되며 staging은 비어 있다. |
| 27.7b | 27.7a 뒤 새 ETag+새 key로 `DELETE /api/v2/admin/products/{goodsId}`하고 B public variant를 GET | DELETE `200`, B public GET `404 NOT_FOUND` | B association도 제거되고 `detached_at`이 설정된다. goods dependent row는 hard delete되지만 A/B final variants는 보존되고 staging은 비어 있다. |
| 27.8 | unknown variant와 query가 붙은 public media URL | unknown variant `404`, query `400 INVALID_QUERY` | DB·association·final variants·staging이 기준과 같다. |

### 실패 경로

| 입력 또는 주입 failure | 기대 결과 | side effect |
| --- | --- | --- |
| bearer 누락 또는 malformed bearer | `401 UNAUTHORIZED` | business·audit·idempotency completion·file 없음 |
| key 누락 | `428 IDEMPOTENCY_KEY_REQUIRED` | business·audit·idempotency completion·file 없음 |
| key malformed 또는 duplicate | `400 INVALID_IDEMPOTENCY_KEY` | business·audit·idempotency completion·file 없음 |
| JSON content | `415 UNSUPPORTED_MEDIA_TYPE` | final/staging file과 media row 없음 |
| missing/empty/multiple/extra file part 또는 form field | `422 VALIDATION_FAILED` | final/staging file과 media row 없음 |
| oversized application stream 또는 multipart parser limit 초과 | `413 PAYLOAD_TOO_LARGE` | final/staging file, media row, audit가 모두 없음 |
| processor backpressure | `429 RATE_LIMITED` + `Retry-After` | media/audit/final/staging 없음 |
| processor infrastructure failure | `503 SERVICE_UNAVAILABLE` | media·audit·idempotency record·final/staging file이 모두 없다 |
| storage finalize failure | `503 SERVICE_UNAVAILABLE` | business DB transaction rollback, final cleanup, staging discard가 끝난다. 독립 idempotency reservation은 `IN_PROGRESS`로 남으며 lease 전 같은 key는 `409 IDEMPOTENCY_IN_PROGRESS`, lease 만료 뒤 같은 payload 재시도만 정상 완료할 수 있다. |

**중단·정리.** final DB row는 있지만 variant가 열리지 않는 경우와 attachment가 없는 public
`200`은 즉시 실패다. cleanup scheduler의 retention 동작은 별도 cleanup test의 범위이며,
이 E2E는 detach 뒤 final file이 즉시 지워질 것이라고 가정하지 않는다.
실제 login으로 발급되는 bearer는 ADMIN authority뿐이므로, 발급 불가능한 non-admin bearer의
`403` branch는 이 real-login HTTP E2E에 억지로 만들지 않고 security lower-level coverage에서
다룬다.

## 구현과 실행 명령

`src/test/java/dev/espero/festival/e2e/DynamicContentReleaseHttpE2eTest.java`의 메서드 매핑은 다음과 같다.

| ID | JUnit method |
| --- | --- |
| HTTP-25 | `noticeLifecycleReplaysOnceRejectsStaleWritesAndRemovesPublicVisibility` |
| HTTP-26 | `goodsAvailabilityAndOptionEditsPreserveRetainedStateThenDetachOnHardDelete` |
| HTTP-27 | `mediaUploadReplayDeliveryValidatorsReplacementAndDetachHaveNoOrphanFiles` |
| HTTP-27 입력 실패 | `multipartAuthenticationValidationAndBothSizeLimitsLeaveNoPersistentState` |
| HTTP-27 인프라 실패 | `processorBackpressureAndInfrastructureFailuresRollbackAndAllowRecovery` |

인프라 실패 메서드만 spy에 실패를 주입한다. processor backpressure, 실제 staging 일부를
쓴 뒤 실패, 실제 final move 직후 실패를 분리한다. finalization 뒤 실패만 독립된
`IN_PROGRESS` idempotency reservation 1건을 남기고, business DB·감사·완료 record·final·staging은
무변경이어야 한다. 같은 key는 lease 전 거절되고 만료 뒤 같은 payload로 정상 복구한다. parser 상한은 이 테스트에서 11 MiB,
request 상한은 12 MiB로 두어 10 MiB application spool 상한과 parser 초과를 따로 검증한다.

Docker와 Java 21 이상을 준비한 뒤 저장소 루트에서 실행할 focused 명령은 다음과 같다.
이 명령은 이번 작업에서 실행하지 않았다.

```powershell
cmd /d /c "mvnw.cmd --batch-mode --no-transfer-progress -Dtest=DynamicContentReleaseHttpE2eTest test"
```

네이티브 codec·런타임 이미지·실제 WebP decode 호환성, 브라우저 polling과 운영 media mount
증거는 이 class의 결과에 포함하지 않는다. [staging gate](release-http-e2e-staging.md)와 기존
media compatibility 검증을 별도로 완료해야 한다.
