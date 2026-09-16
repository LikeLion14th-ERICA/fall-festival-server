# 부스·지도 공개 카탈로그 백엔드

[위키 홈](../README.md) · 읽는 때: 부스&마켓·전체/구역 지도·티켓 위치 연결의 DB/API 구현

## 목적과 현재 기준

이 문서는 API v2와 V8~V11 카탈로그 구현을 기준으로 한다. V2~V6의 `Festival`·
`FestivalRevision`과 published revision 1건 위에 V8이 `Space`, `Place`, 지도 자산·핀 테이블과
공개 조회 API를 추가했다. V10은 핀 대분류와 표시명 번역을, V11은 revision-scoped 티켓·스탬프
안내와 개발자 전용 publish CLI·감사 이력을 추가했다. V3의 `ticket_guide`가 독립 nullable로
두었던 map target 네 필드는 V8에서 전부 null 또는 전부 존재하도록 하고, 실제 현재 `PLACE`
핀을 참조하게 만들었다.

현재 배포 단위는 작은 읽기 전용 카탈로그다. published revision을 시작 시 검증·적재한 불변
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
| 공개 범위 | 로그인 없는 읽기 API이며 관리자·import·publish HTTP 경로는 만들지 않는다. 개발자 전용 `CatalogCliApplication`이 로컬 manifest의 `import → validate → publish / rollback`만 수행한다. |
| 게시 | `festival_revisions.state = published`인 정확히 한 revision만 읽는다. 프로세스 시작 시 완전성을 검증하고 snapshot을 교체 없이 보관한다. 단일 인스턴스에서는 검증된 게시 뒤 재시작으로 반영한다. |
| 미배포/장애 | snapshot이 없으면 `/readyz`는 준비되지 않음을 알리고 카탈로그 API는 503을 반환한다. 이전 또는 완전한 새 snapshot만 노출한다. |
| cache/전파 | Outbox, CAS, CDN purge, 요청별 shared JSON 캐시는 넣지 않는다. 이들은 다중 인스턴스 또는 CDN 도입이 확정될 때 다시 평가한다. 해시 경로 지도 이미지는 배포 환경에서 immutable cache header를 쓴다. |
| revision | `revision` query를 추가하지 않는다. `meta.revision`은 응답 비교용이며 늦은 응답 폐기는 클라이언트 책임이다. 409는 `mapVersion` 불일치에만 쓴다. |
| locale | 현재 공개 언어는 `ko`뿐이다. 다른 locale은 fallback하지 않고 거절한다. 새 언어는 공간·장소·지도·핀·filter label·locale별 정렬이 모두 완결되고 승인된 경우에만 공개한다. |
| migration | main의 관리자 인증은 V7·V9, 카탈로그는 V8~V11이다. V10은 기존 핀의 분류를 추정해 채우지 않고 null로 보존한다. V11은 legacy guide 행을 삭제·재작성하지 않고 revision table에 복사하며, 날짜 없는 legacy ticket 시간값은 새 snapshot에서 명시적 미설정으로 정규화한다. 운영 DB의 Flyway history와 기존 데이터는 고치거나 재생성하지 않는다. |

## 저장 모델과 불변식

모든 아래 행은 `festival_revision_id`로 묶인다. 표시 ID는 안정 문자열이고 표시명과
분리한다.

```text
FestivalRevision (published)
 ├─ Space ─ SpaceTranslation / SpaceSortOrder / SpaceEvent / SpaceMenu
 ├─ Place ─ PlaceTranslation ──(SPACE일 때)── Space
 ├─ Map ─ MapTranslation / MapAssetVersion(current version) ─ MapPin ─ Place | MapArea
 │                                                                  └ MapArea.targetMap(AREA)
 │                                                                  └ MapPinFilterGroupTranslation
 ├─ SpaceMapTarget ─ canonical PLACE MapPin
 ├─ TicketGuideRevision ─ optional canonical PLACE MapPin
 ├─ StampGuideRevision
 ├─ FestivalDay
 └─ CatalogRevisionAudit
```

- `Space`는 `BOOTH | PUB | FLEA_MARKET`이고, `SpaceSortOrder`는 `(revision, locale,
  space)`마다 하나의 순위를 저장한다. 한국어·영어 이름을 같은 정렬 값으로 재사용하지
  않는다.
- `Place.kind`는 `SPACE | FACILITY | LANDMARK`이며 `SPACE`일 때만 `space_id`를 가진다.
  `MapPin`의 `PLACE | AREA` target 종류와 합치지 않는다. 핀은 `place_id XOR area_id`,
  `x`/`y`는 0~1 범위로 DB에서 막는다.
