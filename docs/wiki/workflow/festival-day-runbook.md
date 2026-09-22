# 행사 당일 운영 절차서

[위키 홈](../README.md) · 읽는 때: 행사 기간 중 콘텐츠 수정·계좌 교체·스탬프 코드 교체·장애 대응을 실제로 수행할 때

이 문서는 명령을 실행하는 절차만 다룬다. 각 CLI·API의 설계 근거는
[게시](../engineering/publishing.md), [계좌 운영 설정](../engineering/operational-account-settings.md),
[운영](../engineering/operations.md)을 따른다. A1은 root `Dockerfile`의 단일 `docker run`으로
실행하고 Caddy가 reverse proxy, Cloudflare가 edge를 맡는 것으로 확인됐다. 실제 SSH 접속,
host port/network, Caddy upstream과 TLS 설정은 아직 확인되지 않았으므로 이 문서에 명령이나
운영값을 추측해 적지 않는다. 해당 상태는 [원격 개발 환경 결정](../../dev-deployment-decision.md)에
기록한다.

릴리스 후보를 행사 환경으로 넘기기 전 staging 검증 결과와 복구·브라우저 인수인계 증거는
[릴리스 증거 runbook](release-evidence-runbook.md)의 evidence bundle과 block 기준을 따른다.

## 1. 콘텐츠 수정은 재시작이 있어야 반영된다

이 서버는 단일 인스턴스이며 CDN이 없다. 두 가지 콘텐츠 경로를 구분한다.

- **catalog(축제일·부스·라인업·타임테이블·지도·티켓/스탬프 안내 문구 등):** `import → validate →
  publish`로 새 revision을 만들어도, 실행 중인 프로세스는 시작 시 적재한 published snapshot만
  서비스한다. **게시 후 반드시 backend를 재시작**해야 `/readyz`와 공개 API의 `meta.revision`이
  새 revision으로 바뀐다. 재시작 전에는 게시가 끝났어도 사용자에게 이전 콘텐츠가 보인다.
- **동적 운영 데이터(공지, 굿즈 상품/재고, 혼잡도, 계좌, 공지 템플릿):** 관리자 API·CLI로 바로
  반영되며 재시작이 필요 없다. 프런트는 15초 polling으로 다음 주기에 새 값을 받는다.

행사 당일 콘텐츠를 고칠 때는 먼저 이 둘 중 어디에 속하는지 확인한다. catalog 수정인데
재시작을 잊으면 "게시했는데 안 바뀐다"는 오인 장애가 생긴다.

## 2. 계좌 교체

[계좌 운영 설정](../engineering/operational-account-settings.md)의 CLI 사용 절을 따른다. 요약:

1. account operator role로 **dry-run** 먼저 실행해 state, version, 바뀔 field, 끝 네 자리를
   확인한다(`--confirm` 없이).
2. 문제 없으면 `--confirm --last-four=<새 계좌 끝 네 자리> --expected-version=<현재 version>
   --actor=<이름> --reason=<사유> --evidence-id=<근거>`로 적용한다.
3. 응답·로그에는 계좌 원문이 남지 않는다. 끝 네 자리와 은행명만 눈으로 대조한다.
4. `TICKET`·`GOODS`는 `--purpose=TICKET`/`--purpose=GOODS`, 부스는
   `--purpose=SPACE --space-id=<부스 API id>`를 쓴다.
5. 재시작이 필요 없다. version이 올라가면 ETag가 바뀌고 다음 polling에 새 계좌가 반영된다.

계좌를 잘못 바꿨으면 `restore-version`으로 이전 version을 새 version으로 재적용한다(덮어쓰지
않고 새 이력을 쌓는다). 당장 계좌를 감춰야 하면 `clear`로 `UNCONFIGURED`로 바꾼다.

## 3. 스탬프 수령 인증 코드 교체

README의 "스탬프 수령 인증 코드 설정" 절 그대로 실행한다.

