# 릴리스 staging·운영 리허설 상세 케이스

[릴리스 HTTP E2E 개요](release-http-e2e.md) · [위키 홈](../README.md) · 읽는 때: staging 또는 승인된 운영 리허설의 실제 ingress·브라우저·media·부하·복구 gate를 준비·실행할 때

이 문서는 STAGE-01–STAGE-05의 **실행 대기** 수용 조건이다. Testcontainers HTTP E2E와
서로 보완하지만 대체하지 않는다. 실제 실행 전에는
[행사 당일 운영 절차서](festival-day-runbook.md), [운영 기준](../engineering/operations.md),
[품질 기준](../engineering/quality.md)을 함께 확인한다.

## 공통 실행 기록과 판정

각 stage는 다음을 보호된 운영 기록에 남긴다. 비밀값, 원본 account, token, SSH 명령, 개인
연락처는 기록하지 않고 안전한 참조만 남긴다.

쓰기·부하·복구 단계는 run ID로 구분된 전용 staging DB·media·admin account·fixture 안에서만
수행한다. 승인된 운영 리허설을 써야 하면 승인 범위, writable object 목록, cleanup/recovery
담당자와 근거를 먼저 기록한다. 이 격리는 01.3, 02.4, 04.4, 05.1과 recovery rehearsal에
공통으로 적용한다.

| 항목 | 필수 증거 |
| --- | --- |
| 대상 식별 | KST 실행 시각, 실행자·대체 담당, 승인 참조, Git commit, image digest, candidate manifest/revision·checksum |
| 환경 | staging/승인된 운영 리허설 여부, actual public URL·ingress/upstream 참조, FESTIVAL_ID, DB target, media mount/volume identity |
| 입력·관측 | 적용되는 browser·device·viewport, request/response 요약, probe·metric dashboard·load report 링크, pre/post state |
| 판정 | PASS/FAIL/BLOCKED/미완료와 이유, 중단 시각, 책임자, rollback 또는 retry 판단 |
| 복구 | 적용되는 경우 이전 image/ingress config/recovery set reference, 실제 수행 결과, Flyway history/checksum·revision·media·public smoke 재확인 |

모든 stage에는 대상·환경·입력·판정 evidence가 있어야 하고, 해당 stage에 적용되는
stage-specific evidence가 빠지면 PASS가 아니다. 실제로 적용되지 않는 항목만 이유와 함께
`N/A`로 남긴다. 핵심 fixture가 없으면 `N/A`가 아니라 BLOCKED/미완료다. 후보 commit,
image digest, catalog revision, migration, ingress, media mount가 바뀌면 영향받은 evidence를
다시 만든다. staging은 실제 시간·network 경로를 쓰므로 fixed clock Testcontainers 경계 사례를
여기로 복사하지 않는다.

## STAGE-01 · artifact·migration·published snapshot·public probe

**목적.** 후보 artifact가 의도한 DB/media/ingress 조합으로 실행되고, catalog의 publish와
controlled restart 차이를 실제 public HTTPS 경로에서 확인한다.

**선행 조건.** 01.3의 publish와 01.4의 restart 전, STAGE-03의 03.2 baseline을 확보하고
유효한 DB/media paired recovery set과 승인된 recovery 대상을 확인한다. 변경 뒤에 baseline이나
복구 대상을 처음 만들지 않는다.

| 단계 | 실행·관측 | 통과 기준 | 중단 또는 복구 판단 |
| --- | --- | --- | --- |
| 01.1 | 배포 전 current/target image digest, manifest revision/checksum, DB target, FESTIVAL_ID, media mount, ingress/upstream을 기록 | runtime이 어느 artifact·data·volume을 쓸지 추적 가능 | 어느 값이 확인되지 않으면 배포하지 않는다. |
| 01.2 | Flyway history/checksum과 provider 승인·preflight 판단을 확인 | 승인됨, history/checksum 일치, `STOP_AND_REVIEW` 아님 | 승인 부재·checksum 불일치·unknown existing data면 migration/restart를 중단한다. |
| 01.3 | candidate manifest를 import/validate/publish하고, **재시작 전** running server snapshot을 기록 | publish만으로 running snapshot이 바뀌지 않는 단일-instance 동작을 구분해 기록 | 즉시 노출을 가정하거나 unexpected snapshot이면 catalog 작업을 중단한다. |
| 01.4 | media mount·DB/FESTIVAL_ID를 유지한 controlled restart 후 public HTTPS `GET /healthz`, `GET /readyz`, config를 조회 | health/ready는 `200`, config의 published `meta.revision`이 의도한 candidate와 일치 | probe 또는 revision이 다르면 traffic 전환 대신 원인을 분리하고 rollback 판단으로 간다. |
| 01.5 | public catalog 핵심 read와 admin dynamic data의 예비 smoke를 기록 | HTTPS ingress·backend routing·published snapshot이 함께 일관됨 | `readyz`만 DB 지속 health probe로 해석하지 않는다. DB 장애 의심은 별도 DB check로 분리한다. |

