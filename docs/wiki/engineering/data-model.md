# 데이터 모델·ERD 설계 검토

[위키 홈](../README.md) · 읽는 때: DB·Entity·Flyway migration·ERD·운영 콘텐츠 저장 구조를 설계하거나 변경할 때

## 목적과 적용 기준

이 문서는 최신 Product v5와 화면 연동용 API v2를 바탕으로 한 논리 데이터 모델과
구현된 저장 경계를 함께 기록한다. 아래에 명시하지 않은 모델은 현재 운영 DB schema·JPA
Entity·Flyway migration이 아니며, 미정 운영값을 seed 데이터나 DDL 기본값으로 고정하지 않는다.

우선순위는 최신 사용자 결정, Product 문서, 승인 운영 자료, Figma 의도, 확정된 API v2 계약
순이다. 구현한 경로 외의 운영 전환 범위는 결정 기록으로 관리한다.

## 현재 구현 경계

- 루트 Spring Boot 프로젝트에는 JDBC·PostgreSQL·Flyway 기반의 축제 core(V2~V11), 티켓,
  스탬프·혼잡도와 published revision의 부스·장소·지도 카탈로그 공개 조회가 있다. V8은
  revision-scoped 공간·번역·locale별 정렬·장소·지도 자산/핀·대표 target 구조를, V10은 지도
  filter group을, V11은 revision-scoped guide·게시 CLI·카탈로그 감사 이력을, V12는
  운영 관리자 감사 이력을, V13은 revision-scoped 공연·출연진·번역·링크·대표곡·타임테이블
  설정·반입 금지 안내의 물리 schema를 만든다. 승인된 운영 부스·지도·좌표·티켓존·공연 자료는
  seed하지 않는다. 부스·지도 상세 물리 모델과 완료 기준은
  [부스·지도 공개 카탈로그](spaces-map-backend.md)를 따른다.
- 기본 profile은 DataSource와 Flyway 자동 구성을 끈다. `db` profile과 환경변수, 실제 migration을
  함께 준비한 뒤에만 운영 DB를 연결한다.
- `test/`의 Next.js·Spring Boot·PostgreSQL 코드는 PWA·스탬프·Push 실기기 검증 환경이다.
  그 schema나 사용자 상태를 운영 서비스 설계로 이식하지 않는다.

## 설계 문서 검토 결과

- 운영 대상은 루트 Spring Boot 서비스이고 `test/`는 별도 실기기 검증 환경이다. 두 영역의
  schema·사용자 상태·인증 결정을 섞지 않는다.
- API v2는 제품의 유일한 계약이다. 공개 카탈로그와 관리자 인증은 서버에 구현됐고,
  나머지 화면 연동의 운영 전환 범위는 계속 결정한다.
- 공개 조회는 로그인 없이 유지한다. 관리자 쓰기는 username 로그인, 단일 `ADMIN`, 짧은 JWT와
  refresh cookie 회전·회수 모델로 서버에서 검증한다. 세부 RBAC는 현재 Product 범위가
  아니다. 운영 관리자 감사 이력은 1년 보관하며 cleanup target은 기본 dry-run·scheduler
  비활성 상태에서 전용 datasource와 역할을 지정한 경우에만 삭제를 수행한다. 설정은
  [데이터 정리](cleanup.md)를 따른다.
- 이 문서의 하위 모델은 API·Product 문서를 구현 가능한 저장 구조로 해석한 **논리 후보**다.
  `Space`·`Place`·`Map`·`MapAssetVersion`·`MapPin`·`MapArea`·filter group·canonical map
  target과 revision-scoped ticket/stamp guide·catalog audit의 현재 물리 schema는 V8~V11에,
  공연 카탈로그의 현재 물리 schema는 V13에 있다. 나머지 모델의 컬럼·인덱스·삭제 방식은
  migration 설계에서 확정한다.

## 논리 모델과 관계