1. 새 6자리 코드를 정한다.
2. 코드를 명령 기록에 남기지 않고 SHA-256 hash로 변환한다(README의 PowerShell 스니펫).
3. `STAMP_RECEIPT_CODE_SHA256` 환경변수를 새 hash로 바꾼다. 하루 안에 교체 시점을 걸치면
   `이전hash,새hash`처럼 두 값을 콤마로 함께 넣어 두 코드를 동시에 허용하다가, 이전 코드를
   더 이상 안 쓸 때 옛 값을 뺀다.
4. **환경변수 변경은 재시작이 있어야 반영된다.** 컨테이너를 재시작한다.
5. hash도 비밀값이므로 커밋·문서·티켓에 남기지 않는다.

## 4. 장애 시 롤백

### 4.1 catalog 콘텐츠가 잘못 게시됐을 때

```powershell
java '-Dloader.main=dev.espero.festival.CatalogCliApplication' -cp target/fall-festival-server-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher rollback <되돌릴-archived-revision-uuid> --expected-current=<현재-published-revision-uuid-또는-none> --actor=<이름>
```

- `rollback`은 지정한 archived revision을 새 증가 revision draft로 복제해 다시
  validate·publish한다. 즉 "덮어쓰기"가 아니라 "그 내용으로 새 revision을 또 게시"한다.
- `--expected-current`로 지금 게시돼 있다고 생각하는 revision을 명시한다. 그 사이 다른 게시가
  끼어들었으면 `BASE_REVISION_CONFLICT`로 실패한다 — 실패하면 현재 published revision을 다시
  확인하고 재시도한다.
- rollback도 catalog이므로 **게시 후 backend 재시작**이 있어야 반영된다.
- 실시간 혼잡도 저장값은 catalog rollback 대상이 아니다. 계좌 설정도 rollback되지 않으므로
  계좌 문제는 3번 섹션의 `restore-version`/`clear`로 따로 되돌린다.
- 로컬 워크벤치를 쓸 수 있으면 [로컬 카탈로그 워크벤치](../engineering/catalog-workbench.md)의
  화면으로 같은 CLI 흐름을 브라우저에서 수행할 수 있다(단, 워크벤치 자체에는 rollback 화면이
  없으므로 rollback은 항상 CLI로 한다).

### 4.2 특정 공지·상품 하나만 잘못 올렸을 때

catalog 전체를 롤백할 필요 없이 관리자 API로 바로 고친다.

- 공지: 관리자 화면(또는 `PUT`/`DELETE /api/v2/admin/notices/{id}`)으로 수정하거나 소프트
  삭제한다. `If-Match` ETag가 필요하다.
- 굿즈 판매 상태: `PUT /api/v2/admin/goods/{goodsId}/combinations/{combinationId}/availability`로
  즉시 품절/판매중 전환한다(`If-Match` 불필요, `Idempotency-Key`만 필요).
- 상품 자체(가격·옵션 등)가 잘못됐으면 `PUT /api/v2/admin/products/{goodsId}`로 수정한다.

이 경로는 재시작이 필요 없다.

### 4.3 backend 자체가 응답하지 않을 때

A1은 root `Dockerfile`을 단일 `docker run`으로 실행하고(compose 아님), 앞단에 Caddy reverse
proxy, 그 앞에 Cloudflare가 있다. 정확한 `docker run` 커맨드, Caddy upstream, host port/network와
Cloudflare TLS/proxy 설정은 아직 확인되지 않았으므로 아래 절차의 `<운영자가 확인한 값>`을 실제
값으로 치환하기 전에는 재시작·이미지 교체를 실행하지 않는다.

1. 현재 이미지 digest/container ID와 `GET /healthz`, `GET /readyz` 결과를 기록한다. Caddy와
   Cloudflare를 통한 공개 URL에서 같은 probe를 확인한다.
