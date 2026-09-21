# 릴리스 HTTP E2E 시나리오

[위키 홈](../README.md) · 읽는 때: 릴리스 후보 HTTP 검증을 설계·확장·실행하거나, 공개·관리자 API의 출시 영향을 검토할 때

이 페이지는 후보 서버를 실제 HTTP 경계까지 검증하는 범위와 보강 우선순위를 정의한다.
실행 명령과 현재 자동 검증 결과는 [검증 명령과 CI](validation.md), 배포·복구 절차는
[행사 당일 운영 절차서](festival-day-runbook.md)를 따른다. 시나리오 정의만으로 실행이나
릴리스 승인을 뜻하지 않는다.

## 현재 HTTP release E2E 인벤토리

모든 대상은 JUnit 5, Testcontainers PostgreSQL, 랜덤 포트 Spring 서버, Java `HttpClient`를
사용한다. 후보 catalog는 실제 CLI로 import·publish하며, 원격 DB·계정·배포 환경에는 연결하지
않는다.

| 테스트 | 유형 | JUnit method |
| --- | --- | ---: |
| `ReleaseReadinessHttpE2eTest` | 후보 catalog·공개 사용자·관리자 기본 여정과 격리 | 9 |
| `ReleaseFailureModesHttpE2eTest` | 미게시 후보, readiness, rate limit·request ID 복구 | 1 |
| `AdminSessionReleaseE2eTest` | 로그인·refresh rotation·logout·인증 경계 | 2 |
| `CrowdingConcurrencyE2eTest` | 혼잡도 동시 저장·멱등·시간 경계 | 5 |
| `OperationalAccountPropagationE2eTest` | TICKET 계좌 CLI → HTTP 반영·송금 시간 경계 | 2 |
| `CatalogPublicationLifecycleE2eTest` | 게시·재시작·rollback의 HTTP 노출 | 1 |

따라서 현재 실제 HTTP E2E는 **6개 class, 20개 JUnit method**다. 한 method가 여러 요청을
묶으므로 [검증 명령과 CI](validation.md)의 위험 시나리오 기준 수는 `HTTP-01`~`HTTP-24`,
즉 **24개**다. 운영자·개발자 도구 시나리오 `OPS-01`~`OPS-20`은 별도 process E2E이며 HTTP
수에 포함하지 않는다.

## 출시 준비 판단

`HTTP-01`~`HTTP-24`는 catalog 탐색, 지도·공연 관계, 티켓, 관리자 세션, 혼잡도,
게시·rollback, 조건부 읽기와 제한을 폭넓게 검증한다. 하지만 이 게이트는 공지·굿즈·굿즈
이미지와 스탬프 수령 확인을 명시적으로 제외한다. 해당 경로는 현재 MockMvc, 단위 또는 DB
통합 검증이 있어도 실제 HTTP server·security filter·serialization·runtime storage를 함께
확인하지 않는다.

따라서 현 상태는 **catalog 중심 후보 검증에는 충분하지만 실제 릴리스 전체 승인에는
불충분**하다. 아래 `HTTP-25`~`HTTP-31`을 구현·통과하고 staging/운영 gate도 완료해야 전체
릴리스 준비가 된다. 이 7개는 계획 시나리오이므로 현재 24개 수에 더하지 않는다.

## 보강할 HTTP 시나리오

각 자동 시나리오는 Testcontainers 전용 DB, 고정 KST clock, 랜덤 포트 server, 실제 관리자
로그인을 사용한다. 식별자는 응답에서 읽고, 계좌번호·수령 코드·hash·토큰 원문을 assertion
message나 로그에 넣지 않는다.

### HTTP-25 · 공지 생성·수정·삭제의 공개 반영 — P0

- 준비: published 후보, 빈 공지 상태, 관리자 세션과 당일 KST clock을 준비한다.
- 실행: 일반 한국어 공지와 HTTPS 링크를 `Idempotency-Key`로 생성하고 같은 요청을 재전송한다.
  관리자 목록·상세와 공개 `/notices`를 조회한 뒤, stale `If-Match` 수정과 최신 ETag 수정,
  최신 ETag 삭제를 차례로 보낸다.
