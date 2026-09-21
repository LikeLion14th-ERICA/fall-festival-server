# 검증 명령과 CI

[위키 홈](../README.md) · 읽는 때: 코드·의존성·배포 변경 검증

## 서비스 백엔드

이 저장소는 `LikeLion14th-ERICA/fall-festival-server` 백엔드 저장소다.
루트 `pom.xml`은 Java 21 기준 Spring Boot 4.1.1 서버를 구성한다.
Maven Wrapper 3.3.4가 Maven 3.9.11과 배포 SHA-256을 고정한다.
별도 Maven 설치 없이 저장소 루트에서 실행한다.

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

macOS/Linux에서는 `sh ./mvnw --batch-mode --no-transfer-progress verify`를 사용한다.
루트 backend 변경의 최소 검증 명령이며 테스트와 실행 가능한 JAR 패키징을 포함한다.
테스트 위치는 `src/test/java`다. DB 및 운영 배포는 아직 구성하지 않았다.

`.github/workflows/backend-ci.yml`의 검사 이름은 `backend-verify (Java 21)`과
`backend-verify (Java 25)`다. GitHub에서 실행된 후 저장소 관리자가 두 검사를
보호 규칙의 필수 검사로 지정해야 한다. 로컬 실행이 GitHub CI 성공을 뜻하지 않는다.
실행과 환경변수는 [루트 README](../../../README.md)를 따른다.
초기 구성의 실제 결과와 미실행 항목은 [개발 준비 기록](../../backend-setup-verification.md)에 있다.

원격 DB 조사에는 웹 앱·catalog CLI 대신 독립된 `DatabasePreflightApplication`을 사용한다.
실행 명령, 읽기 전용 환경변수, 중단 판정과 로컬 PostgreSQL 검증 명령은
[DB 사전 점검 runbook](../engineering/database-preflight.md)에 있다.

### 릴리스 후보 backend E2E

`ReleaseReadinessHttpE2eTest`는 Docker의 임시 PostgreSQL에만 연결한다. 테스트는
Flyway를 적용하고 후보 catalog manifest를 실제 catalog CLI로 import·publish한 뒤,
랜덤 포트의 서버를 새로 기동한다. `/readyz`와 published snapshot, 후보 revision을
가진 공개 catalog 경로와 관리자 경계를 하나의 릴리스 게이트로 확인한다. 응답에서 ID를
읽으므로 후보 데이터의 ID가 바뀌어도 관계가 보존되면 검사가 유지된다.
Docker 엔진이 없으면 이 테스트는 skip하지 않고 실패한다.

기본 흐름 fixture는 `dev/catalog/frontend-mock-catalog.json`이다. 공간·전체/구역 지도·핀·장소·
공연·타임테이블·티켓·스탬프의 연결을 검사하는 로컬 전용 fixture이며, 원격 개발 DB나 운영
데이터로 import·publish하지 않는다.

