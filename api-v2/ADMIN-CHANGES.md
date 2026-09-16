# API v2 draft.3 변경·마이그레이션

main `21eb76dacd78b3ad79ed4d9589dd341fbc25b883`(PR #6 병합 완료) Product Context와 2026-09-14 사용자 결정에 따라
[관리자 v5](../docs/wiki/product/admin/README.md)를 기준으로 통일했습니다.
아래는 draft.2 소비자가 변경할 계약입니다. API v2가 제품 계약이며 Spring Boot 구현·운영 배포 범위는 경로별로 함께 추적합니다.

| 영역 | draft.2 | draft.3 |
|---|---|---|
| 혼잡도 | 홈·지도 표시 | 홈만 표시, 지도 핀·색상 연동 없음 |
| 운영 시간 | 관리자 GET/PUT | 개발자 등록, 관리자 시간 편집 경로 제거 |
| 날짜 | 운영일 이력 | KST 00:00 전날 상태·수정 시각 초기화 |
| 굿즈 상태 | 정수 수량에서 자동 판정 | 실제 조합별 ON_SALE/SOLD_OUT 직접 저장. 관리자도 수량 미관리 |
| 상품 옵션 | 전체 색상×사이즈 곱집합 | Goods.options에 등록된 실제 조합만 |
| 신규 옵션 | 자동 0개·품절 | 신규 상품·조합은 ON_SALE |
| 공지 | 영어 완료 전 저장 차단 | 한국어 우선 게시, 영어 PENDING/FAILED도 성공 |
| 번역 | translationSource 저장 검증 | 저장 필드 제거. READY/PENDING/FAILED, 선택 언어 READY만 공개 |
| 공연 안내 | 실시간 performance-alert | 상시 prohibited-items 목록·안내 |
| 갱신 | 5초 보장 | 화면이 보이는 동안 15초 HTTP polling, 즉시 재조회와 30/60초 backoff |
| 관리자 인증 | 고정 목 Bearer token만 사용 | username 로그인, 15분 JWT access token, 7일 opaque refresh cookie rotation/revoke, 단일 ADMIN |

## 경로·필드 교체

- `GET /api/v2/admin/operating-hours`, `PUT /api/v2/admin/operating-hours/{operatingDay}`를 제거합니다.
- `PUT /api/v2/admin/goods/{goodsId}/colors/{colorId}/sizes/{sizeId}/inventory`는 끝 경로를 **availability**로 바꿉니다.
- 저장 본문은 `{ "status": "ON_SALE" }` 또는 `{ "status": "SOLD_OUT" }`입니다. quantity는 422입니다.
- 관리자 Inventory 응답은 Availability로 교체합니다. variants에 수량이 없습니다.
- Goods와 ProductInput에 `options: [{ colorId, sizeId }]`가 필수입니다. 실제 제공 조합만 등록하고 존재하지 않는 조합 저장은 404입니다.
- `GET /api/v2/performance-alert`는 `GET /api/v2/prohibited-items`로 교체합니다. 응답은 items(string[])·message(string 또는 null)이며 공연 전후에도 조회합니다.
- 공지 저장의 translationSource를 제거합니다. 한국어 READY·공백 아닌 제목/본문은 필수이며 외국어 PENDING/FAILED의 title/body는 null을 허용합니다.

상품명·가격·옵션 이름 변경 시 기존 ID와 options를 보존하면 기존 판매 상태를 유지합니다.
새 상품과 새 조합은 ON_SALE로 생성합니다. 색상·사이즈·제공 조합 삭제를 허용하며,
삭제한 조합의 판매 상태는 제거하고 남은 조합의 상태는 보존합니다. 이미지 개수·배치·업로드
기술과 옵션 없는 상품 입력 방식은 합의 대기이며, 이미지가 없는 저장은
409 IMAGE_CONFIGURATION_UNRESOLVED로 거절합니다.

영어 실패 시 미리보기는 canSave=true, 번역은 FAILED일 수 있으며 한국어 생성201/수정200은
성공합니다. 영어는 모든 공지의 준비 대상이나 한국어 저장의 선행 조건이 아닙니다.
선택 언어 READY만 공개하고 번역이 없으면 PENDING을 남깁니다. 현재 공개 locale은 한국어이며
완전 번역·승인이 끝난 언어만 공개 목록에 추가합니다. 템플릿 원문을 게시 전에
바꾸면 이전 번역을 그대로 READY로 보내지 않습니다. 미리보기 source는 현재 입력과
비교해 늦은 응답을 폐기하는 UI용입니다. 게시 후 상세 재번역 절차는 미정입니다.

## 화면 데이터와 출처

[화면 데이터 표](SCREEN-DATA.md)는 26개 화면·유효 데이터177개와 제외 이력10개를,
[화면 상태](SCREEN-STATES.md)는 조회·빈 상태·오류·프런트 책임을 연결합니다.
지도 혼잡도4개·운영 시간 편집5개·수량1개를 제외하고 실제 조합2개·고정 안내 목록1개를 추가했습니다.
공지 이미지 D04는 이전 버전에서 이미 폐기되어 원문 기록에만 남습니다.

[source-screen-requirements.json](source-screen-requirements.json)과
[source-admin-requirements.md](source-admin-requirements.md)는 비교용 과거 원본입니다.
원격 Google Sheets `화면 요구사항 정의 · 전체 화면 통합 작업`도 관련 셀만 갱신했습니다.
02 데이터와 책임의 상품 이미지·색상·사이즈 및 지도 위치 식별, 03 동작 정의의 혼잡도·상품
등록/수정, 04 상태 정의의 상품 입력·저장 실패, 05 조회 규칙의 동일 상태·상품 삭제,
07 검토의 동일 상태 행을 수정했습니다. 원격 값의 전체 비교·복원 결과와 검증 한계는
[검증 기록](VERIFICATION.md)에 기재했습니다.
screen-coverage.json의 legacySource는 과거 질문·필수 표시 기록으로 현재 결정에 사용하지 않습니다.
