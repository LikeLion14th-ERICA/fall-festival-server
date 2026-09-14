# API 명세서 v2 · 화면 개발용 계약

상태: **2.0.0-draft.3 — 프런트 개발에 사용할 수 있는 초안**. 백엔드 구현·운영 배포·미정 기능 승인을 의미하지 않습니다.

[Product Context](../docs/PRODUCT_CONTEXT.md)와 2026-09-14 사용자 결정에 따라 관리자 규칙은
[v5](../docs/wiki/product/admin/README.md)로 통일했습니다. main 21eb76dacd78b3ad79ed4d9589dd341fbc25b883에 PR #6까지
병합된 상태를 기준으로 동기화했습니다. 이전 화면 원문은 비교용 스냅샷으로 보존하며 현재
요구는 [화면 데이터 표](SCREEN-DATA.md)와 [화면 상태](SCREEN-STATES.md)를 사용합니다.
실제 행사 날짜·가격·계좌·사진은 운영 자료로 확정해야 하며 예제는 가상 데이터입니다.

| 결과물 | 용도 |
|---|---|
| [openapi.json](openapi.json) | OpenAPI 3.1 경로·메서드·파라미터·필드·필수 여부·상태 코드·예제 |
| [ENDPOINTS.md](ENDPOINTS.md) | 37개 요청과 지원 시나리오 빠른 조회 |
| [examples.json](examples.json) | 요청 헤더·본문·경로와 257개 응답 원문 |
| [SCREEN-DATA.md](SCREEN-DATA.md) | 26개 화면의 유효 176개·제외 10개 필드 → API 또는 프런트 상태 추적표 |
| [FRONTEND.md](FRONTEND.md) | 실행·시나리오 전환·화면 연동 |
| [DECISIONS.md](DECISIONS.md) | 합의가 필요한 기술 계약과 운영 자료 |
| [client-state-examples.json](client-state-examples.json) | 스탬프 등 HTTP 응답으로 만들지 않는 로컬 상태 |
| [source-screen-requirements.json](source-screen-requirements.json) | 출처 8개 탭의 원문 스냅샷 |
| [VERIFICATION.md](VERIFICATION.md) | 실제 검증 결과와 범위 |

## 공통 계약

기본 경로는 `/api/v2`입니다. 공개 GET 요청은 인증이 없습니다. `/admin/` 요청은 서버에서 관리자 권한을 확인합니다. 목의 고정 토큰은 실제 인증 규격이 아닙니다. 이번 변경은 v1과 기존 Spring Boot 구현을 수정하지 않습니다.

성공은 `{ data, meta }`, 오류는 `{ error, meta }`입니다. `meta`는 `requestId`, `serverTime`, `timezone`, `festivalId`, `revision`, `locale`, `mock`을 포함합니다. `X-Request-Id`도 같은 요청 ID입니다. 오류 `code`로 분기하고 `message`는 진단에 사용합니다. 화면 문구는 프런트 번역에서 선택합니다.

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
    "revision": 1, "locale": "ko", "mock": true
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
| 언어 | `locale` 쿼리, 생략 시 `ko`. `config.languages`에 준비된 언어만 사용. 목은 `ko`, `en`. 공지의 선택 언어가 READY가 아니면 제외하며 한국어로 대신 노출하지 않음 |
| 이미지·링크 | 이미지 URL은 절대 또는 origin 기준 상대 경로. 상대 경로는 API origin으로 해석. 외부 링크는 HTTPS·새 탭. 목 자산은 개발용 SVG이며 외부 예제 주소는 연결 불가 |
| 목록 | 화면에서 필요한 전체 목록, 별도 pagination 없음. 정렬은 개별 스키마 설명에 명시. 부스 즐겨찾기 정렬은 브라우저 책임 |
| 캐시·버전 | 목은 `Cache-Control: no-store`. `revision`은 같은 축제·세션의 데이터 변경 순서 비교용이며 응답 완료 순서가 아님. 실제 캐시·배포 환경 계약은 추가 합의 필요 |

