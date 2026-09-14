# fall-festival-server

한양대학교 ERICA 가을 축제 서비스의 **Spring Boot 백엔드 저장소**입니다.
공개 축제 정보 API와 관리자 기능의 서버 측 구현을 담당합니다.

- GitHub: [LikeLion14th-ERICA/fall-festival-server](https://github.com/LikeLion14th-ERICA/fall-festival-server)
- 제품 범위: [기능 범위](docs/wiki/product/scope.md)
- 새 화면 연동 계약 초안: [API v2](api-v2/README.md)
- 기존 API v1 기록: [핵심 기능 API 명세서 v1](핵심%20기능%20API%20명세서%20v1.md)
- 논리 데이터 모델·ERD: [설계 검토](docs/wiki/engineering/data-model.md)
- 작업 시작: [AGENTS.md](AGENTS.md) · [기여 안내](CONTRIBUTING.md)

## 현재 구현 상태

루트 프로젝트는 서버 실행·테스트·패키징을 위한 초기 구성입니다. 축제 도메인 API,
DB 연결, 관리자 인증, 배포 구성은 아직 구현하지 않았습니다. API v2는 프런트 연동용
draft.3이며, 명세에 기재된 기능이 모두 구현되거나 공개 승인된 상태는 아닙니다.

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

| 변수 | 기본값 | 용도 |
|---|---|---|
| `SERVER_ADDRESS` | `127.0.0.1` | 로컬 접속 주소 |
| `SERVER_PORT` | `8080` | HTTP 포트 |
| `SPRING_PROFILES_ACTIVE` | (없음) | `db`로 설정해야 아래 DB 변수가 적용됨 |
| `SPRING_DATASOURCE_URL` | (없음) | `jdbc:postgresql://host:5432/db` 형식. `db` profile에서만 사용 |
| `SPRING_DATASOURCE_USERNAME` | (없음) | DB 계정 |
| `SPRING_DATASOURCE_PASSWORD` | (없음) | DB 비밀번호 |
| `SPRING_FLYWAY_ENABLED` | `false` | 마이그레이션 파일이 생기기 전까지 `false` 유지 |

예를 들어 포트가 사용 중이라면 PowerShell에서 다음과 같이 실행합니다.

```powershell
$env:SERVER_PORT = '8081'
.\mvnw.cmd spring-boot:run
```

`SPRING_PROFILES_ACTIVE`를 지정하지 않으면 DB 없이 실행됩니다. 공유 DB에 연결하려면
아래처럼 `db` profile과 세 DB 변수를 함께 지정합니다. 팀이 공유한 접속 정보가
`postgres://사용자:비밀번호@호스트:5432/db명` 형식이면 `SPRING_DATASOURCE_URL`에는
`jdbc:postgresql://호스트:5432/db명`만 넣고 사용자·비밀번호는 별도 변수로 분리합니다.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'db'
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://<host>:5432/<db>'
$env:SPRING_DATASOURCE_USERNAME = '<user>'
$env:SPRING_DATASOURCE_PASSWORD = '<password>'
.\mvnw.cmd spring-boot:run
```

DB 접속 정보는 절대 저장소나 커밋 메시지, PR, 이슈에 붙여넣지 않습니다. 비밀값은
`.env`(gitignore 대상) 또는 셸·IDE 실행 설정에만 둡니다. 관련 구현을 추가할 때
[보안 규칙](docs/wiki/engineering/security.md)을 먼저 확인합니다. 운영 배포 절차는
추후 인프라와 인증·관측성 구성을 확정하면서 작성합니다.

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
