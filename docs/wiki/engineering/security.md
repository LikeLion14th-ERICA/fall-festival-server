# 익명 접근·보안·개인정보

[위키 홈](../README.md) · 읽는 때: 인증·관리자·공개 쓰기·저장·로그·업로드 변경

- 라인업, 타임테이블, 부스, 지도와 공지는 인증 없이 접근 가능해야 한다.
- 언어와 필터는 비민감한 로컬 환경 설정으로 저장할 수 있다.
- 계정 종속 즐겨찾기나 `내 일정`을 임의로 만들지 않는다. 필요하다면 익명 로컬 저장과
  계정 기능 중 어느 것인지 먼저 결정한다.
- 관리자 도구는 공개 사용자 앱과 별도 보안 경계를 두고 서버에서 `ADMIN` 권한을
  검사한다. 세부 역할은 Product 범위가 확정된 뒤 별도 계약으로 추가한다. 관리자 작업은
  감사 이력을 남기며 계정·권한 회수 절차를 갖춘다.
- 공개 쓰기 요청에는 속도 제한, 남용 방지, 안전한 실패 응답을 적용한다.
- 개인정보와 비밀 값은 최소 수집·최소 권한·정해진 보관 기간 원칙을 적용하고 로그나
  클라이언트 번들에 노출하지 않는다.
- 입력 검증, 출력 이스케이프, 보안 헤더, CORS·CSRF, 파일 업로드, 의존성 취약점 대응을
  운영 환경의 위협 모델과 함께 관리한다.

## 구현 보안 규칙

- 모든 일반 사용자 조회 화면은 인증 없이 접근 가능해야 한다.
- 공개 경로에 로그인 리다이렉트, 회원가입, 계정 종속 즐겨찾기를 임의로 추가하지 않는다.
- 언어·필터·마지막 선택처럼 비민감한 환경 설정은 로컬 상태에 저장할 수 있지만,
  이를 사용자 계정이나 영구 개인 프로필로 확장하지 않는다.
- 관리자 기능은 별도 진입점과 서버 측 `ADMIN` 권한 검사를 갖춘 보안 경계에 둔다. access
  token은 짧은 수명의 JWT, refresh token은 회전되는 Secure·HttpOnly cookie를
  사용한다. logout은 현재 refresh session만 revoke하며 이미 발급된 access JWT는 최대 15분까지
  유효하다. JWT 서명 secret 교체는 access JWT만 무효화하므로 모든 자격증명 차단은 별도 auth
  lifecycle 절차로 다룬다.
- 최초 `ADMIN` bootstrap password는 15개 이상의 Unicode code point이면서 BCrypt 입력 한계인
  UTF-8 72바이트 이하여야 한다. 비밀번호 설정·변경 API는 현재 Product 범위에 없다.
- 모든 입력은 서버에서 검증하고 출력은 사용 맥락에 맞게 이스케이프한다. 공개 쓰기
  요청에는 속도 제한과 남용 방지를 적용하며 CORS, CSRF, 보안 헤더, 파일 업로드 정책을
  배포 환경에 맞게 명시한다.
- 비밀 값은 저장소, 클라이언트 번들, 로그에 두지 않는다. 의존성·컨테이너·배포 설정의
  취약점 검사를 CI와 출시 절차에 포함한다.
- 이름, 전화번호, 위치, 채팅 로그 등 개인정보를 수집하기 전에 목적, 최소 항목,
  보관 기간, 접근 권한, 삭제, 미성년자 처리와 동의를 확정한다.
- 로그와 분석 이벤트에는 인증 정보, 원문 개인정보, 불필요한 채팅 본문을 남기지 않는다.
- 스탬프 투어가 승인되더라도 기존 공개 기능을 로그인 뒤로 이동시키지 않는다.
- 스탬프 상품 수령 인증 코드(6자리 숫자)는 현장 담당자가 사용자 기기에 직접 입력하고 서버에서만
  검증한다. 서버는 코드의 SHA-256 hash만 `STAMP_RECEIPT_CODE_SHA256`으로 받고 상수 시간으로
  비교한다. 6자리는 100만 가지뿐이라 hash가 유출되면 곧바로 역산되므로 이 값도 비밀값으로
  다루고, 요청 수 제한(`stamp-receipt`)과 **코드 교체**로 대입을 막는다. 행사일마다 새 코드로
  바꾸고, 바꾸는 동안에는 이전·새 hash를 쉼표로 함께 넣었다가 이전 hash를 뺀다. 실제 값은 서버 비밀 설정으로 관리하며 `StampGuide` 응답, 클라이언트 저장소,
  분석·접근 로그와 오류 응답에 넣지 않는다. 공개 검증 요청은 속도 제한하고 틀린 코드는
  상세 원인 없이 거절한다. 코드가 맞아도 그 브라우저의 오늘 스탬프 4개와 미수령을 서버가
  확인한 뒤에만 수령을 기록한다.
