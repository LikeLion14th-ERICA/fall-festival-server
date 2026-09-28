# 캐시·배포·복구

[위키 홈](../README.md) · 읽는 때: 캐시·배포·장애·복원·게시 전파 변경

릴리스 전 staging gate와 복구 증거의 필수 항목·RPO/RTO·on-call·alert 차단 기준은 [릴리스 증거 runbook](../workflow/release-evidence-runbook.md)을
따른다. 실제 host·credential·dump·media archive·Caddy 설정은 보호된 운영 기록에 두고 저장소에는 reference ID와 검증 결과만 남긴다.

- 캐시 키에는 축제 회차, revision, locale, map version과 필터 등 응답을 바꾸는 값을
  포함한다.
- 초기 배포는 단일 인스턴스이며 CDN을 두지 않는다. 해시 또는 version이 고정된 지도·이미지
  자산은 앱 서버가 장기 cache header와 함께 제공한다.
- 단일 인스턴스의 게시 반영은 `publish → 제어된 재시작 → /readyz와 meta.revision 확인`으로
  마무리한다. 다중 인스턴스 또는 CDN을 도입하기 전에는 Outbox, CAS, CDN purge를 만들지 않는다.
- API 또는 네트워크 장애 시 직전의 검증된 데이터를 제한적으로 제공할 수 있으나 마지막
  갱신 시각과 stale 상태를 분명히 표시한다. 안전과 관련된 긴급 공지를 무기한 캐시하지 않는다.
- 데이터, 콘텐츠 revision, 지도 자산과 운영 설정을 정기 백업하고 복구 시점·복구 시간
  목표를 운영 전에 정한다. 복원 훈련과 행사 전 리허설로 실제 복구 가능성을 검증한다.
- 배포, 스키마 마이그레이션, 대량 게시마다 rollback 조건, 실행자, 사용자 안내와
  사후 검증 절차를 runbook에 둔다.

## 서버 로그 보관·조회

백엔드는 JSON 로그를 표준 출력으로 내보내며 Docker에서는
`/var/log/espero/application.jsonl`에도 기록한다. 운영·staging은 로그 디렉터리에 **이름 있는
별도 volume**을 연결하고 컨테이너 교체 시 같은 volume을 재사용한다. 이미지의 익명 volume만
사용하면 다음 컨테이너에 자동 재연결되지 않는다. 한 volume에 여러 JVM을 동시에 쓰지 않는다.
파일은 20MB 단위로 순환하며 archive는 최대 14일·합계 1GB로 제한한다. 용량 한도에 먼저
도달하면 14일 전에 삭제될 수 있다. volume 삭제·호스트 유실에 대한 백업은 별도다.
Docker 출력 사본도 `local` 드라이버의 20MB 파일 5개로 제한한다. 이 사본은 컨테이너 삭제 시
사라지므로 교체 이후 분석에는 named volume의 파일을 사용한다.

```text
--log-driver local --log-opt max-size=20m --log-opt max-file=5
--mount type=volume,src=espero-logs,dst=/var/log/espero
```

```sh
docker inspect --format '{{.HostConfig.LogConfig.Type}} {{json .HostConfig.LogConfig.Config}}' <container-name>
docker logs --since 1h --timestamps <container-name>
docker logs --follow --tail 100 <container-name>
docker exec <container-name> tail -n 100 /var/log/espero/application.jsonl
```

관리자·카탈로그 변경 이력은 `db` 프로필과 PostgreSQL이 활성화된 환경에서 애플리케이션
출력과 별도로 DB에 저장된다. 승인된 읽기 전용 DB 계정으로 아래처럼 확인한다. 계좌 설정
이력에는 계좌 원문이 있으므로 범용 조회·로그 수집 대상으로 삼지 않는다. 로그 원문을
공유하거나 증거로 보관할 때는 비밀값·개인정보를 먼저 제거한다.

```sql
SELECT occurred_at, admin_id, action, resource_type, resource_id, request_id
FROM admin_audit_events
ORDER BY occurred_at DESC
LIMIT 50;

SELECT created_at, actor, action, revision_id, source_revision_id
FROM catalog_revision_audit
ORDER BY created_at DESC
LIMIT 50;
```

