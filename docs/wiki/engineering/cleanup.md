# 데이터 정리 작업

[위키 홈](../README.md) · 읽는 때: 보관 기간 enforcement와 cleanup scheduler를 운영할 때

루트 Spring Boot 서비스의 cleanup은 `db` profile에서만 등록된다. 기본값은 스케줄링
비활성화와 dry-run이며, datasource가 없는 기본 profile의 공개 서비스 시작에는 영향을
주지 않는다.

## 현재 target

- `admin_audit_events`에서 현재 시각 기준 1년보다 오래된 행을 정리한다.
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
cleanup_run status=COMPLETED mode=DELETE target_count=1 eligible_count=42 deleted_count=42 duration_ms=...
cleanup_target target=admin_audit_events dry_run=false eligible_count=42 deleted_count=42 batch_count=1
```

운영자는 먼저 `FESTIVAL_CLEANUP_DRY_RUN=true`로 결과를 확인하고, 전용 역할의 권한과
대상 행 수를 검토한 뒤 delete schedule을 별도로 활성화한다. 원격 DB에 대한 migration
또는 임의 SQL 실행은 이 기능의 배포 절차가 아니다.