- `MapArea.target_map_id`는 구역 이동의 목적 지도다. snapshot 검증기는 목적 지도가
  `AREA`인지 확인한다.
- `MapPin.filter_group`은 `STUDENT_COUNCIL | EXPERIENCE | CONVENIENCE |
  FOOD_AND_BEVERAGE | PERFORMANCE | null`이다. `PLACE` 핀은 유효한 대분류 하나를 갖고,
  `AREA` 핀은 null이다. 응답 `filters`는 해당 지도 version의 실제 `PLACE` 그룹만 고정 순서로
  내보내며 `AREA` 핀은 어떤 선택 필터에서도 계속 표시한다. 기존 V8 revision이 모든 그룹 null인
  경우에는 읽을 수 있지만, 그룹을 하나라도 넣은 새 revision은 모든 `PLACE` 핀의 group과
  한국어 group label을 완결해야 한다.
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
- `Space.contact`가 있으면 `Link.url`은 API v2의 `format: uri`와 `^https://` 조건을
  모두 만족해야 한다. snapshot 로드 경계에서 URI 문법과 HTTPS 접두사를 검증하며,
  `https://bad url` 같은 값은 `CatalogIntegrityException`으로 공개 전에 거절한다.
- 공개 API v2로 내보내는 `Meta.festivalId`, `Space.id`, `Map.id`, `Place.id`, `Pin.id`,
  `Pin.target`의 장소·지도 ID, `Place.spaceId`와 두 `MapTarget`의 ID는
  `^[a-z0-9][a-z0-9-]{0,63}$`를 따른다. `Map.version`, 핀 목록의 map version과
  `MapTarget.mapVersion`은 비어 있지 않아야 한다. `Space.image.url`과 현재 지도
  asset의 `Image.url`은 API의 `uri-reference` 계약을 따르며, snapshot 로드 경계에서
  URI 문법을 검증한다. scheme이나 host를 별도로 제한하지 않는다.
- ticket guide의 네 target 값은 모두 null이거나 모두 존재해야 한다. 존재할 때 동일
  revision의 현재 지도 version에 속한 `PLACE` 핀과 복합 FK로 연결한다. 티켓 수령 부스와
  외부인 티켓존은 하나의 `Place`로 모델링한다. 승인된 위치·좌표 자료가 아직 없으므로 seed하지
  않으며, 티켓 일정이 확정되지 않아 응답 status가 `UNCONFIGURED`여도 검증된 target은
  독립적으로 반환한다.

## snapshot과 API 동작

`CatalogSnapshotStore`는 repeatable-read 읽기 transaction에서 published revision을 한 번
선택한 뒤 한국어 번역, 정렬, 현재 map asset, 핀 target, canonical target, ticket 안내 본문과
ticket target, stamp guide를 같은 revision으로 읽고 검증한다. `TicketGuideController`와
`StampGuideController`는 요청마다 guide table을 다시 읽지 않고 이 snapshot만 사용한다.
번역·정렬·현재 asset·target 중 하나라도 빠지거나 target이 현재 version과 다르면 snapshot을
거부한다. 빈 `spaces`/`maps`는 정상 snapshot이며 각각 `items: []`, `overviewId: null`을
반환한다. 지도가 하나라도 있으면 전체 지도는 정확히 하나여야 한다.

