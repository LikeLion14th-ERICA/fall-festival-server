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

## Backend release E2E · 2026-09-21

`src/test/java/dev/espero/festival/e2e/ReleaseReadinessHttpE2eTest.java`는 후보 catalog
manifest를 미래 릴리스 전에 독립적으로 검증하는 backend 게이트다. Testcontainers PostgreSQL에
Flyway를 적용하고, 실제 Catalog CLI로 import·publish한 다음 랜덤 포트 서버를 기동한다.
원격 개발·운영 DB나 그 자격증명에는 연결하지 않는다.

기본 후보는 사용자 흐름 검증용 `dev/catalog/frontend-mock-catalog.json`이다. 이 fixture는
로컬 Testcontainers 전용이며 원격 개발 DB나 운영 데이터로 import·publish하지 않는다. 빈
catalog 또는 실제 출시 후보는 `-Dfestival.release-e2e.manifest=<path>`로 지정한다. 실제
콘텐츠 관계까지 요구하려면
`-Dfestival.release-e2e.require-user-journey-content=true`를 함께 지정한다.

E2E는 응답에서 ID를 읽어 다음 흐름을 HTTP로 검사한다.

- 서비스 health/readiness와 published revision의 config
- 공간 목록·분류·상세와 대표 `mapTarget`에서 AREA 지도·핀·장소로의 이동
- overview의 AREA 핀에서 대상 구역 지도 이동, stale `mapVersion` 409 뒤의 재조회
- 기본 날짜의 lineup에서 artist·performance·timetable로의 연결
- ticket guide의 조건부 응답과 지도 target, stamp 안내의 보상·기간·일일 한도
- 관리자 login·`/admin/me`·refresh rotation·logout
- 혼잡도 변경, idempotency replay, 같은 단계 no-op, stale ETag, FULL 확인
- locale·중복 query 오류, weak·다중 ETag, CORS·잘못된 인증 입력과 후보 datasource 격리
- 모든 선언 날짜·lineup category·공간 category filter의 반복 응답·정렬·상세 관계
- 실행 중인 server에서 별도 계좌 CLI set·clear의 ticket guide 반영과 transfer close 계좌 비노출
- 실제 동시 혼잡도 PUT의 단일 변경·감사·idempotency 재사용 거절
- 게시 A/B, 제어된 server restart, expected-current rollback 뒤 새 revision A 복원과 동적 상태 보존
- 공개 입력 오류 뒤의 config 복구와 malformed admin bearer가 공개 경로를 막지 않는지
- 첫 FestivalDay 전·마지막 FestivalDay 후 config와 lineup의 같은 기본 날짜 선택
- TICKET 계좌가 설정된 상태의 송금 개시·마감 초 경계에서 상태·계좌 노출·settings version·ETag 전환
- 혼잡도 첫날 전·공백일·마지막 날 뒤와 개장·마감 경계, 비축제일 PUT 거절·운영 시간 밖 축제일 저장
- 선택 회차에 게시본이 없는 deploy의 `/healthz` 생존·`/readyz` not-ready·공개 config `CATALOG_NOT_READY` 경계와 공개·login rate limit의 client 분리·refill 회복
- 별도 관리자 session server에서 누락 Origin·malformed JSON·token 없는 logout의 안전한 거절

빈 목록과 `UNCONFIGURED` 티켓은 non-strict 후보에서 현재 계약상 유효한 표현으로 다룬다.
strict 후보에서는 최소 하나의 공간·대표 지도 경로·PLACE 핀·AREA 핀·공연·타임테이블 관계를
요구한다.

이 게이트는 공지·굿즈 담당자의 API·미디어 검증, 원격 DB preflight·role provisioning,
실제 배포 smoke와 운영 자료 승인을 대체하지 않는다. 후속 작업자는 이 경계를 유지하고,
변경 뒤 focused E2E와 전체 `mvnw.cmd verify` 결과를 아래 검증 기록에 추가한다. 브라우저의
navigation/back 상태, Secure·SameSite cookie, 관리자 proxy, 모바일 네트워크 복귀와 polling은
web 저장소와 실기기 acceptance gate에서 확인한다.

