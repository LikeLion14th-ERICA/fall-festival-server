# 원격 개발 환경 배포 결정

## Status

- 상태: 개발 환경 DB 결정 갱신, 아직 backend를 provision하지 않음
- 기준: `main` `9357409` (2026-09-19, Flyway V1~V23)
- Provider 정책 확인일: 2026-09-17 (provision 직전 재확인)
- 범위: remote development only; production hosting 결정이 아님
- 검증 게이트: `READ_ONLY_DATABASE_PREFLIGHT`, `DEPLOYMENT_VALIDATION_REQUIRED`

## Context

원격 프런트엔드와 백엔드는 아직 배포되지 않았다. 프런트엔드 저장소
`LikeLion14th-ERICA/fall-festival-web`은 Next.js 16 App Router에서
`NEXT_PUBLIC_API_BASE_URL=/api/v2`와 `API_PROXY_TARGET=<backend-origin>`을 사용해
API 요청을 rewrite할 수 있다. 백엔드는 루트 `Dockerfile`, PostgreSQL과 `db` profile을
지원하지만 provider resource, remote URL과 production runbook은 아직 없다.

## Decision

초기 원격 개발 환경은 다음을 기본안으로 사용한다.

- Frontend provider: `TBD`. 기존 Next.js `/api/v2` rewrite 구조는 유지한다.
- Backend: Render Free Web Service에 루트 `Dockerfile`로 배포한다.
- Database: 팀에서 remote development 용도로 사용을 승인한 PostgreSQL database를 사용한다.
- Development catalog: repository에서 추적하는 synthetic development manifest를 사용한다.
- Browser/API: same-origin proxy를 우선하며 public API CORS는 추가하지 않는다.
- Health check candidate: `/readyz`.

Render 또는 GitHub resource와 secret은 이 결정만으로 생성하지 않는다. 팀 제공 database에도
이 문서 변경만으로 접속하거나 변경을 적용하지 않는다.

## Topology

```text
Browser
  |
  | HTTPS, /api/v2/**
  v
Next.js frontend (provider TBD)
  |
  | rewrite to API_PROXY_TARGET
  v
Render Free Web Service (public HTTPS origin, Dockerfile)
  |
  | PostgreSQL connection
  v
Team-provided PostgreSQL development database
```

브라우저에는 backend URL을 API base로 노출하지 않는다. Render URL은 provision 후
`https://<render-service>.onrender.com` 형태의 proxy target으로만 설정한다. 실제 서비스명과
URL은 이 문서에서 정하지 않는다.

## Frontend

Frontend provider는 remote deployment가 없고 private GitHub repository의 hosting 조건이
정해지지 않아 `TBD`로 둔다. 선택할 provider는 다음을 모두 지원해야 한다.

- Next.js server/runtime 또는 `/api/v2/**` rewrite
- HTTPS
- environment variable 주입
- 외부 Render backend로의 server-side proxy

Browser는 상대 경로 `/api/v2/**`만 호출하고 Next.js는 `API_PROXY_TARGET`으로 전달한다.
Remote host가 이 rewrite를 지원하지 않으면 배포 전에 topology와 public CORS 결정을 다시
검토한다.

## Backend

Render Free Web Service를 zero-cost 개발 기본안으로 선택한다. GitHub repository 연결,
Docker runtime, managed HTTPS, runtime environment/secrets와 HTTP health check를 사용할 수
있기 때문이다. 서비스는 public HTTPS endpoint를 갖지만 browser가 직접 호출하지 않는다.

루트 image는 이미 `SERVER_ADDRESS=0.0.0.0`, `SERVER_PORT=8080`을 기본값으로 둔다. Render는
web service가 `0.0.0.0`에 bind하고 `PORT`를 사용하기를 권장하므로 초기 development service는
다음 explicit override를 사용하고 실제 listening log로 확인한다.

```text
SERVER_ADDRESS=0.0.0.0
PORT=10000
SERVER_PORT=10000
```

