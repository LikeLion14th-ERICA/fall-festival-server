# 기여 및 Pull Request 규칙

이 문서는 저장소의 브랜치, 커밋, 리뷰, 릴리스 규칙을 정의한다. 제품 판단은
[`AGENTS.md`](AGENTS.md)와 [`docs/PRODUCT_CONTEXT.md`](docs/PRODUCT_CONTEXT.md)를 먼저
따른다.

## 1. 저장소와 검증 기준

현재 실행 코드는 `test/frontend`의 Next.js 애플리케이션, `test/backend`의
Spring Boot 애플리케이션과 `test/docker-compose.yml`의 실기기 검증 환경으로
구성되어 있다. 이 환경은 축제 서비스 전체 아키텍처가 아니다. 현재 작업 디렉터리에는
Git 메타데이터와 CI workflow가 없으므로 원격 저장소를 구성할 때 이 문서의 보호
규칙과 아래 검증 명령을 필수 검사로 연결한다.

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

## 2. 브랜치 관리

### 2.1 기본·보호 브랜치

- 기본 브랜치는 `main`이다. `main`은 항상 배포 가능한 상태를 유지한다.
- 장기 유지하는 `develop` 브랜치는 두지 않는다. 모든 일반 작업은 최신 `main`에서
  시작해 짧게 유지하고 PR로 `main`에 병합한다.
- `main`과 활성 `release/*` 브랜치는 보호 브랜치다. GitHub 연결 시 Ruleset 또는
  branch protection으로 다음을 강제한다.
  - 직접 push, force push, 브랜치 삭제 금지
  - PR을 통한 변경만 허용
  - 리뷰 권한을 가진 활동 인원이 2명 이상이면 작성자 외 최소 1명 승인
  - 1인 유지보수 기간에는 승인 수 요구 대신 기록된 self-review와 필수 검사 통과
  - 변경과 관련된 CODEOWNER가 설정되어 있다면 해당 승인 필수
  - 필수 CI 검사 통과와 병합 직전 base branch 최신 상태 확인
  - 관리자 우회는 운영 복구처럼 기록 가능한 긴급 상황으로 제한
- 제품 범위, 개인정보, 인증·권한, API 호환성, 배포 설정처럼 위험도가 높은 변경은
  해당 책임자의 승인을 추가로 받는다. 책임자는 팀 구성 시 `CODEOWNERS`와 저장소
  Ruleset에 반영한다.

### 2.2 작업 브랜치 흐름

1. 관련 이슈와 요구사항 상태를 확인한다.
2. 원격의 최신 `main`을 기준으로 작업 브랜치를 만든다.
3. 리뷰 가능한 단위로 commit하고, 가능한 한 이른 시점에 Draft PR을 연다.
4. base branch 변경을 반영하고 실제 검증 결과를 기록한 뒤 Ready for review로 바꾼다.
5. 승인, 필수 검사, 리뷰 대화 해결, 배포·rollback 확인이 끝나면 squash merge한다.
6. 병합 후 원격과 로컬 작업 브랜치를 삭제한다.

한 브랜치는 하나의 이슈 또는 하나의 목적만 다룬다. 서로 독립적으로 검토·배포할 수
있는 변경과 대규모 포맷 변경은 별도 브랜치와 PR로 나눈다.

### 2.3 브랜치 이름

일반 작업 브랜치는 `<type>/<issue-number>-<kebab-case-summary>`를 사용한다. 이슈가
아직 없다면 문서·초기 설정처럼 추적 단위가 명확한 작업에 한해
`<type>/<kebab-case-summary>`를 허용한다.

허용 type은 다음과 같다.

- `feat`: 사용자 기능
- `fix`: 일반 버그 수정
- `docs`: 문서만 변경
- `refactor`: 외부 동작을 바꾸지 않는 구조 개선
- `perf`: 성능 개선
- `test`: 테스트만 추가·수정
- `build`: 빌드 시스템·외부 의존성 변경
- `ci`: CI/CD 설정 변경
- `chore`: 위 유형에 속하지 않는 유지보수
- `hotfix`: 현재 운영 장애·보안 문제의 긴급 수정
- `release`: 릴리스 안정화

예시는 다음과 같다.

```text
feat/42-map-category-filter
fix/87-timetable-end-boundary
docs/api-v1-contract
hotfix/103-public-api-auth-regression
release/v1.2.0
```

