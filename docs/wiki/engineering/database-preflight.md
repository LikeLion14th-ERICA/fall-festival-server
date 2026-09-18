# 원격 DB 읽기 전용 사전 점검

[위키 홈](../README.md) · 읽는 때: 팀 제공 DB 최초 연결, migration·catalog import·publish 전

`DatabasePreflightApplication`은 PR #28의 `READ_ONLY_DATABASE_PREFLIGHT` 전용 진입점이다.
JDK와 PostgreSQL JDBC driver만 사용하며 Spring context, `db` profile, Flyway, 웹 서버,
scheduler, 관리자 bootstrap, catalog service·audit를 시작하지 않는다. `.env`도 읽지 않는다.
애플리케이션이나 `CatalogCliApplication`을 먼저 실행해서 DB 연결을 확인하지 않는다.

## 실행

저장소 루트에서 현재 코드를 패키징한다. 이 명령의 테스트는 로컬 Testcontainers만 사용하며,
팀 제공 DB를 연결하지 않는다.

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
java '-Dloader.main=dev.espero.festival.preflight.DatabasePreflightApplication' -cp target/fall-festival-server-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher --help
```

대상 DB 소유자에게 읽기 전용 조사 계정과 schema를 확인하고 아래 환경변수를 안전하게
주입한다. 기존 `SPRING_DATASOURCE_*`나 `FESTIVAL_ID`를 자동으로 사용하지 않으므로,
개발 셸에 남은 다른 DB 자격증명으로 연결하지 않는다. 계정은 필요한 schema의 metadata와
table SELECT만 허용한다. 쓰기·DDL 권한과 앱 runtime/admin 계정을 필요로 하지 않는다.

| 변수 | 값 |
|---|---|
| `PREFLIGHT_DATASOURCE_URL` | `jdbc:postgresql://<host>:<port>/<database>` |
| `PREFLIGHT_DATASOURCE_USERNAME` | 제공자가 승인한 읽기 전용 role |
| `PREFLIGHT_DATASOURCE_PASSWORD` | secret store 또는 숨김 입력으로 주입한 비밀번호 |
| `PREFLIGHT_SCHEMA` | 조사할 schema 이름. 생략하지 않는다. |
| `PREFLIGHT_FESTIVAL_ID` | 이미 확인한 회차 UUID. 최초 조사에서는 생략 가능 |

TLS는 제공 조건에 따라 URL의 `sslmode`, `sslrootcert`로 설정한다. URL 안의 user/password,
`options`, `readOnlyMode`, `currentSchema`, driver factory 같은 다른 옵션은 거절한다.
실제 URL·credential을 command line, 저장소, CI log에 적지 않는다.

```powershell
java '-Dloader.main=dev.espero.festival.preflight.DatabasePreflightApplication' -cp target/fall-festival-server-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
$preflightExit = $LASTEXITCODE
```

macOS/Linux에서도 같은 `java` 명령을 사용하고 패키징만 `sh ./mvnw ... verify`로 바꾼다.
`java -jar`는 웹 앱을 시작하므로 사전 점검 명령으로 사용하지 않는다.

## 조사 범위와 판정

- PostgreSQL version, 현재 database/current user/session user, schema·owner·접근 가능 여부,
  일반/partition table·view·foreign table 이름과 SELECT 권한을 조사한다.
- 대상 schema의 Flyway version/type/script/checksum/success를 현재 artifact에 포함된 SQL과
  비교한다. SQL 파일은 텍스트로만 읽으며 Flyway를 호출하거나 migration을 실행하지 않는다.
- 축제 ID·timezone, revision ID·회차·번호·state와 published 상태를 출력한다. 카탈로그
  table은 `EXISTS`로 데이터 유무만 조사한다. 제목·본문·계좌·인증 정보·감사 본문은 조회하지 않는다.
- 같은 DB의 다른 client session 수와 상태 접근 가능 여부를 조사한다. session의 SQL,
  주소, application name은 출력하지 않는다.