2. **DB와 media volume을 같은 시점에 함께 백업한다.** PostgreSQL 백업과 named volume
   `espero-media`(`/var/lib/espero/media`)의 파일 아카이브를 각각 만들되 동일한 백업 시각·artifact
   ID를 기록한다. 한쪽만 복구하면 DB의 media 기록과 파일이 어긋난다. 실제 백업 도구·저장
   위치는 C·D가 작업 기록에 남기고, 아래의 보존 정책을 적용한다.
3. 새 이미지로 교체할 때는 기존에 확인한 `espero-media` volume을 반드시 다시 mount하고,
   DB 접속 환경변수와 `FESTIVAL_ID`를 유지한다. volume 없이 컨테이너를 재생성하지 않는다.
4. Flyway는 애플리케이션 시작 시 자동 실행될 수 있다. 현재 사전 점검 결과는 PostgreSQL
   17.11, `public` schema, V1~V26 전부 `success`이며 현재 SQL과 checksum이 일치한다.
   새 artifact가 추가 migration을 포함하거나 이력·checksum이 다르면 재시작하지 말고 DB
   담당자와 먼저 검토한다. Flyway migration을 수동으로 되돌리거나 장애 복구 중 임의로
   migrate/import/publish하지 않는다.
5. 재기동 후 `/healthz`가 200이고 `/readyz`가 200인지, 공개 API의 `meta.revision`이 의도한
   published snapshot인지 확인한다. 실제로 연결된 굿즈 이미지가 있으면 공개 굿즈 응답이 돌려준
   `images[].masterUrl`을 확인된 공개 URL에서 `GET`해 `200`인지 확인한다. 이 검증은 volume
   보존 확인과 별개다. 실제 연결 이미지가 없으면 이미지 전달 검증만 미완료로 남긴다.
6. Caddy가 컨테이너 중단 중 502를 반환하는지 재시도하는지는 실제 `Caddyfile` 확인 전까지
   단정하지 않는다. 공개 smoke가 통과하기 전에는 행사 운영을 재개했다고 보고하지 않는다.

#### DB·media 복구 세트와 보존

DB dump와 `espero-media` archive는 같은 artifact ID·생성 시각을 가진 하나의 복구 세트다. 생성,
보존 연장, 삭제, 복원은 두 artifact를 함께 처리한다. 한 artifact가 여러 보존 규칙에 해당하면
가장 늦은 만료 시점을 적용한다.

| 복구 세트 | 최소 보존 기간 | 책임자 | 삭제 전 확인 |
|---|---|---|---|
| 개발용 DB·media | 생성 후 7일 | D 데이터 리드 | 같은 recovery set의 DB·media가 모두 만료됐는지 |
| 일일 DB·media | 생성 후 14일 | D 데이터 리드 | 같은 recovery set의 DB·media가 모두 만료됐는지 |
| 행사 중 시간별 DB·media | 생성 후 72시간 | D 데이터 리드 | 같은 recovery set의 DB·media가 모두 만료됐는지 |
| 배포·DB 변경 직전 DB·media | `변경 후 7일`과 `행사 종료 후 7일` 중 더 늦은 시점 | D 데이터 리드 | 변경 작업 기록과 행사 종료 기준을 다시 대조했는지 |
| 행사 최종 DB·media | 행사 종료 후 30일 | D 데이터 리드 | 복원에 필요한 DB·media가 같은 recovery set으로 남아 있는지 |

이 정책은 실제 백업 생성이나 복원 성공의 증거가 아니다. 실행 전에는 recovery set ID, 저장 위치,
생성자, checksum 또는 검증 결과를 작업 기록에 남긴다.

DB 복구가 필요하면 같은 백업 artifact ID의 DB와 media volume을 함께 복원하고, 복원 대상이
운영 DB인지 별도 검증 대상인지 먼저 확인한다. 복원 뒤 Flyway history와 checksum, published
revision, media 파일 조회를 확인한 뒤에만 트래픽을 재개한다. 현재 A1 사전 점검은 이미 데이터가
있는 DB에서 `STOP_AND_REVIEW`로 끝났으므로 복구 중에도 새 migration이나 catalog 게시를
자동으로 실행하지 않는다.