- type과 설명은 소문자로 쓰고 단어는 하이픈으로 구분한다.
- `feature/...`, `bugfix/...`처럼 허용 목록 밖의 동의어를 섞지 않는다.
- 사람 이름, 날짜, `work`, `temp`, `final`처럼 목적을 설명하지 못하는 이름을 쓰지
  않는다.
- 이미 병합된 브랜치 이름을 재사용하지 않는다.

### 2.4 동기화와 이력 정리

- 작업 시작과 Ready for review 전에는 원격 상태를 갱신하고 base branch와의 충돌을
  작업 브랜치에서 해결한다.
- 단독으로 사용하는 작업 브랜치는 `origin/main` 위로 rebase하는 방식을 우선한다.
  여러 명이 공유하거나 리뷰가 진행 중인 브랜치는 사전 합의 없이 rebase하지 않고,
  base branch를 merge하거나 참여자에게 이력 변경을 알린 뒤 rebase한다.
- force push는 보호·공유 브랜치에서 금지한다. 본인만 사용하는 작업 브랜치의 rebase
  결과를 올릴 때도 `--force` 대신 `--force-with-lease`만 사용한다.
- 충돌 해결 과정에서 다른 사람의 변경을 임의로 버리지 않는다. 생성 파일이나 lockfile
  충돌도 원인을 확인하고 필요한 검증을 다시 실행한다.
- 병합 직전 base branch와 달라져 필수 검사가 무효가 되면 최신 상태에서 검사를 다시
  통과한다.

### 2.5 PR과 병합

- PR base는 일반 작업과 일반 버그 수정은 `main`, 릴리스 안정화 수정은 해당
  `release/*` 브랜치로 지정한다.
- Draft PR은 구조·API·UX 피드백을 조기에 받을 때 사용한다. 미완성이라는 이유로
  검증 결과와 미결정 사항을 생략하지 않는다.
- PR 제목도 커밋 제목과 같은 Conventional Commits 형식을 사용한다.
- 기본 병합 방식은 squash merge다. squash 제목은 승인된 PR 제목을 사용하고, PR의
  관련 이슈·breaking change 정보가 squash commit 본문과 footer에 보존되는지 확인한다.
- merge commit이나 rebase merge가 필요한 예외는 릴리스 이력 보존 등 구체적인 이유를
  PR에 적고 저장소 관리자 승인을 받는다.
- 팀 운영 중에는 자신의 PR을 필수 승인·검사 없이 병합하지 않는다. 1인 유지보수
  기간에는 Ruleset의 필수 승인 수를 0으로 두고 PR에 self-review를 기록하되, 필수
  검사는 생략하지 않는다. 리뷰 가능한 인원이 합류하면 최소 1명 승인을 즉시 적용한다.

### 2.6 Hotfix

- `hotfix/*`는 현재 운영 중인 서비스의 중대한 장애, 보안 취약점, 데이터 훼손 위험을
  복구할 때만 사용한다. 일반 버그의 우선순위를 높이는 용도로 사용하지 않는다.
- 브랜치는 현재 운영 배포를 만든 정확한 commit 또는 활성 `release/*`에서 시작한다.
- PR에는 장애 영향, 재현 조건, 원인, 수정 범위, 회귀 검증, 배포·rollback 절차와
  모니터링 항목을 포함한다.
- 긴급 PR도 리뷰 가능한 다른 인원이 있으면 최소 1명의 리뷰와 가능한 필수 검사를
  거친다. 검사를 생략해야 할 정도로 긴급했다면 생략 항목, 승인자 또는 단독 결정자,
  사유와 후속 검증 기한을 기록한다.
- hotfix가 릴리스 브랜치에 먼저 병합됐다면 같은 수정이 `main`에도 반영되도록 연결
  PR을 만들고, 누락 여부를 릴리스 전에 확인한다.
- 복구 배포 후 patch 버전 태그를 생성하고 hotfix 브랜치를 삭제한다.

### 2.7 Release와 태그

- 버전은 Semantic Versioning을 따라 `MAJOR.MINOR.PATCH`로 관리하고 태그는
  `vMAJOR.MINOR.PATCH` 형식을 사용한다.
- 기본적으로 검증된 `main` commit에서 릴리스한다. 별도 안정화 기간이 필요할 때만
  릴리스 책임자가 `release/vMAJOR.MINOR.PATCH` 브랜치를 만든다.
