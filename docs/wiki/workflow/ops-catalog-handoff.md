# 운영·카탈로그 구현 인수인계

[위키 홈](../README.md) · 읽는 때: 이 장기 구현을 이어받거나 재개할 때

## 목적과 기준

이 문서는 PR #28 이후의 운영·카탈로그 백엔드 작업을 다른 에이전트나 개발자가
안전하게 이어받도록 현재 상태와 다음 행동을 기록한다. 기준 commit은
`d3a3e8e`이며, 최신 `origin/main`을 다시 확인한 뒤 작업을 시작한다.

포함 범위는 공통 동시성 기반, 정리 작업 틀, 관리자 혼잡도 백엔드, `TICKET`·`GOODS`
계좌 운영 설정, 티켓 계좌 분리, 카탈로그 export·게시 보호, 로컬 카탈로그
워크벤치다. 공지·굿즈 콘텐츠 API/UI/미디어 작업과 관리자 SPA 자체는 다른 담당자
범위다.

## 이어받는 에이전트 프로필

이 작업에는 적용된 Observatory 프로필의 다음 조합만 사용한다. 목적에 맞지 않는
모델이나 추론 강도를 임의로 바꾸지 않는다.

| 작업 성격 | 모델 · 추론 강도 |
|---|---|
| 메인 통합과 최종 판단 | `gpt-5.6-terra / ultra` |
| 단순하고 경계가 분명한 구현 | `gpt-5.6-luna / medium` |
| 상위 수준의 계획 | `gpt-5.6-sol / medium` |
| 고맥락 검토와 계획 검토 | `gpt-6-astra / low` |
| 난도가 높거나 해법이 불명확한 설계·문제 해결 | `gpt-6-astra / max` |
| 복잡하지만 요구가 명확한 구현 | `gpt-5.6-luna / max` |

병렬 구현을 맡길 때는 각 하위 작업의 성격과 위 행을 함께 기록한다. 현재 공통
cleanup과 혼잡도 구현은 마지막 조합을 사용한다.

## Backend release E2E · 2026-09-21

`src/test/java/dev/espero/festival/e2e/ReleaseReadinessHttpE2eTest.java`는 후보 catalog
manifest를 미래 릴리스 전에 독립적으로 검증하는 backend 게이트다. Testcontainers PostgreSQL에
Flyway를 적용하고, 실제 Catalog CLI로 import·publish한 다음 랜덤 포트 서버를 기동한다.
원격 개발·운영 DB나 그 자격증명에는 연결하지 않는다.

기본 후보는 `dev/catalog/development-catalog.json`이고, 다른 후보는
`-Dfestival.release-e2e.manifest=<path>`로 준다. 이 테스트는 readiness와 후보 revision,
공개 공간·지도·핀·장소·티켓·스탬프 경로, 조건부 응답, 그리고 혼잡도 관리자 인증·동시성·감사
경계를 HTTP로 확인한다. 목록에 실제 ID가 있으면 상세 경로도 순회한다. 빈 공간·지도와
`UNCONFIGURED` 티켓은 현재 계약상 유효한 표현으로 다룬다.

이 게이트는 공지·굿즈 담당자의 API·미디어 검증, 원격 DB preflight·role provisioning,
실제 배포 smoke와 운영 자료 승인을 대체하지 않는다. 후속 작업자는 이 경계를 유지하고,
변경 뒤 focused E2E와 전체 `mvnw.cmd verify` 결과를 아래 검증 기록에 추가한다.
## 재개 절차

1. `AGENTS.md`, 이 문서, 해당 단계의 작업별 위키·API 계약을 읽는다.
2. 더티 루트 checkout을 사용하지 않는다. 최신 `origin/main`에서 전용 `codex/` branch와
   linked worktree를 만들고, `git status --short --branch`가 깨끗한지 확인한다.
3. migration, catalog import 또는 publish 전에 독립적인 읽기 전용 preflight를 실행한다.
   이 단계에서는 웹 앱, Catalog CLI, Flyway, scheduler, catalog audit을 시작하지 않는다.
4. preflight가 shared schema, 알 수 없는 Flyway history, 기존 catalog의 소유 불명확성을
   보고하면 `STOP_AND_REVIEW`로 멈춘다. 원격 DB에 시험 migration을 적용하지 않는다.
