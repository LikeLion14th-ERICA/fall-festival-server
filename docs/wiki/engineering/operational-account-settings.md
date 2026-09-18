# 계좌 운영 설정

[위키 홈](../README.md) · 읽는 때: `TICKET`·`GOODS` 계좌 변경, 역할 provisioning, 계좌 CLI 또는 retention 변경

## 범위와 모델

`operational_account_settings`는 `(festival_id, purpose, scope_id)` 현재값과 증가하는 `version`을
저장한다. `purpose`는 축제 단위의 `TICKET`·`GOODS`와 부스 단위의 `SPACE`(V23)이며, `SPACE`의
`scope_id`는 부스 API id, 나머지는 빈 문자열이다. `SPACE`는 서비스 내부 은행 식별자
`bank_code`와 토스 연결 노출 여부 `toss_link_enabled`를 가지며 송금 링크는 가질 수 없다. 공개
조회는 [부스 상세](../product/spaces.md#부스-계좌-송금-안내)의 `bankTransfer`다. 상태는 공개 가능한 `CONFIGURED`와 표시를
중단하는 `UNCONFIGURED`다. 은행명·계좌번호·예금주·선택 송금 링크는 catalog revision 밖의
운영 설정이다. 이 PR은 HTTP 관리자 쓰기와 공개 조회를 만들지 않는다.

V15의 trigger는 INSERT/UPDATE/DELETE마다 `operational_account_setting_history`에 전후 값을
append-only로 남긴다. 이력에는 실제 login DB role인 `session_user`, CLI가 받은 작업자 표시명,
사유, 근거 ID와 시각이 남는다. `clear`는 현재 행을 `UNCONFIGURED`로 새 version에 바꾸며,
일상 절차에서 현재 행을 DELETE하지 않는다. 권한 있는 직접 SQL repair로 삭제가 생겨도 마지막
이력이 version watermark가 되어 다음 INSERT는 더 큰 version을 사용하고, 삭제 전 값은
`restore-version`으로 선택할 수 있다.

계좌 원문은 current/history table에만 저장한다. `AdminAuditEvent`와 `CatalogRevisionAudit`에
복사하지 않는다. 계좌 변경 완료 log는 transaction commit 뒤 purpose·version·시각과
`change_count=1`만 남긴다. 운영 지표 `operational_account_changes_total`은 이 event의
`change_count`를 합산해 만들며, label은 `purpose`만 쓴다. version과 시각은 event field로
유지한다. 로그 수집기가 연결되기 전에는 이 지표를 운영 검증 완료로 간주하지 않는다.

## 실행 전 gate

1. 최신 artifact를 package하고 전용 읽기 전용 role로
   [DB 사전 점검](database-preflight.md)을 실행한다. `STOP_AND_REVIEW`면 migration, CLI,
   import, publish를 실행하지 않는다.
2. 병합 전 공유 원격 개발 DB에는 migration을 적용하지 않는다. 병합 직전 `origin/main`의
   다음 Flyway 번호를 다시 배정한다.
3. DB 제공자에게 migration, runtime, cleanup, account operator, catalog export, catalog
   publish, preflight role을 요청한다. 단일 계정만 제공하는 개발 DB에서는 역할 분리를
   강제할 수 없음을 기록하고, 운영 배포는 분리된 role 없이는 진행하지 않는다.
4. migration role로 schema를 변경한 뒤 provider가 승인한 이름을 넣어
   [`tools/database/provision-operational-account-roles.sql`](../../../tools/database/provision-operational-account-roles.sql)을
   `psql -v` 변수로 실행한다. 이 script는 Flyway migration이 아니며 role 이름을 저장소에
   고정하지 않는다.

runtime role은 current settings만 SELECT한다. account operator role은 current settings의
SELECT/INSERT/UPDATE와 history SELECT만 가진다. cleanup role은 current settings의
`festival_id/purpose/version`과 history SELECT/DELETE만 가진다. catalog export/publish role은
두 table에 접근할 수 없고, `ticket_guide_revisions`에도 table 전체가 아니라 계좌·송금 링크를
제외한 열 권한만 받는다. 그래서 legacy 계좌 열을 읽거나 `SELECT *`를 실행하면 권한 오류로
멈춘다. legacy `ticket_guide` singleton table은 두 role 모두 접근할 수 없다.
history UPDATE/DELETE는 cleanup role 경로 외에 grant하지 않는다.
table/function owner와 DB superuser는 PostgreSQL 소유자 권한으로 이 제한을 우회할 수 있으므로
별도 break-glass 접근으로 기록한다.

## CLI 사용

`AccountSettingsCliApplication`은 non-web entry point이며 Flyway auto-configuration을
구조적으로 제외한다. `SPRING_FLYWAY_ENABLED=true`가 남아 있어도 이 CLI가 migration을 실행할
수 없다. `SPRING_DATASOURCE_*`에는 runtime role이 아닌 별도 account operator role을 주입한다.
비밀값을 command line, shell history, CI, 로그에 넣지 않는다.

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress package
java '-Dloader.main=dev.espero.festival.AccountSettingsCliApplication' -cp target/fall-festival-server-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher set --festival-id=<uuid> --purpose=TICKET --expected-version=<current-version> --input-file=<secure-local-json> --last-four=<four-digits>
```

부스 계좌는 `--purpose=SPACE --space-id=<부스 API id>`로 지정한다. 부스마다 version이 따로
증가한다.

```powershell
java '-Dloader.main=dev.espero.festival.AccountSettingsCliApplication' -cp target/fall-festival-server-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher set --festival-id=<uuid> --purpose=SPACE --space-id=<space-id> --expected-version=<current-version> --input-file=<secure-local-json> --last-four=<four-digits>
```

input JSON은 `bankName`, `accountNumber`, `accountHolder`, `transferLinkUrl`, `bankId`,
`tossLinkEnabled`만 받는다. `SPACE`는 `bankId`(소문자·숫자·하이픈)가 필수이고
`transferLinkUrl`을 쓸 수 없으며, `TICKET`·`GOODS`는 `bankId`와 `tossLinkEnabled`를 쓸 수 없다.
`bankName`은 공개 응답의 `bankDisplayName`, `accountHolder`는 `accountHolderName`이 된다. 파일은
regular file·16 KiB 이하·unknown field 없음이어야 하며 symlink는 거절한다. 사용 뒤 운영자가
승인된 비밀 관리 절차로 파일을 제거한다. 링크는 선택 사항이지만 HTTPS, 기본 포트, userinfo와
fragment 없음, `ACCOUNT_TRANSFER_LINK_ALLOWED_HOSTS`의 정확한 host 중 하나여야 한다. 빈
allowlist에서는 링크가 있는 설정을 저장할 수 없다.

기본은 dry-run이다. 출력은 state, version, 바뀐 field 이름, 계좌 끝 네 자리와 링크 설정 여부만
비교하며 은행명·계좌번호·예금주·링크 원문을 출력하지 않는다. 적용에는 `--confirm`와
`--actor`, `--reason`, `--evidence-id`가 모두 필요하다. `set`에는 새 계좌 끝 네 자리 확인도
필요하다. `restore-version`은 source version과, 복원 대상이 CONFIGURED이면 끝 네 자리 확인을
요구한다. `clear`는 `UNCONFIGURED`로 새 version을 만든다.

## 복구와 retention

`restore-version`은 이전 값을 덮어쓰지 않고 현재 expected version을 확인한 뒤 새 version으로
재적용한다. 두 CLI가 동시에 변경하면 하나는 expected version 불일치로 실패한다. catalog
rollback은 계좌 값을 rollback하지 않는다.

## 티켓 read 전환 runbook

`/api/v2/ticket-guide`는 catalog의 가격·일정·안내·map target과 현재 `TICKET` 설정을 합쳐
응답한다. 계좌를 등록하기 전에는 일정이 완전해도 `status: UNCONFIGURED`이고 `account`는
null이므로, 아래 순서를 지켜야 사용자에게 빈 계좌가 노출되지 않는다.

1. account operator role로 `set --purpose=TICKET`을 **dry-run**으로 실행해 state, version,
   바뀔 field 이름과 끝 네 자리를 확인한다.
2. `--confirm`과 `--last-four`, `--expected-version`, `--actor`, `--reason`, `--evidence-id`로
   적용한다. 값 자체는 출력·로그에 남지 않는다.
3. 기존 catalog revision의 legacy 계좌 열과 새 설정이 같은 계좌인지 **값을 출력하지 않고**
   대조한다. 끝 네 자리와 은행 일치 여부만 운영자가 눈으로 확인하며, 다르면 전환을 멈춘다.
4. read 전환 binary를 배포한다. 배포 뒤 `/api/v2/ticket-guide`가 송금 시간 안에서
   `TRANSFER_OPEN`과 `account`, `paymentSettingsVersion`을 반환하는지 확인한다.
5. 계좌 분리 이전 binary로의 rollback은 금지한다. 그 binary는 catalog의 legacy 열을 다시
   읽으므로 설정에서 바꾼 계좌와 어긋난 값을 노출할 수 있다. 문제가 생기면 계좌 설정을
   `clear`하거나 `restore-version`으로 되돌린다.

계좌를 바꾸면 version이 올라가고 응답 본문의 `paymentSettingsVersion`이 바뀌므로 ETag도
바뀐다. 프런트의 15초 polling은 다음 주기에 새 표현을 받는다. 서버는 응답을 캐시하지 않고
`Cache-Control: private, no-cache`를 보낸다.

통합 cleanup 단계는 history를 1년 뒤 batch 삭제하되, 각 `(festival_id, purpose)`의 최신 event를
남긴다. 이는 현재 설정의 근거이면서 직접 SQL DELETE 뒤에도 version을 재사용하지 않는
watermark다. cleanup은 current table key/version만 읽고 account 원문을 읽지 않는다. backup에는
삭제 이력이 남을 수 있으며, 복원 뒤 cleanup을 다시 실행한다.

## 보안과 검증

한 사람이 CLI 변경을 확정하는 것은 사용자 결정이다. 빠른 현장 변경을 위해 선택했지만 그
사람의 실수나 자격증명 유출이 즉시 공개 계좌 변경으로 이어진다. CLI의 dry-run, expected
version, 끝 네 자리 확인, trigger history, 별도 DB role과 사후 log가 그 위험을 줄이지만
2인 승인은 제공하지 않는다.

Testcontainers 검증은 trigger history, direct SQL 기록, direct DELETE 뒤 monotonic version과
restore, history 권한 거부, cleanup 최소 SELECT, isolated Flyway schema, dry-run redaction,
allowlist 및 CLI Flyway exclusion을 포함한다. 실제 원격 DB에는 이 검증이 migration 권한을
부여하지 않는다.
