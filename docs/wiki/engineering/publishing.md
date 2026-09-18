# 콘텐츠 게시

[위키 홈](../README.md) · 읽는 때: 관리자 콘텐츠·예약 게시·revision 변경

- 라인업, 공연, 공간, 지도, 공지, 외부 링크와 번역은 축제 회차에 귀속되는 운영
  콘텐츠로 관리한다.
- 콘텐츠는 최소 `draft / scheduled / published / archived` 상태, revision, 작성자,
  승인·게시·수정 시각과 감사 이력을 갖는다.
- 공개 서비스는 승인된 published revision만 읽는다. 개발자 전용 CLI가 HTTP 쓰기 API 없이
  `import → validate → publish / rollback`을 수행한다. 릴리스 운영자는 같은 서비스를 쓰는
  [로컬 카탈로그 워크벤치](catalog-workbench.md)로 export·검증·비교·가져오기·게시를 할 수 있다. 입력은 schema 검증 가능한 완전 revision
  JSON이며 DB 비밀값·수령 인증 코드·원격 URL 다운로드를 포함하지 않는다. 자산은 사전 배포한
  참조만 허용한다.
- import는 모든 입력 검증 뒤 한 transaction으로 새 draft만 삽입한다. validate는 실제 공개
  snapshot loader와 같은 규칙으로 locale, 정렬, 링크, 이미지, 지도 좌표, filter group,
  canonical target과 revision 연결을 검증한다.
- publish는 festival 행 잠금 뒤 target draft를 재검증하고, 현재 published보다 큰 revision 번호인
  경우에만 기존 published를 archived로 바꾼 뒤 target을 published로 전환하며 감사 기록을 같은
  transaction에 남긴다. 더 오래된 draft는 게시하지 않는다. 일정·지도처럼 서로 의존하는 변경은
  부분 게시하지 않는다.
- rollback은 archived revision을 새 증가 revision draft로 복제해 validate·publish를 다시 수행한다.
  이미지·좌표·핀 target이 같으면 기존 `mapVersion`을 유지한다. 호출자는 `--expected-current`로
  교체하려는 published revision을 명시하며, `none`은 published revision이 없다는 기대를 뜻한다.
- 모든 draft는 편집 기준이 된 published revision을 `base_revision_id`로 기록한다. manifest의
  `baselineRevisionId`가 그 값이며, 첫 catalog만 null을 쓴다. import·publish·rollback은 festival
  행 잠금 안에서 이 값을 현재 published pointer와 비교하고 다르면 `BASE_REVISION_CONFLICT`로
  중단한다. 그래서 준비 도중 끼어든 게시를 조용히 덮어쓰지 않는다.
- export는 저장된 revision 하나를 repeatable-read transaction에서 manifest로 되돌린다. 공개
  `CatalogSnapshot`을 재사용하지 않고 revision-scoped 행을 직접 읽어 다른 locale, 현재가 아닌
  map asset version과 공개 API가 내보내지 않는 내용까지 보존한다. 계좌·혼잡도는 catalog 밖이므로
  포함하지 않는다.
- export는 무손실이다. `PLACE` 핀의 filter group 누락과 부분 설정된 티켓 일정은 추측해 채우지
  않고 각각 `LEGACY_FILTER_GROUPS_UNCONFIGURED`, `LEGACY_TICKET_SCHEDULE_UNCONFIGURED` finding으로
  보고하며, 그 manifest의 import·publish는 승인된 값을 넣기 전까지 같은 코드로 실패한다.
- Current Festival은 서버 `FESTIVAL_ID` 환경변수로 선택한다. DB에서 현재 Festival을 자동
  추측하지 않으며, 설정된 회차에 published revision이 없으면 공개 콘텐츠를 가짜 revision으로
  제공하지 않는다.
- V13 공연 카탈로그도 `CatalogManifest`의 완전 revision 입력과 CLI
  `import → validate → publish / rollback` 대상이다. 저장된 공연 revision은 게시 직전에도
  한국어 필수 번역, 지원 locale, 링크·이미지·공연일·타임테이블 불변식을 재검증한다.
  이 수명주기 지원은 공개 라인업·타임테이블 조회 API의 활성화를 뜻하지 않는다.
- 일정·지도처럼 서로 의존하는 변경은 부분 게시하지 않는다. 예약 게시의 시간대와 실패 처리,
  중복 실행 안전성을 명시한다.
- 긴급 공지는 일반 콘텐츠보다 우선해 즉시 게시·수정·회수할 수 있고, 게시 결과와 캐시
  반영 상태를 운영자가 확인할 수 있어야 한다.
- 잘못 게시한 콘텐츠를 직전의 검증된 revision으로 빠르게 되돌리는 rollback 기능과
  권한·절차를 둔다. rollback은 티켓·스탬프 안내와 축제일을 포함한 완전 revision을 복원하되,
  실시간 혼잡도 저장값은 콘텐츠 rollback 대상으로 삼지 않는다.