- release 브랜치에는 신규 기능을 추가하지 않는다. 릴리스 차단 버그 수정, 버전
  메타데이터, changelog와 배포 설정만 허용한다.
- release 브랜치에서 발생한 수정은 태그 전에 `main`으로 forward-port하고 연결 PR을
  남긴다.
- 태그는 승인·필수 검사·배포 준비 확인이 끝난 commit에 릴리스 책임자가 생성한다.
  공개된 태그를 이동·재사용·삭제하지 않는다. 잘못된 릴리스는 새 patch 버전으로
  수정한다.
- 태그와 릴리스 노트에는 사용자 영향, 주요 변경, 알려진 문제, migration·환경변수
  변경, rollback 기준을 기록한다.
- 지원이 끝난 release 브랜치는 삭제하되 태그와 릴리스 노트는 보존한다.

## 3. 커밋 메시지

### 3.1 기본 형식

모든 공유 이력과 squash commit은 Conventional Commits 형식을 사용한다.

```text
<type>[optional scope][optional !]: <subject>

[optional body]

[optional footer(s)]
```

- header는 72자 이내를 권장한다.
- type과 scope는 영문 소문자로 쓴다.
- scope는 변경의 주된 기능·패키지·계약 경계를 나타낸다. 저장소 전체 변경처럼 의미
  있는 scope가 없다면 생략할 수 있다.
- 권장 scope는 `home`, `lineup`, `timetable`, `spaces`, `map`, `notice`, `chatbot`,
  `i18n`, `admin`, `api`, `docs`, `build`, `ci`다. 새 scope는 일관되게 재사용할 수 있을
  때만 추가한다.

### 3.2 type

- `feat`: 사용자가 이용할 수 있는 기능 추가·변경
- `fix`: 잘못된 동작 수정
- `docs`: 문서만 변경
- `refactor`: 사용자 동작·API 계약을 바꾸지 않는 구조 개선
- `perf`: 측정 가능한 성능 개선
- `test`: 제품 코드 동작을 바꾸지 않는 테스트 변경
- `build`: 빌드 시스템, 패키지 매니저, 외부 의존성 변경
- `ci`: CI/CD workflow와 자동화 변경
- `chore`: 릴리스나 제품 코드에 직접 해당하지 않는 유지보수
- `revert`: 기존 commit 되돌리기

형식만 다듬는 변경은 실제 범위에 따라 `refactor`, `docs`, `test` 등을 사용한다.
사용자 동작이 추가되었는데 `chore`로 숨기거나 버그 수정을 `refactor`로 표시하지 않는다.

### 3.3 subject

- 무엇이 달라지는지 명령형 현재 시제로 구체적으로 쓴다.
- 한국어 또는 영어를 사용할 수 있지만 한 commit 안에서는 자연스럽게 한 언어로 쓴다.
- 첫 글자를 불필요하게 대문자로 만들지 않고 마침표를 붙이지 않는다.
- `수정`, `업데이트`, `작업`, `반영`, `changes`처럼 대상과 결과를 알 수 없는 표현만
  쓰지 않는다.
- 이슈 번호, 담당자 이름, 리뷰 상태는 subject 대신 branch, PR, footer에 둔다.

### 3.4 body

- header 다음에 빈 줄을 한 줄 둔다.
- 코드만으로 드러나지 않는 변경 이유, 이전 동작, 선택한 해결 방식과 부작용을 적는다.
- `무엇을` 반복하기보다 `왜` 필요한지와 운영·호환성 영향을 설명한다.
- 여러 문단이나 목록을 사용할 수 있으며 한 줄은 100자 안팎으로 읽기 쉽게 감싼다.
- API·DB·설정 변경은 migration, rollout, rollback 또는 하위 호환 전략을 포함한다.

### 3.5 footer와 이슈 연결

- footer 앞에는 빈 줄을 한 줄 둔다. Conventional Commits trailer는 `Token: value`
  형식으로 쓰고 GitHub 이슈 종료 키워드는 `Closes #123`처럼 쓴다.
- commit이 이슈를 완전히 해결할 때 `Closes #123`, `Fixes #123`, `Resolves #123` 중
  하나를 사용한다. 관련만 있고 종료하지 않을 때는 `Refs #123`을 사용한다.
- 여러 이슈는 의미가 드러나도록 footer를 줄별로 나눈다. 외부 이슈 키는 해당
  tracker의 정식 표기를 따른다.
