# 백엔드 개발 준비 검증 기록

확인일: 2026-09-05 (Asia/Seoul)

## Git 동기화

- 저장소: `LikeLion14th-ERICA/fall-festival-server`
- 시작 시 로컬 `main`과 원격 `chore/import-festival-project`는 모두
  `38ce23b2439de0b6bc96cd49d3ddb8958a092acd`였다.
- 로컬에 원격이 없어 지정 저장소를 `origin`으로 연결하고 fetch했다.
- 작업 전 `핵심 기능 API 명세서 v1.md`에 미커밋 수정이 있었다. 이 작업의 수정과
  커밋에서 제외했다.
- 작업 브랜치: `build/spring-boot-setup`.
- 사용자 요청에 따라 위 커밋을 원격 `main`으로 push했다. 코드나 이력을 덮어쓰지 않았다.
- GitHub 기본 브랜치 변경은 API에서 `404`를 반환했고, 웹 설정에서도
  `You don't have access to repository options`를 확인했다. 따라서 기본 브랜치는
  아직 `chore/import-festival-project`다. 관리자가 Settings → General → Default branch에서
  `main`을 선택해야 한다. 브랜치 보호 규칙과 CI 필수 검사는 별도 관리자 설정이 필요하다.

## 개발 준비 결과

- Spring Boot 4.1.1, Java 21 기준 컴파일, Maven 3.9.11 Wrapper 구성.
- Web MVC, Validation, 테스트 의존성과 루트 서버 진입점 추가.
- 기본 바인딩 `127.0.0.1:8080`, 환경변수로 변경 가능.
- 도메인 API·DB·인증·운영 배포는 미구현. 기존 `test/`는 변경하지 않음.
- CI workflow는 작성했으며 GitHub 실행 결과는 아직 없음.

## 실제 검증

환경: Windows PowerShell, Zulu JDK 25.0.2, Maven 3.9.11.
시스템 Maven은 없어 공식 Maven 배포를 프로젝트 `.cache/bootstrap`에 내려받고
공식 SHA-512와 비교한 뒤 Wrapper를 생성했다. Wrapper에는 SHA-256을 고정했다.

```powershell
$env:MAVEN_USER_HOME = Join-Path $PWD '.cache/maven-user-home'
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=.cache/m2" verify
git diff --check
```

- `verify`: BUILD SUCCESS, 테스트 1개, 실패 0, 오류 0, 건너뜀 0.
- 실제 임의 HTTP 포트에서 외부 DB 없이 기동하고 미구현 API의 404 및 리다이렉트 부재 확인.
- `target/fall-festival-server-0.0.1-SNAPSHOT.jar` 생성 성공.
- JDK 25에서 Maven/Mockito의 Unsafe·동적 agent 관련 경고가 있었으나 검증은 성공했다.
- `git diff --check` 및 변경 문서의 상대 파일 링크 검사 통과.
- Java 21과 Linux 실행은 로컬 미실행: 설치 환경은 JDK 25·Windows이며 CI 매트릭스로 구성.
- 기존 `test/`와 프런트엔드·Compose 검사는 미실행: 해당 구현·실행 설정 변경이 없음.
- 운영 배포·보안 취약점 검사·부하 검증은 수행하지 않았으며 운영 준비 완료를 의미하지 않음.