| ID | 사용자 흐름 | 주요 검증 |
| --- | --- | --- |
| HTTP-01 | 서비스 진입 | `/healthz` → `/readyz` → `/api/v2/config`, published revision, `mock=false`, KST 메타데이터 |
| HTTP-02 | 부스·공간 탐색 | 공간 목록 → 분류 필터 → 공간 상세 |
| HTTP-03 | 공간에서 지도 이동 | 공간 `mapTarget` → AREA 지도 → 현재 버전 핀 → 장소 상세 → 원래 공간 |
| HTTP-04 | 전체·구역 지도 탐색 | overview의 AREA 핀 → 대상 AREA 지도, 필터와 PLACE 핀 관계, stale `mapVersion` 409 후 최신 핀 재조회 |
| HTTP-05 | 공연 탐색 | config 기본 날짜 → lineup → artist → performance → timetable, 출연진·공연 ID와 순서 관계 |
| HTTP-06 | 티켓·스탬프 확인 | ticket guide의 `UNCONFIGURED`·strong ETag/304·map target, stamp 제목·보상·기간·일일 한도 |
| HTTP-07 | 관리자 세션 | 로그인 → `/admin/me` → refresh rotation → 이전 refresh 거부 → logout 뒤 refresh 거부 |
| HTTP-08 | 혼잡도 운영 | 공개 조회 → 관리자 변경 → 같은 idempotency key replay → 공개 반영 → 같은 단계 재선택의 no-op |
| HTTP-09 | 충돌·확인 처리 | stale `If-Match`의 409, FULL 확인 누락 422, 확인 뒤 FULL 반영과 감사 건수 |
| HTTP-10 | locale·query 오류 | 미게시 locale의 `LOCALE_NOT_READY`, 미지원·중복 query의 `INVALID_QUERY`, 후보 meta와 안전한 오류 envelope |
| HTTP-11 | 조건부 읽기 호환성 | ticket guide의 strong ETag에 weak validator와 다중 `If-None-Match` 값을 보내도 304·새 request ID·cache 지시자가 일관됨 |
| HTTP-12 | 관리자 입력 경계 | 허용하지 않거나 누락된 Origin의 credential 발급 거절, 잘못된 비밀번호·손상 access token·malformed JSON·access token 없는 logout의 안전한 오류 |
| HTTP-13 | release E2E datasource 격리 | Hikari·JNDI override가 있어도 후보 import helper와 HTTP context가 Testcontainers datasource만 사용 |
| HTTP-14 | 전체 후보 탐색 | 모든 선언 날짜·ARTIST/CONTEST 목록의 반복 응답·순서·상세 관계와 노출된 모든 공간 category filter·중복 없음 |
| HTTP-15 | 계좌 CLI의 즉시 반영 | 별도 Account CLI의 TICKET set·clear가 실행 중 server의 ticket guide·version·ETag/304에 즉시 반영되고 마감 시 계좌를 숨김 |
| HTTP-16 | 혼잡도 동시 변경 | 실제 admin HTTP 요청 두 개의 같은 key/ETag 경쟁에서 한 번만 저장·감사되고 replay·key 재사용 거절이 보존됨 |
| HTTP-17 | 게시·재시작·rollback lifecycle | A 게시 → 실행 server의 A snapshot 유지 → 재시작의 B 노출 → expected-current rollback → 재시작의 새 revision A 복원과 동적 상태 보존 |
| HTTP-18 | 공개 입력 오류 후 복구 | 잘못된 날짜·분류·지도 query와 존재하지 않는 공간·지도·장소·출연진·공연이 안정 오류·후보 meta를 내고, 잘못된 admin bearer가 공개 탐색을 막지 않으며 다음 config 조회가 복구됨 |
| HTTP-19 | 축제 날짜 경계 | 첫 FestivalDay 전과 마지막 FestivalDay 후에 config 기본 날짜와 기본 lineup 날짜가 각각 첫째·마지막 날로 함께 고정됨 |
| HTTP-20 | 미게시 축제 배포 | 다른 축제의 seed가 있어도 선택 회차에 published revision이 없으면 `/healthz`는 살아 있고 `/readyz`·공개 config는 안전한 `CATALOG_NOT_READY`로 실패 |
| HTTP-21 | 공개 읽기 rate limit | trusted proxy client 단위 429·`Retry-After`·안전 envelope, 다른 client의 독립 bucket, clock 회복 뒤 재조회와 서버 생성 request ID를 실제 HTTP로 확인 |
| HTTP-22 | 관리자 로그인 rate limit | 잘못된 비밀번호 추측이 trusted proxy client 단위로 제한되고 다른 client·refill 뒤에는 다시 인증 오류로 처리되며 cookie를 발급하지 않음 |

위 HTTP-01~22는 서로 다른 출시 위험을 나타내는 **22개 시나리오**다. JUnit test
method는 관계된 요청을 한 transaction·server lifecycle 안에서 묶으므로 시나리오 수와
method 수가 같지 않다.

기본 흐름 fixture를 단독 실행하려면 다음을 사용한다.

```powershell
cmd /d /c "mvnw.cmd --batch-mode --no-transfer-progress -Dtest=ReleaseReadinessHttpE2eTest test"
```

실행 중인 server·별도 계좌 CLI·동시 관리자 요청·게시 lifecycle까지 포함한 HTTP release
E2E는 다음 명령으로 실행한다.

```powershell
cmd /d /c "mvnw.cmd --batch-mode --no-transfer-progress -Dtest=ReleaseReadinessHttpE2eTest,OperationalAccountPropagationE2eTest,CrowdingConcurrencyE2eTest,CatalogPublicationLifecycleE2eTest,AdminSessionReleaseE2eTest,ReleaseFailureModesHttpE2eTest test"
```

다른 후보 manifest는 경로를 시스템 프로퍼티로 준다. 이 기본 모드에서는 빈 공간·지도와
`UNCONFIGURED` 티켓처럼 계약상 유효한 sparse 후보도 검사한다. 실제 사용자 흐름 콘텐츠가
필수인 출시 후보에는 `festival.release-e2e.require-user-journey-content=true`를 추가한다.
이 strict 모드는 최소 하나의 공간·대표 지도 경로·PLACE 핀·AREA 핀·공연·타임테이블 관계가
없으면 실패한다. 공백이 있는 경로는 PowerShell에서 값을 따옴표로 감싼다.