- 공동 작성은 실제 기여가 있을 때만 `Co-authored-by: 이름 <email>` trailer를 쓴다.
- PR에서 squash할 때 자동 종료해야 하는 이슈는 PR 설명에도 동일하게 연결한다.

### 3.6 Breaking change

- 기존 클라이언트, 공개 API, 저장 데이터, 환경 설정 또는 배포 절차가 호환되지 않는
  변경은 type/scope 뒤에 `!`를 붙이고 footer에 `BREAKING CHANGE:`를 반드시 쓴다.
- `BREAKING CHANGE:`에는 깨지는 대상, 영향받는 버전·사용자, 전환 절차, 배포 순서,
  rollback 가능 여부를 적는다. `!`만 쓰거나 footer만 모호하게 쓰지 않는다.
- breaking change는 PR 설명과 API 명세·migration 문서에도 같은 내용을 반영하고
  책임자 승인을 받는다.

```text
feat(api)!: 공간 목록 응답을 페이지 기반으로 변경한다

대량 공간 데이터의 안정적인 조회를 위해 목록 응답에 페이지 메타데이터를 추가한다.

BREAKING CHANGE: `data` 배열을 `data.items`로 이동한다. 기존 클라이언트는 배포 전에
`items`를 읽도록 변경해야 하며 구버전 응답은 v2.0.0부터 제공하지 않는다.
Refs #142
```

### 3.7 Revert

- 공유된 변경은 이력을 삭제하거나 고쳐 쓰지 않고 새 revert commit으로 되돌린다.
- 제목은 `revert(<scope>): <되돌리는 결과>` 형식을 사용하고, body에
  `This reverts commit <full-sha>.`를 포함한다.
- 일부만 되돌리면 원 commit, 남겨 둔 변경, 선택적 revert 이유와 재적용 조건을
  설명한다.
- 운영 반영 이력을 되돌리는 경우 일반 변경과 동일하게 PR, 검증, 배포·rollback 절차를
  따른다.

### 3.8 예시

```text
feat(map): 카테고리와 구역 다중 필터를 추가한다
fix(timetable): 종료 시각에 공연이 중복 강조되지 않게 한다
docs(api): 공지 페이지네이션 계약을 명시한다
perf(map): 마커 이미지의 초기 디코딩 비용을 줄인다
test(i18n): 누락 번역 fallback 회귀 검증을 추가한다
ci: pull request 정적 검사를 추가한다
```

본문과 이슈 footer가 필요한 예시는 다음과 같다.

```text
fix(spaces): 매표소 중복 마커 생성을 막는다

서로 다른 운영 명칭이 같은 물리적 장소를 가리키므로 안정적 장소 ID를 기준으로
마커를 하나만 생성한다.

Closes #87
```

### 3.9 커밋 금지 사항

- `WIP`, `temp`, `fix`, `수정`, `최종`, `asdf`처럼 의미 없는 메시지를 공유 이력에
  남기지 않는다. 로컬 임시 commit은 PR 전 정리한다.
- 서로 무관한 기능, 리팩터링, 포맷 변경을 한 commit에 섞지 않는다.
- 테스트가 깨진 중간 상태나 빌드할 수 없는 상태를 의도적으로 공유하지 않는다.
- 비밀키, 토큰, 실제 자격증명, 개인정보, 불필요한 빌드 산출물을 commit하지 않는다.
- 생성 파일과 lockfile 변경을 이유 확인 없이 제외하거나 대량 갱신하지 않는다.
- 이미 공유한 commit을 `commit --amend`, interactive rebase, 강제 push로 사전 합의 없이
  변경하지 않는다.

## 4. PR 범위와 설명

- 한 PR은 하나의 목적 또는 하나의 기능 도메인을 중심으로 한다.
- 독립적으로 검토·배포할 수 있는 변경은 분리한다.
- 대규모 포맷 변경과 기능 변경을 같은 PR에 섞지 않는다.
- 보류 기능은 승인 근거 없이 구현하지 않는다.
- API 계약 변경은 명세·영향받는 클라이언트·서버·테스트를 같은 PR에 반영한다. 분리가
  필요하면 연결 PR, 호환 순서, rollout 계획을 명시한다.
- DB schema 변경은 migration, rollback 또는 복구 전략, 구버전과의 호환성을 포함한다.
- PR 설명에는 변경 이유와 사용자 결과, 근거, 포함·제외 범위, 구현 판단, 실제 검증,
  위험과 배포·rollback, 영향 영역, 미결정 사항을 빠짐없이 기록한다.