Render가 제공하는 `PORT`를 애플리케이션이 직접 읽지는 않는다. Render dashboard에서 `PORT`와
`SERVER_PORT`에 같은 literal 숫자 `10000`을 설정한다. `SERVER_PORT=$PORT` 같은 shell expansion과
Dockerfile의 8080 자동 탐지에는 의존하지 않는다. Dockerfile 자체는 바꾸지 않는다.

## Database

The team-provided PostgreSQL database is approved for remote development use. Render backend는
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
migration history와 V1~V23 호환성을 확인한 뒤에만 mutation 단계로 진행한다. 공유 schema라면
별도 database 또는 schema가 필요한지 다시 결정한다.

팀에서 제공한 connection 정보는 다음 Spring 변수로 분리해 Render secret/environment settings에만
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
빈 호환 schema에 V1~V23을 적용한 경우에만 Flyway가 만든 development festival을 확인한다. V6 seed
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
  -> 안전할 때만 Flyway V1-V23 적용 또는 현재 migration 상태에서 continue
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
- 계좌·role 관련 migration 뒤에는 역할 provisioning script를 다시 실행한다.

### 역할 provisioning

DB 제공자에게 migration, runtime, cleanup, account operator, catalog export, catalog publish,
preflight 역할을 요청한다. migration 뒤 migration 역할로
[`tools/database/provision-operational-account-roles.sql`](../tools/database/provision-operational-account-roles.sql)을
`psql -v` 변수로 실행한다. 이 script는 계좌 테이블 권한을 나누고, provider가 schema 전체를
grant했더라도 catalog 역할에서 계좌·혼잡도·공지·굿즈 테이블 권한을 회수한다. catalog 역할에는
catalog 테이블(V22 `festival_links` 포함) 권한을 provider가 따로 준다. 단일 계정만 제공되면 역할
분리를 강제할 수 없음을 기록하고 운영 배포는 진행하지 않는다.

## Networking / CORS

초기 결정은 `NO_PUBLIC_CORS_CHANGE_FOR_INITIAL_DEV_DEPLOYMENT`다. Browser와 Next.js 사이의
요청은 same-origin이고 Next.js server가 Render로 proxy하므로 public GET이 browser CORS에
의존하지 않는다. 향후 browser가 backend를 직접 cross-origin 호출하면 wildcard가 아니라
정확한 frontend origin allowlist를 별도 보안 검토 후 추가한다.

관리자 인증은 access JWT, `Secure`·`HttpOnly`·`SameSite=Strict`·`__Host-` refresh cookie와
정확한 `ADMIN_ALLOWED_ORIGIN` 정책을 유지한다. 초기 development backend의 startup convention은
`ADMIN_ALLOWED_ORIGIN=http://localhost:3001`이며, local `fall-festival-admin`도 port 3001을
사용한다. Public frontend의 localhost:3000과 구분한다.

이 값은 backend startup, 정확한 admin CORS/origin 설정과 login 응답의 access token을 이용한
수동 개발을 위한 것이다. Localhost admin에서 Render backend를 직접 호출하는 cross-site 구조의
`Secure`·`SameSite=Strict` refresh cookie가 login → refresh → logout 전체에서 동작한다는 뜻은
아니다. Admin frontend proxy/session integration과 remote cookie 검증은 별도 follow-up이다.

Admin frontend가 HTTPS로 remote deploy되면 값을 `https://<actual-admin-development-origin>`으로
교체한다. Wildcard, Render backend URL, 편의상 선택한 public frontend origin과 임의 도메인은
사용하지 않는다. Remote browser와 backend는 HTTPS를 사용하며 HTTP만으로 관리자 session 검증을
완료했다고 판단하지 않는다.

## Health checks