```powershell
cmd /d /c "mvnw.cmd --batch-mode --no-transfer-progress -Dfestival.release-e2e.manifest=<candidate-manifest-path> -Dtest=ReleaseReadinessHttpE2eTest test"
```

```powershell
cmd /d /c "mvnw.cmd --batch-mode --no-transfer-progress -Dfestival.release-e2e.manifest=<candidate-manifest-path> -Dfestival.release-e2e.require-user-journey-content=true -Dtest=ReleaseReadinessHttpE2eTest test"
```

이 검사는 원격 개발 DB, 계정, 배포 환경을 읽거나 변경하지 않는다. 원격 DB의
`DatabasePreflightApplication`, 제공자 role provisioning, 실제 배포 smoke 검증과
운영 승인 절차는 별도 릴리스 조건으로 유지한다. 공지·굿즈 API·UI·미디어는 이 게이트의
범위가 아니다. 실제 브라우저의 navigation/back 상태, Secure·SameSite cookie 동작, 관리자
proxy와 CORS, 모바일 viewport, 온라인 복귀·polling backoff는 web 저장소와 실기기 acceptance
gate에서 검증한다.

### 운영자·개발자 도구 E2E

`OperatorToolProcessE2eTest`는 Docker의 새 PostgreSQL database마다 실제 별도 JVM으로
`CatalogCliApplication`과 `AccountSettingsCliApplication`을 실행한다. 테스트 classpath로
main entry point를 실행하는 E2E이며, 원격 DB·현재 셸의 datasource·계좌값을 사용하지 않는다.
패키징된 JAR의 `PropertiesLauncher` 명령은 [각 운영 runbook](../engineering/catalog-workbench.md)에서
따로 확인한다.

| ID | 운영 흐름 | 주요 검증 |
| --- | --- | --- |
| OPS-01 | Catalog CLI 정상 흐름 | import → validate → publish → export가 실제 exit code와 PostgreSQL published pointer·감사 기록을 함께 만족 |
| OPS-02 | 순차 게시·rollback 충돌 | 같은 baseline의 A/B draft 중 A 게시 뒤 B의 publish 거절, archived A의 rollback 성공, stale expected-current rollback 거절 |
| OPS-03 | Catalog CLI 안전 실패 | malformed revision이 exit 1과 안정 오류를 내고 stack trace·DB URL·비밀번호를 출력하지 않음 |
| OPS-04 | 계좌 CLI 변경 | set dry-run 무변경, confirm set, 잘못된 끝 네 자리 거절, clear·restore-version의 증가 version·trigger history·민감값 redaction |
| OPS-05 | DB 사전 점검 | `DatabasePreflightIntegrationTest`와 read-only role child JVM이 JDK+PostgreSQL driver만의 read-only 조사·`STOP_AND_REVIEW`·Flyway history 무변경을 검증 |
| OPS-06 | 로컬 workbench | `CatalogWorkbenchIntegrationTest`가 HTTP token·Host·Origin·role 경계와 다른 축제 revision의 export·diff·publish 거절을 검증 |
| OPS-07 | 손상 draft 복구 | 불완전 draft publish가 pointer·감사를 바꾸지 않고, 같은 baseline의 정상 replacement가 다음 별도 process에서 게시됨 |
| OPS-08 | Catalog write role 경계 | SELECT-only PostgreSQL role의 import가 실패하고 draft·catalog audit을 남기지 않음 |
| OPS-09 | 계좌 입력·version 무결성 | 예상 밖 JSON field와 stale expected-version이 무변경으로 거절되고, 같은 값 set은 version·history를 늘리지 않음 |
| OPS-10 | 계좌 write role 경계 | SELECT-only role이 Account CLI set·clear·restore를 실행해도 current setting·version·history를 바꾸지 못함 |
| OPS-11 | standalone preflight 실패 | 기존 catalog의 `STOP_AND_REVIEW`와 잘못된 datasource 설정의 `CONFIGURATION_INVALID`가 별도 process에서 비밀값 없이 출력됨 |
| OPS-12 | hostile logging 환경 | Hikari·Spring package DEBUG 환경에서도 CLI가 JDBC URL·비밀번호·stack trace를 출력하지 않음 |
| OPS-13 | import 감사 실패 원자성 | import의 마지막 audit insert가 실패하면 draft·모든 revision 하위 행·audit이 함께 rollback되고, trigger 해제 뒤 재시도는 성공 |
| OPS-14 | publish 감사 실패 원자성 | publish의 마지막 audit insert가 실패하면 기존 published pointer·draft state·catalog row가 보존되고, 재시도만 새 published revision을 만듦 |
| OPS-15 | rollback 감사 실패 원자성 | rollback 중 새 revision 복제와 ROLLBACK audit 뒤의 publish audit이 실패하면 복제·두 audit·pointer가 함께 rollback되고 재시도는 성공 |
| OPS-16 | 독립 CLI publish 경쟁 | 실제 두 JVM이 같은 festival 행 잠금에서 대기한 뒤 한 publish만 승리하고 다른 draft는 `BASE_REVISION_CONFLICT`·부수 audit 없음으로 끝남 |
| OPS-17 | 독립 CLI publish/rollback 경쟁 | 실제 publish와 rollback JVM이 같은 잠금에서 직렬화되어 승자만 새 published revision·필요 audit을 남기고 패자는 기준 revision 충돌로 끝남 |

