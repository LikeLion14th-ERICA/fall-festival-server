# API v2 draft.3 변경·마이그레이션

main `21eb76dacd78b3ad79ed4d9589dd341fbc25b883`(PR #6 병합 완료) Product Context와 2026-09-14 사용자 결정에 따라
[관리자 v5](../docs/wiki/product/admin/README.md)를 기준으로 통일했습니다.
아래는 draft.2 소비자가 변경할 계약입니다. API v2가 제품 계약이며 Spring Boot 구현·운영 배포 범위는 경로별로 함께 추적합니다.
표의 draft.3 열은 2026-09-14 기록을 보존합니다. 2026-09-28에 확정한 운영 시간 관리 확장은
아래 별도 절에서 현재 계약으로 연결합니다.

| 영역 | draft.2 | draft.3 |
|---|---|---|
| 혼잡도 | 홈·지도 표시 | 홈만 표시, 지도 핀·색상 연동 없음 |
| 운영 시간 | 관리자 GET/PUT | 개발자 등록, 관리자 시간 편집 경로 제거 |
| 날짜 | 운영일 이력 | KST 00:00 전날 상태·수정 시각 초기화 |
| 관리자 혼잡도 저장 | 응답 본문 성공 | `If-Match`와 `Idempotency-Key` 필수, 성공·재시도는 204, 같은 단계는 시각·감사 이력 유지 |
| 혼잡도 일정·상태 | 고정 운영 시각·revision 귀속 후보 | published FestivalDay 일정, `(festival_id, operating_date)` revision-independent 상태, meta revision 0 |
| 굿즈 상태 | 정수 수량에서 자동 판정 | 실제 조합별 ON_SALE/SOLD_OUT 직접 저장. 관리자도 수량 미관리 |
| 상품 옵션 | 전체 색상×사이즈 곱집합 | Goods.options에 등록된 실제 조합만 |
| 신규 옵션 | 자동 0개·품절 | 신규 상품·조합은 ON_SALE |
| 공지 | 영어 완료 전 저장 차단 | 한국어 우선 게시, 영어 PENDING/FAILED도 성공 |
| 번역 | translationSource 저장 검증 | 저장 필드 제거. READY/PENDING/FAILED, 선택 언어 READY만 공개 |
| 공연 안내 | 실시간 performance-alert | 상시 prohibited-items 목록·안내 |
| 갱신 | 5초 보장 | 화면이 보이는 동안 15초 HTTP polling, 즉시 재조회와 30/60초 backoff |
| 관리자 인증 | 고정 목 Bearer token만 사용 | username 로그인, 15분 JWT access token, 7일 opaque refresh cookie rotation/revoke, 단일 ADMIN |

## 2026-09-28 재학생존 운영 시간 관리 확장

기존 draft.3의 개발자 등록·관리자 편집 제외 규칙을 대체합니다. 새 기능 `ADM-CROWD-002`는
각 published FestivalDay의 운영 시간을 날짜별로 읽고 저장합니다. 목록 GET은
`/api/v2/admin/crowding/operating-hours`, 상세 GET과 저장 PUT은
`/api/v2/admin/crowding/operating-hours/{operatingDay}`입니다.

- 응답은 `operatingDay`, `opensAt`, `closesAt`, `updatedAt`을 제공하며 목록은 날짜 오름차순
  `items`입니다. `updatedAt: null`은 카탈로그 초기값을 쓰고 있다는 뜻입니다.
- PUT 본문은 `opensAt`, `closesAt`이며 RFC3339로 보냅니다. KST로 정규화하고 분 단위만
  허용합니다. 시작은 당일 안, 종료는 시작 뒤부터 다음 날 `00:00`까지이며 구간 끝은 제외합니다.
- 상세 GET strong `ETag`를 `If-Match`에 넣고 `Idempotency-Key`도 전송합니다. 헤더 누락은
  `428`, stale ETag는 `409 EDIT_CONFLICT`, 성공과 완료된 같은 요청 재시도는 `204`입니다.
- 관리자 `ADMIN` 인증·요청 제한·`no-store`를 적용하고 `meta.revision`은 `0`입니다. 이전
  global `/admin/operating-hours` 경로는 복구하지 않습니다.
- 저장값은 카탈로그 revision과 독립적입니다. 카탈로그 재게시·rollback·서버 재시작 후에도
  유지하며, 게시에서 빠진 날짜는 조회에서 제외하고 설정 행은 보존합니다. 날짜가 다시 게시되면
  기존 설정을 적용합니다. 새로운 날짜는 카탈로그 값을 초기값으로 사용합니다.
- 첫 저장은 카탈로그와 같은 시간이어도 관리자 설정을 확정하고 감사 기록을 만듭니다. 이후
  같은 값을 저장하면 수정 시각·감사 기록을 추가하지 않습니다. 시간 변경은 당일 혼잡도 단계와
  혼잡도 수정 시각을 바꾸지 않습니다.
- 입력 중 값은 조회 갱신으로 덮지 않습니다. 저장 후 목록·상세·현재 혼잡도를 다시 읽고, 충돌이면
  입력을 보존합니다. 실제 관리자 UI 코드는 프런트 담당자의 구현·검증 범위입니다.

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

[화면 데이터 표](SCREEN-DATA.md)와 [화면 상태](SCREEN-STATES.md)는 계약 원본에서 생성합니다.
draft.3 시점에는 지도 혼잡도 4개·운영 시간 편집 5개·수량 1개를 제외하고 실제 조합 2개와
고정 안내 목록 1개를 추가했습니다. 2026-09-28 확장으로 운영 시간 화면과 다섯 필드를 다시
활성화했습니다. 현재 생성 결과는 27개 화면, 189개 데이터 매핑(추적 183개·제외 6개)입니다.
공지 이미지 D04는 이전 버전에서 이미 폐기되어 원문 기록에만 남습니다.

[source-screen-requirements.json](source-screen-requirements.json)과
[source-admin-requirements.md](source-admin-requirements.md)는 비교용 과거 원본입니다.
원격 Google Sheets `화면 요구사항 정의 · 전체 화면 통합 작업`도 관련 셀만 갱신했습니다.
02 데이터와 책임의 상품 이미지·색상·사이즈 및 지도 위치 식별, 03 동작 정의의 혼잡도·상품
등록/수정, 04 상태 정의의 상품 입력·저장 실패, 05 조회 규칙의 동일 상태·상품 삭제,
07 검토의 동일 상태 행을 수정했습니다. 원격 값의 전체 비교·복원 결과와 검증 한계는
[검증 기록](VERIFICATION.md)에 기재했습니다.
screen-coverage.json의 legacySource는 과거 질문·필수 표시 기록으로 현재 결정에 사용하지 않습니다.