기본값 `FESTIVAL_HTTP_LOG_SUCCESS=true`는 성공·실패 HTTP 요청을 모두 `http_request` JSON
이벤트로 기록한다. 정상적이고 빠른 `/healthz`, `/readyz`만 제외한다. 실패는 WARN/ERROR,
500ms 이상 지연은 WARN으로 표시하며 기준은 `FESTIVAL_HTTP_SLOW_REQUEST_MS`로 조정한다.
릴리스 부하 테스트도 같은 설정으로 측정한다. 성공 기록을 끈 측정은 운영 동등성 증거가 아니다.
메서드, 라우트 패턴(미매칭은 `unmatched`), 실제 상태 코드, 소요 시간, 요청 ID, 오류 코드,
revision·locale을 남긴다. `RELEASE_COMMIT`에 배포 commit을 넣으면 모든 JSON 로그에 함께
기록된다. 실제 URL·query·header·body·IP·예외 메시지는 기록 대상이 아니다. 안전한 예외 진단은
최대 5단계 원인 클래스와 프로젝트 코드 위치만 남기므로 SQL·비밀값이 포함된 원문 stack trace를
출력하지 않는다. 인증·요청 제한에 의해 MVC 전에 거절된 요청도 요청 ID와 오류 코드로 추적한다.
비동기 이미지 전송은 완료 시 한 번 기록한다. 이미 200이 전송된 뒤 연결이 실패한 경우 상태를
500으로 꾸미지 않고 `completion=failed`를 남긴다.
처리 중 예외가 밖으로 전파되면 `response_committed`도 함께 확인한다. false일 때의 status는
필터 종료 시 상태이며 컨테이너의 후속 오류 응답이 확정된 상태로 해석하지 않는다.

`admin_audit_events`에는 로그인·로그아웃·인증 실패가 없지만 위 HTTP 진단 로그에는 해당 요청
결과가 남는다. SQL 감사 이력과 로그 보관 기간은 별개다. 로컬 JAR에서도 파일이 필요하면
`LOGGING_FILE_NAME`을 보호된 쓰기 가능 경로로 지정한다. CI는 성공·실패 모두 Surefire 보고서를
14일 artifact로 보관하며 실제 운영 비밀값을 테스트 환경에 주입하지 않는다.

배포 수용 검증에서는 `/readyz`, 공개 정상 요청, 인증 거부 요청의 응답 요청 ID가 JSON 로그와
일치하고, `release`가 후보 commit이며, 로그 volume에 파일이 생성되는지 확인한다. staging에서
같은 volume으로 컨테이너를 교체한 뒤 이전 기록이 남는지도 확인한다. 로그 수집·대시보드·경보
수신과 DB/JVM 병목 지표는 별도 운영 gate이며 이 파일 설정만으로 완료되었다고 판정하지 않는다.

## 재학생존 운영 시간 설정

운영 시간 목록·상세 GET과 날짜별 PUT은 관리자 전용이며 모든 응답에 `Cache-Control: no-store`를
적용한다. 목록·상세 조회는 조건부 요청을 지원하고 상세 응답의 strong ETag로 PUT의
`If-Match`를 처리한다. Next.js same-origin proxy는 응답·조건부 상태를 전달하고 캐시하지
않는다.

V31 `crowding_operating_hours`는 catalog revision 밖에 저장되고 V32는 익일 `01:00` 종료
경계까지 허용하는 시간 CHECK 제약을 별도 migration으로 갱신한다. V31 파일은 수정하지 않아
checksum을 보존한다. 관리자 저장이 완료되면 다음
공개 혼잡도 GET부터 새 운영 시간을 적용하므로 설정을 읽기 위해 서버를 다시 시작하지 않는다.
홈은 기존 15초 polling으로 상태를 갱신한다. 카탈로그 게시와 rollback은 이 행을 수정하지
않으며 DB recovery set에도 포함한다.

배포 시 최신 main의 migration과 role 권한을 사전 점검하고, runtime에만 시간 테이블의
SELECT·INSERT·UPDATE를 준다. catalog export/publish role은 새 테이블을 읽거나 쓸 수 없어야
한다. 배포 후 관리자가 목록과 상세를 읽어 `updatedAt: null` 및 게시본 초기값을 확인하고,
승인된 실제 시작·종료 시각을 날짜별로 저장한 뒤 재조회로 유지 여부를 확인한다.

이전 애플리케이션으로 rollback할 때는 해당 버전이 관리자 저장값을 읽지 않아 공개 혼잡도가
카탈로그 초기값으로 돌아갈 수 있음을 확인한다. 설정을 읽는 버전이라도 종료 상한을 익일
`00:00`으로 검증한다면 이후 시각을 무효로 판단할 수 있다. 적용 시각의 호환성을 실제 코드와
운영자 검토로 확인하고 `crowding_operating_hours`와 그 행은 유지한다. migration을 내려서
테이블이나 설정을 지우지 않는다.

## 초기 갱신·부하 기준

- 혼잡도, 공지, 굿즈 판매 상태는 화면이 보이는 동안 15초 HTTP polling으로 갱신한다.
  화면 진입·재활성화·온라인 복귀 때 즉시 요청하고, 숨김·오프라인에서는 중단한다. 동일
  자원 요청을 겹치게 보내지 않으며 실패 시 30초, 60초 backoff와 마지막 정상값 유지를 쓴다.
