# 프런트 개발용 목 데이터

원격 개발 서버(`api-festival.likelionerica.com`)에 프런트가 화면을 개발할 수 있을 만큼의 **가상 데이터**를
넣는 절차입니다. 실제 행사 정보가 아니며 나중에 실데이터로 바꿉니다.

- 모든 이름은 `[목]`으로 시작하고, 링크는 `example.invalid`를 씁니다. 예외는 HOME-008에서 확정한
  총학생회 공식 채널 3개입니다.
- 서버 응답의 `meta.mock`은 실서버라서 항상 `false`입니다. 목 데이터인지는 `[목]` 접두어로 구분합니다.
- 이 서버와 DB는 공개되어 있어서 넣은 데이터는 누구나 볼 수 있습니다. 실제 계좌·개인정보는 넣지 않습니다.
- 원격 DB에 쓰는 작업이므로 팀의 승인을 받고 진행합니다.

## 무엇이 들어가나

| 데이터 | 파일 | 넣는 방법 |
|---|---|---|
| 부스 20개(6개 분류 모두), 지도 4장·핀 28개(편의시설 필터 4종), 공연 4개·아티스트 5명, 티켓·스탬프 안내, 반입 금지 물품, 홈 링크 | [`dev/catalog/frontend-mock-catalog.json`](../catalog/frontend-mock-catalog.json) | [`Publish-MockCatalog.ps1`](Publish-MockCatalog.ps1) |
| 공지 3개(일반 2, 분실물 1, 링크 포함 1), 굿즈 3개(단일 2, 색상×사이즈 옵션 1) | [`seed-admin-content.mjs`](seed-admin-content.mjs) | [`Seed-AdminMockContent.ps1`](Seed-AdminMockContent.ps1) |
| 티켓 계좌, 부스 계좌 | 아래 JSON 예시 | 계좌 CLI |

부스는 운영 정보가 비어 있는 경우(운영 주체·시간·설명·SNS 없음), 메뉴가 많은 주점, 이벤트가 없는 부스처럼
화면의 빈 상태를 확인할 수 있게 값을 섞었습니다. catalog는
`python dev/catalog/generate-frontend-mock-catalog.py`로 다시 만듭니다. 부스·지도 이미지는
`placehold.co`의 자리 표시 이미지입니다.

## 1. catalog (부스·지도·공연·안내)

