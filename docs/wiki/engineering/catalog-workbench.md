# 로컬 카탈로그 워크벤치

[위키 홈](../README.md) · 읽는 때: 릴리스 운영자가 원격 DB의 catalog를 export·검증·비교·가져오기·게시할 때, 워크벤치 보안 경계를 바꿀 때

워크벤치는 [게시](publishing.md)의 CLI 흐름(`export → import → publish`)을 브라우저 화면으로
감싼 로컬 도구다. 같은 `CatalogExportService`·`CatalogManifestReader`·`CatalogRevisionService`를
쓰므로 검증 규칙, `BASE_REVISION_CONFLICT`, 감사 기록은 CLI와 같다. HTTP 쓰기 API를 공개
서비스에 추가하지 않는다.

## 보안 경계

- **로컬 전용:** 서버는 환경변수와 관계없이 `127.0.0.1`에만 bind한다(기본 포트 8790).
- **세션 token:** 시작할 때마다 256비트 임의 token을 만들고 터미널에
  `http://127.0.0.1:<port>/#token=...` 주소로 한 번 출력한다. token은 URL fragment로만
  전달되어 서버 요청·referrer에 실리지 않고, 화면은 이를 탭의 `sessionStorage`로 옮긴 뒤
  주소창에서 지운다. 모든 `/api/*` 요청은 `X-Workbench-Token`이 일치해야 한다(401).
- **Host·Origin:** `Host`는 정확히 `127.0.0.1:<port>`여야 한다(DNS rebinding 차단, 403).
  `Origin`이 있으면 같은 origin이어야 하고, 쓰기 요청은 `Origin`이 반드시 있어야 한다(403).
- **응답 헤더:** 엄격한 CSP(`default-src 'none'`, 외부 script 없음, `frame-ancestors 'none'`),
  `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`.
- **DB 비밀값:** 접속 정보는 워크벤치 process 안에만 있다. 어떤 응답에도 URL·사용자·
  비밀번호를 넣지 않고 DB 오류는 종류만 log에 남긴다.
- **SSH tunnel:** DB URL은 loopback(`127.0.0.1`·`localhost`·`[::1]`) 단일 host여야 한다.
  원격 DB에는 승인된 SSH tunnel로만 연결한다. 다른 host나 multi-host URL이면 시작하지 않는다.
- **역할 분리:** export role은 read-only pool로, publish role은 별도 context·pool로 연결한다.
  두 사용자 이름이 같으면 시작하지 않는다. publish 정보가 없으면 **export 전용**으로 뜨고
  가져오기·게시는 `403 PUBLISH_ROLE_NOT_CONFIGURED`다.
- **축제 경계:** export·diff·publish에 준 revision UUID는 configured `FESTIVAL_ID` 소속이어야 한다.
  다른 축제의 revision은 catalog를 읽거나 바꾸기 전에 `422 REVISION_FESTIVAL_MISMATCH`로 거절한다.
- **catalog 밖 테이블:** [role provisioning script](../../../tools/database/provision-operational-account-roles.sql)가
  catalog export·publish role에서 계좌·티켓 계좌 열·혼잡도·공지 테이블 권한을 회수한다.
  provider가 schema 전체를 먼저 grant했어도 적용된다. catalog 밖 테이블을 추가하는
  migration 뒤에는 script를 다시 실행하고 새 테이블을 script에 추가한다(굿즈 포함).

## 실행

1. [DB 사전 점검](database-preflight.md)이 `STOP_AND_REVIEW`가 아닌지 확인한다.
2. 승인된 SSH tunnel을 연다. 예: 원격 5432를 로컬 `127.0.0.1:15432`로 전달.
3. 비밀값은 셸 환경변수로만 넣는다. 저장소·문서·명령 기록에 남기지 않는다.

| 환경변수 | 필수 | 설명 |
|---|---|---|
| `FESTIVAL_ID` | 예 | 작업할 축제 UUID |
| `CATALOG_WORKBENCH_EXPORT_URL` / `_USERNAME` / `_PASSWORD` | 예 | catalog export role. URL은 tunnel의 loopback 주소 |
| `CATALOG_WORKBENCH_PUBLISH_URL` / `_USERNAME` / `_PASSWORD` | 아니오 | catalog publish role. 없으면 export 전용 |
| `CATALOG_WORKBENCH_BACKEND_URL` | 아니오 | 게시 후 확인할 공개 백엔드. `https://` 또는 loopback `http://` |
| `CATALOG_WORKBENCH_PORT` | 아니오 | 기본 8790 |

```bash
java '-Dloader.main=dev.espero.festival.workbench.CatalogWorkbenchApplication' -cp target/fall-festival-server-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

4. 터미널에 출력된 `Catalog workbench: http://127.0.0.1:8790/#token=...` 주소를 연다.
   주소는 다른 사람과 공유하지 않는다. 종료하면 token은 무효가 된다.

## 작업 흐름

1. **상태:** 축제, 현재 게시본, 최근 50개 revision, 권한 모드를 확인하고 운영자 이름을
   입력한다. 이름은 감사 기록의 actor로 남는다.
2. **내보내기:** revision을 manifest로 불러온다. `LEGACY_TICKET_SCHEDULE_UNCONFIGURED` 같은
   finding이 있으면 승인된 값을 넣기 전까지 가져오기가 거절된다. `JSON 저장`으로 파일을 남긴다.
3. **편집:** 로컬 JSON 파일을 불러오거나 화면에서 고친다. 지도 자산은 **이미 배포한 URI만**
   고친다. 이미지 업로드는 범위 밖이다.
4. **검증:** DB에 쓰지 않고 manifest 검증 결과와 기준 revision 확인을 보여 준다. 기준 revision은
   manifest 값, 현재 게시본, `없음(첫 게시)` 중에서 명시한다.
5. **비교:** 현재 게시본(없으면 빈 catalog)과 section별 추가·삭제·변경 row를 보여 준다. row는
   순서가 아니라 id 또는 key field로 비교한다.
6. **가져오기:** publish role로 새 draft를 만든다. 준비 중 다른 게시가 끼어들었으면
   `BASE_REVISION_CONFLICT`로 거절된다.
7. **게시:** 목록의 draft에서 게시한다. 게시는 draft를 재검증하고 기준 revision을 다시 확인한다.
8. **게시 후 확인:** 릴리스 운영자가 정해진 시간에 백엔드를 재시작한다. 공개 서비스는
   재시작 뒤에 새 revision을 읽는다. `백엔드 확인`은 `/readyz` 상태와 공개 API
   `meta.revision`이 게시 revision 번호와 같은지 읽기 전용으로 확인하며 재시작하지 않는다.

rollback은 워크벤치 범위가 아니다. [게시](publishing.md)의 CLI `rollback --expected-current`를 쓴다.

## 검증

- `WorkbenchUnitTest`: loopback DB URL, Host·Origin·token guard와 보안 헤더, 행 identity 기반 diff,
  백엔드 URL 제한.
- `CatalogWorkbenchIntegrationTest`: 실제 워크벤치를 PostgreSQL과 분리된 export·publish role로
  띄워 검증·비교·가져오기·게시·재export 무차이·끼어든 게시 거절·게시 후 확인, token·Origin
  없는 요청 거절, export 전용 모드, 같은 role·원격 DB URL로 시작 거부와 다른 축제 revision의
  export·diff·publish 거절을 확인한다.
- `OperationalAccountSettingsIntegrationTest`: provider가 schema 전체를 grant한 뒤에도 catalog
  role이 계좌·혼잡도·공지 테이블을 읽지 못한다.
