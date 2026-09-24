# 릴리스 후보 staging·복구 증거 runbook

[위키 홈](../README.md) · 읽는 때: backend 릴리스 후보를 staging에서 검증하거나 복구 리허설을 기록할 때

이 문서는 root backend release validation의 실행 결과를 **증거 묶음(evidence bundle)** 으로
남기는 절차다. 실행 코드와 명령의 원문은 [검증 명령과 CI](validation.md)와 저장소의
`tools/release-validation`에 둔다. 실제 운영 host, credential, database dump, media archive
또는 Caddy 설정값은 이 문서에 만들거나 복사하지 않는다.

## 1. 적용 범위와 release block

모든 gate는 운영 데이터와 분리된 staging에서 실행한다. staging은 후보 image와 후보
catalog만 사용하고 운영 DB·media volume·실제 계좌·실제 stamp secret에 연결하지 않는다.
staging 결과는 운영 배포 성공의 증거가 아니라 승인된 release candidate가 다음 단계로 갈 수
있는지 판단하는 입력이다.

다음 중 하나라도 빠지거나 실패하면 release를 block한다.

- candidate image digest 또는 Git commit을 고유하게 식별할 수 없다.
- OpenAPI hash, migration checksum, operation mapping, dependency/container/secret scan 중 하나가
  후보와 연결되지 않거나 실패했다.
- PostgreSQL 17 compatibility/preflight, disposable-DB backend release E2E, staging smoke/load 결과
  중 하나가 해당 evidence bundle에 남지 않았다. Docker가 없어 focused E2E를 건너뛴 결과는
  성공으로 취급하지 않는다.
- recovery set 또는 복구 리허설이 없고 데이터·media 복구 결과를 같은 artifact ID로 묶지 못했다.
- RPO 또는 RTO의 승인된 목표가 없다. 목표값을 임의로 정하지 말고 릴리스 승인 기록에 확정한다.
- on-call 주 담당·대체 담당, 실제 연락 채널, alert 수신·acknowledge·escalation 경로가
  확정되지 않았다. 알림이 아직 연결되지 않았거나 누가 대응할지 모르면 release를 block한다.
- staging smoke, browser handoff 또는 복구 후 smoke에 required probe 실패·미실행·판정 보류가
  남았다.

`/readyz`는 해당 `FESTIVAL_ID`의 published snapshot 적재 여부를 확인하는 기존 readiness
의미를 유지한다. `/healthz`는 liveness다. `/readyz`를 DB 장애 감시 probe, 백업 성공 probe,
알림 수신 probe로 해석하거나 계약을 바꾸지 않는다.

## 2. 증거 bundle 식별과 저장

release 책임자는 staging 실행 전에 다음 필드를 하나의 보호된 작업 기록에 만든다.

| 필드 | 기록 규칙 |
| --- | --- |
| `evidenceId` | 재사용하지 않는 작업 기록 ID |
| `candidateCommit` | 후보 Git commit SHA |
| `candidateImageDigest` | 후보 container image의 immutable digest와 OCI revision label |
| `stagingTarget` | staging 식별자와 Asia/Seoul 시작·종료 시각 |
| `festivalId` | 검증 대상 회차 식별자(운영 secret 아님) |
| `operator` / `reviewer` | 실행자와 독립 확인자 |
| `decision` | `PASS`, `BLOCKED`, `REVIEW` 중 하나와 근거 |

명령 출력은 원문 전체를 저장하기보다 결과, 종료 코드, artifact ID, hash, 실행 시각과 판정만
남긴다. 로그·스크린샷·브라우저 export에 Authorization header, cookie, password, connection
string, 계좌 원문, stamp code/hash, 개인정보가 있으면 제거하거나 저장하지 않는다.

protected record에는 registry에서 확인한 실제 candidate image digest, 그 image의 OCI revision
label, `candidateCommit`과 label의 일치 결과, 그리고 Trivy image scan evidence의 artifact ID와
report hash를 반드시 연결한다. registry 주소, credential, secret, host 값과 scan 원문에 포함된
민감한 경로는 저장소에 넣지 않는다.

## 3. staging gate와 증거 형식

각 행은 같은 `evidenceId`와 `candidateImageDigest`를 가리켜야 한다. `status`가 `PASS`가
아니면 release block 또는 명시적 `REVIEW` 승인 전까지 다음 gate로 넘어가지 않는다.