- 하나의 `REPEATABLE READ`, read-only transaction을 사용하고 성공·실패 모두 rollback한다.
  연결 5초, socket 15초, 쿼리 5초·lock 1초, 결과 집합 501행 상한으로 제한한다.

exit code **2**, `status=STOP_AND_REVIEW`는 다음 경우에 반환한다.

- 알 수 없는 schema/table/view, 대상 schema 접근 불가 또는 권한·조회 실패
- 다른 DB client session, 다른 user schema, 결과 상한 초과로 공유 여부나 전체 상태가 불명확함
- 실패·누락·중복·알 수 없는 Flyway 이력, script/checksum 불일치, 이력과 table 목록 불일치
- 기존 festival/revision/catalog 데이터, 선택한 festival 부재 또는 중복 published revision
- 현재 통합 테스트가 검증한 PostgreSQL major 16·17 이외 버전
- 환경설정 오류, 연결 실패, SQL resource inventory를 읽을 수 없음

빈 호환 schema 등 자동 차단 항목이 없으면 exit code **0**,
`status=READ_ONLY_DATABASE_PREFLIGHT_COMPLETE`다. 이는 읽기 전용 조사의 완료만 뜻한다.
**모든 결과에 `mutationAuthorized=false`를 출력한다.** Metadata와 현재 session만으로 다른
팀의 비활성 사용·예약 작업·schema 소유권을 증명할 수 없다. 제공자/팀의 사용 범위 확인,
보고서 검토와 현재 작업의 변경 권한 확인까지 마쳐야 mutation 게이트를 통과한다.

## 중단 뒤 처리와 변경 게이트

1. 새 조사 결과와 실행 artifact commit을 기록한다. 보고서에는 내부 DB/schema/role/회차 ID가
   있으므로 접근 제한된 운영 기록에 보관하며 공개 CI log나 저장소에 넣지 않는다.
2. `STOP_AND_REVIEW`이면 migrate/import/publish를 실행하지 않는다. 대상 DB 제공자와 기존
   schema·축제·게시 revision·다른 팀 사용 범위를 확인한다. 기존 데이터는 삭제·repair·baseline·
   republish로 자동 해결하지 않는다. 기존 catalog의 사용이 승인되면 그 검토 근거와
   baseline revision을 후속 변경 기록에 연결한다.
3. schema/role 분리가 필요하면 제공자에게 요청한다. 단일 계정 환경의 한계는 기록하며
   preflight가 권한 분리를 보장한다고 주장하지 않는다.
4. migration은 최신 main과 병합한 파일만 대상으로 한다. 공유 원격 개발 DB에 미병합
   migration을 적용하지 않는다. import/publish 직전에도 다시 조사하고, catalog의 festival
   행 잠금 안에서 baseline revision을 별도로 검사한다. 사전 점검은 동시 게시 제어를 대체하지 않는다.
5. 후속 catalog/account CLI는 Flyway를 명시적으로 끈 상태로 실행한다. web backend를 통한
   자동 migration이나 catalog `validate`의 감사 쓰기를 읽기 전용 점검으로 사용하지 않는다.

보고서는 계좌와 콘텐츠 원문을 포함하지 않는다. 오류는 분류와 허용된 SQLSTATE만 출력하고
JDBC URL·credential·서버 예외 메시지·stack trace를 출력하지 않는다.

## 로컬 검증

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=DatabasePreflightApplicationTest,DatabasePreflightIntegrationTest,DatabasePreflightPostgresql17IntegrationTest' test
```

기존 전체 통합 검증은 별도 `postgres:16-alpine` Testcontainers DB를 사용한다. 추가된
`postgres:17-alpine` 검증은 빈 schema에서 preflight가 mutation 없이 완료되는지와 현재 포함된
모든 versioned migration 적용 후 실제 checksum 대조, 읽기 전용 SELECT·rollback, 감사·카탈로그·공지
table 무변경을 확인한다. 지원 major는 정확히 16과 17이며 다른 major는 계속
`POSTGRESQL_VERSION_REQUIRES_REVIEW`로 중단한다. Docker가 없으면 통합 검증이 skip되므로 실행
결과의 skipped 개수를 반드시 보고한다.
