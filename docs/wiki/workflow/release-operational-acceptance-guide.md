# 릴리스 운영 수용 검증 가이드

[위키 홈](../README.md) · 읽는 때: 자동 release E2E 이후 staging·운영 배포와 현장 수용 검증을 실행할 때

이 문서는 자동화로 대체할 수 없는 출시 검증을 담당자가 실행하고 판정하는 가이드다.
자동 검사와 후보별 증거 묶음은 [릴리스 증거 runbook](release-evidence-runbook.md), 실제
staging ingress·브라우저·media·부하 절차는
[릴리스 staging 상세 케이스](release-http-e2e-staging.md), 행사 당일 변경과 복구 작업은
[행사 당일 운영 절차서](festival-day-runbook.md)를 따른다. 이 문서는 그 명령을 복제하지 않고
운영 수용 범위, 승인 순서, PASS/BLOCKED 기준과 담당자 handoff를 정의한다.

실제 host, credential, 계좌 원문, stamp code·hash, dump, media archive, Caddyfile,
Cloudflare zone·rule 값과 개인 연락처는 저장소에 기록하지 않는다. 보호된 운영 기록의
reference ID와 검증 결과만 연결한다.

## 1. 공통 안전 게이트와 역할

모든 쓰기·재시작·복구·부하·장애 주입은 운영과 분리된 staging에서 먼저 수행한다. 운영에서
필요한 최종 확인은 읽기 전용 대조를 기본으로 한다. 운영 쓰기나 장애 리허설이 꼭 필요하면
승인 범위, 변경 대상, 시간대, 실행자, 독립 확인자, rollback 담당자와 중단 조건을 먼저
기록한다. 승인되지 않은 운영 데이터 수정, cache purge, container 교체 또는 복원은 실행하지
않는다.

| 역할 | 책임 | 시작 전 확인 |
| --- | --- | --- |
| 릴리스 책임자 | 전체 순서와 최종 판정 | 동일 `evidenceId`와 후보 digest, 승인·변경 창 |
| C 인프라 담당 | image, container, Caddy·Cloudflare, alert | 현재·이전 artifact와 config reference, rollback 경로 |
| D 데이터 담당 | DB, Flyway, 계좌, paired recovery | mutation 승인, recovery set, RPO/RTO |
| B 콘텐츠 담당 | catalog와 승인 운영 데이터 대조 | 승인 자료 버전, published revision |
| A 서비스 담당 | 관리자·공개 기능과 현장 흐름 확인 | 지원 browser/device, 운영자 인수 기준 |
| 독립 확인자 | 증거와 PASS/BLOCKED 검토 | 실행자와 다른 사람, 비밀값 미노출 확인 |

다음 중 하나면 해당 시나리오는 시작하지 않고 `BLOCKED`로 기록한다.

- 후보 Git commit, immutable image digest, OCI revision label이 서로 연결되지 않는다.
- staging이 운영 DB·media·계좌·stamp secret과 분리됐는지 확인할 수 없다.
- Flyway history/checksum이 불일치하거나 `STOP_AND_REVIEW`, `mutationAuthorized=false`가 남아 있다.
- 이전 image/config 또는 같은 ID의 DB+media recovery set을 확인할 수 없다.
- 실행자·독립 확인자·rollback 담당자, 실제 연락 채널과 변경 승인이 없다.
- 승인된 RPO/RTO, required smoke와 alert 수신 경로가 없다.

`FAIL`은 실행 결과가 합격 기준을 위반한 상태다. `BLOCKED`는 전제·승인·증거가 없어 실행하거나
판정할 수 없는 상태다. 둘 다 운영 배포를 허용하지 않는다. `N/A`는 기능이나 경로가 후보에
존재하지 않음을 승인 자료로 증명한 경우만 사용한다.

## 2. 공통 증거 양식

각 시나리오는 아래 필드를 같은 보호된 작업 기록에 남긴다. screenshot·HAR·로그에는 token,
cookie 값, 계좌 원문, stamp code, 개인정보가 없어야 한다.

