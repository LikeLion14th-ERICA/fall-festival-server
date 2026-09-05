# 커밋 메시지

[위키 홈](../README.md) · 읽는 때: commit·squash 제목 작성

### 3.1 기본 형식

검증을 마친 목적별 작은 작업 단위마다 자주 커밋한다. 다른 작업의 미커밋 변경은
명시적으로 선택한 파일에 포함하지 않는다. 커밋과 원격 push·PR 병합은 별도 단계다.

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