최소한 다음은 항상 확인한다.

- `GET /healthz` (liveness) → 실패하면 프로세스 재시작이 필요하다.
- `GET /readyz` (해당 festival의 published snapshot 적재 여부) → 503이면 재시작 직후 아직 못
  읽었거나 게시본이 없는 상태다.
- DB 접속 불가로 인한 장애는 preflight·DB 담당자에게 별도로 알린다. `/readyz`는 DB 장애를
  지속적으로 감지하는 probe가 아니다.

### 4.4 A1 배포·복구 인수인계와 작업 기록

실제 SSH 접속 정보, 명령, 비밀값은 이 저장소에 적지 않는다. C 인프라·교육 리드가 다음 실제
증거를 보호된 운영 기록에 채우고, D 데이터 리드가 DB 관련 항목을 확인한다. A1 상태·컨테이너
실행 정보·Caddy/TLS·Flyway 판단·recovery set이 비어 있거나 중단 조건에 해당하면 배포·재기동·복구를
실행하지 않는다. smoke와 복원 검증은 실행 후 결과이므로, 실패하거나 미완료면 트래픽·행사 운영을
재개하지 않고 rollback 판단으로 전환한다.

| 인수인계 항목 | 주 담당 / 대체 | 작업 기록에 남길 실제 증거 | 중단 조건 |
|---|---|---|---|
| A1 접속과 현재 상태 | C / D | 승인된 접속 경로, 현재 container ID·image digest·생성 시각, 실행 중인 health 응답 | 접속 권한 또는 현재 상태를 확인할 수 없음 |
| 컨테이너 교체 | C / D | 실제 `docker run` 명령의 안전한 참조, 환경변수 주입 방식·secret 저장 위치 참조, host port/network, `espero-media` mount와 DB/FESTIVAL_ID 유지 여부 | 실제 실행 옵션·volume 연결을 확인하지 못함 |
| Caddy·TLS·Cloudflare | C / D | `Caddyfile` 위치, 설정 검증·적용·되돌림 명령, backend upstream, TLS 발급 방식, Cloudflare proxy/DNS 상태 | Caddyfile 또는 공개 HTTPS 경로를 검증하지 못함 |
| Flyway 사전 점검·적용 | D / C | V1~V26 history/checksum, 기존 data 범위, provider mutation 승인, 적용 여부와 중단 판단 | `STOP_AND_REVIEW`, `mutationAuthorized=false`, history/checksum 불일치 또는 승인 부재 |
| 이전 이미지·DB·media 복구 | C / D | 이전 image digest, 같은 recovery set ID의 DB·media artifact, 복원 대상과 검증 결과 | 이전 이미지 또는 짝지어진 recovery set이 없음 |
| 배포·복구 smoke | C / D | `/healthz`, `/readyz`, 공개 API `meta.revision`, 연결된 굿즈 이미지 `masterUrl` 200 결과 | 어느 required probe가 실패하거나 연결 이미지 전달을 확인하지 못함 |

작업 기록에는 Asia/Seoul 시각, 실행자·대체 담당, 요청·승인 근거, Git commit·image digest,
recovery set ID, 시작 전후 결과, 중단·복구 판단, 실제 연락 채널을 남긴다. 명령 본문에 비밀값을
복사하지 않고 secret reference만 기록한다.

## 5. 행사 당일 역할 분담

역할 코드는 A 서비스, B 콘텐츠, C 인프라·교육, D 데이터다. 기본 대체 담당은 A↔B, C↔D다.
이 배정은 실제 관리자·A1·DB 권한을 부여하지 않으며, 연락 수단·연락처는 행사 전 작업 기록에
확정한다.