Render HTTP health check 후보는 `/readyz`다. Development catalog publish와 web backend 최초
시작 또는 controlled restart가 끝난 뒤 `/readyz` 200을 확인하고 health check로 사용한다.
`/healthz`는 process liveness만, `/readyz`는
선택한 festival의 published catalog snapshot readiness를 나타내므로 실제 public API 수신
준비를 더 잘 반영한다.

```text
liveness:  GET /healthz
readiness: GET /readyz
Render health check candidate: /readyz
```

`/readyz`는 DB 연결을 지속 탐지하는 probe가 아니라 시작 시 적재된 snapshot 상태를 나타낸다.
운영 관측성이나 DB 장애 감지를 대신하지 않는다.

## Environment / secrets

Render backend에 배포 단계에서 다음 값을 secret/environment variable로 주입한다.

```text
SPRING_PROFILES_ACTIVE=db
SPRING_DATASOURCE_URL=<jdbc-postgresql-url>
SPRING_DATASOURCE_USERNAME=<username>
SPRING_DATASOURCE_PASSWORD=<secret>
FESTIVAL_ID=<development-db-festival-id>
ADMIN_JWT_SIGNING_SECRET=<at-least-32-utf8-byte-secret>
ADMIN_ALLOWED_ORIGIN=http://localhost:3001
SERVER_ADDRESS=0.0.0.0
PORT=10000
SERVER_PORT=10000
API_DOCS_ENABLED=true
```

`API_DOCS_ENABLED=true`는 개발 서버에서만 켠다(`/docs` Swagger UI). 그 밖의 선택 설정은 README
환경변수 표를 따른다.

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
`flyway_schema_history`, schema와 기존 data를 확인하고 V1~V23 호환성을 판정한다. 안전하다고
승인된 경우에만 빈 호환 schema에는 V1~V23을 적용하고, 기존 Flyway history가 있으면 확인된 현재
migration 상태에서 이어간다. 상태가 불명확하거나 공유 schema이면 migrate하지 않고
`STOP_AND_REVIEW`한다. Production 또는 multi-instance 배포에서는 별도 migration gate를 다시
결정한다.

## Known limitations

- Render Free: 0.1 CPU, 512 MB RAM. 2026-09-19 로컬 Docker에서 같은 한도(`-m 512m --cpus 0.1`)로
  기동하면 DB 없는 기본 profile 기준 약 41초, 메모리 123 MiB였다. `db` profile과 게시 catalog를
  적재한 상태의 메모리·기동 시간은 배포 때 실측한다.
- Idle: inbound traffic이 15분 없으면 sleep하므로 첫 요청과 wake-up이 느릴 수 있다.
- Filesystem: restart, redeploy 또는 spin-down 뒤 유지되지 않으므로 영속 상태를 두지 않는다.
- Database: Render에서의 network access, schema 상태, migration history와 공유 여부가 아직 미검증이다.
- Frontend: remote provider와 실제 origin이 아직 `TBD`다.
- Admin: remote UI의 refresh-cookie/session proxy integration이 미완료다.
- Production: Free service는 production에 사용하지 않는다.

개발 중 첫 요청이 느리면 backend wake-up 뒤 `/readyz`를 확인한다. Sleep을 피하려는 ping 또는
keep-alive hack은 추가하지 않는다. Render 무료 plan 수치와 lifecycle은 2026-09-17 공식 문서 기준이며
실제 provision 직전에 다시 확인한다.

## Deployment validation checklist

최초 provision은 별도 작업에서 수행하고 다음을 실제 로그와 응답으로 검증한다.

