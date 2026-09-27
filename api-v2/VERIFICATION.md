# API v2 검증 기록

## 2026-09-15 · API 계약 검증

환경: Windows, Node.js 24.
기준: main 63ccdf383839718faf4172cac578f672dd928658 및 사용자 FAQ 외부 링크·START 전 QR 진입·현장 수령 인증 코드 결정.

| 검사 | 실제 결과 |
|---|---|
| npm ci --ignore-scripts | 16개 패키지 설치, audit 취약점 0 |
| `node generate.mjs --check` 및 `node --test contract.test.mjs` (지정 Node 24) | 38개 API, 26개 화면, 187개 매핑, 예제263개. 테스트 288개 통과, 실패·건너뜀0 |
| OpenAPI·JSON Schema | Swagger Parser OpenAPI3.1, Ajv2020/formats와 실제 HTTP 응답 검증 통과 |
| HTTP 예제 | 263개 요청의 상태 코드·본문·스키마 일치 |
| 스탬프 수령 인증 | 현장 코드 정상·오류 응답, 코드 미노출, 성공 뒤에만 브라우저 `claimed` 상태 전환 검증 |
| 관리자 v5 | 시간 편집·수량 경로 제거, 실제3조합 독립 상태, 잘못된 조합·quantity 거절 |
| 신규 상품·옵션 | 최초 상태 미정409·실패 후 미변경, 명시적 목 상태 성공, 기존 상태 보존·삭제 거절 |
| 공지 | 한국어 우선 게시·영어 실패/재시도·READY만 노출, createdAt 불변·줄바꿈·삭제·템플릿 보존 |
| 시간·지도 | KST 자정 혼잡도/일반 공지 초기화, 티켓 마감·재개, 지도 ID·버전 연결 |
| 고정 공연 안내 | 공연 전·중·후 조회, 영어 응답의 한국어 대체 없음, 기본 시간축22:00 |
| 인증·입력 | 공개 비로그인·관리자 권한, 잘못된 입력, CORS, 세션 격리 검증 |
| 문서 상대 경로 | 담당 에이전트가 누락0 보고. 최종 검토에서 별도 재실행하지 않음 |
| git diff --check | 통과 |
| npm start | 이번 작업에서는 재실행하지 않음 (아래 미실행 범위에 기재) |

제품 UI·실제 Spring Boot·DB·배포 구성은 변경하지 않아 Maven, 실DB, 모바일 화면 검사는
실행하지 않았습니다. 이번 작업에서는 `npm start`도 재실행하지 않았습니다.
목 콘솔 브라우저 조작, 실기기 QR/카메라, 실제 인증·번역·이미지 업로드·
송금 연결, 운영 환경 갱신 지연은 미검증입니다. 예제는 가상 데이터이며 운영 승인을 뜻하지 않습니다.

원격 Google Sheets `화면 요구사항 정의 · 전체 화면 통합 작업`은 02 데이터와 책임의
상품 이미지·색상·사이즈·지도 위치 식별, 03 동작 정의의 혼잡도·상품 등록/수정,
04 상태 정의의 상품 입력·저장 실패, 05 조회 규칙의 동일 상태·상품 삭제,
07 검토의 동일 상태 셀을 수정했다. 값 필드만 갱신했으며 표본 셀의 서식·드롭다운을 확인했다.
중간 업데이트의 과도한 범위 지정으로 기존 값 1,124개가 비워져 원본 읽기 기록에서
복원했다. 8개 탭의 원본 범위 전체 비교와 검토 탭 121~127행 추가 비교에서
기존 값의 누락이 없고 의도한 25개 셀 변경만 남음을 확인했다. 시각 렌더링은 미검증이다.
저장소 SCREEN-DATA.md와 SCREEN-STATES.md도 같은 기준으로 갱신했으며 이전 원문
스냅샷은 비교용으로 보존했다.

## 2026-09-25 · 아티스트 Hyped 변경 검증

