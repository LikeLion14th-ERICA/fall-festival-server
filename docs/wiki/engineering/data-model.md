# 데이터 모델·ERD 설계 검토

[위키 홈](../README.md) · 읽는 때: DB·Entity·Flyway migration·ERD·운영 콘텐츠 저장 구조를 설계하거나 변경할 때

## 목적과 적용 기준

이 문서는 최신 Product v5와 화면 연동용 API v2 draft.3을 바탕으로 한 **구현 전 논리
데이터 모델**이다. 현재 운영 DB schema·JPA Entity·Flyway migration이 아니며, 미정 운영값을
seed 데이터나 DDL 기본값으로 고정하지 않는다.

우선순위는 최신 사용자 결정, Product 문서, 승인 운영 자료, Figma 의도, 기존 API v1 순이다.
API v1은 기존 계약 기록이고, 새 화면 연동의 초안은 API v2다. 실제 서버 구현 전에 적용할
API 버전과 v1 호환 전략을 명시한다.

## 현재 구현 경계

- 루트 Spring Boot 프로젝트에는 JDBC·PostgreSQL·Flyway 기반의 축제 core(V2~V6), 티켓,
  스탬프와 혼잡도 조회가 있다. V6은 하나의 published FestivalRevision을 seed하지만 부스,
  장소, 지도 자산·핀 카탈로그 migration과 공개 조회는 아직 없다.
- 기본 profile은 DataSource와 Flyway 자동 구성을 끈다. `db` profile과 환경변수, 실제 migration을
  함께 준비한 뒤에만 운영 DB를 연결한다.
- `test/`의 Next.js·Spring Boot·PostgreSQL 코드는 PWA·스탬프·Push 실기기 검증 환경이다.
  그 schema나 사용자 상태를 운영 서비스 설계로 이식하지 않는다.

## 설계 문서 검토 결과

- 운영 대상은 루트 Spring Boot 서비스이고 `test/`는 별도 실기기 검증 환경이다. 두 영역의
  schema·사용자 상태·인증 결정을 섞지 않는다.
- API v1은 기존 계약 기록이며 새 화면의 목·연동 계약은 API v2 draft.3이다. 실제 서버 구현 전
  v1 호환 전략과 적용 버전을 결정한다.
- 공개 조회는 로그인 없이 유지한다. 관리자 쓰기는 서버 권한 검증과 감사 이력이 필수지만,
  계정·역할·세션·권한 회수 모델은 아직 결정되지 않았다.
- 이 문서의 하위 모델은 API·Product 문서를 구현 가능한 저장 구조로 해석한 **논리 후보**다.
  Festival/FestivalRevision의 물리 schema는 V2~V6에 존재하지만, 나머지 모델의 실제 컬럼·
  인덱스·삭제 방식은 migration 설계에서 확정한다.

## 논리 모델과 관계

아래 이름은 구현 시 사용할 후보 aggregate/table이며, 실제 컬럼·인덱스·삭제 방식은 migration
설계에서 확정한다. 모든 운영 콘텐츠는 `FestivalRevision`을 통해 축제 회차와 revision에
귀속하고, 표시명은 안정 ID와 분리한다. 날짜와 시각은 `Asia/Seoul` 및 offset을 보존한다.

| 영역 | 후보 모델 | 핵심 관계와 불변식 |
|---|---|---|
| 회차·게시·운영일 | `Festival`, `FestivalRevision`, `FestivalDay`, `CrowdingState` | Festival 1:N Revision, Revision 1:N 운영 콘텐츠, FestivalDay 1:0..1 CrowdingState. 공개 서비스는 승인된 `published` revision만 읽는다. 혼잡도는 운영자 저장 단계와 수정 시각을 보관하고 KST 자정에 전날 값을 이월하지 않는다. |
| 공지 | `Notice`, `NoticeTranslation`, `NoticeLink`, `NoticeTemplate`, `NoticeTemplateTranslation` | Notice 1:N Translation/Link. `(notice_id, locale)`은 고유하며 한국어는 READY·본문 필수다. 외국어 READY/PENDING/FAILED와 사용자 노출은 분리한다. 공지의 물리 삭제·soft delete·보존 기간은 아직 결정하지 않았다. |
| 굿즈 | `Goods`, `GoodsImage`, `GoodsColor`, `GoodsSize`, `GoodsOption`, `PaymentGuide`, `BankAccount` | 실제 제공 조합만 `GoodsOption`으로 만든다. `(goods_id, color_id, size_id)`는 고유하고 `availability`는 `ON_SALE` 또는 `SOLD_OUT`이며 `updated_at`을 남긴다. `allSoldOut`은 실제 조합이 하나 이상이고 모두 품절일 때의 파생값이다. 수량, 자동 품절, 입금 확인, 지급 완료는 저장하지 않는다. |
| 공연 | `Artist`, `ArtistLink`, `ArtistSong`, `Performance`, `PerformanceArtist`, `ProhibitedItem` | 공연과 출연진은 `PerformanceArtist`로 연결해 여러 출연진과 표시 순서를 표현한다. Performance는 FestivalDay에 속한다. 여러 무대 지원용 `Stage` 후보는 공통 API 요구이지만 현재 Performance 계약 필드가 없어 확정 게이트로 남긴다. |
| 부스·지도 | `Space`, `SpaceEvent`, `SpaceMenu`, `Place`, `Map`, `MapAssetVersion`, `MapPin`, `MapArea` | Space는 선택적으로 Place에 연결한다. MapAssetVersion 1:N MapPin으로 이미지와 좌표의 버전을 묶는다. MapPin은 `place_id` 또는 `area_id` 중 정확히 하나만 가진다. Place의 종류와 핀 target 종류를 같은 enum으로 합치지 않는다. |
| 안내 설정 | `TicketGuide`, `StampGuide`, `StampReward`, `FestivalLink`, `BankAccount` | 축제/운영일에 귀속한 안내 콘텐츠만 저장한다. 티켓 주문·입금·팔찌 지급, 스탬프 참여 누적·중복 차단·상품 재고는 현재 제품 범위가 아니다. 수령 인증 코드는 서버 비밀 설정에서만 검증하고 콘텐츠·사용자 이력 모델로 저장하지 않는다. 공식 채널·웰컴 데이는 검증된 외부 HTTPS 링크만 둔다. |

