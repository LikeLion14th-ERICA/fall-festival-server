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
| 1 | 공통 동시성 기반 | 진행 중 | preflight, request ID/conditional response, idempotency 결과를 통합하고 API 계약·CORS·테스트를 완성한다. |
| 2 | 정리 작업 틀 | 대기 | PR 1의 idempotency 테이블과 AdminAuditEvent retention을 사용한다. |
| 3 | 관리자 혼잡도 백엔드 | 대기 | 운영 일정 승인 전에는 원격 DB 검증을 주장하지 않는다. |
| 4 | 계좌 운영 설정 | 대기 | DB provider role 발급·provisioning 절차를 먼저 확정한다. |
| 5 | 티켓 계좌 분리와 polling | 대기 | 계좌 설정 배포·등록 확인 뒤에 static catalog 읽기를 전환한다. |
| 6 | catalog export·게시 보호 | 대기 | `base_revision_id`와 legacy validation report를 구현한다. |
| 7 | 로컬 catalog workbench | 대기 | export/publish 보호와 role 분리 뒤에 구현한다. |

현재 통합 branch는 `codex/goal-ops-catalog`이다. 독립 작업 branch는 통합 전까지
각각 전용 worktree에서 유지한다. 이 표는 각 통합 commit, 실패, 외부 의존성 변화 뒤에
반드시 갱신한다.

## 고정 안전 규칙

- Flyway 번호는 병합 직전 최신 `main`의 다음 번호로 다시 정한다. 병합 전 공유 원격
  개발 DB에는 적용하지 않는다.
- OpenAPI 생성물은 손으로 병합하지 않는다. source 충돌을 해결한 뒤
  `npm run generate`, `npm run check`로 재생성한다.
- 모든 catalog/account CLI는 `spring.flyway.enabled=false`로 실행한다. preflight는
  별도 plain JDBC 도구이며 catalog audit을 남기지 않는다.
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
| 2026-09-18 | 최신 원격 기준 확인 | `origin/main`은 `d3a3e8e`(PR #28) | PR 1 구현을 시작한다. |
| 2026-09-18 | 원격 DB 상태 | 실행하지 않음 | 구현 중·병합 전에는 remote DB mutation을 금지한다. |

새 행에는 실행한 명령의 요약, 실제 결과, 미실행 사유를 남긴다. 실패한 검증은 삭제하지
않고 원인과 후속 조치를 기록한다.

## 외부 의존성

- `fall-festival-admin`은 별도 프로젝트이며 로컬 포트 3001에서 기존 login/refresh/API
  client를 공유한다. 원격 관리자 proxy와 refresh cookie E2E는 후속 범위다.
- 팀 제공 PostgreSQL은 전용성이 보장되지 않았다. preflight 결과와 DB 제공자의 role
  발급 결과 없이는 role 분리 또는 migration 안전성을 주장할 수 없다.
- 실제 FestivalDay 운영 시각과 운영 자료는 승인 전이다. 코드의 local fixture 검증과
  원격 운영 검증을 구분한다.
