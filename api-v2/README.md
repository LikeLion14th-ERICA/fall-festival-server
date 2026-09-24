# API 명세서 v2 · 화면 개발용 계약

LOVE-001 러브레터의 경로·상태·가상 예시는 [제품 명세](../docs/wiki/product/love-letter.md),
[화면 상태](SCREEN-STATES.md), [운영·연동 인계](../docs/wiki/workflow/love-letter-operations.md)를
함께 본다. 목 서버는 `X-Mock-Session`으로 가상 사용자 상태를 구분한다. 운영 서버의
HttpOnly 쿠키·Origin·CSRF·DB 원자성은 백엔드 통합 검사에서 검증한다.

상태: **2.0.0-draft.3 — 프런트 개발에 사용할 수 있는 초안**. 백엔드 구현·운영 배포·미정 기능 승인을 의미하지 않습니다.
LOVE-001 경로는 백엔드 구현을 포함하지만 기능은 기본 비활성이고 운영 활성화를 의미하지 않습니다.

[Product Context](../docs/PRODUCT_CONTEXT.md)와 승인된 사용자 결정에 따라 관리자 규칙은
[v5](../docs/wiki/product/admin/README.md)로 통일했습니다. 특정 과거 commit이 아니라 현재
branch의 생성 결과물을 함께 유지합니다. 이전 화면 원문은 비교용 스냅샷으로 보존하며 현재
요구는 [화면 데이터 표](SCREEN-DATA.md)와 [화면 상태](SCREEN-STATES.md)를 사용합니다.
실제 행사 날짜·가격·계좌·사진은 운영 자료로 확정해야 하며 예제는 가상 데이터입니다.

| 결과물 | 용도 |
|---|---|
| [openapi.json](openapi.json) | OpenAPI 3.1 경로·메서드·파라미터·필드·필수 여부·상태 코드·예제 |
| [ENDPOINTS.md](ENDPOINTS.md) | 62개 요청과 지원 시나리오 빠른 조회 |
| [examples.json](examples.json) | 요청 헤더·본문·경로와 468개 응답 원문 |
| [SCREEN-DATA.md](SCREEN-DATA.md) | 26개 화면의 유효 177개·제외 10개 필드 → API 또는 프런트 상태 추적표 |
| [FRONTEND.md](FRONTEND.md) | 실행·시나리오 전환·화면 연동 |
| [DECISIONS.md](DECISIONS.md) | 합의가 필요한 기술 계약과 운영 자료 |
| [client-state-examples.json](client-state-examples.json) | 스탬프 등 HTTP 응답으로 만들지 않는 로컬 상태 |
| [source-screen-requirements.json](source-screen-requirements.json) | 출처 8개 탭의 원문 스냅샷 |
| [release-operation-coverage.json](release-operation-coverage.json) | OpenAPI 전체 operation의 릴리스 provider·scope coverage와 HTTP/OPS 시나리오 매핑 |
| [release-test-selection.mjs](release-test-selection.mjs) | live provider·HTTP/OPS 시나리오·PostgreSQL 17 release anchor의 Maven test class 선택기 |
| [VERIFICATION.md](VERIFICATION.md) | 실제 검증 결과와 범위 |

`openapi.json`이 API 계약의 기계 판독 source of truth입니다. 이 저장소에는 runtime
springdoc 또는 Swagger UI가 없으며, 정적 OpenAPI 3.1 문서와 계약 검증을 사용합니다. 스키마와
예제는 source module에서 생성되므로 생성 JSON만 직접 수정하지 않습니다.

릴리스 coverage metadata도 `openapi.json`에서 자동 inventory합니다. 원천 매핑은
`release-operation-coverage.mjs`에 두고 `npm run check:release-coverage`로 62개 operation이
정확히 한 번 분류되는지, 각 live operation의 provider test와
HTTP-01~36·OPS-01~20 매핑이 유효한지 확인합니다. `npm run release:test-selection`은
live provider와 56개 HTTP/OPS 시나리오의 테스트 class, `Postgresql17MigrationReleaseTest`를
정렬된 Maven `-Dtest` CSV로 출력합니다. 이 검사는 제품 route를 활성화하지 않습니다.

