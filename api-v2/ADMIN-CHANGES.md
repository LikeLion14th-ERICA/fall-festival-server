# 새 관리자 명세 반영 · 2026-09-13

API v2 **2.0.0-draft.2**. 근거는 [사용자가 제공한 관리자 기능명세서](source-admin-requirements.md)이며, 화면 데이터 표의 기존 관리자 요구보다 우선합니다. 운영 자료와 기술 선택은 별도로 남겼습니다.

| 영역 | 이전 계약 | 변경 계약 |
|---|---|---|
| 운영 시간 | 고정 목 시간·별도 관리 명세 대기 | ADM-CROWD-002 추가. 날짜별 HH:mm 시작·종료, 기본 13:00~22:00, 종료>시작 |
| 혼잡도 | 가상 overnight 시나리오 | 시간 저장 후 즉시 재판정. 당일 이력 복원·다른 날짜 미이월. 자정 초과 시나리오 제거 |
| 재고 | 상품×사이즈 ON_SALE/SOLD_OUT 직접 저장 | 상품×색상×사이즈의 0 이상 정수 수량 저장, 서버가 상태 계산 |
| 공개 재고 | 사이즈 공통 상태 | 색상×사이즈 상태와 전체 품절만 제공. 정확한 수량은 관리자 전용 |
| 상품 | 별도 상품 관리 없음 | 목록·신규 등록·수정, 복수/색상별 이미지와 옵션 관리 |
| 옵션 | 재고 생성 연동 없음 | 신규 조합은 0개, 기존 옵션 ID를 유지한 이름 수정은 재고 보존. 상품·옵션 삭제 없음 |
| 공지 입력 | 독립 명세 대기·image 필드 존재 | 한국어 필수, 링크 입력·검증, 이미지 필드 제거 |
| 번역 | READY/PENDING 수동 저장 초안 | 저장 전 자동 번역 미리보기·직접 수정, 영어 완료 필수. 제공 중 중·일은 실패 저장 가능 |
| 원문 수정 | 번역 갱신 확인 없음 | 현재 원문과 translationSource 일치 검증. 번역 재생성/검토 완료를 표현하는 기술 초안 |
| 시간·노출 | 기존 규칙 유지 | createdAt 불변·updatedAt 갱신, 영어 저장 차단·언어별 READY 필터 검증 강화 |

## 경로 변경과 프런트 마이그레이션

| 변경 | 요청 |
|---|---|
| 추가 | `GET /api/v2/admin/operating-hours` |
| 추가 | `PUT /api/v2/admin/operating-hours/{operatingDay}` — `{ "opensAt": "13:00", "closesAt": "22:00" }` |
| 제거 | `PUT /api/v2/admin/goods/{goodsId}/sizes/{sizeId}` |
| 대체 | `PUT /api/v2/admin/goods/{goodsId}/colors/{colorId}/sizes/{sizeId}/inventory` — `{ "quantity": 5 }` |
| 추가 | `GET /api/v2/admin/products`, `POST /api/v2/admin/products` |
| 추가 | `GET /api/v2/admin/products/{goodsId}`, `PUT /api/v2/admin/products/{goodsId}` |
| 추가 | `POST /api/v2/admin/notice-translations` — `{ "title": "한국어 제목", "body": "한국어 본문" }`, 200 반환 |

- 공개 `Availability.sizes`는 `variants`로 교체합니다. 각 항목은 `colorId`, `colorName`, `sizeId`, `sizeLabel`, `status`입니다. 관리자 `Inventory.variants`에만 `quantity`가 추가됩니다.
- `GET /admin/goods`는 상품 재고 목록입니다. 상품 편집 목록은 `GET /admin/products`를 사용합니다.
- 상품의 색상 목록은 `colors`, 복수 이미지는 `images`를 사용합니다. `image`는 대표 이미지이며 없으면 null입니다. 기존 `colorImages`는 호환용 이미지 목록으로 유지합니다.
- 상품 PUT에는 기존 옵션을 모두 포함합니다. 기존 ID 누락은 옵션 삭제로 간주하여 409입니다. 옵션 이름 변경은 ID를 유지하며 새 옵션은 새 ID를 사용합니다. 옵션 ID 발급·이미지 업로드의 실제 방식은 기술 합의 대기입니다.
- 공지 요청·응답의 `image`를 제거하고 `translationSource: {title, body}`를 추가합니다. 한국어 현재 값과 다르면 `STALE_TRANSLATION_SOURCE`(422), 영어가 미완료면 `ENGLISH_TRANSLATION_REQUIRED`(422)입니다. 공지 원문 변경 뒤 이전 번역 응답을 적용하지 마세요.
- 번역 미리보기는 공지를 저장하지 않습니다. 응답 `source`가 현재 편집 중인 원문과 같은 경우에만 번역을 적용합니다. 직접 수정·검토한 번역도 현재 원문과 함께 저장할 수 있습니다. 이 필드는 번역 의미의 정확성을 자동 보증하지 않습니다.
- 미리보기의 영어 실패는 200과 `canSave:false`, 해당 번역의 `PENDING`으로 표현합니다. 실제 저장 요청은 422로 거절합니다. 번역 서비스 전체 오류는 503입니다.
- `GET /config?__scenario=all-languages`는 **같은 목 세션**에 중·일 언어를 추가하는 테스트 제어입니다. `partial-translation`으로 제공 중 중·일 실패를 재현할 수 있습니다. 초기화하면 ko/en으로 돌아갑니다. 실제 서비스 언어 활성화를 의미하지 않습니다.
- 운영 상태는 `Crowding.operatingStatus`로 확인합니다. 시작 시각은 포함, 종료 시각은 제외합니다. 운영 시간 변경 시각과 혼잡도 수정 시각은 서로 독립입니다.

## 화면 데이터 표 변경

기존 8개 탭을 유지하고 `ADM-CROWD-HOURS`, `ADM-GOODS-PRODUCT-LIST`, `ADM-GOODS-PRODUCT-EDIT`를 추가했습니다. 총 27개 화면·183개 활성 데이터입니다. `ADM-NOTICE-EDIT-D04`는 폐기한 이미지 계약의 이력으로 남기고 활성 API 매핑에서 제외했습니다. 링크는 새 D07, 번역 완료 상태는 D08로 추가하여 기존 ID 의미를 바꾸지 않았습니다.

이미지 업로드 제외와 재고 저장 방식 질문은 결정 완료로 기록했습니다. 운영 시간·공지 작성·번역 관련 질문은 해결된 내용을 결과에 남기고 남은 질문만 좁혔습니다. 실제 운영 수량·담당자·이미지 제한과 관리자 인증은 여전히 확인이 필요합니다.

이번 수정은 기존 v1 경로와 실제 Spring Boot 구현을 변경하지 않습니다. draft.1 소비자는 위 필드·경로 변경을 적용한 뒤 목 서버를 재시작해야 합니다.