5. 가장 앞선 미완료 PR 하나만 통합하고, 검증·commit·이 문서의 상태를 갱신한 뒤 다음
   PR로 진행한다.

## 현재 상태 · 2026-09-18

| 순서 | 목표 | 상태 | 다음 확인 |
|---|---|---|---|
| 1 | 공통 동시성 기반 | 통합 완료 | request ID·conditional response·CORS·idempotency·If-Match 기반을 통합했고 `PUT /admin/crowding`이 첫 사용처다. |
| 2 | 정리 작업 틀 | 통합 완료 | `admin_audit_events` 1년, `COMPLETED` idempotency 24시간 retention과 재사용 target 경계를 통합했다. 전용 cleanup role과 연결 없이는 삭제하지 않는다. |
| 3 | 관리자 혼잡도 백엔드 | 통합 완료 | FestivalDay 일정 기반 GET/PUT과 `crowding_state_dynamic`을 통합했다. migration은 병합 시 V16으로 재배정했고 원격 DB에는 적용하지 않았다. |
| 4 | 계좌 운영 설정 | 구현 통합, provisioning 대기 | CLI·V15 설정/이력 schema·history retention을 통합했다. DB provider의 role 발급과 provisioning script 실행은 남아 있다. |
| 5 | 티켓 계좌 분리와 polling | 통합 완료 | catalog에서 계좌·송금 링크를 제거하고 `/ticket-guide`가 현재 `TICKET` 설정과 합쳐 조건부 응답을 낸다. 실제 계좌 등록과 read 전환 배포는 [계좌 운영 설정](../engineering/operational-account-settings.md)의 runbook을 따른다. |
| 6 | catalog export·게시 보호 | 통합 완료 | `base_revision_id` 기반 `BASE_REVISION_CONFLICT`, 명시적 rollback 기대값, revision 직접 읽기 exporter와 legacy finding을 구현했다. 로컬 workbench(7)는 아직 시작하지 않았다. |
| 7 | 로컬 catalog workbench | 구현 완료 | `127.0.0.1` 전용 companion·UI, 세션 token, Host·Origin 검증, loopback(SSH tunnel) DB URL 강제, export·publish role 분리 context, 검증·diff·가져오기·게시·게시 후 확인. [runbook](../engineering/catalog-workbench.md). |

