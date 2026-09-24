# LOVE-001 릴리스 검증 시나리오

[릴리스 HTTP E2E 개요](release-http-e2e.md) · [제품 명세](../product/love-letter.md) ·
[운영·연동 인계](love-letter-operations.md) · 읽는 때: 러브레터 후보의 자동·staging·현장 검증

이 문서는 **시나리오 정의**다. 테스트가 존재해도 해당 후보에서 실행한 결과와 승인된
staging 증거가 없으면 `PASS`가 아니다. 공개 UI와 실제 초대 링크 발송은 현재 백엔드 PR
범위 밖이므로 러브레터 **활성화 후보**의 관련 단계는 릴리스 전에 `BLOCKED`로 유지한다.
이번에 기능을 공개하지 않는 서버 배포라면 비활성·비노출을 확인하고 활성화 gate를
후속 릴리스로 넘겼다는 승인 근거를 남긴다. 테스트 데이터는 가상 이름과
연락처만 사용하고 응답 원문·쿠키·초대 토큰·암호화 키를 로그, HAR, 스크린샷, CI artifact에
남기지 않는다.

## 기존 테스트 전체 감사와 추가 위치

| 계층 | 현재 근거 | 이번 보강 및 남는 출시 검증 |
| --- | --- | --- |
| 서비스 단위 | `LoveLetterServiceTest`의 시각·입력·암호화 검사 | 59/60초, 정확한 09:00/00:00, 동의·길이·줄바꿈·키 재사용과 종료 경계를 보강한다. DB 원자성의 증거로 대체하지 않는다. |
| PostgreSQL·MockMvc | `LoveLetterFlowIntegrationTest`의 기본 흐름·초대·동시성·차단·정리 | 동일 브라우저 경쟁, 초대 재발급/동시 claim, 관리자 권한·감사, rate limit·Origin/CSRF, 7일 cleanup의 전체 FK 트리와 오류 후 재시도를 확인한다. `disabledWithoutDocker`로 skip되면 릴리스 PASS가 아니다. |
| 공개 API v2·목 서버 | `contract.test.mjs`·OpenAPI·예시·화면 상태 | 15개 작업의 개인정보 `no-store`, 60초 대기/개봉, 오류 코드·입력 한계와 미노출을 계약·목 응답에서 확인한다. 목 서버의 성공은 운영 보안·DB 잠금의 증거가 아니다. |
| 실제 HTTP | `src/test/java/.../e2e`의 기존 HTTP-01–31 | HTTP-32–36을 추가해 랜덤 포트 서버와 실제 쿠키·CSRF·Origin·관리자 인증·PostgreSQL을 통과시킨다. 직접 service 호출이나 MockMvc만으로 HTTP 경계를 통과 처리하지 않는다. |
| PostgreSQL 17 migration | `Postgresql17MigrationReleaseTest` | 새 DB V1–V30, V23 fixture의 V24–V30 순차 upgrade, V30 유일 제약·FK·restart/checksum을 확인한다. 구버전 앱의 실제 운영 rollback과 백업 복원은 별도 staging 절차다. |
| release mapping·CI | `release-operation-coverage.mjs/json`, `release-test-selection.mjs` | 러브레터 15개 작업을 HTTP-32–36과 연결하고 실제 JUnit method가 없거나 누락된 작업이 있으면 검사를 실패시킨다. PR의 `pg17-love-letter-regression`과 후보별 Java 21/25·전체 PG17·보안 CI 결과를 구분해 연결한다. |
| 부하·실기기·운영 | `tools/load-test/`, STAGE-01–05, OA 가이드 | 일반 공개 부하 지표를 러브레터 쓰기 용량으로 오인하지 않는다. STAGE-06에서 익명 브라우저·09시·60초·차단·복원·cleanup을 가상 데이터로 연습하고 임계값을 승인받는다. 별도 PWA/Web Push `test/`는 러브레터 서비스가 아니므로 변경 범위와 실행 결과를 구분한다. |

## 자동 HTTP 위험 시나리오

아래 그룹은 각각 새 Testcontainers PostgreSQL과 랜덤 포트 Spring 서버의 실제 HTTP를
통과한다. 요청의 `requestId`·상태 코드·안정 오류 코드·`Cache-Control: no-store`와
DB 행 수/유일 제약을 함께 보며, 본문 원문 대신 필요한 비노출 여부를 검증한다.
모든 사전 쪽지·연락처는 합성 fixture다.

