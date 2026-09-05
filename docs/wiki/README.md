# 프로젝트 위키

[처음 숙지하기](START_HERE.md) · [항상 지킬 규칙](../../AGENTS.md)

## 작업별 읽기 표

일치하는 모든 행을 적용한다. 지정 페이지는 필수이며 조건부 자료는 해당 변경이 있을
때만 읽는다. 선택한 페이지는 전문을 읽되 모든 링크를 재귀적으로 따라가지 않는다.

| 작업 | 읽을 페이지 | 조건부 자료 |
|---|---|---|
| 기능 추가·활성화·제품 판단 | [범위](product/scope.md), [근거](product/sources.md), [결정 대기](product/decisions.md) | 해당 도메인·Figma node |
| 홈·탭·공지·챗봇 진입 | [내비게이션](product/navigation.md), [홈](product/home.md) | 공지·챗봇 API |
| 라인업 | [라인업](product/lineup.md) | API 11절 |
| 시간표·현재 공연 | [시간표](product/timetable.md) | API 6·12절·홈 집계 |
| 부스·주점·플리마켓·메뉴 | [부스 & 마켓](product/spaces.md) | 위치 연결은 지도 |
| 지도·좌표·필터 | [지도](product/map.md), [학교 용어](product/terminology.md) | 지도 API·자산 |
| 학교 명칭·공식 번역 | [용어](product/terminology.md), [결정 대기](product/decisions.md) | 다국어 |
| 스탬프·QR·경품 | [스탬프](product/stamp.md), [보안](engineering/security.md), [검증 서비스](engineering/test-service.md) | API 29절 |
| PWA·푸시·익명 상태 복원 | [검증 서비스](engineering/test-service.md), [보안](engineering/security.md), [운영](engineering/operations.md) | 해당 test 구현·API 절 |
| 사용자 화면·문자열·번역 | [다국어](engineering/i18n.md), [품질](engineering/quality.md) | 해당 화면 도메인 |
| API·DB·schema·데이터 계약 | [API 규칙](engineering/api.md), [API 절 찾기](engineering/api-navigation.md) | 해당 도메인·계약 테스트 |
| 관리자·인증·공개 쓰기·저장·로그·업로드 | [보안](engineering/security.md) | 관리자·미디어 API |
| 콘텐츠·번역 게시·예약·revision | [게시](engineering/publishing.md), [운영](engineering/operations.md) | 관리자 콘텐츠 API |
| 캐시·배포·복구·인프라 | [운영](engineering/operations.md), [품질](engineering/quality.md), [검증](workflow/validation.md) | 변경 대상 runbook |
| branch·worktree·동기화·push | [작업 절차](workflow/task.md), [브랜치](workflow/branches.md) | PR 규칙 |
| commit·squash 메시지 | [커밋](workflow/commits.md) | breaking change 계약 |
| 검증·완료 보고 | [검증](workflow/validation.md), [완료 조건](workflow/done.md) | 해당 항목만 적용 |
| PR·리뷰·병합 | [PR](workflow/pull-requests.md), [완료 조건](workflow/done.md) | PR 템플릿 |
| hotfix·release·tag | [릴리스](workflow/releases.md), [PR](workflow/pull-requests.md), [운영](engineering/operations.md) | 배포 runbook |
| 위키·컨텍스트 문서 | [위키 관리](MAINTENANCE.md) | 변경하는 주제 페이지 |

## 원문과 목차

- [제품 컨텍스트 목차](../PRODUCT_CONTEXT.md)
- [협업 규칙 목차](../../CONTRIBUTING.md)
- [API v1 원문](../../핵심%20기능%20API%20명세서%20v1.md)
- [검증 서비스 README](../../test/README.md)
- [PR 템플릿](../../.github/PULL_REQUEST_TEMPLATE.md)

API와 배포 runbook은 단일 원문을 유지한다. 필요한 절을 찾아 읽고 내용을 위키에
복제하지 않는다.