- 통과: 생성은 `201`과 `Location`, replay는 중복 notice/audit 없이 같은 결과, stale 수정은
  `409 EDIT_CONFLICT`다. 생성·수정·삭제마다 공개 ETag가 바뀌고 삭제 뒤 공개 목록에서는
  사라지며, DB는 soft delete와 정확히 한 번의 CREATE/UPDATE/DELETE 감사 이력을 보존한다.

### HTTP-26 · 굿즈 운영 변경의 공개 구매 여정 — P0

- 준비: 실제 관리자 세션과 이미지가 연결된 다색상·다사이즈 상품 입력을 준비한다.
- 실행: 상품을 생성하고 같은 idempotency key를 replay한다. 공개 목록·상세·availability를
  읽은 뒤 조합을 하나씩 `SOLD_OUT`으로 바꾸고 하나를 다시 `ON_SALE`로 바꾼다. 유지·삭제·신규
  조합이 섞인 상품 수정, stale `If-Match`, 최종 삭제를 수행한다.
- 통과: 상품 생성은 `201`과 `Location`, 신규 조합은 `ON_SALE`, 유지 조합 상태는 보존,
  삭제 조합은 두 목록에서 제거된다.
  모든 남은 조합이 품절일 때만 `allSoldOut`이 true이고 재판매 후 false가 된다. 공개 응답은
  각 commit을 반영하며, stale 수정은 `409`, 각 유효 mutation은 audit/idempotency에 한 번만 남는다.

### HTTP-27 · 굿즈 이미지 업로드·공개 media 전달 — P0

- 준비: 임시 media storage root와 실제 이미지 처리 경로를 설정하고 관리자 세션을 만든다.
- 실행: 유효한 이미지를 multipart로 업로드하고 replay한다. 연결 전 media GET, 상품 연결 뒤
  master·thumbnail WebP GET과 `If-None-Match`, 이미지 교체·분리·상품 삭제를 순서대로 호출한다.
- 통과: replay 뒤 media row·variant set·감사 이력은 하나뿐이다. unattached media는 `404`이고,
  연결된 variant만 `200`, 정확한 content type, immutable cache header와 `304`를 준다. 분리·삭제
  뒤에는 공개 접근이 막히고 staging file·association·lifecycle 상태가 서로 어긋나지 않는다.

### HTTP-28 · 스탬프 수령 코드와 abuse 방어 — P0

- 준비: published 후보와 이전·현재 6자리 코드의 SHA-256 hash만 설정하고, 운영 proxy hop 수와
  같은 rate-limit 구성을 사용한다.
- 실행: 두 유효 코드, 형식이 틀린 코드와 불일치 코드를 제출한 뒤 한 client bucket을 초과한다.
  다른 forwarded client와 refill 뒤에 다시 제출한다.
- 통과: 유효 코드는 `200`과 `verified: true`, 실패 코드는 동일한 `422 INVALID_RECEIPT_CODE`,
  제한 초과는 `429 RATE_LIMITED`와 `Retry-After`다. client bucket은 독립적이고, 응답·감사·DB에
  코드·hash·참여·수령 기록이 남지 않는다.

### HTTP-29 · GOODS 계좌 CLI의 실행 중 반영 — P0

- 준비: 상품을 만든 뒤 `GOODS` 계좌가 없는 상태에서 실행 server와 account CLI child process를
  시작한다.
- 실행: 모든 상품의 payment guide를 읽고 CLI dry-run, confirm set, stale expected-version set,
  clear를 차례로 수행하며 매 단계 payment guide의 HTTP 표현을 다시 읽는다.
- 통과: set 전 account는 비어 있고, set 후 모든 상품이 같은 공유 계좌를 보인다.
  stale set은 DB·HTTP를 바꾸지 않고 clear는 account를 다시 숨긴다. CLI 출력은 원문 계좌를
  숨기며, 각 유효 변경만 immutable history를 만든다. 현재 goods payment guide 계약에는
  conditional ETag나 settings version이 없으므로 이를 임의로 기대하지 않는다.

