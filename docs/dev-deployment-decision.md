# 원격 개발 환경 배포 결정

## Status

- 상태: backend는 A1 인스턴스에 Docker로 이미 배포돼 있다. 이 문서는 확인된 A1 사실과
  아직 확인하지 못한 운영 값을 구분한다.
- 기준: 2026-09-20 읽기 전용 사전 점검 결과와 현재 `main`의 Flyway V1~V26 SQL
- Provider 정책 확인일: 2026-09-17 (Render 기본안 검토 당시. A1로 바뀐 뒤에는 해당 없음)
- 범위: remote development only; production hosting 결정이 아님
- 검증 게이트: `READ_ONLY_DATABASE_PREFLIGHT`, `DEPLOYMENT_VALIDATION_REQUIRED`

### Gate status

| 게이트 | 현재 상태 | 종료 조건 |
|---|---|---|
| `READ_ONLY_DATABASE_PREFLIGHT` | 읽기 전용 조사는 완료했지만 결과는 `STOP_AND_REVIEW`, `mutationAuthorized=false`다. 변경 권한을 뜻하지 않는다. | 기존 data·사용 범위와 mutation 승인 기록을 확인한다. 그 전에는 migration, import, publish, role 변경을 실행하지 않는다. |
| `DEPLOYMENT_VALIDATION_REQUIRED` | 미완료다. backend가 A1에 이미 떠 있다는 사실만으로 이 게이트가 통과한 것은 아니다. | 아래 배포 검증 목록의 해당 항목을 실제 로그·응답으로 확인해 기록한다. 재기동 뒤 연결된 굿즈 이미지의 공개 URL `200` 확인도 포함한다. |

> **2026-09-20 갱신:** backend는 Render가 아니라 A1 인스턴스에서 root `Dockerfile`의
> 이미지를 단일 `docker run`으로 실행한다. Caddy reverse proxy와 Cloudflare edge 연결은
> 확인했지만 SSH 접속·host port·Caddy upstream·TLS 발급 방식·Cloudflare proxy 모드는
> 확인하지 못했다. 확인되지 않은 운영값은 추정해 채우지 않는다.

### A1 확인 답변 (2026-09-20, 제원)

- 실행 방식: 단일 `docker run`(root `Dockerfile`), compose 아님.
- reverse proxy: Caddy(`Caddyfile`). Cloudflare에 연결 중(도메인 edge).
- 관리자 웹 origin: `admin-festival.likelionerica.com`으로 라우팅 예정. **아직 배포 전**이며
  연락받은 계획 단계다.
- `STAMP_RECEIPT_CODE_SHA256`: 이 세션에서 코드를 생성해 hash를 전달했다(코드·hash는 문서에
  남기지 않는다. 비밀값이므로 repo/커밋/티켓에 넣지 않는다).

### DB 확인 완료 (2026-09-20, `DatabasePreflightApplication` 실행 결과)

- PostgreSQL 17.11, schema `public`. migration은 **V1~V26 전부 적용, 전부 success, 현재
  `main`의 SQL과 checksum 완전 일치**(drift 없음).
- festival 1개 존재: `id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf`, timezone `Asia/Seoul`이다.
  이 값은 해당 DB에서 확인된 값이며 새 환경의 기본값으로 가정하지 않는다.
- revision 4개: 1·2 archived, **3 published**, 4 draft. `goods`·`notices` 테이블은 0행(아직
  상품·공지 없음).
- 다른 client session 10개 감지됨. preflight만으로 A1 backend인지 다른 서비스인지 식별할 수
  없으므로 DB 단독 사용이나 A1 연결의 증거로 해석하지 않는다.
- 결과는 예정대로 `status=STOP_AND_REVIEW`, `mutationAuthorized=false`다. 이미 festival·게시된
  revision이 있기 때문에 자동으로 뜨는 판정이며, 여기서 새 migration·import·publish를 함부로
  실행하지 않는다는 뜻이다. **이 조사 자체는 SELECT만 사용했고 아무것도 바꾸지 않았다.**
- **revision 3 내용 확인 완료(같은 날):** `CatalogCliApplication export`(읽기 전용 트랜잭션,
  DB에 쓰지 않음)로 revision 3(`53f49114-0e1e-4dac-978e-6fa21c2f6230`)을 직접 꺼내봤다. **mock/개발용
  synthetic 카탈로그였다** — 부스 id가 전부 `mock-` 프리픽스, 이름에 "[목]" 표시, 이미지가
  `placehold.co` 플레이스홀더, 연락처가 `example.invalid`. 실제 행사 콘텐츠가 아니다. 즉 A1은
  이미 개발용 카탈로그가 게시된 상태로 떠 있고, 실제 2026 한양 축제 데이터는 아직 들어가지
  않았다(아래 "여전히 확인 필요"와 노션 체크리스트 3번 "운영 데이터 입력"이 그대로 필요하다).