| gate | 확인할 내용 | 최소 evidence |
| --- | --- | --- |
| Candidate identity | commit·image·catalog candidate가 같은 후보인지 | SHA, image digest, OCI revision label과 candidate commit 일치, manifest/revision ID, 생성 시각 |
| OpenAPI contract | 후보 계약과 생성 artifact가 일치하는지 | `api-v2/openapi.json` SHA-256, source revision, contract check 결과 |
| Migration | Flyway history와 후보 SQL checksum이 일치하는지 | migration 목록·checksum, schema 대상, `mutationAuthorized` 판정 |
| Operation mapping | 후보 OpenAPI 전체 operation(현재 62개)의 classification과 provider·scenario mapping이 후보와 일치하는지 | classification/provider·scenario mapping hash, check 결과, 누락·중복 0 판정 |
| Scan | dependency/container vulnerability, secret, misconfiguration 결과와 CodeQL Java/Kotlin 분석 | Trivy image/filesystem scan evidence, `HIGH,CRITICAL` fail-closed 결과, CodeQL report·check reference, report hash, waiver 승인 ID |
| PostgreSQL 17 | 지원 PostgreSQL 17 staging에서 preflight와 migration 확인 | server version, schema, Flyway result/checksum, read-only preflight 결과 |
| Automated release E2E | disposable Testcontainers DB에서 후보 import/publish, 공개 흐름, admin 경계, rollback/restart 관계 | 실행 ID, exit code, scenario summary, `/healthz`, `/readyz`, `meta.revision` |
| Staging smoke | 실제 staging 후보 image의 공개·관리자 경계와 readiness 확인 | staging target reference, image digest, smoke 결과, `/healthz`, `/readyz`, `meta.revision` |
| Load | 승인된 동시 사용자·RPS profile에서 latency와 오류 확인 | profile, duration, p95/p99, 4xx/5xx/429, heap/GC/DB pool, report hash |
| Recovery | DB와 media를 같은 recovery set으로 복원하고 검증 | recovery set ID, dump/media checksum, restore 시각, revision/media smoke |
| Browser handoff | 지원 브라우저·viewport에서 운영자와 사용자 흐름 인수인계 | browser/version/viewport, run ID, navigation/back·cookie·CORS 결과, owner sign-off |

자동 release E2E의 HTTP-01~36와 OPS-01~20은 disposable Testcontainers PostgreSQL DB 전용이다.
이 자동 검사는 실제 staging datasource·운영 DB·원격 개발 DB를 사용하지 않으며, Docker가 없으면
성공으로 취급하지 않는다. 실제 staging은 별도 후보 image를 배포해 smoke, load, browser handoff,
recovery를 실행하고 그 결과를 별도 gate evidence로 연결한다. 러브레터 활성화 후보는
[LOVE-001 릴리스 시나리오](release-http-e2e-love-letter.md)의 STAGE-06 증거도 연결한다.
각 절차와 기존 시나리오·load 기준은
[검증 명령과 CI](validation.md), [운영·카탈로그 구현 인수인계](ops-catalog-handoff.md),
[운영](../engineering/operations.md)을 따른다. 이 문서는 그 결과를 다시 구현하거나 숫자를
복제하지 않고 후보별 증거를 연결한다.

### 3.1 후보·계약·migration 연결

후보 기록에는 다음처럼 서로 다른 hash를 분리해 적는다.

```text
evidenceId: <protected-record-id>
candidateCommit: <git-sha>
candidateImageDigest: sha256:<image-digest>
catalogRevision: <published-revision-id>
openapiSha256: <sha256-of-generated-openapi>
migrationChecksums: <protected-report-id>
```

`openapiSha256`는 생성된 OpenAPI artifact의 hash이고, migration checksum은 Flyway history와
후보 SQL의 검증 결과다. 하나를 다른 하나의 대체 증거로 사용하지 않는다. checksum 불일치,
예상 밖 migration, 승인되지 않은 mutation이면 staging에서 중단하고 DB 담당자의 판단을
기록한다.

### 3.2 실행·부하·브라우저 handoff

자동 HTTP/OPS E2E는 disposable Testcontainers DB만 사용한다. 실제 staging 후보 image의
배포·smoke·load·browser handoff·recovery는 이 자동 E2E와 별도 실행이며, 각각의 실제 target과
artifact ID를 protected record에 연결한다. Docker 부재로 테스트를 skip했거나 실행 중인 서버
없이 명령 형식만 확인한 경우도 PASS가 아니다.