### HTTP-30 · 공지 템플릿 process → HTTP 경계 — P1

- 준비: 실제 notice-template CLI와 인증된 관리자, 서로 다른 두 template JSON revision을 둔다.
- 실행: 첫 template set을 child process로 교체한 뒤 관리자 목록·상세에서 번역을 읽고 공지를
  만든다. 해당 template ID가 없는 두 번째 set으로 교체한 뒤 기존 공지를 다시 조회한다.
- 통과: template 목록·번역은 HTTP에서 정확히 보이고 없는 template detail은 `404`다. 삭제된
  template와 연결된 공지는 내용·공개 여부를 유지하되 template 연결만 끊기며 공개 경로에는
  template 관리 데이터가 노출되지 않는다.

### HTTP-31 · 공지·굿즈·media 관리자 mutation 경계 — P1

- 준비: 관련 테이블과 media root의 기준 상태를 기록하고, 유효한 관리자 세션 하나를 만든다.
- 실행: notice, product, availability, media upload 경로마다 anonymous·손상 bearer·누락/foreign
  `Origin`·잘못된 `Idempotency-Key` 또는 `If-Match` 요청을 보낸다.
- 통과: 계약에 맞는 `401`/`403`/`400`/`428`만 반환하며 business row, audit, idempotency row,
  media file이 하나도 만들어지거나 바뀌지 않는다. 유효한 요청 뒤에는 같은 경계가 공개 GET을
  인증 뒤로 보내지 않는다.

## 실제 배포에서 별도로 통과할 gate

Testcontainers HTTP E2E는 실제 ingress, browser와 운영 data를 대체하지 않는다. 아래는
staging 또는 승인된 운영 리허설에서 artifact·commit·candidate revision을 기록해 확인한다.

| ID | 실행 | 통과 기준과 실패 시 행동 |
| --- | --- | --- |
| STAGE-01 | 운영과 같은 image·migration·candidate manifest를 배포하고 public HTTPS로 `/healthz`, `/readyz`, config revision을 조회 | 의도한 published revision과 준비 상태가 일치해야 한다. 불일치·migration 문제면 트래픽 전환 대신 rollback 판단으로 간다. |
| STAGE-02 | 실제 관리자 origin 브라우저에서 login/refresh/logout과 notice·goods mutation을 수행하고 다른 origin도 시도 | Secure·HttpOnly·SameSite cookie와 CORS가 정확하며 잘못된 origin은 side effect 없이 거절해야 한다. |
| STAGE-03 | 연결된 굿즈 image URL을 public ingress로 받고, 재시작 뒤 같은 media volume과 동적 notice/goods/account 상태를 확인 | media `200`과 운영 데이터 보존이 모두 필요하다. volume·응답이 누락되면 이전 image와 짝지어진 DB/media recovery set으로 복구한다. |
| STAGE-04 | 390×844 모바일, 저속·오프라인/온라인 전환에서 공지·굿즈 polling, 마지막 정상값, back navigation을 확인 | 15초 갱신·30/60초 backoff·마지막 정상값 규칙을 지키고 공개 기능은 로그인 없이 계속 접근 가능해야 한다. |
| STAGE-05 | 후보 artifact에서 rate-67과 예상 peak 부하, error/latency/DB 지표와 rollback rehearsal를 기록 | [부하 기준](../engineering/operations.md)과 관측·rollback 기준을 만족해야 한다. health 또는 핵심 smoke 실패면 출시하지 않는다. |

## 구현·실행 기록

`HTTP-25`~`HTTP-31`은 기존 release E2E의 Testcontainers-only 격리를 재사용해 구현하고,
실행 명령에 포함되는 시점에만 [검증 명령과 CI](validation.md)의 실제 목록과 수를 갱신한다.
이 문서 변경은 시나리오 설계만 포함하며 앱·E2E 명령을 실행하지 않는다.
