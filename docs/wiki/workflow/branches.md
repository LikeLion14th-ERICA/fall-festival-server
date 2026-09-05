# 브랜치와 동기화

[위키 홈](../README.md) · 읽는 때: 브랜치 생성·동기화·push

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