- 카탈로그 revision과 동적 운영 상태를 구분한다. 같은 `meta.revision`이어도 혼잡도·공지·판매
  상태의 응답을 무시하지 않는다.
- 부하 검증의 초기 상한은 화면을 보고 있는 동시 사용자 500명이다. 각 사용자가 15초마다
  두 동적 자원을 조회하는 경우 약 67 RPS가 되므로, 이 부하와 1배·2배·5배에서 오류율,
  p95, heap, GC를 기록한다. 실제 예상 동시 접속자가 확보되면 그 값으로 갱신한다.
- 카탈로그 부하 도구의 500 VU 로컬 회귀 실행은 non-2xx 응답, timeout, transport error가
  모두 0건이어야 한다. 이는 synthetic fixture와 localhost 경로의 회귀 기준이며, 위 운영
  부하의 배포 환경 검증을 대신하지 않는다.
- 같은 도구는 동적 polling을 고정 도착률로 재현하는 `rate-67` 단계를 함께 실행한다. 혼잡도
  34 RPS와 티켓 안내 33 RPS를 60초 동안 제공하고, 경로별 p95 300ms·p99 1초 이하, 예상 밖
  오류(transport·timeout·429 외 non-2xx) 0.1% 이하, 달성률 95% 이상을 요구한다. 결과에는
  5xx·429 수, heap·GC, PostgreSQL 연결·active·lock 대기를 함께 남긴다. Render 512MB 같은
  단일 환경 결과만으로 운영 용량을 확정하지 않는다.

### 아티스트 Hyped

`GET /api/v2/artist-hyped`는 공유 누적 수와 `hypedEnabled`를 읽고,
`POST /api/v2/artists/{artistId}/hyped`는 공개 참여를 기록한다. 두 응답에
`Cache-Control: no-store`를 적용하고 Next.js same-origin proxy가 이 값을 보존하며 자체
캐시를 사용하지 않는지 확인한다. GET은 화면이 보이고 온라인일 때 15초마다 조회하며, 화면이
숨겨지거나 오프라인이면 멈춘다. 화면에 돌아오거나 온라인이 되면 즉시 다시 조회한다.

서버 내부에서는 언어 독립적인 아티스트 ID·누적 수 목록만 1초 동안 메모리에 보관한다.
축제 회차·게시 revision·리허설 namespace가 달라지면 즉시 다시 조회하며, 동시 cache miss는
한 번의 DB 조회를 공유한다. 단일 인스턴스에 마지막 집계 한 개만 보관하고 Redis는 사용하지 않는다.
참여 가능 시간과 응답 meta는 요청마다 계산하고 HTTP `no-store`는 유지한다. 성공한 POST의
누적 수는 같은 cache에도 반영하되 TTL을 연장하지 않는다. 만료 후 DB 조회 실패는 기존대로
503을 반환하며 오래된 cache로 대체하지 않는다. 외부 DB 변경은 다음 만료 후 조회에 반영된다.

Hyped GET·HEAD는 `artist-hyped-read` 전용 bucket을 사용한다.
`RATE_LIMIT_ARTIST_HYPED_READ_CAPACITY`(기본 `120`)와
`RATE_LIMIT_ARTIST_HYPED_READ_REFILL_PER_SECOND`(기본 `4.0`)로 조정하며, 두 값이 없으면
기본값을 적용한다. Hyped 폴링이 굿즈·공지 등 일반 공개 조회 한도를 소모하지 않는다.
Hyped 읽기·쓰기·일반 공개 조회는 같은 클라이언트에서도 각각 독립된 한도를 사용한다.

공개 쓰기의 보호 속도 제한은 `RATE_LIMIT_ARTIST_HYPED_CAPACITY`(기본 `120`)와
`RATE_LIMIT_ARTIST_HYPED_REFILL_PER_SECOND`(기본 `4.0`)로 조정한다. 이는 개인별 참여 횟수
한도가 아니라 서버 요청 보호 설정이다. 집계는 축제 회차·아티스트별 누적값이며 카탈로그
재게시로 초기화하지 않는다. 게시 전후에도 같은 아티스트 ID를 유지하고 다른 출연자에게
재사용하지 않는다.

새 프런트는 클릭을 IndexedDB에 먼저 저장한 뒤 1초·최대 20회씩 전송하며 클릭마다 GET을
추가하지 않는다. 새 묶음은 delta만큼 쓰기 한도를 소비하고 기존 묶음의 결과 확인은 HTTP
요청당 1 단위만 소비한다. capacity가 delta보다 작으면 422로 거절되므로 이 프런트를 사용하는
환경은 `RATE_LIMIT_ARTIST_HYPED_CAPACITY`를 최소 20으로 유지한다(기본 120). 429는
`Retry-After`, 네트워크·5xx는 같은 batchId로 backoff 재시도한다.

