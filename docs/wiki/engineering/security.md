# 익명 접근·보안·개인정보

[위키 홈](../README.md) · 읽는 때: 인증·관리자·공개 쓰기·저장·로그·업로드 변경

- 라인업, 타임테이블, 부스, 지도, 공지, 챗봇은 인증 없이 접근 가능해야 한다.
- 언어와 필터는 비민감한 로컬 환경 설정으로 저장할 수 있다.
- 계정 종속 즐겨찾기나 `내 일정`을 임의로 만들지 않는다. 필요하다면 익명 로컬 저장과
  계정 기능 중 어느 것인지 먼저 결정한다.
- 관리자 도구는 공개 사용자 앱과 별도 보안 경계를 두고 서버에서 역할별 권한을
  검사한다. 관리자 작업은 감사 이력을 남기며 계정·권한 회수 절차를 갖춘다.
- 익명 챗봇과 공개 쓰기 요청에는 속도 제한, 남용 방지, 안전한 실패 응답을 적용한다.
- 개인정보와 비밀 값은 최소 수집·최소 권한·정해진 보관 기간 원칙을 적용하고 로그나
  클라이언트 번들에 노출하지 않는다.
- 입력 검증, 출력 이스케이프, 보안 헤더, CORS·CSRF, 파일 업로드, 의존성 취약점 대응을
  운영 환경의 위협 모델과 함께 관리한다.

## 구현 보안 규칙

- 모든 일반 사용자 조회 화면과 챗봇 진입은 인증 없이 접근 가능해야 한다.
- 공개 경로에 로그인 리다이렉트, 회원가입, 계정 종속 즐겨찾기를 임의로 추가하지 않는다.
- 언어·필터·마지막 선택처럼 비민감한 환경 설정은 로컬 상태에 저장할 수 있지만,
  이를 사용자 계정이나 영구 개인 프로필로 확장하지 않는다.
- 관리자 기능은 별도 진입점과 서버 측 권한 검사를 갖춘 보안 경계에 둔다. access
  token은 짧은 수명의 JWT, refresh token은 회전되는 Secure·HttpOnly cookie를
  사용하고 역할, 세션 만료, 계정 복구와 긴급 권한 회수는 API v2 계약과 운영 runbook을
  따른다.
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
  상세 원인 없이 거절한다. 이 인증은 로그인 없는 참여의 엄격한 중복 차단이나 사용자별
  수령 이력을 만들지 않는다.

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