아래 이름은 구현 시 사용할 후보 aggregate/table이며, 실제 컬럼·인덱스·삭제 방식은 migration
설계에서 확정한다. 모든 운영 콘텐츠는 `FestivalRevision`을 통해 축제 회차와 revision에
귀속하고, 표시명은 안정 ID와 분리한다. 날짜와 시각은 `Asia/Seoul` 및 offset을 보존한다.

| 영역 | 후보 모델 | 핵심 관계와 불변식 |
|---|---|---|
| 회차·게시·운영일 | `Festival`, `FestivalRevision`, `FestivalDay`, `CatalogRevisionAudit`, `CrowdingState` | Festival 1:N Revision, Revision 1:N 운영 콘텐츠, FestivalDay 1:0..1 CrowdingState. 공개 서비스는 정확히 하나의 `published` revision만 읽는다. 개발자 CLI는 complete draft만 import·validate·publish하며 archived revision을 새 revision으로 rollback한다. 혼잡도는 revision 밖의 운영 상태이고, 운영 시간 밖 저장을 허용하며 KST 자정에 전날 값을 이월하지 않는다. 같은 상태 저장은 수정 시각을 바꾸지 않는다. |
| 공지 | `Notice`, `NoticeTranslation`, `NoticeLink`, `NoticeTemplate`, `NoticeTemplateTranslation` | Notice 1:N Translation/Link. `(notice_id, locale)`은 고유하며 한국어는 READY·본문 필수다. 외국어 READY/PENDING/FAILED와 사용자 노출은 분리한다. 공지의 물리 삭제·soft delete·보존 기간은 아직 결정하지 않았다. |
| 굿즈 | `Goods`, `GoodsImage`, `GoodsColor`, `GoodsSize`, `GoodsOption`, `PaymentGuide`, `BankAccount` | 실제 제공 조합만 `GoodsOption`으로 만든다. `(goods_id, color_id, size_id)`는 고유하고 `availability`는 `ON_SALE` 또는 `SOLD_OUT`이며 `updated_at`을 남긴다. 신규 상품·조합은 `ON_SALE`로 시작한다. 색상·사이즈·조합 삭제 시 그 상태를 제거하고 남은 조합 상태를 보존한다. `allSoldOut`은 실제 조합이 하나 이상이고 모두 품절일 때의 파생값이다. 수량, 자동 품절, 입금 확인, 지급 완료는 저장하지 않는다. |
| 공연 | `Artist`, `ArtistTranslation`, `ArtistLink`, `ArtistLinkTranslation`, `ArtistSong`, `ArtistSongTranslation`, `Performance`, `PerformanceTranslation`, `PerformanceArtist`, `TimetableConfig`, `ProhibitedItem`, `ProhibitedItemTranslation`, `ProhibitedMessage` | V13 물리 schema다. Artist와 Performance는 revision-scoped 복합 키를 사용하고 `PerformanceArtist` N:M 관계로 여러 출연진과 공연별 표시 순서를 표현한다. Performance는 `(festival_revision_id, festival_date)` 복합 FK로 같은 revision의 FestivalDay에 속한다. 번역·링크·대표곡과 반입 금지 항목/문구는 별도 테이블이며 locale fallback을 저장 구조에서 만들지 않는다. 타임테이블 축은 revision별 설정이고 FestivalDay 운영 시간과 분리한다. 단일 무대만 사용하므로 `Stage` aggregate와 stage field는 없으며 운영 seed도 없다. |
| 부스·지도 | `Space`, `SpaceEvent`, `SpaceMenu`, `Place`, `Map`, `MapAssetVersion`, `MapPin`, `MapArea`, `MapPinFilterGroupTranslation` | Space는 선택적으로 Place에 연결한다. MapAssetVersion 1:N MapPin으로 이미지와 좌표의 버전을 묶는다. MapPin은 `place_id` 또는 `area_id` 중 정확히 하나만 가진다. `PLACE` 핀은 대분류 filter group 하나, `AREA` 핀은 null이며 항상 노출한다. Place의 종류와 핀 target 종류를 같은 enum으로 합치지 않는다. |
| 안내 설정 | `TicketGuideRevision`, `StampGuideRevision`, `StampReward`, `FestivalLink`, `BankAccount` | 축제 revision에 귀속한 안내 콘텐츠만 snapshot으로 읽는다. 티켓 수령 부스와 외부인 티켓존은 한 Place로 모델링한다. 티켓 주문·입금·팔찌 지급, 스탬프 참여 누적·중복 차단·상품 재고는 현재 제품 범위가 아니다. 수령 인증 코드는 서버 비밀 설정에서만 검증하고 콘텐츠·사용자 이력 모델로 저장하지 않는다. 공식 채널·웰컴 데이는 검증된 외부 HTTPS 링크만 둔다. |