2026-09-22 인수인계 당시 release E2E 시나리오는 HTTP-01~24와 OPS-01~20, 총 **44개**였다.
현재 목록·개수는 [릴리스 HTTP E2E](release-http-e2e.md)를 확인한다. 이 수는 관련 요청을
한 lifecycle 안에 묶는 JUnit method 수와 다르며, 각 시나리오의 상세 매핑은
[검증 명령](validation.md#릴리스-후보-backend-e2e)에 둔다.

## 운영자·개발자 도구 E2E · 2026-09-21

`src/test/java/dev/espero/festival/e2e/OperatorToolProcessE2eTest.java`는 실제 별도 JVM의
`CatalogCliApplication`과 `AccountSettingsCliApplication`을 새 Testcontainers PostgreSQL에 연결한다.
다음 작업자가 이 테스트를 in-process service test로 바꾸거나 원격 datasource를 재사용하지 않는다.

- Catalog CLI: 같은 baseline에서 만든 두 draft 중 먼저 게시된 revision만 통과하는지, export,
  archived revision rollback, stale publish·rollback 거절, malformed UUID의 안전한 exit 1을 확인한다.
- 계좌 CLI: dry-run 무변경, confirm set, 잘못된 끝 네 자리 거절, clear, restore-version, versioned
  trigger history와 stdout/stderr의 은행·계좌번호·예금주·DB credential redaction을 확인한다.
- `DatabasePreflightIntegrationTest`는 이미 JDK와 PostgreSQL driver만의 별도 process로 preflight의
  read-only·`STOP_AND_REVIEW` 경계를 확인한다. migrated catalog DB의 preflight 성공을 요구하지 않는다.
- `CatalogWorkbenchIntegrationTest`는 HTTP 워크벤치가 다른 축제 revision의 export·diff·publish를
  `REVISION_FESTIVAL_MISMATCH`로 거절하고 해당 축제의 published pointer·revision·audit을 바꾸지
  않는지 확인한다.
- 추가 process 경계는 손상된 draft publish 뒤 정상 replacement 복구, Catalog·계좌 CLI의
  read-only role 거절, 계좌 입력·expected-version 무결성, standalone preflight의 설정 오류
  redaction, hostile logging 환경의 JDBC URL·비밀번호·stack trace 비노출까지 확인한다.
- Catalog CLI의 마지막 audit insert를 PostgreSQL trigger로 실패시키면 import·publish·rollback의
  draft·복제 행·published pointer·audit이 각각 같은 transaction에서 rollback되고 재시도만
  성공하는지 확인한다. 두 real child JVM의 publish/publish·publish/rollback도 festival 행 잠금에서
  실제로 대기한 뒤 승자 하나만 state·audit을 남기는지 확인한다.
- `OperationalReleaseGateE2eTest`는 별도 SELECT-only role로 preflight를 실행해 Flyway history를
  바꾸지 않는지, 같은 role의 Account CLI clear·restore가 current setting·version·history를
  바꾸지 못하는지 확인한다.
- `DatabasePreflightEmptyDatabaseE2eTest`는 JDK+PostgreSQL driver child JVM이 빈 DB를 두 번
  점검해도 relation·Flyway·festival·revision·audit을 만들지 않는지 확인한다.
- 실제 CLI draft export→import→validate→draft export→publish는 generated ID·감사 시각 외 의미
  단위로 왕복 동일해야 한다. source draft는 이 흐름 뒤에도 draft로 남는다. 일정 필수값 6개 중
  하나라도 빠진 legacy ticket은 export finding과 무변경 차단 뒤 값 복구 retry만 게시할 수 있다.
  `null` PLACE filter는 현재 계약상 정상으로 lossless 보존한다.

이 E2E는 test classpath의 main entry point를 실행한다. 배포 JAR의 `PropertiesLauncher` 명령은
package 뒤 운영 runbook대로 별도로 실행한다. 단, 이 검증도 원격 DB, SSH tunnel, 실제 계좌 또는
배포 환경을 사용하지 않는다.

## E2E 재개·출시 후보 확인

1. Docker가 실행 중인지 확인하고, 원격 DB에는 연결하지 않는다. 이 release E2E는 Docker 부재 시
   skip하지 않고 실패해야 한다.
2. HTTP release E2E는 `cmd /d /c "mvnw.cmd --batch-mode --no-transfer-progress -Dtest=ReleaseReadinessHttpE2eTest,OperationalAccountPropagationE2eTest,CrowdingConcurrencyE2eTest,CatalogPublicationLifecycleE2eTest,AdminSessionReleaseE2eTest,ReleaseFailureModesHttpE2eTest test"`를 실행한다.
3. 운영자·개발자 도구 확인은 `cmd /d /c "mvnw.cmd --batch-mode --no-transfer-progress -Dtest=OperatorToolProcessE2eTest,OperationalReleaseGateE2eTest,DatabasePreflightEmptyDatabaseE2eTest,CatalogCliRunnerTest,CliFlywayIsolationIntegrationTest,DatabasePreflightIntegrationTest,CatalogWorkbenchIntegrationTest test"`를 실행한다.
4. 실제 출시 후보는 `-Dfestival.release-e2e.manifest=<path>`와
   `-Dfestival.release-e2e.require-user-journey-content=true`를 지정해 실행한다.
5. focused E2E가 통과하면 `cmd /d /c "mvnw.cmd --batch-mode --no-transfer-progress clean verify"`를 실행한다.
6. 실패하면 fixture를 완화하지 않는다. 끊어진 catalog 관계, 계약, 서버 동작 중 어느 층이
   원인인지 기록하고 수정한다.
7. 원격 개발 DB 검증은 별도의 `DatabasePreflightApplication`과 운영 승인 절차로 수행한다.

## 다음 작업자 재개 기준 · 2026-09-22

- 이 문서의 release E2E는 원격 개발 DB를 읽거나 변경하지 않는다. Testcontainers PostgreSQL만
  사용하며, Docker가 없을 때 focused release E2E가 성공으로 보이면 중단하고 Docker 환경에서
  다시 실행한다.
- 재개 전 `git fetch origin`으로 최신 `main`을 확인한다. `origin/main`을 포함하지 않은 branch는
  먼저 병합하고, 충돌을 해결한 뒤 HTTP·운영자 focused 명령과 전체 `clean verify`를 다시 실행한다.
- 이 인수인계 시점의 matrix는 HTTP-01~24와 OPS-01~20, 총 44개였다. focused 명령의 당시
  JUnit invocation은 HTTP 20개, 운영자·개발자 도구 48개였다. 최신 범위는
  [릴리스 HTTP E2E](release-http-e2e.md)를 사용하고, scenario 수와 invocation 수를 같은
  수치로 보고하지 않는다.
- 유지해야 할 경계: 비축제일 관리자 혼잡도 PUT은 `409 NOT_FESTIVAL_DAY`, 실제 FestivalDay의
  운영 전·후 저장은 허용한다. pre-open 티켓은 계좌를 노출하지 않는다. legacy ticket 일정은
  import·validate·publish 모두 `LEGACY_TICKET_SCHEDULE_UNCONFIGURED`으로 차단한다. `null`
  PLACE filter는 현재 계약상 정상이며 lossless로 보존한다.
- 워크벤치 role context와 E2E는 일반·Hikari datasource, JNDI, 외부 config의 상속을 차단해야
  한다. 이 격리가 약해지면 local test가 팀 원격 DB에 연결할 수 있으므로 fixture를 바꾸어
  우회하지 말고 isolation을 먼저 복구한다.

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
| 6 | catalog export·게시 보호 | 통합 완료 | `base_revision_id` 기반 `BASE_REVISION_CONFLICT`, 명시적 rollback 기대값, revision 직접 읽기 exporter와 legacy finding을 구현했다. 실제 process E2E가 stale draft·rollback을 검증한다. |
| 7 | 로컬 catalog workbench | 구현 완료 | `127.0.0.1` 전용 companion·UI, 세션 token, Host·Origin 검증, loopback(SSH tunnel) DB URL 강제, export·publish role 분리 context, 검증·diff·가져오기·게시·게시 후 확인과 cross-festival revision 차단. [runbook](../engineering/catalog-workbench.md). |

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
| 2026-09-22 | release E2E 44개 확장·회귀 정리 | HTTP focused 20개, 운영자·개발자 도구 focused 48개가 각각 실패·오류·건너뜀 0으로 통과했다. `api-v2` 생성·계약 검사 357개도 통과했다. 최신 `origin/main` 포함을 확인한 뒤 `mvnw.cmd clean verify`는 648개 중 639개 통과, 실패·오류 0, 선택적 미디어 호환성 9개 skip으로 통과했고 JAR를 생성했다. 테스트가 발견한 티켓 pre-open 계좌 노출, 비축제일 혼잡도 저장 허용, 워크벤치 datasource 상속, legacy ticket 일정 오류 식별자 불일치, 오래된 혼잡도 기대값을 수정했다. | 이후 변경은 44개 scenario matrix와 Testcontainers-only isolation을 유지한다. 원격 DB preflight·role provisioning·실제 배포 smoke와 공지·굿즈 담당 범위는 별도 gate다. |
| 2026-09-21 | 상세 release E2E 확장·격리 hardening | HTTP-01~17·OPS-01~12의 29개 시나리오를 문서화했다. 별도 CLI JVM, 계좌 변경의 live HTTP 반영, 동시 idempotency, publish→restart→rollback lifecycle, 전체 candidate traversal, locale·ETag·CORS 오류를 Testcontainers에서 확인했다. Hikari datasource-class/property URL·Flyway URL·JNDI 상속도 dummy 값으로 회귀 검증했고 원격 DB에는 연결하지 않았다. focused 16개는 실패·오류 0, `mvnw.cmd clean verify`는 621개 실패·오류 0, skip 9와 JAR 패키징으로 통과했다. 종료 뒤 Testcontainers가 내려간 Hikari connection refused 로그와 Surefire self-fork 30초 정리 경고가 있었지만 Maven exit은 0이었다. | 이후 확장된 44개 scenario matrix와 Testcontainers-only isolation을 유지한다. 종료 경고가 실패·지연으로 바뀌면 별도 원인 분석을 한다. |
| 2026-09-21 | 운영자·개발자 도구 process E2E와 전체 backend 검증 | 실제 별도 JVM의 Catalog CLI·계좌 CLI에서 baseline 충돌·rollback·dry-run·confirm·redaction과 안전한 framework failure를 확인했다. hostile Hikari 환경변수도 Testcontainers child에 전달되지 않는다. focused 27개와 `mvnw.cmd clean verify` 608개가 실패·오류 0, skip 9로 통과했고 JAR를 패키징했다. 종료 뒤 Surefire가 30초 후 남은 test fork JVM을 정리했다는 경고가 있었지만 Maven exit은 0이었다. | 다음 변경에서도 operator process E2E를 유지한다. Surefire 종료 경고가 테스트 실패나 종료 지연으로 바뀌면 별도 원인 분석을 한다. |
| 2026-09-21 | 상세 사용자 흐름 backend E2E | `frontend-mock-catalog.json` strict 흐름 1개와 `development-catalog.json` sparse 후보 1개가 각각 임시 PostgreSQL에서 통과했다. 공간·지도·핀·장소, 공연·타임테이블, 티켓·스탬프, 관리자 refresh/logout, 혼잡도 동시성 흐름을 포함한다. 최신 `origin/main` 확인 뒤 `mvnw.cmd clean verify`는 603개 통과, 실패·오류 0, 기존 환경 의존 skip 9개였다. | 실제 출시 후보에는 strict property를 지정하고, 공지·굿즈·브라우저·배포 전용 게이트를 별도로 통과시킨다. |
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
| 운영자 CLI: 실제 main exit·출력·DB 결과 | `OperatorToolProcessE2eTest`(Catalog import·validate·publish·export·stale baseline·rollback과 계좌 dry-run·set·last-four 거절·clear·restore·redaction) |
| export/publish: 끼어든 게시·rollback 충돌 차단, semantic round-trip, legacy 차단, 최신 공연 catalog 보존 | `CatalogRevisionServiceIntegrationTest`, `CatalogWorkbenchIntegrationTest`(다른 축제 revision export·diff·publish 차단) |
| 사용자 흐름: 공간·지도·공연·티켓·스탬프·관리자 세션·혼잡도 연계 | `ReleaseReadinessHttpE2eTest`의 HTTP-01~09. 실제 candidate의 ID를 응답에서 읽어 관계를 검증한다. |
| 출시 후보 콘텐츠 관계 | `ReleaseReadinessHttpE2eTest`에 `festival.release-e2e.require-user-journey-content=true`를 지정한다. |
| 브라우저·proxy·실기기 동작 | backend E2E 범위 밖이며 web 저장소와 실기기 acceptance gate에서 검증한다. |
| 67 RPS 부하와 지표 기록 | `tools/load-test/run.ps1`의 `rate-67` 단계. 결과와 heap·GC·DB 지표는 `tools/load-test/README.md`에 있다. 로컬 결과이며 원격 용량은 확정하지 않았다. |

## 외부 의존성

- `fall-festival-admin`은 별도 프로젝트이며 로컬 포트 3001에서 기존 login/refresh/API
  client를 공유한다. 원격 관리자 proxy와 refresh cookie E2E는 후속 범위다.
- 팀 제공 PostgreSQL은 전용성이 보장되지 않았다. preflight 결과와 DB 제공자의 role
  발급 결과 없이는 role 분리 또는 migration 안전성을 주장할 수 없다.
- 실제 FestivalDay 운영 시각과 운영 자료는 승인 전이다. 코드의 local fixture 검증과
  원격 운영 검증을 구분한다.