- 부스 스탬프(V27): 부스 QR 토큰은 128비트 난수이고 catalog에는 SHA-256만 둔다. 원문 토큰이 든
  QR 링크 목록(`ops/stamp/out/`)은 비밀이며 커밋·공유하지 않는다. 참여자는 `__Host-festival-stamp`
  익명 쿠키(HttpOnly, Secure, SameSite=Lax, 30일)로 구분하고 서버는 그 SHA-256만 저장한다.
  개인정보를 받지 않으므로 브라우저 데이터를 지우면 새 참여자가 된다. 적립 요청은
  `public-read` 제한을 받는다.

현재 구현·계약에 맞춘 release 증거와 외부 운영 gate는
[백엔드 보안 출시 체크리스트](security-release-checklist.md)에서 관리한다.

## 요청 수 제한

`/api/v2`는 인증보다 먼저 클라이언트별 token bucket으로 제한한다. 단일 인스턴스 메모리에서 동작하며
초과하면 `429 RATE_LIMITED`(retryable)와 `Retry-After`를 반환한다. `/healthz`, `/readyz`,
`/docs`는 제한하지 않는다.

| 정책 | 대상 | 기본값 |
|---|---|---|
| public-read | 그 밖의 `/api/v2/**` | 120회 즉시, 초당 4회 회복 |
| admin | `/api/v2/admin/**` | 60회 즉시, 초당 1회 회복 |
| admin-login | `POST /admin/sessions`, `/admin/sessions/refresh` | 5회 즉시, 12초마다 1회 회복 |
| stamp-receipt | `POST /stamp-receipt-verifications` | 5회 즉시, 12초마다 1회 회복 |

클라이언트는 `RATE_LIMIT_TRUSTED_PROXY_HOPS`로 정한다. 브라우저 요청은 Next.js proxy와 호스팅
load balancer를 거치므로 소켓 주소는 proxy다. 0으로 두면 모든 사용자가 한 bucket을 쓰게 되므로
배포 환경에서는 실제 hop 수(보통 2)를 설정하고 `X-Forwarded-For` 구성을 실측한다. 백엔드 주소로
직접 들어오는 요청은 `X-Forwarded-For`를 위조할 수 있으므로 공개 URL을 proxy만 부르도록 제한하는
것이 좋다. 요청 수 제한 로그에는 IP를 남기지 않는다. IPv6 literal은 `/64` prefix를 하나의
client key로 쓰며, client bucket table이 가득 차도 active bucket을 지우지 않고 policy별
공유 overflow bucket을 쓴다. idle bucket은 주기적으로만 회수해 table sweep 자체가 요청 폭주를
만들지 않게 한다. 부하 시험(`tools/load-test`)은 서버 용량을 재기 위해 제한을 끈다.

## 관리자 감사 이력

- `CatalogRevisionAudit`는 개발자 CLI의 카탈로그 `IMPORT / VALIDATE / PUBLISH / ROLLBACK`
  lifecycle을 기록한다. `AdminAuditEvent`는 인증된 관리자의 혼잡도·공지·굿즈 등
  운영 콘텐츠 HTTP write를 기록하며, 두 모델을 합치지 않는다.
- 관리자 감사 이력은 append-only이고 해당 운영 데이터 변경과 같은 DB transaction에서
  기록한다. 감사 저장이 실패하면 운영 데이터 변경도 rollback하고, 운영 변경이
  rollback되면 감사 이력도 남기지 않는다.
- `AdminAuditEvent`는 1년간 보관한다. cleanup framework의 audit target은 기본 dry-run·
  scheduler 비활성 상태이며, 전용 cleanup datasource와 역할을 별도로 지정한 경우에만
  파괴적 scheduler를 활성화한다. 운영 설정과 결과 형식은 [데이터 정리](cleanup.md)를 따른다.
- 감사 event에는 비밀번호, access/refresh token, cookie, Authorization header, request/response
  body, IP, User-Agent, 원문 개인정보, 예외·SQL message와 임의 metadata를 저장하지 않는다.
- login·refresh·logout·인증 실패 감사는 현재 `AdminAuditEvent` 범위가 아니며 후속
  auth lifecycle 작업에서 다룬다.

## 계좌 운영 설정 이력

- 계좌 현재값과 append-only `OperationalAccountSettingHistory`는 `AdminAuditEvent`와
  `CatalogRevisionAudit`와 별도 보안 경계다. account operator CLI의 변경은 DB trigger가
  current 값 변경과 같은 transaction에서 이력을 만들고, trigger가 실제 login role인
  `session_user`를 기록한다.
- 은행명·계좌번호·예금주·송금 링크 원문은 계좌 table과 제한된 history에만 둔다. 관리자
  감사·catalog audit·application log·metric·CLI output에는 purpose, version, 시각과 끝 네
  자리 외의 원문을 넣지 않는다.
- runtime은 current 설정을 SELECT만 할 수 있고, account operator·cleanup·catalog
  export/publish role을 분리한다. history UPDATE/DELETE는 cleanup role 외에 grant하지
  않으며, DB owner/superuser의 break-glass 접근은 별도로 기록한다. 자세한 role provisioning과
  one-person CLI 위험은 [계좌 운영 설정](operational-account-settings.md)을 따른다.