| HTTP | 의미 |
|---|---|
| 200 / 201 | 조회·수정·삭제 성공 / 공지·상품 생성 성공. 삭제도 JSON 응답. 생성은 Location 헤더 포함 |
| 400 | 잘못된 쿼리·날짜·JSON·미지원 언어·목 제어값 |
| 401 / 403 | 관리자 미인증 / 권한 없음. 목의 미허용 origin도 403 |
| 404 / 405 | 리소스·경로 없음 / 미지원 메서드 |
| 409 | 지도 버전 불일치, 운영 상태와 요청의 충돌 등. 해당 코드별 처리 |
| 413 / 415 / 422 | 본문 64KiB 초과 / JSON이 아닌 본문 / 필드 검증 실패 |
| 429 | 요청 제한. `Retry-After` 초, 목은 1초 |
| 500 / 503 | 처리 오류 / 일시적 사용 불가. 기존 화면 데이터 유지 여부는 화면별 상태 정의를 따름 |

## 화면 계약의 주요 결정

- 혼잡도는 사용자 홈과 관리자가 공유합니다. 지도에는 표시하지 않습니다. 운영 시간은 개발자 등록이며 관리자 편집 경로는 없습니다. KST 00:00에 전날 상태·수정 시각을 초기화합니다. 저장 상태와 시간에 따른 운영 상태를 분리합니다.
- 굿즈는 Goods.options에 등록된 실제 색상×사이즈 조합만 표시합니다. 관리자가 ON_SALE(구매 가능)/SOLD_OUT(품절)을 선택하며 수량 입력·저장은 없습니다. 상품 정보와 판매 상태를 독립 조회하고 전체 품절이어도 상품과 계좌 안내 진입을 유지합니다. 신규 옵션 최초 상태는 미정이며 명시적 목 시나리오로만 선택합니다.
- 한국어 공지는 먼저 저장·게시하며 영어가 PENDING/FAILED여도 막지 않습니다. 선택 언어의 READY 번역만 노출합니다. 직접 이미지 첨부와 공개 공지 상세 API는 없습니다.
- 타임테이블은 고정 반입 금지 물품 목록·안내를 /prohibited-items로 조회합니다. 공연 진행 여부에 따라 숨기지 않으며 현재 시각선은 축제 당일 17:00~22:00에만 표시합니다.
- 공지는 당일 일반 공지와 날짜와 무관한 분실물 공지를 합칩니다. 신규 공지와 삭제 공지의 갱신 UX가 달라 `visibleIds`를 제공합니다. 공개 상세·공지 이미지 API는 없습니다.
- 지도 이미지와 핀 요청을 분리합니다. 핀 요청의 `mapVersion`은 필수이며 이미지 버전 불일치는 409입니다. 구역 이동과 장소 팝업 대상은 서로 다른 타입입니다.
- 티켓은 오늘의 송금·수령 안내입니다. 수량과 합계는 프런트 상태입니다. 입금 확인·주문 생성·지급 완료 API는 없습니다. 송금 시간 밖에는 계좌를 반환하지 않습니다.
- 스탬프는 안내만 조회합니다. 참여 시작·4칸 누적·수령·KST 날짜 초기화는 브라우저 상태이며 서버 참여 기록·중복 차단·상품 재고 API는 없습니다.
- 웰컴 데이는 준비 완료된 외부 소개 페이지를 새 탭으로 연결합니다. 자료·공개 확인 전 `welcomeDay: null`입니다. FAQ는 비활성입니다.

## 변경·백엔드 인계

스키마·경로는 `contract-source.mjs`와 `admin-contract.mjs`, 동작·가상 자료는 `domain.mjs`와 `admin-domain.mjs`, 화면 연결은 `screen-coverage.mjs`에서 수정합니다. `npm run generate`로 산출물을 갱신하고 `npm run check`를 통과시킵니다. 생성 JSON만 수동 수정하면 검증이 실패합니다.

백엔드는 먼저 [결정 대기](DECISIONS.md)의 기술 초안을 합의하고 같은 OpenAPI에 맞춰 구현합니다. 프런트는 API origin을 실제 서버로 전환하고 목 전용 헤더·쿼리·토큰을 제거합니다. 실제 관리자 인증·번역 엔진·이미지 업로드는 별도 연동이 필요합니다. 번역 생성·검토·실패·저장 계약은 목으로 제공합니다. 실제 서버에도 계약·화면 소비자 검증을 실행한 뒤 전환하며 v1 호환 경로를 조용히 덮어쓰지 않습니다.

기계 판독 문서 형식은 [OpenAPI 3.1.0](https://spec.openapis.org/oas/v3.1.0.html)을 사용합니다.
