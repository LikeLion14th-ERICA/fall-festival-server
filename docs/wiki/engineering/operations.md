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

공개 쓰기의 보호 속도 제한은 `RATE_LIMIT_ARTIST_HYPED_CAPACITY`(기본 `120`)와
`RATE_LIMIT_ARTIST_HYPED_REFILL_PER_SECOND`(기본 `4.0`)로 조정한다. 이는 개인별 참여 횟수
한도가 아니라 서버 요청 보호 설정이다. 집계는 축제 회차·아티스트별 누적값이며 카탈로그
재게시로 초기화하지 않는다. 게시 전후에도 같은 아티스트 ID를 유지하고 다른 출연자에게
재사용하지 않는다.

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