배포 순서는 **V34 migration → runtime DB 권한 → 백엔드 → 프런트**다.
`tools/database/provision-operational-account-roles.sql`의 새 `artist_hyped_batches`
SELECT/INSERT/UPDATE 권한을 적용한다. runtime과 migration 계정이 다르면 테이블 생성만으로
쓰기가 허용되지 않는다. 백엔드가 기존 `{}`와 새 묶음을 모두 처리하는지 확인한 후 프런트를
배포한다. 구형 백엔드는 새 본문을 422로 거절하며 프런트는 이 요청을 영구 실패로 처리한다.

되돌릴 때는 프런트부터 되돌리고 이미 생성한 V34 테이블·기록·권한은 보존한다. 구형 PWA나
탭이 새 요청을 계속 보낼 수 있으므로 백엔드의 묶음 지원을 먼저 제거하지 않는다. 클라이언트
큐의 최대 수명과 조정하지 않은 ledger 삭제·TTL 정리는 금지한다. DB 복원은 counts와 batches를
같은 시점으로 복원해야 한다. counts만 복원하면 재시도가 중복되거나 누락될 수 있다.

저장된 묶음은 화면 이동·PWA 재실행·온라인 복귀 때 복구하며 종료 중 실행을 보장하지 않는다.
같은 축제의 이미 성공한 묶음은 참여 마감 후에도 확인할 수 있다. 종료 후 처음 도착하면
HYPED_CLOSED, 회차 전환 후에는 FESTIVAL_MISMATCH로 거절한다. 리허설 receipt는 실제
집계에 합치지 않는다. 물리 iOS·Android PWA의 종료/재실행 확인과 운영 지연 측정은 로컬
자동 검증과 별도로 수행해야 한다.

### 굿즈 이미지 전달

`GET /api/v2/media/goods-images/{mediaId}/{variant}`는 요청 스레드에서 `Content-Length`와 함께
본문을 쓴다. 비동기 streaming은 Spring 기본 작업 executor(8 thread)를 공유해 느린 모바일
클라이언트 몇 명이 다른 이미지 전송을 막을 수 있어 사용하지 않는다. 응답은
`Cache-Control: public, max-age=31536000, immutable`과 강한 ETag만 사용하며, 이 경로에는
Spring Security 기본 `Pragma: no-cache`·`Expires: 0`을 쓰지 않는다. URL에 확장자가 없으므로
CDN(Cloudflare 등)은 이 경로를 캐시 대상으로 지정하는 규칙이 있어야 edge에서 응답한다.

## 구현 규칙

- 운영 콘텐츠는 최소 `draft / scheduled / published / archived` 생명주기와 게시자,
  게시·수정 시각, revision, 감사 이력을 갖는다. 공개 API는 게시 승인된 revision만
  반환한다.
- 관리자 변경은 미리보기와 유효성 검사를 거친다. 시간표·지도처럼 여러 객체가 함께
  바뀌는 변경은 부분 게시되지 않도록 하나의 게시 단위로 취급한다.
- 긴급 공지는 예약 콘텐츠보다 우선하며 즉시 게시·회수할 수 있어야 한다. 초기 단일
  인스턴스에서는 게시 또는 회수 뒤 제어된 재시작으로 snapshot을 교체한다.
- 캐시 키에는 축제 회차, 콘텐츠 revision, locale, map version 등 응답을 바꾸는 요소를
  포함한다. 이미지와 version 고정 자산에는 장기 캐시를 적용하며, 공유 JSON 캐시와 CDN
  도입은 배포 구조가 확정된 뒤 다시 검토한다.
- 네트워크 또는 API 장애 시 검증된 마지막 데이터로 제한된 기능을 제공할 수 있지만,
  오래된 데이터임을 표시하고 긴급 공지를 무기한 캐시하지 않는다.
- 운영 데이터와 설정은 정기 백업하며 복구 시점·복구 시간 목표를 출시 전에 정한다.
  백업 존재 여부가 아니라 실제 복원 훈련으로 검증한다.
- 배포·스키마 변경·대량 콘텐츠 게시에는 롤백 절차와 담당자를 두고, 이전의 일관된
  revision으로 복구할 수 있어야 한다.
- `TICKET`·`GOODS` 계좌는 catalog revision 밖의 versioned 운영 설정이다. 변경은 HTTP가
  아닌 별도 DB role의 dry-run CLI로 수행하며, migration·role provisioning·복구·retention은
  [계좌 운영 설정](operational-account-settings.md)을 따른다.