| ID | P | 절차와 합격 기준 | 주요 자동 근거 |
| --- | --- | --- | --- |
| HTTP-32 | P0 | 관리자 구성 직후 기본 비활성 → 동의받은 남·여 seed 등록 → 활성화. 한쪽 pool·키·기간·Origin 미설정이면 활성화 실패; 익명 안내와 세션 발급은 실제 운영 상태와 일치. 허용되지 않은 Origin/CSRF와 관리자 비인증 요청은 DB·감사 부작용 없이 거절. | `LoveLetterReleaseHttpE2eTest`의 관리자/익명 경계 |
| HTTP-33 | P0 | 익명 쿠키로 남→여·여→남 등록 시 내 쪽지·타인 쪽지 배정을 원자적으로 확정. 등록 성공에 연락처·결과 ID 없음; 59초 상태는 `WAITING`/ID 없음, 60초는 `SEALED`/ID 있음, 조기 개봉 `LOVE_WAITING`, 본인 개봉 후 원문과 재방문 연락처만 제공. 다른 쿠키는 ID를 알아도 개봉·신고 불가. | 실제 HTTP + DB 배정/암호문 검증 |
| HTTP-34 | P0 | 마지막 이성 후보에 두 브라우저가 동시 등록하면 하나만 성공하고 실패한 요청은 쪽지·날짜 기록 없음. 같은 키·본문 재전송은 원래 `letterId`/`revealAt`을 복구, 다른 본문의 키 재사용은 충돌. 쪽지 부족과 DB 오류 후 재시도에도 중복 배정·참여 차감 없음. | 동시 HTTP 요청 + 제약/행 수 검증 |
| HTTP-35 | P0 | 관리자 사전 등록·링크 재발급은 옛 링크를 무효화. 지정일 연결은 재작성 없이 배정을 시도하고 60초 대기. 후보 부족이면 `SEEDED`와 지정일 권리를 유지해 서버가 재시도; 지난 지정일·재사용·동일 브라우저 당일 참여와 충돌하면 귀속/배정 없음. | 실제 관리자·초대 HTTP + retry/DB 검증 |
| HTTP-36 | P0 | 본인만 신고 → 관리자 목록/상세에서 처리 → 차단 시 추가 추첨·기존 수신자의 연락처 재열람 금지, 당일 재추첨 없음. 작성자/신고자 각각 제한·해제, 기능 중지는 신규 등록과 열람을 막는다. 비관리자에게 신고 원문·관리자 기능 노출 없음. | 실제 신고/관리자 HTTP + 감사/DB 검증 |

### 세부 경계·실패 매트릭스

각 행은 위 HTTP 그룹, MockMvc, 단위, 계약, staging 중 **어느 층에서 증명됐는지**
기록한다. 실제 release 판정에는 같은 후보의 실행 결과를 연결한다.

