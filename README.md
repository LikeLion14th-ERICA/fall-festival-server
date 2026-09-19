# fall-festival-server

한양대학교 ERICA 가을 축제 서비스의 **Spring Boot 백엔드 저장소**입니다.
공개 축제 정보 API와 관리자 기능의 서버 측 구현을 담당합니다.

- GitHub: [LikeLion14th-ERICA/fall-festival-server](https://github.com/LikeLion14th-ERICA/fall-festival-server)
- 제품 범위: [기능 범위](docs/wiki/product/scope.md)
- 새 화면 연동 계약 초안: [API v2](api-v2/README.md)
- 제품 API 계약: [핵심 기능 API 명세서 v2](핵심%20기능%20API%20명세서%20v2.md)
- 논리 데이터 모델·ERD: [설계 검토](docs/wiki/engineering/data-model.md)
- 작업 시작: [AGENTS.md](AGENTS.md) · [기여 안내](CONTRIBUTING.md)

## 현재 구현 상태

루트 프로젝트는 JDBC·PostgreSQL·Flyway를 사용해 축제 회차와 published revision의 공개
카탈로그를 제공합니다. `db` profile에서 현재 구현된 공개 조회는 다음과 같습니다.

- 공연: `GET /api/v2/lineup`, `/artists/{artistId}`, `/timetable`,
  `/performances/{performanceId}`, `/prohibited-items`
- 공간·지도: `GET /api/v2/spaces`, `/spaces/{spaceId}`, `/maps`, `/maps/{mapId}`,
  `/maps/{mapId}/pins`, `/places/{placeId}`
- 안내·운영 상태: `GET /api/v2/ticket-guide`, `/stamp-guide`, `/crowding`

카탈로그 API는 시작 시 검증한 published snapshot만 읽습니다. 실제 행사 운영 자료는 승인
전이므로 migration에 임의로 seed하지 않습니다. 공개 GET은 인증 없이 접근할 수 있습니다.
`/api/v2/admin/**`에는 login, access JWT, 회전되는 refresh cookie, logout, `/admin/me`와
서버 측 `ADMIN` 권한 검사가 구현되어 있습니다. 관리자 웹 UI와 콘텐츠별 관리자 CRUD가
구현됐다는 뜻은 아닙니다. API v2는 프런트 연동용 draft.3이며, 명세의 모든 기능이 구현되거나
공개 승인된 상태는 아닙니다.

일반 사용자는 설치·로그인·회원가입 없이 공개 정보를 조회합니다. 구현 대상은
라인업, 타임테이블, 부스 & 마켓, 지도, 공지 등이며, 관리자 권한은 서버에서 검증해야
합니다. 미확정 기능과 실제 행사 데이터는 [기능 범위](docs/wiki/product/scope.md)를
확인한 후 추가합니다.

`test/`는 기존 실기기 검증 환경입니다. 그 안의 Next.js 프런트엔드와 Spring Boot
백엔드는 별도 실험용 프로젝트이며, 루트 서버 빌드에 포함되지 않습니다.

## 개발 환경

| 항목 | 기준 |
|---|---|
| 언어 | Java 21 기준 컴파일, JDK 21 권장 |
| 프레임워크 | Spring Boot 4.1.1 |
| 빌드 | Maven 3.9.11, Maven Wrapper 3.3.4 |
| 기본 주소 | `http://127.0.0.1:8080` |
| CI | JDK 21·25에서 `verify` |

기존 검증 백엔드의 Java·Spring Boot·Maven 계열을 재사용합니다.
[Spring Boot 공식 호환성 안내](https://docs.spring.io/spring-boot/system-requirements.html)를
기준으로 JDK 21~26을 허용하며, CI 검증 대상은 21과 25입니다.
의존성 버전은 `pom.xml`과 Spring Boot dependency management에서 관리하고,
Maven 배포 파일은 Wrapper에 고정한 버전과 SHA-256으로 검증합니다.

JDK를 설치하고 `JAVA_HOME`을 JDK 디렉터리로 지정하세요. 전역 Maven 설치는 필요
없습니다. 최초 실행에는 Maven Central 접근이 필요하며 기본 캐시는 `~/.m2`입니다.
IDE에서는 루트 `pom.xml`을 Maven 프로젝트로 열고 Project SDK를 JDK 21로 설정합니다.

## 실행 및 검증

Windows PowerShell:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
.\mvnw.cmd spring-boot:run
```

macOS / Linux:

```bash
sh ./mvnw --batch-mode --no-transfer-progress verify
sh ./mvnw spring-boot:run
```

`verify`는 테스트와 실행 가능한 JAR 생성을 수행합니다.

```bash
java -jar target/fall-festival-server-0.0.1-SNAPSHOT.jar
```

기동 로그의 `Started FallFestivalServerApplication`을 확인하세요. 현재 `/` 및
미구현 API 요청의 `404`는 정상입니다. 기동 테스트는 실제 임의 포트에서 외부 서비스
없이 서버가 시작되는지, 미구현 API가 로그인으로 리다이렉트되지 않는지 확인합니다.
서버는 실행한 터미널에서 `Ctrl+C`로 종료합니다.

## 로컬 설정

[.env.example](.env.example)은 변수 참고 파일입니다. Spring Boot가 `.env`를 자동으로
읽지는 않으므로 셸 또는 IDE 실행 설정에 환경변수를 지정하세요.

| 변수 | 필요 여부 | 기본값 | profile | 용도·검증 |
|---|---|---|---|---|
| `SERVER_ADDRESS` | 선택 | `127.0.0.1` | 전체 | 서버 bind 주소 |
| `SERVER_PORT` | 선택 | `8080` | 전체 | HTTP 포트 |
| `SERVER_TOMCAT_ACCEPT_COUNT` | 선택 | `1024` | 전체 | Tomcat 연결 대기열 상한 |
| `SERVER_TOMCAT_MAX_KEEP_ALIVE_REQUESTS` | 선택 | `10000` | 전체 | 연결당 허용할 HTTP/1.1 요청 상한 |
| `SPRING_PROFILES_ACTIVE` | 선택 | (없음) | 전체 | `db`로 설정하면 DB·Flyway·카탈로그·관리자 인증 bean 활성화 |
| `SPRING_DATASOURCE_URL` | 필수 | (없음) | `db` | `jdbc:postgresql://host:5432/db` 형식의 접속 URL |
| `SPRING_DATASOURCE_USERNAME` | 필수 | (없음) | `db` | DB 계정 |
| `SPRING_DATASOURCE_PASSWORD` | 필수 | (없음) | `db` | DB 비밀번호 |
| `SPRING_FLYWAY_ENABLED` | 선택 | `true` | `db` | 시작 시 migration 적용 여부 |
| `FESTIVAL_ID` | 필수 | (없음) | `db` | 제공할 `festivals.id`; 공백 또는 UUID 형식이 아니면 startup 실패 |
| `ADMIN_JWT_SIGNING_SECRET` | 필수 | (없음) | `db` | access JWT 서명 비밀; UTF-8 32바이트 미만이면 startup 실패 |
| `ADMIN_ALLOWED_ORIGIN` | 필수 | (없음) | `db` | 관리자 credential 요청용 단일 HTTP(S) origin; wildcard·경로·query 불가 |
| `ADMIN_BOOTSTRAP_USERNAME` | 선택 | (없음) | `db` | 최초 관리자 username; 양쪽 값이 있을 때 공백 제거 후 100자 이하 |
| `ADMIN_BOOTSTRAP_PASSWORD` | 선택 | (없음) | `db` | 최초 관리자 비밀번호; 양쪽 값이 있을 때 UTF-8 72바이트 이하 |
| `STAMP_RECEIPT_CODE_SHA256` | 선택 | (없음) | `db` | 스탬프 수령 인증 코드(6자리 숫자)의 SHA-256 hex. 교체 중에는 쉼표로 여러 개. 없으면 인증 API가 503 |
| `RATE_LIMIT_ENABLED` | 선택 | `true` | 전체 | `/api/v2` 클라이언트별 요청 수 제한. 초과 시 `429 RATE_LIMITED`와 `Retry-After` |
| `RATE_LIMIT_TRUSTED_PROXY_HOPS` | 선택 | `0` | 전체 | 앞단에서 `X-Forwarded-For`를 붙이는 신뢰 proxy 수. Next.js proxy와 호스팅 load balancer 뒤면 `2` |
| `PUBLIC_LOCALES` | 선택 | `ko` | `db` | 공개할 언어, 쉼표 구분(`ko,en,zh-Hans`). 한국어는 항상 공개. 나열한 언어도 게시 catalog의 번역이 모두 있어야 공개되고, 빠지면 시작 로그에 이유를 남기고 `LOCALE_NOT_READY` 유지 |
| `API_DOCS_ENABLED` | 선택 | `false` | 전체 | `true`면 `/docs`에서 Swagger UI 제공. 로컬·개발 서버 전용, 운영에서는 끔 |

예를 들어 포트가 사용 중이라면 PowerShell에서 다음과 같이 실행합니다.

```powershell
$env:SERVER_PORT = '8081'
.\mvnw.cmd spring-boot:run
```

`SPRING_PROFILES_ACTIVE`를 지정하지 않으면 DB 없이 실행됩니다. `.env.example`과 로컬
`.env`는 참고용이며 Spring Boot가 자동으로 읽지 않습니다. 환경변수는 실행할 shell,
IDE run configuration 또는 deployment platform에서 주입해야 합니다.

`db` profile에는 PostgreSQL 접속정보, 축제 UUID와 관리자 인증 설정이 모두 필요합니다.
팀이 공유한 접속 정보가 `postgres://사용자:비밀번호@호스트:5432/db명` 형식이면 datasource
URL에는 `jdbc:postgresql://호스트:5432/db명`만 넣고 사용자·비밀번호를 별도 변수로 분리합니다.
아래 값은 형식만 보여 주는 placeholder입니다.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'db'
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://<host>:5432/<db>'
$env:SPRING_DATASOURCE_USERNAME = '<user>'
$env:SPRING_DATASOURCE_PASSWORD = '<password>'
$env:FESTIVAL_ID = '<festival-uuid>'
$env:ADMIN_JWT_SIGNING_SECRET = '<at-least-32-utf8-byte-secret>'
$env:ADMIN_ALLOWED_ORIGIN = '<admin-origin>'
.\mvnw.cmd spring-boot:run
```

macOS / Linux:

```bash
export SPRING_PROFILES_ACTIVE='db'
export SPRING_DATASOURCE_URL='jdbc:postgresql://<host>:5432/<db>'
export SPRING_DATASOURCE_USERNAME='<user>'
export SPRING_DATASOURCE_PASSWORD='<password>'
export FESTIVAL_ID='<festival-uuid>'
export ADMIN_JWT_SIGNING_SECRET='<at-least-32-utf8-byte-secret>'
export ADMIN_ALLOWED_ORIGIN='<admin-origin>'
sh ./mvnw spring-boot:run
```

`FESTIVAL_ID`는 `festival.id`에 바인딩되는 `festivals.id` UUID입니다. `db` profile에서
누락·공백·잘못된 UUID이면 startup이 실패하며, DB의 최신 festival을 자동 선택하지 않습니다.
설정한 festival에 published revision이 있어야 `/readyz`와 공개 카탈로그가 준비 상태가 됩니다.
환경별 UUID는 팀에서 제공받거나 해당 환경 DB에서 확인하며, 특정 원격 DB의 값을 문서 기본값으로
사용하지 않습니다.

이 저장소는 PostgreSQL에 연결할 수 있지만 루트 Docker Compose 등으로 PostgreSQL 인스턴스를
생성하거나 기동하지는 않습니다. 기존 PostgreSQL, 팀 공유 DB 또는 직접 준비한 로컬 PostgreSQL
중 하나가 필요합니다. 새 DB에서는 기본값인 `SPRING_FLYWAY_ENABLED=true`로 V1~V13 migration을
적용합니다. Flyway clean은 비활성화되어 있습니다. `false`는 migration이 이미 별도로 관리되는
schema를 의도적으로 사용할 때만 선택하며 일반 개발 기본값으로 사용하지 않습니다.

### 최초 관리자 bootstrap

관리자 계정이 하나도 없는 DB에서만 두 값을 함께 설정해 최초 `ADMIN`을 만들 수 있습니다.

```text
ADMIN_BOOTSTRAP_USERNAME=<bootstrap-username>
ADMIN_BOOTSTRAP_PASSWORD=<bootstrap-password>
```

둘 중 하나라도 누락되거나 공백이면 bootstrap을 건너뜁니다. 둘 다 있더라도 기존 관리자 계정이
하나라도 있으면 아무것도 변경하지 않습니다. username은 공백 제거 후 100자 이하, password는
BCrypt 입력 한계인 UTF-8 72바이트 이하여야 합니다. 로그인과 `GET /api/v2/admin/me`로 생성을
확인한 뒤 runtime 환경에서 두 bootstrap credential을 제거하고 서버를 다시 시작합니다.

### 기동 확인과 API 호출

1. 로그에서 `Started FallFestivalServerApplication`을 확인합니다.
2. `GET /healthz`가 `200 {"status":"ok"}`인지 확인합니다.
3. `db` profile이면 `GET /readyz`가 `200 {"status":"ready"}`인지 확인합니다.
4. 공개 API를 하나 호출하고 응답 `meta.revision`이 기대한 published revision인지 확인합니다.

`/healthz`는 프로세스 liveness만 나타냅니다. `/readyz`는 `db` profile에서만 등록되며, DB에서
설정된 festival의 published snapshot을 불러오면 200, 아직 준비되지 않으면 503을 반환합니다.
DB 없는 기본 profile에서는 `/readyz`가 등록되지 않아 404입니다.

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/api/v2/lineup?locale=ko'
Invoke-RestMethod 'http://127.0.0.1:8080/api/v2/timetable?locale=ko'
```

```bash
curl --fail-with-body 'http://127.0.0.1:8080/api/v2/lineup?locale=ko'
curl --fail-with-body 'http://127.0.0.1:8080/api/v2/timetable?locale=ko'
```

공개 GET에는 인증이 필요하지 않습니다. 관리자 login과 refresh만 익명 진입을 허용하고,
그 밖의 `/api/v2/admin/**` 요청에는 `ADMIN` access JWT가 필요합니다. 전체 경로·응답 계약은
[정적 OpenAPI 3.1 문서](api-v2/openapi.json), 실제 서버와 목 서버의 구분은
[프런트 연동 안내](api-v2/FRONTEND.md)를 따릅니다.

### 스탬프 수령 인증 코드 설정

서버에는 6자리 코드 자체가 아니라 SHA-256 hash만 넣습니다. 코드는 명령 기록에 남지 않도록
입력받아 계산합니다.

```powershell
$code = Read-Host '6자리 수령 인증 코드'; [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($code))).ToLower(); Remove-Variable code
```

출력된 64자리 hex를 `STAMP_RECEIPT_CODE_SHA256`에 넣습니다. 행사일마다 새 코드로 바꾸고, 바꾸는
동안에는 `이전hash,새hash`처럼 함께 넣었다가 이전 값을 뺍니다. hash도 비밀값으로 다룹니다.

### Swagger UI로 엔드포인트 확인

로컬·개발 서버에서 `API_DOCS_ENABLED=true`로 실행하면 `http://127.0.0.1:8080/docs`에서 API v2
계약을 보고 **Try it out**으로 이 서버에 바로 요청할 수 있습니다.

```powershell
$env:API_DOCS_ENABLED = 'true'
```

- 화면은 저장소의 `api-v2/openapi.json`을 그대로 보여 주며 요청 대상만 이 서버로 바꿉니다.
- 실행 중인 서버에 실제 route가 없는 operation은 요약 앞에 `[서버 미구현]`이 붙습니다. 판정은
  현재 profile 기준이므로 `db` profile 없이 띄우면 DB가 필요한 API가 모두 미구현으로 표시됩니다.
- 관리자 API는 **Authorize**에 login 응답의 access token을 넣어 호출합니다. login·refresh는
  `ADMIN_ALLOWED_ORIGIN`과 같은 `Origin`만 허용하므로, Swagger에서 login하려면 로컬에서만
  `ADMIN_ALLOWED_ORIGIN`을 이 서버 주소(예: `http://127.0.0.1:8080`)로 둡니다.
- 운영 서버에서는 켜지 않습니다. 꺼져 있으면 `/docs`는 404입니다.

DB 접속정보, JWT secret과 bootstrap password는 저장소, 커밋 메시지, PR 또는 이슈에 넣지
않습니다. 관련 구현을 추가할 때 [보안 규칙](docs/wiki/engineering/security.md)을 먼저 확인합니다.

Docker image는 루트에서 다음과 같이 빌드할 수 있습니다.

```bash
docker build --tag fall-festival-server:local .
```

이미지는 `0.0.0.0:8080`으로 bind하지만 DB와 runtime 환경변수를 자체 provision하지 않습니다.
로컬 실행과 Docker image build는 지원하지만 원격 개발 서버는 아직 provision되지 않았고 운영
배포 절차도 확정되지 않았습니다. 인프라와 인증·관측성 구성이 정해진 뒤 별도 runbook으로
작성합니다. 배포 전 검토할 development provider와 same-origin proxy 기본안은
[원격 개발 환경 배포 결정](docs/dev-deployment-decision.md)에 기록되어 있습니다.

## 디렉터리

```text
src/main/java/dev/espero/festival/   서버 소스
src/main/resources/                 애플리케이션 설정
src/test/java/dev/espero/festival/   서버 테스트
.mvn/wrapper/                       Maven Wrapper 버전·체크섬
.github/workflows/backend-ci.yml    루트 백엔드 CI
docs/wiki/                         제품·개발·협업 규칙
test/                              기존 실기기 검증 환경
```

루트 서버는 프런트엔드를 빌드하거나 제공하지 않습니다. 프런트엔드 저장소 주소와
연동 환경은 확정 후 문서화합니다. 웹 화면의 브라우저·viewport 검증은 프런트엔드의
책임이며 현재 미확정 지원 범위는 [결정 대기](docs/wiki/product/decisions.md)를 따릅니다.

## 협업

`origin/main`에서 목적별 작업 브랜치를 만들고 검증을 마친 작은 작업 단위마다
커밋합니다. [브랜치 규칙](docs/wiki/workflow/branches.md)과
[커밋 규칙](docs/wiki/workflow/commits.md)을 따르고 PR을 통해 병합합니다.
API 구현 시 계약·테스트를 함께 갱신하며, 기존 미커밋 변경을 덮어쓰지 않습니다.

검사 명령과 필수 CI 이름은 [검증 안내](docs/wiki/workflow/validation.md)를 참고하세요.
기존 검증 앱을 변경할 때는 [test/README.md](test/README.md)의 별도 절차를 적용합니다.
초기 구성 검증 결과와 원격 연결 상태는
[개발 준비 기록](docs/backend-setup-verification.md)에 기록합니다.