| 역할 | 행사 전 준비 | 행사 당일 |
|---|---|---|
| A 서비스 리드 | 관리자 인증·혼잡도·공지·굿즈·스탬프 기능과 오류 대응 자료를 확인한다. | 총학생회 요청 창구, 요청 우선순위·장애 상황 총괄, 기능 오류 대응을 맡는다. |
| B 콘텐츠 리드 | 부스·지도·공연·시간표 자료를 수합·검수하고 catalog 게시·rollback을 준비한다. | 일정·장소 변경 반영, catalog 게시·rollback, 공개 화면 최종 확인을 맡는다. |
| C 인프라·교육 리드 | A1·Docker·Caddy·TLS·배포·모니터링, 운영자 교육·리허설, Git backup branch 보존을 맡는다. | 서버 상태 감시·재기동·복구, 운영 절차 안내, 교대 운영자 인수인계를 맡는다. |
| D 데이터 리드 | DB 역할·권한·Flyway, 계좌 변경 절차, DB·media backup 정책·복원 훈련을 맡는다. | DB 상태 점검, 계좌 변경, backup·restore 총괄, 데이터 정합성 확인을 맡는다. |

C가 운영자 교육·리허설을 진행하고 이수 여부를 기록한다. A는 기능·오류 대응 자료를, B는 콘텐츠
입력·검수 자료를 제공한다. 교육에는 관리자 로그인, 혼잡도 운영일 확인, 공지 등록, 굿즈 품절 전환,
스탬프 수령 인증, 장애 연락 절차를 포함한다.

| 작업 | 주 담당 | 대체 담당 | 협업·연락 경로 | 권한 필요 |
|---|---|---|---|---|
| 혼잡도·공지·굿즈·스탬프 운영 | A | B | A가 총학생회 요청을 접수한다. 스탬프 설정 적용·재기동은 C가 지원한다. | `ADMIN` 권한 관리자 계정, access JWT; 스탬프는 서버 환경변수·재시작 권한 |
| catalog 게시·rollback | B | A | D가 게시 상태를 확인하고 C가 필요한 controlled restart를 수행하며 B가 공개 결과를 확인한다. | DB catalog publish role |
| 서버 배포·장애 복구 | C | D | D가 DB 호환성을 확인하고 A가 서비스 기능을 확인한다. 서버 장애와 교육 요청이 겹치면 C는 복구에 집중하고 A가 운영자 문의를 접수한다. | A1 접근 권한 |
| Flyway·DB 권한 변경 | D | C | C가 배포 순서와 실행 환경을 확인한다. | provider가 승인한 DB 권한 |
| DB·media backup·restore·보존 | D | C | C가 media volume·외부 저장소 상태를 확인한다. | DB backup/restore와 volume 접근 권한 |
| 계좌 교체 | D | C | A가 서비스 반영 결과를 확인한다. | DB account operator role, 서버 실행 환경 접근 |
| 운영자 교육·리허설·교대 인수인계 | C | D | A가 기능·오류 대응 자료를, B가 콘텐츠 입력·검수 자료를 제공한다. | 교육 자료와 관리자 절차 접근 |
| Git backup branch 보존·정리 | C | D | D가 삭제 전 DB 호환성·복구 필요성을 확인한다. | Git ref 관리 권한 |

실제 연락 채널·연락처·당직 시간은 아직 확정되지 않았다. 행사 전에는 각 작업 행의 주·대체 담당이
서로 연락 가능한 채널과 인수인계 시각을 작업 기록에 남겨야 한다.

### 5.1 Git backup branch 보존

Git backup branch는 행사 종료 후 30일까지 C가 보존한다. 미해결 복구·인수인계가 있으면 기존
종료일과 해결 완료 후 7일 중 더 늦은 시점까지 연장한다. 삭제 전에는 C가 해당 commit의 release
tag, 대응 Docker image digest와 이미지 보존 여부를 확인하고 D가 DB 호환성·복구 필요성이 없음을
확인한다. Git branch는 DB·media recovery set을 대체하지 않는다.