**복구 완료 조건.** 이전 image를 쓰기 전 DB/Flyway 호환성을 확인한다. catalog 문제는 archived
내용을 새 revision으로 publish하고 restart하며, artifact 문제는 확인된 이전 image/ingress
configuration으로 복귀한다. 어느 경로든 public health/ready, intended revision과 아래
STAGE-03 media smoke를 다시 확인한다.

## STAGE-02 · 관리자 browser cookie/CORS와 공개 접근

**목적.** 실제 지원 browser와 configured administrator origin에서 session cookie·CORS·CSRF가
맞고, 공개 사용자는 로그인 없이 계속 읽을 수 있는지 확인한다.

### 준비와 범위

- actual allowed origin, browser version, private/incognito profile, one disposable admin account를
  기록한다. production operator account나 saved browser secret을 공유하지 않는다.
- session cookie flow는 bearer mutation과 다른 정책이다. login/refresh/logout은 Origin/CSRF
  gate를, content mutations는 server bearer authentication policy를 각각 검증한다.

| 단계 | browser/network 흐름 | 통과 기준 | 실패 시 side effect |
| --- | --- | --- | --- |
| 02.1 | allowed origin에서 login → protected read → refresh rotation → old refresh replay → logout → refresh | expected cookie flags/scope와 access session lifecycle, old/revoked refresh 거절 | refresh replay/logout 실패가 새 session이나 business mutation을 만들지 않는다. |
| 02.2 | allowed origin의 CORS preflight 및 credentialed request | allow-origin/credentials/header/method가 application policy와 일치 | browser가 expected origin을 보내지 못하면 CORS setting을 추측해 우회하지 않는다. |
| 02.3 | foreign Origin은 browser에서 login·refresh·logout으로, missing Origin은 non-browser HTTP client에서 같은 cookie route로, malformed bearer는 보호된 admin endpoint로 각각 요청 | 각 request가 해당 contract error로 거절 | session cookie, business row, audit, idempotency, media file이 추가되지 않는다. public GET에 malformed bearer가 붙었다고 거절을 기대하지 않는다. |
| 02.4 | notice/product/availability/media의 representative valid mutation과 rejected mutation | bearer authentication과 precondition 결과가 API contract와 일치 | invalid request가 public GET, DB, media lifecycle을 바꾸지 않는다. |
| 02.5 | private profile에서 notices/goods/availability/media와 `GET /api/v2/artist-hyped`를 익명으로 읽는다 | public path가 login/administrator cookie 없이 성공 | session 로그아웃 뒤 public user path가 인증 뒤로 이동하지 않는다. |
| 02.6 | 오늘이 등록된 FestivalDay인 격리 fixture에서 private profile로 실제 ARTIST의 Hyped 버튼을 반복 누른다 | 관리자 cookie 없이 각 명시적 클릭이 1회 POST되고 반환된 누적 수가 증가한다. CONTEST에는 버튼이 없다 | 429 또는 모호한 네트워크 결과를 성공으로 표시하거나 자동 재전송하지 않는다. 축제일 fixture가 없으면 BLOCKED로 기록한다. |

**증거.** DevTools network record에서 request Origin, cookie attributes, response CORS headers와
status만 보관한다. cookie/token value와 uploaded binary는 redact한다. 필요하면 keyboard focus,
label, error display는 STAGE-04 evidence에 연결한다.

## STAGE-03 · media volume·동적 상태 보존과 공개 전달

**목적.** DB row와 media volume, public ingress, restart가 함께 일관된다는 것을 실제 연결
이미지와 동적 운영값으로 확인한다.

