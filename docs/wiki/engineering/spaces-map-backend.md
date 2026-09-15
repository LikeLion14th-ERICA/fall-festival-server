# 부스·지도 공개 카탈로그 백엔드

[위키 홈](../README.md) · 읽는 때: 부스&마켓·전체/구역 지도·티켓 위치 연결의 DB/API 구현

## 목적과 현재 기준

이 문서는 `origin/main`의 `1247890`(2026-09-15)과 API v2 draft.3을 기준으로 한다.
V2~V6은 `Festival`·`FestivalRevision`과 published revision 1건을 만들었지만, `Space`,
`Place`, 지도 자산·핀 테이블과 공개 조회 API는 아직 없다. V3의 `ticket_guide`는 map target
네 필드를 독립 nullable 값으로 저장하므로 실제 핀을 만들 때 참조 무결성을 추가해야 한다.

첫 배포 단위는 작은 읽기 전용 카탈로그다. published revision을 시작 시 검증·적재한 불변
snapshot에서 다음 공개 GET을 제공한다.

- `/api/v2/spaces`, `/api/v2/spaces/{spaceId}`
- `/api/v2/maps`, `/api/v2/maps/{mapId}`, `/api/v2/maps/{mapId}/pins?mapVersion=...`
- `/api/v2/places/{placeId}`
- 기존 `/api/v2/ticket-guide`의 검증된 `mapTarget`

실제 부스·지도·좌표·티켓존 자료는 아직 승인되지 않았다. Flyway에는 구조와 현재 빈
카탈로그만 넣고 API v2의 가상 목 자료를 운영 seed로 복사하지 않는다.

## 채택한 경계

| 항목 | 이번 구현 |
|---|---|
| 공개 범위 | 로그인 없는 읽기 API이며 관리자·import·publish HTTP 경로는 만들지 않는다. 승인 자료 반영은 개발자 실행 도구/DB 작업으로 별도 설계한다. |
| 게시 | `festival_revisions.state = published`인 정확히 한 revision만 읽는다. 프로세스 시작 시 완전성을 검증하고 snapshot을 교체 없이 보관한다. 단일 인스턴스에서는 검증된 게시 뒤 재시작으로 반영한다. |
| 미배포/장애 | snapshot이 없으면 `/readyz`는 준비되지 않음을 알리고 카탈로그 API는 503을 반환한다. 이전 또는 완전한 새 snapshot만 노출한다. |
| cache/전파 | Outbox, CAS, CDN purge, 요청별 shared JSON 캐시는 넣지 않는다. 이들은 다중 인스턴스 또는 CDN 도입이 확정될 때 다시 평가한다. 해시 경로 지도 이미지는 배포 환경에서 immutable cache header를 쓴다. |
| revision | `revision` query를 추가하지 않는다. `meta.revision`은 응답 비교용이며 늦은 응답 폐기는 클라이언트 책임이다. 409는 `mapVersion` 불일치에만 쓴다. |
| locale | 첫 공개 준비 언어는 `ko`뿐이다. 다른 locale은 fallback하지 않고 거절한다. 다른 언어 공개, 번역 누락 처리, locale별 정렬은 결정 36과 승인 번역을 받은 뒤 활성화한다. |

## 저장 모델과 불변식

모든 아래 행은 `festival_revision_id`로 묶인다. 표시 ID는 안정 문자열이고 표시명과
분리한다.

```text
FestivalRevision (published)
 ├─ Space ─ SpaceTranslation / SpaceSortOrder / SpaceEvent / SpaceMenu
 ├─ Place ─ PlaceTranslation ──(SPACE일 때)── Space
 ├─ Map ─ MapTranslation / MapAssetVersion(current version) ─ MapPin ─ Place | MapArea
 │                                                                  └ MapArea.targetMap(AREA)
 ├─ SpaceMapTarget ─ canonical PLACE MapPin
 └─ TicketGuide ─ optional canonical PLACE MapPin
```

- `Space`는 `BOOTH | PUB | FLEA_MARKET`이고, `SpaceSortOrder`는 `(revision, locale,
  space)`마다 하나의 순위를 저장한다. 한국어·영어 이름을 같은 정렬 값으로 재사용하지
  않는다.
- `Place.kind`는 `SPACE | FACILITY | LANDMARK`이며 `SPACE`일 때만 `space_id`를 가진다.
  `MapPin`의 `PLACE | AREA` target 종류와 합치지 않는다. 핀은 `place_id XOR area_id`,
  `x`/`y`는 0~1 범위로 DB에서 막는다.