```text
evidenceId: <protected-record-id>
scenarioId: OA-01 .. OA-08
environment: <staging-or-approved-production-check>
candidateCommit: <git-sha>
candidateImageDigest: sha256:<digest>
catalogRevision: <revision-id>
startedAt/finishedAt: <Asia/Seoul timestamps>
operator/reviewer/rollbackOwner: <names-or-protected-references>
approvalReference/changeWindow: <protected-reference>
preState/postState: <safe summaries and artifact references>
expected/actual: <acceptance criterion and observed result>
evidenceArtifacts: <redacted log, screenshot, HAR, metric, checksum references>
status: PASS | FAIL | BLOCKED | N/A
rollbackDecision/result: <not-needed-or-action-reference-and-post-smoke>
openRisks/owner/dueAt: <none-or-explicit entries>
```

후보 image, catalog revision, migration, ingress, media mount, cache rule 또는 승인 운영 데이터가
바뀌면 영향받은 시나리오를 다시 실행한다. 이전 후보의 PASS를 옮겨 쓰지 않는다.

## 3. OA-01 · 실제 staging과 운영 배포 수용

**목적:** 검증한 후보와 실제 배포본이 같고, staging에서 검증한 배포 경로를 승인된 운영
설정으로 승격했음을 증명한다.

### 실행 순서

1. [릴리스 증거 runbook](release-evidence-runbook.md)의 필수 gate가 같은 commit·image digest를
   가리키는지 확인한다. 빠진 gate가 있으면 배포하지 않는다.
2. staging의 image digest, DB target reference, `FESTIVAL_ID`, media mount identity,
   ingress/upstream reference와 catalog revision을 기록한다.