- [ ] `READ_ONLY_DATABASE_PREFLIGHT`에서 PostgreSQL version, database, user와 accessible schema 확인
- [ ] 기존 table, `flyway_schema_history`, migration version과 festival table 확인
- [ ] 현재 data와 동일 database/schema를 사용하는 다른 서비스·팀 여부 확인
- [ ] Render에서 team-provided PostgreSQL로 network access 가능한지 확인
- [ ] V1~V23 migration compatibility 검토와 mutation 승인 기록
- [ ] 역할 provisioning script 적용과 catalog 역할의 계좌·혼잡도·공지·굿즈 접근 거부 확인
- [ ] 안전한 경우에만 Flyway 적용 또는 확인된 현재 migration 상태에서 continue
- [ ] 실제 development festival/revision과 `FESTIVAL_ID` 확인
- [ ] 기존 catalog data가 있으면 자동 변경 없이 `STOP_AND_REVIEW`
- [ ] Compatibility 확인 후 synthetic manifest `import → validate → publish`와 revision 기록
- [ ] Docker container build와 startup 성공
- [ ] `0.0.0.0:10000` bind 확인
- [ ] Web backend 최초 시작 또는 controlled restart 뒤 `/healthz` 200 확인
- [ ] `/readyz` 200 확인
- [ ] `/api/v2/lineup`이 valid envelope를 반환하는지 확인
- [ ] `/api/v2/timetable`의 dates, axis와 items가 synthetic manifest와 일치하는지 확인
- [ ] Synthetic artist 하나의 `/api/v2/artists/{artistId}`가 200인지 확인
- [ ] Synthetic performance 하나의 `/api/v2/performances/{performanceId}`가 200인지 확인
- [ ] `/api/v2/prohibited-items`가 계약에 맞는 empty 또는 populated 응답인지 확인
- [ ] `/api/v2/config`의 축제명·날짜·공식 채널 링크 확인
- [ ] `/docs` Swagger UI에서 `[서버 미구현]` 표시가 기대와 같은지 확인
- [ ] 모든 smoke response의 `meta.revision`이 published development revision인지 확인
- [ ] heap 사용량과 OOM/restart 여부 확인
- [ ] 위 public GET을 Next.js same-origin proxy 경로로도 확인
- [ ] secret이 source, build log와 client bundle에 노출되지 않았는지 확인

512 MB에서 안정적으로 기동하지 않으면 이 PR에서 provider 결정을 바꾸지 않고 paid Render
compute, Railway 또는 다른 Docker host를 후속 대안으로 평가한다.

## Alternatives considered

- Railway: Docker, PostgreSQL/private networking, health check와 environment 지원이 장점인
  backend hosting fallback이다. 현재 Render Free backend보다 단순하지 않아 기본안으로 선택하지
  않았다. 변동하는 가격 숫자는 이 문서에 고정하지 않는다.
- New managed PostgreSQL: 팀 제공 database가 remote development 용도로 승인되어 새 DB provider는
  만들지 않는다. Preflight에서 안전하게 사용할 수 없다고 판단되면 별도 database/schema 또는
  managed PostgreSQL 필요성을 다시 결정한다.
- Vercel: frontend 후보일 수 있으나 private repository, Next.js rewrite/runtime, environment와
  external proxy 요구를 실제 account/plan 조건에서 확인하기 전에는 확정하지 않는다.

## Production non-goals

This decision is for remote development only. Production에서는 always-on compute, capacity,
database backup, RPO/RTO, monitoring, rollback, custom domain, production secrets와 multi-instance
migration strategy를 다시 결정한다.

## Follow-ups

다음 작업은 provider resource를 만들기 전에 account/plan과 frontend rewrite 지원을 확인한다.
synthetic development manifest는 이미 저장소에 있다. 팀 제공 PostgreSQL에는 먼저 read-only preflight만 수행하고 compatibility와
mutation 승인을 확인한다. 그 뒤에만 필요한 Flyway migration과 non-web catalog CLI의 import,
validate, publish를 수행하고 Render web backend를 시작해 validation checklist의 실측 결과를
기록한다.
Admin remote cookie/session integration과 production deployment는 별도 범위다.

Provider 제약 참고: [Render free instances](https://render.com/docs/free),
[Render compute plans](https://render.com/docs/compute-plans),
[Render web services](https://render.com/docs/web-services),
[Render health checks](https://render.com/docs/health-checks).
