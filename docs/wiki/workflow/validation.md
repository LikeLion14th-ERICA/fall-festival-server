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