`Publish-MockCatalog.ps1`이 읽기 전용 사전 점검 → 현재 revision 확인 → 기준 revision 자동 선택 →
import → publish를 한 번에 합니다. 비밀번호는 화면에 남지 않게 입력받고 이 process 안에서만 씁니다.

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -DskipTests package
.\dev\admin-mock\Publish-MockCatalog.ps1 -DatabaseUrl 'jdbc:postgresql://<host>:<port>/<database>?sslmode=require' -Username '<DB 사용자>' -FestivalId '<축제 UUID>' -Actor '<본인 이름>' -DryRun
```

- **축제 UUID**는 `GET /api/v2/config` 응답의 `data.festival.id`입니다.
- `-DryRun`은 DB를 바꾸지 않고 현재 revision과 기준 revision만 보여 줍니다. 먼저 이걸로 확인하세요.
- 실제로 게시하려면 `-DryRun`을 빼고 실행한 뒤 `yes`를 입력합니다. 확인 없이 진행하려면 `-Force`.
- SSL을 쓰지 않는 DB면 URL에서 `?sslmode=require`를 뺍니다. schema가 `public`이 아니면 `-Schema`를 줍니다.
- 사전 점검이 `STOP_AND_REVIEW`로 나오는 것은 **정상**입니다. 이미 축제·catalog 데이터가 있는 DB라는 뜻이며,
  이 작업은 migration이 아니라 기존 게시본 위에 새 revision을 얹는 것입니다.
- 기준 revision이 그사이 바뀌면 `BASE_REVISION_CONFLICT`로 멈춥니다. 다시 실행하면 새 기준으로 진행합니다.

게시 뒤 **백엔드를 재시작**해야 공개 API에 새 revision이 보입니다. `/readyz`가 200이고 `/api/v2/spaces`의
`meta.revision`이 올라갔는지 확인합니다.

워크벤치를 쓸 수도 있지만 loopback DB 주소만 받으므로 승인된 SSH tunnel이 필요합니다
([워크벤치 runbook](../../docs/wiki/engineering/catalog-workbench.md)).

## 2. 공지·굿즈 (관리자 API)

Node 18 이상이 필요합니다. `-AdminOrigin`은 서버의 `ADMIN_ALLOWED_ORIGIN`과 같아야 합니다.

```powershell
.\dev\admin-mock\Seed-AdminMockContent.ps1 -BaseUrl 'https://api-festival.likelionerica.com' -AdminOrigin '<서버의 ADMIN_ALLOWED_ORIGIN>' -Username '<관리자 아이디>' -DryRun
.\dev\admin-mock\Seed-AdminMockContent.ps1 -BaseUrl 'https://api-festival.likelionerica.com' -AdminOrigin '<서버의 ADMIN_ALLOWED_ORIGIN>' -Username '<관리자 아이디>'
```

- `-DryRun`은 로그인 없이 보낼 내용만 출력합니다.
- 한국어 제목·이름이 이미 있는 항목은 건너뛰므로 여러 번 실행해도 중복되지 않습니다.
- 굿즈 이미지는 스크립트가 1024×1024 단색 PNG를 만들어 올립니다. 서버가 WebP로 변환하려면
  `cwebp`가 필요해서 Docker 이미지에서만 동작합니다. Windows에서 jar를 직접 띄우면 이미지 업로드가 503입니다.

## 3. 계좌 (티켓·부스)

[계좌 CLI](../../docs/wiki/engineering/operational-account-settings.md)로 넣습니다. 은행명·예금주에 `[목]`을
붙이고 계좌번호는 `0`으로 채운 가상 값을 씁니다. 입력 파일은 사용 뒤 지웁니다.

```json
{"bankName": "[목] 가상은행", "accountNumber": "000000000000", "accountHolder": "[목] 예금주", "transferLinkUrl": null}
```

```json
{"bankName": "[목] 가상은행", "accountNumber": "000000001234", "accountHolder": "[목] 달빛포차", "bankId": "mock-bank", "tossLinkEnabled": false}
```

첫 번째는 `--purpose=TICKET --last-four=0000`, 두 번째는 `--purpose=SPACE --space-id=mock-pub-moonlight
--last-four=1234`로 설정합니다. 티켓 계좌가 없으면 티켓 안내가 `UNCONFIGURED`로 나옵니다.

## 실데이터로 바꿀 때

| 데이터 | 방법 |
|---|---|
| catalog | 실데이터 manifest를 새로 import·publish하고 재시작합니다. 목 revision은 `archived`로 남으며 공개되지 않습니다. |
| 공지·굿즈 | 관리자 화면이나 API에서 `[목]` 항목을 삭제합니다. 굿즈 이미지 파일은 서버 volume에 남습니다. |
| 계좌 | 계좌 CLI의 `set`으로 실제 값으로 바꾸거나 `clear`로 해제합니다. 이력은 남습니다. |

## 검증

- `CatalogRevisionServiceIntegrationTest.importsAndPublishesTheFrontendMockCatalog`: 목 catalog가 실제 DB에
  import·publish되고 공개 snapshot으로 읽힙니다.
- 로컬 PostgreSQL과 Linux 컨테이너에서 CLI import·publish, 두 번째 import(수정)와 재시작 반영, 공개 API 13개
  경로, 스크립트의 공지·굿즈 생성과 재실행 시 건너뛰기, 굿즈 이미지 조회를 확인했습니다.
- `Publish-MockCatalog.ps1`을 V26까지 migration한 로컬 DB에서 `-DryRun`과 실제 게시로 두 번 실행해, 기준
  revision 자동 선택과 재실행 시 새 기준 사용(부스 20·핀 28·홈 링크 6행)을 확인했습니다.