관리자 쓰기는 서버 권한 검증과 감사 이력이 필요하다. 다만 계정·역할·세션·권한 회수 모델은
아직 확정되지 않았으므로 `AdminAuditEvent`의 actor 참조와 보관 정책은 인증 결정과 함께 설계한다.
비밀값과 개인정보는 콘텐츠 테이블, API 응답, 로그에 저장하지 않는다.

## ERD

상세 도식과 재생성용 JSON은 [ERD 산출물 안내](../../design/README.md)에 모아 두었다.

- [게시·운영일 ERD](../../design/festival-publication-erd.html) · [원본 JSON](../../design/festival-publication-erd.json)
- [굿즈 ERD](../../design/festival-goods-erd.html) · [원본 JSON](../../design/festival-goods-erd.json)
- [공연·출연진 ERD](../../design/festival-program-erd.html) · [원본 JSON](../../design/festival-program-erd.json)
- [부스·지도 ERD](../../design/festival-map-erd.html) · [원본 JSON](../../design/festival-map-erd.json)
- [티켓·스탬프·외부 안내 ERD](../../design/festival-guide-erd.html) · [원본 JSON](../../design/festival-guide-erd.json)

도식의 간결성을 위해 일부 FK는 엔터티 sublabel에 함께 적었고, 표의 관계·고유 제약·XOR 제약이
저장 모델의 기준이다. 예를 들어 `GoodsImage.color_id?`, `PaymentGuide.goods_id`,
`MapArea.target_map_id`와 `MapPin.place_id XOR area_id`는 해당 도식의 관계와 표를 함께 따른다.

이 ERD의 고정 Viewer UI는 한국어 locale을 지원하지 않아 영어로 표시될 수 있다. 도식의
제목·노드·관계·카드는 한국어로 작성했다.

## 구현 전 결정 게이트

다음은 DDL 또는 API 동시성에 영향을 주므로 결정 전에는 기본값으로 추측하지 않는다.

1. 관리자 인증·역할·세션·감사 이력과 긴급 권한 회수
2. 콘텐츠 revision 저장 단위, 캐시 무효화, 새로고침 없는 반영 방식과 최대 지연
3. 공지 원문 변경 뒤 번역 무효화·재검토, 번역 엔진과 게시 이력
4. 동일 혼잡도 재선택의 저장·수정 시각, 운영 시간 밖 저장 정책
5. 굿즈 신규 옵션 최초 상태, 이미지 업로드 방식, 옵션 삭제 정책과 표시 순서
6. 여러 무대의 식별자·지도 연결과 API v2 `Performance` stage field 추가 여부
7. 지도 좌표·필터 분류, map version과 핀 target의 XOR 무결성 방식
8. 실제 일정·계좌·가격·지도 자산·공식 번역 등 운영 자료

상세 질문은 [Product 결정 대기](../product/decisions.md)와 [API v2 결정 대기](../../../api-v2/DECISIONS.md)에서
추적한다.

## migration 착수 순서

1. 적용 API 버전과 v1 호환 전략, 관리자 인증 경계를 확정한다.
2. Festival·FestivalRevision과 FestivalDay, 공지·번역, 굿즈·실제 제공 조합부터 Flyway migration으로 만든다.
3. DB 제약으로 `(notice_id, locale)` 및 `(goods_id, color_id, size_id)` 중복을 막고, 수량·결제·사용자 참여 테이블을 추가하지 않는다.
4. MapAssetVersion과 MapPin의 버전·XOR 무결성, PerformanceArtist의 표시 순서를 설계한 뒤 나머지 콘텐츠 migration을 추가한다.
5. migration마다 rollback 또는 복구 방법, API 계약·예시·통합 테스트를 같은 변경에서 갱신한다.

## 검증

- 2026-09-14: 다섯 JSON 원본은 각각 Archify showcase 검증의 9개 artifact 검사와
  composition 오류 0·경고 0을 통과했다. 근거 링크는 각각 18·16·18·17·14개를 검증했다.
- 같은 날 생성된 다섯 HTML은 Chrome에서 1440×900, 1600×1000, 1920×1080,
  2048×1320 해상도의 light/dark 화면으로 확인했다. 가로·세로 overflow와 Viewer 겹침이 없고,
  1440×900 light 스크린샷을 시각 검토했다.
- 이 문서는 설계 검토용이며 migration·JPA Entity·운영 DB 연결을 변경하지 않았다.
