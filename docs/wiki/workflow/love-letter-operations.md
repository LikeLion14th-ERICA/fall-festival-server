# LOVE-001 운영·연동 인계

[제품 명세](../product/love-letter.md) · [기술 설계](../engineering/love-letter.md) · [디자인 요청](../design/love-letter-request.md)

## 공개 전 준비

1. `FESTIVAL_ID`가 운영 축제와 맞는지 확인하고 PR #92의 `V29` 다음 `V30` 러브레터 마이그레이션을 적용한다. 기존 표를 삭제하거나 수정하는 migration은 없다.
2. 무작위 32바이트 키를 Base64로 인코딩해 `LOVE_LETTER_KEY_BASE64`에 서버 비밀 설정으로 공급한다. `LOVE_LETTER_KEY_VERSION`은 `v1`부터 시작한다. 행사 중 암호문이 남아 있는 동안 키·버전을 교체하지 않는다. 키는 DB, 저장소, 프런트 번들, 로그에 넣지 않는다.
3. `LOVE_LETTER_ALLOWED_ORIGIN`을 실제 사용자 앱 HTTPS origin 하나로 설정한다. CORS와 Origin 검증에 동일하게 적용된다. 리버스 프록시는 `Origin`과 전용 쿠키를 그대로 전달하고, 러브레터 응답을 공유 캐시에 저장하지 않는다.
4. 최종 동의문 문안·버전, 개인정보 문의 경로, 행사 시작·종료일, 남·여 초기 참여자의 동의 확보를 확인한다. 운영은 매일 09:00~24:00 KST다. 관리자 `PUT /api/v2/admin/love-letters/configuration`에는 첫날 09:00 KST와 마지막 날 **다음 날 00:00 KST**를 보내며, 저장하면 기능은 비활성화된다.
5. 운영자가 동의받은 실제 쪽지를 `POST /api/v2/admin/love-letters/seeds`로 개별 등록한다. 이름·연락처 파일을 저장소나 요청 로그에 보관하지 않는다. 링크 토큰은 성공 응답에서 한 번만 표시하고 지정 참여자에게 별도 안전한 경로로 전달한다. URL에는 query 대신 fragment를 쓰고 서버에 토큰 원문을 보내는 시점은 연결 POST만으로 제한한다.
6. 양쪽 성별의 미배정 쪽지, 디자인·번역, 관리자 신고 처리, 백업·정리 절차를 확인한 뒤 `PUT /api/v2/admin/love-letters/settings`에 `{"enabled":true}`를 보낸다. 서버도 양쪽 초기 풀과 암호화 키를 검사한다. 공개 프런트 화면과 실제 링크 발송은 이 백엔드 변경 밖의 작업이다.

## 사용자 앱 연동

- `GET /api/v2/love-letter-guide`로 활성화·기간·동의문 버전을 확인한다. `POST /api/v2/love-letter-participants`는 `Origin`을 보내고, 발급된 `__Host-festival-love` 쿠키를 브라우저에 맡긴다. `data.csrfToken`은 이후 쓰기 요청의 `X-Love-Letter-CSRF`에 넣는다. `credentials: include`가 필요하다.
- 입력은 `POST /api/v2/love-letters`로 등록한다. 서버가 같은 트랜잭션에서 상대 쪽지를 배정하고 `revealAt`을 반환한다. 응답을 못 받았으면 같은 `Idempotency-Key`와 본문으로 재전송한다. `LOVE_POOL_EMPTY`면 등록·당일 횟수가 차감되지 않으므로 화면 메모리에 남은 입력으로 다시 시도한다. `GET /api/v2/love-letter-status`의 `WAITING`에는 결과 ID가 없으며, `revealAt`까지 카운트다운한다. 별도 추첨 요청은 없다.
- `revealAt` 후 상태를 다시 조회하면 `SEALED`와 `exchangeId`를 받는다. 그 ID로 `POST /api/v2/love-letter-results/{id}/open`을 호출한다. 조기 개봉의 `LOVE_WAITING`은 남은 시각을 안내한다. `OPENED` 상태 조회에는 연락처만 포함된다. 이름·내용은 봉투 개봉 응답에서만 사용하고 영구 웹 저장소에 넣지 않는다. 화면은 서버의 `nextParticipationAt`을 기준으로 다음 날 CTA를 전환한다.
- 사전 링크는 지정일에 같은 브라우저 세션에서 `POST /api/v2/love-letter-invitations/claim`으로 연결한다. 연결 시 서버가 배정을 시도한다. 상대 쪽지가 없으면 `SEEDED`를 유지하고 서버가 주기적으로 다시 시도한다. 성공 후 새 쿠키·CSRF와 상태 조회로 60초 대기 및 봉투 도착을 표시한다. 지정일이 아니거나 사용·재발급된 링크는 거절된다.
- `RESULT_BLOCKED`면 연락처를 숨기고 재추첨 버튼을 표시하지 않는다. `RESTRICTED`와 `CLOSED`는 새 참여와 연락처 열람을 막는다. 사용자 작성 원문은 번역하지 않으며 화면 안내와 오류 코드는 앱 언어별로 번역한다.
- 00:00~09:00에는 `BEFORE_OPEN`과 `nextParticipationAt`(당일 09:00)을 표시한다. 다음 날 참여가 가능해지는 자정에도 실제 등록·열람은 09:00부터다.

## 운영 중·종료

- 신고는 관리자 목록·상세에서 확인한다. 상세 조회·쪽지 차단·참여 제한/해제·설정 변경은 대상 ID와 조치만 관리자 감사에 남긴다. 차단만으로 이미 복사된 연락처를 회수할 수 없다.
- 사고·남용 시 `enabled:false`로 즉시 신규 참여와 공개 연락처 열람을 중지한다. 롤백 때문에 추가 표를 즉시 삭제하지 않는다. 기존 ciphertext와 키는 정리 기한까지 유지한다.
- 행사 종료 시각부터 공개 추첨·열람을 막는다. `LoveLetterCleanupTarget`은 종료 7일이 지난 참여자를 배치당 최대 설정 수만큼 삭제하며, 참가자 하위 쪽지·배정·신고·링크·재시도 기록도 FK cascade로 제거한다. dry run과 삭제 결과 건수를 감시하고 실패하면 다음 실행에서 재처리한다.
- 운영 DB 백업의 보존 기간도 개인정보 정책에 맞춰 별도로 확정한다. 백업 복구 직후 러브레터 기능을 비활성화하고 종료 시각 및 7일 기한을 확인한 뒤 cleanup delete를 다시 실행한다. 삭제 완료와 백업 만료를 운영 기록에 남긴다.
- 서비스 내 수정·철회 기능은 없지만 개인정보 관련 문의가 접수되면 운영 담당자가 요청자와 해당 자료를 확인해 적용 가능한 조치 및 답변을 기록한다. 사용자에게 임의로 다른 수신자의 원문을 알려주지 않는다.

## 검증 게이트

`npm run generate && npm run check`를 `api-v2`에서 실행하고, 서버는 `mvnw.cmd verify`로 확인한다. PostgreSQL Testcontainers가 없는 환경에서는 통합 검사가 skip되므로 실제 릴리스 전 Docker가 있는 CI에서 재실행해야 한다. 공개 프런트와 실기기에서 쿠키·CORS·CSRF, 좁은 화면, 번역, 키보드, 스크린리더, 자정, 통신 재시도를 확인한다.
