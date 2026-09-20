# 행사 당일 운영 절차서

[위키 홈](../README.md) · 읽는 때: 행사 기간 중 콘텐츠 수정·계좌 교체·스탬프 코드 교체·장애 대응을 실제로 수행할 때

이 문서는 명령을 실행하는 절차만 다룬다. 각 CLI·API의 설계 근거는
[게시](../engineering/publishing.md), [계좌 운영 설정](../engineering/operational-account-settings.md),
[운영](../engineering/operations.md)을 따른다. A1 서버의 실제 접속 방법(SSH, 배포 경로)은
[원격 개발 환경 결정](../../dev-deployment-decision.md)에 A1 실행 정보가 채워진 뒤 이 문서에 절 링크를
추가한다.

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
proxy, 그 앞에 Cloudflare가 있다. 정확한 `docker run` 커맨드와 `Caddyfile` 내용은 아직 공유받지
않아 재시작·이미지 롤백의 정확한 명령은 이 절에 채우지 못했다([원격 개발 환경 결정](../../dev-deployment-decision.md)의
"A1 확인 답변"·"여전히 확인 필요" 참고). 그 전까지 확인된 원칙만 남긴다.

- 새 이미지로 교체할 때는 `-v espero-media:/var/lib/espero/media` named volume을 반드시
  다시 붙인다. volume 없이 재생성하면 굿즈 이미지가 사라지고 DB 기록만 남아 조회가 503이 된다.
- 컨테이너를 내리고 다시 올리는 동안 Caddy가 어떻게 반응하는지(502를 그대로 보여주는지, 재시도
  하는지)는 `Caddyfile` 확인 후 채운다.
- DB migration 상태가 아직 미확정이므로, 장애 복구 중이라도 원격 DB에 새 migration을 적용하지
  않는다. Flyway는 기본적으로 시작 시 자동 적용되므로, 확정 전 backend를 재시작하면 의도치
  않게 migration이 실행될 수 있다는 점을 인지한다 — 이 위험이 해소될 때까지는 재시작 전 DB
  상태를 먼저 확인한다.

최소한 다음은 항상 확인한다.

- `GET /healthz` (liveness) → 실패하면 프로세스 재시작이 필요하다.
- `GET /readyz` (해당 festival의 published snapshot 적재 여부) → 503이면 재시작 직후 아직 못
  읽었거나 게시본이 없는 상태다.
- DB 접속 불가로 인한 장애는 preflight·DB 담당자에게 별도로 알린다. `/readyz`는 DB 장애를
  지속적으로 감지하는 probe가 아니다.

## 5. 행사 당일 역할 분담

실행 담당자는 팀에서 확정해 이름을 채운다. 같은 사람이 여러 역할을 겸할 수 있다.

| 역할 | 담당 | 권한 필요 |
|---|---|---|
| 혼잡도 입력 | (미정) | 관리자 계정 (현재 `PUT /admin/crowding` 자체가 미구현 — 아래 주의 참고, 구현 전까지는 운영 불가) |
| 공지 게시·수정·삭제 | (미정) | 관리자 계정 |
| 굿즈 재고(판매중/품절) 전환 | (미정) | 관리자 계정 |
| 굿즈 상품 등록·수정 | (미정) | 관리자 계정 |
| 계좌 교체(CLI) | (미정) | DB account operator role, 서버 실행 환경 접근 |
| 스탬프 코드 교체 | (미정) | 서버 환경변수·재시작 권한 |
| catalog 게시·rollback(CLI) | (미정) | DB catalog publish role |
| A1 서버 재시작·장애 대응 1차 | (미정) | A1 접근 권한 (제원 님 또는 위임자) |

> **주의:** 관리자 혼잡도 저장(`PUT /api/v2/admin/crowding`)은 관리자 인증·권한 기반이 아직
> 없어 의도적으로 만들지 않았다(무인증으로 열면 누구나 혼잡도를 조작할 수 있는 구멍이 생김).
> 인증 기반이 나오면 바로 이어서 구현한다. 그 전까지 혼잡도는 공개 조회만 가능하고 운영자가
> 값을 저장할 방법이 없다 — 행사 전 역할 분담표를 확정하기 전에 먼저 이 기능부터 구현해야 한다.

## 6. 체크리스트 요약

- [ ] catalog 콘텐츠를 고쳤으면 게시 후 backend를 재시작했다.
- [ ] 계좌를 바꿨으면 dry-run으로 먼저 확인했고, 끝 네 자리만 대조했다.
- [ ] 스탬프 코드를 바꿨으면 환경변수 변경 후 재시작했고, 교체 시점엔 두 코드를 함께 열어뒀다.
- [ ] 롤백은 catalog와 계좌를 구분해서 실행했다(계좌는 catalog rollback으로 되돌아가지 않는다).
- [ ] `/healthz`·`/readyz`로 재시작 뒤 정상 기동을 확인했다.
- [ ] 이 문서의 역할 분담표가 채워져 있다.