3. [STAGE-01](release-http-e2e-staging.md#stage-01--artifactmigrationpublished-snapshotpublic-probe)과
   STAGE-02~05 중 후보에 적용되는 모든 결과가 PASS인지 확인한다.
4. 운영 배포 전 현재 image/config, 이전 known-good image/config, paired recovery set과 rollback
   담당자를 기록한다. 실제 값은 보호된 기록에서만 참조한다.
5. 독립 확인자가 staging 후보 digest와 승격 대상 digest가 같은지 확인하고 운영 배포를 승인한다.
6. 승인된 운영 절차로 배포한 뒤 public HTTPS에서 `/healthz`, `/readyz`, config의
   `meta.revision`, 대표 공개 조회, 관리자 로그인, 연결된 media URL을 확인한다.
7. 실제 실행 설정은 secret 값이 아니라 profile, DB·media reference, 공개 locale과 ingress
   연결 여부만 기록한다.

**PASS:** 배포된 immutable digest와 후보가 같고 required probe가 성공하며, 의도한 revision과
대표 공개·관리자·media smoke가 모두 통과한다. **FAIL/BLOCKED:** digest 불일치, 설정 reference
누락, probe·smoke 실패, 승인되지 않은 migration 또는 운영 자료 불일치가 하나라도 있다.

**중단·복구:** traffic 전환을 중단한다. artifact/config 문제는 확인된 이전 image/config로,
catalog 문제는 archived 내용을 새 revision으로 publish한 뒤 controlled restart로 복구한다.
DB/media 복원은 데이터 손상이 확인된 경우에만 OA-03 절차로 수행한다. 복구 후 같은 public
smoke를 다시 통과하기 전에는 운영 재개로 판정하지 않는다.

## 4. OA-02 · 승인 운영 데이터 대조

**목적:** 문법상 유효한 fixture가 아니라 승인된 실제 정보가 공개 응답과 화면에 정확히
반영됐음을 두 사람이 대조한다.

### 대조 표

| 영역 | 승인 자료와 대조할 값 | 공개 결과의 합격 기준 |
| --- | --- | --- |
| 축제·혼잡도 | 날짜, 일별 개장·마감, 자정 경계 | KST 날짜와 운영 상태가 승인 일정과 일치 |
| 공연 | 단일 무대, 출연진, 순서·시각, 반입 금지 안내 | 목록·상세·타임테이블 관계와 문구가 승인본과 일치 |
| 부스 | 분류, 소개, 메뉴 가격, 이미지, 지도 연결 | 모든 승인 부스가 한 번씩 연결되고 임시값이 없음 |
| 지도 | 최종 이미지·버전, 핀 좌표, 장소 연결 | 승인 배치와 일치하고 같은 물리 장소가 불필요하게 중복되지 않음 |
| 굿즈 | 상품·가격·실제 옵션 조합·이미지·판매 상태 | 수량 모델을 만들지 않고 실제 제공 조합과 상태가 일치 |
| 티켓 | 단가·송금 시간·계좌·티켓존·수령 안내 | 미확정값을 노출하지 않고 승인된 값과 상태만 제공 |
| 스탬프 | 운영일·공통 QR·일일 한도·수령 안내·담당자 | 공통 QR 흐름과 현장 안내가 승인 운영안과 일치 |
| 외부 링크 | URL, 공개 접근, 모바일 가독성 | 로그인 없이 HTTPS로 열리고 승인 대상과 일치 |
| 언어 | 공개 locale, 공식 명칭, 번역 완료 상태 | 준비된 언어만 보이고 미완성 언어 fallback이 없음 |

1. B가 자료별 승인자·버전·승인 시각을 기록하고 임시·미정 값을 분리한다.
2. D는 계좌 원문을 기록하지 않고 승인 담당자와 함께 purpose, version, 은행명·끝 네 자리만
   대조한다. catalog revision과 계좌 version을 별개로 기록한다.
3. B가 API 응답의 ID·revision과 공개 화면을 항목별로 대조하고, A가 모바일 화면과 실제 사용자
   경로를 독립 확인한다.
4. 누락, 중복, 오래된 예시, 미승인 번역은 수정 대상과 소유자를 기록한다. 실제 값으로 추측해
   채우지 않는다.

**PASS:** 모든 공개 항목이 추적 가능한 승인 자료와 일치하고 두 사람의 확인이 있으며,
미정 값은 숨김·준비 전 상태 등 계약된 표현으로 남는다. **FAIL/BLOCKED:** 승인 근거가 없거나
임시값·synthetic catalog·잘못된 계좌·미완성 locale이 공개되면 차단한다.

**중단·복구:** catalog 데이터는 수정본을 새 revision으로 validate·publish하고 restart한다.
공지·굿즈·혼잡도는 관리자 경로, 계좌는 `restore-version` 또는 `clear`로 각각 되돌린다.
정정 뒤 영향받은 표 행과 공개 smoke를 다시 대조한다.

## 5. OA-03 · DB+media 짝 복원 리허설

**목적:** 백업의 존재가 아니라 같은 시점의 DB와 media를 별도 환경에 실제 복원해 RPO/RTO와
데이터·파일 정합성을 증명한다.

1. 운영과 격리된 복원 대상, 승인, cleanup 담당자를 기록한다. 운영 대상 복원은 별도 명시
   승인이 없으면 금지한다.
2. 같은 `recoverySetId`와 생성 시각을 가진 DB dump와 media archive, 각각의 checksum과 보존
   상태를 확인한다. 한쪽만 있거나 시점이 다르면 시작하지 않는다.
3. 복원 시작 시각을 기록하고 DB와 media를 같은 복원 대상에 연결한다. 후보 image digest와
   migration 정책은 사전에 승인된 값만 사용한다.
4. Flyway history/checksum, published revision, 공지·굿즈·계좌의 의미값, DB media reference와
   실제 파일 대응을 확인한다.
5. public HTTPS에서 catalog, 공지, 굿즈, 계좌 상태, 연결된 상품 이미지, `/healthz`, `/readyz`,
   `meta.revision`을 확인한다. staging용 관리자 계정으로 로그인과 승인된 대표 쓰기 한 건을
   확인하고 cleanup한다.
6. 복구된 데이터의 시점, 허용 손실, 총 복구 시간, 누락 파일·dangling reference 수를 기록해
   승인된 RPO/RTO와 비교한다.

**PASS:** checksum이 맞는 paired set이 복원되고 모든 필수 조회·media·관리자 smoke가 통과하며
측정 RPO/RTO가 승인 목표 안에 있다. **FAIL/BLOCKED:** pair/checksum 불일치, 목표 초과, 누락
파일·참조, migration/revision 불일치 또는 required smoke 미실행이 하나라도 있다.

**중단·복구:** 실패한 복원 대상은 운영 traffic에 연결하지 않는다. 원본 recovery set은
보존하고 실패 원인과 재시도 대상을 새 run ID로 기록한다. cleanup도 DB와 media를 같은
recovery set 단위로 처리하며, 성공 증거를 만들기 위해 원본 artifact를 수정하지 않는다.

## 6. OA-04 · restart가 아닌 container 재생성

**목적:** 기존 process 재시작을 넘어 새 container가 동일 DB와 media volume, 승인 설정을
다시 연결해도 파일과 동적 상태가 유지됨을 확인한다.

1. staging의 container ID, current/target image digest, DB·media mount identity, profile,
   `FESTIVAL_ID`, ingress/upstream reference와 OA-03 recovery set을 기록한다.
2. 재생성 전 연결된 실제 staging 이미지 URL과 공지·굿즈 조합 상태·계좌 version 등 동적
   baseline을 기록한다.
3. 승인된 배포 절차를 사용해 container를 새로 만들되 기존 media volume과 DB 설정 reference를
   명시적으로 다시 연결한다. 단순 process restart 결과를 이 단계의 증거로 쓰지 않는다.
4. 새 container ID와 image digest, mount destination·read/write 상태를 확인한다. secret 값이나
   실제 실행 명령 전문은 저장소에 복사하지 않는다.
5. public HTTPS의 health/ready/revision, baseline 동적 값, media URL의 상태·content type을 다시
   확인한다. CDN cache만 보고 파일 보존을 판정하지 않도록 승인된 origin 확인 결과를 함께 남긴다.

**PASS:** 새 container가 목표 digest로 실행되고 동일 media 저장소와 DB를 사용하며 baseline과
media가 보존되고 public smoke가 통과한다. **FAIL/BLOCKED:** mount identity 누락, 이미지 404,
동적 데이터 손실, 잘못된 DB/FESTIVAL_ID, probe 실패 또는 이전 artifact 부재가 있으면 차단한다.

**중단·복구:** known-good image/config로 container를 다시 만들고 동일 mount를 연결한다. media
또는 DB 손상이 확인되지 않았다면 restore를 먼저 수행하지 않는다. 손상이 확인된 경우에만
OA-03의 paired set으로 복원하고 post-recovery smoke를 남긴다.

## 7. OA-05 · Caddy·Cloudflare·browser cache 수용

**목적:** 코드의 header 선언만 확인하지 않고 실제 공개 HTTPS 경로에서 proxy와 edge cache가
동적 상태·locale·조건부 요청을 올바르게 전달하는지 확인한다.

먼저 실제 배포가 Caddy·Cloudflare를 사용하는지 보호된 설정 reference로 확인한다. 사용하지
않는 계층은 근거와 함께 `N/A`로 남기며, 존재하지 않는 cache rule을 추측하지 않는다. origin
직접 비교는 승인된 보호 경로에서만 수행하고 방화벽이나 접근 통제를 우회하지 않는다.

| 사례 | 실행 관측 | PASS 기준 |
| --- | --- | --- |
| 라우팅·TLS | public URL의 API, Caddy upstream, Cloudflare proxy 상태 | 인증서·host·forwarded header·status가 승인 설정과 일치 |
| 혼잡도 | 관리자가 staging 상태 변경 후 즉시 조회와 다음 polling | 오래된 edge 응답 없이 최신 상태 반영 |
| 공지 | 등록·수정·삭제 및 KST 자정 전후 일반/분실물 | 일반 공지만 날짜 경계에 맞게 사라지고 분실물은 유지 |
| 굿즈 | 한 조합의 판매 상태 변경 후 목록·상세·availability | 세 응답이 같은 상태이며 이전 cache가 남지 않음 |
| 티켓·계좌 | 시간 경계와 account version 변경 | 마감 후 계좌가 숨고 상세의 `no-store`가 실제 경로에서도 유지 |
| locale | 같은 URL의 지원 locale 전환 | 다른 언어 응답이 cache key에서 섞이지 않음 |
| ETag | stable ETag의 304, 업무 변경 뒤 이전 validator 재요청 | 불변이면 304, 변경 뒤에는 새 내용·validator를 반환 |
| versioned media | 동일 URL 재조회와 새 version URL | 승인 cache 정책과 content type, 새 자산 전환이 일치 |

각 사례는 public response status, 안전한 header 요약, edge cache 상태, KST 시각, 변경 전후 업무
version을 남긴다. 계좌 원문과 token은 캡처하지 않는다.

**PASS:** Caddy/Cloudflare/browser를 지난 결과가 origin의 의도한 상태와 일치하고, 동적·민감
응답이 오래 캐시되지 않으며 locale·ETag·시간 경계가 섞이지 않는다. **FAIL/BLOCKED:** stale
공지·품절·계좌, 잘못된 304, locale 혼합, 승인되지 않은 cache rule 또는 origin 비교 불가가
있으면 차단한다.

**중단·복구:** 승인된 이전 Caddy/Cloudflare config로 복귀하고 필요할 때만 승인된 범위의
cache purge를 수행한다. purge를 애플리케이션 데이터 복구로 취급하지 않는다. config 복귀 뒤
표의 모든 영향 사례를 다시 확인한다.

## 8. OA-06 · 실제 browser/mobile QR·송금 수용

**목적:** backend 응답만으로 판정할 수 없는 실제 기기 저장, QR camera, 외부 송금 앱 전환과
현장 담당자 흐름을 검증한다.

### 공통 기기 조건

- 승인된 지원 matrix의 실제 iOS·Android 기기와 browser/version, viewport, 네트워크를 기록한다.
- staging에서는 synthetic 계좌와 전용 QR·수령 코드를 사용한다. 운영 최종 대조는 승인된 실제
  정보의 읽기 전용 확인을 기본으로 하며 계좌·code 원문을 evidence에 남기지 않는다.
- offline, 느린 응답, background→foreground, 요청 순서 역전을 포함하고 마지막 정상값과 오류
  표시를 함께 관찰한다.

### QR·스탬프

1. START 전 QR 접근은 적립되지 않고 안내가 계약대로 표시되는지 확인한다.
2. 한 번의 정상 scan이 정확히 한 칸만 추가하고 같은 browser 재진입 후 유지되는지 확인한다.
3. 하루 4개 뒤 추가 scan이 차단되고, KST 자정 이후 당일 상태가 초기화되는지 확인한다.
4. 다른 browser/device의 기록은 합쳐지지 않으며 사용자가 이 한계를 오해하지 않도록 안내되는지
   확인한다.
5. 잘못된 길이·문자·앞자리 0을 포함한 입력과 오입력을 거절하고, `verified: true` 전에는 로컬
   수령 완료로 바뀌지 않는지 확인한다.
6. 수령 코드 교체 구간은 승인된 절차에서 이전·새 코드의 의도한 허용 기간과 종료를 확인한다.
   사람 단위 중복 지급 방지는 서버가 보장하지 않으므로 현장 위험 인수 기록을 별도로 남긴다.

### 티켓·굿즈 송금 안내

1. 승인된 가격·인원·총액, 은행명·끝 네 자리, 운영 시간과 현장 수령 안내를 두 사람이 대조한다.
2. 계좌 복사가 계좌번호만 복사하고 성공·실패 대체 안내가 보이는지 확인한다.
3. 토스 설치 기기와 미설치 기기에서 외부 전환, 취소, browser 복귀를 확인한다. 복귀 뒤 티켓
   인원·금액이 계약대로 초기화되고 자동 구매·입금 완료가 표시되지 않아야 한다.
4. 운영 전·마감 뒤·계좌 미설정 상태에서는 계좌와 송금 action이 노출·활성화되지 않는지 확인한다.
5. 지도 위치 연결과 뒤로 가기, 긴 번역, 확대·reflow, keyboard·screen reader 핵심 경로를 확인한다.

**PASS:** 지원 기기별 QR·로컬 상태·코드 검증과 송금 전환·복사·복귀·비활성화 흐름이 계약과
일치하고 운영 담당자가 sign-off한다. **FAIL/BLOCKED:** 실제 기기 미실행, 운영값 미승인,
잘못된 계좌·금액 노출, 검증 전 수령 처리, 마감 후 송금 활성화 또는 필수 대체 안내 부재가
있으면 차단한다.

**중단·복구:** 잘못된 계좌는 `clear` 또는 승인된 `restore-version`, 잘못된 catalog 안내는 새
revision publish+restart로 분리해 복구한다. 스탬프 code 문제는 승인된 secret rotation과
restart 절차를 따른다. 앱 내부 입금 상태나 서버의 사람 단위 수령 원장을 임시로 추가해 우회하지
않는다.

## 9. OA-07 · alert·on-call·교대 인수

**목적:** dashboard가 존재한다는 사실보다 실제 test alert가 도착하고 담당자가 acknowledge,
분류, escalation, rollback 결정을 수행할 수 있음을 확인한다.

1. probe/metric별 threshold, notification route, 주 담당·대체 담당, 당직 시간, acknowledge 제한,
   escalation 순서와 rollback 결정권을 보호된 기록에서 확인한다.
2. `/healthz` liveness, `/readyz` snapshot readiness, public core smoke, API 오류율·지연, DB 연결,
   media 저장소, certificate/ingress에 필요한 alert가 각각 어떤 의미인지 대조한다. `/readyz`를
   지속 DB health나 backup 성공 alert로 대신 사용하지 않는다.
3. staging 또는 승인된 안전한 운영 방식으로 test alert를 발생시키고 수신 시각, route, 담당자
   acknowledge 시각과 분류 결과를 기록한다. 실제 서비스 장애를 만들거나 임계치를 임의 변경하지
   않는다.
4. 주 담당 무응답을 가정한 대체 담당 escalation과 교대 인수, 보호된 runbook 접근을 확인한다.
5. alert payload, log, dashboard에서 token·cookie·계좌·stamp secret·개인정보가 노출되지 않는지
   확인한다.
6. 종료 후 test 상태가 해제되고 원래 승인된 threshold와 route가 유지되는지 확인한다.

**PASS:** 실제 route로 alert가 도착하고 정해진 시간 안에 acknowledge·분류·escalation이 수행되며,
주·대체 담당과 rollback 권한이 명확하고 비밀값이 없다. **FAIL/BLOCKED:** test alert 미도착,
담당자·연락 채널·threshold·escalation 미확정, runbook 접근 실패 또는 민감정보 노출이 있으면
차단한다.

**중단·복구:** 잘못된 test injection을 제거하고 승인된 alert config로 복귀한다. 알림 경로가
복구되고 test alert를 다시 수신하기 전에는 release를 재개하지 않는다. 실제 장애가 발견되면
OA-01의 traffic 중단과 대상별 rollback 책임자에게 인계한다.

## 10. OA-08 · 실제 보안 강제·runtime secret·ingress 경계 수용

**목적:** Trivy·CodeQL·HTTP 자동 검사가 통과했다는 사실만으로는 GitHub 보호 규칙, 실제
runtime secret 처리, Caddy·Cloudflare의 외부 노출과 trusted proxy 경계를 증명할 수 없다.
이 단계는 보호된 운영 기록에서 그 세 가지를 실제 후보에 연결한다. 실제 host, secret 값,
connection string, Caddyfile 본문, Cloudflare rule 값이나 Docker inspect 전문은 저장소와
evidence bundle에 복사하지 않는다.

### OA-08.1 · CI 보안 강제와 후보 scan 연결

**담당:** 릴리스 책임자와 독립 확인자. GitHub ruleset·CodeQL 권한 확인은 저장소 관리자가
지원한다.

**입력:** candidate commit·immutable image digest, 해당 commit의 `security-filesystem`,
`docker-build`, `codeql-java` 실행 reference, release workflow의
`verify-and-scan-candidate-digest` 실행 reference와 Trivy SARIF artifact reference, `main`
보호 ruleset과 CodeQL `High or higher` alert 정책 reference다.

**실행:** 실행자가 아닌 독립 확인자가 source CI의 checkout commit과 `docker-build` 결과를
대조하고, release workflow가 같은 candidate digest를 pull해 OCI revision과 Trivy scan 대상을
확인했는지 별도로 대조한다. pre-merge `docker-build`의 local tag scan은 immutable digest scan을
대체하지 않는다. 두 scan의 fail-closed 설정(`HIGH,CRITICAL`, `ignore-unfixed=false`,
`exit-code=1`)과 결과를 확인하고, ruleset이 위 CI check와 CodeQL alert 정책을 실제 merge gate로
강제하는지 확인한다. 단순 workflow YAML 존재, rerun 성공 또는 이름이 비슷한 다른 commit의 report는
증거가 아니다.

**PASS:** source CI와 digest scan이 같은 후보를 가리키고 성공했으며, High/Critical finding·scan
오류·허용 실패가 없고 보호 규칙이 우회를 허용하지 않는다. **FAIL:** finding, scan failure,
digest/commit 불일치 또는 required gate 우회가 확인되면 후보를 승격하지 않는다. **BLOCKED:**
ruleset 또는 artifact 접근 권한·CodeQL 정책 reference가 없거나 scan 대상과 후보 연결을 확인할 수
없으면 판정하지 않는다.

**증거:** 안전한 CI run/artifact/ruleset reference, candidate digest·commit, 결과 상태와
독립 확인자 서명만 남긴다. SARIF 원문이나 보안 alert 세부 정보는 보호된 보안 기록에 둔다.

**중단·복구:** 배포 전이면 promotion을 중단하고 수정 후보를 새 digest로 다시 검증한다. 이미
운영에 반영된 후보에서 차단 finding이 확인되면 릴리스 책임자가 영향과 공개 여부를 분류한 뒤
검증된 이전 image/config로 rollback 여부를 결정한다. 과거 후보의 PASS를 새 digest에 복사하거나
finding을 문서상 `N/A`로 바꾸지 않는다.

### OA-08.2 · runtime secret·로그·운영 증거 경계

**담당:** C 인프라 담당과 D 데이터 담당, 독립 확인자가 redaction을 검토한다.

**입력:** 보호된 secret-store reference, 허용된 환경변수 **이름** 목록, candidate runtime
configuration reference, redacted startup/proxy/alert log reference, 배포 전후 container
metadata의 안전한 요약이다. 실제 값·endpoint·전체 inspect export는 입력으로도 evidence에
넣지 않는다.

**실행:** secret store에서 주입한 값이 root source, Docker image layer, CI output, browser HAR,
alert payload, 일반 운영 log와 release evidence에 나타나지 않는지 확인한다. staging candidate의
bootstrap credential은 포함되지 않고, secret 파일과 container env metadata의 열람 권한은 승인된
담당자로 제한되는지 확인한다. `STAMP_RECEIPT_CODE_SHA256`도 hash이지만 비밀값으로 취급한다.

**PASS:** 승인된 변수 이름과 protected reference만 추적 가능하고, 비밀값·raw JDBC URL·token·cookie·
계좌 원문이 비보호 artifact에 없으며, bootstrap 값이 남아 있지 않다. **FAIL:** 하나라도 비보호
출력·artifact·client bundle에 노출되면 해당 candidate는 차단한다. **BLOCKED:** secret-store,
runtime env 접근 통제 또는 redacted log 확인 권한이 없으면 배포·재기동을 시작하지 않는다.

**증거:** secret-store와 runtime config의 보호된 reference, 변수 이름 목록, redaction 검토 결과,
안전한 log/artifact reference와 검토자만 남긴다.

**중단·복구:** 노출된 secret은 보호된 절차로 revoke·rotate하고 노출 artifact의 접근을 차단한다.
원인을 제거한 새 candidate/runtime configuration을 만들고 같은 scan·smoke·redaction 검토를 다시
실행한다. secret을 evidence에 복사해 원인을 설명하거나 기존 container의 환경을 추측해 수정하지
않는다.

### OA-08.3 · ingress·direct-backend·trusted proxy 경계

**담당:** C 인프라 담당이 실행하고 A 서비스 담당과 독립 확인자가 공개 경로 결과를 검토한다.

**입력:** 보호된 Caddy/Cloudflare configuration reference, approved public URL, container의
loopback bind 또는 approved Docker network reference, 실제 `RATE_LIMIT_TRUSTED_PROXY_HOPS`,
지원 browser origin과 STAGE-02 cookie/CORS evidence다.

**실행:** 승인된 staging 경로에서 public HTTPS → Caddy → backend routing, TLS/redirect와
안전한 forwarded-header 요약을 관측한다. 별도 승인된 내부/관리 probe로 backend port가 public
internet에 직접 열려 있지 않고 Caddy의 upstream만 접근 가능한지 확인한다. public client가
임의 `X-Forwarded-For`를 넣어도 새 client bucket을 만들거나 rate limit을 우회하지 않는지,
Cloudflare proxy mode와 Caddy 동작에 맞는 trusted hop 수인지 확인한다. 방화벽·접근 통제를
우회하는 scan, 무차별 포트 탐색, 승인되지 않은 origin 변경은 수행하지 않는다.

**PASS:** public HTTPS 경로만 승인 topology로 서비스되고 direct backend 노출이 없으며, client
identity·rate limit·forwarded header가 보호된 설정과 일치한다. STAGE-02의 browser cookie/CORS
결과도 같은 ingress를 가리켜야 한다. **FAIL:** backend가 외부에 직접 노출되거나 header spoofing으로
bucket 분리가 가능하고, redirect/TLS/routing이 승인된 설정과 다르면 차단한다. **BLOCKED:**
Caddyfile 위치·Cloudflare mode·host port/network·trusted hop 중 하나라도 확인되지 않으면 값을
추측해 배포하지 않는다.

**증거:** Caddy/Cloudflare·network/port·trusted-hop의 보호된 reference, public response의
redacted status/header 요약, direct-exposure probe 결과, rate-limit 관측, A·독립 확인자 결과를
남긴다.

**중단·복구:** traffic 전환을 중단하고 승인된 이전 Caddy/Cloudflare config와 known-good image/
network 연결로 복귀한다. `RATE_LIMIT_TRUSTED_PROXY_HOPS`나 CORS를 추측값으로 바꾸지 않으며,
config 복귀 뒤 OA-05와 STAGE-02의 영향 사례를 다시 확인한다.

## 11. 최종 판정과 인수인계

릴리스 책임자와 독립 확인자는 OA-01~08을 한 후보 기준으로 검토한다. 필수 시나리오가 모두
`PASS`이고 `BLOCKED`, `FAIL`, 판정 보류가 없을 때만 운영 배포 또는 행사 운영 재개를 승인한다.

```text
evidenceId: <id>
candidateImageDigest: sha256:<digest>
operationalAcceptance: OA-01..OA-08 <status-summary>
approvedDataReference: <id>
recoverySetId/rpo/rto: <id-and-measured-result>
edgeCacheEvidence: <id>
mobileHandoffEvidence: <id>
alertOnCallEvidence: <id>
securityRuntimeIngressEvidence: <id>
openRisks: <none-or-release-blocking-items>
decision: PASS | BLOCKED
operator/reviewer/decidedAt: <references-and-Asia/Seoul-time>
```

인수인계에는 현재 image digest·catalog revision·account version, 다음 당직자와 연락 채널,
known issue, rollback 기준, paired recovery set과 마지막 성공 smoke 시각을 포함한다. `PASS` 뒤
후보나 운영 데이터가 바뀌면 변경 영향에 해당하는 OA 시나리오를 다시 검증한다.