위 OPS-01~17은 **17개 시나리오**다. HTTP 22개와 합쳐 현재 backend release
E2E 시나리오는 **39개**다.

운영 도구 focused 검증은 다음 명령으로 실행한다.

```powershell
cmd /d /c "mvnw.cmd --batch-mode --no-transfer-progress -Dtest=OperatorToolProcessE2eTest,OperationalReleaseGateE2eTest,CatalogCliRunnerTest,CliFlywayIsolationIntegrationTest,DatabasePreflightIntegrationTest,CatalogWorkbenchIntegrationTest test"
```

이 검사는 실제 원격 DB의 preflight 승인, provider role provisioning, SSH tunnel, 배포된 JAR와
backend restart·`/readyz` 확인을 대신하지 않는다. 그 절차는 운영 runbook의 변경 gate로 남는다.

## 기존 실기기 검증 환경

기존 실행 코드는 `test/frontend`의 Next.js 애플리케이션, `test/backend`의
Spring Boot 애플리케이션과 `test/docker-compose.yml`의 실기기 검증 환경으로
구성되어 있다. 이 환경은 축제 서비스 전체 아키텍처가 아니다. Git 저장소는 구성되어 있다. CI workflow와 원격 보호 규칙은 설정 여부를 확인하고,
아래 검증 명령을 필수 검사로 연결한다.

현재 기준 검증 명령은 다음과 같다.

```bash
cd test/frontend
npm run check

cd ../backend
mvn verify

cd ..
docker compose config --quiet
docker compose build
```

- frontend는 Node.js 20.9 이상과 `npm@11.12.1`, backend는 Java 21 이상과 Maven 3.9
  이상을 사용한다.
- `npm run check`는 lint, TypeScript 검사, Vitest, production build를 차례로 실행한다.
- Compose 검증은 `test/.env.example`을 바탕으로 비밀값 없는 로컬 `.env`를 준비한 뒤
  실행한다. 실제 비밀값을 commit하거나 로그에 출력하지 않는다.
- 의존성 설치·잠금 파일, 디렉터리 또는 스크립트가 바뀌면 같은 PR에서 문서와 CI
  명령을 함께 갱신한다.
- 축제 서비스의 최초 scaffold PR은 런타임·패키지 관리자와 lockfile, 프런트엔드와
  백엔드 경계, 설치·개발·검증 명령, 지원 브라우저·viewport, 테스트 위치,
  `.gitignore`, `.editorconfig`, 비밀값 없는 `.env.example`, 실제 CI workflow와
  필수 check 이름을 함께 확정한다. 이후 이 절의 검증 명령을 실제 서비스 구조에 맞게
  갱신한다.

## 테스트와 CI

- 변경된 동작에는 위험도에 맞는 자동 테스트를 추가한다.
- 버그 수정은 가능하면 실패를 재현하는 테스트를 먼저 포함한다.
- PR에는 실제 실행한 명령·환경·결과를 적는다. 실행하지 못한 검사는 이유와 대체
  확인 방법을 적는다.
- frontend 변경은 최소 `npm run check`, backend 변경은 최소 `mvn verify`, 통합 실행
  환경 변경은 최소 `docker compose config --quiet`와 `docker compose build`를 실행한다.
- CI 구성 시 위 검사를 필수로 등록하고 의존성·SAST·secret 검사 등 서비스 위험도에
  맞는 검사를 추가한다. 검사 이름이나 명령이 바뀌면 이 문서도 같은 PR에서 갱신한다.