### 여전히 확인 필요

- [ ] `Caddyfile` 실제 내용(어떤 host/port를 backend container로 proxy하는지, TLS 발급을
  Caddy 자체 ACME로 하는지 Cloudflare Origin CA를 쓰는지)
- [ ] Cloudflare가 proxy 모드(주황 구름)인지 DNS-only인지 — proxy 모드면 실제 client IP가
  `CF-Connecting-IP`/`X-Forwarded-For`로 오므로 `RATE_LIMIT_TRUSTED_PROXY_HOPS` 산정에 영향
- [ ] container가 host의 어떤 port에 바인딩되는지, Caddy가 그 port로 proxy하는지 docker
  network로 묶여 있는지
- [ ] [역할 provisioning](#역할-provisioning) 실행 여부, 단일 계정인지 분리됐는지
- [ ] `admin-festival.likelionerica.com` 실제 배포 시점 (배포 전까지 `ADMIN_ALLOWED_ORIGIN`은
  운영 값으로 바꾸지 않는다)

### 확정된 운영 책임·보존 정책 (2026-09-22)

운영 역할은 A 서비스, B 콘텐츠, C 인프라·교육, D 데이터로 정한다. 기본 대체 담당은 A↔B,
C↔D다. C는 A1 배포·복구·운영자 교육과 Git backup branch 보존을, D는 Flyway·DB 권한과
DB·media backup·restore를 맡는다. 각 작업의 협업 관계와 실제 작업 기록 항목은
[행사 당일 운영 절차서](wiki/workflow/festival-day-runbook.md)에 둔다.

DB와 media는 하나의 recovery set으로 함께 보존·삭제한다. 일일 backup은 14일, 행사 중
시간별 backup은 72시간, 행사 최종 backup은 종료 뒤 30일, 변경 직전 backup은 변경 뒤 7일과
행사 종료 뒤 7일 중 더 늦은 때까지 보관한다. Git backup branch는 C가 행사 종료 뒤 30일까지
보존하고, 미해결 복구·인수인계가 있으면 기존 종료일과 해결 뒤 7일 중 더 늦은 때까지 연장한다.

이 정책은 권한 발급, backup 생성, restore 성공, `STOP_AND_REVIEW` 해소 또는 배포 게이트 통과를
뜻하지 않는다. 실제 SSH·container/image·`docker run`·Caddy·Cloudflare·Flyway 증거와 연락
채널은 작업 전에 별도로 기록해야 한다.

## Context

원격 프런트엔드는 아직 배포되지 않았다. backend는 A1에 이미 배포돼 있다. 프런트엔드 저장소
`LikeLion14th-ERICA/fall-festival-web`은 Next.js 16 App Router에서
`NEXT_PUBLIC_API_BASE_URL=/api/v2`와 `API_PROXY_TARGET=<backend-origin>`을 사용해
API 요청을 rewrite할 수 있다. 백엔드는 루트 `Dockerfile`, PostgreSQL과 `db` profile을
지원하며 A1에 배포돼 있다. frontend provider와 public frontend origin은 아직 정해지지 않았다.

## Decision

초기 원격 개발 환경은 다음을 기본안으로 사용한다.

- Frontend provider: `TBD`. 기존 Next.js `/api/v2` rewrite 구조는 유지한다.
- Backend: A1 인스턴스에서 root `Dockerfile`을 단일 `docker run`으로 실행하고, Caddy가
  reverse proxy, Cloudflare가 edge를 맡는다(이미 이 방식으로 올라가 있다).
- Database: 팀에서 remote development 용도로 사용을 승인한 PostgreSQL database를 사용한다.
  2026-09-20 preflight에서 PostgreSQL 17.11, public schema, V1~V26 success와 checksum 일치를
  확인했다. 기존 데이터와 동시 사용 여부가 남아 있어 `STOP_AND_REVIEW`이며 승인 전 mutation을
  실행하지 않는다.
- Development catalog: repository에서 추적하는 synthetic development manifest를 사용한다.
- Browser/API: same-origin proxy를 우선하며 public API CORS는 추가하지 않는다.
- Health check candidate: `/readyz`.

GitHub resource와 secret은 이 결정만으로 생성하지 않는다. 팀 제공 database에도 이 문서
변경만으로 접속하거나 변경을 적용하지 않는다. migration 적용은 확인된 상태에서만 진행한다.

## Topology

```text
Browser
  |
  | HTTPS
  v
Cloudflare (edge, proxy 모드 여부 확인 필요)
  |
  v
Caddy reverse proxy (A1, Caddyfile 실제 내용 확인 필요)
  |
  | -> backend container로 proxy
  v
Backend: 단일 docker run 컨테이너 (root Dockerfile, A1)
  |
  | PostgreSQL connection
  v
Team-provided PostgreSQL database
```

브라우저에는 backend container를 API base로 직접 노출하지 않는다. Public frontend와 admin
frontend가 각각 어떤 origin에서 이 topology 앞단(Cloudflare/Caddy)으로 붙는지는 프런트엔드
hosting이 확정된 뒤 정한다. 관리자 웹은 `admin-festival.likelionerica.com`으로 라우팅될
예정이지만 아직 배포 전이다.

## Frontend

Frontend provider는 remote deployment가 없고 private GitHub repository의 hosting 조건이
정해지지 않아 `TBD`로 둔다. 선택할 provider는 다음을 모두 지원해야 한다.

- Next.js server/runtime 또는 `/api/v2/**` rewrite
- HTTPS
- environment variable 주입
- 외부 A1 backend(Cloudflare/Caddy 뒤)로의 server-side proxy

Browser는 상대 경로 `/api/v2/**`만 호출하고 Next.js는 `API_PROXY_TARGET`으로 전달한다.
Remote host가 이 rewrite를 지원하지 않으면 배포 전에 topology와 public CORS 결정을 다시
검토한다.

## Backend

Backend는 A1 인스턴스에서 root `Dockerfile`로 빌드한 이미지를 **단일 `docker run`**(compose
아님)으로 실행한다. 앞단에 Caddy가 reverse proxy로 붙고, Caddy 앞에는 Cloudflare가 있다.
서비스는 public HTTPS endpoint를 Caddy/Cloudflare를 통해 가지며, browser가 container를
직접 호출하지 않는다.

루트 image는 `SERVER_ADDRESS=0.0.0.0`, `SERVER_PORT=8080`을 기본값으로 둔다(Dockerfile
`ENV`, `EXPOSE 8080`). Render 전용이었던 `PORT=10000` override는 현재 A1 실행 방식의
근거가 아니다. 애플리케이션 컨테이너의 Dockerfile 기본 listen port는 `8080`이지만 실제
host 매핑과 Caddy upstream은 확인 전까지 미확정으로 둔다. `Caddyfile`과 A1 실행 정보를
확보한 뒤에만 정확한 port·network 값을 기록한다.

## Database

The team-provided PostgreSQL database is approved for remote development use. A1 backend는
이 database에 접속하며 새 managed PostgreSQL provider를 provision하지 않는다. 이 승인은 사용
허가를 뜻할 뿐 schema와 migration의 호환성 또는 단독 사용을 보장하지 않는다. 다른 서비스나
팀이 같은 database 또는 schema를 쓰는지는 아직 확인되지 않았으므로 `exclusive development DB`로
표현하지 않는다.

최초 접속은 `READ_ONLY_DATABASE_PREFLIGHT`로 제한한다. 별도의 read-only 조사 경로에서
`SELECT`와 metadata 조회만 사용해 다음을 확인한다.

- PostgreSQL version, current database와 current user
- 접근 가능한 schema와 기존 table
- `flyway_schema_history` 존재 여부와 적용된 migration version
- `festivals`, `festival_revisions` 존재 여부
- 현재 festival, revision, published revision과 catalog data 존재 여부
- 다른 서비스나 팀의 동일 database/schema 사용 여부

안전성 판정 전에는 `CREATE`, `ALTER`, `DROP`, `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, Flyway
migrate, catalog import와 catalog publish를 실행하지 않는다. Flyway가 기본 활성화된 web/CLI
process도 이 database를 대상으로 먼저 기동하지 않는다. 이 점검은 저장소의 standalone
`DatabasePreflightApplication`으로 수행한다(SELECT만 실행, 불명확하면 `STOP_AND_REVIEW`,
[DB 읽기 전용 사전 점검](wiki/engineering/database-preflight.md)). Network access, database/schema 상태,
migration history와 V1~V26 호환성을 확인한 뒤에만 mutation 단계로 진행한다. 공유 schema라면
별도 database 또는 schema가 필요한지 다시 결정한다.

팀에서 제공한 connection 정보는 다음 Spring 변수로 분리해 A1의 secret/environment settings에만
저장한다. credential, 실제 endpoint와 전체 connection string은 repository에 기록하지 않는다.

```text
SPRING_DATASOURCE_URL=<jdbc-postgresql-url>
SPRING_DATASOURCE_USERNAME=<username>
SPRING_DATASOURCE_PASSWORD=<secret>
```

일반 PostgreSQL URI를 받더라도 실제 deployment 단계에서
`jdbc:postgresql://<db-host>:<db-port>/<database>` 형식과 Spring JDBC 호환성을 검증한다. SSL과
direct/pooled connection option은 read-only preflight와 제공 조건을 확인한 뒤 정하며 추정 option을
미리 고정하지 않는다.

`FESTIVAL_ID`에는 read-only preflight와 이후 승인된 migration 결과로 이 development DB에 실제
존재함을 확인한 `festivals.id`를 사용한다. 기존 festival이 있으면 그 사용 가능성을 먼저 검토하고,
빈 호환 schema에 V1~V26을 적용한 경우에만 Flyway가 만든 development festival을 확인한다. V6 seed
UUID를 무조건 가정하거나 production/shared 환경의 UUID를 추측해 사용하지 않는다. UUID literal은
이 decision에 복제하지 않는다.

### Development catalog bootstrap

Development catalog의 source는 저장소의 synthetic development manifest
[`dev/catalog/development-catalog.json`](../dev/catalog/README.md)이다. 운영 DB dump,
production/shared DB 복제, production credential과 승인되지 않은 실제 운영 데이터를 사용하지
않는다. 예외로 HOME-008에서 확정한 총학생회 공식 채널 링크만 들어 있다.

Database와 catalog lifecycle은 다음 gate를 따른다.

```text
team-provided PostgreSQL development database
  -> READ_ONLY_DATABASE_PREFLIGHT (SELECT / metadata only)
  -> network, schema, flyway_schema_history와 기존 data 확인
  -> migration compatibility 판정
  -> 안전할 때만 Flyway V1-V26 적용 또는 현재 migration 상태에서 continue
  -> DB 제공자가 발급한 역할에 provisioning script 적용
  -> development DB의 실제 festivals.id를 FESTIVAL_ID로 결정
  -> 기존 festival/catalog data가 있으면 STOP_AND_REVIEW
  -> 로컬 catalog workbench 또는 CatalogCliApplication으로
     import <synthetic-manifest> -> validate -> publish
  -> web backend 최초 시작 또는 실행 중인 backend의 controlled restart
  -> GET /readyz 확인
  -> public API smoke test
```

Database가 이미 festival 또는 catalog data를 포함하면 current revision, published revision,
festival ID와 기존 catalog 내용을 먼저 확인한다. 기존 데이터를 자동으로 덮어쓰거나 republish하지
않으며 의도하지 않은 data가 있으면 `STOP_AND_REVIEW`한다.

Migration compatibility가 확인된 뒤에만 `CatalogCliApplication` 또는
[로컬 카탈로그 워크벤치](wiki/engineering/catalog-workbench.md)를 사용한다. 워크벤치는 loopback
DB URL만 받으므로 원격 DB에는 승인된 SSH tunnel로 연결하고, export·publish 역할을 분리한다. 이 entry point는 web
backend와 분리되어 있지만 startup에서 Flyway를 실행할 수 있으므로 preflight 전에는 대상 DB에
연결하지 않는다. `import`는 manifest의 `festivalId`에 해당하는 기존 festival을 잠그고 새 draft
revision을 만들며, `validate`와 `publish`는 import가 출력한 revision UUID를 받는다. 실제
launcher/package 명령은 deployment branch에서 현재 build artifact로 검증한 뒤 기록한다.

Synthetic manifest는 단순 `/readyz` 성공이 아니라 lineup, timetable, artist detail, performance
detail과 prohibited-items 계약을 의미 있게 검증해야 한다. 최소 FestivalDay, timetable config,
artist와 한국어 translation, performance와 한국어 translation, performance-artist relation을 포함한다.
Prohibited item/message는 계약과 validator가 empty/null을 허용하므로 의도한 empty 응답도 허용한다.

모든 값은 synthetic 또는 명확한 development-only 값이어야 한다. 승인된 timezone과 locale 정책
같은 구조적 사실은 source of truth를 따르되, 미승인 실제 artist·booth·timetable, private URL과
운영 DB 데이터를 복사하지 않는다. 특히 FestivalDay open/close 시각은 추측하지 않고 승인된
development test-data 결정이 나온 뒤 manifest에 넣는다.

### Migration 주의

- V16은 기존 `crowding_state` 행이 있으면 `FESTIVAL_ID` placeholder를 요구하고, 축제·날짜가 맞지
  않으면 중단한다.
- V19는 기존 지도 핀의 이전 대분류 filter 값을 지우고 label을 삭제한다(디자인 필터로 교체).
- V23은 운영 계좌 테이블의 key·제약·history trigger를 다시 만든다. 기존 TICKET·GOODS 값과 이력은
  유지한다.
- V24는 번역 테이블 4개(축제명·지도 이미지 대체 텍스트·티켓·스탬프 안내)를 추가만 한다. 기존 행은
  바꾸지 않는다. catalog export·publish 역할에 이 테이블 권한을 provider가 함께 준다.
- V26은 공지 템플릿 테이블과 `notices.template_id`를 추가하고 `템플릿 등록 필요` 임시 템플릿
  하나를 넣는다. 실제 템플릿은 템플릿 CLI로 교체한다.
- 계좌·role 관련 migration 뒤에는 역할 provisioning script를 다시 실행한다.

### 역할 provisioning

DB 제공자에게 migration, runtime, cleanup, account operator, catalog export, catalog publish,
preflight 역할을 요청한다. migration 뒤 migration 역할로
[`tools/database/provision-operational-account-roles.sql`](../tools/database/provision-operational-account-roles.sql)을
`psql -v` 변수로 실행한다. 이 script는 계좌 테이블 권한을 나누고, provider가 schema 전체를
grant했더라도 catalog 역할에서 계좌·혼잡도·공지·굿즈 테이블 권한을 회수한다. catalog 역할에는
catalog 테이블(V22 `festival_links` 포함) 권한을 provider가 따로 준다. 단일 계정만 제공되면 역할
분리를 강제할 수 없음을 기록하고 운영 배포는 진행하지 않는다.

## 굿즈 이미지 저장소

굿즈 이미지는 `FESTIVAL_MEDIA_STORAGE_ROOT` 디렉터리에 파일로 저장하고 DB에는 media 기록만 둔다.
Docker image는 이 값을 `/var/lib/espero/media`로 두고 UID/GID 10001 소유로 만든다.

- 이 경로에 named volume을 mount한다. volume이 없으면 container를 다시 만들 때 파일이 사라지고
  DB 기록만 남아 이미지 조회가 `503`이 된다.
- 값이 없으면 업로드·조회가 `503 MEDIA_STORAGE_UNCONFIGURED`이며 상품을 등록할 수 없다. 빈 값이나
  container 사용자가 쓸 수 없는 경로는 startup에서 실패한다.
- DB와 volume은 같은 시점으로 함께 백업·복구한다. 한쪽만 복구하면 이미지가 깨지거나 DB에 없는
  파일이 남는다.
- 배포 후 관리자로 이미지 하나를 올리고 container를 재시작한 뒤에도 같은 이미지가 조회되는지
  확인한다.

## Networking / CORS

초기 결정은 `NO_PUBLIC_CORS_CHANGE_FOR_INITIAL_DEV_DEPLOYMENT`다. Browser와 Next.js 사이의
요청은 same-origin이고 Next.js server가 A1(Cloudflare → Caddy → backend container)으로
proxy하므로 public GET이 browser CORS에 의존하지 않는다. 향후 browser가 backend를 직접
cross-origin 호출하면 wildcard가 아니라 정확한 frontend origin allowlist를 별도 보안 검토 후
추가한다.

관리자 인증은 access JWT, `Secure`·`HttpOnly`·`SameSite=Strict`·`__Host-` refresh cookie와
정확한 `ADMIN_ALLOWED_ORIGIN` 정책을 유지한다. 초기 development backend의 startup convention은
`ADMIN_ALLOWED_ORIGIN=http://localhost:3001`이며, local `fall-festival-admin`도 port 3001을
사용한다. Public frontend의 localhost:3000과 구분한다.

이 값은 backend startup, 정확한 admin CORS/origin 설정과 login 응답의 access token을 이용한
수동 개발을 위한 것이다. Localhost admin에서 A1 backend를 직접 호출하는 cross-site 구조의
`Secure`·`SameSite=Strict` refresh cookie가 login → refresh → logout 전체에서 동작한다는 뜻은
아니다. Admin frontend proxy/session integration과 remote cookie 검증은 별도 follow-up이다.

관리자 웹은 `admin-festival.likelionerica.com`으로 라우팅될 예정이지만 **아직 배포 전**이다.
실제 배포되면 값을 `https://admin-festival.likelionerica.com`으로 교체한다. 배포 전에 미리
이 값으로 바꾸지 않는다 — 아직 그 origin에서 서비스가 응답하지 않으므로 관리자 로그인이
막힌다. Wildcard, backend URL, 편의상 선택한 public frontend origin과 임의 도메인은 사용하지
않는다. Remote browser와 backend는 HTTPS를 사용하며 HTTP만으로 관리자 session 검증을
완료했다고 판단하지 않는다.

## Health checks

HTTP health check 후보는 `/readyz`다. Development catalog publish와 web backend 최초
시작 또는 controlled restart가 끝난 뒤 `/readyz` 200을 확인하고 health check로 사용한다.
`/healthz`는 process liveness만, `/readyz`는
선택한 festival의 published catalog snapshot readiness를 나타내므로 실제 public API 수신
준비를 더 잘 반영한다. Caddy 자체 healthcheck 설정 여부는 `Caddyfile` 확인 후 채운다.

```text
liveness:  GET /healthz
readiness: GET /readyz
Health check candidate: /readyz
```

`/readyz`는 DB 연결을 지속 탐지하는 probe가 아니라 시작 시 적재된 snapshot 상태를 나타낸다.
운영 관측성이나 DB 장애 감지를 대신하지 않는다.

## Environment / secrets

A1의 `docker run`에 다음 값을 secret/environment variable로 주입한다. Render 전용이었던
`PORT`/`SERVER_PORT=10000` override는 없앤다 — Dockerfile 기본값 `SERVER_PORT=8080`을 그대로
쓰고 Caddy가 그 port로 proxy한다(정확한 host port 매핑은 `Caddyfile` 확인 후 채운다).

```text
SPRING_PROFILES_ACTIVE=db
SPRING_DATASOURCE_URL=<jdbc-postgresql-url>
SPRING_DATASOURCE_USERNAME=<username>
SPRING_DATASOURCE_PASSWORD=<secret>
FESTIVAL_ID=<development-db-festival-id>
ADMIN_JWT_SIGNING_SECRET=<at-least-32-utf8-byte-secret>
ADMIN_ALLOWED_ORIGIN=http://localhost:3001
API_DOCS_ENABLED=true
PUBLIC_LOCALES=ko
RATE_LIMIT_TRUSTED_PROXY_HOPS=<Cloudflare·Caddy·Next.js proxy hop 수>
STAMP_RECEIPT_CODE_SHA256=<6자리 코드의 SHA-256 hash>
```

`API_DOCS_ENABLED=true`는 개발 서버에서만 켠다(`/docs` Swagger UI). 실제 행사 운영 배포에서는
`false`로 끈다. `PUBLIC_LOCALES`는 번역이 모두 들어간 revision을 게시한 뒤에만
`ko,en,zh-Hans`처럼 늘리고, 재시작 로그의 `Published locales`로 실제 공개 언어를 확인한다.
`RATE_LIMIT_TRUSTED_PROXY_HOPS`는 실제 앞단 구성(Cloudflare가 proxy 모드인지, Caddy, Next.js
proxy)을 모두 확인해 정한다. 틀리면 모든 사용자가 한 client로 묶이거나 `X-Forwarded-For`
위조를 믿게 된다 — Cloudflare가 proxy 모드(주황 구름)면 `CF-Connecting-IP`를 신뢰하는 hop이
하나 늘어난다. `STAMP_RECEIPT_CODE_SHA256`은 운영자가 코드를 정한 뒤 hash만 secret으로
넣는다(코드 자체는 넣지 않는다). 그 밖의 선택 설정은 README 환경변수 표를 따른다.
`ADMIN_ALLOWED_ORIGIN`은 admin frontend가 실제로 `admin-festival.likelionerica.com`에
배포된 뒤에만 그 값으로 바꾼다([Networking / CORS](#networking--cors) 참고).

최초 관리자 생성에만 아래 두 값을 함께 사용할 수 있다. Login과 `/api/v2/admin/me`로 생성
확인 후 두 값을 모두 제거하고 재시작한다.

```text
ADMIN_BOOTSTRAP_USERNAME=<bootstrap-username>
ADMIN_BOOTSTRAP_PASSWORD=<bootstrap-password>
```

초기 local admin convention은 명시적인 HTTP localhost origin이므로 startup validation을
충족한다. Remote admin deploy 이후에는 실제 단일 HTTPS browser origin으로 교체한다.

## Flyway

기존 database가 비어 있다고 가정하지 않는다. 먼저 `READ_ONLY_DATABASE_PREFLIGHT`에서
`flyway_schema_history`, schema와 기존 data를 확인하고 V1~V26 호환성을 판정한다. 안전하다고
승인된 경우에만 빈 호환 schema에는 V1~V26을 적용하고, 기존 Flyway history가 있으면 확인된 현재
migration 상태에서 이어간다. 상태가 불명확하거나 공유 schema이면 migrate하지 않고
`STOP_AND_REVIEW`한다. Production 또는 multi-instance 배포에서는 별도 migration gate를 다시
결정한다.

현재 확인 결과는 PostgreSQL 17.11, public schema, V1~V26 success 및 현재 SQL과 checksum
일치다. 그러나 기존 festival·revision과 다른 client session이 있으므로 이 결과만으로
Flyway migrate, catalog import/publish 또는 role 변경을 승인하지 않는다. 롤백이 필요하면
행사 당일 운영 절차서의 백업·복구와 revision rollback 절차를 따르고, migration을 임의로
되돌리거나 `flyway_schema_history`를 수정하지 않는다.

## Known limitations

- A1 사양(CPU/RAM)과 실측 메모리·기동 시간은 아직 이 문서에 기록되지 않았다. `db` profile과
  게시 catalog를 적재한 상태로 실측해 채운다.
- Idle/sleep: A1의 가동 lifecycle과 재시작 정책은 확인되지 않았다. Render Free의 동작을
  A1에 적용해 추정하지 않는다.
- Filesystem: 굿즈 이미지는 named volume([굿즈 이미지 저장소](#굿즈-이미지-저장소))에만
  유지된다. volume 없이 container를 재생성하면 파일이 사라진다.
- Database: preflight에서 PostgreSQL 17.11, public schema, V1~V26 success와 checksum 일치를
  확인했다. A1의 접속 주체와 다른 session의 소유자는 미확정이며, 기존 data가 있어
  `STOP_AND_REVIEW`를 해소하기 전에는 mutation을 실행하지 않는다.
- Frontend: remote provider와 public frontend 실제 origin이 아직 `TBD`다.
- Admin: `admin-festival.likelionerica.com`이 아직 배포 전이다. 배포되면 remote UI의
  refresh-cookie/session proxy integration을 검증한다.
- Reverse proxy: `Caddyfile` 실제 내용과 Cloudflare proxy 모드 여부가 아직 미확인이다.

`/readyz`로 준비 상태를 확인한다. A1 인스턴스 자체의 가동 lifecycle(항상 켜져 있는지, 재시작
정책)은 확인 후 이 절에 채운다.

## Deployment validation checklist

backend는 이미 A1에 떠 있으므로 아래는 최초 provision이 아니라 배포·복구·콘텐츠 변경 전에
실제 로그와 응답으로 재확인할 목록이다.

- [x] `READ_ONLY_DATABASE_PREFLIGHT`에서 PostgreSQL 17.11, public schema와 접근 가능한
  database/table을 확인 — 2026-09-20 실행 결과는 `STOP_AND_REVIEW`, `mutationAuthorized=false`
- [x] 기존 table, `flyway_schema_history`, migration version과 festival table 확인 —
  2026-09-20 preflight에서 V1~V26 success/checksum 일치, festival 1개, revision 1~4 확인
- [ ] 현재 data와 동일 database/schema를 사용하는 다른 서비스·팀 여부 확인
- [ ] A1의 DB network access와 session 소유자 확인
- [ ] V1~V26 migration compatibility와 mutation 승인 기록 — checksum은 확인했으나
  `STOP_AND_REVIEW`/`mutationAuthorized=false`이므로 승인 전 변경 금지
- [ ] role 생성과 권한 부여는 [DB 역할 설계](wiki/engineering/db-role-design.md)의 권한표를
  따른다. 그 문서의 `CREATE ROLE`은 아직 실행하지 않은 초안이다.
- [ ] 역할 provisioning script 적용과 catalog 역할의 계좌·혼잡도·공지·굿즈 접근 거부 확인
- [ ] 안전한 경우에만 Flyway 적용 또는 확인된 현재 migration 상태에서 continue
- [ ] 실제 festival/revision과 `FESTIVAL_ID` 확인
- [ ] 기존 catalog data가 있으면 자동 변경 없이 `STOP_AND_REVIEW`
- [ ] Compatibility 확인 후 manifest `import → validate → publish`와 revision 기록
- [ ] 컨테이너 listen 주소와 host port/Caddy upstream 확인 (`Caddyfile` 및 A1 실행 정보 필요)
- [ ] C/D 인수인계 기록에 A1 접속, 현재 container/image, 실제 실행 옵션·환경변수 주입 방식,
  Caddy 검증·적용·되돌림 방법과 TLS/Cloudflare 상태를 남김
- [ ] D가 Flyway 사전 점검·적용 또는 중단 판단, provider mutation 승인, V1~V26 history/checksum을
  기록함
- [ ] DB·media recovery set이 함께 생성됐고 보존 정책·저장 위치·복원 검증 결과가 작업 기록에 있음
- [ ] Web backend 최초 시작 또는 controlled restart 뒤 `/healthz` 200 확인
- [ ] `/readyz` 200 확인
- [ ] 재기동 뒤 실제로 연결된 굿즈 이미지가 있으면, 공개 굿즈 응답이 돌려준 `images[].masterUrl`을 확인된 공개 URL에서 `GET`해 `200`인지 확인. 실제 연결 이미지가 없으면 이 검증은 미완료이며, 상세 절차는 [행사 당일 운영 절차서](wiki/workflow/festival-day-runbook.md)를 따른다.
- [ ] `/api/v2/lineup`이 valid envelope를 반환하는지 확인
- [ ] `/api/v2/timetable`의 dates, axis와 items가 manifest와 일치하는지 확인
- [ ] `/api/v2/config`의 축제명·날짜·공식 채널 링크 확인
- [ ] `/docs` Swagger UI가 꺼져 있는지 확인(`API_DOCS_ENABLED=false`, 행사 운영 배포 기준)
- [ ] 모든 smoke response의 `meta.revision`이 published revision인지 확인
- [ ] heap 사용량과 OOM/restart 여부 확인
- [ ] Cloudflare/Caddy를 거친 실제 HTTPS 경로로도 위 public GET 확인
- [ ] secret이 source, build log와 client bundle에 노출되지 않았는지 확인
- [ ] Cloudflare가 proxy 모드일 때 `RATE_LIMIT_TRUSTED_PROXY_HOPS`가 맞게 잡혀 실제 client IP로
  rate limit이 걸리는지 확인(모든 요청이 한 IP로 뭉치지 않는지)

## Alternatives considered (historical)

아래는 A1로 바뀌기 전 초기 development 기본안을 정할 때 검토했던 내용이다. 지금은 A1이 이미
결정·배포된 상태이므로 더 이상 비교 대상이 아니고, 기록으로만 남긴다.

- Railway: Docker, PostgreSQL/private networking, health check와 environment 지원이 장점인
  backend hosting fallback이었다.
- New managed PostgreSQL: 팀 제공 database가 remote development 용도로 승인되어 새 DB provider는
  만들지 않기로 했다.
- Vercel: frontend 후보로 검토했으나 확정하지 않았다.

## Production non-goals

A1 배포가 실제로는 `admin-festival.likelionerica.com` 같은 운영에 가까운 도메인을 향하고
있어 이 절의 범위가 좁아지고 있다. Production 수준으로 다뤄야 할 always-on capacity, database
backup, RPO/RTO, monitoring, rollback, custom domain, production secrets, multi-instance
migration strategy는 [행사 당일 운영 절차서](wiki/workflow/festival-day-runbook.md)와 노션
"해야할 것" 체크리스트 1~2번에서 별도로 추적한다. 이 문서만으로 production 준비가 끝났다고
간주하지 않는다.

## Follow-ups

- 기존 DB 사용 범위와 mutation 승인 → `STOP_AND_REVIEW` 해소 전까지 Flyway migrate,
  import, publish를 실행하지 않는다.
- `Caddyfile` 확보 → Backend/Health checks/Environment 절의 port·proxy 관련 빈칸을 채운다.
- `admin-festival.likelionerica.com` 실제 배포 → `ADMIN_ALLOWED_ORIGIN`을 그 값으로 바꾸고
  로그인 → refresh → logout 전체 흐름을 그 origin에서 검증한다.
- A1 사양(CPU/RAM) 확인 → Known limitations의 실측치를 채운다.