관리자 쓰기는 서버 권한 검증과 감사 이력이 필요하며 현재 관리자 인증은 구현되어 있다.
`CatalogRevisionAudit`는 개발자 CLI의 revision lifecycle을 actor 문자열로 기록하고,
`AdminAuditEvent`는 인증된 `admin_accounts.id`를 actor로 삼아 운영 콘텐츠 HTTP write를
기록한다. 두 모델은 통합하지 않고, 변경과 같은 transaction에 append-only로 저장한다.
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

1. 세부 관리자 역할과 긴급 권한 회수 운영, 관리자 감사 이력 1년 보관의 자동 삭제·archive 방식
2. 공지 원문 변경 뒤 번역 무효화·재검토, 번역 엔진과 게시 이력
3. 굿즈 이미지 업로드 방식, 옵션 없는 상품 입력 방식과 표시 순서
4. 실제 일정·계좌·가격·지도 자산·공식 번역 등 운영 자료

상세 질문은 [Product 결정 대기](../product/decisions.md)와 [API v2 결정 대기](../../../api-v2/DECISIONS.md)에서
추적한다.

## migration 착수 순서

1. 승인 콘텐츠는 개발자 CLI manifest로 import하고, 공개 전 번역·locale별 정렬·asset·핀·filter group·target·guide 검증을 통과시킨다.
2. V13 공연 카탈로그는 개발자 CLI manifest의 완전 revision import·validate·publish·rollback에 포함한다. 내부 DB 검증기는 저장된 공연 revision을 게시 직전에 다시 검증한다. 공개 라인업·타임테이블 조회는 아직 구현하지 않으며 별도 제품·계약 작업 뒤 활성화한다.
3. 공지·번역과 굿즈·실제 제공 조합은 해당 제품·계약 결정이 난 뒤 Flyway migration으로 만든다. DB 제약으로 `(notice_id, locale)` 및 `(goods_id, color_id, size_id)` 중복을 막고, 수량·결제·사용자 참여 테이블을 추가하지 않는다.
4. migration마다 운영 DB 보존, rollback 또는 복구 방법, API 계약·예시·통합 테스트를 같은 변경에서 갱신한다.

## 검증

- 2026-09-14: 다섯 JSON 원본은 각각 Archify showcase 검증의 9개 artifact 검사와
  composition 오류 0·경고 0을 통과했다. 근거 링크는 각각 18·16·18·17·14개를 검증했다.
- 같은 날 생성된 다섯 HTML은 Chrome에서 1440×900, 1600×1000, 1920×1080,
  2048×1320 해상도의 light/dark 화면으로 확인했다. 가로·세로 overflow와 Viewer 겹침이 없고,
  1440×900 light 스크린샷을 시각 검토했다.
- 부스·지도 실제 구현은 V8~V11과 [부스·지도 공개 카탈로그](spaces-map-backend.md)에 기록한다.
  공연 물리 schema는 V13과 PostgreSQL migration 통합 테스트로 검증하며 운영 자료는 포함하지
  않는다. 이 문서의 나머지 모델은 계속 설계 검토 대상이며 운영 자료·DB 연결 값은 포함하지 않는다.