- `MapArea.target_map_id`는 구역 이동의 목적 지도다. snapshot 검증기는 목적 지도가
  `AREA`인지 확인한다.
- `MapAssetVersion`은 이미지와 핀 좌표를 묶는다. `Map.current_version`은 존재하는 asset을
  참조한다. 이미지 URL·크기·좌표·핀 배치·target 연결이 바뀔 때만 `mapVersion`을 올린다.
  snapshot 검증기는 같은 festival/map/version이 published·archived revision과 이미지 URL·크기·
  `pinId → 좌표·실제 목적` 집합이 같은지도 확인한다. 이미지 alt·지도명·핀 label 같은 텍스트
  수정 또는 같은 자산의 재게시·rollback은 version을 바꾸지 않는다.
- `SpaceMapTarget`은 공간당 하나의 대표 `PLACE` 핀이다. 대표 핀이 속한 지도는
  `AREA` 종류여야 하며, `(revision, mapId, mapVersion, pinId, placeId)` 복합 FK와
  현재 지도 version FK로 다른 장소·AREA 핀·구버전 핀을 참조할 수 없다. 여러 지도에
  같은 장소 핀이 있어도 이 행이 화면의 대표 위치를 정한다. `TicketGuide.mapTarget`은
  같은 현재 `PLACE` 핀 연결을 사용하지만 티켓존은 전체 지도에 있을 수 있으므로
  `AREA` 지도 제한을 적용하지 않는다.
- 공개 API v2로 내보내는 `Meta.festivalId`, `Space.id`, `Map.id`, `Place.id`, `Pin.id`,
  `Pin.target`의 장소·지도 ID, `Place.spaceId`와 두 `MapTarget`의 ID는
  `^[a-z0-9][a-z0-9-]{0,63}$`를 따른다. `Map.version`, 핀 목록의 map version과
  `MapTarget.mapVersion`은 비어 있지 않아야 한다. `Space.image.url`과 현재 지도
  asset의 `Image.url`은 API의 `uri-reference` 계약을 따르며, snapshot 로드 경계에서
  URI 문법을 검증한다. scheme이나 host를 별도로 제한하지 않는다.
- `ticket_guide`의 네 target 값은 모두 null이거나 모두 존재해야 한다. 존재할 때 동일
  revision의 현재 지도 version에 속한 `PLACE` 핀과 복합 FK로 연결한다. 실제 티켓존과
  수령 부스의 동일성은 결정 11·26이 해결될 때까지 null로 둔다.

## snapshot과 API 동작

`CatalogSnapshotStore`는 repeatable-read 읽기 transaction에서 published revision을 한 번
선택한 뒤 한국어 번역, 정렬, 현재 map asset, 핀 target, canonical target, ticket 안내 본문과
ticket target을 같은 revision으로 읽고 검증한다. `TicketGuideController`는 요청마다
`ticket_guide`를 다시 읽지 않고 이 snapshot만 사용한다. 번역·정렬·현재 asset·target 중
하나라도 빠지거나 target이 현재 version과 다르면 snapshot을 거부한다. 빈 `spaces`/`maps`는 정상 snapshot이며 각각
`items: []`, `overviewId: null`을 반환한다. 지도가 하나라도 있으면 전체 지도는 정확히
하나여야 한다.

`/maps/{mapId}/pins`는 존재하지 않는 지도에 404, 현재 `Map.version`과 다른
`mapVersion`에 `409 MAP_VERSION_MISMATCH`, 맞는 version에 그 version의 핀만 반환한다.
`/spaces`의 `category`는 `ALL | BOOTH | PUB | FLEA_MARKET`만 받고 알 수 없거나 중복된
query는 400으로 거절한다. `mapTarget` 키는 미연결 때에도 null로 유지한다.

응답 meta에는 snapshot의 실제 festival ID와 revision을 넣는다. `X-Request-Id`는 길이와
문자 집합을 검증한 값만 반사하고, 나머지는 새 UUID로 바꾼 뒤 응답 헤더와 body meta에
같은 값을 쓴다.

## 위험과 대응