| 단계 | 실행·관측 | 통과 기준 | 중단 또는 복구 판단 |
| --- | --- | --- | --- |
| 03.1 | 실제 연결 image를 가진 public goods response의 `images[].masterUrl`을 기록하고 public ingress GET | `200 image/webp`와 expected cache/security headers | 연결 image가 없으면 image delivery는 **미완료**이며 PASS로 바꾸지 않는다. |
| 03.2 | restart 전 KST 시각, notice visibility state와 ticket window를 포함한 notice/product/combination status/GOODS·TICKET account 의미값, 아티스트별 Hyped 누적 수, current media mount identity 기록 | baseline이 revision 외 동적 data와 time boundary를 분리해 기록 | catalog revision만 보고 dynamic state가 보존됐다고 결론 내리지 않는다. |
| 03.3 | STAGE-01 controlled restart 뒤 같은 public URL·동적 reader를 재조회 | 저장된 의미값·Hyped 누적 수와 media mount는 유지되고, restart 동안 ticket window/notice date boundary를 넘었으면 public 표현은 문서화된 시간 전이에 맞는다 | missing mount/404/503, data loss 또는 time boundary와 맞지 않는 public 전이면 traffic 재개 전에 원인을 분류한다. |
| 03.4 | DB/media backup recovery set reference와 before/after smoke를 대조 | DB와 media가 같은 recovery set ID·time으로 관리됨 | DB 또는 media 한쪽만 복원하는 계획은 실행하지 않는다. |

**원인별 대응.** ingress/TLS 단독 문제는 확인된 config rollback을, incompatible artifact는 DB
compatibility 검토 후 이전 image를, catalog 내용 문제는 archived content의 새 revision publish
+ restart를, account 문제는 `restore-version`/`clear`를 사용한다. DB/media 자체를 복원할 때만
동일 recovery set을 함께 복원하고, 복원 뒤 Flyway history/checksum, published revision, attached
media GET와 public smoke를 확인한다. 모든 HTTP failure의 기본 대응으로 DB/media restore를
사용하지 않는다.

## STAGE-04 · mobile polling·offline recovery·navigation·accessibility

**목적.** mobile real browser가 dynamic notices/goods/availability/crowding/Hyped를 15초 polling
규칙과 stale UX로 안전하게 보여 주는지 확인한다.

| 단계 | 실제 사용자 흐름 | 통과 기준 |
| --- | --- | --- |
| 04.1 | 390×844와 지원 viewport에서 notice/goods/availability/crowding 및 아티스트 라인업·상세 first entry | initial request가 즉시 시작되고 로그인 없이 usable content/empty/error state가 보인다. Hyped 조회 실패를 0회로 바꾸지 않는다. |
| 04.2 | page visible 상태에서 notice/goods/availability/crowding/Hyped의 15초, hidden 상태, foreground 복귀 | visible에서만 15초 polling, hidden에서 중단, foreground/route 재진입에서 즉시 revalidate한다. 동일 resource request가 겹치지 않는다. |
| 04.3 | notice/goods/availability/crowding/Hyped에서 offline → online, server failure를 재현 | offline/hidden 중 polling 중단, online에서 즉시 재요청한다. Hyped는 마지막 정상 수를 보존하고, 결과가 모호한 POST는 재전송하지 않고 GET으로 동기화한다. 다른 화면의 실패 backoff는 각 화면 계약을 따른다. |
| 04.4 | 같은 catalog revision 아래 administrator가 notice/goods/availability를 바꾸고, [행사 당일 운영 절차서](festival-day-runbook.md) 기준으로 실제 FestivalDay·operatingDay를 확인한 뒤 crowding을 바꾼다 | next polling 또는 immediate revalidation에서 dynamic change가 보인다. catalog meta.revision만으로 dynamic update를 버리지 않는다. FestivalDay/operatingDay가 아니면 crowding mutation은 BLOCKED로 기록한다. |
| 04.5 | list → detail → back, deep link, keyboard/zoom/긴 번역 | product navigation 규칙, scroll/focus restoration, label·keyboard focus, text reflow/overflow가 각 화면 계약에 맞는다. |
| 04.6 | 아티스트 라인업 → 상세에서 Hyped 수, 버튼, 닫힘·429 상태를 확인 | 라인업은 수만 표시하고 상세에서만 클릭할 수 있다. 닫힌 기간에는 수를 읽되 참여를 막고, 실패한 클릭으로 수를 올리지 않는다. 긴 수 표기와 버튼 focus·label을 확인한다. |

Network timing은 browser trace로 기록한다. 적용 대상 screen은 15초 polling과 각 화면 계약의
실패 후 재조회 규칙을 충족해야 한다. 구현이 없으면 FAIL 또는 BLOCKED로 기록하고, 승인되지 않았거나
화면 계약에 매핑되지 않은 screen만 근거와 함께 `N/A`로 남긴다.

## STAGE-05 · staging k6 부하 프로필·관측·rollback rehearsal

