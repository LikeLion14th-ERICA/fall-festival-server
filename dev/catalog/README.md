# Development Catalog

`development-catalog.json`은 개발 환경의 공개 API smoke test에만 사용하는 synthetic
catalog manifest입니다. 실제 행사 운영 정보나 승인된 production content가 아닙니다.

다음 값은 모두 개발용 가상 값이며 승인된 축제 정보로 취급하면 안 됩니다.

- 축제일: `2026-09-29`, `2026-09-30`, `2026-10-01`
- 일별 가상 운영 시간: 09:00~23:00 KST
- 가상 타임테이블 축: 18:00~23:00
- 가상 공연 A: 09/29 19:00~19:30 KST
- 가상 공연 B: 10/01 20:00~20:30 KST
- 09/30: 공연 없음

아티스트, 공연, 시간, 티켓, 스탬프, 금지 물품, 지도와 기타 값도 실제 운영 자료로
사용하지 않습니다. 이 manifest에는 실제 festival UUID, 계좌 정보, 가격, 송금 링크,
QR, 지도, 부스 또는 반입 금지 정책이 들어 있지 않습니다.

예외로 `festivalLinks`에는 [HOME-008](../../docs/wiki/product/home.md)에서 확정한 총학생회
공식 채널(Instagram, YouTube, 총학생회 홈페이지)의 공개 주소를 넣었습니다. 공지사항·FAQ·
에리카 웰컴 데이 링크는 URL이 확정되지 않아 넣지 않았고, `/api/v2/config`는 이들을 `null`로
반환합니다.

## Festival binding

manifest는 환경에 독립적이어야 하므로 `festivalId`를 포함하지 않습니다. 실제 개발
환경에서 import할 때는 이미 존재하는 개발 festival UUID를 명시적으로 전달해야 합니다.

```text
Catalog CLI import dev/catalog/development-catalog.json --festival-id=<existing-development-festival-uuid> --baseline-revision=<current-published-revision-uuid|none>
```

Catalog CLI는 `FESTIVAL_ID` 환경변수를 import 대상의 fallback으로 사용하지 않습니다.
항상 `--festival-id`를 명시합니다. 같은 이유로 manifest는 `baselineRevisionId`도 담지 않으며,
import 직전 read-only preflight로 확인한 그 festival의 현재 published revision을
`--baseline-revision`으로 전달합니다. published revision이 없으면 `none`을 씁니다. 준비하는
동안 다른 게시가 끼어들면 import와 publish가 `BASE_REVISION_CONFLICT`로 멈춥니다.
계좌는 catalog가 아닌 운영 계좌 설정이므로 이 manifest의 `ticketGuide`에는 계좌·송금 링크
field가 없습니다. Catalog CLI는 Flyway도 실행하지 않으므로 대상 DB가
필요한 schema version까지 먼저 migration되어 있어야 합니다.

## Operational sequence

실제 개발 DB 작업은 별도의 승인과 자격증명 관리 아래 다음 순서로 진행합니다.

1. read-only preflight
2. import
3. 생성된 draft 점검
4. validate
5. publish
6. 애플리케이션 재시작 후 `/readyz`와 공개 API 응답 확인

`import`, `validate`, `publish`는 이번 fixture 작성·로컬 검증 작업에 포함되지 않습니다.
특히 `validate`와 `publish`는 DB mutation 및 audit 기록을 만들 수 있으므로 read-only
operation으로 취급하지 않습니다.
