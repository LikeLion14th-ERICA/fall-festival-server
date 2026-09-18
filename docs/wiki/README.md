# 프로젝트 위키

[처음 숙지하기](START_HERE.md) · [항상 지킬 규칙](../../AGENTS.md)

## 작업별 읽기 표

일치하는 모든 행을 적용한다. 지정 페이지는 필수이며 조건부 자료는 해당 변경이 있을
때만 읽는다. 선택한 페이지는 전문을 읽되 모든 링크를 재귀적으로 따라가지 않는다.

| 작업 | 읽을 페이지 | 조건부 자료 |
|---|---|---|
| 기능 추가·활성화·제품 판단 | [범위](product/scope.md), [근거](product/sources.md), [결정 대기](product/decisions.md) | 해당 도메인·Figma node |
| Product 기능·Figma 화면 흐름 대조 | [Design / Wireframe Reference](design/README.md), 해당 [Product 문서](product/overview.md) | Figma node `438:2` |
| 홈·탭·공지 진입 | [내비게이션](product/navigation.md), [홈](product/home.md) | 앱 내부 공지는 [알림 메시지](product/notice.md) |
| 관리자 공통 범위·제외 기능 | [관리자 Product](product/admin/README.md), [보안](engineering/security.md) | 인증·권한 계약은 별도 설계 |
| 관리자 혼잡도·운영 시간 연동 | [관리자 혼잡도](product/admin/crowd.md), [홈](product/home.md) | 운영 일정은 개발자 등록, 구현 시 보안·API |
| 관리자 공지·번역·템플릿 | [관리자 공지](product/admin/notice.md), [알림 메시지](product/notice.md) | 게시·번역 구현은 engineering 문서 |
| 관리자 굿즈·판매 상태 | [관리자 굿즈](product/admin/goods.md), [굿즈샵](product/goods.md) | 실제 수량 관리는 범위 밖 |
| 에리카 웰컴 데이·학교 소개 연결 | [웰컴 데이](product/welcome.md), [범위](product/scope.md) | 자료·URL은 결정 대기 |
| FAQ 외부 링크·URL | [홈](product/home.md), [범위](product/scope.md), [결정 대기](product/decisions.md) | [FAQ 검토용 초안](product/faq-draft.md)은 외부 콘텐츠 참고 기록 |
| 굿즈·재고·현장 송금 | [굿즈샵](product/goods.md) | 계좌·운영값은 결정 대기 |
| 외부인 티켓·현장 수령 | [외부인 티켓](product/ticket.md), [지도](product/map.md) | 계좌·운영값은 결정 대기 |
| 공연·라인업 | [라인업](product/lineup.md) | 타임테이블은 해당 행도 적용 |
| 타임테이블·현재 시각선·반입 금지 물품 안내 | [타임테이블](product/timetable.md), [라인업](product/lineup.md) | 구현 시 API v2 `getTimetable`, `getProhibitedItems` 계약 동기화 |
| 부스·주점·플리마켓·메뉴 | [부스&마켓](product/spaces.md) | 위치 연결은 지도 |
| 지도·좌표·필터 | [지도](product/map.md), [학교 용어](product/terminology.md) | 지도 API·자산 |
| 학교 명칭·공식 번역 | [용어](product/terminology.md), [승인 번역표](product/translations.md), [결정 대기](product/decisions.md) | 다국어 |
| 스탬프·QR·경품 | [스탬프](product/stamp.md), [보안](engineering/security.md), [검증 서비스](engineering/test-service.md) | API v2 `getStampGuide`, `verifyStampReceipt` 계약 동기화 |
| PWA·푸시·익명 상태 복원 | [검증 서비스](engineering/test-service.md), [보안](engineering/security.md), [운영](engineering/operations.md) | 해당 test 구현·API 절 |
| 사용자 화면·문자열·번역 | [다국어](engineering/i18n.md), [승인 번역표](product/translations.md), [품질](engineering/quality.md) | 해당 화면 도메인 |
| API·DB·schema·데이터 계약 | [API 규칙](engineering/api.md), [데이터 모델·ERD](engineering/data-model.md), [API 절 찾기](engineering/api-navigation.md) | 부스·지도 구현은 [부스·지도 공개 카탈로그](engineering/spaces-map-backend.md), 해당 도메인·계약 테스트 |
| 원격 DB 최초 연결·migration·catalog import/publish 전 | [DB 읽기 전용 사전 점검](engineering/database-preflight.md), [운영](engineering/operations.md) | [원격 개발 환경 결정](../dev-deployment-decision.md) |
| TICKET·GOODS 계좌 설정 CLI·역할 provisioning·retention | [계좌 운영 설정](engineering/operational-account-settings.md), [DB 읽기 전용 사전 점검](engineering/database-preflight.md), [보안](engineering/security.md) | 티켓 read 전환은 [외부인 티켓](product/ticket.md) |
| 관리자·인증·공개 쓰기·저장·로그·업로드 | [보안](engineering/security.md) | 관리자·미디어 API |
| 데이터 정리·보관 기간 enforcement | [데이터 정리](engineering/cleanup.md), [보안](engineering/security.md) | 실제 대상 추가 시 해당 schema·운영 runbook |
| 콘텐츠·번역 게시·예약·revision | [게시](engineering/publishing.md), [운영](engineering/operations.md) | 관리자 콘텐츠 API |
| 로컬 워크벤치로 catalog export·검증·비교·게시 | [로컬 카탈로그 워크벤치](engineering/catalog-workbench.md), [게시](engineering/publishing.md), [DB 읽기 전용 사전 점검](engineering/database-preflight.md) | role provisioning은 [계좌 운영 설정](engineering/operational-account-settings.md) |
| 캐시·배포·복구·인프라 | [운영](engineering/operations.md), [품질](engineering/quality.md), [검증](workflow/validation.md) | 변경 대상 runbook |
| branch·worktree·동기화·push | [작업 절차](workflow/task.md), [브랜치](workflow/branches.md) | PR 규칙 |
| 운영·카탈로그 장기 구현 이어받기 | [운영·카탈로그 구현 인수인계](workflow/ops-catalog-handoff.md) | 현재 미완료 단계와 검증 기록 |
| commit·squash 메시지 | [커밋](workflow/commits.md) | breaking change 계약 |
| 검증·완료 보고 | [검증](workflow/validation.md), [완료 조건](workflow/done.md) | 해당 항목만 적용 |
| PR·리뷰·병합 | [PR](workflow/pull-requests.md), [완료 조건](workflow/done.md) | PR 템플릿 |
| hotfix·release·tag | [릴리스](workflow/releases.md), [PR](workflow/pull-requests.md), [운영](engineering/operations.md) | 배포 runbook |
| 위키·컨텍스트 문서 | [위키 관리](MAINTENANCE.md) | 변경하는 주제 페이지 |

## 원문과 목차

- [제품 컨텍스트 목차](../PRODUCT_CONTEXT.md)
- [협업 규칙 목차](../../CONTRIBUTING.md)
- [API v2 명세](../../핵심%20기능%20API%20명세서%20v2.md)
- [검증 서비스 README](../../test/README.md)
- [PR 템플릿](../../.github/PULL_REQUEST_TEMPLATE.md)

API와 배포 runbook은 단일 원문을 유지한다. 필요한 절을 찾아 읽고 내용을 위키에
복제하지 않는다.

## 화면 데이터 기반 API v2

새 API·목 연동 작업은 [v2 명세](../../api-v2/README.md),
[화면별 필드 추적](../../api-v2/SCREEN-DATA.md), [프런트 실행 안내](../../api-v2/FRONTEND.md),
[결정 대기](../../api-v2/DECISIONS.md)를 함께 읽습니다.

관리자 운영 시간·공지 작성/번역·굿즈 상품/재고 변경 작업은 [새 관리자 명세 반영](../../api-v2/ADMIN-CHANGES.md)과 해당 도메인 페이지를 함께 읽습니다.