`CatalogCliApplication`은 웹 서버와 분리된 non-web Spring entry point다. manifest는 10 MiB
이하의 로컬 JSON만 허용하고 알려지지 않은 key, 원격 URL 다운로드, SQL, 비밀값을 받지 않는다.
import는 festival 행 잠금과 manifest·snapshot 검증 뒤 draft 전체를 한 transaction으로 넣고
`IMPORT` 감사 행을 남긴다. validate는 같은 snapshot loader로 draft 또는 archived revision을
검증한다. publish는 festival 행을 잠근 뒤 draft를 재검증하고 현재 published보다 큰 revision
번호일 때만 이전 published revision을 archive한 뒤 새 revision을 publish하며 `PUBLISH` 감사 행을
남긴다. rollback은 archived
revision을 새 증가 번호의 draft로 복사한 뒤 같은 validate·publish 경로를 거친다. 이때
`mapVersion`은 이미지·좌표·target이 바뀌지 않았으면 유지하고 혼잡도 같은 실시간 운영 상태는
복사하지 않는다.

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
| 부분 게시로 이미지와 핀 좌표가 어긋남 | 현재 version FK, 복합 target FK, 시작 시 snapshot 검증, version mismatch 409을 함께 쓴다. CLI import·publish는 전체 revision transaction이라 부분 콘텐츠를 publish하지 않는다. |
| 다른 회차/미게시 자료 노출 | 모든 카탈로그 query를 published revision으로 제한하고 cross-revision FK를 둔다. |
| 번역을 한국어로 조용히 대체 | fallback하지 않는다. 알려졌지만 미준비 locale은 `LOCALE_NOT_READY`, 알 수 없는 locale은 `INVALID_QUERY`다. 필수 한국어 행 누락은 snapshot 실패다. |
| 잘못된 request ID 반사 | 형식·길이 제한과 서버 생성 fallback으로 header injection·로그 오염을 막는다. |
| 작은 데이터에 과도한 캐시·전파 구조 | 초기 전체 자료를 메모리에 올리고 재시작 반영을 사용한다. 다중 인스턴스/실제 CDN 전에는 Outbox·purge를 만들지 않는다. |
| 피크 트래픽 | 카탈로그 조회는 DB 왕복 없이 immutable snapshot을 읽는다. 동적 홈·공지·굿즈 상태는 화면이 보이는 동안 15초 polling으로 조회한다. 초기 기준은 동시 500명, 약 67 RPS와 그 1·2·5배에서 p95·오류율·heap·GC를 기록하고 실제 예상치가 나오면 갱신한다. |

## 구현 상태와 완료 기준

1. **V8 읽기 수직 절단:** JDBC snapshot loader, `/readyz`, spaces/maps/places controller와
   400·404·409·503 오류를 제공한다.
2. **V10 지도 필터:** `Pin.filterGroup`과 지도별 `filters`를 제공하며, legacy revision의 전체
   null 그룹을 읽을 수 있게 보존한다.
3. **V11 게시 경로:** immutable guide revision, manifest 검증, CLI import·validate·publish·rollback,
   audit log를 제공한다. HTTP 콘텐츠 쓰기 경로는 만들지 않는다.
4. **운영 자료 투입 gate:** 실제 asset·좌표·문구·번역·티켓존 자료는 manifest validation을
   통과한 완전 revision만 게시한다. 이 저장소에는 승인되지 않은 운영 부스·지도·핀·티켓존·번역을
   seed하지 않는다.

완료는 빈 published catalog가 공개 GET에서 계약 형식의 정상 빈 응답을 내고, 테스트 fixture가
locale별 정렬, `PLACE XOR AREA`, 대표 Space target, ticket·stamp guide의 같은 snapshot 정합성,
`Space → Place → Pin` 왕복 관계와 mapVersion 정합성을 모두 보장하는 것이다. 부분·고아·다른
revision·AREA·구버전 target, 같은 mapVersion의 자산·좌표·target drift, 불완전 filter group,
완전하지 않은 locale은 DB 제약 또는 snapshot/manifest 검증에서 거절해야 한다. alt·텍스트만
바꾼 재게시와 내용이 같은 rollback은 mapVersion을 올리지 않는다.

## 검증 기준

| 계층 | 반드시 확인할 결과 |
|---|---|
| Flyway/PostgreSQL | V8~V11 migration에서 FK·CHECK·UNIQUE 제약, legacy guide 보존, revision guide 복사, audit row, filter group 제약을 검증한다. Testcontainers가 Docker 부재로 skip되면 성공과 구분해 기록한다. |
| Java 단위/HTTP | 정상·빈 목록, category, 알려진 미준비 locale, unknown locale, not-found, mapVersion 409, 503, request ID 형식, ticket·stamp snapshot 및 target, 대표 target과 API v2 ID·이미지 URI 형식을 검증한다. |
| CLI | 알려지지 않은 JSON key·불완전 revision·원격 경로·10 MiB 초과 manifest를 거절하고, import·validate·publish·rollback의 상태 전이와 audit 기록을 검증한다. |
| API v2 | `contract-source.mjs`의 map filter·locale·target 의미와 생성물 일치, 목 contract check를 검증한다. Spring 응답은 JSON schema provider test로 별도 확인한다. |
| 회귀 | `mvnw.cmd --batch-mode --no-transfer-progress verify`, `api-v2`의 `npm run generate`, `npm run check`, `git diff --check`를 실행한다. |
| 성능 | 승인된 운영 자료를 넣기 전 합성 fixture로 동시 500명(약 67 RPS)과 1·2·5배를 실행하고 p95·오류율·heap·GC·환경·한계를 운영 문서에 남긴다. |

다중 인스턴스, CDN, 실제 예상 동시 접속자, 다국어 공개와 실제 운영 자료는 도입 전 다시
검토한다.
