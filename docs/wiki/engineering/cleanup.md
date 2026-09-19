# 데이터 정리 작업

[위키 홈](../README.md) · 읽는 때: 보관 기간 enforcement와 cleanup scheduler를 운영할 때

루트 Spring Boot 서비스의 cleanup은 `db` profile에서만 등록된다. 기본값은 스케줄링
비활성화와 dry-run이며, datasource가 없는 기본 profile의 공개 서비스 시작에는 영향을
주지 않는다.

## 현재 target

- `admin_audit_events`에서 현재 시각 기준 1년보다 오래된 행을 정리한다.
- `admin_idempotency_records`에서는 `COMPLETED` 상태이고 완료 시각에서 24시간이 지난
  replay 응답만 정리한다. `IN_PROGRESS` lease는 만료됐어도 이 작업이 삭제하지 않는다.
- `media_assets`에서는 상품에 한 번도 연결되지 않은 `GOODS_IMAGE`를 생성 24시간 뒤,
  상품에서 분리된 `GOODS_IMAGE`를 분리 1일 뒤 정리한다. 두 target 모두 현재
  `goods_images` 연관이 없는 행만 삭제하며 정확한 경계 시각의 행은 유지한다. 미디어
  저장소가 설정되지 않은 서버에는 target 자체가 등록되지 않는다.
- 한 번의 run은 최대 500행을 처리한다. `festival.cleanup.batch-size`는 1~500만 허용하며
  기본값은 500이다. 남은 행은 다음 scheduler run에서 처리해 한 transaction이 오래
  유지되지 않게 한다.
- 작업은 PostgreSQL `pg_try_advisory_xact_lock`을 먼저 획득한다. 다른 cleanup 실행이
  lock을 보유하면 기다리지 않고 `SKIPPED_LOCK_NOT_ACQUIRED`로 끝난다.
- dry-run은 eligible 행 수만 읽고 `deleted_count=0`을 반환한다. 결과와 로그에는 target,
  eligible count, deleted count, batch count, mode, status가 포함된다.

`CleanupTarget`는 등록 extension point다. 이후 보관 target은 해당 인터페이스를 구현한
Spring bean으로 추가하며 controller, API 계약 또는 migration을 추가하지 않고도 같은
lock·batch·결과 경계를 재사용한다.

파일처럼 DB 밖의 부수 효과가 있는 target은 행을 지운 뒤 `context.afterCommit(action)`으로
작업을 예약한다. 예약한 작업은 cleanup transaction이 **commit된 뒤에만** 실행되고,
transaction이 rollback되면 실행되지 않는다. dry-run에서는 아무것도 예약하지 않는다. 실패한
작업은 `FESTIVAL_CLEANUP_POST_COMMIT_MAX_ATTEMPTS`번까지 재시도하며, 끝내 실패해도 이미
commit된 삭제를 되돌리지 않고 결과의 `postCommit`과 `post_commit_failed_count`로 보고한다.
행은 이미 사라졌으므로 작업은 멱등이어야 한다. 일반 파일 target은
`CleanupPostCommitAction.deleteFile(target, path)`를 쓰고, 상품 미디어는
`MediaStorage.delete(festivalId, mediaId)`를 호출해 저장소의 경로 격리와 symlink 검사를
재사용한다. 없는 파일은 이미 삭제된 것으로 처리한다. 재시도 뒤에도 남은 파일은 소유 담당자가
별도 sweep으로 정리한다. 로그에는 예외 종류만 남기고 파일 경로는 남기지 않는다.

## 운영 설정

설정은 Spring relaxed binding을 사용한다. 아래 환경변수는 `db` profile과 실제 DB를
운영하는 별도 작업 프로세스에만 지정한다.

| 환경변수 | 기본값 | 설명 |
|---|---:|---|
| `FESTIVAL_CLEANUP_SCHEDULE_ENABLED` | `false` | scheduler 등록 여부 |
| `FESTIVAL_CLEANUP_DRY_RUN` | `true` | `false`일 때만 delete mode 요청 |
| `FESTIVAL_CLEANUP_SCHEDULE_INTERVAL_MS` | `86400000` | scheduler fixed delay |
| `FESTIVAL_CLEANUP_SCHEDULE_INITIAL_DELAY_MS` | `0` | 첫 실행 전 지연 |
| `FESTIVAL_CLEANUP_BATCH_SIZE` | `500` | 1~500행 |
| `FESTIVAL_CLEANUP_POST_COMMIT_MAX_ATTEMPTS` | `3` | commit 뒤 작업의 최대 시도 횟수(1~10) |
| `FESTIVAL_CLEANUP_POST_COMMIT_RETRY_DELAY_MS` | `1000` | commit 뒤 작업 재시도 간격 |
| `FESTIVAL_CLEANUP_DATASOURCE_URL` | 없음 | 전용 cleanup PostgreSQL URL |
| `FESTIVAL_CLEANUP_DATASOURCE_USERNAME` | 없음 | 전용 cleanup DB 사용자 |
| `FESTIVAL_CLEANUP_DATASOURCE_PASSWORD` | 없음 | 전용 cleanup DB 비밀값 |
| `FESTIVAL_CLEANUP_DATASOURCE_ROLE` | 없음 | 전용 cleanup 역할 이름; dedicated connection의 `current_user`와 일치해야 함 |

파괴적 스케줄은 dry-run을 명시적으로 끄고 전용 datasource의 URL·사용자·비밀번호·역할
이름을 모두 지정한 경우에만 등록된다. 하나라도 빠지면 scheduler가 생성되지 않으며,
명시적인 `DELETE` 실행도 `SKIPPED_UNSAFE_CONFIGURATION`으로 종료된다. 전용 설정이
없는 읽기·dry-run은 일반 application datasource를 사용한다.

cleanup datasource와 역할은 audit 행을 삭제할 수 있는 최소 권한으로 따로 만든다. DELETE
직전에 dedicated connection의 `current_user`가 설정한 역할 이름과 일치하는지 확인하며,
불일치하거나 연결할 수 없으면 `SKIPPED_UNSAFE_CONFIGURATION`으로 끝난다. 실제 DELETE
grant provisioning은 운영 환경에서 관리한다. URL, 사용자, 비밀번호와 역할 이름은 로그에 남기지 않는다. 로그 수집기는 다음 key/value
이벤트를 metric label로 사용할 수 있다.

```text
cleanup_run status=COMPLETED mode=DELETE target_count=1 eligible_count=42 deleted_count=42 post_commit_count=0 post_commit_failed_count=0 duration_ms=...
cleanup_target target=admin_audit_events dry_run=false eligible_count=42 deleted_count=42 batch_count=1
```

운영자는 먼저 `FESTIVAL_CLEANUP_DRY_RUN=true`로 결과를 확인하고, 전용 역할의 권한과
대상 행 수를 검토한 뒤 delete schedule을 별도로 활성화한다. 원격 DB에 대한 migration
또는 임의 SQL 실행은 이 기능의 배포 절차가 아니다.