Spring Boot 서버에는 공개 공연 조회·홈 설정을 포함한 API v2 구현과 LOVE-001 백엔드가
있습니다. 러브레터는 기본 비활성이며 공개 화면·운영 자료가 준비되기 전에는 활성화하지
않습니다. 다른 계약 경로도 각 구현 상태를 별도로 확인해야 하며, 계약에 있다는 사실만으로
실제 서버 구현이나 공개 승인을 의미하지 않습니다.

## 공통 계약

기본 경로는 `/api/v2`입니다. 공개 GET 요청은 인증이 없습니다. `/admin/` 요청은 서버에서 관리자 권한을 확인합니다. 목의 고정 토큰은 실제 인증 규격이 아닙니다. API v2가 제품의 유일한 계약이며, 실제 Spring Boot 적용 범위는 경로별 구현 상태와 함께 관리합니다.

성공은 `{ data, meta }`, 오류는 `{ error, meta }`입니다. 일반 `meta`는 `requestId`, `serverTime`, `timezone`, `festivalId`, `revision`, `locale`, `mock`을 포함합니다. `X-Request-Id`도 같은 요청 ID입니다. 특정 published `FestivalRevision`에 귀속된 콘텐츠의 `revision`은 실제 `FestivalRevision.revision_number`인 1 이상입니다. 인증·시스템·오류와 같이 특정 published revision에 안전하게 귀속되지 않는 응답은 `revision: 0`을 사용합니다. 혼잡도는 published FestivalDay 일정과 revision-independent 상태를 조합하므로 revision 0을 사용한다. 조건부 GET과 idempotency 재생이 가능한 공지 POST/PUT은 안정 body meta를 위해 requestId·serverTime을 본문에서 제외한다. 조건부 GET만 ETag와 X-Server-Time을 함께 제공하고, 공지 mutation은 X-Request-Id로 요청을 추적한다. 스탬프·티켓 안내는 해당 revision ID로 조회하므로 1 이상을 사용합니다. 오류 `code`로 분기하고 `message`는 진단에 사용합니다. 화면 문구는 프런트 번역에서 선택합니다.

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "요청 필드를 확인해 주세요.",
    "details": [{ "field": "level", "reason": "허용되지 않은 값입니다." }],
    "retryable": false
  },
  "meta": {
    "requestId": "example-request", "serverTime": "2030-10-01T18:00:00+09:00",
    "timezone": "Asia/Seoul", "festivalId": "festival-mock",
    "revision": 0, "locale": "ko", "mock": true
  }
}
```

| 항목 | 규칙 |
|---|---|
| 날짜·시각 | 날짜 `YYYY-MM-DD`, 시각 RFC 3339의 `+09:00` 포함. 시간대 `Asia/Seoul`. 날짜 경계는 사용자 기기 시간대와 무관 |
| 금액 | `{ "amount": 1000, "currency": "KRW" }`. 원 단위 0 이상의 정수. 문자열·소수점·센트 변환 없음 |
| 필수·선택 | OpenAPI의 `required`가 키 존재 여부를 결정. `null` 허용과 키 생략 가능은 별개. 선택 표시 정보도 키는 유지하고 값이 없으면 `null`인 필드가 대부분 |
| 빈 값 | 목록·연관 항목은 `[]`, 없는 선택 정보는 `null`. 빈 문자열·`"미정"`을 결측값으로 사용하지 않음. 필수 운영 데이터 누락을 가짜 값으로 채우지 않음 |
| 빈 목록·상세 부재 | 빈 목록은 200과 `items: []`. 존재하지 않는 상세 ID는 404. 선택 설명·링크가 없다고 전체 상세를 오류로 처리하지 않음 |
| 필드 검증 | 알 수 없는 본문 필드·쿼리, 중복 쿼리, 잘못된 enum·날짜는 거절. 스키마에 없는 필드는 임의 전송하지 않음 |
| 언어 | `locale` 쿼리, 생략 시 `ko`. 현재 공개 언어는 `ko`뿐이다. `config.languages`에 준비된 언어만 사용하며, 알려졌지만 준비되지 않은 언어는 `LOCALE_NOT_READY`, 알 수 없는 값은 `INVALID_QUERY`다. 공지·카탈로그 모두 한국어로 대신 노출하지 않는다. `all-languages`는 프런트 검증용 목 세션 제어값이다. |
| 이미지·링크 | 이미지 URL은 절대 또는 origin 기준 상대 경로. 상대 경로는 API origin으로 해석. 외부 링크는 HTTPS·새 탭. 목 자산은 개발용 SVG이며 외부 예제 주소는 연결 불가 |
| 목록 | 화면에서 필요한 전체 목록, 별도 pagination 없음. 정렬은 개별 스키마 설명에 명시. 부스 즐겨찾기 정렬은 브라우저 책임 |
| 캐시·버전 | 목은 `Cache-Control: no-store`. `revision` 1 이상은 응답 데이터가 실제로 귀속된 published FestivalRevision 번호이며 응답 완료 순서가 아님. `0`은 특정 published FestivalRevision에 안전하게 귀속되지 않음을 뜻함. 혼잡도는 published FestivalDay 일정과 `(festival_id, operating_date)` 상태를 사용해 항상 0이며 `ETag`·`If-None-Match`로 304를 지원함 |

| HTTP | 의미 |
|---|---|
| 200 / 201 | 조회·수정·삭제 성공 / 공지·상품 생성 성공. 삭제도 JSON 응답. 생성은 Location 헤더 포함 |
| 400 | 잘못된 쿼리·날짜·JSON·미지원 언어·목 제어값 |
| 401 / 403 | 관리자 미인증 / 권한 없음. 목의 미허용 origin도 403 |
| 404 / 405 | 리소스·경로 없음 / 미지원 메서드 |
| 409 | 지도 버전 불일치, 실제 `FestivalDay`가 아닌 날짜의 관리자 혼잡도 저장(`NOT_FESTIVAL_DAY`), 기타 운영 상태와 요청의 충돌 등. 해당 코드별 처리 |
| 413 / 415 / 422 | 본문 64KiB 초과 / JSON이 아닌 본문 / 필드 검증 실패 |
| 429 | 요청 제한. `Retry-After` 초, 목은 1초 |
| 500 / 503 | 처리 오류 / 일시적 사용 불가. 기존 화면 데이터 유지 여부는 화면별 상태 정의를 따름 |

## 화면 계약의 주요 결정

- 혼잡도는 사용자 홈과 관리자가 공유합니다. 지도에는 표시하지 않습니다. 운영 시간은 published FestivalDay에서 읽고 관리자 편집 경로는 없습니다. 관리자는 실제 `FestivalDay`인 날짜에만 저장할 수 있으며 운영 전·운영 종료 뒤에도 저장할 수 있습니다. 실제 FestivalDay가 아닌 날짜의 PUT은 `409 NOT_FESTIVAL_DAY`입니다. 공개 홈은 실제 운영 시간 상태를 우선하고 운영 전·종료에는 저장 시각을 숨깁니다. 관리자 저장은 `If-Match`와 `Idempotency-Key`가 필요하며 성공·재시도는 204입니다. 같은 단계를 다시 저장하면 성공으로 응답하되 수정 시각과 감사 이력을 바꾸지 않습니다. 일정이 없으면 `503 CROWDING_SCHEDULE_UNCONFIGURED`입니다.
- 굿즈는 Goods.options에 등록된 실제 색상×사이즈 조합만 표시합니다. 관리자가 ON_SALE(구매 가능)/SOLD_OUT(품절)을 선택하며 수량 입력·저장은 없습니다. 상품 정보와 판매 상태를 독립 조회하고 전체 품절이어도 상품과 계좌 안내 진입을 유지합니다. 신규 상품·조합은 ON_SALE로 시작하고 색상·사이즈·조합 삭제는 허용합니다. 삭제한 조합의 판매 상태를 제거하고 유지 조합 상태는 보존합니다.
- 한국어 공지는 먼저 저장·게시하며 영어가 PENDING/FAILED여도 막지 않습니다. 선택 언어의 READY 번역만 노출합니다. 직접 이미지 첨부와 공개 공지 상세 API는 없습니다.
- 타임테이블은 고정 반입 금지 물품 목록·안내를 /prohibited-items로 조회합니다. 공연 진행 여부에 따라 숨기지 않으며 현재 시각선은 축제 당일 17:00~22:00에만 표시합니다.
- 공지는 당일 일반 공지와 날짜와 무관한 분실물 공지를 합칩니다. 신규 공지와 삭제 공지의 갱신 UX가 달라 `visibleIds`를 제공합니다. 공개 상세·공지 이미지 API는 없습니다.
- 지도 이미지와 핀 요청을 분리합니다. 핀 요청의 `mapVersion`은 필수이며 이미지 버전 불일치는 409입니다. 구역 이동과 장소 팝업 대상은 서로 다른 타입입니다. 핀은 세부 `category`와 디자인 필터 `filterGroup`(화장실·포토부스·흡연구역·쓰레기통)을 함께 내보냅니다. 그 밖의 `PLACE` 핀은 `filterGroup: null`로 `전체`에서만 보이고, `AREA` 핀은 `filterGroup: null`로 항상 표시합니다.
- 시간표는 단일 무대만 사용하며 Stage 모델이나 stage 필드는 추가하지 않습니다.
- 티켓은 오늘의 송금·수령 안내입니다. 수량과 합계는 프런트 상태입니다. 입금 확인·주문 생성·지급 완료 API는 없습니다. 송금 시간 밖에는 계좌를 반환하지 않습니다.
- 스탬프는 안내를 조회하고, 수령 안내 창에서 현장 담당자가 입력한 코드는 `POST /stamp-receipt-verifications`로 서버 검증합니다. 참여 시작·4칸 누적·수령 완료·KST 날짜 초기화는 브라우저 상태이며, 인증 성공의 `verified: true`일 때만 `claimed`를 저장합니다. 코드는 서버 비밀 설정으로만 관리하고 서버 참여 기록·중복 차단·상품 재고 API는 만들지 않습니다. START 전 기본 카메라 QR 직접 진입은 시작 화면으로 보내고 자동 시작·적립하지 않습니다.
- FAQ는 준비 완료된 외부 페이지를 새 탭으로 연결합니다. 자료·공개 확인 전 `faq`는 `null`이며 FAQ 콘텐츠·번역·전용 API는 제공하지 않습니다. 웰컴 데이(`WELCOME-001`)는 참여 저조로 2026-09-22 결정에 따라 제거해 `Config.links`에서 뺐습니다.

## 변경·백엔드 인계

스키마·경로는 `contract-source.mjs`와 `admin-contract.mjs`, 동작·가상 자료는 `domain.mjs`와 `admin-domain.mjs`, 화면 연결은 `screen-coverage.mjs`에서 수정합니다. `npm run generate`로 산출물을 갱신하고 `npm run check`를 통과시킵니다. 생성 JSON만 수동 수정하면 검증이 실패합니다.

백엔드는 [결정 대기](DECISIONS.md)의 남은 기술·운영 자료를 확정한 뒤 같은 OpenAPI에 맞춰
구현합니다. 구현 완료된 공연 API는 실제 Spring Boot 서버로 연동하고, 아직 미구현된 화면과
계약 상태 개발에는 목 서버를 사용할 수 있습니다. 실제 서버로 전환할 때 목 전용
헤더·쿼리·토큰을 제거합니다. 번역 엔진·이미지 업로드는 별도 연동이 필요합니다. 번역
생성·검토·실패·저장 계약은 목으로 제공합니다. 실제 서버에도 계약·화면 소비자 검증을 실행한
뒤 전환합니다.

## 관리자 인증 연동

- `POST /api/v2/admin/sessions`에서 username/password로 로그인하고 access JWT를 응답으로 받습니다.
- access JWT는 15분 동안 유효하며 이후 관리자 요청의 `Authorization: Bearer <token>` 헤더로 보냅니다.
- refresh token은 7일 유효한 `Secure; HttpOnly; SameSite=Strict` cookie이므로 브라우저 코드에서 읽거나 별도 저장하지 않습니다. refresh 성공 시 기존 token은 폐기되고 cookie가 교체됩니다.
- 로그인·refresh·logout 요청은 배포 환경에 설정된 관리자 origin에서만 받습니다. 프런트는 credentials를 포함해 요청하고 브라우저가 보내는 정확한 `Origin` 헤더를 변경하거나 제거하지 않습니다.
- 일반 공개 API는 인증 없이 유지됩니다. 공개 회원가입과 세부 역할별 RBAC는 현재 범위에 없습니다.
- 이 인증·권한 기반은 관리자 웹 UI나 공지·굿즈·혼잡도 등 콘텐츠별 관리자 CRUD의 구현 완료를
  의미하지 않습니다.

기계 판독 문서 형식은 [OpenAPI 3.1.0](https://spec.openapis.org/oas/v3.1.0.html)을 사용합니다.