혼잡도 저장은 구현된 인증 관리자 API다. 먼저 관리자 인증 access JWT로
`GET /api/v2/admin/crowding`을 호출해 현재 상태의 `ETag`와 `data.operatingDay`를 얻는다.
Asia/Seoul의 오늘이 실제 published `FestivalDay`이고 `data.operatingDay`도 오늘과 같은지 먼저
확인한다. 축제 전·운영일 사이 공백일에는 다음 운영일, 축제 종료 뒤에는 마지막 운영일이
응답될 수 있지만 그 날짜를 저장 대상으로 사용하지 않고 `PUT`하지 않는다. 확인 뒤 같은 관리자
인증으로 다음 요청을 보낸다.

```http
PUT /api/v2/admin/crowding
Authorization: Bearer <access-jwt>
If-Match: "<현재 상태의 64자리 hex ETag>"
Idempotency-Key: <재시도에 재사용할 1~128자 키>
Content-Type: application/json

{"level":"CROWDED"}
```

성공과 동일 요청 재시도는 `204 No Content`다. `FULL`은 `{"level":"FULL",
"confirmFull":true}`로만 저장한다. `If-Match` 또는 `Idempotency-Key`가 없으면 `428`, 형식이
잘못되면 `400`이다. `409 EDIT_CONFLICT`이면 다른 운영자가 먼저 저장해 ETag가 바뀐 것이므로
현재 GET을 다시 호출해 새 ETag를 확인하고, 의도한 최신 상태를 검토한 뒤 새로운
Idempotency-Key로 다시 요청한다. `409 NOT_FESTIVAL_DAY`이면 저장을 중단하고 재시도하지 않는다.
인증 실패는 `401`이며 관리자 계정·권한을 확인한다. 성공 후 공개 `GET /api/v2/crowding`의
ETag와 상태가 바뀌었는지 다음 polling 또는 즉시 조회로 확인한다. 혼잡도 저장은 동적 운영
데이터이므로 backend 재시작이나 catalog rollback이 필요 없다.

## 6. 체크리스트 요약

- [ ] catalog 콘텐츠를 고쳤으면 게시 후 backend를 재시작했다.
- [ ] 계좌를 바꿨으면 dry-run으로 먼저 확인했고, 끝 네 자리만 대조했다.
- [ ] 스탬프 코드를 바꿨으면 환경변수 변경 후 재시작했고, 교체 시점엔 두 코드를 함께 열어뒀다.
- [ ] 롤백은 catalog와 계좌를 구분해서 실행했다(계좌는 catalog rollback으로 되돌아가지 않는다).
- [ ] `/healthz`·`/readyz`로 재시작 뒤 정상 기동을 확인했다.
- [ ] 재기동·이미지 교체 전 DB와 `espero-media` volume을 같은 백업 artifact ID로 함께 보관했다.
- [ ] backup recovery set의 보존 기간과 삭제 예정 시점이 정책에 맞고, DB·media가 함께 관리된다.
- [ ] 복구 시 Flyway V1~V26 history/checksum, published revision, media 이미지 조회를 확인했다.
- [ ] 재기동 뒤 실제로 연결된 굿즈 이미지의 확인된 공개 URL이 `200`을 반환했다(이미지가 없으면 이 항목은 미완료다).
- [ ] 혼잡도 저장 전 Asia/Seoul의 오늘이 실제 `FestivalDay`이고 GET의 `data.operatingDay`도 오늘인지 확인했다. 관리자 JWT, 최신 `If-Match`, `Idempotency-Key`를 사용해 204 및 공개 조회 반영을 확인했으며, `NOT_FESTIVAL_DAY`이면 중단했다.
- [ ] A1 접속·container/image·실행 옵션·Caddy·Flyway·복구 세트의 실제 증거와 중단 판단을 작업 기록에 남겼다.
- [ ] 이 문서의 역할 분담표에 모든 작업의 주 담당·대체 담당·실제 연락 채널·인수인계 시각이 기록돼 있다.