현재 통합 branch는 `feat/ops-foundation`이고 마지막 `main` 병합 commit은 `05f8f68`(PR #35까지)다.
PR #29는 `main`에 병합됐지만 #30·#31은 stack의 중간 branch로 병합돼 `main`에 반영되지 않았으므로,
그 내용은 이 branch에서 다시 PR로 올린다.
이전 통합 branch `codex/goal-ops-catalog`와 개별 작업 branch의 내용은 모두 이 branch에
들어왔다. 이 표는 각 통합 commit, 실패, 외부 의존성 변화 뒤에 반드시 갱신한다.

### 이미 통합한 변경

- `f884ce1`: 이 인수인계 문서와 읽기 표를 추가했다.
- `495decd`: Windows의 일반 Maven 사용자 홈에서 wrapper가 시작 전에 실패하던 문제를
  수정하고, 관리자 CORS에 `If-Match`·`Idempotency-Key`와 `ETag`·서버 시각 헤더를 추가했다.
- `282c84a`: 서버 생성 request ID, 안정 conditional envelope, SHA-256 strong ETag,
  `If-None-Match` 304 지원 유틸리티를 추가했다. 아직 개별 공개 경로의 전면 전환은 하지 않았다.
- `ebe763f`: Spring·Flyway·scheduler·catalog audit 없이 JDBC 읽기만 하는
  `DatabasePreflightApplication`과 runbook을 추가했다.
- `a3cc13e`: hashed idempotency record V14, 2분 lease, 동일 키 replay, 처리 중 409,
  typed `If-Match` 정책·428/409 도구를 추가했다. V14는 **원격 DB에 적용하지 않은
  provisional 번호**다.
- `b107026`: client supplied request ID를 반사하지 않는 conditional response 정책을
  부스·지도 백엔드 문서에도 맞췄다.
- `81ae54e`: 적용된 Observatory profile의 모델·추론 강도 배정을 이 문서에 남겼다.
- `792e51c`: 500행 이하 단일 transaction batch, advisory lock, dry-run, 전용 cleanup
  datasource/role 검증과 1년 감사 retention을 추가했다.
- `ca2a2d1`: `COMPLETED` idempotency response만 24시간 뒤 정리하는 target을 추가했다.
- `7b17aeb`: `TICKET`·`GOODS` 계좌 현재 설정 V15, trigger 소유 immutable history와
  dry-run 기본 CLI를 추가했다.
- `5a0b4f3`: 계좌 이력을 cleanup 틀에 등록하고 현재 version·최신 복원 가능 상태를
  retention 이후에도 보존한다.
- `8601903`: 혼잡도를 `(festival_id, operating_date)` 키와 published FestivalDay 일정으로
  옮기고 V5 legacy 표는 보존한다.
- `586be92`: 위 두 갈래를 병합했다. preflight의 non-catalog 표 목록을 합치고, 계좌
  migration이 V15를 쓰고 있어 혼잡도 migration을 V16으로 재배정했다.
- `PR6`: draft마다 편집 기준 published revision을 기록하고 import·publish·rollback이 잠금 안에서
  이를 검증한다. `export` CLI는 revision을 직접 읽어 manifest로 되돌리고, 부분 티켓 일정을
  finding으로 보고하며 재import를 막는다. filter group 없는 `PLACE` 핀은 V19 이후 정상이다.
- `23f59d7`: 티켓 계좌를 catalog에서 분리했다. manifest는 계좌·링크 field를 unknown
  property로 거절하고, snapshot·copy·rollback은 legacy 열을 읽지 않으며,
  `/ticket-guide`가 현재 `TICKET` 설정과 `paymentSettingsVersion`을 합쳐 strong ETag와
  `Cache-Control: private, no-cache`로 응답한다.

### 현재 병렬 작업

없다. `codex/goal-ops-catalog`, `codex/admin-crowding-backend`,
`codex/account-operational-settings`, `codex/cleanup-framework`,
`codex/conditional-foundation`, `codex/preflight-safety`의 내용은 모두
`feat/ops-foundation`에 포함됐다. 다음 작업은 이 branch에서 이어간다.

## 고정 안전 규칙

- Flyway 번호는 병합 직전 최신 `main`의 다음 번호로 다시 정한다. 병합 전 공유 원격
  개발 DB에는 적용하지 않는다.
- OpenAPI 생성물은 손으로 병합하지 않는다. source 충돌을 해결한 뒤
  `npm run generate`, `npm run check`로 재생성한다.
- 모든 catalog/account CLI는 `spring.flyway.enabled=false`로 실행한다. preflight는
  별도 plain JDBC 도구이며 catalog audit을 남기지 않는다.
- Windows에서는 `cmd /d /c "mvnw.cmd ..."`로 wrapper를 실행한다. wrapper 자체의
  null symlink target 처리는 `495decd`에서 수정했고, PowerShell에서 직접 실행하지 않는다.
- DB role 이름·GRANT는 Flyway migration에 넣지 않는다. 환경별 provisioning script를
  사용한다. 운영에서는 preflight, migration, runtime, cleanup, account operator,
  catalog export, catalog publish 역할을 분리한다.
- 비밀값·계좌번호·토큰·원격 DB 자격증명은 문서·로그·commit에 넣지 않는다.
- 같은 checkout을 둘 이상의 에이전트가 수정하지 않는다. 병렬 작업은 각 branch와
  worktree에서 검증·commit한 뒤 통합 worktree에 가져온다.

## 설계 불변식

- idempotency는 관리자·method·route·resource 범위와 hashed fingerprint를 사용한다.
  lease 예약은 짧게 commit하고, 업무 변경과 `COMPLETED` 기록은 같은 transaction에서
  commit한다.
- conditional response의 strong ETag는 실제 안정 본문 전체를 해시한다. volatile
  request ID/server time은 `X-Request-Id`, `X-Server-Time` 헤더에만 둔다.
- 혼잡도는 `(festival_id, operating_date)` 동적 상태이고 `meta.revision`은 0이다.
  published snapshot의 FestivalDay가 없으면 임시 운영 시간을 만들지 않고 503으로
  실패한다.
- 계좌 설정은 catalog revision 밖 데이터다. catalog export/publish role은 설정·이력과
  legacy ticket 계좌 열을 읽지 못한다.
- draft는 명시적인 baseline published revision을 저장한다. import·publish·rollback은
  festival 행 잠금 안에서 이를 검증하며, 동적 혼잡도·계좌 설정을 rollback하지 않는다.

## 검증 기록

| 날짜 | 변경 또는 확인 | 결과 | 다음 행동 |
|---|---|---|---|
| 2026-09-21 | 후보 backend E2E | 기본 후보와 `festival.release-e2e.manifest` 지정 경로가 임시 PostgreSQL에서 각각 1개 통과. 최신 `origin/main` 병합 뒤 `mvnw.cmd clean verify`는 603개 통과, 실패·오류 0, 기존 환경 의존 skip 9개 | 실제 출시 후보 manifest에 같은 property를 지정하고, 공지·굿즈·배포 전용 게이트를 별도로 통과시킨다. |
| 2026-09-18 | 최신 원격 기준 확인 | `origin/main`은 `d3a3e8e`(PR #28) | PR 1 구현을 시작한다. |
| 2026-09-18 | CORS·Maven wrapper focused test | `AdminCorsConfigurationTest` 1개 통과 | 전체 suite는 통합 뒤 실행한다. |
| 2026-09-18 | conditional response subtask | agent branch에서 Maven 261개 통과 | `282c84a`로 통합했다. |
| 2026-09-18 | standalone preflight subtask | agent branch에서 Maven 269개 통과, local refused-connection smoke 통과 | `ebe763f`로 통합했다. 원격 DB 연결은 하지 않았다. |
| 2026-09-18 | idempotency·precondition focused suite | unit + PostgreSQL Testcontainers 19개 통과 | cleanup/crowding 통합 뒤 전체 `verify`를 실행한다. |
| 2026-09-18 | cleanup framework + completed idempotency target | PostgreSQL Testcontainers focused suite 10개 통과 | crowding 통합 뒤 전체 `verify`를 다시 실행한다. |
| 2026-09-18 | 원격 DB 상태 | 실행하지 않음 | 구현 중·병합 전에는 remote DB mutation을 금지한다. |
| 2026-09-18 | crowding branch를 통합 branch에 병합 | 충돌 1건(`DatabasePreflight` 표 목록)을 합집합으로 해결하고 crowding migration을 V16으로 재배정 | 아래 두 검증을 실행했다. |
| 2026-09-18 | `api-v2` 재생성과 계약 검증 | `node generate.mjs` 결과가 병합본과 동일, `npm run check` 324개 통과 | 생성물을 손으로 고치지 않았다. |
| 2026-09-18 | 전체 `mvnw.cmd verify` | 첫 실행은 직접 Flyway를 구성하는 계좌·preflight 통합 테스트 4건이 `${festivalId}` placeholder 누락으로 실패 | 두 호출부에 빈 placeholder를 전달해 수정했다. |
| 2026-09-18 | 전체 `mvnw.cmd verify` 재실행 | Docker 사용 가능 상태에서 312개 통과, 실패·오류·건너뜀 0 | PR 5 티켓 계좌 분리를 시작한다. |
| 2026-09-18 | PR 5 티켓 계좌 분리 후 `mvnw.cmd clean verify` | 318개 통과, 실패·오류·건너뜀 0 | 계좌 등록 전에는 `UNCONFIGURED`가 정상 응답이다. |
| 2026-09-18 | PR 5 `api-v2` 재생성과 `npm run check` | 324개 통과. `paymentSettingsVersion`, 조건부 envelope, `Cache-Control`·`X-Server-Time` 헤더 선언을 반영 | 생성물은 source 수정 뒤 재생성했다. |
| 2026-09-18 | `main`(PR #32~#35) 병합 | 충돌 4건 해결, 기준 revision migration을 V18로 재배정, 개발 catalog manifest의 계좌 field 제거, PG17 테스트의 migration 버전 하드코딩 제거. `clean verify` 343개 통과 | #30·#31 내용과 부하 시나리오를 새 PR로 올린다. |
| 2026-09-18 | 67 RPS 동적 부하(`tools/load-test/run.ps1`) | `rate-67` 통과: crowding p95 5.137ms·p99 6.380ms, ticket p95 3.548ms·p99 4.231ms, 4,022건 예상 밖 오류·5xx·429 0. 100/200/500 VU 오류 0. DB 연결 최대 10, lock 대기 0 | 원격 환경 용량은 별도로 검증한다. |
| 2026-09-18 | PR 6 게시 보호·export | `CatalogRevisionServiceIntegrationTest` 21개 통과. 끼어든 게시·stale rollback 차단, export→import→export 동일성, legacy finding과 import 차단을 포함 | 로컬 workbench와 부하 시나리오는 남아 있다. |
| 2026-09-18 | catalog role의 legacy 티켓 열 차단 | provisioning script를 실행하는 Testcontainers 검증에서 export/publish role의 `SELECT *`와 계좌 열 조회가 권한 거부 | 원격 DB에는 아직 적용하지 않았다. |
| 2026-09-18 | cleanup post-commit 파일 작업 | `CleanupPostCommitIntegrationTest` 4개 통과(commit 뒤에만 실행, 일시 실패 재시도, 재시도 한도 초과 보고, rollback·dry-run 시 미실행). `clean verify` 347개 통과 | 파일을 소유하는 target이 생기면 `afterCommit`으로 등록한다. |
| 2026-09-18 | 혼잡도 흐름·V5 이관 | `CrowdingFlowIntegrationTest` 7개(축제 전·공백일·축제 후 `NOT_FESTIVAL_DAY`, 운영일 없음·게시본 없음 503, `If-Match` 누락 428, 저장 뒤 새 ETag·304, 완료 요청 replay, 지문 재사용·stale 409, publish·rollback 뒤 상태 보존)와 `CrowdingStateMigrationIntegrationTest` 5개(빈 V5, 행 있을 때 `FESTIVAL_ID` 필수, 다른 축제·날짜 불일치 중단, 일치 행 복사) 통과. `clean verify` 359개 통과 | 원격 DB에는 V16을 적용하지 않았다. |
| 2026-09-18 | 티켓 계좌 흐름·last-four | `TicketGuideAccountFlowIntegrationTest` 2개(계좌 등록·변경·해제가 시각 변화 없이 다음 요청에 반영, 옛 ETag 200·새 ETag 304와 `private, no-cache`, 송금 마감 시 계좌 숨김·ETag 변경)와 last-four 불일치·형식 오류 거절 통과. `clean verify` 362개 통과 | 아래 완료 조건 대응표에 반영했다. |
| 2026-09-18 | 디자인 정렬(지도 필터·푸드트럭 구역·부스 분류 6개) | V19·V20, `SpaceCategories`, 계약·목 서버 갱신. `clean verify` 371개, `npm run check` 325개 통과 | #40이 첫 커밋만 병합되어 필터 커밋을 다시 올렸다. |
| 2026-09-18 | PR 7 로컬 catalog workbench | `WorkbenchUnitTest` 4개, `CatalogWorkbenchIntegrationTest` 4개, catalog role의 혼잡도·공지 테이블 차단 검증 통과. 임시 로컬 PostgreSQL로 브라우저에서 검증·비교·가져오기·게시·지도 URI 편집을 확인했고 console 오류 없음 | 원격 DB·SSH tunnel로는 실행하지 않았다. |
| 2026-09-19 | `GET /api/v2/config` | V22 `festival_links`, manifest `festivalLinks`·검증·import·rollback 복사·export, snapshot `FestivalHome`, `ConfigController`. OpenAPI provider 검증 포함 | FAQ·웰컴 데이·공지 URL은 확정 전이라 `null`이다. main의 굿즈 schema(V21)와 겹쳐 V22로 재배정했다. |
| 2026-09-19 | 부스 계좌 송금 안내 | V23로 운영 계좌 설정에 `SPACE`(부스별 scope, `bank_code`, `toss_link_enabled`) 추가, CLI `--space-id`, 부스 상세 `bankTransfer`(목록 null, `no-store`). 계좌·CLI·controller·OpenAPI·V23 업그레이드 테스트 통과 | 토스 송금 URL은 실기기 검증 전이라 `tossLinkEnabled` 기본 false. 원격 DB 미적용. |

새 행에는 실행한 명령의 요약, 실제 결과, 미실행 사유를 남긴다. 실패한 검증은 삭제하지
않고 원인과 후속 조치를 기록한다.

## 완료 조건 대응표

인수인계 완료 조건별로 근거가 되는 자동 검증이다. 모두 `mvnw clean verify`에 포함된다.

| 완료 조건 | 근거 테스트 |
| --- | --- |
| preflight: SELECT만, Flyway·catalog audit 미시작, 불명확한 DB에서 `STOP_AND_REVIEW` | `DatabasePreflightIntegrationTest`(SELECT 외 statement 거부, `flyway_schema_history`·`catalog_revision_audit`·`admin_audit_events` 무변경, 빈 schema에서 migration 미실행, checksum·미지 table·다른 schema·다른 session·축제 누락 시 중단), `DatabasePreflightApplicationTest`(설정 오류·연결 실패 시 `STOP_AND_REVIEW`, read-only 연결 강제), `DatabasePreflightPostgresql17IntegrationTest` |
| idempotency: 동시 중복·지문 재사용 409, lease 만료 뒤 이중 적용 없음, 원자성 | `AdminIdempotencyServiceIntegrationTest`, `CrowdingFlowIntegrationTest`(HTTP replay·`IDEMPOTENCY_KEY_REUSED`) |
| ETag: 304, CORS 노출, 428, stale 409, PUT 후 재조회, 정책 선언 예외 | `CrowdingFlowIntegrationTest`, `CrowdingControllerOpenApiTest`, `AdminCorsConfigurationTest`, `AdminMutationPreconditionsTest` |
| cleanup: advisory lock, dry-run 무변경, 500행 batch, 파일 post-commit 재시도 | `AdminAuditCleanupIntegrationTest` 외 cleanup suite, `CleanupPostCommitIntegrationTest` |
| 혼잡도: 축제 전·공백일·축제 후 거절, snapshot·운영 시각 없음, V5 행 유무, publish/rollback 뒤 보존 | `CrowdingFlowIntegrationTest`, `CrowdingStateMigrationIntegrationTest`, `CrowdingControllerTest`, `CrowdingStoreIntegrationTest`. 운영 시각은 `festival_days`에서 NOT NULL이므로 누락은 운영일 없는 게시본으로 검증한다. |
| 계좌: trigger·direct SQL 이력, history 변경 차단, dry-run, last-four·version 거절, restore/clear, role 차단, 민감값 로그 부재 | `OperationalAccountSettingsIntegrationTest`, `AccountSettingsCliRunnerTest` |
| 티켓: `UNCONFIGURED`, 송금 경계 ETag 변경, 304 흐름, 계좌 수정 뒤 15초 안 반영, legacy 열 미접근 | `TicketGuideAccountFlowIntegrationTest`, `TicketGuideControllerTest`, `TicketGuideStoreIntegrationTest`, provisioning role 검증(`OperationalAccountSettingsIntegrationTest`) |
| export/publish: 끼어든 게시·rollback 충돌 차단, semantic round-trip, legacy 차단, 최신 공연 catalog 보존 | `CatalogRevisionServiceIntegrationTest` |
| 67 RPS 부하와 지표 기록 | `tools/load-test/run.ps1`의 `rate-67` 단계. 결과와 heap·GC·DB 지표는 `tools/load-test/README.md`에 있다. 로컬 결과이며 원격 용량은 확정하지 않았다. |

## 외부 의존성

- `fall-festival-admin`은 별도 프로젝트이며 로컬 포트 3001에서 기존 login/refresh/API
  client를 공유한다. 원격 관리자 proxy와 refresh cookie E2E는 후속 범위다.
- 팀 제공 PostgreSQL은 전용성이 보장되지 않았다. preflight 결과와 DB 제공자의 role
  발급 결과 없이는 role 분리 또는 migration 안전성을 주장할 수 없다.
- 실제 FestivalDay 운영 시각과 운영 자료는 승인 전이다. 코드의 local fixture 검증과
  원격 운영 검증을 구분한다.