- UI 변경에는 전후 이미지 또는 영상을 첨부한다. Figma 기준 화면은 최소 390×844
  viewport를 포함하고 다른 지원 viewport도 확인한다.

## 5. 완료 조건

모든 PR은 해당되는 항목을 충족해야 한다.

### 공통

- 관련 요구사항의 상태가 확정인지 확인했다.
- 샘플 날짜·아티스트·URL·학교 약칭 추정값을 실제 운영 데이터로 고정하지 않았다.
- 비밀키, 토큰, 실제 관리자 자격증명, 개인정보를 commit하지 않았다.
- 오류·빈 상태·로딩 상태와 운영 실패 시나리오를 다뤘다.
- 키보드 조작, 의미 있는 label, 초점, 색상 외 상태 표현을 확인했다.
- 로그·메트릭·알림에 개인정보나 비밀값을 남기지 않고 운영 진단에 필요한 신호를
  확인했다.

### 공개 사용자

- 로그인하지 않은 상태에서 모든 공개 사용자 경로가 동작한다.
- 로그인·회원가입 리다이렉트가 새로 생기지 않았다.
- 모바일 우선 레이아웃과 정의된 지원 viewport를 확인했다.

### 다국어

- UI 문자열을 직접 하드코딩하지 않았다.
- 한국어, 영어, 확정된 중국어 locale과 긴 번역 문자열을 확인했다.
- locale별 검색·cache·fallback 영향을 확인했다.
- 공식 학교 명칭을 임의 번역하지 않았다.

### 타임테이블

- 축제 시간대, 시작·종료 경계, 동시 공연을 확인했다.
- 홈과 타임테이블의 현재 공연 계산이 같은 규칙을 쓴다.
- 지연·취소·일정 변경 또는 해당 기능 미지원 상태를 명시했다.

### 지도·부스

- 카테고리와 구역 상태를 혼동하지 않았다.
- 비율 좌표를 사용하는 경우 지도 자산 버전과 좌표 변환을 검증했다.
- 마커, 바텀시트, 상세의 위치 이동이 같은 ID를 사용한다.
- 지도 정보에 접근 가능한 텍스트 대체 경로가 있다.
- 메뉴 이미지의 대체 텍스트와 구조화 데이터 필요성을 검토했다.

### API·backend

- 요청·응답·오류·enum 변경을 버전 1 API 명세에 반영했다.
- 목록 응답, pagination, sort, locale fallback을 일관되게 검증했다.
- 공개 endpoint가 실수로 관리자 인증을 요구하지 않는다.
- rate limit, validation, cache·게시 상태, 관측 가능성 영향을 확인했다.

## 6. 테스트와 CI

- 변경된 동작에는 위험도에 맞는 자동 테스트를 추가한다.
- 버그 수정은 가능하면 실패를 재현하는 테스트를 먼저 포함한다.
- PR에는 실제 실행한 명령·환경·결과를 적는다. 실행하지 못한 검사는 이유와 대체
  확인 방법을 적는다.
- frontend 변경은 최소 `npm run check`, backend 변경은 최소 `mvn verify`, 통합 실행
  환경 변경은 최소 `docker compose config --quiet`와 `docker compose build`를 실행한다.
- CI 구성 시 위 검사를 필수로 등록하고 의존성·SAST·secret 검사 등 서비스 위험도에
  맞는 검사를 추가한다. 검사 이름이나 명령이 바뀌면 이 문서도 같은 PR에서 갱신한다.

## 7. 리뷰와 병합

- 작성자는 Ready for review 전과 병합 직전에 PR 템플릿의 체크리스트로 self-review한다.
- 리뷰어는 요구사항 충족뿐 아니라 회귀, 보안·개인정보, API·데이터 호환성, 운영 실패,
  접근성, 다국어 영향을 확인한다.
- 피드백을 반영하지 않기로 했다면 대화 없이 닫지 말고 근거와 후속 이슈를 남긴다.
- 최종 diff에 무관한 변경, 임시 로그, debug flag, 로컬 전용 설정이 없는지 확인한다.
- 모든 승인·필수 검사·대화 해결 후 작성자 또는 지정된 릴리스 책임자가 병합한다.
- 병합 후 배포 상태와 핵심 운영 지표를 확인한다. 이상이 있으면 무리한 forward fix보다
  영향과 복구 시간을 기준으로 rollback 또는 revert를 결정한다.