| 영역 | 입력·실행 | 확인할 결과와 영속 상태 |
| --- | --- | --- |
| 입력·동의 | 성별 누락/기타 값, 이름 0/20/21자, 내용 0/100/101자·개행, 연락처 0/100/101자, 성인·본인 연락처 확인 false, 동의문 버전 불일치 | `422` 또는 명세상 안정 오류, 쪽지·배정·참여 행 증가 0. 실제 연령·연락처 인증을 성공으로 주장하지 않는다. |
| 시간 1 | 첫날 08:59:59/09:00:00, 매일 23:59:59/다음날 00:00:00/08:59:59/09:00:00 KST | 09:00에만 참여·열람 가능. 00:00~09:00은 `BEFORE_OPEN`, 다음 09:00 안내. 마지막 날 다음 00:00은 `CLOSED`와 다음 참여 시각 null. |
| 시간 2 | 23:59:30 배정 후 자정, 재개장 09:00, 행사 종료 직전 배정 | 자정 중에는 60초가 지나도 결과 ID·연락처를 공개하지 않고, 운영 중 재개 시 서버 기준 60초 규칙을 적용. 행사 종료 뒤에는 다시 열지 않는다. |
| 배정 | 양방향 성별, 자기 쪽지, 미배정 후보 다수, 차단 후보, 서로 같은 두 사람 | 다른 성별·타인의 유효 미배정 쪽지 하나만 배정하며 쪽지당 수신자 1명 유일. 상호 배정 보장은 테스트 기대값이 아니다. 무작위성은 특정 쪽지 ID를 고정하지 않고 후보 집합·유일성으로 검증한다. |
| 경쟁 | 마지막 쪽지 동시 등록, 같은 브라우저 동일/상이 키 동시 요청, 등록과 사전 링크 claim 교차, 사전 링크 동시 claim, 설정 변경과 seed 등록 교차 | 한 참여자·축제일 한 번, 쪽지 한 번 배정. 패자는 안정 오류·무부작용. 경쟁 테스트는 timeout/DB deadlock 없이 끝나야 하며 실패를 무작정 재시도해 숨기지 않는다. |
| 재시도 | 응답 유실 뒤 동일 키/본문, 같은 키 다른 본문, 키 없이 쓰기, network timeout 후 상태 조회 | 동일 결과·시각을 복구하고 원문 응답을 멱등 기록에 보관하지 않는다. 충돌·잘못된 키는 쪽지·배정을 추가하지 않는다. |
| 상태/소유권 | 새 브라우저, 다른 브라우저에 노출된 ID, 새 축제일, 성공한 새 배정 | 다른 쿠키의 상태/개봉/신고 거절. 다음 날 새 작성 허용, 새 결과로 최근 결과 대체, 재방문에는 최근 연락처만 표시. 쿠키 삭제/기기 변경 우회 가능성은 제품 한계로 기록. |
| 개인정보 | DB ciphertext·token hash, 공개 guide/status/register/open/report, 관리자 목록·상세, 오류·감사·로그 | 이름·내용·연락처는 DB에서 평문이 아니고 개봉/권한 있는 관리자 상세 외 비노출. 개인정보 응답 `no-store`; requestId·오류·감사·trace에 원문/토큰 없음. 키 버전·AES-GCM 무결성 실패도 평문으로 대체하지 않는다. |
| 웹 보안 | `Secure; HttpOnly; SameSite` 전용 쿠키, 누락/위조 Origin·CSRF, 허용/타 Origin CORS, 없는/만료/타인 쿠키, rate limit | 쓰기 전부 거절 또는 허용 정책 일치. `Retry-After`와 사용자별·프록시 경계, 제한 만료 후 복구를 확인. 61+ 독립 브라우저가 하나의 ingress IP에 묶일 때 허용량과 정책을 검증한다. |
| 사전 모집 | 양쪽 seed, 지정일 전/후, 링크 재발급·재사용·동시 claim, 후보 없는 지정일, retry scheduler 재시작 | 실참여 동의 시각·버전 저장, 원문 링크는 한 번만 전달·DB는 hash. 후보 없는 seed의 권리 보존/자동 재시도, 지난 지정일 이월 없음. 재시작 후도 중복 배정 없음. |
| 신고·운영 | 수신자/타인의 신고, 관리자 목록·상세, 잘못된 관리자 권한, 차단, 제한/해제, 기능 중지·재활성 | 신고만으로 자동 차단·당일 재추첨 없음. 차단 직후 추가 추첨/연락처 차단. 관리자 변경은 대상 ID·조치만 감사. 기능 중지는 공개 열람도 즉시 차단. |
| 정리·복구 | 종료+7일 직전/정각, 배치 상한, 참여자·쪽지·배정·신고·링크·재시도 기록, 실패 뒤 재실행, 다른 축제 | 기한 전/타 축제는 보존, 기한 뒤 FK 트리 삭제. 배치 제한 내 점진 삭제·실패 후 멱등 재실행. 백업 복원 직후 기능 비활성·기한 지난 개인정보 재삭제 및 키 보존 절차는 staging 증거 필요. |

## 계약·목 응답 점검

- OpenAPI 15개 LOVE operation의 메서드·경로·권한·locale·Origin·CSRF·쿠키·멱등 헤더를
  실제 controller와 맞춘다. 공개 운영 안내·상태를 제외한 개인정보 경로는 `no-store`다.
- 성공 예시는 등록의 `letterId/revealAt/WAITING`에 연락처·배정 ID가 없고, 상태의
  `WAITING`에 `exchangeId=null`이며, `SEALED`에 ID만, `OPENED`에 최근 연락처만 있다.
  이름·내용·연락처는 본인 개봉 응답 또는 관리자 상세에서만 나타난다.
- `LOVE_POOL_EMPTY`, `LOVE_WAITING`, `LOVE_ALREADY_PARTICIPATED`,
  `LOVE_IDEMPOTENCY_CONFLICT`, `LOVE_INVITATION_INVALID`, `LOVE_CSRF_INVALID`,
  `LOVE_RESTRICTED`, `LOVE_RESULT_BLOCKED`, `LOVE_CLOSED`, rate limit과 입력 오류의
  HTTP 코드·`retryable`·다음 시각을 문서/목/서버 사이에서 대조한다.