| 위험 | 대응 |
|---|---|
| 부분 게시로 이미지와 핀 좌표가 어긋남 | 현재 version FK, 복합 target FK, 시작 시 snapshot 검증, version mismatch 409을 함께 쓴다. |
| 다른 회차/미게시 자료 노출 | 모든 카탈로그 query를 published revision으로 제한하고 cross-revision FK를 둔다. |
| 번역을 한국어로 조용히 대체 | fallback하지 않는다. 준비되지 않은 locale은 공개하지 않고, 필수 한국어 행 누락은 snapshot 실패다. |
| 잘못된 request ID 반사 | 형식·길이 제한과 서버 생성 fallback으로 header injection·로그 오염을 막는다. |
| 작은 데이터에 과도한 캐시·전파 구조 | 초기 전체 자료를 메모리에 올리고 재시작 반영을 사용한다. 다중 인스턴스/실제 CDN 전에는 Outbox·purge를 만들지 않는다. |
| 피크 트래픽 | 카탈로그 조회는 DB 왕복 없이 immutable snapshot을 읽는다. 부하 시험은 동시 100명, 200명, 500명을 명시해 p95 오류율·응답 시간과 heap/GC를 기록한 뒤 실제 예상치가 나오면 갱신한다. |

## 단계와 완료 기준

1. **구조:** Flyway에 빈 카탈로그 테이블, revision·version·XOR·대표 target·ticket target
   제약을 추가하고 V6의 published revision에 기존 ticket guide를 연결한다.
2. **읽기 수직 절단:** JDBC snapshot loader, `/readyz`, spaces/maps/places controller와
   400·404·409·503 오류를 추가한다.
3. **안전 연결:** ticket 안내 본문과 원시 네 target 값을 같은 snapshot에서 읽고, target은
   검증된 canonical `PLACE` 핀으로만 반환하게 바꾸며, request ID 반사도 안전하게 만든다.
4. **운영 자료 투입 전 gate:** 실제 asset·좌표·문구·번역·티켓존 자료를 별도 검증 입력으로
   확인한다. 이 PR은 승인 자료를 넣거나 publish/import API를 만들지 않는다.

완료는 다음 모두를 만족할 때다.

- 빈 초기 published catalog가 부스·지도 GET에서 계약 형식의 정상 빈 응답을 낸다.
- 비어 있지 않은 테스트 fixture에서 locale별 순서, `PLACE XOR AREA`, 대표 space target,
  ticket 안내 본문·target의 같은 snapshot 정합성, revision·place·pin·현재 mapVersion 정합성과
  `Space → Place → Pin` 왕복 관계가 보장된다.
- 부분·고아·다른 revision·AREA·구버전 ticket/space target 및 같은 mapVersion의 다른
  이미지·좌표·AREA 목적지 삽입이 DB 제약 또는 snapshot 검증에서 거절된다. alt만 다른
  재게시는 같은 version으로 허용된다.
- 알려지지 않은 resource는 404, 잘못된 query는 400, pins의 구버전은 409,
  snapshot 미준비는 503이며 오류도 schema에 맞는 meta revision(최소 1)을 낸다.
- 운영 데이터가 없는 migration에는 가짜 부스·지도·핀·티켓존·번역이 없다.

## 검증 기준

| 계층 | 반드시 확인할 결과 |
|---|---|
| Flyway/PostgreSQL | 실제 migration에서 FK·CHECK·UNIQUE 제약을 검증한다. Testcontainers가 Docker 부재로 skip되면 성공과 구분해 기록한다. |
| Java 단위/HTTP | 정상·빈 목록, category, not-found, mapVersion 409, 503, request ID 형식, snapshot 기반 ticket 안내·target null/정상 경로를 검증한다. |
| API v2 | `contract-source.mjs`의 target 의미를 갱신하고 생성물 일치 및 mock contract check를 통과한다. Spring 응답은 JSON schema provider test로 별도 확인한다. |
| 회귀 | `mvnw.cmd --batch-mode --no-transfer-progress verify`, `api-v2`의 `npm run generate`, `npm run check`, `git diff --check`를 실행한다. |
| 성능 | 승인된 운영 자료를 넣기 전 합성 fixture에서 100/200/500 동시 조회를 실행하고 결과·환경·한계를 운영 문서에 남긴다. |

다중 인스턴스, CDN 도입, 실제 예상 동시 접속자, 다국어 공개, 핀 필터 표시명·대분류,
개발자 import/publish 실행 방식은 자료 또는 제품 결정이 나온 뒤 다음 설계 변경에서 다룬다.