이 절은 위 2026-09-15 기록과 다른 후보인 [PR #97](https://github.com/LikeLion14th-ERICA/fall-festival-server/pull/97)의
병합 커밋 `43ef081`의 코드 기준이다. `npm run check`에서 OpenAPI 49개 operation, 화면 26개,
데이터 매핑 189개(현행 추적 178개·제외 11개), 응답 예제 381개와 계약 테스트 425개가
통과했다. Hyped GET/POST는 같은 OpenAPI, 목 서버, 화면 데이터 연결에 포함된다.

PR CI에서는 Java 21/25 `verify`, `api-v2-contract`, `artist-hyped-load`, CodeQL,
Docker image·filesystem 보안 검사가 통과했다. Java 21 로그의
`ArtistHypedHttpE2eTest` 2개는 실패·건너뜀 0개였고, 전용 부하 단계는
실제 HTTP와 임시 PostgreSQL에서 성공 POST 총수와 최종 HTTP·DB 누적 수의 일치를
검사한다. [부하 실행 방법](../tools/load-test/README.md)과
[릴리스 HTTP E2E](../docs/wiki/workflow/release-http-e2e.md)에 범위가 있다.

이 검증은 분리된 합성 환경과 백엔드 계약을 대상으로 했다. 실제 프런트 화면, 배포 ingress,
운영 proxy·공유 NAT에서의 제한 동작과 staging 용량은 별도 검증 대상이다.

## 2026-09-28 · 재학생존 운영 시간 문서·계약 상태

이번 기능의 API 계약 생성 결과는 52 operations, 27 screens, 189 data mappings(추적 183개·제외 6개), 예제 408개다. 계약 생성은 통과했다. 기존 제외 이력은 위 2026-09-15 기록과 API 관리자 변경 기록에 남기고, 현재 운영 시간 화면 매핑은 다시 활성화했다.

통합 checkout에서 백엔드·테스트 컴파일과 로컬 단위·OpenAPI provider 테스트 33개가 실패·건너뜀 없이 통과했다. API v2 `npm run check`도 통과했다. 생성 일치·release operation coverage·OpenAPI 표준 파싱·JSON Schema·목 HTTP 동작을 포함한 계약 테스트 458개가 실패·건너뜀 없이 통과했다. 격리 worktree의 의존성 부족으로 중단됐던 검사는 통합 checkout의 설치된 의존성으로 실행했다. 검증 중 발견한 PUT 예제 ETag·시간대 예제와 목 소수초 경계 판정을 수정했다.

이 PC에는 Docker가 없어 PostgreSQL migration·role·preflight 통합 검증, 실제 Spring HTTP E2E와 공개 조회 67 RPS 부하 회귀는 로컬에서 실행하지 못했다. 해당 테스트는 공통 PR CI에 연결됐고 V30→V31 업그레이드·무 backfill·제약 검사는 별도 `pg17-migration` 작업에서 PostgreSQL 17로 실행한다. 원격 CI 결과는 아직 대기 상태이며 이 기록은 전체 CI 통과를 뜻하지 않는다. 실제 관리자 UI 구현, 브라우저 polling 확인, staging 배포 뒤 승인 운영 시간 저장과 배포 환경의 재시작·재게시·rollback 보존 확인도 남아 있다.

## 2026-09-28 · 야간 운영 경계 추가 통합 검증

기준: 익일 `01:00` 종료 상한, KST 자정 운영일 선택, 운영일별 혼잡도 idempotency 및 stale ETag 처리.

| 검사 | 실제 결과 |
|---|---|
| API v2 `npm run check` | 통과. 52 operations, 27 screens, 189 data mappings, 408 example responses, release operation coverage 52/52, 계약 테스트 460개. 실패·건너뜀 0 |
| 생성·표준 파싱·JSON Schema | 생성 산출물 일치, OpenAPI parser, JSON Schema 검증 통과 |
| 로컬 Java 검증 | main/test 컴파일과 focused Java 26개 통과. 실패·건너뜀 0 |
| Docker 기반 검증 | 이 PC에 Docker가 없어 V31→V32 PostgreSQL migration, role/preflight 통합 검증과 Spring HTTP E2E를 실행하지 못했다. 최신 push의 PR CI 결과를 기다리는 중이며 아직 통과로 기록하지 않는다 |

계약 테스트에서 같은 밤 운영일을 유지한 자정 뒤 저장·완료 재시도, 종료 경계의 stale ETag 충돌,
다음 날짜에서 같은 key를 새 범위로 사용하는 경우를 확인한다. 겹치는 전날·당일 일정의 선택 우선순위와
당일 CLOSED 유지도 unit/consumer에서 확인한다. 실제 UI와 운영 DB·배포 환경의 호환성은 이 검사에
포함되지 않는다.