Trivy image·filesystem scan은 `HIGH,CRITICAL`을 fail-closed로 처리하고 `ignore-unfixed=false`,
`exit-code=1`을 사용한다. CodeQL Java/Kotlin 분석 결과도 evidence에 연결한다. GitHub CodeQL
workflow만으로는 alert severity의 merge 차단이 자동 설정되지 않으므로, repository admin이
`main` 보호 ruleset에서 CodeQL security alerts `High or higher`와 관련 CI checks를 required로
설정해야 `codeql` gate를 `passed`로 판정할 수 있다. 이 조건이 확인되지 않으면 release를
block하며, 저장소에는 실제 ruleset·registry·secret·host 값을 기록하지 않는다.

부하 결과에는 profile, 대상 endpoint와 dataset, 실행 시간, 성공·실패·timeout, latency
percentile, JVM·DB 자원, rate limit 응답을 함께 기록한다. 운영 용량을 staging 한 번의 결과로
확정하지 말고 승인된 profile과 한계를 release 기록에 명시한다.

Browser handoff는 staging 공개 URL에서 수행한다. 모바일 viewport와 지원 브라우저의 공개
탐색, back/navigation, Secure·SameSite cookie, CORS/proxy, 온라인 복귀와 polling을 확인하고
실제 credential이나 cookie 값을 캡처하지 않는다. 결과는 브라우저·버전·viewport와 PASS/BLOCKED,
보호된 artifact ID만 남긴다.

## 4. 복구 리허설과 RPO/RTO

복구 리허설은 staging의 별도 대상에 수행한다. DB dump와 `espero-media` archive는 같은
recovery set ID와 생성 시각을 가져야 한다. 각각의 checksum을 계산하고, 복원 후 다음 순서로
확인한다.

1. 복원 대상과 후보 image digest를 기록한다.
2. DB와 media를 같은 recovery set으로 복원한다.
3. Flyway history/checksum과 published revision을 확인한다.
4. 연결된 media URL을 staging 공개 경로에서 조회하고 catalog·공개 API smoke를 실행한다.
5. `/healthz`와 `/readyz`, `meta.revision` 결과와 복구 소요 시간을 evidence bundle에 연결한다.

RPO는 복구 시 허용되는 데이터 손실 시점, RTO는 서비스 복구 목표 시간으로 기록한다. 두 값이
확정되지 않았거나 리허설이 목표를 충족했는지 측정할 수 없으면 release를 block한다. 백업 파일이
존재한다는 사실만으로 RPO/RTO 충족을 주장하지 않는다. 복구 실패, checksum 불일치, DB와 media
불일치, required smoke 미실행은 `BLOCKED`다.

실제 운영 dump·media archive의 저장 위치와 보존은 [행사 당일 운영 절차서](festival-day-runbook.md)의
복구 세트 정책을 따른다. 이 저장소에는 dump, archive, secret reference, 실제 host 경로를
커밋하지 않고 보호된 운영 기록의 `recoverySetId`와 checksum 결과만 연결한다.

## 5. on-call·alert·승인 인수인계

release 승인 전에 아래 세 가지를 작업 기록에 채운다.

| 항목 | 필요한 확정값 | 미확정 시 판정 |
| --- | --- | --- |
| On-call | 주 담당, 대체 담당, 당직 시간, 실제 연락 채널 | `BLOCKED` |
| Alert | 대상 probe/metric, threshold, notification route, acknowledge와 escalation 절차 | `BLOCKED` |
| Recovery authority | C 인프라·D 데이터 담당, 승인자, rollback 결정권 | `BLOCKED` |

운영 host, Caddy upstream/config, Cloudflare 설정, credential과 명령 전문은 보호된 운영 기록에서
참조한다. evidence bundle에는 안전한 참조 ID와 검증 결과만 넣는다. 참조가 없거나 접근 권한이
확인되지 않으면 배포·재기동·복구를 시작하지 않는다.

## 6. 최종 판정 기록

release 책임자와 독립 확인자는 모든 gate를 확인한 뒤 아래 형식으로 판정한다.

```text
evidenceId: <id>
candidateImageDigest: sha256:<digest>
gateSummary: <pass-count>/<total-count> PASS
rpo: <approved-target-or-BLOCKED>
rto: <approved-target-or-BLOCKED>
onCall: <owner/backup/contact-reference-or-BLOCKED>
alerting: <route-and-ack-reference-or-BLOCKED>
recoverySetId: <id-or-BLOCKED>
browserHandoff: <artifact-id-or-BLOCKED>
decision: PASS | BLOCKED | REVIEW
reviewer: <name>
decidedAt: <Asia/Seoul timestamp>
```

`PASS`는 모든 필수 증거가 같은 후보를 가리키고 block 조건이 없을 때만 사용한다. `REVIEW`는
릴리스 승인 전 해결해야 할 미확정 항목을 명시한 상태이며 운영 배포 허가로 간주하지 않는다.