**목적.** 초기 500 concurrent viewer 가정과 dynamic polling 부하, rollback 가능성을 실제
candidate/environment 지표로 판단한다. local load fixture 성공은 운영 capacity 증명이 아니다.

### rate-67 acceptance

| 항목 | 기준 |
| --- | --- |
| traffic | 60초 동안 crowding 34 RPS + ticket guide 33 RPS, total 67 RPS |
| latency | route별 p95 ≤ 300 ms, p99 ≤ 1 s |
| error | 200/304 성공률 ≥ 99.9%, 429 비율 ≤ 0.1%, transport·timeout·그 밖의 예상 밖 상태 비율 ≤ 0.1%, dropped iteration 0 |
| evidence | 5xx·429·transport·timeout 분리 count, heap·GC, PostgreSQL connection/active/lock wait, candidate/cache/fixture conditions |
| scale | 1×, 2×, 5×와 500 concurrent viewer 가정의 결과를 actual environment에서 별도로 기록 |
[release-scenarios.js](../../../tools/load-test/release-scenarios.js)는 승인된 staging에서만 쓰는
warm-cache, cold-cache, activation-spike, public-polling-mix, sustained-load,
dynamic-mutation-interleaving, receipt-rate-limit-mix 프로필을 제공한다. 기본 public 부하는
승인된 limiter mode와 client identity strategy, candidate reference, 보호된 외부 evidence
경로가 없으면 시작하지 않는다. cold-cache는 500 VU가 각각 한 bundle을 실행하며 cache purge를
수행하지 않는다. mutation은 disposable fixture에서만 허용하며 initial admin read가 writable savedLevel을 돌려주고 final read-back·원복 증거가 있어야 한다. 하나라도 없으면 FAIL이다. 강제 중단으로 teardown이 보장되지 않으면 해당 run은 FAIL로 기록하고
수동 recovery·audit 확인을 먼저 끝낸다.

| 추가 profile | 필수 판정 |
| --- | --- |
| warm/cold cache | 200/304 성공률, route별 p95/p99, 허용 429 상한과 cache preparation reference를 함께 남긴다. |
| activation spike/public polling/sustained | public polling은 crowding/notices/goods/goods availability를 17/17/17/16 RPS로 분리하고, activation은 같은 비율로 67/134/335 RPS를 만든다. route별 success/429/5xx/timeout/transport, dropped iteration과 heap·GC·DB 관측을 분리한다. |
| dynamic mutation | opt-in bearer/origin·FestivalDay·writable fixture, initial admin savedLevel 확인, 원래 savedLevel의 read-back 복원과 cleanup audit를 요구한다. |
| receipt rate-limit mix | valid/invalid 결과, RATE_LIMITED envelope·Retry-After·request ID, public/other client 분리와 refill 회복을 기록한다. |
| artist Hyped burst | 별도 승인된 격리 fixture에서 실제 ingress로 GET/POST를 함께 실행한다. 승인한 목표 요청량·지연 기준과 client identity 분포를 실행 전에 기록하고, 보호 limiter를 켠 결과에서 성공 POST 총수와 최종 API·DB count 일치, 429·5xx·timeout·lock wait·connection pressure를 분리한다. 합성 loopback CI 결과로 대체하지 않는다. |

| 단계 | 실행·관측 | 통과·중단 기준 |
| --- | --- | --- |
| 05.1 | candidate artifact와 STAGE-01 probe가 통과한 동일 환경에서 rate-67 실행 | 모든 rate-67 기준과 관측 evidence가 충족해야 한다. 429/5xx를 합쳐 숨기지 않는다. |
| 05.2 | rate-67과 warm/cold cache, 1×/2×/5× activation, public polling, sustained, receipt limit 및 Hyped burst 중 후보에 적용되는 승인 profile을 비교 | saturation point, DB lock/connection pressure, cache/limiter/client identity condition을 기록한다. 결과 하나로 production capacity를 확정하지 않는다. |
| 05.3 | 승인된 별도 recovery 대상에서 documented rollback/recovery를 rehearsal | agreed RPO/RTO 판단, Flyway/revision/media/public smoke를 모두 재확인한다. |
| 05.4 | rehearsal failure 또는 health/core smoke failure | release를 진행하지 않고 known-good artifact/config 또는 대상별 recovery로 전환한다. |

**정리.** load data와 dashboards에 user data·secret을 남기지 않는다. rollout decision은 이
stage 하나가 아니라 required CI, HTTP E2E, product acceptance, migration/operations approval과
함께 내린다.