- 목 서버는 UI 상태 개발용이다. 오프라인 예시 한 건의 `normal` 응답을 실제 DB·소유권
  검사의 근거로 사용하지 않는다. 별도 mock session을 섞어도 개인정보가 누출되지 않는지
  확인한다.

## STAGE-06 · 러브레터 공개 전 리허설

실제 ingress·브라우저·프록시·백업·알림은 자동 테스트가 대신할 수 없다. 이 단계는
[staging 상세 케이스](release-http-e2e-staging.md)의 STAGE-06과 같은 후보 digest에
연결한다. 러브레터 활성화 후보에서 기능이 비활성이거나 프런트가 미구현이면
`N/A`가 아니라 `BLOCKED`다.

| 단계 | 실행/증거 | 통과 기준 |
| --- | --- | --- |
| 06.1 배포·활성화 | V30 checksum, 서버 키·Origin reference, 기간·동의문 승인, staging의 양쪽 합성 pool, 운영에서 실제 참여자 동의 확보 증빙, 운영자·신고/문의 담당 | 비활성 배포 → 양쪽 seed 확인 → 승인 후 활성. 실제 개인정보는 staging에 복사하지 않고 키·원문·링크를 evidence에 넣지 않는다. |
| 06.2 공개 브라우저 | 모바일 390×844·좁은 화면·지원 브라우저, 한국어/영어/확정 중국어와 긴 번역, 스크린리더·키보드·초점·복사 | 홈→소개→필수 입력/동의→등록/대기→봉투→재방문/다음 날·오류 상태가 로그인 없이 동작하고 원문은 번역·영구 저장되지 않는다. |
| 06.3 실제 시각·네트워크 | 네트워크 유실 후 같은 키 재전송, 60초와 09시/자정 경계, 숨김/복귀, 다른 브라우저, 초대 fragment | 서버 `revealAt`으로 대기하고 중복 등록 없음. 쿠키·CSRF/CORS·`no-store`·referrer/로그 비노출. 연락처를 자동 외부 실행하지 않는다. |
| 06.4 운영 안전 | 신고→차단·제한→기능 중지, seed 자동 재시도·마지막 후보 동시 부하, 61+ 클라이언트/동일 프록시, 모니터링 | 추가 추첨/공개 열람 즉시 차단, 부하·429·DB 잠금과 retry/timeout 지표·알림을 승인된 임계값으로 판정. 개인 연락처가 지표/trace에 없음. |
| 06.5 보관·복원 | 별도 staging 백업 복원, 종료+7일 cleanup dry run/실행/실패 재처리, 구버전 image 호환성 | 복원 직후 비활성, 기한 지난 개인정보 재삭제·백업 만료 기록. 실제 RPO/RTO와 이전 image의 V30 스키마 호환성이 승인돼야 한다. |

## 실행·판정 기록

`api-v2` 계약은 `npm run generate && npm run check`, 서비스는
`./mvnw.cmd --batch-mode --no-transfer-progress verify`, PostgreSQL 17 릴리스 대상은
`./mvnw.cmd --batch-mode --no-transfer-progress -Prelease-pg17
"-Dtest=Postgresql17MigrationReleaseTest,LoveLetterReleaseHttpE2eTest,..." test`로 확인한다.
Windows PowerShell에서 `./mvnw.cmd` 대신 `.\mvnw.cmd`도 가능하다. `...`는 실제 선택한
전체 class명으로 바꾸며 게이트 명령으로 그대로 복사하지 않는다. PG17 anchor class 누락,
0건, skip, Docker 부재는 실패다. 정확한 후보별 선택 명령은 `node
api-v2/release-test-selection.mjs`와 [검증 명령](validation.md)을 따른다.

결과에는 `scenarioId`, 후보 SHA/image digest/OpenAPI hash/V30 checksum, 환경·실행 시각,
테스트 class/method·명령·실제 PASS/FAIL/SKIP, request ID, 비식별 DB 행 수와 운영
관측 참조를 남긴다. mock·로컬·CI·staging의 증거는 각각 별도 기록하고, 기획상 미구현
화면/발송/승인 문안은 결과 없이 PASS로 승격하지 않는다.
