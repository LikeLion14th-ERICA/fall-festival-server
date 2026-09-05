# 한양대학교 ERICA 가을 축제 웹 앱 API 명세서 v1.0

## 1. 문서 정보

| 항목 | 값 |
|---|---|
| API 버전 | v1 |
| Base path | /api/v1 |
| 전송 형식 | HTTPS, JSON, UTF-8 |
| 공개 사용자 인증 | 없음 |
| 관리자 route prefix | /admin (Base path와 결합하면 /api/v1/admin) |
| 기준 시간대 | Asia/Seoul |
| 식별자 | 도메인 entity는 opaque UUID; 명시된 singleton key·code는 닫힌 예외 |

이 문서는 공개 웹 앱과 관리자 운영 도구가 공유하는 v1 HTTP 계약이다. 구현 언어와
프레임워크는 이 계약에 포함하지 않는다. 현재 저장소의 `test/`는 PWA·스탬프·Web Push
검증용 prototype이며 이 축제 서비스의 제품 API가 아니다. 제품 OpenAPI artifact가
추가되기 전까지 이 문서를 규범적 계약으로 사용한다. 최초 제품 API 구현 PR은 이 문서와
일치하는 OpenAPI 파일과 계약 테스트를 함께 추가하고, 그 이후에는 OpenAPI를 기계 판독
가능한 계약 원본으로 유지한다.

모든 운영 콘텐츠는 하나의 축제 회차 festivalId에 귀속된다. 연도가 같아도 별도 회차는
서로 다른 festivalId를 사용하며, 클라이언트는 이름이나 연도에서 ID를 만들지 않는다.

## 2. 버전과 호환성

- 하위 호환 가능한 선택 필드, 새 endpoint, 새 오류 상세의 추가는 v1 안에서 허용한다.
- 필드 삭제, 타입 변경, 기존 enum의 의미 변경, 경로 또는 인증 경계 변경은 새 major
  API 버전에서 제공한다.
- 새 enum 값은 기존 클라이언트가 알 수 없는 값으로 안전하게 처리할 수 있을 때만
  추가한다. 그렇지 않으면 새 API 버전을 사용한다.
- 제거 예정 기능은 응답의 Deprecation 및 Sunset 헤더와 운영 공지로 알리고 대체 경로를
  문서화한다.
- 공개 API의 게시된 데이터 구조를 바꾸는 배포는 consumer·provider 계약 테스트와
  이전 클라이언트 호환 검증을 통과해야 한다.

## 3. 서비스 경계

### 3.1 v1 공개 기능

- 현재 축제 회차와 축제 기본 정보
- 홈 집계
- 라인업 아티스트 목록
- 공연 시간표 목록·상세
- 부스·주점·플리마켓 목록·상세와 구조화 메뉴
- 축제 지도, 구역·카테고리, 마커 목록·상세
- 공지 목록·상세
- 축제 운영 데이터에 근거한 비로그인 챗봇

Figma가 제안한 라인업 검색·상세·SNS·추천곡과 FAQ·공식 외부 링크는 v1 schema가
호환 가능한 capability를 제공하지만, 승인된 축제 회차 설정에서 활성화되기 전에는
공개 앱에 노출하거나 데이터를 게시하지 않는다.

### 3.2 v1에서 공개하지 않는 기능

다음 기능은 제품 승인과 운영 정책이 완성될 때까지 실행 OpenAPI와 배포 route에
공개하지 않는다. 문서의 조건부 확장 계약은 활성 기능이나 구현 승인으로 보지 않는다.

- 스탬프 투어 — 적용 시 계약은 29절
- 하루 단위 분실물 안내
- 폴라로이드
- 텐텐식 술게임
- 일본어

고등학생 방문 안내는 총학생회가 승인한 콘텐츠와 노출 조건을 받은 뒤 팝업·페이지의
콘텐츠와 노출 상태 계약을 별도 추가한다. 승인 전에는 endpoint, generic 외부 링크,
빈 카드나 임시 문구로 대신하지 않는다.

### 3.3 기획·Figma IA 대응표

이 절은 Figma 와이어프레임 `86:2`, FigJam 서비스 IA `34:398`, 참고용 API 초안과
이 계약의 대응 관계를 고정한다. Figma의 예시 날짜·아티스트·운영 시간·학교 약칭은
운영 데이터나 공식 명칭으로 승격하지 않는다.

| 공개 화면·흐름 | v1 API | 상태와 계약 |
|---|---|---|
| 홈 | `GET /festivals/{festivalId}/home` | 현재 진행 항목·다음 공연·중요 공지·승인된 바로가기 집계 |
| 언어 변경 | 모든 공개 조회의 `lang`, 챗봇 body의 `lang` | `ko`, `en`, `zh`; Figma의 일본어 항목은 후순위라 반환하지 않음 |
| 라인업·아티스트 상세 | artists 목록·상세 | 검색·상세·SNS·추천곡은 회차 capability로 개별 게이트 |
| 아티스트 `내 일정에 추가` | 없음 | Figma 제안이며 범위 미확정; 사용자 계정이나 서버 저장을 임의로 추가하지 않음 |
| 타임테이블 | performances 목록·상세 | 서버 시각·현재 공연 ID·운영 설정을 같은 revision으로 반환 |
| 부스·주점·플리마켓 | spaces 목록·상세 | 구조화 메뉴·운영 시간·안정적 `mapMarkerId` 포함 |
| 지도·필터·마커 바텀시트 | map, map/markers 목록·상세 | 구역/카테고리는 별도 축, snapshot과 marker version 일치 강제 |
| 공지사항 | notices 목록·상세 | 운영·우천·긴급·일정 변경·교통 공지 지원 |
| 챗봇 | chatbot/configuration, chatbot/messages | 로그인 없이 초기 화면 구성 조회, 게시 데이터와 승인된 지식만 근거로 응답 |
| FAQ·공식 SNS | external-links | 승인된 회차에서 capability가 켜진 경우에만 노출 |
| 고등학생 안내 | 없음 | 총학생회 자료와 팝업·페이지 노출 조건 대기; 승인 후 별도 계약 추가 |
| 분실물·FUN | 없음 | 각각 협의·보류 또는 후보 기능이며 v1 공개 계약에서 제외 |
| 스탬프 투어 | 현재 없음; 29절 조건부 계약 | 참여·QR·경품·개인정보 승인 전 route 비활성 |

관리자 FigJam IA의 대응은 18.1을 규범적 목록으로 사용한다. 화면이 IA에 존재한다는
사실만으로 자료 대기·협의 기능을 활성화하지 않는다.

## 4. 공통 HTTP 규약

### 4.1 요청

- Content-Type이 필요한 요청은 endpoint가 별도로 지정하지 않으면 application/json을
  사용한다. JSON Merge Patch endpoint는 application/merge-patch+json을 사용한다.
- 클라이언트는 X-Request-Id를 보낼 수 있다. 없으면 서버가 생성한다.
- GET과 HEAD는 상태를 변경하지 않으며 재시도 가능해야 한다.
- 인증된 관리자 상태 변경 POST와 모든 관리자 PUT은 Idempotency-Key를 필수로 받는다.
  인증·복구 endpoint와 명시적으로 idempotent한 session DELETE의 예외는 18.3을 따른다.
- 관리자 수정·삭제·게시 요청은 리소스의 ETag를 If-Match로 전달해야 한다. 아직 존재하지
  않는 versioned singleton의 최초 PUT만 19.1에 따라 `If-None-Match: *`를 사용한다.
- 자신의 보안 session 폐기와 로그아웃은 신속한 revoke를 위한 명시적 예외이며 18.3의
  idempotent 규칙을 따른다.
- endpoint가 요구하는 Idempotency-Key가 없으면 400 IDEMPOTENCY_KEY_REQUIRED, 예외가 아닌
  관리자 변경에서 If-Match가 없으면 428 PRECONDITION_REQUIRED다. 값이 있지만 현재 ETag와
  다르면 412 REVISION_MISMATCH다.
- 알 수 없는 query parameter는 400 INVALID_QUERY_PARAMETER를 반환한다.
- 목록의 반복 query parameter는 허용된 필터에서만 사용한다.

### 4.2 성공 상태

| 상태 | 의미 |
|---:|---|
| 200 | 조회·수정 성공 |
| 201 | 생성 성공 |
| 202 | 게시·cache 반영 등 비동기 작업 접수 |
| 204 | 응답 본문이 없는 삭제·로그아웃 성공 |

목록이 비어 있으면 404가 아니라 200을 반환한다. cursor 목록은 빈 `data.items`,
공연·marker처럼 이름 있는 collection은 자기 schema의 빈 배열을 반환한다.

### 4.3 성공 envelope

모든 JSON 성공 응답은 data와 meta를 가진다. 204 응답에는 본문이 없다.

    {
      "data": {},
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul",
        "contentRevision": "184",
        "snapshotUpdatedAt": "2030-09-18T18:04:30+09:00",
        "language": {
          "requested": "en",
          "content": "ko",
          "fallbackApplied": true
        }
      }
    }

meta 규칙:

- requestId는 모든 JSON 응답에 포함하며 응답 헤더 X-Request-Id와 동일하다.
- serverTime은 모든 JSON 성공 응답에 포함하며 서버가 응답을 만든 시각이다.
- festivalTimeZone은 축제 회차에 속한 응답에 포함하는 IANA time zone이다.
- contentRevision은 게시 콘텐츠를 반환하는 응답에 포함하며 응답을 구성한 revision이다.
- snapshotUpdatedAt은 게시 콘텐츠 응답에 포함하며 그 contentRevision이 생성된 시각이다.
  시간 비의존 snapshot 응답의 Last-Modified는 이 값이고, 시간 파생 응답은 22절의 별도
  validator 규칙을 따른다.
- language는 번역 가능한 콘텐츠가 있는 응답에 포함한다.
- pagination은 cursor 기반 목록 응답에 추가한다.
- 번역 가능한 응답의 Content-Language 헤더는 meta.language.content와 같다.

`ContentRevision`은 정규식 `^[1-9][0-9]{0,18}$`을 따르는 1~19자리 decimal string이다. 서버는
성공한 public head commit마다 signed 64-bit 양수 범위에서 단조 증가시키지만 client는 숫자
연산이나 사전식 정렬을 하지 않고 opaque equality token으로만 사용한다. 이 문서의
`contentRevision`, `validatedBaseContentRevision`, `baseContentRevision`,
`appliedContentRevision`, `targetContentRevision`, `expectedCurrentContentRevision`은 모두 이
타입이며, 명시적으로 nullable인 필드만 최초 public head 전 null일 수 있다. 리소스별 integer
workingRevision·publishedRevision과 서로 변환하거나 비교하지 않는다.

### 4.4 오류

오류 Content-Type은 application/problem+json이며 RFC 9457 형식을 따른다.

    {
      "type": "https://festival.example.com/problems/validation-error",
      "title": "Validation failed",
      "status": 422,
      "detail": "요청 값을 확인해 주세요.",
      "instance": "/api/v1/admin/festivals/7ef.../performances",
      "code": "VALIDATION_ERROR",
      "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
      "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
      "errors": [
        {
          "field": "effectiveEndsAt",
          "code": "END_BEFORE_START",
          "message": "종료 시각은 시작 시각보다 늦어야 합니다."
        }
      ]
    }

- detail과 errors.message는 사용자에게 노출 가능한 문장만 담는다.
- stack trace, SQL, 내부 호스트, 토큰, 개인정보를 반환하지 않는다.
- type URI는 안정적으로 유지하고 오류 설명 문서로 연결한다.
- requestId는 응답 헤더 X-Request-Id와 같고 운영 문의·로그 조회에 사용한다.
- traceId는 분산 추적이 활성화된 요청에 포함하며 추적 시스템에서 조회할 수 있어야 한다.

| 상태 | 사용 조건 |
|---:|---|
| 400 | 문법이 잘못된 JSON, query, cursor, locale |
| 401 | 필요한 관리자·익명 session·service credential이 없거나 무효 |
| 403 | 관리자 권한 부족 또는 same-origin/CSRF 검증 실패 |
| 404 | 존재하지 않거나 공개되지 않은 리소스 |
| 409 | 참조, 일정, 게시 상태 또는 idempotency 충돌 |
| 410 | 만료·소비·폐기된 단기 token/session 자원 또는 권리 기한이 지난 미디어 |
| 412 | If-Match 또는 요청한 version/snapshot precondition 불일치 |
| 415 | 지원하지 않는 media type |
| 422 | 필드 값 검증 실패 |
| 428 | 필수 If-Match 누락 |
| 429 | 요청 제한 초과 |
| 500 | 복구되지 않은 서버 오류 |
| 503 | 의존 서비스 또는 게시 revision 일시 사용 불가 |

## 5. 다국어 계약

### 5.1 지원 언어

v1의 언어 식별자는 ko, en, zh다. 일본어는 지원 값에 포함하지 않는다. zh는 해당 축제
회차에서 승인된 하나의 중국어 번역 카탈로그를 뜻한다. 간체·번체가 별도로 확정되면
BCP 47 하위 태그와 마이그레이션 정책을 추가한다.

언어 선택 우선순위:

1. lang query parameter
2. Accept-Language 헤더에서 지원되는 첫 언어
3. 축제 회차의 defaultLanguage

지원하지 않는 명시적 lang 값은 400 UNSUPPORTED_LANGUAGE를 반환한다.
festivals/current를 제외한 모든 공개 GET은 lang을 받을 수 있다. 챗봇은 request body의
lang을 우선하고, 없으면 Accept-Language와 defaultLanguage 순으로 선택한다.

### 5.2 번역 fallback

- 번역은 필드별로 섞지 않는다. 상세 응답은 콘텐츠 행 전체, 목록과 홈은 응답에 포함된
  번역 가능 콘텐츠 전체를 하나의 언어로 반환한다.
- 요청 언어의 필수 번역이 하나라도 불완전하면 해당 응답 전체를 승인된 fallback
  언어로 반환한다. 한 응답 안에 요청 언어와 fallback 언어의 항목을 섞지 않는다.
- 응답 meta.language에 requested, content, fallbackApplied를 반환한다.
- 학교 공간·조직·프로그램의 영어·중국어 공식 명칭은 승인된 값만 저장한다.
- 관리자 게시 검증은 필수 언어 누락을 탐지한다. fallback 허용 여부는 축제 회차 설정에
  따른다.
- 검색·정렬용 텍스트는 실제 콘텐츠 언어를 사용한다. CDN·API cache key는 요청 언어,
  실제 콘텐츠 언어, fallback 적용 여부를 모두 포함해 `lang=en → content=ko` 응답과
  `lang=ko → content=ko` 응답이 같은 cache entry를 공유하지 않게 한다.

Festival.translationPolicy의 계약:

- requiredLanguages는 비어 있지 않은 supportedLanguages의 부분집합이며
  defaultLanguage를 포함한다.
- fallbackLanguage는 requiredLanguages에 포함되며 모든 게시 리소스에서 완전한 번역을
  가져야 한다.
- fallbackAllowedFor는 fallbackLanguage를 제외한 supportedLanguages의 부분집합이다.
- v1 회차의 supportedLanguages는 순서와 무관하게 정확히 `ko`, `en`, `zh`를 한 번씩
  포함한다. `ja`와 그 밖의 언어는 거부한다. defaultLanguage는 이 세 언어 중 하나로
  구성 가능하며, 중국어 문자 체계와 기본 언어의 최종 운영 결정은 번역 콘텐츠와 별도로
  관리한다.
- 게시 snapshot을 만들 때 requiredLanguages의 번역 누락은 항상 차단한다. 그 밖의 지원
  언어가 불완전하면 fallbackAllowedFor에 포함된 경우에만 게시할 수 있고, 포함되지 않은
  언어의 누락도 게시 오류다.
- 공개 요청의 선택 언어가 fallbackAllowedFor에 있고 해당 응답의 필수 번역이
  불완전하면 fallbackLanguage를 사용한다. 이 조건을 만족하지 않는 번역 누락이 게시
  snapshot에서 발견되면 서로 다른 언어를 섞어 반환하지 않고 503
  CONTENT_REVISION_UNAVAILABLE을 반환한다.
- 언어별 presentation 자산이 응답 schema상 필수이면 그 언어 자산의 누락·비가용도 위
  `필수 번역 불완전` 판정에 포함한다. 텍스트 언어를 먼저 확정한 뒤 자산만 별도 fallback하지
  않고, 선택 후보 언어의 텍스트와 필수 자산을 함께 검사해 응답 전체의 content 언어를 정한다.

관리자 번역 입력 형식:

    {
      "translations": {
        "ko": {
          "name": "승인된 한국어 명칭",
          "description": "승인된 한국어 설명"
        },
        "en": {
          "name": "Approved English name",
          "description": "Approved English description"
        },
        "zh": null
      }
    }

모든 `translations`와 `*Translations` 입력은 알 수 없는 언어 key와 알 수 없는 하위
필드를 거부하는 닫힌 object다. key는 해당 회차의 supportedLanguages에 포함되어야 한다.
리소스가 draft인 동안 언어 값 전체를 null로 둘 수 있지만, non-null 언어 값에서는 각
쓰기 schema가 필수로 지정한 문자열을 빈 문자열로 보낼 수 없다. nullable 번역 필드는
명시적인 null을 허용한다. 문자열은 NFC로 정규화하고 앞뒤 공백만 있는 값은 거부한다.

## 6. 날짜와 시간

- 날짜는 YYYY-MM-DD 형식이다.
- 일시는 offset을 포함한 RFC 3339 형식으로 교환한다.
- 축제 기준 시간대는 Asia/Seoul이며 Festival.timeZone에도 명시한다.
- 클라이언트 기기의 현지 시간만으로 현재 공연을 판정하지 않는다.
- 현재 구간의 경계는 effectiveStartsAt <= serverTime < effectiveEndsAt이다.
- 홈과 시간표는 같은 게시 revision과 같은 서버 판정 결과를 사용한다.
- 여러 무대의 동시 공연을 배열로 표현한다.
- 시간 변경 시 scheduled 시각을 보존하고 effective 시각을 별도로 갱신한다.
- 취소된 공연은 시간표에서 제거하지 않고 CANCELED 상태로 반환한다.
- CANCELED와 DELAYED는 운영자가 명시한다. 나머지는 서버가 effective 시각을 기준으로
  SCHEDULED, LIVE, COMPLETED를 판정하며 현재 공연에는 LIVE만 포함한다.
- 관리자 PerformanceWrite는 `manualStatus=NORMAL|DELAYED|CANCELED`만 받는다. NORMAL이면
  공개 PerformanceStatus의 SCHEDULED·LIVE·COMPLETED를 서버가 계산하며 client가 이 세
  값을 저장 요청으로 보낼 수 없다. 행사 중 변경은 19.4의 live-state를 사용한다.
- 같은 무대의 유효 시간 중복은 관리자 저장 또는 게시 시 409 SCHEDULE_CONFLICT다.
- 일광 절약 시간 적용 여부와 무관하게 offset과 timeZone을 모두 보존한다.

PerformanceStatus:

- SCHEDULED
- LIVE
- DELAYED
- COMPLETED
- CANCELED

## 7. 페이지네이션, 정렬과 필터

### 7.1 공개 목록

아티스트, 공간, 공지는 cursor pagination을 사용한다.

- cursor: 서버가 발급한 opaque 문자열
- limit: 기본 20, 최소 1, 최대 100
- cursor 없이 요청하면 첫 페이지다.
- 첫 페이지에서 q를 제외한 category·type·area·상태·노출 시각 등 non-text filter를 먼저
  적용해 candidate collection snapshot을 만든다. 이 전체 candidate에 대해 하나의 content
  language와 fallbackApplied를 결정한 뒤, 그 확정 언어의 검색 index에만 q를 적용한다.
  q 결과나 페이지에 우연히 포함된 item으로 언어를 다시 고르지 않는다. candidate가 비었으면
  요청 선택 언어를 content language로 두고 fallbackApplied=false로 고정한다.
- 첫 페이지의 serverTime을 `evaluationTime`으로 고정한다. 노출 기간, 운영 상태,
  nextPerformance처럼 시간에 따라 달라지는 포함 여부·정렬·projection은 모든 후속
  페이지에서 이 시각으로 다시 계산한다.
- 첫 페이지는 이 collection에 영향을 주는 live-state apply·clear event의 composite
  `operationalStateRevision`도 고정한다.
- 언어와 q를 적용해 정해진 전체 결과 집합이 직접·간접 참조하는 media에 대해 22절의
  `mediaRightsValidator`를 첫 페이지에서 고정한다.
- cursor는 q를 포함한 모든 filter, sort, 요청 language, 결정된 content language, fallbackApplied,
  festivalId, `contentRevision`, evaluationTime, operationalStateRevision,
  mediaRightsValidator와 함께 서명하거나 검증한다.
- cursor 유효 기간은 evaluationTime부터 최대 10분이다. 후속 페이지 전에 cursor의
  contentRevision과 operationalStateRevision을 현재 public head 및 현재 이 collection의
  composite operational revision과 비교한다. 하나라도 달라졌거나 cursor가 만료됐으면 과거
  콘텐츠·override를 재생하지 않고 409 REVISION_CHANGED를 반환해 첫 페이지부터 다시 조회하게
  한다. 같을 때만 첫 페이지의 evaluationTime과 정렬 경계를 사용해 다음 page를 읽는다.
- 모든 후속 페이지는 첫 페이지와 같은 meta.language.content와 fallbackApplied를
  반환한다. 따라서 여러 페이지를 이어 붙인 목록에 서로 다른 언어가 섞이지 않는다.
- evaluationTime은 노출·운영 계산을 고정할 뿐 법적 media deadline을 과거 시각으로 되돌리지
  않는다. 후속 요청 시 현재 rightsEpoch·rightsPhase 또는 deny fence에서
  mediaRightsValidator가 달라졌으면 이전 URL·media projection을 재생하지 않고 409
  REVISION_CHANGED를 반환해 cursor 없는 첫 페이지부터 다시 시작하게 한다.
- meta.pagination.evaluationTime은 첫 페이지부터 마지막 페이지까지 같은 값을
  반환한다. meta.pagination.operationalStateRevision도 같은 opaque string을 반환한다. live-state
  apply·clear와 새 publish·unpublish는 관련 cache를 purge하고 이미 발급된 cursor를 위 비교로
  즉시 무효화한다.
- 다음 페이지가 없으면 nextCursor는 null이다.
- 정렬은 마지막 키에 id를 포함해 결정적으로 유지한다.

    {
      "data": {
        "items": []
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul",
        "contentRevision": "184",
        "snapshotUpdatedAt": "2030-09-18T18:04:30+09:00",
        "language": {
          "requested": "ko",
          "content": "ko",
          "fallbackApplied": false
        },
        "pagination": {
          "nextCursor": null,
          "hasNext": false,
          "limit": 20,
          "evaluationTime": "2030-09-18T18:05:00+09:00",
          "operationalStateRevision": "opstate-42"
        }
      }
    }

공연 목록, 지도 메타데이터·마커, 외부 링크와 Festival에 포함되는 라인업 카테고리는
축제 회차별 상한이 작은 전체 목록이며 pagination을 사용하지 않는다. 한 published public
head의 고정 상한은 LINEUP_CATEGORY 20개, STAGE 50개, PERFORMANCE 1,000개,
MAP_CATEGORY 100개, MAP_AREA 300개, MAP_MARKER 3,000개, EXTERNAL_LINK 200개다.
FestivalSpace 상세의 고정 상한은 space 하나당 MENU_SECTION 20개,
MENU_ITEM_PER_SECTION 100개, MENU_ITEM_PER_SPACE 합계 500개, SPACE_EVENT 100개다.
삭제·hidden·unpublished 항목은 공개 응답 수에는 포함하지 않지만
desired snapshot 검증에서는 최종 공개 집합으로 정확히 계산한다. validate는 초과 type,
`actualCount`, `maximumCount`를 가진 ValidationIssue code
`PUBLIC_COLLECTION_LIMIT_EXCEEDED`를 반환하고 valid=false로 만들며 publish·schedule·activate
worker도 commit 직전에 같은 상수로 전체 head를 다시 검사한다. 이 상수는 OpenAPI `maxItems`,
게시 validator, fixture와 피크 부하 시험에 하나의 설정 source로 사용하고 런타임 환경 변수로
서로 다르게 완화하지 않는다. 상한 변경은 공개 성능 예산과 consumer 계약을 다시 검증하는
명세 변경이다.

### 7.2 관리자 목록

관리자 목록은 1부터 시작하는 page와 size를 사용한다.

- page 기본 1
- size 기본 50, 최대 200
- totalElements, totalPages, page, size를 meta.pagination에 반환한다.
- sort는 field,asc 또는 field,desc 형식이며 허용 필드만 받는다.

## 8. 공통 리소스

### 8.1 Media

| 필드 | 타입 | 규칙 |
|---|---|---|
| id | UUID | 안정적 미디어 ID |
| url | string | HTTPS CDN URL |
| width | integer | 이미지 원본 너비 |
| height | integer | 이미지 원본 높이 |
| mimeType | enum | `image/jpeg`, `image/png`, `image/webp` 중 서버가 검증한 값 |
| alt | string | 콘텐츠 언어의 대체 텍스트 |
| blurHash | string, nullable | 선택적 로딩 placeholder |
| availableUntil | datetime, nullable | 권리상 제공 만료 시각; 무기한이면 null |

### 8.2 관리자 revision과 게시 수명주기

관리자 리소스는 편집 중인 revision과 공개 중인 revision을 분리한다. 공개 중인
리소스를 수정해도 공개 응답은 기존 `publishedRevision`을 계속 사용하고, 게시 성공
시에만 pointer를 새 불변 snapshot으로 원자 전환한다.

| 필드 | 타입 | 규칙 |
|---|---|---|
| adminVersion | integer | 콘텐츠·수명주기·예약 변경마다 증가하는 낙관 잠금 version |
| workingRevision | integer | PATCH·PUT이 변경하는 단조 증가 편집 revision |
| publishedRevision | integer, nullable | 현재 공개 snapshot; 게시 이력이 없으면 null |
| workingStatus | WorkingStatus | 편집본의 상태 |
| publicationState | PublicationState | 공개 pointer 상태 |
| scheduledAction | enum, nullable | PUBLISH 또는 UNPUBLISH |
| scheduledBatchId | UUID, nullable | 예약을 소유하는 publication batch |
| scheduledApplyAt | datetime, nullable | 예약 적용 시각 |
| pendingPublicationOperationId | UUID, nullable | 즉시 또는 예약 apply worker의 target reservation |
| pendingOperationalOperationId | UUID, nullable | live·emergency worker의 우선순위 reservation |
| translationStatus | TranslationStatus | 필수 언어·필드 완성도 |
| createdBy / updatedBy | UUID | 관리자 계정 ID |
| publishedBy | UUID, nullable | 현재 공개 revision 게시 실행자; 비공개면 null |
| createdAt / updatedAt | datetime | 편집 이력 시각 |
| publishedAt | datetime, nullable | 현재 공개 revision 게시 시각 |

관리자 상세 응답은 위 필드와 `ETag: "admin-{adminVersion}"`를 반환한다. 쓰기,
예약, 게시, 게시 취소, archive와 restore는 이 ETag를 `If-Match`로 사용한다.
adminVersion은 workingRevision이 바뀌지 않는 상태 변경에도 증가한다.
`translationStatus`는 COMPLETE,
INCOMPLETE, FALLBACK_ALLOWED 중 하나이며 누락 필드 상세는 번역 현황 API가 제공한다.

WorkingStatus:

- DRAFT
- CLEAN
- ARCHIVED

PublicationState:

- NEVER_PUBLISHED
- PUBLISHED
- UNPUBLISHED

공개 중인 리소스를 편집하면 workingStatus=DRAFT, publicationState=PUBLISHED,
workingRevision > publishedRevision으로 표현한다. 게시 직후처럼 편집본과 공개본이
같으면 workingStatus=CLEAN이다. 공개 API는
publicationState=PUBLISHED이고 공개 기간 안에 있는 publishedRevision만 반환한다.
세 예약 필드는 모두 null이거나 모두 non-null이어야 하며 workingStatus와 독립이다.
두 pending operation ID는 평상시 동시에 non-null일 수 없다. 19.4의 emergency priority
fence가 APPLYING batch를 선점하는 짧은 구간에서만 둘 다 존재할 수 있고, batch worker는
그 상태를 commit하지 못한다. 모든 terminal 전이는 자기 pending ID를 지운다.

### 8.3 OperatingStatus

- UPCOMING
- OPEN
- PAUSED
- CLOSED
- CANCELED

상태는 운영자가 명시적으로 설정한 값과 운영 시간 계산을 혼동하지 않는다.
응답은 operatingStatus와 nextStatusAt을 함께 제공할 수 있다.

OperatingPeriod는 필수 `startsAt`, `endsAt`을 가진다. 두 값은 Asia/Seoul offset을 포함하고
startsAt < endsAt이어야 하며 같은 리소스 안에서 겹칠 수 없다. FestivalSpace는 게시 시
하나 이상의 기간이 필요하고, 독립 시설 MapMarker는 운영 시간이 없으면 빈 배열과
nullable operatingStatus·nextStatusAt을 반환할 수 있다.

운영자가 설정하는 OperatingOverride는 NORMAL, PAUSED, CANCELED다. NORMAL일 때 서버는
serverTime이 최초 기간 전이면 UPCOMING, 기간 안이면 OPEN, 기간 사이 또는 마지막 기간
후이면 CLOSED를 계산한다. nextStatusAt은 다음 시작·종료 경계이고 더 이상 경계가 없으면
null이다. PAUSED와 CANCELED override는 시간 계산보다 우선하며 nextStatusAt은 null이다.
공개 응답에는 계산 결과인 OperatingStatus만 반환하고 override 자체는 노출하지 않는다.

## 9. 공개 API 목록

| 번호 | Method | Path | 설명 |
|---:|---|---|---|
| 1 | GET | /festivals/current | 현재 서비스 회차 |
| 2 | GET | /festivals/{festivalId} | 축제 기본 정보 |
| 3 | GET | /festivals/{festivalId}/home | 홈 집계 |
| 4 | GET | /festivals/{festivalId}/artists | 라인업 목록 |
| 5 | GET | /festivals/{festivalId}/artists/{artistId} | 아티스트 상세 |
| 6 | GET | /festivals/{festivalId}/performances | 공연 목록 |
| 7 | GET | /festivals/{festivalId}/performances/{performanceId} | 공연 상세 |
| 8 | GET | /festivals/{festivalId}/spaces | 부스·주점·플리마켓 목록 |
| 9 | GET | /festivals/{festivalId}/spaces/{spaceId} | 공간 상세 |
| 10 | GET | /festivals/{festivalId}/map | 지도 메타데이터 |
| 11 | GET | /festivals/{festivalId}/map/markers | 마커 목록 |
| 12 | GET | /festivals/{festivalId}/map/markers/{markerId} | 마커 상세 |
| 13 | GET | /festivals/{festivalId}/notices | 공지 목록 |
| 14 | GET | /festivals/{festivalId}/notices/{noticeId} | 공지 상세 |
| 15 | GET | /festivals/{festivalId}/external-links | 공식 외부 링크 |
| 16 | GET | /festivals/{festivalId}/chatbot/configuration | 챗봇 공개 화면 구성 |
| 17 | POST | /festivals/{festivalId}/chatbot/messages | 챗봇 질의 |

표의 path에는 공통 Base path /api/v1이 생략되어 있다.

## 10. 축제와 홈

### 10.1 GET /festivals/current

관리자가 명시적으로 지정한 유일한 active festival의 canonical festivalId만 반환한다.
날짜가 가깝다는 이유로 다른 published 회차를 자동 선택하지 않는다. active pointer가
없거나 대상 회차의 필수 공개 snapshot을 읽을 수 없으면 404 FESTIVAL_NOT_PUBLISHED다.
selectionReason은 선택 알고리즘이 아니라 active 회차의 서버 시각 기준 상태다.

    {
      "data": {
        "festivalId": "7ef50e0b-49c1-465f-a098-796d89f39c1a",
        "selectionReason": "LIVE",
        "resourcePath": "/api/v1/festivals/7ef50e0b-49c1-465f-a098-796d89f39c1a"
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul"
      }
    }

selectionReason:

- UPCOMING
- LIVE
- ENDED

### 10.2 GET /festivals/{festivalId}

    {
      "data": {
        "id": "7ef50e0b-49c1-465f-a098-796d89f39c1a",
        "slug": "stable-public-slug",
        "year": 2030,
        "name": "승인된 축제명",
        "startsAt": "2030-09-18T12:00:00+09:00",
        "endsAt": "2030-09-20T23:00:00+09:00",
        "timeZone": "Asia/Seoul",
        "status": "UPCOMING",
        "defaultLanguage": "ko",
        "supportedLanguages": ["ko", "en", "zh"],
        "translationPolicy": {
          "requiredLanguages": ["ko"],
          "fallbackLanguage": "ko",
          "fallbackAllowedFor": ["en", "zh"]
        },
        "heroMedia": null,
        "lineupCategories": [],
        "capabilities": {
          "artistSearch": false,
          "artistDetail": false,
          "artistSocialLinks": false,
          "artistTracks": false,
          "externalLinks": false,
          "publicFaq": false,
          "chatbot": true
        },
        "updatedAt": "2030-09-01T10:00:00+09:00"
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul",
        "contentRevision": "184",
        "snapshotUpdatedAt": "2030-09-18T18:04:30+09:00",
        "language": {
          "requested": "ko",
          "content": "ko",
          "fallbackApplied": false
        }
      }
    }

FestivalStatus:

- UPCOMING
- LIVE
- ENDED

DRAFT와 ARCHIVED는 관리자 상태이며 공개 응답에 나타나지 않는다.
capabilities 변경은 제품 승인 근거를 관리자 요청에 연결하고 감사 로그에 남긴다.

Festival data의 필수 필드는 `id`, `slug`, `year`, `name`, `startsAt`, `endsAt`, `timeZone`,
`status`, `defaultLanguage`, `supportedLanguages`, `translationPolicy`, `heroMedia`,
`lineupCategories`, `capabilities`, `updatedAt`이다. heroMedia만 nullable이며
lineupCategories는 11.1의 LineupCategory 배열로 값이 없으면 빈 배열이다. status는
serverTime < startsAt이면 UPCOMING, startsAt <= serverTime < endsAt이면 LIVE, 그 이후면
ENDED로 계산한다.

capabilities는 필수 boolean key `artistSearch`, `artistDetail`, `artistSocialLinks`,
`artistTracks`, `externalLinks`, `publicFaq`, `chatbot`을 모두 가지며 알 수 없는 key를
반환하지 않는다. supportedLanguages와 translationPolicy는 5절의 계약을 따른다.
`artistSocialLinks=true` 또는 `artistTracks=true`이면 artistDetail도 true여야 하고,
`publicFaq=true`이면 externalLinks도 true여야 한다. Festival PATCH와 게시 검증 모두
도달할 수 없는 capability 조합을 422로 거부한다.

### 10.3 GET /festivals/{festivalId}/home

Query:

| 이름 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| lang | language | 아니요 | 응답 언어 |

    {
      "data": {
        "festival": {},
        "currentItems": [],
        "currentItemTotal": 0,
        "currentItemHasMore": false,
        "upcomingPerformances": [],
        "importantNotices": [],
        "highlightArtists": [],
        "quickLinks": [],
        "configuration": {
          "currentItemTypes": ["PERFORMANCE"],
          "currentItemLimit": 20,
          "upcomingPerformanceLimit": 6,
          "importantNoticeLimit": 3,
          "highlightArtistLimit": 0
        }
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul",
        "contentRevision": "184",
        "snapshotUpdatedAt": "2030-09-18T18:04:30+09:00",
        "language": {
          "requested": "ko",
          "content": "ko",
          "fallbackApplied": false
        }
      }
    }

`data.festival`은 10.2의 Festival data와 동일한 필드·nullable 규칙을 같은
contentRevision에서 반환한다. 홈 전용으로 축약하거나 별도 capability 값을 만들지 않는다.
Home data는 필수 `festival`, `currentItems`, `currentItemTotal`, `currentItemHasMore`,
`upcomingPerformances`, `importantNotices`, `highlightArtists`, `quickLinks`, `configuration`만
가지는 닫힌 schema다. currentItemTotal은 0 이상의 integer이고 currentItemHasMore는
boolean이다. HomeConfiguration은 필수 `currentItemTypes`, `currentItemLimit`,
`upcomingPerformanceLimit`, `importantNoticeLimit`, `highlightArtistLimit`만 가진 읽기 전용
projection이며 각 값의 범위는 HomeConfigurationWrite와 같다.

- currentItems 후보는 effectiveStartsAt, typePriority, type별 내부 order, id 순으로
  정렬하고 앞에서부터 currentItemLimit까지만 반환한다. typePriority는 PERFORMANCE=0,
  SPACE_EVENT=1, CURATED=2이고 내부 order는 PERFORMANCE의 performance.order,
  CURATED의 구성 order이며 SPACE_EVENT는 event.order다. type은 PERFORMANCE,
  SPACE_EVENT, CURATED다. CURATED는 홈 구성에서 직접 관리하며 sourceId 대신 승인된
  action을 가진다. currentItemTotal은 잘리기 전 후보의 정확한 수이고
  currentItemHasMore는 currentItemTotal > currentItems.length일 때만 true다.
- 어떤 type을 현재 진행 항목에 포함할지는 승인된 home configuration의
  currentItemTypes로 정한다. API schema가 type을 지원한다는 사실만으로 공개 노출을
  승인하지 않는다.
- PERFORMANCE는 status가 LIVE일 때만, SPACE_EVENT와 CURATED는 status가 ACTIVE이고
  effectiveStartsAt <= serverTime < effectiveEndsAt일 때만 currentItems에 포함한다.
  DELAYED와 CANCELED 항목은 현재 진행 중으로 표시하지 않는다. SPACE_EVENT는 상위
  FestivalSpace.operatingStatus도 OPEN이어야 한다. 상위 공간이 UPCOMING, PAUSED, CLOSED,
  CANCELED이면 event 자체의 시간 상태가 ACTIVE여도 currentItems에서 제외한다. Space
  live-state commit은 이 집합을 다시 계산하고 home cache를 즉시 무효화한다.
- PERFORMANCE 항목의 status는 PerformanceStatus다. SPACE_EVENT와 CURATED의 status는
  HomeItemStatus를 사용한다.
- upcomingPerformances는 PerformanceSummary, importantNotices는 NoticeSummary,
  highlightArtists는 ArtistSummary 배열이다. 각각 effectiveStartsAt 오름차순,
  emergency·important·publishedAt 내림차순, featured order·id 순으로 정렬한다.
  upcoming 공연 후보는 status가 SCHEDULED 또는 DELAYED이고 effectiveStartsAt >=
  serverTime인 공연이다. CANCELED·COMPLETED·LIVE는 제외한다. 각 무대 후보의 첫 공연을
  effectiveStartsAt·stage.order·performance.order·id 순으로 먼저 선택하고, limit보다 무대가 많으면 그
  순서에서 자른다. 남은 자리는 아직 선택하지 않은 전체 후보를 같은 순서로 채워 중복을
  허용하지 않은 뒤 upcomingPerformanceLimit을 적용한다.
  importantNotices와 highlightArtists는 각각 importantNoticeLimit,
  highlightArtistLimit을 적용한다. limit이 0이면 해당 배열은 비운다.
- importantNotices 후보는 important=true 또는 emergency=true인 현재 노출 공지이고,
  highlightArtists 후보는 featured=true인 공개 아티스트다.
- PERFORMANCE의 id와 sourceId는 performanceId이고 상세로 이동한다. SPACE_EVENT의 id와
  sourceId는 eventId이며 action은 상위 space 상세로 이동한다. CURATED의 id는 홈 구성
  item ID이고 sourceId는 null이다.
- 중요 공지와 현재 공연은 동일 contentRevision으로 묶는다.
- 승인되지 않은 FUN, 스탬프, 고등학생 안내 항목은 quickLinks에 포함하지 않는다. 고등학생
  안내는 전용 계약이 추가되기 전까지 CURATED나 OPEN_EXTERNAL_LINK로 우회하지 않는다.

HomeCurrentItem의 공통 필드는 `id`, nullable `sourceId`, `type`, `title`, nullable `description`,
`effectiveStartsAt`, `effectiveEndsAt`, `status`, nullable `spaceId`, nullable
`mapMarkerId`, nullable `operationalUpdatedAt`, `action`이다. type별 규칙:

| type | 필수 연결 | 허용 action |
|---|---|---|
| PERFORMANCE | `sourceId=performanceId` | 서버가 만드는 `OPEN_PERFORMANCE` |
| SPACE_EVENT | `sourceId=spaceEventId`, `spaceId` | `OPEN_FESTIVAL_SPACE`, `OPEN_MAP` |
| CURATED | `sourceId=null`, 관리자 입력 `action` | 아래 HomeAction 중 승인된 값 |

PERFORMANCE의 operationalUpdatedAt은 원 Performance 값을, SPACE_EVENT는 상위
FestivalSpace 값을 projection하고 CURATED는 null이다.

HomeItemStatus는 SCHEDULED, ACTIVE, DELAYED, COMPLETED, CANCELED다. SpaceEvent와
CURATED가 이 enum을 사용하며, 현재 항목 포함 여부와 시간 경계는 10.3의 규칙을 따른다.
PerformanceWrite나 HomeConfigurationWrite는 공연 카드 action을 받지 않는다. PERFORMANCE
current item의 action은 항상 같은 performanceId의 OPEN_PERFORMANCE로 projection한다.

HomeQuickLink는 필수 `id`, `label`, `order`, `action`과 nullable `description`,
`icon: HomeIcon`을
가진다. quickLinks는 order, id 순으로 반환하며 값이 없으면 null이 아니라 빈 배열이다.

HomeIcon은 필수 `source: BUILTIN|MEDIA`, nullable `builtinKey`, nullable `media`, 필수
번역 `alt`를 가진다. BUILTIN이면 builtinKey는 NOTICE, CHATBOT, FAQ, LINK, ARTIST,
TIMETABLE, SPACE, MAP 중 하나이고 media는 null이다. MEDIA이면 READY Media만 제공하고
builtinKey는 null이다.
아이콘 문맥의 접근 가능한 이름은 outer HomeIcon.alt가 authoritative하다. nested
Media.alt는 원본 asset 메타데이터일 뿐 같은 요소에서 함께 읽히게 렌더링하지 않는다.
HomeAction은 임의 URL이나 임의 client route 문자열이 아니라 다음 tagged union이다.

- `OPEN_NOTICE_LIST`: 추가 payload 없음
- `OPEN_CHATBOT`: 추가 payload 없음; `capabilities.chatbot=true` 필수
- `OPEN_EXTERNAL_LINK`: `externalLinkId` 필수
- `OPEN_ARTIST`: `artistId` 필수
- `OPEN_PERFORMANCE`: `performanceId` 필수
- `OPEN_TIMETABLE`: payload 없음 또는 선택적 `date`, `stageId`, `performanceId`
- `OPEN_FESTIVAL_SPACE`: `spaceId` 필수
- `OPEN_MAP`: payload는 선택이며, 있으면 `markerId`, `areaIds`, `categoryCodes` 중 하나
  이상. 위치가 있는 HomeCurrentItem에서 사용하면 `markerId`가 필수다.

각 variant는 필수 `type`과 그 variant가 요구할 때만 `payload`를 가지는 닫힌 object다.
`OPEN_NOTICE_LIST`와 `OPEN_CHATBOT`은 payload property 자체를 보내지 않는다. 나머지 ID는
UUID이고 같은 festival의 현재 published public snapshot에 존재해야 하며 숨김·미게시 대상은
참조할 수 없다. `OPEN_TIMETABLE`은 payload 생략이 root 이동이고, payload가 있으면 빈 object를
허용하지 않는다. date는 TimetableConfiguration.dayViews에 있는 ISO date다. performanceId가
있으면 stageId·date가 함께 제공된 경우 그 공개 performance의 stage와 day view에 모두
일치해야 하고, stageId만 있으면 현재 공개 stage여야 한다.

`OPEN_MAP` payload의 markerId는 publicly addressable marker, areaIds와 categoryCodes는 각각
중복 없는 1개 이상의 UUID·code 배열이며 현재 MapSnapshot에 존재해야 한다. 배열 크기는
FilterConfiguration.maxAreaSelections·maxCategorySelections를 넘을 수 없다. markerId와
areaIds를 함께 보내면 marker의 non-null areaId가 배열에 포함되어야 하고, categoryCodes를
함께 보내면 marker의 category code가 배열에 포함되어야 한다. 알 수 없는 payload field,
빈 배열, 서로 모순되는 복수 field는 저장과 게시 검증에서 422 VALIDATION_ERROR다.

Home의 기능 활성 여부는 Festival.capabilities가 유일한 권위값이다. home
configuration은 순서·목록별 limit·현재 항목 type만 관리하며 `chatbotEnabled` 같은 중복 flag를
갖지 않는다. 서버는 capability가 꺼진 기능의 quick link와 콘텐츠를 조립 단계에서
제거한다.

## 11. 라인업

### 11.1 Artist

Artist는 인물·팀 정보이며 단일 공연 날짜를 소유하지 않는다. 출연 시간과 장소는
Performance가 관리한다. 한 Artist는 여러 Performance에, 한 Performance는 여러
Artist에 연결될 수 있다.

| 필드 | 타입 | 설명 |
|---|---|---|
| id | UUID | 아티스트 ID |
| name | string | 승인된 표시명 |
| introduction | string, nullable | 소개 |
| profileMedia | Media, nullable | 프로필 이미지 |
| category | LineupCategory, nullable | 승인된 경우에만 쓰는 데이터 기반 분류 |
| featured | boolean | 홈·라인업 강조 여부 |
| socialLinks | array | capability가 활성화된 경우 승인된 외부 링크 |
| tracks | array | capability가 활성화된 경우 승인된 추천곡 메타데이터 |
| performances | ArtistPerformanceSummary array | 연결된 공개 공연 요약 |
| order | integer | 운영자가 정한 기본 순서 |
| updatedAt | datetime | 마지막 게시 수정 |

LineupCategory는 id, code, label, order를 가진 축제 운영 데이터다. 공식 분류가 확정되지
않은 명칭을 전역 enum으로 만들지 않는다.

ArtistSocialLink는 필수 `platform`, `label`, `url`, `external=true`를 가진다. platform은
INSTAGRAM, YOUTUBE, TIKTOK, X, OTHER이며 url은 승인된 HTTPS URL이다. ArtistTrack은 필수
`title`, `artistLabel`, `provider`, `url`과 nullable `artworkMedia`를 가진다. provider는
SPOTIFY, YOUTUBE, APPLE_MUSIC, OTHER이고 url은 승인된 HTTPS URL이다. socialLinks와
tracks는 각각 최대 10개와 3개이며 값이 없으면 빈 배열이다.

### 11.2 GET /festivals/{festivalId}/artists

Query:

| 이름 | 타입 | 설명 |
|---|---|---|
| categoryId | UUID | 라인업 분류 |
| date | date | 해당 날짜 공연이 있는 아티스트 |
| q | string | 이름 검색, 1~100자 |
| cursor | string | 다음 페이지 |
| limit | integer | 1~100 |
| lang | language | 응답 언어 |

기본 정렬은 category가 있는 항목, category.order, artist.order, artist.id 순이며 nullable
category는 항상 마지막이다. 검색어는 Unicode 정규화, 길이 제한, locale별 검색 인덱스를
적용한다.

목록의 `data.items`는 ArtistSummary 배열이다. ArtistSummary의 필수 필드는 `id`,
`name`, `profileMedia`, `category`, `featured`, `nextPerformance`, `order`, `updatedAt`이다.
`profileMedia`, `category`, `nextPerformance`는 null일 수 있고 나머지는 null이 아니다.
목록에는 introduction, socialLinks, tracks 전체를 싣지 않는다.

ArtistSummary.nextPerformance는 null 또는 `id`, `title`, `effectiveStartsAt`,
`effectiveEndsAt`, `status`, `stage: StageSummary`를 가진 ArtistPerformanceSummary다.
profileMedia는 8.1의 Media이고 category는 `id`, `code`, `label`, `order`를 가진다.
nextPerformance는 10.3과 같은 SCHEDULED·DELAYED 및 serverTime 기준 후보 중 첫 공연이다.
artists 목록에 date filter가 있으면 해당 축제 local date의 dayView 안에 시작하는 후보로
한정한다. 후보가 없으면 null이다.

    {
      "data": {
        "items": []
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul",
        "contentRevision": "184",
        "snapshotUpdatedAt": "2030-09-18T18:04:30+09:00",
        "language": {
          "requested": "ko",
          "content": "ko",
          "fallbackApplied": false
        },
        "pagination": {
          "nextCursor": null,
          "hasNext": false,
          "limit": 20,
          "evaluationTime": "2030-09-18T18:05:00+09:00",
          "operationalStateRevision": "opstate-42"
        }
      }
    }

artistSearch capability가 비활성화된 회차에서 q를 보내면 400
FILTER_NOT_SUPPORTED를 반환한다.

### 11.3 GET /festivals/{festivalId}/artists/{artistId}

artistDetail capability가 활성화된 회차에서만 Artist 전체 필드와 연결된 공개
Performance 요약을 반환한다. capability가 비활성화되면 404 FEATURE_NOT_ENABLED,
활성화되어 있지만 대상이 없거나 비공개면 404 ARTIST_NOT_FOUND를 반환한다.

socialLinks와 tracks는 각각의 capability가 활성화된 경우에만 값을 반환하며, 그렇지
않으면 빈 배열이다. 외부 링크는 HTTPS만 허용하고 rel=noopener 적용에 필요한 external
값을 함께 제공한다. 추천곡은 메타데이터와 승인된 URL만 반환하며 미디어를 API가
재배포하지 않는다.

상세 `data`는 11.1의 Artist 필드를 모두 가지며 `introduction`, `profileMedia`,
`category`만 nullable이다. performances는 effectiveStartsAt, id 순의
ArtistPerformanceSummary 배열이다. socialLinks, tracks, performances는 값이 없을 때 빈
배열을 반환한다.

## 12. 공연 시간표

### 12.1 Performance

StageSummary는 필수 id, name, order와 nullable mapMarkerId를 가진다. `mapMarkerId`는
MapMarker.stageId 관계에서 파생되며 marker가 게시되고 category.visible=true일 때만
non-null인 읽기 전용 projection이다. 같은 무대의 여러
Performance가 하나의 공연장 marker를 공유한다.

| 필드 | 타입 | 설명 |
|---|---|---|
| id | UUID | 공연 ID |
| title | string | 공연명 |
| stage | StageSummary | 무대 |
| artists | ArtistSummaryLite array | 출연자 ID·표시명 |
| scheduledStartsAt | datetime | 최초 확정 시작 |
| scheduledEndsAt | datetime | 최초 확정 종료 |
| effectiveStartsAt | datetime | 현재 적용 시작 |
| effectiveEndsAt | datetime | 현재 적용 종료 |
| status | PerformanceStatus | 공연 상태 |
| statusMessage | string, nullable | 승인된 지연·취소 안내 |
| description | string, nullable | 공연 설명 |
| order | integer | 같은 무대·시각의 결정적 표시 순서 |
| updatedAt | datetime | 마지막 게시 수정 |
| operationalUpdatedAt | datetime, nullable | 마지막 live-state 적용·해제 시각 |

PerformanceSummary의 필수 필드는 `id`, `title`, `stage`, `artists`,
`effectiveStartsAt`, `effectiveEndsAt`, `status`, `order`, `updatedAt`, nullable
`operationalUpdatedAt`이다. artists는 `id`, `name`만 가진 ArtistSummaryLite 배열이다. 지도 연결은 중복 root 필드 없이
stage.mapMarkerId만 사용한다.

### 12.2 TimetableConfiguration

Figma의 `DAY 1/2/3`, 17:00~22:00, 30분 눈금은 화면 예시일 뿐 고정값이 아니다.
서버는 게시된 다음 설정을 공연과 같은 revision으로 반환한다.

| 필드 | 타입 | 설명 |
|---|---|---|
| dayViews | TimetableDayView array | 화면 날짜 탭과 표시 window |
| gridIntervalMinutes | integer | 눈금 간격; 5~120 |
| stagePresentation | enum | `TABS`, `COLUMNS`, `GROUPED` |
| currentTimeLineEnabled | boolean | 서버 시각 기준 현재 시각선 표시 가능 여부 |
| updatedAt | datetime | 현재 게시 설정 시각 |

TimetableDayView의 필수 non-null 필드는 `date`, `label`, `windowStartsAt`,
`windowEndsAt`, `order`다. dayViews는 1~31개이며 date와 order는 각각 배열 안에서
유일하고 order, date 순으로 반환한다. windowStartsAt과 windowEndsAt은 축제 시간대
offset을 포함하고 windowStartsAt < windowEndsAt이어야 한다. windowStartsAt의
Asia/Seoul local date는 date와 같아야 하며 windowEndsAt은 같은 날 또는 바로 다음 날일
수 있지만 구간은 24시간을 넘을 수 없다. 두 시각과 date는 축제 기간 안에 있어야 한다.
모든 dayView의 `[windowStartsAt, windowEndsAt)` 구간은 서로 겹칠 수 없다.

공개할 Performance의 effective 구간은 date가 같은 하나의 dayView window 안에 완전히
포함되어 정확히 하나의 dayView에 속해야 하며, 벗어나거나 둘 이상에 걸리면 게시 검증은
오류를 반환하고 자동으로 잘라내지 않는다.

### 12.3 GET /festivals/{festivalId}/performances

Query:

| 이름 | 타입 | 설명 |
|---|---|---|
| date | date | 축제 시간대의 날짜 |
| stageId | UUID, 반복 가능 | 무대 필터 |
| status | PerformanceStatus, 반복 가능 | 상태 필터 |
| lang | language | 응답 언어 |

date를 생략하면 축제 전체 공연을 반환한다. 기본 정렬은 effectiveStartsAt, stage.order,
performance.order, performance.id 순이다.

    {
      "data": {
        "performances": [],
        "stages": [],
        "currentPerformanceIds": [],
        "configuration": {
          "dayViews": [],
          "gridIntervalMinutes": 30,
          "stagePresentation": "TABS",
          "currentTimeLineEnabled": true,
          "updatedAt": "2030-09-01T10:00:00+09:00"
        }
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul",
        "contentRevision": "184",
        "snapshotUpdatedAt": "2030-09-18T18:04:30+09:00",
        "language": {
          "requested": "ko",
          "content": "ko",
          "fallbackApplied": false
        }
      }
    }

data는 알 수 없는 필드를 허용하지 않는 필수 `performances: Performance[]`,
`stages: StageSummary[]`, `currentPerformanceIds: UUID[]`,
`configuration: TimetableConfiguration`을 가진다. performances는 12.1의 축약 Summary가
아니라 scheduled 시각, statusMessage, description, updatedAt과 operationalUpdatedAt을 모두
포함한 Performance 전체 객체다. currentPerformanceIds는 중복 없는 UUID 배열이다.

currentPerformanceIds는 서버 시각 기준이며 홈 currentItems 중 PERFORMANCE 항목과
동일한 판정 규칙을 사용한다. 화면 날짜·순서의 유일한 원본은
configuration.dayViews이며 별도 dates 배열을 반환하지 않는다.

performances는 date, stageId, status 필터를 모두 만족하는 항목만 반환하고
currentPerformanceIds는 그 배열 안의 LIVE id와 정확히 일치한다. stages는 date·status로
줄이지 않는다. stageId가 있으면 요청한 공개 stage만, 없으면 모든 공개 stage를 order,
id 순으로 반환해 빈 시간대에도 탭 구성이 안정적으로 유지되게 한다.

### 12.4 GET /festivals/{festivalId}/performances/{performanceId}

data는 12.1 Performance의 모든 필드와 필수 nullable `previousPerformanceId`,
`nextPerformanceId`를 반환한다. artists는 `id`, `name`의 ArtistSummaryLite 배열이고 값이
없으면 빈 배열이다. 이전·다음 ID는 같은 무대의 공개 공연을 effectiveStartsAt,
performance.order, performance.id 순으로
정렬해 계산하며 경계에 없으면 null이다.

## 13. 부스 & 마켓

### 13.1 FestivalSpace

SpaceType:

- BOOTH
- PUB
- FLEA_MARKET

| 필드 | 타입 | 설명 |
|---|---|---|
| id | UUID | 공간 ID |
| type | SpaceType | 부스·주점·플리마켓 |
| name | string | 표시명 |
| operatorName | string, nullable | 운영 주체 |
| description | string, nullable | 소개 |
| operatingPeriods | OperatingPeriod array | 시작·종료 일시 목록 |
| operatingStatus | OperatingStatus | 현재 운영 상태 |
| nextStatusAt | datetime, nullable | 다음 상태 전환 예상 |
| operatingStatusMessage | string, nullable | 일시 중단·취소 등 승인된 운영 안내 |
| contact | SpaceContact, nullable | 공개 승인된 문의 정보 |
| media | SpaceMedia array | 역할·순서가 있는 대표·갤러리·메뉴 이미지 |
| area | MapAreaSummary, nullable | 지도 구역 |
| mapMarkerId | UUID, nullable | 안정적 마커 연결 |
| menuSections | MenuSection array | 구조화 메뉴·품목 |
| events | SpaceEvent array | 승인된 현장 이벤트 |
| order | integer | 운영자가 정한 기본 순서 |
| updatedAt | datetime | 마지막 게시 수정 |
| operationalUpdatedAt | datetime, nullable | 마지막 live-state 적용·해제 시각 |

SpaceContact는 필수 `type: PHONE|EMAIL|HTTPS_URL`, `label`, `displayValue`, `href`를 가진다.
href scheme은 type에 따라 tel, mailto, https 중 정확히 하나이고 서버는 표시값·링크를
검증한다. 개인 연락처는 운영 승인과 공개 목적을 확인한 값만 게시한다.

SpaceEvent의 필수 필드는 `id`, `title`, nullable `description`, `effectiveStartsAt`, `effectiveEndsAt`,
`status: HomeItemStatus`, `action`, `order`이며 description과 action만 nullable이다. action이 있으면
10.3 HomeAction 중 `OPEN_FESTIVAL_SPACE` 또는 `OPEN_MAP`만 허용하고 연결된 상위 space
또는 그 mapMarkerId를 가리켜야 한다. 저장과 게시 검증 모두 다른 action type을 거부한다.
상태는 manualStatus=NORMAL일 때 시각으로 SCHEDULED, ACTIVE,
COMPLETED를 계산하고 DELAYED·CANCELED만 운영자가 명시한다. events는 effectiveStartsAt,
order, id 순이며 값이 없으면 빈 배열이다.

SpaceEvent의 effective 구간은 상위 FestivalSpace의 하나 이상의 operatingPeriod 구간 안에
완전히 포함되어야 하며 여러 기간 사이를 걸칠 수 없다. 부모의 live-state가 PAUSED 또는
CANCELED여도 저장된 event 상태를 덮어쓰지 않고, 홈 조립 시 10.3 규칙으로 노출을 제외한다.

SpaceEvent는 해당 FestivalSpace의 mapMarkerId를 재사용하며 별도 장소 좌표를 만들지
않는다. 홈에 투영할 때 event.action이 null이면 서버가 상위 space를 여는
OPEN_FESTIVAL_SPACE action을 만든다. 독립 게시 대상이 아니고 상위 SPACE의
workingRevision, publishedRevision과 함께 저장·검증·게시한다.
한 FestivalSpace에 게시할 수 있는 SpaceEvent는 최대 100개다.

SpaceMedia는 필수 `media: Media`, `role: HERO|GALLERY|MENU_BOARD`, `order`를 가진다. 한
space에는 HERO가 최대 하나이고 목록 thumbnail은 그 HERO의 Media 또는 null이다. 배열은
role, order, media.id 순으로 결정적으로 정렬한다.

FestivalSpace.mapMarkerId와 area는 MapMarker.linkedSpaceId와 그 marker의 areaId에서
파생되는 읽기 전용 projection이다. 공개 snapshot에 연결 marker가 없거나 marker의
category.visible=false이면 둘 다 null이다.
Stage와 FestivalSpace 쓰기 요청은 mapMarkerId나 areaId를 받지 않는다.

### 13.2 MenuSection과 MenuItem

MenuSection은 id, name, order, items를 가진다. 한 FestivalSpace의 section은 최대 20개이고
section 하나의 items는 최대 100개이며, 모든 section의 item 합계는 space당 최대 500개다.
배열은 section의 order, id 및 item의 order, id 순으로 결정적으로 정렬한다.

MenuItem:

| 필드 | 타입 | 설명 |
|---|---|---|
| id | UUID | 품목 ID |
| name | string | 품목명 |
| description | string, nullable | 설명 |
| price.amount | integer, nullable | 정수 금액 |
| price.currency | string | KRW |
| priceLabel | string, nullable | amount가 없을 때의 승인된 `무료`·`현장 문의` 등 표시 문구 |
| availability | enum | AVAILABLE, SOLD_OUT, UNAVAILABLE |
| featured | boolean | 목록 menuHighlights 포함 여부 |
| allergens | string array | 승인된 알레르기 정보 |
| media | Media, nullable | 품목 이미지 |
| order | integer | 표시 순서 |

amount가 있으면 0보다 큰 정수이고 currency는 KRW이며 priceLabel은 null이다. 가격
미정·무료·현장 문의는 amount를 null로 하고 번역된 priceLabel을 필수로 제공한다.
두 표현을 동시에 제공하지 않는다. 이미지 메뉴판만 제공하지 않으며 구조화 텍스트를
함께 게시한다.

### 13.3 GET /festivals/{festivalId}/spaces

Query:

| 이름 | 타입 | 설명 |
|---|---|---|
| type | SpaceType, 반복 가능 | 같은 축의 값은 OR |
| areaId | UUID, 반복 가능 | 같은 축의 값은 OR |
| operatingStatus | OperatingStatus, 반복 가능 | 상태 |
| q | string | 이름·운영 주체·구조화 메뉴 검색 |
| cursor | string | 다음 페이지 |
| limit | integer | 1~100 |
| lang | language | 응답 언어 |

서로 다른 필터 축은 AND로 결합한다. 기본 정렬은 `typePriority`, order, id의 모든 필드
오름차순이다. typePriority는 Figma 탭 순서에 맞춰 BOOTH=0, FLEA_MARKET=1,
PUB=2로 고정하며 cursor도 이 정렬 tuple을 보존한다.
q는 trim 후 1~100자여야 하며 빈 문자열이나 초과 값은 400
INVALID_QUERY_PARAMETER다.

목록의 `data.items`는 FestivalSpaceSummary 배열이다. 필수 필드는 `id`, `type`,
`name`, `operatorName`, `thumbnail`, `area`, `operatingStatus`, `nextStatusAt`,
`operatingStatusMessage`, `mapMarkerId`, `menuHighlights`, `order`, `updatedAt`,
`operationalUpdatedAt`이다.
`operatorName`, `thumbnail`, `area`, `nextStatusAt`, `operatingStatusMessage`,
`mapMarkerId`, `operationalUpdatedAt`은 null일 수 있다. `menuHighlights`는 최대 3개의
`id`, `name`, `price`, `priceLabel`, `availability`만 제공하며 전체 메뉴·설명·media·events는
상세에서만 반환한다.

thumbnail은 8.1의 Media, area는 `id`, `name`, `order`를 가진 MapAreaSummary다.
MenuHighlight의 `priceLabel`은 null일 수 있고, 숫자 가격은 locale별 formatter가
price.amount와 price.currency에서 표시한다. menuHighlights는 featured=true인 item을
menu section.order, item.order, item.id 순으로 최대 3개 선택하고 자동으로 다른 item을
채우지 않는다. 값이 없으면 빈 배열이다.

    {
      "data": {
        "items": []
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul",
        "contentRevision": "184",
        "snapshotUpdatedAt": "2030-09-18T18:04:30+09:00",
        "language": {
          "requested": "ko",
          "content": "ko",
          "fallbackApplied": false
        },
        "pagination": {
          "nextCursor": null,
          "hasNext": false,
          "limit": 20,
          "evaluationTime": "2030-09-18T18:05:00+09:00",
          "operationalStateRevision": "opstate-42"
        }
      }
    }

### 13.4 GET /festivals/{festivalId}/spaces/{spaceId}

FestivalSpace 전체 필드를 반환한다. mapMarkerId가 있으면 지도 상세의 markerId와
동일해야 한다.

## 14. 지도

### 14.1 지도 카테고리

category code는 공식 영문 건물명이 아니라 API의 안정적 기술 식별자다.

| code | 한국어 label | 규칙 |
|---|---|---|
| INFO | 인포 | 운영 자료의 대표 명칭 사용 |
| STUDENT_COUNCIL_BOOTH | 총학생회 부스 | 운영 주체 별도 저장 |
| BRAND_BOOTH | 브랜드 부스 |  |
| FLEA_MARKET | 플리마켓 |  |
| PUB | 주점 |  |
| TICKET_BOOTH | 티켓부스 | 매표소·티켓 수령 부스와 같은 장소 |
| RESTROOM | 화장실 |  |
| SMOKING_AREA | 흡연구역 |  |
| FIRST_AID | 의무실 |  |
| PICNIC_ZONE | 피크닉존 |  |
| FOOD_TRUCK | 푸드트럭 |  |
| EVENT_ZONE | 이벤트존 (호수공원) |  |
| PHOTO_BOOTH | 포토부스 |  |
| TRASH_BIN | 쓰레기통 |  |
| STAGE | 공연장 | 공연장 게이트 포함 |

STAGE_GATE를 별도 최상위 category로 만들지 않는다.

### 14.2 지도 구역

MapArea의 필수 필드는 `id: UUID`, `name: string`, nullable `description: string`,
nullable `bounds: MapBounds`, `order: integer`다. bounds가 없으면 null이며 표시·필터에는
사용할 수 있지만 지도 위 영역 도형은 그리지 않는다. 학교 약칭이나 추정 영문명으로
enum을 만들지 않는다.

기획 자료에서 받은 한국어 구역 원문:

- 학생복지관 앞 민주광장
- 호수공원 이벤트존
- 호수공원 앞 피크닉존
- 예체능대학 앞 & 학술정보관 옆 주차장
- 학술정보관과 제4공학관 사이
- 티켓 수령 부스
- 대운동장

실제 MapArea 게시 여부, 복합 위치의 경계와 대표 노출명은 승인된 운영 설정으로
정한다. 호수공원 이벤트존과 호수공원 앞 피크닉존은 반드시 별도 MapArea로 관리한다.

매표소, 티켓부스, 티켓 수령 부스는 하나의 물리적 장소다.

- category code는 TICKET_BOOTH를 사용하되 category label, marker 표시명과 검색
  alias는 승인된 운영 설정에서 제공한다.
- 티켓 수령 부스를 MapArea로 제공할지 단일 marker로만 제공할지는 운영 결정 전까지
  고정하지 않는다.
- 세 용어를 별도 장소 ID나 좌표로 만들지 않고 하나의 locationId와 marker를 공유한다.

### 14.3 MapConfiguration

| 필드 | 타입 | 설명 |
|---|---|---|
| snapshotId | string | 이 설정과 marker 집합을 함께 고정하는 opaque ID |
| mapType | enum | ILLUSTRATION 또는 GEOGRAPHIC |
| mapVersion | string | 지도 설정과 marker 집합의 불변 버전 |
| mapTextStrategy | enum | `TEXT_FREE_BASE_WITH_OVERLAYS`, `LOCALIZED_ASSETS`, `DYNAMIC_GEOGRAPHIC_LABELS` |
| displayMedia | Media, nullable | 실제 응답 언어에 사용할 단일 지도 자산 |
| textOverlays | MapTextOverlay array | 지도 위에 렌더링할 번역 가능한 구역·랜드마크 label |
| coordinateSystem | enum | NORMALIZED_CONTENT_BOUNDS 또는 WGS84 |
| contentBounds | ContentBounds, nullable | 일러스트 원본 안의 실제 지도 경계 |
| defaultViewport | DefaultViewport | 초기 center와 scale 또는 zoom |
| geographicMap | GeographicMapConfiguration, nullable | 일반 지도 renderer·저작권 설정 |
| categories | MapCategory array | category 표시 설정 |
| areas | MapArea array | 구역 |
| filterConfiguration | FilterConfiguration | 선택 수와 축 결합 규칙 |
| updatedAt | datetime | 게시 시각 |

MapCategory의 필수 필드는 `code`, `label`, `icon`, `markerStyle`, `order`, `visible`이다.
code는 14.1의 기술 식별자이며 관리자가 바꾸지 못한다. label과 icon 대체 텍스트는
번역 대상이고, icon·markerStyle·order·visible은 축제 회차별 운영 설정이다.

지도 wire component는 알 수 없는 필드를 허용하지 않으며 다음 닫힌 schema를 사용한다.

- ContentBounds: `x`, `y`는 0 이상의 integer, `width`, `height`는 1 이상의 integer다.
  ILLUSTRATION의 모든 선택 가능한 display media에 대해 `x + width <= media.width`,
  `y + height <= media.height`여야 한다.
- MapPosition은 `coordinateSystem` discriminator를 가진 oneOf다.
  - NORMALIZED_CONTENT_BOUNDS: `xRatio`, `yRatio`는 0..1이고 anchor는 CENTER 또는
    BOTTOM_CENTER다.
  - WGS84: latitude는 -90..90, longitude는 -180..180이다.
- DefaultViewport도 `coordinateSystem` discriminator를 가진 oneOf다.
  - NORMALIZED_CONTENT_BOUNDS: `centerXRatio`, `centerYRatio`는 0..1이고 scale은 1..8의
    number다. scale=1은 controls·safe area를 뺀 실제 drawable viewport 안에 contentBounds
    전체를 비율 유지한 contain 방식으로 맞춘 기준 배율이고, n은 그 기준의 n배 확대다.
    viewport 크기가 바뀌면 contain 기준만 다시 계산하고 center ratio와 scale 값은 유지한다.
    pan·초기 center는 확대된 contentBounds 밖이 보이지 않는 범위로 각 축을 clamp하며, 한
    축의 콘텐츠가 viewport보다 작아 clamp 범위가 없으면 그 축 center를 0.5로 두고 남는
    영역은 지도 background로 letterbox 처리한다. 자산을 반복하거나 ratio를 외삽하지 않는다.
  - WGS84: `centerLatitude` -90..90, `centerLongitude` -180..180, zoom 0..24다.
- MapBounds도 `coordinateSystem` discriminator를 가진 oneOf다.
  - NORMALIZED_CONTENT_BOUNDS: `minXRatio`, `minYRatio`, `maxXRatio`, `maxYRatio`는
    0..1이며 각 min < max다.
  - WGS84: `south`, `west`, `north`, `east`는 유효 위·경도이고 south < north,
    west < east다.
- MapIcon은 필수 `source: BUILTIN|MEDIA`, nullable `builtinKey`, nullable `media`, 필수
  번역 `alt`를 가진다. BUILTIN은 builtinKey에 14.1 category code만, MEDIA는 READY
  Media만 두고 다른 variant 필드는 null이다.
- MapIconWrite는 같은 variant를 사용하되 media 대신 nullable `mediaId`, alt 대신 필수
  `altTranslations`를 받는다. BUILTIN이면 builtinKey만, MEDIA이면 같은 festivalId의 READY
  mediaId만 non-null이다. MapCategoryPatch와 MapMarkerWrite의 icon은 이 schema다.
  공개 아이콘의 접근 가능한 이름은 outer MapIcon.alt가 authoritative하고 nested
  Media.alt는 원본 asset 메타데이터다. client는 같은 요소에서 둘을 중복 발표하지 않는다.
- MarkerStyle은 필수 `shape: PIN|DOT|BADGE`,
  `tone: DEFAULT|INFO|AMENITY|FOOD|STAGE|EMERGENCY`, `showLabel: boolean`을 가진다.
  임의 CSS·색상 문자열은 API로 받지 않는다.
- FilterConfiguration은 필수 `selectionMode: SINGLE|MULTIPLE`, 1 이상의
  `maxAreaSelections`, `maxCategorySelections`, 고정 `sameAxisOperator: ANY`,
  `crossAxisOperator: AND|OR`를 가진다. SINGLE이면 두 max는 1이다.
- GeographicMapConfiguration은 필수 `renderer: MAPLIBRE_STYLE`, `styleUrl`,
  `attributionText`, nullable `attributionUrl`, `minZoom`, `maxZoom`을 가진다. styleUrl과
  attributionUrl은 승인된 HTTPS URL이고 0 <= minZoom <= maxZoom <= 24다. 공개 client에
  비밀 API key를 전달하지 않으며 attribution은 지도와 함께 항상 표시한다. GEOGRAPHIC의
  DefaultViewport.zoom은 minZoom 이상 maxZoom 이하여야 한다.

MapTextOverlay는 `id`, `type: AREA_LABEL|LANDMARK_LABEL`, `text`, `position: MapPosition`,
`order`, `accessibilityText`를 가진다. position은 현재 coordinateSystem과 같은 variant를
쓴다. `TEXT_FREE_BASE_WITH_OVERLAYS`는 관리자 입력의 baseMediaId와 textOverlays를 필수로
하고 공개 응답의 displayMedia에 해당 공통 자산 하나를 반환한다. category, area, marker,
textOverlays의 label은 HTML/SVG 등 접근 가능한 DOM text로 렌더링하며 raster에 합성된
문자를 유일한 정보로 쓰지 않는다.

`LOCALIZED_ASSETS`는 관리자 입력의 `localizedMediaIds: language→mediaId` map에
translationPolicy에서 fallback을 허용하지 않는 모든 언어의 READY 자산을 필수로 한다.
모든 언어 자산은 같은 pixel width·height와 같은 contentBounds crop을 사용하고, 동일한
지형·건물 지점이 같은 정규화 좌표에 정렬되어야 한다. 게시 preview 검증에서 어느 하나라도
크기·crop·좌표 정렬이 다르면 전체 지도 batch를 거부한다.
공개 응답은 전체 map을 노출하지 않고 meta.language.content에 맞는 자산 하나만
displayMedia로 반환하며 Media.alt도 같은 언어다. 선택 후보 언어의 자산이 null이거나
공개 전송 불가하면 5.2의 필수 번역 불완전으로 취급해, 그 언어가 fallbackAllowedFor일 때
텍스트와 지도 자산을 함께 fallbackLanguage로 다시 projection하고 meta.language.content도
그 언어로 설정한다. fallbackLanguage 자산도 없거나 fallback이 허용되지 않으면 503
CONTENT_REVISION_UNAVAILABLE이다. 단일 언어 문자가 들어간 한 장의 raster 지도를 모든
언어에 반환하지 않는다.

한 marker는 category와 area를 각각 최대 하나만 가지므로 v1의 sameAxisOperator는 ANY로
고정한다. category filter끼리, area filter끼리는 OR이고 두 축의 결합만
crossAxisOperator를 따른다. 선택 방식과 AND·OR 값은 운영 설정에서 정하며 client가
자체 기본값으로 바꾸지 않는다.

ILLUSTRATION은 coordinateSystem NORMALIZED_CONTENT_BOUNDS를 사용하고 contentBounds에
x, y, width, height 원본 pixel을 제공한다. 지도 파일의 crop, 투명 여백, 원본 크기가
바뀌면 새 mapVersion을 발행하고 모든 좌표를 검증한 뒤 하나의 revision으로 게시한다.

GEOGRAPHIC은 coordinateSystem WGS84를 사용하고 media와 contentBounds를 요구하지
않는다. 이 선택이 현재 위치 수집이나 길찾기 제공을 뜻하지 않으며, 해당 기능은 별도
제품 승인과 개인정보 검토 없이는 활성화하지 않는다.

GEOGRAPHIC은 mapTextStrategy=DYNAMIC_GEOGRAPHIC_LABELS를 사용하고 displayMedia를 null로
반환하며 geographicMap이 필수다. 축제 category·area·marker·overlay label은 API의 번역
text로 렌더링한다. ILLUSTRATION은 나머지 두 strategy 중 정확히 하나를 사용하고
geographicMap은 null이다.

v1의 geographic styleUrl은 언어가 박힌 지명·시설명 symbol layer가 없는 승인된
text-free base style만 허용한다. 모든 사용자 의미 label은 선택된 content language의
API text와 overlay로 렌더링한다. 관리자 GeographicMapWrite는 public 필드 중 renderer를
고정값으로 받고 attributionText 대신 `attributionTextTranslations`를 받으며, public
attributionText는 선택 언어 projection이다. 언어별 basemap style이 필요하면 locale
template과 attribution 계약을 다음 버전에 명시하기 전 임의 query 치환으로 제공하지 않는다.

### 14.4 MapMarker

| 필드 | 타입 | 설명 |
|---|---|---|
| id | UUID | 안정적 marker ID |
| mapVersion | string | 지도 자산 버전 |
| category | category code | 15개 중 하나 |
| areaId | UUID, nullable | 구역 |
| locationId | UUID | 동일 물리 장소를 공유하는 안정적 ID |
| name | string | 표시명 |
| description | string, nullable | 안내 |
| icon | MapIcon | category 기본값과 marker override를 합친 표시 icon |
| markerStyle | MarkerStyle | 접근 가능한 shape·tone·label 표시 설정 |
| position | MapPosition | coordinateSystem에 맞는 위치 variant |
| linkedSpaceId | UUID, nullable | 연결된 부스·주점·플리마켓 |
| stageId | UUID, nullable | 연결된 공연장 |
| operatingPeriods | OperatingPeriod array | 시설 자체 운영 시간; 없으면 빈 배열 |
| operatingStatus | OperatingStatus, nullable | 운영 시간이 있는 marker의 현재 상태 |
| nextStatusAt | datetime, nullable | 다음 운영 상태 전환 예상 |
| operatingStatusMessage | string, nullable | 일시 중단·취소 등 승인된 운영 안내 |
| locationText | string | 사용자에게 보여 줄 승인된 위치 설명 |
| aliases | string array | 검색용 승인 별칭 |
| accessibilityText | string | 지도 없이 위치를 찾는 설명 |
| order | integer | category 안의 결정적 표시 순서 |
| updatedAt | datetime | 마지막 게시 수정 |
| operationalUpdatedAt | datetime, nullable | 독립 marker의 마지막 live-state 적용·해제 시각 |

NORMALIZED_CONTENT_BOUNDS의 비율은 전체 이미지가 아니라 contentBounds 기준이다. 한
marker, overlay, area bounds와 viewport는 현재 coordinateSystem에 맞는 variant 하나만
가진다. marker.mapVersion과 MapConfiguration.mapVersion은 반드시 일치한다.

MapMarker가 공간 연결의 authoritative FK를 소유한다. FestivalSpace와 Stage에는 각각
최대 하나의 marker만 연결할 수 있고 같은 Stage의 여러 Performance가 그 공연장 marker를
공유한다. linkedSpaceId와 stageId는 동시에 설정할 수 없다. 연결 대상이 없는 화장실,
쓰레기통 같은 시설 marker는 허용한다.

stageId가 있으면 category는 STAGE여야 한다. linkedSpaceId의 SpaceType이 PUB 또는
FLEA_MARKET이면 category도 각각 PUB 또는 FLEA_MARKET이어야 하고, BOOTH이면
STUDENT_COUNCIL_BOOTH 또는 BRAND_BOOTH만 허용한다. 나머지 category는 독립 marker만
허용한다. 관계와 category가 맞지 않으면 게시 검증에 실패한다.

linkedSpaceId가 있는 marker의 operatingPeriods, operatingStatus, nextStatusAt,
operatingStatusMessage는 FestivalSpace의 같은 필드를 서버가 projection한 값이며 marker에
별도 저장하지 않는다. 이때 operationalUpdatedAt도 연결 FestivalSpace의 값을 projection한다.
stageId가 있거나 독립 시설인 marker만 별도 운영 시간을 가질 수 있다. 관리자 게시
검증은 중복 입력이나 projection 불일치를 거부한다.

operationalUpdatedAt은 현재 override가 해제된 뒤에도 마지막 적용·해제 event 시각을
유지하며 live-state 이력이 없으면 null이다. workingRevision이나 publishedRevision을
바꾸지 않고도 현장 상태가 갱신됐음을 사용자 화면이 표시할 수 있게 한다.

MapMarker의 icon과 markerStyle은 marker override가 있으면 이를, 없으면 category 기본값을
projection한 최종 값이다. 관리자가 override를 null로 바꾸면 category 기본값으로 돌아간다.
aliases는 번역 필드이며 공개 응답에는 meta.language.content 언어의 배열만 반환한다.
marker q 검색도 같은 언어의 name과 aliases 인덱스만 사용한다.

### 14.5 GET /festivals/{festivalId}/map

현재 게시된 MapConfiguration을 반환한다. 지도 자산과 marker revision이 불일치하면
부분 응답을 반환하지 않고 직전의 일관된 게시 revision을 제공한다.

`/map`의 content language는 응답에 바로 보이는 configuration만으로 정하지 않는다. 현재
MapSnapshot의 visible category·area·text overlay·attribution·displayMedia와 모든 publicly
addressable marker의 name·locationText·accessibilityText·icon alt 등 공개 schema에 필요한
번역 및 연결 space에서 projection되는 필수 번역을 하나의 completeness universe로 먼저
검사한다. 요청 언어에서 하나라도 불완전하면 5.2대로 이 전체 universe를 fallbackLanguage로
다시 검사해 지도와 모든 marker가 함께 fallback한다. marker query의 area/category/q와 page는
그 뒤 적용하며 결과 부분집합에 따라 언어를 다시 선택하지 않는다. fallbackLanguage에서도
필수 schema를 만들 수 없으면 503 CONTENT_REVISION_UNAVAILABLE이다.

응답의 `data.snapshotId`, `data.mapVersion`, `meta.contentRevision`은 하나의 불변
MapSnapshot을 식별한다. 클라이언트는 뒤이은 marker 목록·상세 요청에 snapshotId를
그대로 전달한다. 서버는 지도 cache의 max-age 60초 + stale-while-revalidate 300초 +
client 재시도 window 60초를 합친 최소 420초 동안 이전 snapshot을 조회 가능하게
유지한다. 배포 설정이 이 cache window를 늘리면 snapshot 보존 시간도 함께 늘린다.
snapshotId는 mapVersion과 contentRevision뿐 아니라 `/map`에서 결정한 requested language,
content language, fallbackApplied 및 이 snapshot이 temporal projection 전에 참조하는 모든
media의 22절 `mediaRightsValidator`에도 결합한다. marker 목록·상세는 독립적으로 fallback을
다시 계산하지 않고 해당 snapshot의 meta.language를 그대로 반환한다. 명시적 lang이
snapshot의 requested language와 다르면 412 MAP_SNAPSHOT_MISMATCH이고, client는 새 lang으로
`/map`부터 다시 조회한다. 따라서 지도와 marker를 한 화면에 조합해도 언어가 섞이지 않는다.

map 관련 media의 rightsEpoch 또는 rightsPhase가 바뀌면 contentRevision·mapVersion이 같아도
새 snapshotId를 계산하고 `/map`, marker 목록·상세와 client manifest cache를 purge한다. 이
권리 전이는 위 일반 420초 보존의 예외다. 이전 snapshotId는 만료 media URL이나 이전 icon
variant를 재생하지 않고 즉시 412 MAP_SNAPSHOT_MISMATCH가 되며, client는 `/map`부터 다시
조회해 하나의 새 temporal projection을 사용한다. 따라서 MEDIA icon의 BUILTIN fallback이나
displayMedia 비가용도 같은 snapshotId 아래에서 endpoint별로 엇갈리지 않는다.

### 14.6 GET /festivals/{festivalId}/map/markers

Query:

| 이름 | 타입 | 설명 |
|---|---|---|
| snapshotId | string | 필수; map 응답에서 받은 opaque snapshot ID |
| areaId | UUID, 반복 가능 | 허용 개수와 결합은 snapshot의 지도 설정을 따름 |
| category | category code, 반복 가능 | 허용 개수와 결합은 snapshot의 지도 설정을 따름 |
| q | string | 표시명과 alias 검색 |
| lang | language | 응답 언어 |

반복 parameter 허용 수와 같은 축·다른 축의 결합은 요청 snapshotId가 식별한
MapSnapshot.filterConfiguration을 따른다. 보존 중인 옛 snapshot도 그 snapshot에 고정된
selectionMode·maxSelections·crossAxisOperator로 검증하고 현재 head 설정을 섞지 않는다.
SINGLE인데 같은 parameter를 둘 이상 보내면 400
FILTER_SELECTION_LIMIT이다. 결과는 category 표시 순서, marker.order, marker.id
순으로 정렬한다. snapshotId가 현재 또는 보존된 MapSnapshot과 맞지 않으면 부분적으로
최신 marker를 섞지 않고 412 MAP_SNAPSHOT_MISMATCH를 반환한다.
q는 trim 후 1~100자여야 하며 빈 문자열이나 초과 값은 400
INVALID_QUERY_PARAMETER다.

공개 `/map`의 categories와 marker 목록·상세에는 visible=true category만 포함한다. hidden
category code를 filter로 보내면 400 FILTER_NOT_SUPPORTED이고 해당 marker 상세은 404
MARKER_NOT_FOUND다. 관리자는 hidden category와 marker를 preview에서 확인할 수 있다.
이 계약에서 `publicly addressable marker`는 현재 MapSnapshot에 게시되고
category.visible=true인 marker다. StageSummary와 FestivalSpace의 mapMarkerId, HomeAction과
ChatbotAction의 OPEN_MAP markerId는 이 marker만 가리킬 수 있다. derived 관계는 hidden이면
null로 projection하고, 관리자가 직접 입력한 action이 hidden marker를 참조하면 게시
검증을 실패시킨다.

    {
      "data": {
        "snapshotId": "opaque-map-snapshot",
        "mapVersion": "immutable-map-version",
        "markers": []
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul",
        "contentRevision": "184",
        "snapshotUpdatedAt": "2030-09-18T18:04:30+09:00",
        "language": {
          "requested": "ko",
          "content": "ko",
          "fallbackApplied": false
        }
      }
    }

### 14.7 GET /festivals/{festivalId}/map/markers/{markerId}

data는 MapMarker 전체 필드와 필수 nullable `linkedSpace: FestivalSpaceSummary`,
`linkedStage: StageSummary`를 반환한다. 두 값은 동시에 non-null일 수 없고 각각
linkedSpaceId, stageId와 정확히 일치한다. `snapshotId` query는 필수이며 목록과 같은
snapshot 검증을 적용한다. accessibilityText와 locationText는 항상 제공한다.

## 15. 공지

NoticeType:

- GENERAL
- OPERATION
- WEATHER
- EMERGENCY
- SCHEDULE_CHANGE
- TRANSPORT

분실물은 운영 방식이 확정될 때까지 NoticeType에 포함하지 않는다.

NoticeContentType:

- INTERNAL
- EXTERNAL
- HYBRID

Notice 필드:

| 필드 | 타입 | 설명 |
|---|---|---|
| id | UUID | 공지 ID |
| type | NoticeType | 공지 분류 |
| contentType | NoticeContentType | 내부 본문·외부 링크 구성 |
| title | string | 제목 |
| summary | string, nullable | 목록 요약 |
| body | sanitized rich text, nullable | 내부 본문 |
| important | boolean | 중요 공지 |
| emergency | boolean | 읽기 전용; `type == EMERGENCY`에서 파생 |
| sourceName | string, nullable | 공식 출처 |
| externalUrl | HTTPS URL, nullable | 승인된 외부 링크 |
| visibleFrom | datetime | 노출 시작 |
| visibleUntil | datetime, nullable | 노출 종료 |
| publishedAt | datetime | 게시 시각 |
| updatedAt | datetime | 마지막 수정 |

`body`는 임의 HTML 문자열이 아니라 versioned RichText AST다. 최상위 형식은
`{ "schemaVersion": 1, "blocks": [...] }`이다. v1 recursive union:

- ParagraphBlock: `{ "type": "paragraph", "children": Inline[] }`
- HeadingBlock: `{ "type": "heading", "level": 2|3, "children": Inline[] }`
- ListBlock: `{ "type": "list", "ordered": boolean, "items": ListItem[] }`
- ListItem: `{ "children": ParagraphBlock[] }`
- TextInline: `{ "type": "text", "text": string, "marks": ("strong"|"emphasis")[] }`
- LinkInline: `{ "type": "link", "href": HTTPS URL, "children": TextInline[] }`

blocks는 최대 200개, 전체 text는 최대 20,000자, AST 깊이는 최대 4다. 빈 list·빈 link와
중첩 list는 허용하지 않는다. script, style, iframe, rawHtml은 schema에 존재하지 않는다.
link는 HTTPS와 승인 도메인을 검증한다. 서버는 입력 검증 뒤에도 출력 시 같은 allowlist로
정화한다.

관리자 쓰기에는 emergency boolean을 받지 않고 type으로만 긴급 여부를 결정한다.
`publishAt`은 draft snapshot을 공개 revision으로 전환하는 시각이고 `visibleFrom`과
`visibleUntil`은 게시된 공지가 모든 공개 surface에서 addressable한 업무상 노출 기간이다.
목록·상세·홈·챗봇 source는 모두 `visibleFrom <= meta.serverTime < visibleUntil`을 적용하고
visibleUntil=null이면 상한이 없다. publishAt이 visibleUntil 이후이거나 게시 시점에 유효
노출 구간이 없으면 검증에 실패한다.

### 15.1 GET /festivals/{festivalId}/notices

Query:

| 이름 | 타입 | 설명 |
|---|---|---|
| type | NoticeType, 반복 가능 | 공지 분류 |
| important | boolean | true·false를 정확히 일치시킴; 생략하면 모두 조회 |
| cursor | string | 다음 페이지 |
| limit | integer | 1~100 |
| lang | language | 응답 언어 |

긴급 공지, 중요 공지, publishedAt 내림차순, id 순으로 정렬한다.

목록의 `data.items`는 NoticeSummary 배열이다. 필수 필드는 `id`, `type`, `title`,
`summary`, `important`, `emergency`, `publishedAt`, `updatedAt`이다. summary만 null일
수 있으며 body는 목록에 포함하지 않는다.

### 15.2 GET /festivals/{festivalId}/notices/{noticeId}

Notice 전체 필드를 반환한다. contentType별 필수값:

- INTERNAL: body는 non-null 필수, externalUrl은 null
- EXTERNAL: body는 null, externalUrl은 non-null 필수
- HYBRID: body와 externalUrl 모두 non-null 필수

Notice는 contentType discriminator를 쓰는 닫힌 oneOf이며 body·externalUrl key 자체는 모든
variant에서 필수 nullable로 유지한다. body는 위에서 정의한 RichText, externalUrl은 승인된 HTTPS
URL이고 위 조합 외에는 저장·게시 단계에서 거부한다.

visibleFrom 전, visibleUntil 이후, 게시 취소된 공지는 ID를 알아도 404 NOTICE_NOT_FOUND를
반환한다. 홈과 챗봇도 같은 판정을 사용하며 예약 공지 제목·source를 미리 노출하지 않는다.

## 16. 외부 링크

ExternalLinkType:

- OFFICIAL_PAGE
- OFFICIAL_SNS
- TRANSPORT
- FAQ
- OTHER

### GET /festivals/{festivalId}/external-links

Query:

| 이름 | 타입 | 설명 |
|---|---|---|
| type | ExternalLinkType, 반복 가능 | 링크 유형 |
| lang | language | 응답 언어 |

externalLinks capability가 활성화된 회차에서만 각 항목의 id, type, title,
description, url, media, external, order를 반환한다. 비활성화된 경우 404
FEATURE_NOT_ENABLED다. URL은 HTTPS와 승인 도메인을 검증한다. FAQ·공식 SNS는 승인된
채널만 게시한다. 고등학생 방문 안내는 이 generic 외부 링크 리소스로 게시하지 않는다.

ExternalLink의 필수 필드는 `id`, `type`, `title`, `description`, `url`, `media`,
`external`, `order`, `updatedAt`이다. description과 media만 nullable이고 url은 HTTPS,
external은 true다. 응답은 `data.items`에 전체 목록을 반환하며 pagination을 사용하지
않는다.

type=FAQ는 `externalLinks`와 `publicFaq` capability가 모두 true인 경우에만 반환한다.

## 17. 챗봇

### 17.1 GET /festivals/{festivalId}/chatbot/configuration

`Festival.capabilities.chatbot=true`일 때 같은 contentRevision의 published
ChatbotConfiguration 공개 projection을 반환한다. false이면 404 FEATURE_NOT_ENABLED다.
data의 필수 non-null 필드는 `welcomeMessage`, `inputPlaceholder`, `suggestions`,
`maxMessageLength`, `maxConversationTurns`다. suggestions는 현재 응답 언어의 0~8개 string
배열이고 빈 값은 빈 배열이다. 두 제한값은 게시된 ChatbotConfiguration의 값이며 각각
1~2000, 1~30 범위다. working 설정과 내부 source policy는 반환하지 않는다.

### 17.2 POST /festivals/{festivalId}/chatbot/messages

공개·비로그인 endpoint다. 계정이나 영구 사용자 프로필을 만들지 않는다.

`Festival.capabilities.chatbot=false`이면 404 FEATURE_NOT_ENABLED를 반환한다. 응답은
같은 `meta.contentRevision`의 published ChatbotConfiguration,
ChatbotKnowledgeEntry와 공개 축제 리소스만 사용한다. working draft나 서로 다른
revision의 지식을 섞지 않는다.

Request:

    {
      "message": "승인된 언어의 사용자 질문",
      "lang": "ko",
      "conversation": {
        "turns": [
          {
            "role": "assistant",
            "content": "직전 응답"
          }
        ]
      }
    }

규칙:

- message는 trim 후 1~published maxMessageLength자다.
- conversation은 선택값이고 null은 허용하지 않는다. ConversationTurn은 알 수 없는 필드를
  허용하지 않는 `{ "role": "user"|"assistant", "content": string }`이며 content는 trim 후
  1~1,000자다. turns는 과거순 1~published maxConversationTurns개, role이 교대해야 하고
  마지막은 assistant다. 새 사용자
  입력은 turns가 아니라 message에만 둔다. system·tool role은 허용하지 않는다.
- conversation은 문맥 보조일 뿐 운영 사실의 근거로 신뢰하지 않고 게시 데이터로 다시
  검증한다. 형식이 잘못되면 422 VALIDATION_ERROR다.
- 서버는 게시된 해당 축제 회차 데이터만 운영 사실의 근거로 사용한다.
- 확인할 수 없는 일정·장소·가격은 생성하지 않고 확인 불가를 알린다.
- 원문 대화는 기본적으로 저장하지 않는다. 품질 분석 저장이 필요하면 별도 개인정보
  정책과 동의를 적용한다.
- 챗봇 장애는 지도·시간표·공지 조회에 영향을 주지 않는다.

Response:

    {
      "data": {
        "answer": "게시 데이터에 근거한 답변",
        "sources": [
          {
            "resourceType": "NOTICE",
            "resourceId": "b03d13ec-5a79-41bc-8c3d-e637e99a8f41",
            "title": "근거 제목"
          }
        ],
        "actions": [
          {
            "type": "OPEN_MAP",
            "payload": {
              "markerId": "ae3ed717-7070-48c1-9666-e3312a449ac1"
            }
          }
        ],
        "safety": {
          "grounded": true,
          "refusalReason": null
        }
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul",
        "contentRevision": "184",
        "snapshotUpdatedAt": "2030-09-18T18:04:30+09:00",
        "language": {
          "requested": "ko",
          "content": "ko",
          "fallbackApplied": false
        }
      }
    }

이 응답 data의 닫힌 component 이름은 ChatbotMessageResult이며 필수 `answer`,
`sources: ChatbotSource[]`, `actions: ChatbotAction[]`, `safety: ChatbotSafety`만 가진다.

ActionType:

- OPEN_MAP
- OPEN_TIMETABLE
- OPEN_NOTICE
- OPEN_FESTIVAL_SPACE

Action은 `{ "type": ..., "payload": ... }` tagged union이며 payload 규칙은 다음과
같다.

| type | payload |
|---|---|
| OPEN_MAP | `markerId`, `areaIds`, `categoryCodes` 중 하나 이상 |
| OPEN_TIMETABLE | payload 생략 시 시간표 root; 있으면 `date`, `stageId`, `performanceId` 중 하나 이상 |
| OPEN_NOTICE | `noticeId` 필수 |
| OPEN_FESTIVAL_SPACE | `spaceId` 필수 |

ChatbotAction은 type discriminator를 쓰는 oneOf이며 알 수 없는 필드를 허용하지 않는다.
OPEN_MAP의 배열은 비어 있을 수 없고 현재 FilterConfiguration의 선택 한도를 지킨다. 각
UUID와 date는 해당 축제의 같은 공개 snapshot에 존재해야 한다. OPEN_TIMETABLE의 payload가
여러 필드를 가지면 performanceId의 공개 performance가 제공된 stageId와 day view date에
모두 일치해야 한다. OPEN_MAP에서 markerId와 areaIds를 함께 보내면 marker의 non-null
areaId가 배열에 포함되어야 하고, categoryCodes를 함께 보내면 marker category가 배열에
포함되어야 한다. 서로 모순되는 조합은 knowledge 저장·preview·게시 검증에서 거부한다.
이동할 action이 없으면 NONE item을 만들지 않고 actions를 빈 배열로 반환한다.

ChatbotSource의 필수 필드는 `resourceType`, `resourceId`, `title`이다. resourceType은
FESTIVAL, ARTIST, PERFORMANCE, FESTIVAL_SPACE, MAP_MARKER, NOTICE, EXTERNAL_LINK 중 하나고
resourceId는 UUID다. 같은 festivalId·contentRevision에서 사용자가 실제로 조회할 수 있는
공개 리소스만 sources에 포함한다.

ChatbotSafety의 필수 필드는 `grounded: boolean`,
`refusalReason: null|OUT_OF_SCOPE|UNVERIFIED|UNSAFE`다. grounded=true이면 refusalReason은
null이고 false이면 null이 아니다. 응답의 answer는 non-null string이고 sources와 actions는
ChatbotConfiguration의 최대 개수 이내 배열이며 값이 없으면 null이 아니라 빈 배열이다.

OPEN_MAP은 UUID markerId·areaIds와 14.1의 category code만 사용하고 추정한 학교 영문
구역 enum을 반환하지 않는다. 모든 대상 ID는 같은 festivalId의 공개 snapshot에 실제로
존재해야 한다. 대상이 게시 취소되면 action을 제외하고 answer에 최신 조회 경로를
안내한다. 서버가 생성한 임의 client route나 검증되지 않은 URL은 반환하지 않는다.

## 18. 관리자 API

### 18.1 Figma 관리자 IA 대응

Figma 관리자 IA의 각 메뉴가 어느 계약으로 구현되는지 다음과 같이 고정한다.

| 관리자 IA | API 계약 | v1 상태 |
|---|---|---|
| ADMIN DASHBOARD | dashboard, publication operation | 지원 |
| 지도 관리 | map configuration, map categories, map areas, map markers, publication batch | 지원 |
| 라인업 관리 | lineup categories, artists | 지원 |
| 타임테이블 관리 | timetable configuration, stages, performances, live operation | 지원 |
| 부스 / 주점 / 플리마켓 관리 | spaces, menu sections/items, space events | 지원 |
| 공지 관리 | notices, emergency publish/unpublish | 지원 |
| 챗봇 FAQ / 정보 관리 | chatbot configuration, knowledge entries, draft test | 지원 |
| 다국어 콘텐츠 관리 | translation status, validation, preview | 지원 |
| 고등학생 안내 콘텐츠 관리 | 없음 | 총학생회 자료와 팝업·페이지 노출 조건 승인 후 별도 계약 추가 |
| 분실물 관리 | 없음 | 협의·보류 |
| 스탬프 / QR / 현장 경품 관리 | 현재 없음; 29절 조건부 계약 | 필수 정책 승인 뒤 함께 적용 |
| 룰렛 관리 | 현재 없음; 계약 미작성·결정 대기 | 채택 여부·확률·횟수·공정성·감사 결정 전 미지원 |

고등학생 안내, 분실물과 스탬프 관련 관리자 route가 없다는 점은 누락이 아니라 상태
게이트다. 승인 시 별도 계약 변경과 개인정보·운영 검토를 거치며 기존 generic endpoint에
임의 resource 이름이나 외부 링크 subtype을 넣어 우회하지 않는다.

### 18.2 횡단·운영 엔드포인트 인덱스

서비스 Base path는 /api/v1이고 아래 표는 그 부분만 생략한다. 따라서 표의 `/admin`을
한 번만 결합하며, 예를 들어 로그인 전체 경로는 `/api/v1/admin/auth/login`이다. 이 표는
운영·횡단 기능의 인덱스이며 도메인별 literal CRUD 경로와 입력 모델은 19.1이 규범적
원본이다.

| 분류 | Method | Path | 최소 권한 | 설명 |
|---|---|---|---|---|
| 인증 | POST | /admin/auth/login | 없음 | 관리자 로그인 |
| 인증 | POST | /admin/auth/refresh | refresh session | access token 갱신 |
| 인증 | POST | /admin/auth/logout | refresh session | 현재 session 폐기 |
| 인증 | GET | /admin/auth/me | 인증됨 | 현재 관리자·권한·축제 scope |
| 인증 | POST | /admin/auth/invitations/accept | 없음 | body token으로 초대 수락·최초 비밀번호 설정 |
| 인증 | POST | /admin/auth/password-reset-requests | 없음 | 계정 복구 요청 |
| 인증 | POST | /admin/auth/password-resets | 없음 | 복구 token으로 비밀번호 재설정 |
| 인증 | POST | /admin/auth/password/change | 인증됨 | 현재 비밀번호 변경 |
| 인증 | GET | /admin/auth/sessions | 인증됨 | 자신의 session 목록 |
| 인증 | DELETE | /admin/auth/sessions/{sessionId} | 인증됨 | 자신의 session 폐기 |
| 축제 회차 | GET | /admin/festivals | VIEWER | 축제 회차 목록 |
| 축제 회차 | POST | /admin/festivals | ADMIN + GLOBAL_FESTIVALS | 축제 회차 생성 |
| 축제 회차 | GET | /admin/festivals/{festivalId} | VIEWER | 축제 회차 상세 |
| 축제 회차 | PATCH | /admin/festivals/{festivalId} | ADMIN | 회차·capability·승인 근거 수정 |
| 축제 회차 | POST | /admin/festivals/{festivalId}/activate | ADMIN | 공개 서비스 회차 활성화 |
| 축제 회차 | POST | /admin/festivals/{festivalId}/deactivate | ADMIN | 공개 서비스 회차 비활성화 |
| 회차 전환 작업 | GET | /admin/festivals/{festivalId}/activation-operations | VIEWER | 회차 전환 작업 목록·복구 |
| 회차 전환 작업 | GET | /admin/festivals/{festivalId}/activation-operations/{operationId} | VIEWER | 활성 pointer·cache 반영 상태 |
| 회차 전환 작업 | POST | /admin/festivals/{festivalId}/activation-operations/{operationId}/retry-cache-invalidation | ADMIN | 실패한 전환 purge만 재시도 |
| 대시보드 | GET | /admin/festivals/{festivalId}/dashboard | VIEWER | 운영·게시·번역 요약 |
| 미리보기 | GET | /admin/festivals/{festivalId}/preview | VIEWER | 조립된 화면 snapshot 미리보기 |
| 다국어 | GET | /admin/festivals/{festivalId}/translation-status | VIEWER | 번역 누락·완성도 조회 |
| 홈 구성 | GET, PUT | /admin/festivals/{festivalId}/home-configuration | VIEWER, EDITOR | versioned 홈 구성 |
| 시간표 구성 | GET, PUT | /admin/festivals/{festivalId}/timetable-configuration | VIEWER, EDITOR | 날짜·눈금·무대 표현 설정 |
| 지도 구성 | GET, PUT | /admin/festivals/{festivalId}/map-configuration | VIEWER, EDITOR | versioned 지도 설정 |
| 지도 category | GET | /admin/festivals/{festivalId}/map-categories | VIEWER | category 표시 설정 목록 |
| 지도 category | PATCH | /admin/festivals/{festivalId}/map-categories/{categoryCode} | EDITOR | label·icon·순서·노출 수정 |
| 챗봇 구성 | GET, PUT | /admin/festivals/{festivalId}/chatbot-configuration | VIEWER, EDITOR | 안내·제안 질문·정책 설정 |
| 챗봇 테스트 | POST | /admin/festivals/{festivalId}/chatbot/test-messages | EDITOR | draft 지식 기반 테스트 |
| 라이브 시간표 | POST | /admin/festivals/{festivalId}/performances/{performanceId}/live-state | PUBLISHER | 지연·취소·유효 시각 긴급 반영 |
| 라이브 공간 | POST | /admin/festivals/{festivalId}/spaces/{spaceId}/live-state | PUBLISHER | 주점·부스 운영 일시 중단·취소 반영 |
| 라이브 시설 | POST | /admin/festivals/{festivalId}/map-markers/{markerId}/live-state | PUBLISHER | 독립 시설 운영 일시 중단·취소 반영 |
| 긴급 공지 | POST | /admin/festivals/{festivalId}/notices/{noticeId}/emergency-publish | PUBLISHER | 공지 검증·게시·purge 접수 |
| 긴급 공지 회수 | POST | /admin/festivals/{festivalId}/notices/{noticeId}/emergency-unpublish | PUBLISHER | 예약보다 우선해 긴급 공지 즉시 회수 |
| target 검증 | POST | /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/validate | PUBLISHER | working revision 검증 |
| target 예약 | POST | /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/schedule | PUBLISHER | 독립 target 예약 게시 |
| target 예약 취소 | POST | /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/cancel-schedule | PUBLISHER | 독립 target 예약 취소 |
| target 게시 | POST | /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/publish | PUBLISHER | 독립 target 즉시 게시 |
| target 게시 취소 | POST | /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/unpublish | PUBLISHER | 독립 target 공개 pointer 제거 |
| target rollback | POST | /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/rollback | PUBLISHER | 독립 target 과거 revision 재게시 |
| target 보관 | POST | /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/archive | PUBLISHER | 비공개 target 보관 |
| target 복원 | POST | /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/restore | ADMIN | 보관 target draft 복원 |
| target 이력 | GET | /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/revisions | VIEWER | revision 목록 |
| target revision | GET | /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/revisions/{revision} | VIEWER | 불변 revision 상세 |
| target revision 복원 | POST | /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/revisions/{revision}/restore-to-draft | ADMIN | 과거 revision을 새 draft로 복사 |
| 게시 batch | GET, POST | /admin/festivals/{festivalId}/publication-batches | VIEWER, PUBLISHER | batch 목록·생성 |
| 게시 batch | GET, PATCH | /admin/festivals/{festivalId}/publication-batches/{batchId} | VIEWER, PUBLISHER | batch 상태 조회·DRAFT 수정 |
| 게시 batch | POST | /admin/festivals/{festivalId}/publication-batches/{batchId}/validate | PUBLISHER | batch 정합성 검증 |
| 게시 batch | POST | /admin/festivals/{festivalId}/publication-batches/{batchId}/schedule | PUBLISHER | 검증된 batch 예약 |
| 게시 batch | POST | /admin/festivals/{festivalId}/publication-batches/{batchId}/apply | PUBLISHER | target별 게시·게시 취소 원자 적용 |
| 게시 batch | POST | /admin/festivals/{festivalId}/publication-batches/{batchId}/cancel | PUBLISHER | APPLYING 전 batch 취소 |
| 게시 batch | POST | /admin/festivals/{festivalId}/publication-batches/{batchId}/rollback | PUBLISHER | 이전 revision 재게시 |
| 게시 작업 | GET | /admin/festivals/{festivalId}/publication-operations | VIEWER | 게시·현장 작업 목록·복구 |
| 게시 작업 | GET | /admin/festivals/{festivalId}/publication-operations/{operationId} | VIEWER | 게시·cache 반영 상태 |
| 게시 작업 | POST | /admin/festivals/{festivalId}/publication-operations/{operationId}/retry-cache-invalidation | PUBLISHER | 실패한 purge만 재시도 |
| 축제 감사 | GET | /admin/festivals/{festivalId}/audit-logs | VIEWER | scope 안의 콘텐츠·게시 감사 목록 |
| 축제 감사 | GET | /admin/festivals/{festivalId}/audit-logs/{auditLogId} | VIEWER | scope 안의 감사 상세 |
| 전역 감사 | GET | /admin/audit-logs | GLOBAL_AUDIT | 전 축제·인증·계정 감사 목록 |
| 전역 감사 | GET | /admin/audit-logs/{auditLogId} | GLOBAL_AUDIT | 전역 감사 상세 |
| 미디어 | GET | /admin/media | VIEWER | 미디어 검색·선택 목록 |
| 미디어 | POST | /admin/media/upload-intents | EDITOR | 업로드 intent 발급 |
| 미디어 | POST | /admin/media/{mediaId}/complete | EDITOR | 업로드 검증 완료 |
| 미디어 | GET, PATCH | /admin/media/{mediaId} | VIEWER, EDITOR; restrictive rights는 PUBLISHER | 상태 조회·대체 텍스트·권리 메타 수정 |
| 미디어 | DELETE | /admin/media/{mediaId} | EDITOR | 미참조 미디어 삭제 |
| 미디어 복구 | GET | /admin/media/{mediaId}/maintenance-issues | VIEWER | 자동 정리·권리 purge 실패 목록 |
| 미디어 복구 | GET | /admin/media/{mediaId}/maintenance-issues/{issueId} | VIEWER | 실패 target·재시도 상태 |
| 미디어 복구 | POST | /admin/media/{mediaId}/maintenance-issues/{issueId}/retry | EDITOR | 실패 target 수동 재시도 |
| 관리자 계정 | GET, POST | /admin/accounts | GLOBAL_ACCOUNTS | 계정 목록·초대 |
| 관리자 계정 | GET, PATCH | /admin/accounts/{accountId} | GLOBAL_ACCOUNTS | 계정 상세·권한 수정 |
| 관리자 계정 | POST | /admin/accounts/{accountId}/revoke-sessions | GLOBAL_ACCOUNTS | 계정 session 강제 폐기 |
| 관리자 초대 | POST | /admin/accounts/{accountId}/invitation/resend | GLOBAL_ACCOUNTS | 만료·대기·회수 초대 재발급 |
| 관리자 초대 | POST | /admin/accounts/{accountId}/invitation/revoke | GLOBAL_ACCOUNTS | 대기 초대 회수 |

### 18.3 인증과 권한

#### 인증 endpoint

| Method | Path | 설명 |
|---|---|---|
| POST | /admin/auth/login | 관리자 로그인 |
| POST | /admin/auth/refresh | 세션 갱신 |
| POST | /admin/auth/logout | 현재 refresh session 폐기 |
| GET | /admin/auth/me | 현재 관리자와 권한 |
| POST | /admin/auth/invitations/accept | body token으로 초대 수락·최초 비밀번호 설정 |
| POST | /admin/auth/password-reset-requests | 복구 요청; 계정 존재 여부와 무관하게 같은 응답 |
| POST | /admin/auth/password-resets | 일회용 token으로 비밀번호 재설정 |
| POST | /admin/auth/password/change | 로그인한 계정의 비밀번호 변경 |
| GET | /admin/auth/sessions | 자신의 활성 session 목록 |
| DELETE | /admin/auth/sessions/{sessionId} | 자신의 session 폐기 |

표의 path에는 /api/v1이 생략되어 있다.

login body는 `email`, `password`를 받고 성공 시 data에 `accessToken`, `expiresAt`,
`account: AdminAccountSummary`를 반환하며 refresh cookie를 설정한다. refresh 성공도 같은
data shape과 회전된 refresh cookie를 반환하고, refresh와 logout은 body에 refresh token을
받지 않는다. invitation accept body는 `token`, `displayName`, `password`를, password reset request는
`email`을, reset 완료는 `token`, `newPassword`를 받는다. password change body는
`currentPassword`, `newPassword`다.

이 login·refresh·invitation accept의 공통 200 data component 이름은 AuthSessionResult이며
필수 `accessToken`, `expiresAt`, `account: AdminAccountSummary`만 가진다. invitation accept는
비활성 초대를 원자 소비해 계정을 ACTIVE로 전환하고 새 refresh session을 만든 뒤 같은
AuthSessionResult와 cookie를 반환한다. logout은 cookie를 만료시키고 204다.
password-reset-requests는 계정 존재·전달 가능 여부와 무관하게 202 data로 필수
`accepted=true`만 반환한다. password-resets는 token과 credential을 원자 갱신하고 기존 모든
session을 폐기하며 호출 browser의 refresh cookie도 만료시킨 뒤 204다.

password/change 성공은 credential과 authzVersion을 갱신하고 계정의 기존 모든 session을
폐기해 refresh cookie를 만료시킨 뒤 204를 반환한다. Idempotency-Key는 transaction 안의
중복 credential 변경을 막지만 응답 record에 password·access/refresh token이나 cookie를
보관하지 않는다. 성공 뒤 폐기된 session으로 온 재요청은 일반 인증 middleware에서 401이며
보안 token을 idempotency replay로 재발급하지 않는다. client는 다시 로그인한다.

AdminAccountSummary는 필수 `id`, `accountVersion`, `email`, `displayName`, `role`, `status`,
`globalPermissions`, `festivalScopes`, `permissions: EffectivePermissions`를 가진다.
permissions는 서버가 계산한
현재 유효 권한이며 client가 저장하거나 수정할 수 있는 입력값이 아니다.

`auth/me` data는 `accountId`, `email`, `displayName`, `role`, `globalPermissions`,
`permissions: EffectivePermissions`, `festivalScopes`, `sessionId`, `accessTokenExpiresAt`을
모두 필수로 반환한다.
session item은
`id`, `createdAt`, `lastSeenAt`, `expiresAt`, `current`, `ipSummary`, `userAgentSummary`를
가진다. 개인정보 노출을 줄이기 위해 IP는 전체 주소가 아닌 운영 정책상 필요한 범위로
축약한다.
`GET /admin/auth/sessions`는 200 data로 `items: AdminSession[]`만 반환한다. current=true를
먼저, 나머지는 lastSeenAt 내림차순·id 오름차순으로 정렬하고 활성 session 전체를 최대
100개 반환한다. 계정별 active session 상한도 100이며 새 session 생성이 이를 넘으면 current가
아닌 lastSeenAt이 가장 오래된 session을 transaction에서 revoke한다. 인증·session 응답은
22절에 따라 no-store다.

모든 계정 생성·login·복구의 email은 display name 없는 3~254자의 단일 addr-spec만 받는다.
서버는 앞뒤 ASCII 공백을 제거한 뒤 local-part를 Unicode NFC와 locale 비의존 case fold로,
domain을 IDNA2008 A-label과 lowercase로 변환한 canonicalEmail을 비교한다. local-part 64 octet,
domain과 전체 길이 제한도 변환 뒤 검증하며 제어 문자와 연속·앞뒤 dot을 거부한다.
canonicalEmail은 전역 unique index를 사용하고 `+tag`나 dot을 provider별로 임의 병합하지
않는다. 응답 email은 검증된 표시값이고 login·초대·reset·rate limit은 모두 같은
canonicalEmail 함수를 사용한다.

`DELETE /admin/auth/sessions/{sessionId}`는 If-Match를 받지 않고 인증된 계정이 소유한
session만 즉시 revoke한다. 이미 폐기됐거나 존재하지 않는 자기 session ID도 204를 반환해
idempotent하게 동작하며, 다른 계정의 session 여부는 노출하지 않는다. 현재 session을
폐기하면 refresh cookie도 만료시킨다. logout도 같은 현재-session revoke와 204 규칙을 쓴다.
`POST /admin/auth/password/change`는 인증된 상태 변경이므로 Idempotency-Key를 요구하고
19.4의 재생 규칙을 적용한다. login, refresh, logout, invitation accept, password reset
request·완료에는 Idempotency-Key를 요구하지 않으며 session·일회용 token의 원자적 소비와
아래 계정 존재 여부 비노출 규칙을 적용한다.

인증 규칙:

- access token은 10분 수명의 서명된 JWT이며 Authorization: Bearer로 전달한다.
- JWT는 최소 subject accountId, sessionId, authzVersion, issuer, audience, issued-at,
  expiration을 서명한다. 서버는 모든 관리자 요청에서 session revocation과 계정의 현재
  authzVersion을 일관된 저장소로 확인하고 불일치하면 401을 반환한다. 이 확인을 할 수
  없으면 권한을 추정하지 않고 503으로 fail closed한다.
- refresh token은 절대 수명 12시간, 유휴 수명 30분이며
  `__Host-festival-admin-refresh` Secure, HttpOnly, SameSite=Strict, Path=/ cookie로만
  전달한다. Domain은 설정하지 않는다.
- refresh token rotation과 재사용 탐지를 적용한다.
- refresh token record는 ACTIVE→ROTATED 전이를 CAS해 동시 요청 중 하나만 새 successor를
  만든다. 정상적인 다중 탭 경쟁을 침해 재사용과 구분하기 위해 최초 rotation 뒤 최대 5초의
  short grace에서 같은 이전 token fingerprint·session family의 재요청에는 암호화된 단기
  replay cache의 동일 successor cookie와 성공 응답을 반환하고 새 token을 만들지 않는다.
  replay cache는 grace 종료 즉시 폐기하며 원문 token을 로그나 영구 저장소에 남기지 않는다.
- grace 밖에서 이미 회전된 refresh token을 재사용하거나 successor chain이 맞지 않으면
  탈취로 판정해 해당 session family를 즉시 revoke하고 감사·보안 경보를 남긴다. 같은
  family의 access token은 session revocation 확인에서 다음 요청부터 거부한다. client도
  한 브라우저 context 안에서는 refresh single-flight를 사용하지만 서버 안전성을 그 구현에
  의존하지 않는다.
- access token을 브라우저 영구 저장소에 저장하지 않는다.
- 공개 관리자 회원가입 endpoint는 제공하지 않는다.
- 로그인 실패는 IP와 계정 기준으로 제한하고 잠금·복구 이벤트를 감사 기록한다.
- 로그아웃·session 폐기는 해당 session을 revoke한다. 비밀번호 변경·계정 비활성화와 역할·
  scope·전역 권한 변경은 authzVersion을 증가시켜 기존 access token을 다음 요청부터
  거부한다. 변경 전후 유효 permission 집합에서 하나라도 제거되면 모든 refresh session도
  revoke한다. permission이 추가되기만 하면 refresh session은 유지하고 다음 refresh가 새
  authzVersion의 access token을 발급한다.
- 비밀번호 reset 성공도 authzVersion을 증가시키고 계정의 모든 session을 revoke한다.
- refresh cookie를 읽거나 설정·삭제하는 login, refresh, logout, invitation accept,
  password-resets, password/change 요청은 OpenAPI에 필수 `Origin`과
  `Sec-Fetch-Site` header를 선언한다. Origin은 운영 설정의 단일 관리자 web origin과
  scheme·host·port까지 정확히 같아야 하고 `null`이나 누락을 허용하지 않는다.
  Sec-Fetch-Site는 정확히 `same-origin`이어야 한다. 검사는 cookie·body token 검증과 모든
  상태 변경보다 먼저 수행하며 실패하면 cookie를 회전·만료하거나 token을 소비하지 않고
  403 ADMIN_CSRF_INVALID를 반환한다.
- 위 endpoint는 application/json만 받고 form content type을 거부하며, CORS credential은 그
  관리자 origin 하나에만 허용한다. refresh cookie의 SameSite=Strict와 이 exact-origin/fetch
  metadata 검증을 함께 쓰므로 별도 readable CSRF cookie나 token header는 v1에서 발급하지
  않는다. Bearer access token만으로 호출하는 나머지 관리자 API는 브라우저가 자동 첨부하는
  credential이 아니므로 이 CSRF header 계약 대신 기존 Authorization·CORS 검사를 따른다.
- 초대·복구 token은 짧은 수명, 단일 사용이며 원문을 저장하지 않는다.
- 초대 token은 최대 72시간, 비밀번호 복구 token은 최대 30분 유효하다.
- 초대 수락은 사전에 생성된 비활성 계정에만 허용하며 공개 회원가입으로 동작하지 않는다.
- 비밀번호 복구 요청은 계정 존재 여부를 노출하지 않고 동일한 202 응답을 반환한다.
- 비밀번호는 trim하지 않은 15~128 Unicode code point로 받고 NFC 정규화 후 저장·검증한다.
  정규화된 전체 값을 유출·일반·예측 가능한 문맥별 차단 목록과 비교해 일치하면 거부한다.
  문자 종류 조합을 강제하거나 침해 징후 없이 주기적 변경을 요구하지 않으며, 붙여넣기와
  password manager 사용을 허용한다.
- 잘못된 로그인은 401 AUTHENTICATION_FAILED, 만료·사용된 초대 token은 400
  INVITATION_INVALID_OR_EXPIRED, 복구 token은 400 RESET_TOKEN_INVALID_OR_EXPIRED다.
- `auth/me`는 `accountId`, 역할, 서버가 계산한 `permissions`, 접근 가능한
  `festivalScopes`를 반환한다. client는 역할명만으로 권한을 추론하지 않는다.

Role:

| 역할 | 권한 |
|---|---|
| VIEWER | 관리자 데이터 조회·미리보기 |
| EDITOR | draft 생성·수정, 미디어 업로드 |
| PUBLISHER | 예약·게시·게시 취소·긴급 공지 |
| ADMIN | scope 안의 축제 활성화·회차 운영 관리 |

역할은 VIEWER < EDITOR < PUBLISHER < ADMIN 순으로 하위 권한을 포함한다. 역할과 별개로
festivalScopes가 요청 festivalId를 포함해야 한다. scope는 계정에 명시하며 이름·학과 등
학교 조직을 추측해 자동 매핑하지 않는다. 서버는 화면 노출 여부와 무관하게 모든 축제별
관리자 endpoint에서 역할과 festival scope를 검사한다.

전역 endpoint는 festivalScopes와 무관하게 아래 GlobalPermission을 요구한다. 해당
permission 자체가 전역 endpoint의 권한이며 festival role의 최소 등급을 추가로 요구하지
않는다. 단, 새 축제 생성은 생성 직후 운영 책임을 맡기 위해 예외적으로 ADMIN 역할도
함께 요구한다.

- GLOBAL_AUDIT: 전 축제와 인증·계정 감사 조회
- GLOBAL_ACCOUNTS: 관리자 계정·역할·scope 관리
- GLOBAL_FESTIVALS: 새 축제 회차 생성

festival ADMIN이라는 이유만으로 전역 permission을 부여하지 않는다. `auth/me`의
permissions가 이 값을 명시적으로 포함해야 한다.

최초 관리자 bootstrap은 HTTP endpoint나 공개 signup으로 제공하지 않는다. 새 환경의 control
plane이 비어 있을 때만 배포 권한을 가진 운영자가 감사 가능한 one-time provisioning job으로
초기 계정 하나를 `role=ADMIN`, `globalPermissions=[GLOBAL_ACCOUNTS,
GLOBAL_FESTIVALS]`, 빈 festivalScopes와 함께 만들고 bootstrap state를 NOT_STARTED에서
PENDING으로 바꾼다. NOT_STARTED인데 기존 관리자 계정이 하나라도 있거나 state=COMPLETED이면
아무 계정도 만들지 않고 실패해야 하며 동시 실행도 DB uniqueness와 transaction lock으로
하나만 성공한다. 초기 비밀번호를 로그·환경변수·명령행에
직접 넣지 않고 기존 초대 수락과 같은 단기 single-use token의 keyed digest만 저장해, 원문은
승인된 secret 전달 채널로 지정 수신자에게 한 번만 전달한다. 수신자가 수락하고 비밀번호를
설정해 ACTIVE가 된 뒤 state를 COMPLETED로 원자 확정하고 provisioning credential과 replay
secret을 폐기한다. PENDING에서 token이 만료·유실된 경우 같은 승인된 job은 명시적 rotate
mode로 정확히 그 초기 account의 미사용 token만 교체할 수 있다. 이전 token을 먼저 폐기하고
계정·role·globalPermissions를 바꾸거나 두 번째 계정을 만들 수 없으며 모든 시도를 감사한다.
감사 event에는 배포 actor, 생성 accountId, 부여 역할·전역 permission, 시각과 성공·실패 결과를
남기되 token 원문은 남기지 않는다. COMPLETED 뒤 재실행·일반 계정 생성·권한 변경은 반드시
`/admin/accounts` 계약을 사용한다. 운영 공개 전 readiness는 이 절차를 완료한 ACTIVE
초기 관리자 또는 그 관리자가 만든 ACTIVE 후속 관리자 중 최소 하나가 존재하는지 확인하며,
비어 있으면 fail closed한다. break-glass 복구는 별도 승인 runbook과 감사 절차로만 수행하고 이
API에 숨은 bypass credential을 두지 않는다.

EffectivePermissions는 필수 `festivals`, `global` 배열을 가진다. festivals item은 필수
`festivalId`와 `actions: FestivalPermission[]`이고 festivalId 순으로 정렬한다.
FestivalPermission은 READ, EDIT, PUBLISH, ADMIN이며 역할은 각각 VIEWER=[READ],
EDITOR=[READ,EDIT], PUBLISHER=[READ,EDIT,PUBLISH],
ADMIN=[READ,EDIT,PUBLISH,ADMIN]으로 계산한다. global은 계정에 실제로 부여되어 현재 유효한
GlobalPermission 배열이다. 두 배열은 값이 없으면 빈 배열이고 알 수 없는 문자열을
반환하지 않는다.

`GET /admin/festivals`는 호출자의 festivalScopes에 포함된 회차만 반환한다. 새 회차는
아직 festival scope가 없으므로 `POST /admin/festivals`에 ADMIN과 GLOBAL_FESTIVALS를
요구하며, 성공 시 새 festivalId를 요청자의 festivalScopes에 원자적으로 추가한다.
이 scope 추가는 permission 확대이므로 authzVersion을 증가시키되 refresh session은
유지하고 응답에 `X-Authz-Refresh-Required: true`를 보낸다. 현재 access token은 다음
요청부터 거부되므로 client는 refresh를 한 번 수행한다.
festival 목록은 `year`, `active`, `q`, `page`, `size`만 받고 200 data로
`items: AdminFestival[]`와 7.2 meta.pagination을 반환한다. q는 slug와 저장된 축제명에
적용하고 startsAt 내림차순·id 오름차순으로 고정하며 sort query는 받지 않는다.
festivalScopes는 중복 없는 UUID 배열이고 계정의 단일 role이 배열 안 모든 축제에
동일하게 적용된다. 축제마다 다른 역할이 필요하면 별도 계정을 만들거나 이후 계약 버전에서
scope별 role 모델을 도입하며 v1 응답에 암묵적으로 섞지 않는다.

## 19. 관리자 콘텐츠 API

### 19.1 도메인별 literal CRUD 경로

`{resources}`를 실제 API path parameter로 사용하지 않는다. 다음 collection과 item
경로를 OpenAPI에 각각 선언하며, collection은 GET·POST, item은 GET·PATCH·DELETE를
지원한다. DELETE는 아래 수명주기 규칙을 따른다.
모든 collection·item GET의 최소 역할은 VIEWER, draft POST·PATCH·DELETE의 최소 역할은
EDITOR다. PUBLISHER 또는 ADMIN을 요구하는 action은 18.2와 19.5에 별도로 명시하며 낮은
역할이 우회 호출할 수 없다.

| 관리자 기능 | collection path | item path | 생성 schema·PATCH 필드 원본 |
|---|---|---|---|
| 라인업 분류 | `/admin/festivals/{festivalId}/lineup-categories` | `/admin/festivals/{festivalId}/lineup-categories/{categoryId}` | LineupCategoryWrite |
| 아티스트 | `/admin/festivals/{festivalId}/artists` | `/admin/festivals/{festivalId}/artists/{artistId}` | ArtistWrite |
| 무대 | `/admin/festivals/{festivalId}/stages` | `/admin/festivals/{festivalId}/stages/{stageId}` | StageWrite |
| 공연 | `/admin/festivals/{festivalId}/performances` | `/admin/festivals/{festivalId}/performances/{performanceId}` | PerformanceWrite |
| 부스·주점·플리마켓 | `/admin/festivals/{festivalId}/spaces` | `/admin/festivals/{festivalId}/spaces/{spaceId}` | FestivalSpaceWrite |
| 지도 구역 | `/admin/festivals/{festivalId}/map-areas` | `/admin/festivals/{festivalId}/map-areas/{areaId}` | MapAreaWrite |
| 지도 마커 | `/admin/festivals/{festivalId}/map-markers` | `/admin/festivals/{festivalId}/map-markers/{markerId}` | 생성은 MapMarkerCreateWrite, 저장 projection·PATCH 원본은 MapMarkerWrite |
| 공지 | `/admin/festivals/{festivalId}/notices` | `/admin/festivals/{festivalId}/notices/{noticeId}` | NoticeWrite |
| 외부 링크 | `/admin/festivals/{festivalId}/external-links` | `/admin/festivals/{festivalId}/external-links/{externalLinkId}` | ExternalLinkWrite |
| 챗봇 지식 | `/admin/festivals/{festivalId}/chatbot-knowledge-entries` | `/admin/festivals/{festivalId}/chatbot-knowledge-entries/{entryId}` | ChatbotKnowledgeEntryWrite |

관리자 목록은 7.2의 page 규약과 `workingStatus`, `publicationState`, `scheduledAction`, `updatedBy`,
`updatedFrom`, `updatedTo`, `q` 필터를 공통으로 받는다. 각 endpoint는 자기 도메인의 필터만 추가하며
알 수 없는 필터는 400을 반환한다. 관리자 목록·상세는 locale fallback 결과가 아니라
저장된 모든 번역과 translationStatus를 반환한다.

도메인별 collection query는 다음 닫힌 allowlist를 사용한다. 표의 sort 필드는 asc·desc를
모두 허용하고, 지정하지 않으면 기본 정렬을 사용한다. 모든 정렬은 마지막에 id 오름차순을
붙여 결정적으로 만든다.

| collection | 추가 filter | 허용 sort 필드 | 기본 정렬 |
|---|---|---|---|
| lineup-categories | `code` | `code`, `order`, `updatedAt` | order asc, id asc |
| artists | `categoryId`, `featured` | `order`, `updatedAt`, `createdAt` | order asc, id asc |
| stages | 없음 | `order`, `updatedAt`, `createdAt` | order asc, id asc |
| performances | `stageId`, `artistId`, `date`, `manualStatus` | `scheduledStartsAt`, `effectiveStartsAt`, `order`, `updatedAt` | effectiveStartsAt asc, order asc, id asc |
| spaces | `type` | `type`, `order`, `updatedAt`, `createdAt` | type asc, order asc, id asc |
| map-areas | 없음 | `order`, `updatedAt`, `createdAt` | order asc, id asc |
| map-markers | `categoryCode`, `areaId`, `linkedSpaceId`, `stageId` | `categoryCode`, `order`, `updatedAt` | categoryCode asc, order asc, id asc |
| notices | `type`, `contentType`, `important` | `visibleFrom`, `updatedAt`, `createdAt` | updatedAt desc, id asc |
| external-links | `type` | `type`, `order`, `updatedAt` | order asc, id asc |
| chatbot-knowledge-entries | `kind`, `enabled`, `sourceType` | `priority`, `updatedAt`, `createdAt` | priority desc, updatedAt desc, id asc |

UUID·enum filter는 반복 가능하고 같은 field의 값은 OR, 서로 다른 field는 AND로 결합한다.
boolean과 date·기간 filter는 한 번만 허용한다. q는 저장된 모든 지원 언어의 승인 텍스트를
대상으로 trim 후 1~100자이며, updatedFrom과 updatedTo는 함께 또는 각각 보낼 수 있지만
둘 다 있으면 updatedFrom <= updatedTo여야 한다. 표에서 `없음`인 collection은 공통 filter만
허용한다.

각 `Admin<Resource>` 응답 component는 필수 `id`, 해당 `*Write`의 저장 projection,
8.2의 `adminVersion`, `workingRevision`, nullable `publishedRevision`, `workingStatus`,
`publicationState`, 세 nullable 예약 필드, 두 nullable pending operation ID,
`translationStatus`, 작성·수정·게시 actor와 시각을 결합한 닫힌 schema다. 공개 계산 필드를
관리자 저장값처럼 중복하지 않고 관계 확인에 필요한
읽기 전용 projection을 아래 예외처럼 추가한다. collection GET은
`data.items: Admin<Resource>[]`와 7.2 meta.pagination, item GET과 성공 PATCH는
`data: Admin<Resource>`, POST는 201·Location과 같은 data를 반환한다. 모든 item GET·쓰기
응답은 `ETag: "admin-{adminVersion}"`을 제공한다.

- AdminArtist는 socialLinks·tracks의 저장 번역과 media ID를, AdminPerformance는 연결
  stage·artist 식별자와 nullable activeOperationalOverride를 반환한다.
- AdminFestivalSpace는 MenuSectionWrite·MenuItemWrite·SpaceEventWrite의 서버 발급 ID가
  포함된 전체 aggregate와 읽기 전용 nullable mapMarkerId·areaId·activeOperatingOverride를
  반환한다.
- AdminMapMarker는 locationId, 연결 대상과 nullable activeOperatingOverride를,
  AdminMapConfiguration은 hidden 항목을 포함한 MapCategoryWrite 전체와
  MapTextOverlayWrite의 서버 발급 ID를 반환한다.
- child menu·event와 map category의 성공 shape·ETag는 각각 19.2와 singleton aggregate
  규칙을 우선한다. DELETE 성공은 data 없이 204다.

AdminFestival은 필수 `id`, `slug`, `year`, `translations.name`, nullable `heroMediaId`,
`startsAt`, `endsAt`, `timeZone`, `defaultLanguage`, `supportedLanguages`, `translationPolicy`,
`capabilities`, `capabilityApprovals`, `active`, nullable `pendingActivationOperationId`, 위
lifecycle·감사 필드를 가진다.
capabilityApprovals는 capability key별 nullable ApprovalReference인 닫힌 object이고 active는
전역 pointer에서 계산한 읽기 전용 boolean이다. 목록·상세·생성·PATCH 응답은
AdminFestival을 사용한다. 최근 작업 복구는 resource ETag와 분리된 activation operation
목록을 사용한다.

Festival 자체 관리 경로:

    GET   /admin/festivals
    POST  /admin/festivals
    GET   /admin/festivals/{festivalId}
    PATCH /admin/festivals/{festivalId}
    POST  /admin/festivals/{festivalId}/activate
    POST  /admin/festivals/{festivalId}/deactivate
    GET   /admin/festivals/{festivalId}/activation-operations
    GET   /admin/festivals/{festivalId}/activation-operations/{operationId}
    POST  /admin/festivals/{festivalId}/activation-operations/{operationId}/retry-cache-invalidation

activate와 deactivate는 ADMIN만 실행한다. 활성 회차는 public festivals/current의
선택 대상이며 운영 환경에는 동시에 하나의 활성 회차만 둔다. activate는 FESTIVAL과
필수 홈·시간표·지도 설정의 published revision, 승인 근거와 참조 검증을 통과해야 한다.
또한 `ko`, `en`, `zh` 각각의 공개 핵심 경로가 translationPolicy에 따라 완전한 콘텐츠나
명시적으로 허용된 fallback으로 응답 가능해야 하며 CONTENT_REVISION_UNAVAILABLE이 되는
언어가 하나라도 있으면 활성화를 거부한다.

    {
      "onExistingActive": "REJECT",
      "reason": "승인된 축제 회차 공개"
    }

deactivate body:

    {
      "reason": "행사 종료 후 공개 회차 해제"
    }

onExistingActive는 REJECT 또는 DEACTIVATE_EXISTING이며 기본값은 REJECT다.
DEACTIVATE_EXISTING은 기존 회차 비활성화와 새 회차 활성화를 원자적으로 수행한다.
호출자는 새 회차뿐 아니라 transaction 시점의 기존 active 회차에도 ADMIN 권한과
festival scope를 가져야 한다. worker는 commit 직전에 실제 active pointer 대상을 다시
권한 검사하며 scope가 없으면 어떤 pointer도 바꾸지 않고 403 PERMISSION_DENIED로
판정해 FestivalActivationOperation을 status=FAILED,
error.code=PERMISSION_DENIED·retryable=false로 끝낸다. 이미 202로 접수된 작업이므로 상태
조회 GET은 이 결과를 200으로 반환하며 뒤늦은 HTTP 403을 만들지 않는다. 한 회차의 ADMIN
권한으로 scope 밖 회차를 우회 비활성화할 수 없다.

activate와 deactivate body는 필수 `reason`을 받고 activate만 위
`onExistingActive`를 추가로 받는다. 두 POST는 Idempotency-Key와 festival ETag의
If-Match를 요구하고 202, Location과 FestivalActivationOperation을 반환한다. operation의
필수 필드는 `operationId`, `action: ACTIVATE|DEACTIVATE|RETRY_CACHE_INVALIDATION`,
`operationVersion`, nullable `parentOperationId`, nullable `pendingRetryOperationId`, nullable
`latestRetryOperationId`, `festivalId`, nullable `previousActiveFestivalId`,
`acceptedActivePointerVersion`, nullable `appliedActivePointerVersion`, nullable
`acceptedContentRevision`, nullable `appliedContentRevision`,
`status: QUEUED|APPLYING|INVALIDATING_CACHES|SUCCEEDED|SUCCEEDED_WITH_WARNINGS|FAILED`,
`cacheInvalidation`, nullable `error: ProblemSummary`, nullable `resolvedByOperationId`,
`requestedBy`, `requestedAt`, nullable
`completedAt`이다.

ACTIVATE와 DEACTIVATE_EXISTING 접수는 대상 회차의 현재 public head를 완전 검증하고 그
`contentRevision`을 acceptedContentRevision에 감사 기준으로 고정한다. DEACTIVATE는 두 content
revision이 모두 null이다. worker는 commit 직전에 대상 회차의 최신 public head를 다시 읽어
FESTIVAL·HOME_CONFIGURATION·TIMETABLE_CONFIGURATION·MAP_CONFIGURATION root, capability
dependency, ko·en·zh 번역/fallback, 모든 참조·미디어 권리와 7.1 공개 집합 상한을 재검증한다.
검증한 최신 contentRevision, 현재 active pointer version과 pendingActivationOperationId를 한
transaction의 CAS로 확인한다. 검증 중 public head가 바뀌면 최신 head로 최대 3회 다시
검증하고, 계속 경쟁하면 pointer를 바꾸지 않은 채 409 PUBLICATION_CONFLICT의 비동기 실패로
끝낸다. 성공 시 실제 활성화한 revision을 appliedContentRevision에 기록하므로 접수 뒤 정상
게시된 새 head를 활성화했는지 감사할 수 있다. 유효하지 않은 최신 head는 과거
acceptedContentRevision으로 조용히 되돌리지 않고 operation을 FAILED로 끝낸다.

worker는 현재 active pointer version을 CAS하고, DEACTIVATE_EXISTING이면 기존 회차 해제와
새 회차 지정을 같은 transaction에 적용한다. active 값이 바뀌는 기존·새 AdminFestival의
adminVersion도 같은 transaction에서 증가시켜 상세·목록 ETag가 active projection과 항상
일치하게 한다. pendingActivationOperationId는 cache invalidation을 포함한 operation이
terminal이 될 때까지 유지한다. commit 후 `/festivals/current`, festival
metadata, CDN, CLIENT_MANIFEST cache를 새 activePointerVersion으로 무효화한다. DB pointer
전환 전 실패는 FAILED이며 공개 pointer를 바꾸지 않는다. 전환 뒤 일부 purge가 실패하면
SUCCEEDED_WITH_WARNINGS로 남기고 19.5와 같은 target별 재시도·dashboard 경보를 적용한다.
운영 UI는 SUCCEEDED 또는 SUCCEEDED_WITH_WARNINGS 전에는 전환 완료로 표시하지 않는다.
공개 `/festivals/current`의 ETag와 cache key는 activePointerVersion을 포함한다.
재시도 endpoint는 ADMIN, If-Match가 아닌 Idempotency-Key와 필수 `reason`을 요구하며 이미
전환된 pointer를 다시 변경하지 않고 실패한 cache target만 새
action=RETRY_CACHE_INVALIDATION operation으로 재시도한다. 새 operation.parentOperationId는
원래 activate/deactivate operationId이고 원 operation의 parentOperationId는 null이다.
재시도 접수는 parent operation의 pendingRetryOperationId가 null인지 operationVersion CAS로
확인한 뒤 child ID를 pendingRetryOperationId와 latestRetryOperationId에 기록한다. child
terminal에서 pendingRetryOperationId만 지우며 리소스나 active pointer의 adminVersion은
바꾸지 않는다. 이미 retry가 pending이면 409 PUBLICATION_CONFLICT다. operationVersion은
상태·cache target·retry link가 바뀔 때마다 증가한다.

접수 시 서버는 전역 active-pointer record에 pendingActivationOperationId를 CAS로 예약하고
요청 회차와 현재 active 회차에도 같은 pendingActivationOperationId를 기록해 adminVersion을
증가시키며, 그 직전 pointer version을 acceptedActivePointerVersion에 고정한다. 다른 전환이 pending이면 409
PUBLICATION_CONFLICT이고 두 회차의 festival ETag만으로 동시에 접수하지 않는다. ACTIVATE의
REJECT는 현재 pointer가 null일 때만, DEACTIVATE는 현재 active festivalId가 path와 같을
때만 commit한다. DEACTIVATE_EXISTING은 접수·commit 시 실제 기존 회차 scope를 모두
검증한다. worker는 예상 active festivalId, acceptedActivePointerVersion, 재검증한 최신
contentRevision과 pendingActivationOperationId를 하나의 CAS로 확인하고 성공 시 증가된
pointer version과 실제 head revision을 각각 appliedActivePointerVersion,
appliedContentRevision에 기록한다. 실패하면 두 applied field는 null이고
pointer를 바꾸지 않은 채 전역·회차 reservation을 해제하고 영향 AdminFestival의
adminVersion을 다시 증가시킨다. pointer commit 뒤에는 cache invalidation의 terminal 전이와
같은 transaction에서 전역·영향 회차의 pendingActivationOperationId를 지우고 adminVersion을
증가시킨다. pending 동안 해당 회차 PATCH와
다른 activate/deactivate는
409 PUBLICATION_CONFLICT다. activation operation GET은 path festivalId가 요청 회차 또는
previousActiveFestivalId이고 호출자가 그 scope를 가질 때 조회할 수 있다. cache 재시도
operation은 parent의 appliedActivePointerVersion과 appliedContentRevision을 그대로 반환한다.

activation operation 목록은 `action`, `status`, `parentOperationId`, `requestedBy`와 7.2
pagination을 지원하고 requestedAt 내림차순, operationId 순으로 반환한다. path 회차가
festivalId 또는 previousActiveFestivalId인 작업만 포함하며 호출자 scope를 다시 검사한다.
Location을 잃은 client는 목록 또는 parent.latestRetryOperationId로 child를 복구한다.
목록 data.items는 FestivalActivationOperationSummary[]이고 meta.pagination은 7.2다. summary는
필수 `operationId`, `operationVersion`, `action`, `status`, nullable `parentOperationId`, nullable
`pendingRetryOperationId`, nullable `latestRetryOperationId`, nullable `resolvedByOperationId`,
`festivalId`, nullable `previousActiveFestivalId`, `failedCacheTargetCount`,
`pendingCacheTargetCount`, nullable `error: ProblemSummary`, `requestedBy`, `requestedAt`, nullable
`completedAt`만 가진다. 두 count는 0 이상의 integer이고 전체 cache target·accepted state는
detail에서만 반환한다.

versioned singleton 구성 경로:

    GET /admin/festivals/{festivalId}/home-configuration
    PUT /admin/festivals/{festivalId}/home-configuration
    GET /admin/festivals/{festivalId}/timetable-configuration
    PUT /admin/festivals/{festivalId}/timetable-configuration
    GET /admin/festivals/{festivalId}/map-configuration
    PUT /admin/festivals/{festivalId}/map-configuration
    GET /admin/festivals/{festivalId}/chatbot-configuration
    PUT /admin/festivals/{festivalId}/chatbot-configuration

각 singleton도 8.2의 workingRevision과 publishedRevision을 가진다. PUT만으로 공개
응답이 바뀌지 않으며 publication target 또는 batch로 게시해야 한다. map category는
`GET /map-categories`, `PATCH /map-categories/{categoryCode}`로 편집하지만
MAP_CONFIGURATION aggregate의 workingRevision과 함께 게시한다.
festival 생성은 이 네 singleton의 내용을 추정해 만들지 않는다. 아직 없는 singleton GET은
404 RESOURCE_NOT_FOUND이고 최초 PUT은 `If-None-Match: *`와 Idempotency-Key를 요구해
`adminVersion=1`, `workingRevision=1`, `workingStatus=DRAFT`, `publicationState=NEVER_PUBLISHED`인
aggregate를 원자 생성한다. 성공은 201·Location·`ETag: "admin-1"`과 아래 data component를
반환한다. 같은 종류의 singleton 생성 경쟁은 하나만 성공하며 이미 존재하면 상태를 바꾸지
않고 412 REVISION_MISMATCH다. 그 뒤의 모든 PUT은 현재 `If-Match: "admin-{adminVersion}"`을
요구하고 200을 반환한다. map category 경로는 MAP_CONFIGURATION이 생성되기 전 404이고,
category PATCH로 singleton을 우회 생성할 수 없다.
singleton GET과 성공 PUT의 data는 endpoint별로 AdminHomeConfiguration,
AdminTimetableConfiguration, AdminMapConfiguration, AdminChatbotConfiguration 중 하나다.
각 component는 해당 `*Write` 저장 projection과 8.2 lifecycle 필드를 결합한 닫힌 schema이며
응답 ETag는 `"admin-{adminVersion}"`이다.
map category 목록·PATCH 응답은 map-configuration aggregate의
`ETag: "admin-{adminVersion}"`을 사용하고 PATCH는 이를 If-Match로 요구한다. 성공 시
aggregate의 adminVersion과 workingRevision을 함께 증가시킨다. GET data는 필수
`items: MapCategoryWrite[]`, PATCH data는 필수 `category: MapCategoryWrite`만 가진다. items는
hidden을 포함해 order, code 순이며 category 별도의 version이나 경쟁 제어 token을 만들지
않는다. 전체 configuration이 필요하면 singleton GET을 사용하며 동일 배열을 한 응답에
중복하지 않는다.

### 19.2 부스 메뉴와 현장 이벤트

메뉴 section, item, 현장 event는 FestivalSpace aggregate 안에서 관리한다.

    POST   /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections
    PATCH  /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId}
    DELETE /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId}
    POST   /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId}/items
    PATCH  /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId}/items/{itemId}
    DELETE /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId}/items/{itemId}
    POST   /admin/festivals/{festivalId}/spaces/{spaceId}/events
    PATCH  /admin/festivals/{festivalId}/spaces/{spaceId}/events/{eventId}
    DELETE /admin/festivals/{festivalId}/spaces/{spaceId}/events/{eventId}

하위 리소스 쓰기의 If-Match와 응답 ETag는 개별 item이 아니라 상위 space의
`"admin-{adminVersion}"`을 사용한다. 성공한 하위 변경은 상위 space의 adminVersion과
workingRevision을 모두 증가시킨다. 하위 리소스 변경은 공개 데이터를 직접 바꾸지 않는다.
모든 하위 변경의 최소 역할은 EDITOR다. POST는 201, 새 child URL의 Location, 갱신된 상위
space ETag와 `data: { "space": AdminFestivalSpace, "child": AdminSpaceChild }`를 반환한다.
PATCH는 같은 data shape, 갱신 ETag와 200을 반환한다. AdminSpaceChild는 endpoint에 따라
서버 발급 `id`와 MenuSectionWrite, MenuItemWrite 또는 SpaceEventWrite의 저장 projection을
가지는 닫힌 oneOf다. DELETE는 body 없는 204와 갱신된 상위 space ETag를 반환하므로 다음
하위 쓰기는 그 ETag를 사용한다. item이 남은 menu section의 DELETE는 409 RESOURCE_IN_USE이고
암묵적 cascade 삭제를 하지 않는다.

POST와 PATCH는 상위 FestivalSpace aggregate를 잠그고 변경 뒤 section·section별 item·전체
item·event 수를 7.1의 상한으로 검사한다. 초과하면 child 생성, 상위 adminVersion과
workingRevision 변경 없이 422 VALIDATION_ERROR를 반환하고 Problem Details extensions에
`collection`, `actualCount`, `maximumCount`를 넣는다. 같은 검사는 publication validate와
apply worker의 최종 public head CAS에서도 실행한다.

### 19.3 대시보드, 번역 현황과 미리보기

`GET /admin/festivals/{festivalId}/dashboard`의 data는 다음 필수 필드를 가진 닫힌
Dashboard다.

- `festivalId`
- nullable `publicContentRevision`, nullable `mapVersion`
- `resourceCounts: DashboardResourceCount[]`
- `translationCounts: DashboardTranslationCount[]`
- `scheduledBatches: ScheduledBatchSummary[]`
- `failedOperations: FailedOperationSummary[]`
- `cacheInvalidationCounts: { pending, failed }`
- `operationalHighlights: OperationalHighlight[]`
- `rightsExpiryWarnings: MediaRightsWarning[]`
- `mediaMaintenanceIssues: MediaMaintenanceIssueSummary[]`
- `scheduledBatchTotal`, `failedOperationTotal`, `operationalHighlightTotal`,
  `rightsExpiryWarningTotal`, `mediaMaintenanceIssueTotal`
- `generatedAt`

DashboardResourceCount는 필수 `resourceType`, `workingStatus`, `publicationState`, nullable
`scheduledAction: PUBLISH|UNPUBLISH`, 0 이상의 `count`, nullable `latestUpdatedAt`을 가진다.
같은 네 상태 차원의 집계이며 resourceType, workingStatus, publicationState,
scheduledAction(null 우선) 순으로 반환한다. DashboardTranslationCount는 필수 `language`,
0 이상의 `completeCount`, `incompleteCount`, `fallbackAllowedCount`, `blockingCount`를
가지며 회차 supportedLanguages 순으로 반환한다.

ScheduledBatchSummary는 필수 `batchId`, `status=SCHEDULED`, `scheduledApplyAt`,
`actions: (PUBLISH|UNPUBLISH)[]`, `targetCount`, `detailPath`를 가진다. actions는 PUBLISH,
UNPUBLISH 중 batch에 실제 포함된 값을 중복 없이 반환한다. FailedOperationSummary는 필수
`operationKind: PUBLICATION|ACTIVATION`, `operationId`, nullable `batchId`, `action`,
`status: FAILED|SUCCEEDED_WITH_WARNINGS`, `attentionAt`, nullable `error: ProblemSummary`,
`detailPath`를 가진다. FAILED이면 error가 non-null이고 SUCCEEDED_WITH_WARNINGS이면
top-level error는 null이며 detail의 실패 CacheInvalidationTarget이 원인을 제공한다.
PUBLICATION action은 19.5의
PublicationOperation action enum이고 detailPath는 publication-operations 상세이다.
ACTIVATION action은 ACTIVATE, DEACTIVATE, RETRY_CACHE_INVALIDATION 중 하나이고 detailPath는
activation-operations 상세이며 batchId는 null이다. CacheInvalidationCounts의 pending과
failed는 0 이상의 integer이고 publication·activation operation을 모두 집계한다.

MediaMaintenanceIssueSummary는 필수 `issueId`, `mediaId`,
`kind: UPLOAD_CLEANUP|RIGHTS_INVALIDATION`, `status: OPEN|RETRYING`,
`attentionAt`, nullable `error: ProblemSummary`, `detailPath`, `retryPath`를 가진다. detailPath는
`/admin/media/{mediaId}/maintenance-issues/{issueId}`, retryPath는 그 뒤 `/retry`이고 두 path의
mediaId는 item과 같아야 한다. 마지막 시도가 실패한 OPEN은 error가 non-null이고 RETRYING은
null이다. Festival dashboard에는 ownerFestivalId가 path festivalId와 같은 issue만 포함한다.
RESOLVED issue는 dashboard 배열·total에서 제외하고 상세·감사 보관만 한다.

failedOperations와 cacheInvalidationCounts.failed에는 아직 성공한 retry descendant가 모든
실패 cache target을 회복하지 못한 operation·target만 포함한다. 역사적으로
SUCCEEDED_WITH_WARNINGS인 parent라도 각 실패 target의 resolvedByOperationId가 non-null이면
실패 수에서 제외하고, 전부 해결되면 parent.resolvedByOperationId도 최종 성공 child ID로
설정한다. 원 실패와 재시도 이력 자체는 상세·감사 로그에 그대로 남긴다.

OperationalHighlight는 필수 `type: EMERGENCY_NOTICE|PERFORMANCE|SPACE|STANDALONE_MARKER`,
`resourceType`, `resourceId`, `state`, `displayLabelTranslations`, `updatedAt`, `detailPath`를 가진다.
닫힌 discriminator mapping은 EMERGENCY_NOTICE → resourceType=NOTICE·state=ACTIVE,
PERFORMANCE → resourceType=PERFORMANCE·state=LIVE|DELAYED|CANCELED, SPACE →
resourceType=SPACE·state=PAUSED|CANCELED, STANDALONE_MARKER →
resourceType=MAP_MARKER·state=PAUSED|CANCELED이다. 다른 조합을 반환하지 않는다.
displayLabelTranslations는 회차 supportedLanguages key별 저장값 전체를 반환하는
language→string|null 닫힌 map이며 관리자 UI가 표시 언어를 선택한다.
긴급 공지, LIVE·DELAYED·CANCELED 공연, PAUSED·CANCELED 공간·독립 시설만 포함한다.
scheduledBatches는 scheduledApplyAt, batchId 순으로 최대 20개, failedOperations는
attentionAt 내림차순, operationId 순으로 최대 20개, operationalHighlights는 type, state,
updatedAt 내림차순, resourceId 순으로 최대 50개를 반환한다. 잘린 전체 수는 각각
`scheduledBatchTotal`, `failedOperationTotal`, `operationalHighlightTotal`이라는 필수
0 이상의 integer로 Dashboard에 함께 반환한다.
mediaMaintenanceIssues는 attentionAt 내림차순·issueId 순으로 최대 20개이고 잘리기 전 OPEN·
RETRYING 수를 mediaMaintenanceIssueTotal에 반환한다. rightsExpiryWarnings와 그 total은 아직
만료되지 않은 사전 경고, maintenance issue는 이미 자동 처리에 실패해 조치가 필요한 상태라
같은 mediaId가 필요하면 두 배열에 함께 나타날 수 있다.

MediaRightsWarning은 필수 `mediaId`, `purpose`, `usageExpiresAt`,
`severity: UPCOMING|URGENT|EXPIRED`, `referenceCount`, `detailPath`를 가진다. 만료 30일 전부터
포함하고 7일 이하는 URGENT, 지난 값은 EXPIRED다. usageExpiresAt, mediaId 순으로 최대 50개를
반환하며 전체 수는 rightsExpiryWarningTotal이다.

대시보드는 요약 조회이며 상태 전이를 일으키지 않는다. detailPath는 API base 아래의
상대 관리자 경로이고 호출자의 scope 밖 경로를 만들지 않는다. 집계는 generatedAt 시점의
일관된 DB snapshot에서 계산한다.

`GET /admin/festivals/{festivalId}/translation-status` query:

| 이름 | 타입 | 설명 |
|---|---|---|
| language | `ko`, `en`, `zh`, 반복 가능 | 검사 언어 |
| resourceType | TranslationStatusResourceType, 반복 가능 | 도메인 필터 |
| completeness | COMPLETE, INCOMPLETE, FALLBACK_ALLOWED | 완성도 |
| workingStatus | WorkingStatus | 편집본 상태 필터 |
| publicationState | PublicationState | 현재 공개 pointer 필터 |
| mediaStatus | MediaStatus | 미디어 행 처리 상태 필터 |
| page / size | 7.2 | 관리자 pagination; sort query는 허용하지 않음 |

각 item은 필수 `resourceType`, `resourceId`, `language`, `displayLabel`,
`completeness: COMPLETE|INCOMPLETE|FALLBACK_ALLOWED`, `missingFields: string[]`,
`fallbackAllowed`, nullable `lastTranslatedBy`, nullable
`lastTranslatedAt`, `resourceVersion`, nullable `workingStatus`, nullable
`publicationState`, nullable `mediaStatus`, `detailPath`를 반환한다. 번역 이력이 없으면 두
lastTranslated 필드는 모두 null이다. missingFields는 OpenAPI property path 문자열이며
사전식 순서, item 목록은 resourceType, resourceId, language 순으로 결정적으로 정렬한다.
detailPath는 호출자 scope 안의 원본 관리자 편집 경로다.
completeness query는 이 응답 필드와 정확히 일치하며 client가 missingFields에서 상태를
재계산하지 않는다.
성공 data는 `items: TranslationStatusItem[]`이고 meta.pagination은 7.2다. 목록은
resourceType·resourceId·language 오름차순으로 고정한다.
TranslationStatusResourceType은 19.5의 PublicationResourceType 전체와 MEDIA다. MEDIA
행은 AdminMedia.altTranslations와 이를 참조하는 working/public 후보의 필수 대체 텍스트를
검사하며 detailPath는 `/admin/media/{mediaId}`다.
resourceVersion은 `{ "kind": "WORKING_REVISION", "value": integer }` 또는
`{ "kind": "MEDIA_VERSION", "value": integer }`인 닫힌 tagged union이다. 콘텐츠 행은
첫 variant와 non-null workingStatus·publicationState, null mediaStatus를 반환한다. MEDIA
행은 둘째 variant와 non-null mediaStatus, null인 두 콘텐츠 상태를 반환한다.
workingStatus/publicationState 필터는 콘텐츠 행만, mediaStatus는 MEDIA 행만 선택한다.
resourceType에 MEDIA를 명시하면서 콘텐츠 상태 필터를 보내거나 MEDIA가 아닌 type과
mediaStatus를 보내면 400 FILTER_NOT_SUPPORTED다.

`GET /admin/festivals/{festivalId}/preview` query:

| 이름 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| surface | HOME, LINEUP, TIMETABLE, SPACES, MAP, NOTICES, CHATBOT | 예 | 조립할 화면 데이터 |
| lang | language | 예 | fallback 결과를 확인할 언어 |
| source | WORKING, BATCH, PUBLISHED | 예 | 미리보기 기준 |
| batchId | UUID | source=BATCH | 검증할 batch |
| at | datetime | 아니요 | 현재 공연·노출 기간을 계산할 가상 서버 시각 |
| page / size | integer | 목록 surface만 | LINEUP·SPACES·NOTICES의 1-based page, size 1~200 |

미리보기는 공개 API와 같은 serializer, fallback, 링크·이미지·map snapshot 조립 규칙을
사용하되 관리자 인증과 `Cache-Control: no-store`를 적용한다. PreviewResult data는 필수
`surface`와 `payload`만 가진 닫힌 oneOf다. discriminator mapping은 다음과 같다.

- HOME: 10.3 Home data
- LINEUP: `items: ArtistSummary[]`
- TIMETABLE: 12.3 performance list data
- SPACES: `items: FestivalSpaceSummary[]`
- MAP: `configuration: MapConfiguration`, `markers: MapMarker[]`
- NOTICES: `items: NoticeSummary[]`
- CHATBOT: 17.1 ChatbotConfiguration data

각 payload item은 해당 공개 endpoint와 정확히 같은 localized component·nullable 규칙을
사용한다. LINEUP·SPACES·NOTICES만 page·size를 받고 응답 meta.previewPagination에 필수
`page`, `size`, `totalElements`, `totalPages`를 반환하며 다른 surface에서 두 query를 보내면
400 INVALID_QUERY_PARAMETER다. MAP의 configuration과 markers는 하나의 preview map
snapshot이고 언어·좌표 규칙을 공유한다.

WORKING·BATCH preview는 공개 serializer 실행 전에 그 surface 후보에 19.5와 같은 번역·참조·
media·구조 검증을 수행한다. 요청 언어와 허용 fallback 언어 모두에서 필수 필드를 만들 수
없거나 required reference/media가 없어 공개 component를 만족하지 못하면 nullable placeholder나
부분 payload를 200으로 반환하지 않는다. 대신 422 VALIDATION_ERROR와 Problem Details의 닫힌
`validationResult: PublicationValidationResult` extension을 반환하고 valid=false 및 해당
errors·translationChecks·referenceChecks·referencedMedia·validatedTargets를 채운다. preview는
상태를 바꾸지 않으며 이 오류에서도 previewSnapshotId나 batch를 생성하지 않는다.
`validationWarnings`는 valid=true라 공개 payload를 완전히 만들 수 있는 200 응답의 비차단
경고만 담는다. PUBLISHED source의 committed head가 runtime 권리 만료 등으로 더 이상 같은
serializer를 만족하지 못하면 공개 API와 동일하게 503 CONTENT_REVISION_UNAVAILABLE이다.

응답 meta의 필수 필드는 `source`, `previewSnapshotId`, `evaluationTime`,
`workingRevisions[]`, `referencedMedia: ValidatedMediaReference[]`, nullable `batchId`,
nullable `baseContentRevision`, `language`,
`validationWarnings: ValidationIssue[]`이다. workingRevisions item은 필수 `resourceType`,
`resourceId`, `revision`을 가진다. source=PUBLISHED이면 workingRevisions는 빈 배열이고
batchId는 null이다. source=WORKING이면 batchId는 null이며 조립 시점의 관련 working
revision을 하나의 불변 previewSnapshotId로 고정한다. source=BATCH이면 같은 festivalId의
DRAFT·VALIDATED·SCHEDULED batchId가 필수이고 그 target revision을 고정한다. 다른 상태나
다른 축제 batch는 409 PUBLICATION_CONFLICT 또는 404 RESOURCE_NOT_FOUND다. `at`이 없으면
요청 serverTime을 evaluationTime으로 쓴다. 미리보기는 게시나 cache purge를 수행하지 않는다.
referencedMedia도 previewSnapshotId에 결합되어 같은 preview 안에서 media metadata가
바뀌지 않으며, 보존할 수 없으면 409 REVISION_CHANGED로 새 preview를 요구한다.

### 19.4 쓰기 DTO와 요청

각 쓰기 schema는 알 수 없는 필드를 거부하며 ID, 수명주기, 작성자·시각은 서버가
생성한다. 번역 map은 draft에서 일부 언어가 비어 있을 수 있지만 게시 검증 시 회차의
필수 언어 정책을 적용한다.

표의 `*Write`는 POST 또는 singleton PUT의 완전한 입력 component다. 단, 지도 마커 POST는
생성 명령을 분리한 MapMarkerCreateWrite를 받고 성공 뒤 MapMarkerWrite로 canonicalize한다. item PATCH는 별도
`*Patch` component를 사용하며 해당 `*Write`에서 생성 후 불변이라고 명시한 필드를 제외한
property를 모두 optional로 바꾼 닫힌 JSON Merge Patch다. PATCH는 하나 이상의 property를
포함해야 하고, 병합 결과 전체를 원래 `*Write`의 required·variant·참조 규칙으로 다시
검증한다. null은 원본 property가 nullable일 때만 값 삭제로 허용하며 배열 property가
있으면 배열 전체를 교체한다. ID, code 등 불변 필드와 빈 body는 422로 거부한다.

| schema | 핵심 입력 필드 | 주요 검증 |
|---|---|---|
| LineupCategoryWrite | `code`, `translations.label`, `order` | code는 회차 안에서 유일하고 표시명과 분리; 게시 집합은 최대 20개 |
| ArtistWrite | `translations.name/introduction`, `profileMediaId`, `categoryId`, `featured`, `socialLinks`, `tracks`, `order` | 아래 닫힌 nested schema, capability, READY media, 승인 URL |
| StageWrite | `translations.name`, `order` | marker 관계는 MapMarkerWrite가 소유 |
| PerformanceWrite | `translations.title/description/statusMessage`, `stageId`, `artistIds`, scheduled/effective 시각, `manualStatus`, `order` | manualStatus는 NORMAL·DELAYED·CANCELED; 시간·겹침·기간 검증 |
| FestivalSpaceWrite | `type`, `translations`, `operatingPeriods`, `contact`, `mediaItems`, `order` | 아래 닫힌 contact·media schema; marker·area 관계는 읽기 전용 projection |
| MenuSectionWrite | `translations.name`, `order` | 상위 space workingRevision 안에서 ID·순서 유일 |
| MenuItemWrite | `translations.name/description/priceLabel/allergens`, `price`, `availability`, `featured`, `mediaId`, `order` | KRW 금액·구조화 텍스트·READY media |
| SpaceEventWrite | `translations.title/description`, effective 시각, `manualStatus`, `action` | action은 OPEN_FESTIVAL_SPACE·OPEN_MAP·null만; 상위 space의 장소 사용 |
| MapAreaWrite | `translations.name/description`, `bounds`, `order` | 학교 약칭 기반 ID 금지, 별도 구역 경계 유지 |
| MapMarkerCreateWrite / MapMarkerWrite | 생성 시 `locationRef`, 저장 시 `locationId`; 공통 `categoryCode`, `areaId`, `translations.name/description/locationText/aliases/accessibilityText`, `position`, `linkedSpaceId`, `stageId`, `operatingPeriods`, nullable `icon`·`markerStyle`, `order` | locationRef는 생성 명령에만 존재하고 canonical locationId는 생성 후 불변; 아래 닫힌 locale map·좌표·연결 variant 검증 |
| MapCategoryPatch | `translations.label`, `icon`, `markerStyle`, `order`, `visible` | icon alt는 MapIconWrite만 소유; categoryCode 변경 금지 |
| NoticeWrite | `type`, `contentType`, `translations`, `important`, 출처·URL, `visibleFrom/Until` | emergency는 type에서 파생; RichText·URL·노출 기간 검증 |
| ExternalLinkWrite | `type`, `translations.title/description`, `url`, `mediaId`, `order` | capability와 승인 도메인 |
| HomeConfigurationWrite | current item type·limit, curated item, quick link | capability가 꺼진 action 금지 |
| TimetableConfigurationWrite | `dayViews: TimetableDayViewWrite[]`, `gridIntervalMinutes`, `stagePresentation`, `currentTimeLineEnabled` | 12.2 규칙과 닫힌 날짜 번역 schema |
| MapConfigurationWrite | 지도 자산·text strategy·좌표계·viewport·filter 설정 | 14.3 규칙, marker와 원자 게시 |
| ChatbotConfigurationWrite | 인사말, suggestion, 허용 source type, 응답 제한 | 승인된 링크·리소스만 참조 |
| ChatbotKnowledgeEntryWrite | `kind`, 질문·답변 번역, `sourceRefs: ChatbotSourceRefWrite[]`, `enabled`, `priority` | 닫힌 공식 source union, 공개 사실과 충돌 금지 |

FestivalCreateWrite의 필수 필드는 `slug`, `year`, `translations.name`, `startsAt`,
`endsAt`, `timeZone`, `defaultLanguage`, `supportedLanguages`, `translationPolicy`다.
slug는 3~64자의 소문자 ASCII·숫자·하이픈이고 전역에서 유일하다. timeZone은 v1에서
Asia/Seoul이며 startsAt < endsAt이어야 한다. supportedLanguages는 5.2의 v1 언어 집합을
정확히 포함해야 한다. create에는 capabilities와 capabilityApprovals를 허용하지 않고 서버가
모두 false·null로 만든다. 확정 기능도 실제 데이터·운영 준비 뒤 approvalReference를 갖춘
Festival PATCH로만 활성화한다.

FestivalPatchWrite는 JSON Merge Patch이며 `translations.name`, `heroMediaId`, `startsAt`, `endsAt`,
`defaultLanguage`, `supportedLanguages`, `translationPolicy`, `capabilityChanges`,
`approvalReference`, `reason`만 허용한다. 하나 이상의 변경 필드와 reason이 필수다. id,
slug, year, timeZone, 수명주기·revision·감사 필드는 생성 후 불변이며 PATCH에서 거부한다.
capability를 true로 바꾸면 같은 요청에 approvalReference가 필수이고 false로 바꿀 때도
reason을 감사 기록한다. 이 PATCH는 FESTIVAL workingRevision만 바꾸며 공개 응답은
FESTIVAL publication target을 게시하기 전까지 바뀌지 않는다.
heroMediaId는 null 또는 같은 festivalId가 소유한 READY media여야 하며, public
Festival.heroMedia는 선택된 언어의 Media projection이다.

CapabilityChanges는 core v1에서 optional boolean property `artistSearch`, `artistDetail`,
`artistSocialLinks`, `artistTracks`, `externalLinks`, `publicFaq`, `chatbot`만 허용하는 닫힌
object다. 하나 이상의 실제 값 변경이 필요하고 null, 빈 object, 알 수 없는 key와 현재 값의
반복은 422 VALIDATION_ERROR다. 여러 key를 한 요청에서 바꿀 수 있으며 10.2의 capability
dependency는 병합된 전체 값으로 검사한다. ApprovalReference는 필수 `referenceType:
APPROVED_PRODUCT_DECISION|OFFICIAL_OPERATIONS_APPROVAL|PRIVACY_REVIEW|RISK_ACCEPTANCE`,
`referenceId`, `approvedAt`과 nullable `note`만 가진 닫힌 schema다. referenceId는 승인
저장소가 발급한 1~200자의 opaque 식별자, approvedAt은 serverTime 이하의 offset 포함 RFC
3339 instant, non-null note는 1~500자이며 비밀·개인정보를 넣지 않는다.

capabilityChanges에 false→true 전이가 하나라도 있으면 approvalReference가 필수이고 그
요청의 모든 false→true key에 같은 immutable reference를 capabilityApprovals의 해당 key로
저장한다. 서로 다른 승인 근거가 필요한 key는 별도 PATCH로 나눈다. true→false key의 현재
capabilityApprovals 값은 null로 만들되 과거 reference는 감사 event에 보존한다. true 전이가
없거나 capabilityChanges가 생략됐으면 approvalReference를 보내지 않아야 한다. AdminFestival의
capabilityApprovals는 core capability key를 모두 가지며 각 값은 ApprovalReference 또는 null이다.

기간 또는 언어 정책 변경은 전체 preview를 통과해야 한다. 이미 공개 콘텐츠에 영향을
주면 FESTIVAL과 영향받는 HOME_CONFIGURATION·TIMETABLE_CONFIGURATION·MAP_CONFIGURATION 및
관련 리소스를 하나의 publication batch로 게시한다. 날짜는 모든 공연·공간 운영 기간과
공지 노출 기간을 포함해야 하고, 언어 변경은 5.2 번역·지도 자산 검증을 다시 수행한다.

구성 schema의 필수 필드와 범위:

- HomeConfigurationWrite: `currentItemTypes`, `currentItemLimit` 0~50,
  `upcomingPerformanceLimit` 0~20,
  `importantNoticeLimit` 0~10, `highlightArtistLimit` 0~20, `curatedItems`, `quickLinks`.
  currentItemTypes는 PERFORMANCE, SPACE_EVENT, CURATED 중 중복 없는 0~3개 배열이고 빈
  배열이면 currentItems를 비운다. 저장·응답 순서는 PERFORMANCE, SPACE_EVENT, CURATED다.
  curatedItems는 최대 20개, quickLinks는 최대 20개이고 값이 없으면 빈 배열이다. item ID와
  order는 각 배열 안에서 유일해야 한다.
- TimetableConfigurationWrite: 필수 `dayViews`, `gridIntervalMinutes`,
  `stagePresentation`, `currentTimeLineEnabled`만 받는다. TimetableDayViewWrite의 필수
  non-null 필드는 `date`, `translations.label`, `windowStartsAt`, `windowEndsAt`, `order`다.
  translations의 각 non-null 언어 값은 label 하나만 가진다. public
  TimetableDayView.label은 선택된 언어의 projection이며 updatedAt은 서버 생성이다. 배열
  크기·유일성·시간 구간·정렬 규칙은 12.2와 같다.
- MapConfigurationWrite: 필수 `mapType`, `mapTextStrategy`, `coordinateSystem`,
  `defaultViewport`, `filterConfiguration`, `categories`, `textOverlays`와 strategy별
  `baseMediaId` 또는 `localizedMediaIds`, nullable `contentBounds`, nullable `geographicMap`을
  받는다. 공개 응답의
  `snapshotId`, `mapVersion`, `displayMedia`, `areas`, `updatedAt`은 읽기 전용이다.
  areas는 MapArea CRUD가 소유하고 categories만 MAP_CONFIGURATION aggregate에 포함한다.
  snapshotId와 mapVersion은 지도 batch 게시 때 서버가 발급한다.
- ChatbotConfigurationWrite: 필수 `translations`, `allowedSourceTypes`, `maxSources` 1~10,
  `maxActions` 0~5, `maxMessageLength` 1~2000, `maxConversationTurns` 1~30을 받는다.
  translations의 각 non-null 언어 값은 필수 `welcomeMessage`, `inputPlaceholder`,
  `suggestions: string[]`만 가진다. suggestions는 최대 8개, 각 문자열은 1~120자이며
  중복할 수 없다. nullable 필드는 없고 비어 있는 suggestions는 빈 배열이다.
  allowedSourceTypes는 ChatbotSourceResourceType의 중복 없는 비어 있지 않은 배열이다.
  ChatbotSourceResourceType은 FESTIVAL, ARTIST, PERFORMANCE, FESTIVAL_SPACE, MAP_MARKER,
  NOTICE, EXTERNAL_LINK이며 public ChatbotSource.resourceType과 같은 enum이다.

ArtistWrite는 필수 `translations`, `profileMediaId`, `categoryId`, `featured`,
`socialLinks`, `tracks`, `order`만 받으며 profileMediaId와 categoryId만 nullable이다.
각 non-null 번역 값은 필수 `name`과 nullable `introduction`만 가진다.
ArtistSocialLinkWrite는 필수 `platform: INSTAGRAM|YOUTUBE|TIKTOK|X|OTHER`,
`labelTranslations`, `url`, `order`만 가진다. ArtistTrackWrite는 필수
`titleTranslations`, `artistLabelTranslations`, `provider: SPOTIFY|YOUTUBE|APPLE_MUSIC|OTHER`,
`url`, nullable `artworkMediaId`, `order`만 가진다. 각 `*Translations`는
language→non-empty string 또는 null인 닫힌 map이다. 두 URL은 승인된 HTTPS URL이고
artworkMediaId는 같은 회차의 READY media다. socialLinks는 최대 10개, tracks는 최대
3개이며 각 배열의 order는 유일하고 order, URL 순으로 public projection한다.

LineupCategoryWrite는 필수 `code`, `translations`, `order`만 받는다. 각 non-null 번역
값은 필수 non-empty `label`만 가진다. code는 같은 회차에서 유일하고 생성 후 변경할 수
없다. StageWrite는 필수 `translations`, `order`만 받고 각 non-null 번역 값은 필수
non-empty `name`만 가진다.

PerformanceWrite는 필수 `translations`, `stageId`, `artistIds`, `scheduledStartsAt`,
`scheduledEndsAt`, `effectiveStartsAt`, `effectiveEndsAt`,
`manualStatus: NORMAL|DELAYED|CANCELED`, `order`만 받는다. 각 non-null 번역 값은 필수
non-empty `title`, nullable `description`, nullable `statusMessage`만 가진다. artistIds는
중복 없는 최대 20개 UUID 배열이며 값이 없으면 빈 배열이고 모든 참조는 같은
festivalId여야 한다. 두 시각 pair는 각각 시작 < 종료이고 effective 구간은 정확히 하나의
TimetableDayView에 포함돼야 한다. DELAYED·CANCELED는 게시 언어 정책을 충족하는
statusMessage가 필수다. public Performance와 PerformanceSummary의 order는 이 입력의
projection이다.

FestivalSpaceWrite는 필수 `type: BOOTH|PUB|FLEA_MARKET`, `translations`,
`operatingPeriods`, nullable `contact`, `mediaItems`, `order`만 받는다. 각 non-null 번역
값은 필수 `name`, nullable `operatorName`, nullable `description`만 가진다.
operatingPeriods는 1~20개의 OperatingPeriod이고 8.3의 구간·비중첩 규칙을 따른다.
SpaceContactWrite는 필수 `type: PHONE|EMAIL|HTTPS_URL`, `labelTranslations`,
`displayValueTranslations`, `href`만 가진다. 두 번역 map은 language→non-empty string 또는
null이고 href는 type과 일치하는 tel, mailto, https scheme 하나만 쓴다. public
SpaceContact의 label과 displayValue는 선택된 언어의 projection이다.

MenuSectionWrite는 필수 `translations`, `order`만 받고 각 non-null 번역 값은 필수
non-empty `name`만 가진다. MenuItemWrite는 필수 `translations`, `price`,
`availability: AVAILABLE|SOLD_OUT|UNAVAILABLE`, `featured`, nullable `mediaId`, `order`만
받는다. 각 non-null 번역 값은 필수 non-empty `name`, nullable `description`, nullable
`priceLabel`, `allergens: string[]`만 가진다. price는 필수 `amount: integer|null`,
`currency: KRW` 두 필드만 가진 닫힌 object다. amount가 있으면 양의 정수이고 모든
priceLabel은 null이어야 하며, amount가 null이면 게시 언어 정책을 충족하는 priceLabel이
필수다. allergens는 언어별 최대 30개의 중복 없는 배열이고 값이 없으면 빈 배열이다.
mediaId는 같은 회차의 READY media다.

SpaceEventWrite는 필수 `translations`, `effectiveStartsAt`, `effectiveEndsAt`,
`manualStatus: NORMAL|DELAYED|CANCELED`, nullable `action`, `order`만 받는다. 각 non-null
번역 값은 필수 non-empty `title`, nullable `description`만 가진다. 시작 < 종료이고 축제
기간 안이어야 한다. action은 OPEN_FESTIVAL_SPACE 또는 OPEN_MAP만 허용하고 현재 상위
space 또는 그 marker만 가리킨다. public SpaceEvent.order는 이 입력의 projection이다.

메뉴 section·item·event POST는 ID를 받지 않고 서버가 UUID를 발급한다. PATCH body에도
id를 넣지 않으며 path ID가 대상을 소유한다. 배열을 포함한 nested 값은 해당 PATCH
property가 있으면 전체 값을 교체하고, 없으면 유지하며 JSON Merge Patch의 null 삭제는
schema가 nullable이라고 명시한 필드에만 허용한다.

MapAreaWrite는 필수 `translations`, nullable `bounds: MapBounds`, `order`만 받는다. 각
non-null 번역 값은 필수 non-empty `name`, nullable `description`만 가진다. bounds variant는
현재 MapConfiguration.coordinateSystem과 같아야 한다.

NoticeWrite는 필수 `type: NoticeType`, `contentType: INTERNAL|EXTERNAL|HYBRID`,
`translations`, `important`, nullable `externalUrl`, `visibleFrom`, nullable
`visibleUntil`만 받는다. 각 non-null 번역 값은 필수 non-empty `title`, nullable `summary`,
nullable `body: RichText`, nullable `sourceName`만 가진다. INTERNAL은 게시에 필요한 각
언어의 body가 필수이고 externalUrl은 null, EXTERNAL은 모든 body가 null이고 승인된 HTTPS
externalUrl이 필수, HYBRID는 body와 externalUrl이 모두 필수다. emergency, publishedAt,
updatedAt은 서버 projection이라 입력에서 거부한다. visibleUntil이 있으면 visibleFrom보다
뒤여야 한다.

ExternalLinkWrite는 필수 `type: OFFICIAL_PAGE|OFFICIAL_SNS|TRANSPORT|FAQ|OTHER`,
`translations`, `url`, nullable `mediaId`, `order`만 받는다. 각 non-null 번역 값은 필수
non-empty `title`, nullable `description`만 가진다. url은 승인된 HTTPS이고 mediaId는 같은
회차의 READY media다. FAQ는 externalLinks와 publicFaq capability가 모두 활성화된
후보에서만 게시할 수 있다.

MapMarkerWrite는 필수 `categoryCode`, nullable `areaId`, `locationId`, `translations`,
`position`, nullable `linkedSpaceId`, nullable `stageId`, `operatingPeriods`, nullable
`icon: MapIconWrite`, nullable `markerStyle: MarkerStyle`, `order`만 받는다. 각 non-null
번역 값은 필수 `name`, `locationText`, `accessibilityText`, `aliases: string[]`와 nullable
`description`만 가진다. aliases는 언어별 최대 20개, 각 1~80자의 중복 없는 값이고 public
응답과 검색에는 선택된 언어 배열만 projection한다. linkedSpaceId와 stageId는 최대 하나만
non-null이고 연결 대상·areaId는 같은 festivalId여야 한다. linkedSpaceId가 있으면
operatingPeriods는 빈 배열이어야 하며, 그 밖의 marker는 최대 20개 OperatingPeriod를
받는다. position과 area bounds는 현재 MapConfiguration coordinateSystem과 같아야 한다.

MapIconWrite의 altTranslations는 language→1~160자의 non-empty string 또는 null인 닫힌
map이고 public MapIcon.alt의 유일한 번역 원본이다. MapCategoryPatch의 translations에는
language별 `label`만 있으며 icon alt를 중복 저장하지 않는다. PATCH는 `translations`,
`icon`, `markerStyle`, `order`, `visible` 중 하나 이상만 허용하고 categoryCode는 path가
소유한다.

MapConfigurationWrite.categories item인 MapCategoryWrite는 필수 `code`,
`translations.label`, `icon: MapIconWrite`, `markerStyle`, `order`, `visible`만 가진다.
14.1의 15개 code를 각각 정확히 한 번 포함하고 order는 유일하다. textOverlays item인
MapTextOverlayWrite는 기존 item 수정 시 필수이고 신규 생성 시 생략하는 서버 발급 `id`,
필수 `type: AREA_LABEL|LANDMARK_LABEL`, `translations.text/accessibilityText`, `position`,
`order`만 가진다. 각 non-null 번역 값에는 non-empty text와 accessibilityText가 모두
필수다. textOverlays는 최대 100개이고 ID와 order는 각각 유일하다. localizedMediaIds는
language→READY media UUID 또는 null인 닫힌 map이다. strategy별 media·overlay 필수 조건,
모든 좌표 variant와 공개 projection은 14.3을 따른다.

MapMarkerCreateWrite는 MapMarkerWrite의 locationId를 제외한 모든 필드와 필수
`locationRef`만 받는다. locationRef는 `{ "mode": "CREATE" }` 또는
`{ "mode": "REUSE", "locationId": "UUID" }` tagged union이다. CREATE이면 서버가
같은 festivalId 안의 새 locationId를 발급하고, REUSE는 기존 canonical 장소만 연결한다.
클라이언트가 임의 UUID를 생성하지 않는다. 서버는 POST transaction에서 locationRef를 정확히
한 번 해석해 canonical locationId로 바꾸고 locationRef 자체는 저장하지 않는다. 관리자 응답,
working revision, PublicationRevisionSnapshot과 restore-to-draft payload에는 항상
MapMarkerWrite의 canonical locationId를 보존한다. locationId는 생성 뒤 불변이며
MapMarkerPatch에서 허용하지 않는다. 따라서 restore는 새 location을 만들지 않고 원
locationId를 재사용한다. canonical 장소를 바꿔야 하면 기존 marker를 게시 취소·보관하고
올바른 locationRef로 새 marker를 생성한다. mapVersion은 marker 요청·working revision의
저장 필드가 아니며 지도 batch 적용 시 MapSnapshot에서 서버가 발급해 공개 응답에
projection한다. linkedSpaceId가 있으면 marker 운영 시간 입력을 금지하고 FestivalSpace에서
projection한다.

OperatingOverride는 FestivalSpaceWrite나 MapMarkerWrite의 draft 필드가 아니다. 행사 중
수동 상태는 19.4의 PUBLISHER live-state action으로만 만들고 관리자 상세의 활성 override로
조회한다.

HomeCuratedItemWrite는 기존 item 수정 시 필수이고 신규 item 생성 시 생략하는 서버 발급
`id`, 필수 `translations.title`, `effectiveStartsAt`, `effectiveEndsAt`,
`manualStatus: NORMAL|DELAYED|CANCELED`, `action: HomeAction`, `order`와 nullable
`translations.description`을 가진다. 시각·상태 계산은 SpaceEvent와 같고 CURATED 공개
projection의 sourceId는 null이다.

HomeQuickLinkWrite는 같은 생성·수정 ID 규칙과 필수 `translations.label`, `order`,
`action: HomeAction`, `icon: HomeIconWrite`, nullable `translations.description`을
가진다. HomeIconWrite는 HomeIcon과 같은 tagged union이되 MEDIA variant가 Media 대신
`mediaId`를 받고 alt 번역을 포함한다. PUT에서 기존 ID를 생략하면 해당 item을 제거하며,
다른 축제의 ID나 client 생성 UUID는 거부한다. map·home configuration PUT에서 신규
중첩 item ID를 발급한 최초 결과는 Idempotency-Key record에 함께 고정한다. 같은 key와
동일 요청을 재시도하면 최초 ID를 그대로 반환하며 새 중첩 item을 만들지 않는다.

FestivalSpaceWrite.mediaItems의 item은 필수 `mediaId`, `role: HERO|GALLERY|MENU_BOARD`,
`order`를 가진다. 최대 20개, HERO 최대 1개이며 모든 mediaId는 같은 festivalId의 READY
Media여야 한다. 공개 SpaceMedia는 이 입력을 Media로 확장한 projection이다.

ChatbotKnowledgeEntryWrite는 필수 `kind: FAQ|INFORMATION`, `translations`,
`sourceRefs`, `enabled`, `priority`만 받는다. 각 non-null 번역 값은 필수 non-empty
`question`, `answer`만 가진다. ChatbotSourceRefWrite는 필수 `resourceType`, `resourceId`
두 필드만 가진 닫힌 tagged union이다. resourceType은 FESTIVAL, ARTIST, NOTICE,
PERFORMANCE, FESTIVAL_SPACE, MAP_MARKER, EXTERNAL_LINK 중 하나이고 resourceId는 해당 type의
UUID다. FESTIVAL이면 route의 festivalId와 같아야 하며 다른 variant도 같은 축제의
리소스여야 한다. sourceRefs는 1~20개이고 `(resourceType, resourceId)` 쌍은 유일하다.

이 리소스는 챗봇의 승인 지식이며 공개 FAQ 화면과 같은 개념이 아니다. 저장 시 존재하는
working 리소스를 참조할 수 있지만 게시 후보에서는 각 source가 같은 후보 public
snapshot에 포함되고 ChatbotConfiguration.allowedSourceTypes에 허용돼야 한다. 공개 챗봇의
sources에는 내부 knowledge entry ID가 아니라 사용자가 열람할 수 있는 이 공개 리소스만
반환한다.
FESTIVAL_SPACE source는 게시·검증 내부에서 PublicationResourceType SPACE로 매핑하지만
외부 resourceType 문자열은 바꾸지 않는다.

관리자 챗봇 테스트의 닫힌 AdminChatbotTestWrite는 필수 `message`, `lang`,
`source: WORKING|BATCH|PUBLISHED`, source=BATCH에서만 필수인 `batchId`와 선택적
`conversation`만 받는다. message·conversation은 17.2의 공개 request component와 같은
길이·role 교대 규칙을 사용하되 source가 선택한 ChatbotConfiguration의 한도를 적용한다.
source=BATCH이면 같은 festivalId의 DRAFT, VALIDATED 또는 SCHEDULED batchId가 필수이고,
다른 source에서는 batchId property 자체를 보내면 안 된다. 존재하지 않거나 허용 상태가 아닌
batch는 404 RESOURCE_NOT_FOUND 또는 409 PUBLICATION_CONFLICT다.

성공은 200이고 data는 공개 응답과 구조가 정확히 같은 닫힌 ChatbotMessageResult다. 다만
ChatbotSource와 ChatbotAction의 target은 public head가 아니라 선택한 WORKING·BATCH·PUBLISHED
preview snapshot에서 실제로 addressable하고 서로 정합적인 리소스만 허용한다. meta는 공통
requestId·serverTime·festivalTimeZone과 19.3의 필수 `source`, `previewSnapshotId`,
`evaluationTime`, `workingRevisions`, `referencedMedia`, nullable `batchId`, nullable
`baseContentRevision`, `language`, `validationWarnings` 및 nullable `contentRevision`,
nullable `snapshotUpdatedAt`만 가진다. PUBLISHED에서는 contentRevision·snapshotUpdatedAt이
둘 다 non-null이고 baseContentRevision·batchId는 null이며 workingRevisions는 빈 배열이다.
WORKING에서는 contentRevision·snapshotUpdatedAt·batchId가 null, BATCH에서는 앞의 두 필드만
null이고 batchId가 non-null이다. WORKING·BATCH의 baseContentRevision은 조립에 사용한 현재
public head revision이고 첫 public head 전에는 null이다. 두 source의 workingRevisions에는
실제로 조립한 configuration·knowledge·근거 resource revision을 모두 넣는다. BATCH preview는
요청 시점 target revision과 media pin을 불변 previewSnapshotId로 고정한다. 모든 응답은
Cache-Control: no-store이며 원문 질문은 저장하거나 외부 사용자 분석 지표에 섞지 않는다.

Festival capability 변경은 다음 approvalReference를 같은 PATCH에 포함해야 한다.

    {
      "capabilityChanges": {
        "artistDetail": true
      },
      "approvalReference": {
        "referenceType": "APPROVED_PRODUCT_DECISION",
        "referenceId": "opaque-approved-reference",
        "approvedAt": "2030-08-20T15:00:00+09:00",
        "note": "민감정보를 포함하지 않은 변경 근거"
      }
    }

referenceId는 승인 자료의 실제 저장소가 발급한 식별자이며 사람이 입력한 임의 문장을
승인으로 간주하지 않는다. 승인 값은 관리자 Festival의 `capabilityApprovals`에 저장하고
공개 Festival에는 노출하지 않는다. 고등학생 안내·분실물·스탬프·FUN은 현재 capability와
write schema에 포함하지 않는다.

행사 중 공연 변경, 공간·시설 운영 변경과 긴급 공지는 다음 PUBLISHER action을 제공한다.

    POST /admin/festivals/{festivalId}/performances/{performanceId}/live-state
    POST /admin/festivals/{festivalId}/spaces/{spaceId}/live-state
    POST /admin/festivals/{festivalId}/map-markers/{markerId}/live-state
    POST /admin/festivals/{festivalId}/notices/{noticeId}/emergency-publish
    POST /admin/festivals/{festivalId}/notices/{noticeId}/emergency-unpublish

각 요청 접수 transaction은 If-Match의 adminVersion, body의 expectedWorkingRevision 또는
expectedPublishedRevision, 현재 overrideVersion을 검증하고 PublicationOperation을 만든 뒤
대상에 `pendingOperationalOperationId`를 기록하며 adminVersion을 증가시킨다. 이미 다른
pendingOperationalOperationId가 있으면 409 PUBLICATION_CONFLICT이고 같은 이전 ETag로
경쟁한 요청은 412 REVISION_MISMATCH다. pendingPublicationOperationId만 존재하면 거부하지
않고 아래 emergency priority fence 경로를 사용한다. operational pending 동안 해당 대상의
PATCH·DELETE·다른 상태 전이는 막는다.
operation의 acceptedResourceState에는 접수 전 `adminVersion`, `workingRevision`, nullable
`publishedRevision`, nullable `overrideId`, 예약 뒤 `reservedAdminVersion`을 감사 snapshot으로
고정하고 `commitExpectedAdminVersion`을 같은 값으로 시작한다. 마지막 필드는 아래 emergency
fence cleanup만 갱신할 수 있는 worker CAS cursor이며 client가 보내지 않는다.
DRAFT·VALIDATED·SCHEDULED batch를 접수 transaction에서 선취소할 때는 모든 target 예약
cleanup과 그 adminVersion 증가를 먼저 적용한 뒤 operational pending과 마지막 adminVersion
증가를 기록하며, reservedAdminVersion과 commitExpectedAdminVersion은 이 최종 값을 사용한다.

Performance live-state body는 필수 `manualStatus: NORMAL|DELAYED|CANCELED`, `reason`,
`expectedPublishedRevision`과 선택적 `effectiveStartsAt`, `effectiveEndsAt`, 번역된
`statusMessageTranslations`를 받는다. 이 값은 language→string|null인 닫힌 map이다.
DELAYED·CANCELED이면 회차 번역 정책을 충족하는 non-empty 메시지가 필수다. If-Match에는
Performance admin ETag를 보낸다.

effectiveStartsAt과 effectiveEndsAt은 둘 다 생략하거나 둘 다 제공한다. 생략하면 현재
활성 PerformanceOperationalOverride의 effective pair가 있으면 그 값을, 없으면 published
Performance의 effective pair를 사용한다. 제공하면 시작 < 종료, 축제·dayView 범위와 같은
무대의 겹침을 다시 검증한다. manualStatus=NORMAL이고 두 시각과
statusMessageTranslations를 모두
생략하면 활성 override를 해제한다. NORMAL과 새 시각을 함께 보내면 수동 상태 없이 변경
시각만 유지하는 새 override를 만든다.

Space와 MapMarker live-state body는 필수 `operatingOverride: NORMAL|PAUSED|CANCELED`,
`reason`, `expectedPublishedRevision`과 선택적 `statusMessageTranslations`를 받는다. 같은
닫힌 locale map이며 PAUSED와 CANCELED이면 회차 번역 정책을 충족하는 메시지가 필수다.
linkedSpaceId가 있는 marker의 상태는 Space에서
파생하므로 marker endpoint는 409 OPERATIONAL_OVERRIDE_CONFLICT를 반환하고 Space endpoint를
사용한다. 운영 시간이 없는 독립 marker에는 PAUSED를 설정할 수 없다. NORMAL은 활성
override를 해제하고 즉시 시간 계산 상태로 돌아간다.
성공한 Performance live-state는 performances·home cache, Space live-state는 spaces·home과
연결 marker cache, 독립 MapMarker live-state는 marker cache를 API·CDN·CLIENT_MANIFEST
target에서 즉시 무효화한다. purge 결과는 같은 PublicationOperation의 cacheInvalidation에
기록한다.

emergency-publish body는 필수 `expectedWorkingRevision`, `reason`을 받고,
emergency-unpublish body는 필수
`expectedPublishedRevision`, `reason`을 받는다. 두 endpoint는 type=EMERGENCY Notice에만
허용하며 Notice admin ETag와 Idempotency-Key를 요구한다. publish와 회수 모두 검증, 새
contentRevision 전환, notices·home·CDN·CLIENT_MANIFEST cache purge를 하나의 operation으로
접수하고 202를 반환한다. 일반 EDITOR PATCH나 generic 예약 취소 절차만으로 긴급 공지가
즉시 바뀌지 않는다.
emergency-publish의 type은 expectedWorkingRevision snapshot에서,
emergency-unpublish의 type은 expectedPublishedRevision이 가리키는 현재 공개 snapshot에서
검사한다. 공개 긴급 공지 위에 GENERAL working draft가 있어도 긴급 회수를 막지 않는다.
emergency-publish는 expectedWorkingRevision의 Notice snapshot을 수정 없이 그대로
publishedRevision으로 전환한다. 그 snapshot의 `visibleFrom <= commit serverTime`이고
visibleUntil이 있으면
`serverTime < visibleUntil`이어야 한다. 즉시 노출되지 않는 구간은 422
VALIDATION_ERROR로 거부하고 일반 schedule을 사용하게 한다.

관리자 Performance 상세는 nullable `activeOperationalOverride`, FestivalSpace와 MapMarker
상세는 nullable `activeOperatingOverride`를 반환한다. 두 override는 공통 필수 `id`,
`overrideVersion`, `resourceType`, `resourceId`, `reason`, `createdBy`, `createdAt`을 가진다.
PerformanceOperationalOverride는 추가로 필수 `manualStatus`, nullable
`effectiveStartsAt`, `effectiveEndsAt`, `statusMessageTranslations`를 가진다.
OperatingStatusOverride는 필수 `operatingOverride`와 nullable
`statusMessageTranslations`를 가진다. 공개 Performance.statusMessage와
Space·MapMarker.operatingStatusMessage는 meta.language.content에 해당하는 값의
projection이고 fallback은 5.2와 같다. 새 live-state 성공 시 이전
override를 supersede한 불변 감사 기록으로 보존한다. NORMAL 해제는 새 활성 override를
만들지 않고 불변 clear event만 남겨 active override를 null로 만든다. live-state는
workingRevision을 바꾸지 않는다. 성공 commit은 적용 또는 clear event 시각을 공개
Performance·FestivalSpace·MapMarker의 operationalUpdatedAt에 projection한다.
Performance, FestivalSpace, MapMarker, Notice 관리자 상세는 필수 nullable
`pendingOperationalOperationId`도 반환하며 pending이 아니면 null이다.

live-state는 working draft를 덮어쓰지 않고 published resource 위에 불변 operational
override를 만든다. 공개 응답의 effective 시각·상태·상태 안내는 같은 contentRevision의
활성 override를 우선한다. 이후 해당 리소스를 바꾸거나 제거하는 publication 요청은 활성
override마다 `overrideDecision` 또는 batch의 `overrideDecisions[]`를 반드시 보내며 기본값은
없다. decision은 필수 `resourceType: PERFORMANCE|SPACE|MAP_MARKER`, `resourceId`,
`overrideId`, `policy: RETAIN|CLEAR`와 nullable `reason`을 가진다. CLEAR는 reason이 필수고
RETAIN은 기존 overlay를 새 게시본 위에 유지한다. 누락되거나 overrideId가 현재 활성값과
다르면 409 OPERATIONAL_OVERRIDE_CONFLICT다. 이 규칙으로 과거 draft가 현장 상태를
무의식적으로 덮어쓰지 못하게 한다.

live-state, emergency-publish, emergency-unpublish도 batch apply와 같은 공통 public-head
commit pipeline을 사용한다. worker는 최신 public head를 읽고 해당 target pointer 또는
operational override와 원자 취소할 예약 정보만 병합한 후보를 만들며 비대상 변경을
보존한다. 후보 전체의 참조·시간·지도·번역 조건을 재검증한 뒤 읽은 head version에 CAS로
commit한다. 경쟁이 나면 최신 head에서 병합·검증을 최대 3회 다시 하고, 계속 충돌하면
아무 target도 적용하지 않은 FAILED operation과 409 PUBLICATION_CONFLICT를 기록한다.
따라서 긴급 action끼리 또는 긴급 action과 batch apply가 서로의 비대상 변경을
last-write-wins로 잃게 할 수 없다.
worker는 public-head CAS와 별도로 target의 pendingOperationalOperationId,
acceptedResourceState.commitExpectedAdminVersion, expected revision·overrideId도 commit 시
CAS한다. reservedAdminVersion은 접수 감사값이고 commit cursor로 다시 사용하지 않는다. 다르면 target과
public head를 바꾸지 않고 FAILED와 REVISION_MISMATCH를 기록한다. 성공·실패 terminal 전이는
pendingOperationalOperationId를 지우고 adminVersion을 다시 증가시켜 새 ETag를 만든다.

- 이 절의 상태 변경 POST와 모든 관리자 PUT은 Idempotency-Key를 필수로 받는다. 단순
  조회·미리보기 성격의 POST는 해당 endpoint가 별도로 요구할 때만 적용한다.
- PATCH는 JSON Merge Patch 형식과 application/merge-patch+json을 사용한다.
- PUT은 단일 구성 전체를 대체하며 누락 필드를 삭제로 해석한다.
- PATCH, PUT, DELETE와 상태 전이는 If-Match를 필수로 받는다.
- 성공한 생성은 201과 Location을 반환한다.
- 성공한 수정은 새 ETag를 반환한다.
- 이미 처리한 Idempotency-Key와 다른 body가 오면 409 IDEMPOTENCY_KEY_REUSED다.
- DELETE는 publicationState=NEVER_PUBLISHED이고 예약이 없는, 참조되지 않은 draft만 물리
  삭제하고 204를 반환한다. 참조 중이면 409 RESOURCE_IN_USE, 예약 또는 게시 이력이 있으면
  409 PUBLICATION_CONFLICT다.
- 게시 이력이 있는 리소스는 DELETE로 물리 삭제하지 않는다. 현재 공개 중이면 먼저
  unpublish하고 참조 검증 뒤 archive action을 사용한다.

Idempotency-Key 계약:

- key는 대소문자를 구분하는 16~128자의 `[A-Za-z0-9._:-]` 문자열이다. 형식이 다르면
  422 VALIDATION_ERROR다.
- 중복 판정 scope는 `(인증된 accountId, HTTP method, 실제 resource ID가 결합된 canonical
  path, key)`다. 같은 scope 안에서 canonical query와 JSON payload의 digest가 같아야 같은
  요청이며, Content-Type, 정규화한 If-Match와 endpoint가 결과에 영향을 주도록 명시한
  계약 header도 digest에 포함한다.
- 서버는 유효한 key의 기존 record를 조회하고 digest 일치 여부와 재생 가능 결과를 판정한
  뒤, 처음 보는 key에만 현재 If-Match를 검증한다. 따라서 성공한 요청의 동일 key·digest
  재시도는 리소스 ETag가 이후 바뀌어도 최초 결과를 재생한다. If-Match만 바꾼 재사용은
  새로운 요청으로 실행하거나 412를 반환하지 않고 409 IDEMPOTENCY_KEY_REUSED다.
- 서버는 부수 효과 전에 key reservation을 원자적으로 만든다. 같은 요청이 동시에 오면
  side effect를 두 번 실행하지 않는다. 최초 요청이 아직 응답을 확정하지 못했고 재사용할
  operation도 없으면 409 IDEMPOTENCY_REQUEST_IN_PROGRESS와 Retry-After를 반환한다. 비동기
  operation을 이미 만들었다면 최초 202, Location과 같은 operation ID를 즉시 재생한다.
- 완료된 동일 요청의 재시도는 최초 HTTP status, Location·ETag와 같은 business data를
  반환하고 `Idempotency-Replayed: true`를 붙인다. envelope의 requestId와 serverTime만 현재
  재시도 값을 사용한다. 단, 20절과 29절처럼 응답에 만료되는 secret이 포함되고 endpoint가
  암호화 replay 기한과 만료 뒤 tombstone status를 명시한 경우에는 그 더 구체적인 계약이
  우선하며 secret 기한 뒤 최초 성공 payload를 재생하지 않는다.
- record는 최초 응답 후 최소 24시간 유지한다. 비동기·예약 operation은 그보다 늦게
  terminal이 되면 terminal 후 최소 24시간까지 유지한다. 보관 중 key를 다른 payload에
  재사용하면 409 IDEMPOTENCY_KEY_REUSED다.
- 원문 key, upload URL, token이나 비밀번호를 로그에 남기지 않는다. 저장·관측에는 key와
  canonical 요청의 안전한 단방향 digest만 사용한다.

### 19.5 게시 검증, revision과 operation

PublicationResourceType:

- FESTIVAL
- HOME_CONFIGURATION
- TIMETABLE_CONFIGURATION
- MAP_CONFIGURATION
- CHATBOT_CONFIGURATION
- LINEUP_CATEGORY
- ARTIST
- STAGE
- PERFORMANCE
- SPACE
- MAP_AREA
- MAP_MARKER
- NOTICE
- EXTERNAL_LINK
- CHATBOT_KNOWLEDGE_ENTRY

메뉴와 map category는 각각 SPACE와 MAP_CONFIGURATION aggregate의 일부다. FESTIVAL의
resourceId는 path의 festivalId, singleton의 resourceId는 각각 `home`, `timetable`,
`map`, `chatbot` 고정 문자열이고 나머지는 UUID다. 허용되지 않은 resourceType은 400
INVALID_RESOURCE_TYPE이다.

단일 publication target endpoint:

    POST /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/validate
    POST /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/schedule
    POST /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/cancel-schedule
    POST /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/publish
    POST /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/unpublish
    POST /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/rollback
    POST /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/archive
    POST /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/restore
    GET  /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/revisions
    GET  /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/revisions/{revision}
    POST /admin/festivals/{festivalId}/publication-targets/{resourceType}/{resourceId}/revisions/{revision}/restore-to-draft

validate body는 `expectedWorkingRevision`을 받으며 200 data로 닫힌
PublicationValidationResult를 반환한다. 필수 필드는 `valid`, `errors: ValidationIssue[]`,
`warnings: ValidationIssue[]`, `translationChecks: TranslationValidationCheck[]`,
`referenceChecks: ReferenceValidationCheck[]`, `referencedMedia: ValidatedMediaReference[]`,
`validatedTargets: ValidatedTarget[]`, nullable
`validatedBaseContentRevision`, `validatedAt`이다. valid는 errors가 비었을 때만 true이고 모든
배열은 값이 없으면 빈 배열이다.

ValidationIssue는 필수 `code`, `message`, `resourceType`, `resourceId`, nullable
`propertyPath`, nullable `relatedResourceType`, nullable `relatedResourceId`만 가진다.
TranslationValidationCheck는 필수 `resourceType`, `resourceId`, `language`,
`completeness: COMPLETE|INCOMPLETE|FALLBACK_ALLOWED`, `missingFields: string[]`을 가진다.
ReferenceValidationCheck는 필수 source `resourceType`, `resourceId`, `propertyPath`, target
`targetResourceType`, `targetResourceId`, `status: RESOLVED|MISSING|NOT_PUBLISHABLE`을 가진다.
ValidatedMediaReference는 필수 `mediaId`, `mediaVersion`, `rightsEpoch`, `status=READY`, nullable
`usageExpiresAt`을 가지며 mediaId 순으로 중복 없이 반환한다. nested icon, 지도 언어별 자산,
메뉴·홈·공지 등 후보 snapshot이 간접 참조하는 모든 media를 포함한다.
ValidatedTarget은 publication target의 `resourceType`, `resourceId`, `action`,
`expectedAdminVersion`, nullable
`expectedWorkingRevision`, nullable `expectedPublishedRevision`, nullable
`rollbackTargetRevision`을 그대로 고정한다.

단일 validate의 validatedTargets에는 대상 하나가 있고 validatedBaseContentRevision은 검증
시 public head다. batch validate도 같은 component를 batch.validationResult에 저장한다.
검증 뒤 target revision·override가 바뀌면 결과는 무효지만 비대상 public head 변경은
apply 시 merge·재검증한다.

resourceType별 단일 action 허용 범위:

| resource group | validate·revisions | schedule·cancel·publish·unpublish·rollback | archive·restore |
|---|---:|---:|---:|
| HOME_CONFIGURATION, CHATBOT_CONFIGURATION, LINEUP_CATEGORY, ARTIST, SPACE, NOTICE, EXTERNAL_LINK, CHATBOT_KNOWLEDGE_ENTRY | 허용 | 허용 | 허용 |
| FESTIVAL | 허용 | 허용 | 불가; activate·deactivate 사용 |
| TIMETABLE_CONFIGURATION, STAGE, PERFORMANCE | 허용 | batch만 허용 | 비공개·참조 검증 후 허용 |
| MAP_CONFIGURATION, MAP_AREA, MAP_MARKER | 허용 | batch만 허용 | 비공개·참조 검증 후 허용 |

`batch만 허용`인 action을 단일 target endpoint로 요청하면 상태를 바꾸지 않고 409
BATCH_REQUIRED를 반환한다. 이 규칙은 schedule, cancel-schedule, publish, unpublish,
rollback에 적용한다. archive와 restore는 publicationState가 PUBLISHED가 아니고 참조
정합성 검증을 통과한 경우에만 개별 실행한다.

schedule body:

    {
      "publishAt": "2030-09-01T10:00:00+09:00",
      "expectedWorkingRevision": 7,
      "reason": "승인된 운영 일정에 따른 예약"
    }

publishAt은 6절의 offset 포함 RFC 3339 instant이고 예약 접수 transaction의 serverTime보다
엄격히 미래여야 한다. 같거나 과거이면 즉시 게시로 해석하지 않고 side effect 없이 422
VALIDATION_ERROR다. 이 값은 내부 batch의 scheduledApplyAt에 그대로 고정하며 아래 batch
schedule의 applyAt과 같은 시각 경계 규칙을 사용한다.

나머지 action body:

- publish: `expectedWorkingRevision`, `reason`
- cancel-schedule: `expectedWorkingRevision`, `reason`
- unpublish: `expectedPublishedRevision`, `reason`
- rollback: `expectedPublishedRevision`, `targetResourceRevision`, `reason`; target은 직전의
  previousPublishedRevision만 허용
- archive: `reason`; publicationState=PUBLISHED이면 409이며 먼저 unpublish해야 함
- restore: `reason`; ADMIN만 허용

schedule·publish·rollback이 활성 operational override가 있는 리소스에 영향을 주면 body에
19.4의 `overrideDecision`을 추가한다. unpublish도 활성 override가 있으면 같은 field가
필수이며 policy=CLEAR만 허용한다. 과거의 더 오래된
revision은 PUBLISHER가 직접 공개할 수 없고 ADMIN이 `POST .../revisions/{revision}/restore-to-draft`
에 `reason`을 보내 새 workingRevision으로 복사한 뒤 다시 검증·게시한다. 이 복원 요청도
If-Match를 요구하며 공개 pointer를 즉시 바꾸지 않는다.

If-Match의 adminVersion은 요청 시작 시 관리자 aggregate 전체를 보호하고 body의
expectedWorkingRevision·expectedPublishedRevision은 게시 대상을 보호한다. 둘 중 하나라도
현재 값과 다르면 412 REVISION_MISMATCH다. schedule은 action=PUBLISH인 one-target
PublicationBatch를 내부 생성한다. 서버는 접수 전에 최신 public head에 요청 target을 병합해
번역·참조·시간·지도·capability·미디어 권리와 적용된 조건부 확장의 inventory·QR issuance를
포함한 완전한 PublicationValidationResult를 계산한다. valid=false이면 batch나 예약을 만들지
않고 422 VALIDATION_ERROR를 반환하며 Problem Details의 `validationResult` extension에 그 닫힌
PublicationValidationResult를 싣는다. valid=true이면 접수 transaction에서 public head가
validatedBaseContentRevision과 같은지, target adminVersion·expectedWorkingRevision,
활성 override와 overrideDecision, pending operation·기존 예약 부재를 다시 CAS한다. public
head가 경쟁 변경됐으면 side effect 없이 409 PUBLICATION_CONFLICT이고 target precondition이
달라졌으면 side effect 없이 412 REVISION_MISMATCH다. 검증 결과, 현재 head인
baseContentRevision, target의 validatedAdminVersion을 저장한 kind=STANDARD·status=SCHEDULED
internal batch 생성, scheduledApplyAt=body.publishAt, scheduledBy·scheduledAt·
scheduledAuthzVersion 설정, target의 scheduledAction=PUBLISH·scheduledBatchId·
scheduledApplyAt 기록, target adminVersion 증가와 그 새 값을 reservedAdminVersion에 고정하는
과정을 한 transaction에서 수행한다. 그중 하나라도 실패하면 batch·예약·adminVersion 변경을
전혀 남기지 않는다. 성공은 200으로 갱신된 관리자 리소스, ETag와 batch를 반환한다.
cancel-schedule은 resource의 scheduledBatchId가 가리키는 one-target batch를
원자적으로 cancel하고 같은 shape를 반환한다. 그 batch의 targetCount가 2 이상이면 상태를
바꾸지 않고 409 BATCH_REQUIRED를 반환하며 batch `/cancel`로 전체를 취소해야 한다.
schedule이 만드는 internal batch의 purpose는 schedule body.reason과 정확히 같고,
overrideDecisions는 body.overrideDecision이 있으면 그 하나를 담은 배열, 없으면 빈 배열이다.
publish·unpublish·rollback은 내부적으로
one-target batch를 만들고 202 PublicationOperation을 반환하며 operation.batchId로 조회한다.
세 endpoint는 접수 전에 현재 public head에 target 전이를 병합한 후보를 만들고 번역·참조·
시간·지도·capability·미디어 권리와 적용된 조건부 확장의 inventory·QR issuance 검사를 포함한
완전한 PublicationValidationResult를 계산한다. valid=false이면 batch·operation·reservation을
만들지 않고 422 VALIDATION_ERROR와 Problem Details의 닫힌 `validationResult` extension을
반환한다. valid=true이면 public head revision, target의
adminVersion·expected revision·pending operation 부재를 다시 확인하고, 같은 transaction에서
baseContentRevision이 현재 head인 internal batch와 PublicationOperation을 생성하며 target의
pendingPublicationOperationId를 기록해 adminVersion을 증가시키고 그 값을
reservedAdminVersion에 고정한다. publish·unpublish batch는 kind=STANDARD,
rollback batch는 kind=SINGLE_TARGET_ROLLBACK이고 모두 status=APPLYING,
latestOperationId=operationId이며 purpose는 각 action body.reason과 정확히 같다.
overrideDecisions는 body.overrideDecision이 있으면 `[overrideDecision]`, 없으면 `[]`다. 계산한 완전한 PublicationValidationResult를
batch.validationResult에 저장하며 translationChecks·referenceChecks·validatedTargets,
referencedMedia의 version·rightsEpoch와 적용된 조건부 inventoryChecks·qrIssuanceChecks를
빠짐없이 포함한다. 이 접수 transaction에서 public head가 검증 뒤 경쟁 변경되면 409
PUBLICATION_CONFLICT, target precondition이 달라지면 412 REVISION_MISMATCH이며 어느 경우에도
batch·operation·reservation을 만들지 않는다. 이후에는 19.5의 batch apply worker, 권한 재검사, 전체-head merge·검증·CAS,
reservation 해제와 cache purge 계약을 그대로 사용한다.
publish·unpublish batch kind는 STANDARD이고 rollback은 SINGLE_TARGET_ROLLBACK이다.
archive·restore·restore-to-draft는 200과 갱신된 관리자 리소스·ETag를 반환한다.

revision 목록은 query로 `page`, `size`만 받고 revision 내림차순으로 고정한다. 성공 data는
`items: PublicationRevisionSummary[]`이고 meta.pagination은 7.2를 따른다.
PublicationRevisionSummary는 필수 `resourceType`,
`resourceId`, `revision`(1 이상의 integer), nullable `basedOnRevision`,
`source: CREATE|EDIT|RESTORE_TO_DRAFT|SYSTEM_MIGRATION`, `contentHash`(소문자 SHA-256 hex),
nullable `createdBy`, `createdAt`, nullable `reason`만 가진 닫힌 schema다. 시스템
migration에서만 createdBy가 null이다. reason은 RESTORE_TO_DRAFT와 SYSTEM_MIGRATION에서
비어 있지 않은 값이고, 일반 CREATE·EDIT에서는 mutation이 reason을 받았을 때만 non-null이다.

revision 상세 data는 필수 `summary: PublicationRevisionSummary`,
`snapshot: PublicationRevisionSnapshot`만 가진다. snapshot은 `resourceType`을 discriminator로
하고 `resourceId`, `payload`를 가진 닫힌 tagged union이다. payload는 FESTIVAL은
FestivalCreateWrite와 이후 허용 patch를 병합한 저장 projection, singleton은 각 `*Write`,
나머지는 19.4 표의 해당 `*Write` 저장 projection이다. 메뉴·map category처럼 aggregate에
포함되는 child와 서버 발급 nested ID도 부모 snapshot 안에 포함한다. 모든
PublicationResourceType variant를 OpenAPI oneOf mapping에 빠짐없이 선언하며 다른 type의
payload 조합은 500으로 내보내지 않고 저장·migration 단계에서 거부한다. snapshot에는
credential, token, 서명 URL 또는 operational override를 넣지 않고 `*Write`와 같은 mediaId만
저장한다. mediaVersion·rightsEpoch는 resource revision 자체에 고정하지 않는다. publish
validate 시 19.5의 PublicationValidationResult.referencedMedia가 현재 정확한 값을 고정하고,
성공한 public contentRevision의 불변 manifest가 이를 보존한다.

PublicContentRevisionManifest는 필수 `contentRevision`, `createdAt`,
`resourcePointers: PublishedResourcePointer[]`, `mediaPins: PublishedMediaPin[]`만 가진다.
PublishedResourcePointer는 필수 `resourceType`, `resourceId`, `publishedRevision`을,
PublishedMediaPin은 필수 `mediaId`, `mediaVersion`, `rightsEpoch`를 가진다. 두 배열은 각각
resourceType·resourceId와 mediaId가 유일하고 그 순서로 정렬한다. 역사적 공개 조합의 재현과
감사는 이 manifest를 사용한다. restore-to-draft는 원 revision의 mediaId만 새 working
payload로 복사하고 현재 media record를 다시 해석하며, 다음 validate가 새 version·rightsEpoch를
고정하므로 과거 pin을 새 게시에 암묵적으로 재사용하지 않는다.

revision 상세는 불변이므로 `ETag:
"revision-{resourceType}-{resourceId}-{revision}-{contentHash}"`를 반환하고 If-None-Match가
일치하면 304다. 관리자 cache 정책은 22절에 따라 no-store다. 존재하지 않는 revision은 404,
다른 festival에 속한 target은 존재 여부를 감춘 404다. restore-to-draft는 body의 필수
`reason`과 현재 aggregate ETag의 If-Match, Idempotency-Key를 요구한다. 저장 snapshot을 현재
schema로 검증해 새 workingRevision으로 복사하고 200 data로 갱신된 정확한
`Admin<Resource>`와 새 admin ETag를 반환하며 공개 pointer와 원본 revision은 바꾸지 않는다.

schedule과 cancel-schedule의 정확한 data component는 SingleTargetScheduleResult이며 필수
`resource: Admin<Resource>`, `batch: PublicationBatch`, `batchEtag: string`만 가진다. HTTP
ETag는 갱신된 resource의 `"admin-{adminVersion}"`이고 batchEtag는
`"batch-{batch.batchVersion}"`이다. schedule의 batch는 SCHEDULED, cancel-schedule은
CANCELED와 non-null cancellation을 반환한다. 두 응답 모두 target이 정확히 하나인지
검증하며 불완전한 summary object를 별도로 만들지 않는다.

상태 규칙:

- 편집은 workingRevision만 증가시키고 기존 publishedRevision을 변경하지 않는다.
- CLEAN 상태 편집과 신규 리소스 편집은 workingStatus=DRAFT로 만든다.
- schedule은 workingStatus를 바꾸지 않고 검증을 통과한 정확한 target revision에
  scheduledAction, scheduledBatchId, scheduledApplyAt을 설정한다.
- 예약 batch의 target을 PATCH·PUT·DELETE하거나 단일 lifecycle action으로 바꾸려 하면
  전체 batch를 명시적으로 cancel하기 전까지 409 PUBLICATION_CONFLICT를 반환한다. cancel은
  모든 target의 세 예약 필드를 원자적으로 null로 만들고 감사 event를 남긴다.
- live-state와 emergency-publish·emergency-unpublish는 예약 때문에 차단하지 않는다.
  접수 transaction은 영향받는 target이 든 DRAFT·VALIDATED·SCHEDULED batch를 CANCELED와
  SUPERSEDED_BY_EMERGENCY로 원자 전환하고 모든 target의 세 예약 필드를 지운다. APPLYING
  batch에는 사용자 cancel과 구분되는 emergency priority fence를 batchVersion CAS로
  기록한다. fence가 먼저 이기면 batch 전체와 이미 생성된 원 PublicationOperation을 함께
  CANCELED로 전환하고, batch commit이 먼저
  이기면 APPLIED 상태를 보존한 채 긴급 worker가 그 최신 public head 위에 변경을 적용한다.
  어느 순서든 긴급 operation이 성공한 뒤의 최종 public head에는 긴급 변경이 남는다.
  운영자는 이후 새 revision과 overrideDecision으로 batch를 다시 만든다.
- publish는 검증된 workingRevision을 새 publishedRevision으로 전환하고
  workingStatus=CLEAN, publicationState=PUBLISHED로 만든다. 기존 공개본은 전환 성공
  전까지 계속 제공한다.
- unpublish는 published pointer를 제거하지만 working revision은 보존하고 사유를
  필수로 받는다. 성공 시 publishedRevision, publishedBy, publishedAt을 null로 만들고
  workingStatus=DRAFT, publicationState=UNPUBLISHED로 전환한다. 직전 공개 revision과
  게시자는 불변 revision·audit·AffectedResource.previousPublishedRevision에 보존한다.
- archive는 공개되지 않고 예약 필드와 pending operation이 모두 null인 리소스만
  workingStatus=ARCHIVED로 만들어 편집을 막는다. 예약 중이면 409
  PUBLICATION_CONFLICT이고 먼저 batch를 명시적으로 cancel해야 한다. restore는 ADMIN과
  사유가 필요하며 새 DRAFT workingRevision을 만든다.
- revision 조회는 불변 snapshot이며 수정·삭제할 수 없다.

게시 검증:

- festivalId 참조와 festival scope 일치
- 필수 번역, fallback 정책과 다국어 지도 자산
- 일시, 축제 기간과 timetable display window
- 동일 무대 공연 겹침과 연결 아티스트·무대
- 외부 URL, 링크 action과 READY 미디어
- 참조한 각 mediaId의 정확한 mediaVersion·권리 유효성
- 지도 mapVersion, contentBounds, 좌표·anchor 범위
- 중복 티켓 locationId와 marker 연결
- linked resource의 게시 가능 revision
- 후보 snapshot의 reverse reference; 공개 리소스를 끊는 게시 취소·rollback 금지
- RichText schema와 XSS 정화
- capability별 approvalReference
- 홈 quick link와 챗봇 source/action의 공개 대상 존재 여부

모든 activate, publish, unpublish, rollback, batch apply, live-state와 emergency 후보는
commit 직전에 완성된 public head 전체에 같은 검증을 적용한다. 현재 active pointer가
가리키는 회차라면 FESTIVAL, HOME_CONFIGURATION, TIMETABLE_CONFIGURATION,
MAP_CONFIGURATION의 published revision을 항상 하나씩 유지해야 한다. 이 root 중 하나를
제거하는 단일 요청은 먼저 deactivate하도록 409 RESOURCE_IN_USE를 반환하고, batch 후보는
409 PUBLICATION_BATCH_INVALID로 전체를 거부한다. active 회차의
`Festival.capabilities.chatbot=true`이면 같은 head에 published CHATBOT_CONFIGURATION이
반드시 있어야 한다. 다른 capability가 추가 aggregate를 요구하게 되면 그 dependency도
capability와 같은 계약 변경에서 이 불변식에 추가한다. 활성화 시점에만 검사하고 이후
게시에서 깨뜨리는 것을 허용하지 않는다.

시간표와 지도는 단일 target 게시·게시 취소를 허용하지 않고 publication batch를 요구한다.
이 두 도메인의 batch는 변경분이 아니라 적용 후의 완전한 desired snapshot을 선언한다.
시간표 batch에는 TIMETABLE_CONFIGURATION과 공개할 모든 STAGE·PERFORMANCE를 PUBLISH
target으로, 현재 공개 중이지만 제거할 항목을 UNPUBLISH target으로 포함한다. 지도 batch도
MAP_CONFIGURATION, 공개할 모든 MAP_AREA·MAP_MARKER, 제거할 항목을 같은 방식으로 전부
포함하며 category 설정은 MAP_CONFIGURATION revision에 포함한다.

지도 batch를 적용할 때 서버는 선언된 전체 집합을 전수 검증하고 새 snapshotId와
mapVersion을 발급한다. marker revision 자체에는 mapVersion을 저장하지 않고 공개
MapMarker.mapVersion을 해당 MapSnapshot에서 projection하므로 변경되지 않은 marker
revision도 새 지도 자산과 일치한다. 지도 crop·contentBounds·coordinateSystem 변경은 모든
marker position과 area bounds를 다시 검증한다. 서버는 public map configuration과 marker가
서로 다른 snapshot으로 노출되는 전환을 거부한다.

publication batch endpoint:

    GET  /admin/festivals/{festivalId}/publication-batches
    POST /admin/festivals/{festivalId}/publication-batches
    GET  /admin/festivals/{festivalId}/publication-batches/{batchId}
    PATCH /admin/festivals/{festivalId}/publication-batches/{batchId}
    POST /admin/festivals/{festivalId}/publication-batches/{batchId}/validate
    POST /admin/festivals/{festivalId}/publication-batches/{batchId}/schedule
    POST /admin/festivals/{festivalId}/publication-batches/{batchId}/apply
    POST /admin/festivals/{festivalId}/publication-batches/{batchId}/cancel
    POST /admin/festivals/{festivalId}/publication-batches/{batchId}/rollback

목록은 200 `data.items: PublicationBatchSummary[]`와 7.2 meta.pagination을 반환한다.
PublicationBatchSummary는 필수 `id`, `batchVersion`,
`kind: STANDARD|SINGLE_TARGET_ROLLBACK|BATCH_ROLLBACK`, `status`, `purpose`, `targetCount`,
`actions: (PUBLISH|UNPUBLISH)[]`, nullable `scheduledApplyAt`, nullable
`appliedContentRevision`, nullable `latestOperationId`, nullable `failure: ProblemSummary`,
`createdBy`, `createdAt`, `updatedAt`만 가진다. actions는 실제 target action을 중복 없이
PUBLISH, UNPUBLISH 순으로 반환한다. detail은 200 `data: PublicationBatch`와 batch ETag다.
생성은 201·Location, PATCH·validate·schedule·cancel은 200으로 모두 갱신된
`data: PublicationBatch`와 새 `ETag: "batch-{batchVersion}"`을 반환한다. validate 실패도
HTTP 200으로 DRAFT batch의 validationResult.errors를 반환하되 새 ETag를 제공한다.
apply·rollback 접수는 202·PublicationOperation·operation Location과 최신 batch ETag를
`X-Batch-ETag`에 반환한다. precondition 자체가 틀리거나 worker 접수 전에 검증할 수 없는
요청만 4xx다.

batch `/validate`와 `/apply`는 request body가 없으며 OpenAPI에도 requestBody를 선언하지
않는다. client는 Content-Type을 보내지 않고, body byte가 하나라도 있으면 빈 JSON object를
포함해 400 INVALID_REQUEST다. 두 요청은 아래 batch ETag의 If-Match와 Idempotency-Key만으로
대상과 멱등 범위를 고정한다.

목록은 status, scheduledFrom, scheduledTo, createdBy와 7.2 pagination만 지원하고 updatedAt
내림차순·id 오름차순으로 고정하며 sort query는 받지 않는다. 생성
body는 필수 `purpose`, `targets`, `overrideDecisions`를 가진다. target은 필수
`resourceType`, `resourceId`, `action: PUBLISH|UNPUBLISH`, `expectedAdminVersion`, nullable
`expectedWorkingRevision`, nullable `expectedPublishedRevision`, nullable
`rollbackTargetRevision`을 가진다. kind=STANDARD인 사용자 생성 batch에서는 rollbackTargetRevision이 항상
null이고 PUBLISH는
expectedWorkingRevision만, UNPUBLISH는 expectedPublishedRevision만 필수이며 다른 revision
필드는 null이다. 활성 operational override의 영향을 받는 target은 19.4의 정확한
overrideDecisions entry가 필수이고 없으면 빈 배열을 보낸다.
POST 생성은 서버가 kind=STANDARD를 설정하며 request에서 kind나 sourceBatchId를 받지 않는다.
targets는 1~10,000개이고 `(resourceType, resourceId)` 쌍은 batch 안에서 유일해 같은 대상을
PUBLISH와 UNPUBLISH로 동시에 선언할 수 없다. overrideDecisions도
`(resourceType, resourceId, overrideId)`가 유일하며 활성 override가 있고 그 target의
전이에 영향받는 경우 정확히 하나만 포함한다. 대상과 무관하거나 중복된 decision도
VALIDATION_ERROR로 거부한다.
10,000 상한은 7.1의 최대 지도 current 집합과 서로 겹치지 않는 최대 desired 집합,
MAP_CONFIGURATION target과 제거 target을 함께 선언하는 worst-case 6,601개를 수용한다.
OpenAPI maxItems, request body 제한과 10,000-target apply·rollback 부하 시험은 같은 상수를
사용한다.

PATCH는 DRAFT batch에서만 `purpose`, `targets`, `overrideDecisions`의 optional subset을
받고 하나 이상을 요구한다. application/merge-patch+json과 batch ETag의 If-Match를 쓰며
병합한 전체 body를 생성 규칙으로 다시 검증한다. 성공 시 batchVersion을 증가시키고 새
ETag를 반환한다. DRAFT가 아니면 409 PUBLICATION_CONFLICT이고 수정하려면 새 batch를
만든다.

PublicationBatch는 상태와 무관하게 필수 key `id`, `batchVersion`,
`kind: STANDARD|SINGLE_TARGET_ROLLBACK|BATCH_ROLLBACK`, nullable
`baseContentRevision`, `status`, `targets`, `overrideDecisions`, `purpose`, nullable
`scheduledApplyAt`, nullable `scheduledBy`, nullable `scheduledAt`, nullable
`scheduledAuthzVersion`, nullable `validationResult`, nullable `commitValidationResult`, nullable
`appliedContentRevision`, nullable
`rollbackBatchId`, nullable `sourceBatchId`, nullable `cancellation`, nullable `latestOperationId`, nullable
`failure: ProblemSummary`, `createdBy`, `createdAt`, `updatedAt`을 가진다. 첫 public head 전에는
baseContentRevision이 null이다. scheduledApplyAt은 SCHEDULED와 그 예약에서 전이한
APPLYING에서만 non-null이며 즉시 apply의 APPLYING과 다른 상태에서는 null이다.
scheduledBy, scheduledAt, scheduledAuthzVersion은 한 번이라도 schedule에 성공한 batch에서
함께 non-null이고 그 밖에는 모두 null이다. 취소·terminal 뒤에도 승인 감사 근거로 보존한다.
validationResult는 validate를 한 뒤 non-null이고, DRAFT PATCH로 target·decision·purpose가
바뀌면 null로 무효화한다.
commitValidationResult는 apply worker가 최신 public head와 target을 병합해 마지막 전수 검증을
수행하기 전에는 null이다. 그 검증을 수행한 terminal batch에서는 성공·실패와 관계없이 마지막
완전한 PublicationValidationResult를 저장하고 batchVersion을 증가시키며, 권한 실패처럼 후보
검증 전에 끝난 경우에는 null이다. acceptance 시점 validationResult를 덮어쓰지 않는다.
appliedContentRevision은 APPLIED·ROLLED_BACK에서만 non-null이고 rollbackBatchId는 rollback
성공 후 원본 batch에서만 non-null이다. sourceBatchId는 kind=BATCH_ROLLBACK에서만 원본
batch ID로 non-null이고 STANDARD와 SINGLE_TARGET_ROLLBACK에서는 null이다.
kind=BATCH_ROLLBACK인 서버 생성 batch에서는 PUBLISH inverse target의
expectedWorkingRevision이 null, expectedPublishedRevision은 접수 당시 current published
revision, rollbackTargetRevision은 복원할 이전 published revision으로 non-null이다. 이전
상태가 UNPUBLISHED 또는 NEVER_PUBLISHED면 action=UNPUBLISH, current published revision인
expectedPublishedRevision만 non-null이고
rollbackTargetRevision은 null이다. rollbackTargetRevision은 immutable revision store에
존재해야 하며 workingRevision을 가리키거나 변경하지 않는다.
kind=SINGLE_TARGET_ROLLBACK은 단일 target rollback endpoint가 내부 생성한다. PUBLISH target은
expectedWorkingRevision=null, expectedPublishedRevision=current published revision,
rollbackTargetRevision=요청의 targetResourceRevision이다. 이 endpoint는 직전
previousPublishedRevision이 non-null일 때만 허용하며 첫 게시를 제거하려면 rollback이 아니라
unpublish를 사용한다. 두 rollback kind의 target은 외부 POST/PATCH body로 만들 수 없다.

cancellation은 status=CANCELED에서만 non-null이고 필수
`reasonCode: USER_REQUESTED|SUPERSEDED_BY_EMERGENCY`, `reason`, nullable `canceledBy`,
`canceledAt`을 가진다. 사용자 cancel과 긴급 action의 원자 선취소 모두 canceledBy에 해당
요청자를 기록한다. 시스템 복구처럼 주체가 없을 때만 null이며 다른 상태에서는
cancellation이 null이다.
latestOperationId는 apply·rollback 또는 cache retry operation이 생성된 뒤 그 최신 ID이고
그 전에는 null이다. failure는 status=FAILED에서만 non-null이며 apply·CAS·worker 실패의
안전한 code와 설명을 담는다. validationResult.errors가 있는 동기 validate는 batch를
DRAFT로 유지하므로 failure가 아니라 validationResult에서 원인을 제공한다.

응답 target은 입력 필드 외에 필수 nullable `validatedAdminVersion`, nullable
`reservedAdminVersion`, nullable `result`를 가진다. validatedAdminVersion은 마지막 성공
validate가 확인한 target adminVersion이고 그 전에는 null이다. reservedAdminVersion은
schedule 또는 apply 접수에서 target 예약 필드를 기록한 뒤의 adminVersion이며 예약 전
실패하면 null이다. result는 apply 전과 실패·취소 시 null이다. APPLIED·ROLLED_BACK이면
result는 필수 `previousPublicationState`, nullable `previousPublishedRevision`, nullable
`appliedPublishedRevision`, `resultingAdminVersion`을 가진다. PUBLISH 결과의
appliedPublishedRevision은 non-null이고 UNPUBLISH 결과는 null이다.
응답 ETag는 `"batch-{batchVersion}"`이며 batchVersion은 target·검증·예약·상태 변경마다
증가한다. 생성과 상태 전이 POST는 Idempotency-Key를, validate·schedule·apply·cancel·
rollback은 이 ETag의 If-Match를 필수로 받는다.

validate 성공은 각 target의 현재 adminVersion이 expectedAdminVersion과 같은지 확인하고
그 값을 validatedAdminVersion에 기록한다. schedule 접수는 모든 target의 현재 version이
validatedAdminVersion과 같고 pending operation이 없는지 한 transaction에서 확인한 뒤 세
예약 필드와 요청자의 scheduledBy·scheduledAt·scheduledAuthzVersion을 기록하고
adminVersion을 증가시켜 reservedAdminVersion에 저장한다. 하나라도
다르면 아무 target도 예약하지 않고 412 REVISION_MISMATCH다.

batch validate는 DRAFT에서만 허용한다. 모든 검사 뒤 validationResult.valid=true이면 결과와
validatedAdminVersion을 저장하고 status를 VALIDATED로, batchVersion을 증가시킨다. valid=false면
새 validationResult.errors·warnings를 저장하되 status=DRAFT를 유지하고 모든 target의
validatedAdminVersion을 null로 두며 batchVersion을 증가시킨다. 다른 상태의 validate는 409
PUBLICATION_CONFLICT다. VALIDATED batch 내용을 바꾸려면 직접 PATCH할 수 없고 새 DRAFT batch를
만든다.

즉시 `/apply` 접수는 batch를 VALIDATED→APPLYING으로 전환하고 PublicationOperation을 만든
뒤 모든 target에 그 pendingPublicationOperationId를 기록해 adminVersion을 증가시키고
reservedAdminVersion을 저장하며 batch.latestOperationId=operationId로 갱신하고 batchVersion을
증가시키는 과정을 원자적으로 수행한다. 같은 batch ETag나 target
version으로 경쟁한 요청 중 하나만 202를 받는다. 예약 worker는 SCHEDULED→APPLYING과
operation 생성·pending ID 기록·adminVersion 증가·reservedAdminVersion 갱신,
latestOperationId와 batchVersion 증가를 같은 transaction에서 수행한다.

public pointer나 operational override를 바꾸는 모든 비동기 worker는 commit 직전에
requestedBy 계정이 ACTIVE이고 현재 authzVersion 기준으로 필요한 역할·global permission과
festival scope를 여전히 갖는지 다시 검사한다. 예약 batch는 scheduledBy를 requestedBy로
사용하고 scheduledAuthzVersion은 접수 당시 감사 snapshot으로 보존한다. 권한이 회수됐으면
public head를 바꾸지 않고 operation과 batch를 FAILED, error.code=PERMISSION_DENIED,
retryable=false로 끝내고 모든 target reservation을 원자 해제한다. live·emergency와 즉시
apply도 같은 규칙을 적용한다. 이미 public commit이 끝난 cache-only retry는 새로운 콘텐츠
권한 행위가 아니므로 접수 시 권한을 검사한 뒤 purge 완료까지 계속할 수 있다.

schedule body는 6절의 offset 포함 RFC 3339 `applyAt`과 `reason`을 받으며 applyAt은 예약
접수 transaction의 serverTime보다 엄격히 미래여야 한다. 같거나 과거이면 side effect 없이
422 VALIDATION_ERROR다. VALIDATED batch만 SCHEDULED로
전환할 수 있고 예약한 정확한 target revision과 overrideDecision을 고정한다. 예약 시각이
되면 SCHEDULED → APPLYING으로 원자 전환한 한 worker만 apply를 실행한다. target revision이나
활성 override가 달라졌거나 최신 public head에 병합한 후보가 재검증에 실패하면 적용하지
않고 FAILED와 PUBLICATION_BATCH_INVALID를 기록한다. validatedBaseContentRevision과 최신
head가 다르다는 사실만으로 실패시키지 않고 19.5의 merge·전체 검증·CAS를 수행한다. 예약
batch는 PUBLISH와 UNPUBLISH target을 함께 가질 수 있어 별도 예약 게시 취소 endpoint가
필요하지 않다.

cancel body는 필수 `reason`을 받으며 DRAFT, VALIDATED, SCHEDULED에서만 CANCELED로
전환한다. SCHEDULED라면 모든 target의 scheduledAction, scheduledBatchId,
scheduledApplyAt을 같은 transaction에서 null로 만든다. 같은 Idempotency-Key의 재시도는
기존 성공 응답을 반환하고, 다른 key로 terminal batch를 다시 취소하면 409
PUBLICATION_CONFLICT다. 사용자가 요청하는 cancel은 APPLYING 이후에는 허용하지 않는다.
19.4의 emergency priority fence만 public commit 전 APPLYING batch를 system CANCELED로
전환할 수 있다. 이 system 전이는 모든 target의 세 예약 필드와
pendingPublicationOperationId를 지우고 adminVersion을 증가시킨다. 같은 transaction에서
원 PublicationOperation은 status=CANCELED, completedAt=현재 시각,
cancellation.reasonCode=SUPERSEDED_BY_EMERGENCY와 긴급 operation ID를 기록한다. 공개
target을 적용하지 않았으므로 affectedResources와 cacheInvalidation.targets는 빈 배열이고
cacheInvalidation.status=NOT_STARTED다.
긴급 operation의 대상 target도 이 cleanup에 포함되면 pendingOperationalOperationId가 그
긴급 operation ID인지 확인한 뒤, cleanup 후 adminVersion을
acceptedResourceState.commitExpectedAdminVersion에 기록하고 긴급 operationVersion을
증가시키는 것까지 같은 transaction에서 수행한다. reservedAdminVersion은 바꾸지 않는다.
따라서 긴급 worker는 정상적인 batch 선취소가 만든 version 증가를 자신의 경쟁 변경으로
오인하지 않는다.

PublicationBatchStatus:

- DRAFT
- VALIDATED
- SCHEDULED
- APPLYING
- APPLIED
- FAILED
- CANCELED
- ROLLED_BACK

validate는 개별 검증과 리소스 간 참조·시간표·지도 정합성을 모두 확인하고
validationResult.validatedBaseContentRevision을 기록한다. apply는 VALIDATED 또는 예약
시각이 된 SCHEDULED batch의 모든 target pointer와 하나의 contentRevision을 원자적으로
적용한다. 일부만 적용할 수 없다.
APPLIED commit은 모든 target의 scheduledAction, scheduledBatchId, scheduledApplyAt과
pendingPublicationOperationId를 null로 만들고 adminVersion을 같은 transaction에서
증가시킨다. 예약 worker가 APPLYING 뒤 FAILED로 끝나도 네 필드를 terminal 전이와 함께
원자적으로 지우며 public pointer는 전혀 변경하지 않는다. 따라서 APPLIED·FAILED batch가
편집을 계속 막지 않는다.

apply worker는 시작 시 현재 public head를 읽고 target transition만 그 head에 병합해
비대상 pointer와 emergency/live-state를 보존한 후보를 만든다. target의 expected revision과
overrideId뿐 아니라 `adminVersion=reservedAdminVersion`,
`pendingPublicationOperationId=operationId`, `pendingOperationalOperationId=null`, batchVersion,
status=APPLYING, emergency priority fence 없음도 다시 확인한다. 후보 전체를 재검증한 뒤
public head와 이 target·batch 조건을 한 transaction의 CAS로 commit한다. emergency fence가
있으면 public pointer를 전혀 적용하지 않고 19.4의 system cancellation을 완료한다. 다른
CAS 경쟁이 나면 최신 head에서 병합·검증을 최대 3회 다시 수행하고, 계속
변하거나 참조가 충돌하면 target을 적용하지 않은 채 operation과 batch를 FAILED,
409 PUBLICATION_CONFLICT로 끝낸다. 이 규칙으로 같은 base에서 시작한 서로 다른 batch가
서로의 비대상 변경을 덮어쓰지 못한다. cancel은 APPLYING 이후에는 409를 반환한다.

worker는 매 merge 시 후보 전체의 완전한 commitValidationResult를 다시 계산한다. target의
불변 workingRevision·rollbackTargetRevision payload가 직접 또는 그 aggregate 안에서 참조하는
mediaId는 acceptance validationResult.referencedMedia에 고정된 mediaVersion·rightsEpoch와
정확히 같고 여전히 READY·권리 유효해야 한다. 하나라도 달라지면 같은 target revision을 다른
미디어 projection으로 게시하지 않고 아무 target도 적용하지 않은 채 operation과 batch를
FAILED, error.code=PUBLICATION_BATCH_INVALID로 끝내며 다시 validate해야 한다. 반면 merge에서
그대로 보존한 비대상 resource의 media는 acceptance 배열에 새로 존재할 것을 요구하지 않고
현재 public head의 PublicContentRevisionManifest pin을 출발점으로 사용한다. 비대상 pointer가
다른 batch로 바뀌어 새 media가 추가돼도 그 최신 manifest와 전체 후보 검증이 유효하면 함께
보존한다. commitValidationResult.referencedMedia에는 target의 고정 pin과 보존한 비대상의
현재 pin을 합친 실제 후보 전체를 중복 없이 기록하고, 성공한 새
PublicContentRevisionManifest.mediaPins도 이 최종 배열과 정확히 같아야 한다. worker는 이
pin·media 상태·public head를 commit transaction의 CAS에 포함하며 재시도마다 최신 head에서
commitValidationResult를 다시 계산한다.

rollback은 APPLIED batch에만 허용하고 필수 `expectedCurrentContentRevision`, `reason`,
`overrideDecisions`를 받는다. If-Match는 batchVersion을, expectedCurrentContentRevision은
festival의 현재 public head를 보호하며 transaction commit 시 둘 다 CAS한다. 다르면 상태를
바꾸지 않고 412 REVISION_MISMATCH다. 서버는 각 target을 batch 적용 직전의
previousPublishedRevision으로 되돌리되, batch 대상이 아닌 나중의 긴급 공지·live-state·다른
콘텐츠 pointer는 현재 head에서 보존한다. batch 적용 뒤 같은 target pointer가 다른 게시로
바뀌었으면 409 PUBLICATION_CONFLICT다.

rollback 접수 transaction은 현재 head와 기록된 이전 pointer를 병합한 후보를 만들고 모든
참조·시간·지도 snapshot과 정확한 overrideDecisions를 먼저 검증한다. 이어 원본 batch의
APPLIED 상태·If-Match, expectedCurrentContentRevision, 각 target pointer와 pending operation
부재를 한 번 더 CAS한다. 성공하면 inverse target을 가진 새 rollback batch를
status=APPLYING, sourceBatchId=원본 ID로 만들고 action=ROLLBACK인 PublicationOperation을
생성한다. 새 batch의 purpose는 rollback body.reason과 정확히 같고 overrideDecisions는 요청
배열을 정렬·검증한 값과 정확히 같다. 새 batch의 baseContentRevision과
validationResult.validatedBaseContentRevision은 접수 시 current head revision이고,
접수 검증의 완전한 PublicationValidationResult를 저장한다. 여기에는 referencedMedia의
mediaVersion·rightsEpoch, 모든 reference/translation/validatedTarget과 조건부 확장의
inventoryChecks·qrIssuanceChecks가 포함된다. inverse target마다 접수 시
validatedAdminVersion과 위 rollback target variant의 expectedPublishedRevision·
rollbackTargetRevision을 고정한다. 복원 대상은 workingRevision이 아니라 불변
rollbackTargetRevision이다. 각 target에 pendingPublicationOperationId를 기록해 adminVersion을 증가시키고 새
batch target.reservedAdminVersion에 고정한다. 원본·새 batch의 latestOperationId를 새
operationId로 설정해 두 batchVersion도 증가시킨다. 이 접수 시점에는 원본 status는
APPLIED, rollbackBatchId는 null이고 공개 pointer는 아직 바뀌지 않는다. 응답은 202
PublicationOperation, Location과 접수 뒤 원본 batch의 `X-Batch-ETag`다. 같은 idempotency
key는 같은 operation을 재생하고 다른 key로 경쟁한 옛 batch ETag는 412다.

worker는 19.5의 현재 권한·media·target reservation·public head CAS를 다시 검사한다. 성공
transaction에서만 새 contentRevision과 target result를 만들고 원본 batch를 ROLLED_BACK으로
전환해 rollbackBatchId를 새 batch ID로 기록한다. 원본의 기존 appliedContentRevision과 과거
target.result는 보존한다. 새 rollback batch는 APPLIED가 되고 appliedContentRevision은 새
rollback contentRevision, 각 target.result는 rollback으로 실제 적용한 결과를 가진다.
PublicationOperation.batchId는 새 rollback batch ID다. 대상 revision·실행자·사유를 감사
로그에 남긴다. worker가 실패하면 공개 pointer와 원본 APPLIED 상태·rollbackBatchId=null을
보존하고 새 rollback batch와 operation만 FAILED로 끝낸다. 이때 모든 target의
pendingPublicationOperationId와 예약 필드를 원자 해제하고 adminVersion을 증가시키며 두
batch의 latestOperationId는 실패 operation을 계속 가리켜 복구 경로를 잃지 않는다.

rollback worker 성공 시 각 target의 publishedRevision과 publicationState는 기록된
previousPublishedRevision과 previousPublicationState로 돌아가고 workingRevision은 보존한다.
workingRevision이 복원된 publishedRevision과 같으면 workingStatus=CLEAN, 다르거나 복원된
publishedRevision이 null이면 DRAFT다. 세 예약 필드는 null로 만든다. 이 상태 전이도 한
transaction과 새 contentRevision에 포함한다.

모든 단일·batch publish, unpublish, rollback은 후보 pointer를 현재 공개 head에 병합한 뒤
정방향·역방향 참조를 다시 검증한다. 현재 HOME·PERFORMANCE·MAP·CHATBOT 등에서 참조하는
리소스를 단독으로 제거해 공개 참조가 끊기면 409 RESOURCE_IN_USE를 반환한다. 호출자는
참조자 수정·게시 취소를 같은 batch의 target으로 포함해야 한다.

publish, unpublish, batch apply, live-state, emergency-publish, emergency-unpublish,
rollback은 202와
PublicationOperation을 반환한다.

| 필드 | 타입 | 설명 |
|---|---|---|
| operationId | UUID | 상태 조회 ID |
| operationVersion | integer | 상태·cache·retry link 변경마다 증가하는 내부 CAS version |
| parentOperationId | UUID, nullable | cache 재시도 원 operation; 최초 작업은 null |
| pendingRetryOperationId | UUID, nullable | 실행 중 cache retry child |
| latestRetryOperationId | UUID, nullable | 가장 최근 cache retry child; terminal 뒤에도 유지 |
| resolvedByOperationId | UUID, nullable | 모든 실패 cache target을 회복한 최신 child |
| batchId | UUID, nullable | 이 작업을 소유하는 단일 또는 다중 target batch |
| status | enum | QUEUED, APPLYING, INVALIDATING_CACHES, SUCCEEDED, SUCCEEDED_WITH_WARNINGS, FAILED, CANCELED |
| action | enum | PUBLISH, UNPUBLISH, APPLY_BATCH, LIVE_UPDATE, EMERGENCY_PUBLISH, EMERGENCY_UNPUBLISH, ROLLBACK, RETRY_CACHE_INVALIDATION |
| acceptedResourceState | object, nullable | live·emergency 접수 시 고정한 target revision·예약 version |
| targetContentRevision | string, nullable | 성공 시 공개될 revision |
| affectedResources | AffectedResource array | target별 적용 결과 |
| cacheInvalidation.status | enum | NOT_STARTED, PENDING, SUCCEEDED, PARTIAL, FAILED |
| cacheInvalidation.targets | CacheInvalidationTarget array | cache별 상태와 시도 결과 |
| error | ProblemSummary, nullable | 실패 code와 안전한 설명 |
| cancellation | OperationCancellation, nullable | system cancellation 원인 |
| requestedBy / requestedAt | UUID / datetime | 요청자와 접수 시각 |
| completedAt | datetime, nullable | 종료 시각 |

AffectedResource의 필수 필드는 `resourceType`, `resourceId`,
`transition: PUBLISH|UNPUBLISH|LIVE_UPDATE`, nullable `previousPublishedRevision`, nullable
`appliedPublishedRevision`이다. CacheInvalidationTarget의 필수 필드는
`target: API|CDN|CLIENT_MANIFEST`, `status: NOT_STARTED|PENDING|SUCCEEDED|FAILED`, `attempts`,
nullable `completedAt`, nullable `error: ProblemSummary`, nullable `resolvedByOperationId`다.
attempts는 0 이상의 integer다. resolvedByOperationId는 실패 target을 후속 retry가 성공한
경우에만 그 child ID이고, 원 status와 error는 역사적 결과로 유지한다.
ProblemSummary의 필수 non-null 필드는 `code`, `title`, `detail`, `retryable: boolean`이며
stack·내부 host·민감정보를 포함하지 않는다. 배열 값이 없으면 null이 아니라 빈 배열이다.
OperationCancellation의 필수 필드는
`reasonCode: SUPERSEDED_BY_EMERGENCY`, `detail`, `canceledAt`, `canceledByOperationId`다.
status=CANCELED에서만 cancellation이 non-null이고 error는 null이다. FAILED에서만 error가
non-null이며 나머지 상태에서는 둘 다 null이다.
acceptedResourceState는 19.4의 live·emergency action에서만 non-null이고 필수
`adminVersion`, `workingRevision`, nullable `publishedRevision`, nullable `overrideId`,
`reservedAdminVersion`, `commitExpectedAdminVersion`을 가진다. 앞의 다섯 감사 필드는 접수 뒤
불변이고 commitExpectedAdminVersion만 19.4의 정확한 emergency fence cleanup에서 갱신할 수
있다. 다른 PublicationOperation에서는 null이다.

`GET /admin/festivals/{festivalId}/publication-operations/{operationId}`로 완료 여부를
조회한다. DB snapshot 전환에 실패하면 contentRevision을 공개하지 않는다. snapshot은
전환됐지만 일부 cache purge가 실패하면 SUCCEEDED_WITH_WARNINGS와 PARTIAL 또는 FAILED를
반환하고 dashboard 경보·재시도 대상으로 남긴다. 운영 UI는 SUCCEEDED 또는
SUCCEEDED_WITH_WARNINGS를 확인하기 전 “반영 완료”로 표시하지 않는다.

cache target별 실패는 5초, 20초, 60초 간격으로 최대 3회 자동 재시도한다. 이후에도
실패하면 PUBLISHER가 `POST .../publication-operations/{operationId}/retry-cache-invalidation`
에 `reason`을 보내 재시도할 수 있다. body는 필수 non-empty `reason`만 가진 닫힌 object이고,
이 endpoint는 19.4 공통 상태 전이의 If-Match 예외로 Idempotency-Key만 요구한다. parent의
operationVersion은 요청 header가 아니라 아래 내부 CAS에만 사용한다. 기존 contentRevision을
다시 게시하지 않으며 실패한 cache target만 대상으로 새 202
PublicationOperation을 만든다. child의 parentOperationId는 원 operationId이고 원 작업은
null이다. parent의 pendingRetryOperationId가 null인지 operationVersion CAS로 확인해 child
ID를 pendingRetryOperationId와 latestRetryOperationId에 기록하고, child terminal에서
pending만 지운다. 원 작업이 batch에 속하면 batch.latestOperationId를 child ID로 갱신하고
batchVersion을 증가시킨다. affected resource와 content·reservation adminVersion은 바꾸지
않으므로 이미 검증·예약된 다른 batch를 무효화하지 않는다.
child가 원 작업의 남은 실패 cache target을 모두 성공시키면 각 target과 parent의
resolvedByOperationId를 child ID로 갱신한다. 일부만 성공하면 성공한 target만 표시하고
parent는 null을 유지한다. activation cache retry도 같은 target·parent 해결 규칙을 쓴다.

publication operation 목록은 `action`, `status`, `batchId`, `resourceType`, `resourceId`,
`parentOperationId`, `requestedBy`와 7.2 pagination을 지원하고 requestedAt 내림차순,
operationId 순으로 반환한다. resourceType·resourceId는 affectedResources에 같은 쌍이 있는
작업을 찾는다. 목록과 detail 모두 operation metadata 저장소를 사용하므로 resource ETag를
변경하지 않고도 Location을 잃은 client가 원 작업과 latestRetryOperationId를 복구한다.
목록 data.items는 PublicationOperationSummary[]이고 meta.pagination은 7.2다. summary는 필수
`operationId`, `operationVersion`, `action`, `status`, nullable `parentOperationId`, nullable
`pendingRetryOperationId`, nullable `latestRetryOperationId`, nullable `resolvedByOperationId`,
nullable `batchId`, `affectedResourceCount`, `failedCacheTargetCount`,
`pendingCacheTargetCount`, nullable `targetContentRevision`, nullable `error: ProblemSummary`,
`requestedBy`, `requestedAt`, nullable `completedAt`만 가진다. 세 count는 0 이상의 integer이고
affectedResources와 cache target 배열은 detail에서만 반환한다.

모든 publication·activation·live·emergency operation은 접수 transaction에서 durable outbox와
함께 저장한다. worker는 operationId별 최대 60초 lease와 heartbeat를 CAS로 획득하고 public
head commit·cache purge 각 단계를 idempotent하게 재개한다. lease 만료 작업은 1분 이내
reconciler가 회수해 최대 5회 지수 backoff로 재시도한다. public commit 전 한도를 소진하면
FAILED와 안전한 error를 기록하고 batch·resource·active-pointer reservation을 같은
transaction에서 해제한다. public commit 뒤에는 pointer를 임의 rollback하지 않고
INVALIDATING_CACHES 단계부터 재개하며 자동 한도 뒤 SUCCEEDED_WITH_WARNINGS로 끝내 수동
cache retry 대상으로 남긴다. QUEUED·APPLYING이 2분 넘게 진전 없으면 경보를 만들고 운영
runbook에 operation 조회, lease 회수, idempotent 재개와 reservation 검증 절차를 둔다.

### 19.6 감사 로그

    GET /admin/festivals/{festivalId}/audit-logs
    GET /admin/festivals/{festivalId}/audit-logs/{auditLogId}
    GET /admin/audit-logs
    GET /admin/audit-logs/{auditLogId}

AuditLog의 필수 key는 `id: UUID`, `actorType: ADMIN|SYSTEM`, nullable `actorId`, nullable
`actorRole`, `action`, nullable `festivalId`, nullable `resourceType`, nullable `resourceId`,
nullable `beforeRevision`, nullable `afterRevision`, `changes: AuditChange[]`, `requestId`,
nullable `sourceOperationId`, nullable `ipSummary`, nullable `userAgentSummary`, `occurredAt`,
`success`, nullable `failureCode`다. actorType=ADMIN이면 actorId와 actorRole이 non-null이고
SYSTEM이면 둘 다 null이다. success=true이면 failureCode는 null, false이면 주요 오류 code가
non-null이다. AuditChange는 필수 `propertyPath`, nullable `beforeSummary`, nullable
`afterSummary`만 가지며 secret·token·비밀번호·원문 개인정보는 어느 summary에도 넣지
않는다. 목록은 occurredAt 내림차순, id 오름차순으로 반환하고 item GET의 data는 같은 AuditLog다.

감사 목록은 festivalId, actorId, action, resourceType, resourceId, success,
occurredFrom, occurredTo와 7.2 pagination만 지원하고 sort query는 받지 않는다. 성공 data는
`items: AuditLog[]`다. festival 경로는 해당
festivalScopes의 VIEWER가 축제 콘텐츠·게시 event만 조회하며 다른 축제, 인증, 계정
event를 반환하지 않는다. 전역 경로는 GLOBAL_AUDIT permission이 필요하다. resource
revision endpoint는 감사 로그와 별도이며 해당 scope의 VIEWER가 볼 수 있다. 감사 로그는
관리자도 수정·삭제할 수 없다. 보관 기간과 접근 역할은 운영 보안 정책으로 관리한다.

축제 scope 감사 응답에서는 ipSummary와 userAgentSummary를 항상 null로 둔다. 전역 감사
응답도 전체 IP나 원문 user agent를 반환하지 않고 auth session과 같은 최소 마스킹 요약만
제공한다. 보안 사고 조사에 필요한 원본 네트워크 메타데이터가 있다면 감사 API와 분리된
암호화 저장소, 더 짧은 확정 보관 기간, 별도 보안 담당자 접근 절차를 적용한다. 해당 보관
기간과 삭제 job이 승인되지 않은 배포에서는 원본을 수집하지 않는다.

## 20. 미디어 업로드

| Method | Path | 설명 |
|---|---|---|
| GET | /admin/media | 미디어 검색·선택 목록 |
| POST | /admin/media/upload-intents | 업로드 intent 발급 |
| POST | /admin/media/{mediaId}/complete | 업로드 검증 완료 |
| GET | /admin/media/{mediaId} | 상태 조회 |
| PATCH | /admin/media/{mediaId} | 대체 텍스트·권리 메타 수정 |
| DELETE | /admin/media/{mediaId} | 미참조 미디어 삭제 |
| GET | /admin/media/{mediaId}/maintenance-issues | 자동 정리·권리 purge 실패 목록 |
| GET | /admin/media/{mediaId}/maintenance-issues/{issueId} | 실패 target·재시도 상태 |
| POST | /admin/media/{mediaId}/maintenance-issues/{issueId}/retry | 실패 target 수동 재시도 |

이 경로들은 URL이 전역형이어도 모두 festival-scoped다. collection GET의 필수 festivalId
query와 upload-intent body의 festivalId는 호출자의 festivalScopes에 있어야 하며, 없으면
item과 같은 404 RESOURCE_NOT_FOUND로 존재·scope를 감춘다. POST에는 해당 회차 EDITOR 이상,
GET에는 VIEWER 이상이 필요하다. PATCH·DELETE도 기본 EDITOR 이상이지만 아래 restrictive
rights 변경은 PUBLISHER 이상을 요구한다. 다른 회차의 festivalId로 미디어를 생성하거나
열거할 수 없다.

upload intent request:

    {
      "festivalId": "7ef50e0b-49c1-465f-a098-796d89f39c1a",
      "fileName": "map-image.png",
      "mimeType": "image/png",
      "size": 482103,
      "purpose": "MAP",
      "altTranslations": {
        "ko": "축제 지도"
      },
      "rights": {
        "source": "승인된 출처 식별 또는 URL",
        "rightsHolder": "권리자",
        "license": "사용 근거",
        "usageExpiresAt": null
      }
    }

purpose는 HERO, ARTIST, SPACE, MENU, MAP, NOTICE, ICON, OTHER 중 하나다. 성공 응답은
201, Location, `ETag: "media-1-rights-1-{effectiveRightsTemporalPhase}"`과 다음 data를
반환한다. 아래 예시는 usageExpiresAt=null이므로 phase가 VALID_WINDOW인 경우다.
v1 업로드는 정적 raster 이미지인 `image/jpeg`, `image/png`, `image/webp`만 허용한다.
SVG, GIF·animated WebP, video와 audio는 415 UNSUPPORTED_MEDIA_TYPE이다. MAP은 최대
20 MiB·100 megapixel, ICON은 최대 2 MiB·4 megapixel, 나머지 purpose는 최대
10 MiB·40 megapixel이다. upload intent의 maxBytes는 이 purpose 정책을 반영하며 decoded
width·height와 pixel 수 제한은 complete 처리에서 다시 검증한다.

    {
      "media": {
         "id": "8f49de83-4c5e-42f9-a83e-72ec18468a12",
         "mediaVersion": 1,
         "rightsEpoch": 1,
        "rightsStatus": "VALID",
        "rightsTemporalPhase": "VALID_WINDOW",
        "ownerFestivalId": "7ef50e0b-49c1-465f-a098-796d89f39c1a",
        "status": "PENDING_UPLOAD"
      },
      "upload": {
        "method": "PUT",
        "url": "https://approved-upload-host.example/signed-object",
        "requiredHeaders": {
          "Content-Type": "image/png"
        },
        "expiresAt": "2030-09-01T10:15:00+09:00",
        "maxBytes": 20971520
      }
    }

upload-intent 성공의 media는 필수 `id`, `mediaVersion`, `rightsEpoch`, `rightsStatus`,
`rightsTemporalPhase`, `ownerFestivalId`, `status`만 가진 닫힌 UploadIntentMediaSummary다. 이
응답 ETag를 complete의 If-Match에 그대로 사용하며, 그 사이 권리 phase가 바뀌면 최신 상세를
다시 읽어야 한다.

upload URL은 최대 15분 유효한 해당 object 전용 HTTPS URL이고 method는 PUT이다.
requiredHeaders는 name→value string map이며 client는 정확히 전달한다. URL과 서명 header는
비밀로 취급해 로그·분석·오류 응답에 남기지 않는다.
rights.usageExpiresAt은 null이거나 request serverTime보다 뒤여야 하며, 30일 이내의 미래
시각이면 최초 rightsStatus·rightsTemporalPhase와 ETag는 EXPIRING·EXPIRING_WINDOW다. 이미
지난 시각은 side effect 없이 422 VALIDATION_ERROR다.

upload-intent의 Idempotency-Key replay record는 최초 signed URL과 requiredHeaders를 domain
Media·감사 record와 분리한 envelope encryption field에 upload.expiresAt까지만 보관한다. 그
전의 같은 key·digest 재시도는 최초 201, Location, ETag와 같은 URL·headers를 재생하고
`Idempotency-Replayed: true`를 반환한다. expiresAt에서 encryption key를 crypto-shred하고
`mediaId`, key digest, request digest, `outcome=UPLOAD_SECRET_EXPIRED`만 공통 최소 24시간
tombstone으로 남긴다. 그 뒤 같은 key·digest는 URL을 재발급하거나 옛 201을 가장하지 않고 409
MEDIA_STATE_CONFLICT를 반환하며, 다른 digest는 409 IDEMPOTENCY_KEY_REUSED다. 새 upload
intent가 필요하면 새 Idempotency-Key로 새 Media를 생성한다. 만료 URL과 서명 header 원문은
로그·감사·일반 idempotency record에 남기지 않는다.

`POST /admin/media/{mediaId}/complete` body는 필수 `size`, `checksumSha256`을 받는다. size는
intent와 일치하는 양의 integer이고 checksumSha256은 업로드 bytes의 lowercase 64자리 hex
SHA-256이다. 요청은 media ETag의 If-Match와 Idempotency-Key를 요구한다. 서버는 object
존재, 크기, checksum, 선언·실제 MIME을 확인하고 202, Location과 필수 `mediaId`,
`mediaVersion`, `status=PROCESSING`을 반환한다. 처리 결과는 GET detail로 조회하며 검증
실패는 REJECTED와 안전한 rejectionReason으로 남긴다.

MediaStatus:

- PENDING_UPLOAD
- PROCESSING
- READY
- REJECTED
- UPLOAD_EXPIRED
- DELETED

허용 전이는 PENDING_UPLOAD → PROCESSING → READY|REJECTED,
PENDING_UPLOAD → UPLOAD_EXPIRED와 처리 중이 아닌 미참조
상태에서의 PENDING_UPLOAD|READY|REJECTED|UPLOAD_EXPIRED → DELETED뿐이다. complete는
PENDING_UPLOAD에서만 status와 mediaVersion을 CAS해 PROCESSING으로 만들고 worker가 그
reserved mediaVersion을 CAS해 READY 또는 REJECTED로 전환한다. 같은 Idempotency-Key의
complete 재시도는 최초 202 결과를 반환하지만 다른 key로 다시 완료하면 409
MEDIA_STATE_CONFLICT다. PROCESSING에서는 PATCH·DELETE를 409 MEDIA_STATE_CONFLICT로
거부한다. DELETED는
terminal이며 complete·PATCH로 되살릴 수 없고 GET은 감사 가능한 tombstone 상태만
반환한다. 모든 전이는 감사 event를 남긴다.

uploadIntentExpiresAt이 지났는데 PENDING_UPLOAD인 record는 reconciliation job이 CAS로
UPLOAD_EXPIRED로 전환하고 존재하는 미완료 object·multipart part를 삭제한다. UPLOAD_EXPIRED에는 complete를
409 MEDIA_STATE_CONFLICT로 거부하며 메타데이터는 감사 보관 기간 동안 남긴다. job은 최소
5분마다 누락 intent와 orphan object를 대조하고 정리 실패를 UPLOAD_CLEANUP
MediaMaintenanceIssue와 운영 경보에 남긴다.

PROCESSING worker는 최대 2분의 lease와 processingAttempts를 CAS로 획득하고 decode·검사를
idempotent하게 수행한다. lease 만료 record는 reconciliation worker가 최대 3회 재개하며
5초·20초·60초 backoff 뒤에도 완료하지 못하면 REJECTED와 안전한 rejectionReason으로
terminal 전환하고 object를 격리 또는 삭제한다. 격리·삭제가 실패하면 UPLOAD_CLEANUP
MediaMaintenanceIssue를 만들되 REJECTED binary를 다시 처리하는 기능으로 사용하지 않는다. 영구 PROCESSING을
허용하지 않으며 lease 회수·최종 실패·정리 결과를 감사 기록한다.

서버 규칙:

- 확장자, 선언 MIME, 실제 MIME과 디코딩 결과를 모두 검증한다.
- purpose별 최대 크기와 pixel 수를 제한한다.
- 악성 파일 검사와 불필요한 EXIF 제거를 수행한다.
- READY인 미디어만 게시 콘텐츠가 참조할 수 있다.
- 참조 중인 미디어 삭제는 409 RESOURCE_IN_USE다. `참조`에는 현재 working revision과 공개
  head뿐 아니라 DRAFT·VALIDATED·SCHEDULED·APPLYING batch, 보존 중인 모든 불변 resource
  revision과 PublicContentRevisionManifest, rollback·restore-to-draft 대상, 유효 session이
  고정한 retained stamp snapshot을 포함한다. v1 API는 이 revision·manifest를 prune하거나
  rollback 부적격으로 전환하는 endpoint를 제공하지 않으므로, 그중 하나라도 mediaId를
  참조하면 DELETE할 수 없다.
- 지도 원본 교체는 새 mapVersion과 좌표 검증을 요구한다.
- 저작권·사용 권한·출처 메타데이터를 관리자 입력에 보존한다.

관리자 Media에는 필수 `ownerFestivalId`, `mediaVersion`, `rightsEpoch`, 업로더와 처리 상태,
rights를 추가한다. 상세 응답은
`ETag: "media-{mediaVersion}-rights-{rightsEpoch}-{rightsTemporalPhase}"`을 반환하며 complete,
PATCH, DELETE는 이를
If-Match로 요구한다. 상태·일반 메타데이터 변경마다 mediaVersion이 증가하고, 권리 record나
권리 phase가 바뀔 때마다 rightsEpoch가 증가한다. rights를 바꾸는 PATCH는 두 version을 모두
증가시킨다. 하나의
미디어는 정확히 한 축제 회차가 소유하며 v1은 회차 간 공유를 허용하지 않는다. 목록의
`festivalId` query는 필수이고 `status`, `purpose`, `mimeType`, `q`, `uploadedBy`,
`rightsStatus`, `uploadedFrom`, `uploadedTo`와 7.2 pagination을 지원한다. 개별 조회·완료·수정·삭제도
ownerFestivalId가 호출자의 festivalScopes에 있어야 한다. 미디어 목록의 data는
`items: AdminMedia[]`이고 createdAt 내림차순·id 오름차순으로 고정한다. sort query는
허용하지 않는다.

MediaMetadataPatchWrite는 optional `altTranslations`, `rights`, `reason`만 받는 닫힌
application/merge-patch+json object이고 altTranslations 또는 rights 중 하나 이상이 필수다.
reason은 있으면 trim 후 1~500자다. binary, ownerFestivalId, purpose, MIME, 크기·checksum,
처리 상태는 바꿀 수 없다. rights를 보내면 필수 `source`, `rightsHolder`, `license`, nullable
`usageExpiresAt` 전체를 교체한다. 현재 deadline이 null인데 유한 deadline을 새로 두거나 새
deadline이 기존 값보다 이른 변경은 `restrictive rights change`다. 이 변경은 해당 media의
공개 참조 유무와 무관하게 회차 PUBLISHER 이상, non-empty reason, 현재 rights temporal phase가
포함된 If-Match를 모두 요구하고 actor·reason·전후 deadline을 감사 로그에 남긴다. 그 밖의
alt·rights presentation metadata와 동일하거나 늦어진 deadline 변경은 EDITOR 이상이 할 수
있다. 권한 검사는 mutation이나 deny fence 생성 전에 수행하며 부족하면 403
PERMISSION_DENIED이고 아무 상태도 바꾸지 않는다. 성공 시 mediaVersion과 rightsEpoch를
증가시키고 새 ETag를 반환한다. altTranslations만 바꾸면 mediaVersion만 증가한다.
alt, 출처 표시와 같은 presentation metadata는 게시 snapshot에 복사되므로 PATCH만으로 기존
공개 문구가 바뀌지 않고 참조 리소스를 다시 검증·게시할 때 새 contentRevision에 포함된다.
반면 `usageExpiresAt`은 복사본이 아니라 mediaId의 live 권리 record가 공개 전송을 지배한다.
기존보다 이른 deadline으로 줄이는 PATCH는 공개 참조를 역조회해 rightsEpoch와 mediaVersion을
증가시키고 API projection·binary edge가 새 epoch를 반드시 확인하도록 하는 deny fence를 같은
transaction에 내구성 있게 기록한다. fence를 모든 전송 경계가 확인할 수 없으면 503으로
PATCH 전체를 rollback한다. 성공 200 뒤에는 과거 public snapshot이나 이미 발급된 URL도 새
deadline을 우회하지 못한다. deadline 연장이나 null 복원은 만료된 public binary를 자동으로
되살리지 않으며 참조 리소스 재검증·게시 뒤에만 다시 제공한다.

rights.source, rightsHolder, license는 생성 시 필수이고 usageExpiresAt이 지난 미디어는 새
게시에 사용할 수 없다. usageExpiresAt은 단순 메모가 아니라 해당 시각부터 공개 전송을
허용하지 않는 deadline이다. 서버는 30일·7일·1일 전에 dashboard와 운영 경보를 만들고,
운영자는 참조 콘텐츠를 교체하거나 게시 취소해야 한다. deadline에는 CDN·API·client
manifest를 purge하고 해당 binary URL은 410 MEDIA_RIGHTS_EXPIRED를 반환한다. 만료 시 공개
projection은 다음 닫힌 규칙을 적용하고 그 결과로 temporal validator와 client manifest를
다시 계산한다.

- standalone nullable Media 필드는 null로 바꾼다.
- `FestivalSpace.media`처럼 item 내부 Media가 필수인 배열은 만료 media를 참조하는 item
  전체를 제거한다. 제거된 HERO가 목록 thumbnail의 원본이면 thumbnail도 null이다.
- MEDIA `HomeIcon`은 같은 alt를 유지한 BUILTIN `LINK`로 투영한다.
- MEDIA `MapCategory.icon`은 같은 alt를 유지한 BUILTIN 아이콘으로 투영하고 builtinKey는 그
  category code다. 만료된 MEDIA marker override는 제거한 것으로 보고 위 fallback을 적용한
  category의 최종 icon을 사용한다.
- 위 예외가 아닌 필수 media, 특히 illustration map base가 남으면 stale URL을 제공하지 않고
  해당 공개 aggregate에 503 CONTENT_REVISION_UNAVAILABLE을 반환하며 긴급 경보를 만든다.

삭제 대신 보존해야 하는 감사·권리 메타데이터는 미디어 binary 수명주기와 분리한다.
rightsStatus는 usageExpiresAt=null 또는 serverTime보다 30일 초과로 남으면 VALID, 0초 초과
30일 이하면 EXPIRING, serverTime 이상 남지 않으면 EXPIRED다. `usageExpiresAt-30일`과
`usageExpiresAt` 경계에서 deadline worker가 rightsEpoch와 rightsEpochUpdatedAt을 원자
증가시키며, worker가 늦으면 첫 admin write가 precondition 검사 전에 같은 CAS 전이를 먼저
materialize한다. safe GET은 상태를 바꾸지 않고 effective phase를 투영한다. 모든 전송 경계는
저장 status와 무관하게 serverTime으로 phase를 다시 판정하므로 지연된 worker가 만료를
연장하지 않는다. deadline worker와 위 restrictive PATCH는
같은 rightsEpoch와 deny fence를 사용하며 origin denial이 먼저 유효해진 뒤 cache purge를
재시도한다. purge가 일부 실패해도 edge deny fence가 binary 전송을 막고 실패 target을 가진
RIGHTS_INVALIDATION MediaMaintenanceIssue를 dashboard와 운영 경보에 남긴다.
rightsTemporalPhase는 VALID_WINDOW, EXPIRING_WINDOW, EXPIRED_WINDOW 중 하나이며 같은
serverTime 판정에서 rightsStatus와 일대일로 대응한다. 상세 safe GET은 effective phase를
응답과 ETag에 함께 사용하고, If-Match도 phase를 비교한다. 목록은 각 AdminMedia item에
effective phase를 투영하되 collection ETag를 제공하지 않고 `Cache-Control: private, no-store`로
반환한다. 따라서 worker가 아직 rightsEpoch를 증가시키지 못했더라도 상세 경계를 지난 같은
ETag로 달라진 body를 반환하거나 옛 phase의 write를 허용하지 않는다.

AdminMedia의 필수 필드는 `id`, `mediaVersion`, `rightsEpoch`, `rightsEpochUpdatedAt`,
`ownerFestivalId`, `fileName`, `purpose`,
`mimeType`, `size`, `width`, `height`, `status`, nullable `url`, nullable `blurHash`,
`altTranslations`, `rights`, `rightsStatus: VALID|EXPIRING|EXPIRED`,
`rightsTemporalPhase: VALID_WINDOW|EXPIRING_WINDOW|EXPIRED_WINDOW`,
`checksumSha256`, `uploadIntentExpiresAt`, nullable `processingLeaseExpiresAt`,
`processingAttempts`, `uploadedBy`, `rejectionReason`, `createdAt`, `updatedAt`이다. width, height,
url, blurHash, checksumSha256, processingLeaseExpiresAt, rejectionReason만 nullable이며
processingAttempts는 0 이상의 integer다. READY 이미지에서는 width, height,
checksumSha256이 필수다. rejectionReason은 REJECTED에서만 non-null이다.
READY이고 rightsStatus가 VALID 또는 EXPIRING이면 url이 versioned HTTPS CDN URL로
non-null이고 blurHash는 선택적이다. READY여도 rightsStatus=EXPIRED이면 url과 blurHash는
null이다. PENDING_UPLOAD·PROCESSING·REJECTED·UPLOAD_EXPIRED·DELETED에서는 url과 blurHash가 null이며 upload intent의
서명 URL을 상세·목록에 다시 노출하지 않는다.

미디어 자동 복구 상태는 다음 literal endpoint로 운영한다.

    GET  /admin/media/{mediaId}/maintenance-issues
    GET  /admin/media/{mediaId}/maintenance-issues/{issueId}
    POST /admin/media/{mediaId}/maintenance-issues/{issueId}/retry

GET은 VIEWER와 ownerFestivalId scope, retry는 EDITOR와 같은 scope를 요구하며 다른 scope의
media·issue는 404로 감춘다. 목록은 `kind`, `status`, `page`, `size`만 받고 attentionAt
내림차순·id 오름차순의 7.2 pagination을 반환한다. detail은 `data:
MediaMaintenanceIssue`, `Cache-Control: private, no-store`와
`ETag: "media-maintenance-{issueVersion}"`을 반환한다.

MediaMaintenanceIssue는 필수 `id`, `issueVersion`, `mediaId`, `ownerFestivalId`,
`kind: UPLOAD_CLEANUP|RIGHTS_INVALIDATION`,
`status: OPEN|RETRYING|RESOLVED`, `targets: MediaMaintenanceTarget[]`, `attempts`,
`attentionAt`, nullable `lastError: ProblemSummary`, nullable `resolvedAt`, `createdAt`,
`updatedAt`만 가진다. target은 필수 `target:
OBJECT_STORAGE|MULTIPART_UPLOAD|QUARANTINE|API|CDN|CLIENT_MANIFEST`,
`status: PENDING|SUCCEEDED|FAILED|SKIPPED`, `attempts`, nullable `lastAttemptAt`, nullable
`error: ProblemSummary`, nullable `skipReason`만 가진 닫힌 schema이고 target enum 순으로
유일하게 정렬한다. issue와 target attempts는 0 이상의 integer다. UPLOAD_CLEANUP은
OBJECT_STORAGE·MULTIPART_UPLOAD·QUARANTINE 중 하나 이상만, RIGHTS_INVALIDATION은
API·CDN·CLIENT_MANIFEST 중 하나 이상만 target으로 가지며 두 집합을 섞지 않는다. OPEN은
하나 이상의 FAILED target과 non-null lastError가 있고, RETRYING은 unresolved target과 null
lastError가 있으며, RESOLVED는 모든 target이 SUCCEEDED 또는 더 이상 적용되지 않아 SKIPPED로
감사 확정되고 resolvedAt이 non-null이다. FAILED에서만 target.error가 non-null이고 SKIPPED에서만
1~500자의 안전한 skipReason이 non-null이며 다른 target status에서는 두 필드가 null이다.
issue의 나머지 상태에서는 resolvedAt이 null이다.

자동 worker는 실패한 source event마다 같은 `(mediaId, kind, sourceEventId)` issue를 upsert해
중복 경보를 만들지 않는다. retry body는 필수 `reason`만 가진 닫힌 object이고 issue ETag의
If-Match와 Idempotency-Key를 요구한다. OPEN issue만 RETRYING으로 원자 전환하고 issueVersion·
attempts를 증가시켜 202, 같은 detail Location, 갱신 issue와 ETag를 반환한다. worker는 실패·
PENDING target만 재시도해 모두 회복하면 RESOLVED, 하나라도 실패하면 새 error와 함께 OPEN으로
돌리고 매 전이·actor·reason을 감사한다. RIGHTS_INVALIDATION retry는 이미 유효한 origin deny
fence를 해제하지 않고 cache purge만 회복한다. RESOLVED 재요청과 경쟁·stale ETag는 각각 409
MEDIA_STATE_CONFLICT와 412 REVISION_MISMATCH이며, issue·target의 secret URL이나 내부 host는
응답·감사에 넣지 않는다. Dashboard.mediaMaintenanceIssues는 이 detail의 OPEN·RETRYING
projection이다.

## 21. 관리자 계정

GLOBAL_ACCOUNTS permission이 있는 관리자만 사용할 수 있다.

    GET   /admin/accounts
    POST  /admin/accounts
    GET   /admin/accounts/{accountId}
    PATCH /admin/accounts/{accountId}
    POST  /admin/accounts/{accountId}/revoke-sessions
    POST  /admin/accounts/{accountId}/invitation/resend
    POST  /admin/accounts/{accountId}/invitation/revoke

- 계정 생성은 초대 기반이며 임시 비밀번호를 응답에 반환하지 않는다.
- 역할 변경과 비활성화는 감사 로그에 남긴다.
- 계정 목록과 감사 로그는 필요한 개인정보만 반환한다.

AdminAccount의 필수 필드는 `id`, `accountVersion`, `email`, `displayName`, `role`,
`globalPermissions`, `festivalScopes`, `status`, `invitationStatus`, nullable
`invitationExpiresAt`, `lastSignedInAt`,
`invitationDelivery`, `createdAt`, `updatedAt`이다.
invitationExpiresAt과 lastSignedInAt만 nullable이다. status는 ACTIVE, DISABLED이고 invitationStatus는 PENDING,
ACCEPTED, EXPIRED, REVOKED다.
invitationExpiresAt은 PENDING·EXPIRED에서 non-null이고 ACCEPTED·REVOKED에서는 null이다.

invitationDelivery는 필수 `generation: integer`, `status: NOT_APPLICABLE|QUEUED|SENT|FAILED`, nullable `queuedAt`,
nullable `lastAttemptedAt`, nullable `sentAt`, nullable `failureCode`만 가진다. 초대 이력이
없으면 generation=0이고 수락·회수 뒤에는 NOT_APPLICABLE이며 나머지 nullable 값은 상황에
맞게 유지한다.

초대 생성 직후 계정은 `status=DISABLED`, `invitationStatus=PENDING`이다. 유효한 초대 수락과
비밀번호 설정이 하나의 transaction으로 성공하면 `status=ACTIVE`,
`invitationStatus=ACCEPTED`가 된다. DISABLED 계정은 로그인·refresh·비밀번호 복구를 할 수
없고 계정 존재 여부를 공개 응답으로 구분하지 않는다.

invitation/resend는 PENDING, EXPIRED 또는 REVOKED 초대에 필수 `invitationExpiresAt`, `reason`을
받아 기존 token을 무효화하고 새 단일 사용 token을 발급한 뒤 invitationStatus=PENDING으로
만든다. invitation/revoke는 PENDING 초대에 필수 `reason`을 받아 token을 무효화하고
invitationStatus=REVOKED로 만든다. 둘 다 계정 ETag의 If-Match와 Idempotency-Key를
요구하고 accountVersion을 증가시키며 token 원문은 응답하지 않는다. ACCEPTED 초대 또는
같은 상태의 반복 변경은 409 PUBLICATION_CONFLICT가 아니라 409 ACCOUNT_STATE_CONFLICT다.
resend와 revoke 성공은 200 data로 갱신된 `AdminAccount`와 새
`ETag: "account-{accountVersion}"`을 반환한다.

revoke-sessions body는 필수 `reason`만 받고 계정 ETag의 If-Match와 Idempotency-Key를
요구한다. 성공은 200과 필수 `account: AdminAccount`, 0 이상의 `revokedSessionCount`를
반환하고 새 account ETag를 제공한다. 서버는 대상의 모든 active refresh session을
폐기하고 authzVersion과 accountVersion을 한 번 증가시키며 감사 event를 같은 transaction에
남긴다. 이미 처리한 같은 idempotency key는 같은 count·version 응답을 반환한다.

AdminAccountInviteWrite는 필수 `email`, `displayName`, `role`, `globalPermissions`,
`festivalScopes`, `invitationExpiresAt`을 받는다. invitationExpiresAt은 서버 시각보다
뒤이고 최대 72시간 이내여야 한다. POST는 Idempotency-Key를 필수로 요구하고 201과
계정·초대 상태만 반환하며 초대 token이나
임시 비밀번호를 응답·로그에 남기지 않는다. 계정·token fingerprint·email delivery outbox를
한 transaction에 만들고 201은 `invitationDelivery.status=QUEUED`가 내구성 있게 저장됐음을
뜻할 뿐 실제 발송 완료를 뜻하지 않는다. 승인된 전달 채널은 계정 email로 보내는 TLS
보호 HTTPS 단일 사용 링크다. worker는 일시 실패를 지수 backoff로 재시도하고 SENT 또는
FAILED를 AdminAccount와 전역 운영 관측 경보에 반영한다. 축제별 Dashboard에 계정 전달
실패를 섞지 않는다. resend도 같은 outbox 계약을 사용한다.
정확한 POST 성공 data는 `AdminAccount`이고
`Location: /api/v1/admin/accounts/{accountId}`와
`ETag: "account-{accountVersion}"`을 반환한다. PATCH 성공도 200 data로 갱신된
AdminAccount와 새 account ETag를 반환한다.
같은 canonical email의 생성 경쟁은 unique constraint와 idempotency record를 같은
transaction에서 잠근다. 같은 key·request digest는 최초 201·Location·ETag·AdminAccount를
재생하고 중복 outbox를 만들지 않으며, 다른 key 또는 다른 digest의 이미 존재하는 email은
409 ACCOUNT_STATE_CONFLICT다.
outbox의 QUEUED→SENT|FAILED와 재시도 시각 변경은 accountVersion·updatedAt을 원자적으로
증가시켜 새 account ETag를 만든다. resend는 If-Match 뒤 generation을 증가시키고 이전
token·outbox를 무효화한다. 늦게 도착한 이전 generation worker 결과는 현재 delivery를
덮어쓰지 않고 감사 로그에 stale completion으로 남긴다.
worker는 외부 전달을 시작하기 전과 결과 commit 때 모두
`invitationStatus=PENDING AND outbox.generation=invitationDelivery.generation AND token=ACTIVE`
를 accountVersion과 함께 CAS한다. accept·revoke·deadline expiry transaction은 token을 먼저
폐기하고 같은 generation의 미전달·재시도 outbox를 terminal CANCELED로 만든 뒤
invitationDelivery=NOT_APPLICABLE을 함께 commit한다. 그 뒤 도착한 worker 성공·실패 callback은
SENT·FAILED나 accountVersion을 다시 쓰지 않고 stale completion 감사만 남긴다. 이미 외부
provider에 넘긴 메시지는 회수할 수 없지만 포함된 단일 사용 token은 폐기돼 수락에 사용할 수
없다.
deadline reconciler는 최소 1분마다 PENDING이면서 invitationExpiresAt 이하인 초대를 CAS로
EXPIRED로 바꾸고 token을 폐기하며 invitationDelivery의 같은 generation을 더 이상 발송하지
않는다. 이 전이도 accountVersion·updatedAt을 증가시키고 감사 event를 남긴다.
비밀번호 reset request는 계정 존재 여부와 무관한 동일 202를 먼저 반환하고, 실제 계정이
있을 때만 같은 승인 email outbox를 만든다. 공개 응답에서는 queue·전달 여부를 구분하지
않는다.

AdminAccountPatchWrite는 `displayName`, `role`, `globalPermissions`, `festivalScopes`,
`status`, `reason`만 허용하며 하나 이상의 변경 필드와 필수 reason을 받는다. 권한 또는
scope 축소 시 기존 session을 즉시 폐기하고, 확대만 있으면 session을 유지한다. 계정 목록은 status, role, globalPermission,
festivalId, invitationStatus, q와 7.2 pagination만 지원한다. 성공 data는
`items: AdminAccount[]`이고 updatedAt 내림차순·id 오름차순으로 고정하며 sort query는 받지
않는다.
status=ACTIVE 전환은 invitationStatus=ACCEPTED이고 유효 credential이 이미 설정된 계정에만
허용한다. PENDING·EXPIRED·REVOKED 초대를 PATCH로 우회 활성화할 수 없으며 409
ACCOUNT_STATE_CONFLICT를 반환한다. DISABLED 전환은 모든 session을 함께 revoke한다.

계정 상세는 `ETag: "account-{accountVersion}"`을 반환한다. PATCH와 revoke-sessions는 이
ETag의 If-Match를 요구하고 계정·권한·session 변경마다 accountVersion이 증가한다. 자신의
권한 변경처럼 요청 처리 중 access token의 권한이 달라지더라도 서버는 transaction 시작
시 인증과 If-Match를 모두 검증한다. 성공 뒤 access token은 authzVersion 불일치로
거부하고, refresh session 폐기 여부는 위 확대·축소 규칙을 따른다.

globalPermissions의 허용 값은 18.3의 GlobalPermission 목록이며 GLOBAL_ACCOUNTS 없이는
자기 자신을 포함한 어떤 계정의 전역 권한도 변경할 수 없다. 각 활성 festivalScope의
마지막 ADMIN과 시스템의 마지막 GLOBAL_ACCOUNTS·GLOBAL_FESTIVALS 보유 계정은 각각
비활성화·강등·권한 회수할 수 없다.
여기서 유효 holder는 `status=ACTIVE`, `invitationStatus=ACCEPTED`, 유효 credential을 모두
충족한 계정만 센다. festival 마지막 ADMIN은 해당 festivalId가 festivalScopes에 있고
role=ADMIN인 유효 holder, 전역 마지막 holder는 해당 GlobalPermission을 가진 유효 holder를
transaction snapshot에서 계산한다. 현재 요청 결과까지 반영해 0명이 되면 409
LAST_ADMIN_PROTECTION이며 DISABLED·미수락 초대는 보호 인원으로 세지 않는다.
서버는 영향받는 festivalId별 ADMIN guard row와 각 GlobalPermission guard row를 잠그거나
SERIALIZABLE predicate locking과 동등한 방식으로 검사와 account mutation을 직렬화한다.
서로 다른 두 계정을 동시에 강등해 write-skew로 마지막 holder를 없앨 수 없다.

## 22. Cache와 조건부 요청

공개 GET은 ETag와 Last-Modified를 제공한다. public JSON ETag는 진단용
meta.serverTime 차이만 무시하는 weak validator이며, endpoint·정규화 query·요청 언어·실제
content 언어·fallback 여부·contentRevision·mapVersion 또는 activePointerVersion을 포함한다.
representation이 직접 또는 nested로 참조하는 모든 Media가 있으면 mediaId 순으로 정렬한
`(mediaId, mediaVersion, rightsEpoch, rightsPhase)` tuple의 stable hash인
`mediaRightsValidator`도 반드시 포함한다. rightsPhase는 해당 요청 serverTime의
VALID|EXPIRING|EXPIRED 판정이므로 deadline worker가 늦어도 경계를 지난 304를 만들지 않는다.
If-None-Match가 있으면 이를 우선하고 If-Modified-Since는 보조 validator로 사용한다.

festival status, selectionReason, current item, PerformanceStatus, OperatingStatus,
SpaceEvent status, notice visibility, nextPerformance처럼 serverTime 경계로 representation이
변하는 endpoint는 `/festivals/current`, festival, home, artists 목록·상세, performances
목록·상세, spaces 목록·상세, map marker 목록·상세, notices 목록·상세다. 이 endpoint의
ETag는 위 값에 현재 시각이 속한 `temporalStateVersion`을 추가한다. 이 version은 해당
응답의 모든 적용 가능한 시작·종료·노출 경계를 정렬했을 때 마지막으로 지난 경계의 안정적
식별자와 활성 여부와 무관한 마지막 operational apply·clear event version 및
operationalUpdatedAt에서 계산한다. override를 NORMAL로 해제해 active 값이 null로 돌아가도
이 event version은 이전 값으로 되돌아가지 않는다. 경계를 넘으면 contentRevision이
같아도 ETag가 반드시 바뀐다.

시간 파생 응답의 Last-Modified는 snapshotUpdatedAt, 마지막으로 지난 적용 가능 경계,
마지막 operational override commit 시각, 포함 Media의 rightsEpochUpdatedAt과 마지막으로
지난 rights phase 경계 중 가장 늦은 값이다. `/festivals/current`는
snapshotUpdatedAt 대신 active pointer 갱신 시각을 쓰고 festival startsAt·endsAt 경계도
포함한다. 304는 ETag의 temporalStateVersion이 현재와 같은 경우에만 허용한다. cached JSON의
meta.serverTime은 그 representation 생성 시각이며 현재 시각으로 덮어쓰지 않는다. client와
intermediary는 HTTP Date·Age로 경과 시간을 판단한다.

기본 Cache-Control:

| 리소스 | 정책 |
|---|---|
| festivals/current | public, max-age=30 |
| festival, artists, spaces, external-links | public, max-age=60, stale-while-revalidate=300 |
| home, performances, notices | public, max-age=15, stale-while-revalidate=30 |
| map metadata, markers | public, max-age=60, stale-while-revalidate=300 |
| versioned media | public, 최대 max-age=31536000; rights deadline까지 남은 초를 넘지 않음 |
| chatbot, admin, auth | no-store |

- 시간 파생 응답의 실제 max-age는 표의 값과 다음 상태 경계까지 남은 초 중 작은 값이다.
  이전 temporalStateVersion은 경계를 지난 뒤 stale-while-revalidate로 제공하지 않으며,
  origin 또는 edge timer가 경계에서 재검증한다.
- cache key에는 festivalId, contentRevision, 요청 language, 실제 content language,
  fallbackApplied, filter, sort, cursor, mapVersion, activePointerVersion,
  temporalStateVersion, mediaRightsValidator 등 응답을 바꾸는 값을 포함한다.
- 언어 협상 응답은 Vary: Accept-Language를 포함한다.
- 모든 live-state, 긴급 공지 게시·회수와 시간표 변경은 TTL 만료를 기다리지 않고 영향받는
  home·performances·spaces·map marker·notices 및 CDN·CLIENT_MANIFEST를 purge한다.
- stale 응답을 제공하면 Warning 헤더와 meta.snapshotUpdatedAt으로 오래된 상태를 알린다.
- 안전과 관련된 긴급 공지는 무기한 stale로 제공하지 않는다.
- availableUntil이 null인 versioned media만 1년 immutable을 사용할 수 있다. finite deadline이
  있으면 max-age를 그 경계까지 남은 초로 줄이고 expiry 뒤 stale 응답을 금지하며 즉시 purge한다.
- Media를 포함한 모든 public JSON도 실제 max-age를 포함된 availableUntil 중 가장 가까운
  경계까지 남은 초로 줄인다. 여기에는 current/festival/home/artists/performances/spaces/map/
  notices/external-links/chatbot과 이후 Media projection을 추가하는 공개 endpoint가 모두
  포함된다. rights PATCH·EXPIRING·EXPIRED 전이는 영향 JSON API, CLIENT_MANIFEST, CDN binary를
  역참조해 즉시 purge하며, mediaRightsValidator와 edge deny fence가 purge 실패 중에도 오래된
  URL·304를 막는다. 경계를 넘은 representation은 stale-while-revalidate로 제공하지 않는다.

## 23. 요청 제한

기본 제한은 출발값이며 실제 부하 시험, 행사장 동시 접속량과 장애 대응 정책에 따라
엄격하게 하거나 완화할 수 있다.

| 경계 | 기본 제한 |
|---|---|
| 공개 GET | IP당 분당 120회 soft threshold와 endpoint·edge 전체 capacity bucket |
| 챗봇 | 익명 단기 rate token당 분당 20회 soft threshold와 endpoint·edge capacity bucket |
| 관리자 로그인 | IP와 계정당 15분에 5회 |
| 초대 수락·비밀번호 reset 완료 | IP와 token fingerprint당 15분에 10회 |
| 비밀번호 reset 요청 | IP당 15분에 5회, 정규화 email당 1시간에 3회 |
| 관리자 refresh | session당 분당 30회 |
| 관리자 쓰기 | 관리자당 분당 60회 |

429 응답은 Retry-After와 RateLimit-Limit, RateLimit-Remaining,
RateLimit-Reset을 제공한다. 신뢰 가능한 proxy chain을 명시하고 임의의
X-Forwarded-For 값을 그대로 IP로 사용하지 않는다. CDN cache hit는 origin quota를
소비하지 않는다. 행사장 Wi-Fi·통신사 NAT처럼 여러 방문자가 IP를 공유할 수 있으므로
공개 cacheable GET은 IP threshold 하나만으로 차단하지 않고 endpoint·edge capacity와
명백한 자동화 패턴을 함께 판정한다. 홈·지도·시간표·긴급 공지는 보호된 전체 capacity
안에서 burst를 허용하고, 제한 중에도 검증된 최신 cache snapshot을 제공할 수 있어야 한다.

비로그인 챗봇도 공유 IP 하나를 hard key로 쓰지 않는다. chatbot configuration 또는 첫
message 응답은 계정·개인정보를 담지 않은 서명된 `X-Anonymous-Rate-Token`을 발급할 수 있고,
client는 이후 같은 header로 보낸다. token은 festivalId, 30분 이하 만료와 방문자 bucket을
구분할 128 bit 이상의 무작위 anonymousBucketId만 담고 분석·영구 프로필에 재사용하지
않는다. anonymousBucketId는 사용자·기기 정보에서 파생하지 않고 만료 시 폐기한다. 서버는 이
단기 token, IP soft threshold, endpoint·edge
capacity와 자동화 행동 신호를 함께 사용한다. token이 없다는 이유만으로 공개 메시지를
거부하지 않으며 새 token을 발급한다. 행사장 NAT 부하 시험을 통해 챗봇 threshold도 강화·
완화하고, 공유 IP만으로 다수 방문자를 동시에 429 처리하지 않는다.

## 24. 보안과 개인정보

- 공개 endpoint에 로그인 redirect나 사용자 식별 cookie를 요구하지 않는다.
- TLS를 강제하고 HSTS, CSP, Referrer-Policy, X-Content-Type-Options 등 배포 보안
  헤더를 설정한다.
- CORS는 운영·staging origin allowlist만 허용한다.
- rich text는 저장과 출력 경계에서 허용 목록 기반으로 정화한다.
- 외부 URL은 HTTPS, scheme, host, redirect를 검증한다.
- 입력 길이, 배열 크기, 숫자 범위, UUID 형식을 서버에서 검증한다.
- 로그에 access token, refresh token, 비밀번호, 개인정보, 원문 챗봇 메시지를 남기지
  않는다.
- 비밀은 secret manager에서 주입하며 저장소와 client bundle에 포함하지 않는다.
- 관리자 권한은 최소 권한으로 부여하고 행사 종료 후 불필요한 세션과 권한을 회수한다.
- 취약점, 의존성, container, secret 검사를 CI와 출시 절차에 포함한다.

## 25. 데이터 무결성과 복구

- DB foreign key로 festival 경계를 강제한다.
- 정규화 좌표는 0 이상 1 이하, WGS84 좌표는 유효 위도·경도 범위의 check constraint를
  적용한다.
- 같은 festivalId와 mapVersion에서 티켓 장소의 canonical marker는 하나만 허용한다.
- 게시 revision은 불변 snapshot 또는 동등한 방식으로 복원 가능해야 한다.
- 자동 백업, point-in-time recovery, 지도·미디어 자산 복구를 운영한다.
- 복구 시점 목표와 복구 시간 목표를 운영 runbook에 기록하고 행사 전 복원 훈련으로
  검증한다.
- schema migration은 이전 애플리케이션 버전과 호환되는 expand·migrate·contract
  순서를 우선한다.
- 배포와 대량 게시에는 rollback 조건, 실행자, 검증 절차를 둔다.

## 26. 관측성과 운영

- 모든 요청은 X-Request-Id를 가지며 구조화 로그와 trace에 연결한다.
- 로그 공통 필드는 timestamp, level, service, environment, requestId, route,
  status, durationMs, festivalId, contentRevision이다.
- locale, mapVersion, cache 상태는 운영 분석에 필요한 범위에서 기록한다.
- API 지연·오류율, cache hit·stale, DB pool, 게시 실패, 미디어 처리 실패, 챗봇 실패를
  지표와 경보로 연결한다.
- 공개 핵심 API와 관리자 게시 흐름에 synthetic smoke test를 둔다.
- /healthz는 프로세스 생존, /readyz는 필수 의존성과 읽을 수 있는 게시 revision을
  검사한다. 두 endpoint는 상세 내부 정보를 공개하지 않는다.
- 챗봇 의존성 장애는 circuit breaker와 timeout으로 격리한다.
- 운영 변경 후 home, performances, map, notices의 revision 일치를 확인한다.

## 27. 주요 오류 code

| code | HTTP | 의미 |
|---|---:|---|
| INVALID_REQUEST | 400 | 요청 문법 오류 |
| INVALID_QUERY_PARAMETER | 400 | 허용하지 않는 query |
| INVALID_CURSOR | 400 | 만료·변조·조건 불일치 cursor |
| INVALID_RESOURCE_TYPE | 400 | publication target type이 허용 목록에 없음 |
| IDEMPOTENCY_KEY_REQUIRED | 400 | 해당 변경 요청의 Idempotency-Key 누락 |
| INVITATION_INVALID_OR_EXPIRED | 400 | 초대 token 만료·사용·불일치 |
| RESET_TOKEN_INVALID_OR_EXPIRED | 400 | 비밀번호 복구 token 만료·사용·불일치 |
| UNSUPPORTED_LANGUAGE | 400 | 지원하지 않는 명시적 언어 |
| FILTER_NOT_SUPPORTED | 400 | 비활성 capability 또는 숨김 category 등 현재 공개 설정이 허용하지 않는 필터 |
| FILTER_SELECTION_LIMIT | 400 | 지도 설정의 선택 개수 초과 |
| AUTHENTICATION_REQUIRED | 401 | 관리자 인증 필요 |
| AUTHENTICATION_FAILED | 401 | 관리자 자격 증명 불일치 |
| TOKEN_EXPIRED | 401 | access token 만료 |
| PERMISSION_DENIED | 403 | 역할·전역 permission·festival scope 부족 |
| ADMIN_CSRF_INVALID | 403 | 관리자 cookie endpoint의 Origin·Fetch Metadata 검증 실패 |
| FESTIVAL_NOT_PUBLISHED | 404 | 공개 축제 회차 없음 |
| RESOURCE_NOT_FOUND | 404 | 리소스 없음 또는 비공개 |
| ARTIST_NOT_FOUND | 404 | 공개 아티스트 없음 |
| PERFORMANCE_NOT_FOUND | 404 | 공개 공연 없음 |
| SPACE_NOT_FOUND | 404 | 공개 공간 없음 |
| MARKER_NOT_FOUND | 404 | 공개 marker 없음 |
| NOTICE_NOT_FOUND | 404 | 공개 공지 없음 |
| FEATURE_NOT_ENABLED | 404 | 해당 회차에서 공개하지 않는 capability |
| MEDIA_RIGHTS_EXPIRED | 410 | 사용 권리 기한이 지나 공개 전송이 중단된 미디어 |
| RESOURCE_IN_USE | 409 | 참조 중인 리소스 |
| SCHEDULE_CONFLICT | 409 | 같은 무대의 공연 시간 중복 |
| PUBLICATION_CONFLICT | 409 | 게시 상태·batch 충돌 |
| PUBLICATION_BATCH_INVALID | 409 | batch 검증 실패 또는 검증 후 변경 |
| BATCH_REQUIRED | 409 | 시간표·지도의 단일 target 게시 action 금지 |
| IDEMPOTENCY_KEY_REUSED | 409 | 다른 body로 key 재사용 |
| IDEMPOTENCY_REQUEST_IN_PROGRESS | 409 | 동일 멱등 요청의 최초 처리가 아직 응답을 확정하지 못함 |
| MEDIA_STATE_CONFLICT | 409 | 현재 미디어 상태가 요청 전이를 허용하지 않음 |
| MAP_VERSION_MISMATCH | 409 | 지도 자산과 marker 버전 불일치 |
| REVISION_CHANGED | 409 | cursor의 게시 snapshot을 더 이상 제공할 수 없음 |
| APPROVAL_REQUIRED | 409 | capability·게이트 기능의 승인 근거 없음 |
| OPERATIONAL_OVERRIDE_CONFLICT | 409 | 활성 현장 override 처리 정책 누락·충돌 |
| ACCOUNT_STATE_CONFLICT | 409 | 계정·초대·session 상태가 요청 전이를 허용하지 않음 |
| LAST_ADMIN_PROTECTION | 409 | 변경 결과 유효한 마지막 축제 ADMIN 또는 필수 전역 permission holder가 없어짐 |
| REVISION_MISMATCH | 412 | If-Match 또는 요청한 일반 resource revision/version precondition 불일치 |
| MAP_SNAPSHOT_MISMATCH | 412 | map과 marker의 snapshotId 불일치 |
| UNSUPPORTED_MEDIA_TYPE | 415 | 지원하지 않는 MIME |
| VALIDATION_ERROR | 422 | 필드 검증 실패 |
| PRECONDITION_REQUIRED | 428 | 관리자 변경 요청의 필수 If-Match 누락 |
| RATE_LIMITED | 429 | 요청 제한 초과 |
| INTERNAL_ERROR | 500 | 안전하게 복구되지 않은 오류 |
| CONTENT_REVISION_UNAVAILABLE | 503 | 게시 snapshot의 번역·revision 불변조건 위반 |
| SERVICE_UNAVAILABLE | 503 | 일시적 의존성 장애 |

같은 원인에는 endpoint별로 다른 code를 만들지 않는다. 새 code를 추가할 때 OpenAPI,
클라이언트 fallback 처리, 관측 대시보드와 계약 테스트를 함께 갱신한다.

## 28. 출시 계약 검증

API 변경 PR은 영향받는 항목을 자동화한다.

- OpenAPI 문법과 example validation
- 공개·관리자 인증 경계 테스트
- 관리자 비밀번호 15·128 code point 경계, Unicode NFC, 차단 목록과 붙여넣기 허용
- refresh rotation·재사용 탐지, session 폐기, CSRF, 계정 존재 여부 비노출
- 이전 v1 consumer 호환 테스트
- 요청·응답 schema와 RFC 9457 오류 계약 테스트
- locale 선택, translationPolicy 조합, 응답 단위 fallback, cache key 분리
- Asia/Seoul 경계와 동시 공연·지연·취소 테스트
- cursor 안정성, 정렬, 최대 limit
- LINEUP_CATEGORY·STAGE·PERFORMANCE·MAP_CATEGORY·MAP_AREA·MAP_MARKER·EXTERNAL_LINK와
  space별 MENU_SECTION·MENU_ITEM·SPACE_EVENT 공개 상한, 게시·activate 거부와 상한 크기
  응답 부하
- 지도 좌표 범위, mapVersion·snapshotId, 티켓 marker 중복 방지
- 게시 상태 전이, If-Match, Idempotency-Key
- 게시 batch 원자성, 비동기 operation, cache purge 상태, rollback
- live-state operational override의 RETAIN·CLEAR·충돌 처리
- 관리자 역할·festival scope·GLOBAL_AUDIT·GLOBAL_ACCOUNTS·GLOBAL_FESTIVALS별 허용·거부
- 빈 환경 최초 관리자 provisioning의 단일 성공·PENDING token rotate·COMPLETED 재실행 차단과
  secret·감사 로그 배제
- ownerFestivalId가 다른 미디어의 목록·조회·완료·삭제 거부
- 관리자 IA 도메인별 literal route와 DTO의 OpenAPI coverage
- 번역 현황, working/batch preview, 관리자 챗봇 test provenance와 capability 승인 gate
- 업로드 MIME·크기·디코딩·악성 파일 검사
- mediaVersion·rightsEpoch 동시성, EXPIRING·EXPIRED 경계의 API/CDN deny·ETag·purge
- activate 접수 뒤 public head 변경의 재검증·contentRevision CAS와 실제 적용 revision 감사
- rate limit과 개인정보·비밀 로그 배제
- 백업 복원과 행사 피크 트래픽 부하 검증

출시 전에는 실제 운영 데이터의 축제명, 날짜, 무대, 출연진, 운영 시간, 메뉴, 지도
자산·좌표, 공지, 링크, 번역을 승인 자료와 대조한다. 예시 값은 운영 데이터로 자동
승격하지 않는다.

## 29. 조건부 확장 계약: 스탬프 투어

### 29.1 현재 상태와 적용 게이트

스탬프 투어는 현재 실행 OpenAPI, 배포 route와 Festival.capabilities에 적용되어 있지 않다.
이 절은 적용 결정을 받은 뒤 사용할 계약이며, 문서에 존재한다는 사실만으로 기능을
활성화하지 않는다. 적용 release는 다음 결정을 식별하는 ApprovalReference를 모두 받아야
한다.

저장소 `test/`의 PWA 스탬프·Web Push 코드는 실기기 검증용 독립 prototype이다. 그 route,
cookie, 데이터 모델이나 실행 가능 상태는 본 제품의 스탬프 투어 적용·승인·운영 API로
간주하지 않으며, 승인 release가 이 절의 계약을 별도로 구현·검증해야 한다.

아래 변경은 닫힌 schema의 필수 key와 enum을 늘리므로 공개된 v1 consumer에게 그대로
추가할 수 있는 하위 호환 변경이 아니다. 첫 v1 배포와 consumer contract freeze 전에 승인이
끝나면 이 절 전체를 v1 OpenAPI에 한 번에 편입할 수 있다. v1이 한 번이라도 공개된 뒤라면
동일 의미 계약을 `/api/v2`에서 제공하고 migration·sunset 계획을 2절대로 공지해야 하며,
일부 필드만 v1에 끼워 넣지 않는다. 아래 `/api/v1` 표기는 첫 배포 전 편입하는 경우에만
유효하다.

- 총학생회가 승인한 명칭·설명·체크포인트·운영 기간과 완료 기준
- 방문자가 QR을 읽는 검증 방식, QR 공유 위험과 회전·폐기 정책
- 동일 체크포인트 중복, 수동 보조 처리와 부정 참여 대응
- 경품 사용 여부; 사용할 경우 품목·수량·예약·수령·미수령 반환 정책
- 룰렛 채택 여부; 채택하면 확률·라운드·`1일 1회` 적용 대상·재응모·결과 공개·공정성 감사
  계약을 별도 버전으로 먼저 승인한다. 이 절의 LIMITED_IN_PERSON 예약은 룰렛을 구현하지 않는다.
- 익명 참여 데이터와 감사 데이터의 보관 기간·삭제, 미성년자 처리와 개인정보 비수집 범위
- 카메라를 쓰기 어려운 방문자를 위한 접근 가능한 현장 절차

적용 release는 한 변경에서 다음을 함께 수행한다.

1. Festival.capabilities의 닫힌 schema에 `stampTour: boolean`을 추가해 FestivalPatchWrite,
   AdminFestival과 public Festival에서 round-trip한다. capabilityApprovals에는 nullable
   `stampTour: ApprovalReference`를 추가하되 19.4대로 FestivalPatchWrite·AdminFestival·게시
   검증에만 사용하고 public Festival에는 승인 reference를 노출하지 않는다.
2. 아래 공개·관리자 path와 schema를 OpenAPI와 계약 테스트에 추가한다.
3. PublicationResourceType에 `STAMP_TOUR_CONFIGURATION`, `STAMP_CHECKPOINT`,
   `STAMP_REWARD_CONFIGURATION`을 추가한다.
4. 29.6의 최소 권한과 계정 grant를 추가한다.
5. 아래 오류 code, rate limit, retention·삭제 job과 dashboard 지표를 배포한다.
6. HomeAction tagged union에 추가 payload가 없고 outer festivalId의 `/stamp-tour`로 이동하는
   `OPEN_STAMP_TOUR` variant를 추가한다. HomeIcon의 `builtinKey` enum에는 `STAMP`를 추가하고,
   `stampTour=true`와 published STAMP_TOUR_CONFIGURATION을 요구하는 quick link 검증,
   HOME preview·게시 참조 검증·cache purge를 함께 추가한다. 임의 stampTourId나 route 문자열은
   payload에 넣지 않는다.
7. 관리자 preview surface에 `STAMP_TOUR`를 추가한다. payload는 익명 참여 상태가 없는
   `StampTourConfiguration`이며 WORKING·BATCH·PUBLISHED source, 번역·media·marker 참조 검증과
   previewSnapshotId를 19.3과 동일하게 지원한다.

하나라도 빠지면 capability를 true로 게시하거나 route 일부만 먼저 노출하지 않는다.
`stampTour=false`이면 공개 `GET /festivals/{festivalId}/stamp-tour`, bootstrap·새
session·check-in·session-pass·새 claim,
관리자 manual-check-in과 display QR token 발급은 404 FEATURE_NOT_ENABLED다. 이미 commit된
session 생성·check-in·manual-check-in·reward-claim 생성의 동일 key·digest 완료 replay는 새
mutation이 아니므로 아래 멱등 계약에 따라 예외적으로 원래 성공을 재생한다. session-pass는
완료 replay도 feature gate를 우선해 404다. 다만 capability를
끄기 전에 만들어진 session의 progress·csrf·consent·DELETE와 이미 ISSUED인 claim의
claim-pass·현장 fulfillment는 각 retention·claim expiry까지 유지해 참여 데이터와 예약
재고를 고립시키지 않는다. 29.6의 관리자 configuration·checkpoint·reward draft
조회·편집, QR issuance·display-session 준비, preview·validate는 STAMP_MANAGER 권한이 있으면
capability=false에서도 허용해 최초 공개 batch를 준비할 수 있다. 실제 적립·표시·수령의
operational action만 위 gate를 따른다. 적용 release 전에는 route 자체가
OpenAPI에 없다. 일반 공개 기능과 챗봇은 계속 로그인 없이 동작한다.

### 29.2 공개 endpoint

모든 path 앞에는 `/api/v1`이 붙는다.

| Method | Path | 참여 session | 설명 |
|---|---|---:|---|
| GET | /festivals/{festivalId}/stamp-tour | 불필요 | 게시 설정·체크포인트 조회 |
| POST | /festivals/{festivalId}/stamp-tour/session-bootstrap | 불필요 | session 생성용 same-origin bootstrap 발급 |
| POST | /festivals/{festivalId}/stamp-tour/sessions | 불필요 | 익명 참여 session 생성 |
| POST | /festivals/{festivalId}/stamp-tour/session/csrf | 필요 | 새로고침 뒤 CSRF token 재발급 |
| POST | /festivals/{festivalId}/stamp-tour/session/consent | 필요 | 변경된 개인정보 고지 재동의 |
| GET | /festivals/{festivalId}/stamp-tour/progress | 필요 | 자신의 진행·경품 상태 조회 |
| DELETE | /festivals/{festivalId}/stamp-tour/session | 필요 | 자신의 참여 데이터 조기 삭제 |
| POST | /festivals/{festivalId}/stamp-tour/check-ins | 필요 | 체크포인트 스탬프 적립 |
| POST | /festivals/{festivalId}/stamp-tour/session-passes | 필요 | 현장 보조용 단기 참여 pass 발급 |
| POST | /festivals/{festivalId}/stamp-tour/reward-claims | 필요 | 완료 경품 예약 |
| POST | /festivals/{festivalId}/stamp-tour/reward-claims/{claimId}/passes | 필요 | 수령 확인용 단기 pass 발급 |

회전 QR 표시 단말의 비관리자 service-credential 경로도 적용 OpenAPI에 포함한다.

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| GET | /stamp-displays/{displaySessionId}/qr-token | `Authorization: StampDisplay {credential}` | 현재 rotation interval의 표시용 QR token |

이 표의 session은 계정·로그인이 아니라 축제 회차에만 유효한 익명 참여 token이다. session이
필요하지 않은 GET과 기존 공개 API에 cookie나 로그인 redirect를 요구하지 않는다.
progress GET과 progress를 함께 반환하는 consent·check-in POST는 `lang` query를 허용하고
5절대로 선택한 하나의 content language로 configuration, acceptedPrivacyNotice,
currentPrivacyNotice를 모두 투영한다. 5.2의 번역 완전성·fallback 판정도 세 projection 전체를
한 단위로 계산해 한 응답 안에 언어를 섞지 않는다.
두 POST의 idempotency canonical digest에는 raw header가 아니라 lang, Accept-Language,
festival default 순으로 5.1이 정규화한 `requestedLanguage`를 포함하며 JSON meta.language와
Content-Language를 반환한다. 다른 stamp 상태 변경에는 lang query를 허용하지 않는다.
`GET /festivals/{festivalId}/stamp-tour` 성공은 200 data로 정확한
`StampTourConfiguration`을 반환하고 4.3의 festivalTimeZone, contentRevision,
snapshotUpdatedAt, language meta와 Content-Language를 포함한다. Cache-Control은
`public, max-age=15, stale-while-revalidate=30`이며 실제 max-age는 tour, enabled checkpoint,
활성 PAUSED override의 expiresAt과 enabled LIMITED_IN_PERSON reward의
claimStartsAt·claimEndsAt 중 다음 상태 경계까지 남은 초를
넘지 않고 경계 뒤 stale 응답을 제공하지 않는다. 적용 release는
이 endpoint를 22절의 시간 파생 목록에 추가한다. ETag의 temporalStateVersion과
Last-Modified는 tour·checkpoint·PAUSED 자동 해제·claim window의 마지막 지난 경계를 포함하고, 현재 경계 version이 다르면
같은 contentRevision·stampSnapshotId여도 304를 반환하지 않는다.
ETag와 cache key에는 `stampSnapshotId`와 issuance 집합의 monotonic
`stampOperationalVersion`도 포함하고, Last-Modified는 마지막 issuance 생성·revoke·supersede·
상태 전이와 checkpoint operational override 적용·해제 시각 중 가장 늦은 값까지 사용한다. 현재 public checkpoint에 유효한 issuance 집합이
바뀌는 transaction은 stampOperationalVersion을 증가시키고 `/stamp-tour`, 관련 QR 표시,
CLIENT_MANIFEST cache를 즉시 purge한다. purge 실패 중에도 새 version validator가 과거 304를
막는다.

StampTourConfiguration의 필수 필드는 `id`, `stampSnapshotId`, `participationSnapshotId`,
`configurationRevision`, nullable
`rewardConfigurationRevision`, `title`, nullable `description`, `rules`,
`state: UPCOMING|ACTIVE|ENDED`, `startsAt`, `endsAt`, `requiredStampCount`, `checkpointCount`,
`verificationMode=VISITOR_SCANS_CHECKPOINT`, `qrPolicy: STATIC_SIGNED|ROTATING_SIGNED`,
nullable `rotationSeconds`, `checkpoints: StampCheckpointSummary[]`,
`reward: StampTourRewardSummary`, `privacyNotice: PrivacyNotice`, `updatedAt`이다. state는
Asia/Seoul serverTime으로 계산한다.
id는 UUID가 아니라 회차 안의 singleton 식별자 literal `stamp-tour`이며 관리자
STAMP_TOUR_CONFIGURATION resourceId와 같다. 모든 signed QR의 stampTourId도 이 값이어야 한다.
qrPolicy=ROTATING_SIGNED이면 rotationSeconds는 30~300의 integer로 non-null이고,
STATIC_SIGNED이면 null이다.
stampSnapshotId는 tour·reward·enabled checkpoint의 정확한 published revision,
participationSnapshotId, stampOperationalVersion이 식별한 QR issuance 집합과 privacy
contentHash를 canonicalize한
opaque digest이며 stamp aggregate가 바뀌면
달라진다. 같은 public head의 공지처럼 무관한 resource 변경은 포함하지 않는다.
admin-only issuanceVersion·reservation 변화는 effective public set·상태가 그대로면
stampOperationalVersion이나 stampSnapshotId를 바꾸지 않는다.
participationSnapshotId는 29.7의 참여 규칙과 checkpoint 위치·표시 맥락 hash이며 privacy
고지만 분리된다.
rewardConfigurationRevision은 reward.mode=NONE이면 null이고 LIMITED_IN_PERSON이면 projection에
사용한 published reward revision이다.
checkpoints에는 enabled=true로 게시된 checkpoint만 포함하고 checkpointCount는 배열 길이와
정확히 같으며 1~100이다. requiredStampCount는 1 이상 checkpointCount 이하이고 disabled
draft는 count·배열·완료 계산에 포함하지 않는다.

StampCheckpointSummary는 필수 `id`, `title`, nullable `description`, `startsAt`, `endsAt`,
`state: UPCOMING|ACTIVE|PAUSED|CLOSED|ENDED`, nullable `statusMessage`, nullable `mapMarkerId`,
nullable `statusExpiresAt`, nullable `spaceId`, `locationText`, `order`, nullable
`operationalUpdatedAt`을 가진다. state는 활성 operational override가
있으면 PAUSED|CLOSED를 우선하고, 그 밖에는 서버의 Asia/Seoul serverTime과 enabled인 게시 checkpoint의
`[startsAt, endsAt)`에서 계산한다. mapMarkerId는
14.6의 publicly addressable marker만 사용할 수 있다. 연결된 space·marker와 별도로 학교
약칭이나 추정 번역을 만들지 않는다. 배열은 order, id 순이다.
statusMessage는 PAUSED|CLOSED에서 선택 content language의 override 문구이고 다른 state에서는
null이다. statusExpiresAt은 PAUSED의 자동 해제 시각이고 다른 state에서는 null이다.
operationalUpdatedAt은 마지막 override 적용·해제 시각이며 한 번도 없으면 null이다.
mapMarkerId와 spaceId가 함께 non-null이면 marker.linkedSpaceId와 space.id,
FestivalSpace.mapMarkerId가 모두 같은 연결이어야 한다. spaceId만 저장한 checkpoint는 공개
projection에서 그 space의 nullable mapMarkerId를 파생하며 서로 다른 두 장소를 동시에
노출하지 않는다.

StampTourRewardSummary는 필수 `mode: NONE|LIMITED_IN_PERSON`을 가진 tagged union이다. NONE은
추가 필드가 없다. LIMITED_IN_PERSON은 `title`, nullable `description`, `claimStartsAt`,
`claimGuide`, `claimEndsAt`, `claimPassValiditySeconds`, `reclaimPolicy:
NEVER|WHILE_CLAIM_WINDOW_OPEN`, `maxClaimAttemptsPerSession`,
`claimState: UPCOMING|OPEN|PAUSED|ENDED`를 추가한다. claimState는 enabled=false면 PAUSED,
그 밖에 serverTime이 claimStartsAt 전이면 UPCOMING, `[claimStartsAt, claimEndsAt)`이면 OPEN,
이후면 ENDED다. 재고 수량과 내부
수령 코드는 공개 설정에 노출하지 않는다. 룰렛·추첨·확률형 경품은 이 계약에 포함하지
않는다.

PrivacyNotice는 선택된 content language의 필수 `summary`, `processingPurpose`, `policyUrl`,
`policyVersion`, `participantRetentionEndsAt`을 가진다. policyUrl은 승인된 HTTPS URL이다. 설정·체크포인트·
경품 문구는 모두 5절의 ko·en·zh 번역과 fallback·게시 검증을 적용한다.

### 29.3 익명 참여 session

bootstrap body는 빈 닫힌 object다. 성공은 200 data로 필수 `bootstrapExpiresAt`,
`csrfToken`을 반환하고 256 bit 무작위 secret을
`__Host-festival-stamp-bootstrap` Secure, HttpOnly, SameSite=Lax, Path=/ cookie로 설정한다.
bootstrap은 30분 뒤 만료하며 저장소에는 keyed digest만 둔다. session 생성 body는 필수
`acceptedPolicyVersion`, `expectedStampSnapshotId`만 가진 닫힌 object이고 bootstrap cookie, 같은 origin의 정확한
Origin, `X-Stamp-CSRF`와 `Idempotency-Key`를 요구한다. 누락·형식 오류는 아래 공통 stamp
멱등 규칙을 따른다. 현재 게시된 privacyNotice.policyVersion과 다르면 409
STAMP_POLICY_VERSION_MISMATCH다. 성공은 201과 다음 형태를 반환한다.
bootstrap은 선택적 `X-Anonymous-Rate-Token` request header를 받고 성공 응답에서 23절과 같은
header를 발급·갱신한다. token은 festivalId·무작위 anonymousBucketId·30분 이하 expiry만
서명하며 session 생성 요청도 이를 다시 보낸다. 첫 bootstrap처럼 token이 없거나 무효한
요청은 IP soft threshold와 endpoint·edge capacity bucket으로 판단하되 공유 IP만으로 hard
거부하지 않고, 성공 뒤부터 발급된 token bucket을 주 키로 쓴다. cacheable GET 응답에는
방문자별 token을 싣지 않는다.
bootstrap csrfToken도 원문 대신 keyed digest로 저장하고 bootstrapId, festivalId, 요청
origin과 bootstrapExpiresAt에 묶는다. session 생성은 constant-time 비교 후 bootstrap과 CSRF를
같은 transaction에서 소비한다. cookie·Origin·token이 없거나 다르거나 만료됐으면 존재
여부를 구분하지 않고 403 STAMP_CSRF_INVALID다. 소비 표시는 bootstrap 만료까지 digest와
최초 idempotency key를 보존해 그 정확한 재시도만 아래 replay lookup에서 먼저 허용한다.

    {
      "data": {
        "session": {
          "participantRef": "opaque-random-reference",
          "acceptedStampSnapshotId": "stamp-snapshot-opaque-digest",
          "participationSnapshotId": "participation-semantics-opaque-digest",
          "configurationRevision": 7,
          "rewardConfigurationRevision": 3,
          "createdAt": "2030-09-18T10:00:00+09:00",
          "expiresAt": "2030-10-18T00:00:00+09:00",
          "acceptedPolicyVersion": "policy-3"
        },
        "csrfToken": "one-time-readable-random-token",
        "csrfExpiresAt": "2030-09-18T10:30:00+09:00"
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T10:00:00+09:00",
        "festivalTimeZone": "Asia/Seoul"
      }
    }

성공 data는 필수 `session: StampTourSession`, `csrfToken`, `csrfExpiresAt`만 가진 닫힌
schema다. StampTourSession은 필수 `participantRef`, `acceptedStampSnapshotId`,
`participationSnapshotId`, `configurationRevision`, nullable `rewardConfigurationRevision`,
`createdAt`, `expiresAt`, `acceptedPolicyVersion`만 가진다. retained reward mode가 NONE이면
rewardConfigurationRevision은 null이고 LIMITED_IN_PERSON이면 session 생성 때 고정한 양의
revision이다. csrfToken은 이 성공·동일 멱등 재생에서만 원문을 반환한다.

expectedStampSnapshotId는 방문자가 직전에 확인한 GET stampSnapshotId와 정확히 같아야 하며
session commit에서 전체 current public stamp snapshot을 CAS한다. 그 사이 tour·reward·checkpoint
중 하나라도 게시됐으면
session·bootstrap을 소비하지 않고 412 STAMP_SNAPSHOT_MISMATCH를 반환해 최신 규칙을 다시 보여 준다.
commit serverTime에 CLOSED가 아니고 endsAt이 미래인 enabled checkpoint 수가
requiredStampCount보다 적으면 session·bootstrap을 소비하지 않고 409
STAMP_COMPLETION_NOT_FEASIBLE을 반환한다. PAUSED는 statusExpiresAt이 미래이고
checkpoint.endsAt보다 최소 15분 앞서 있어 재개 시간이 보장되는 경우에만 이 수에 포함하며,
응답 안내에서 현재 일시중지를 표시한다.
성공 session은 요청 값을 acceptedStampSnapshotId로 감사 고정하고 별도로 현재
participationSnapshotId를 고정한다. 뒤의 policy-only 게시·재동의는 두 값을 다시 쓰지 않는다.

서버는 256 bit 이상의 무작위 session secret을
`__Host-festival-stamp` Secure, HttpOnly, SameSite=Lax, Path=/ cookie로 설정하고 저장소에는
secret의 keyed digest만 둔다. participantRef도 무작위 opaque 값이며 계정, 학번, 이름,
전화번호, email, 위치, 광고 ID나 기기 fingerprint와 연결하지 않는다. session은 festivalId에
귀속되고 다른 회차에 사용할 수 없다. session.expiresAt과 cookie Expires·Max-Age는 해당
동의 시점에 게시된 participantRetentionEndsAt이며 서버는 세 값을 같은 절대 시각으로
고정한다. 뒤의 정책 게시가 보관 기한을 늘려도 재동의 없이 기존 session의 기한을 늘리지
않는다.

Idempotency-Key를 요구하는 모든 공개 stamp 변경 요청에서 key는 대소문자를 구분하는 16~128자의
`[A-Za-z0-9._:-]` 문자열이다. 누락은 400 IDEMPOTENCY_KEY_REQUIRED, 형식 오류는 422
VALIDATION_ERROR이며 side effect와 key reservation을 만들지 않는다. session 생성의 scope는 `(festivalId, canonical path,
bootstrapId, key)`이고 bootstrap cookie를 가진 호출자에게만 같은 session과 Set-Cookie를
encrypted replay record에서 반환한다. 한 bootstrap은 하나의 session만 만들 수 있으며 성공
뒤 다른 key·body는 409 STAMP_BOOTSTRAP_USED다. replay record와 재생 가능한 secret은
bootstrap 만료 시각까지인 최대 30분 뒤 폐기한다. 경쟁 요청은 bootstrap과 idempotency
record를 transaction으로 잠가 하나만 새 session을 만든다.

session 생성 외 Idempotency-Key 요청의 scope는 `(stampSessionId, festivalId, method,
canonical path, key)`다. canonical digest는 정규화 query·body와 Content-Type을 포함하고
cookie, Origin, X-Stamp-CSRF 같은 secret/검증 header는 제외한다. 서버는 유효 session과
same-origin/CSRF를 먼저 검증한 뒤 key와 digest를 조회한다. consent·check-in·reward-claim 생성의
완료 mutation record는 현재 capability·tour·policy gate보다 먼저 replay하고, pass endpoint는
아래의 feature·claim·token 만료/소비 규칙을 우선한다. 같은 key의 다른 digest는 409
IDEMPOTENCY_KEY_REUSED다. 동시 동일 요청은 하나만 실행하고 follower는 최대 10초 기다린 뒤
같은 응답 또는 409 IDEMPOTENCY_REQUEST_IN_PROGRESS를 받는다. check-in·consent·reward claim
record는 session retention 또는 조기 DELETE 중 먼저 오는 때까지 유지한다. pass replay의
encrypted token은 pass expiry까지만 유지하고, 그 뒤 `(session/claim scope, key, requestDigest,
outcome=EXPIRED)`만 가진 pass tombstone은 session·claim retention 또는 조기 DELETE 중 먼저
오는 때까지 보존해 같은 옛 key가 새 pass를 만들지 않고 410 STAMP_PASS_EXPIRED를 재생하게
한다. token·cookie·CSRF 원문은 일반 record나 로그에 두지 않되 pass replay에 필요한 token은
만료까지만 암호화한다.

progress를 포함하는 consent·check-in의 완료 record는 mutation 결과와 안정적 resource ID를
고정하되 `progress`와 meta.serverTime·language를 응답 byte로 저장하지 않는다. 같은 key·digest
재시도는 mutation을 다시 실행하지 않고 최초 consent/checkIn 결과를 재생한 뒤 현재 session,
현재 privacy policy와 live-state의 consistent-read로 progress를 다시 투영한다. 따라서 최초
성공 뒤 privacy policy나 현장 상태가 바뀌어도 replay의 currentPolicyVersion,
currentPrivacyNotice, consentRequired와 availability가 과거 값으로 되돌아가지 않는다.
reward-claim 생성 record도 claimId·generation과 최초 reservation 성공만 고정한다. 동일
key·digest replay의 data.claim은 그 claim의 현재 effective status·expiresAt·fulfilledAt으로
다시 투영해 이미 만료·취소·수령된 claim을 ISSUED로 되돌려 보여 주지 않는다.

session/csrf는 request body가 없는 endpoint이며 유효한 session cookie와 same-origin Origin을 요구하고 200 data로 필수
`csrfToken`, `expiresAt`을 반환한다. token은 session·festival·origin에 묶인 256 bit 값이고
최대 30분 또는 session.expiresAt 중 먼저 오는 때 만료한다. bootstrap CSRF와 달리 session
CSRF는 변경 요청마다 소비하지 않고 자체 expiresAt까지 재사용할 수 있다. csrf 재발급이나
consent 성공이 기존의 아직 유효한 session CSRF를 폐기하지 않으며, 각 token digest와 expiry를
session expiry 또는 조기 DELETE까지의 범위에서 보관한다. 따라서 응답 유실 뒤 같은
Idempotency-Key 재시도는 원래 token으로도 CSRF 검증을 통과한 다음 replay된다. session/consent body는 필수
`acceptedPolicyVersion`만 가진 닫힌 object이고 Idempotency-Key와 X-Stamp-CSRF를 요구한다.
현재 게시 version과 일치하면 session의 acceptedPolicyVersion과 consentAt을 원자 갱신하고
200 data로 필수 `progress: StampTourProgress`, `csrfToken`, `csrfExpiresAt`을 반환한다.
csrfExpiresAt은 앞의 CSRF 수명 규칙으로 계산한다. 보관 기한 연장은 이 명시적
재동의 성공 뒤에만 적용할 수 있다. consent transaction은 DB session.expiresAt도 현재
participantRetentionEndsAt으로 바꾸고 응답 progress.sessionExpiresAt 및 cookie
Expires·Max-Age를 같은 절대 시각으로 설정한다. 새 정책이 기한을 단축하면 재동의 여부와
무관하게 유효 expiry를 `min(기존 session.expiresAt, 새 deadline)`으로 즉시 제한하고 다음
session 응답에서 cookie도 단축한다. deadline worker는 더 긴 client cookie가 남아 있어도 그
시각부터 요청을 거부하고 삭제한다. check-in, session-pass, reward claim·pass와 DELETE는
cookie 외에 session 생성·consent 응답 또는 session/csrf에서 받은 `X-Stamp-CSRF`와 정확한
same-origin Origin을 요구한다. CSRF token 원문은 로그에 남기지 않는다.
consent의 동일 Idempotency-Key 재시도는 위 규칙으로 최초 mutation 결과와 현재 progress를
결합하고, CSRF 비밀은 현재 session에 묶인 새 token과 expiry를 발급해
`Idempotency-Replayed: true`를 반환한다. 최초 응답의 header 전달이 끊겨도 DB와 browser
expiry가 갈라지지 않도록 현재 session.expiresAt을 Expires·Max-Age로 사용한 동일
`__Host-festival-stamp` Set-Cookie도 매 replay에서 다시 발행한다. 연장·단축 모두 이 규칙을
쓴다. 기존 token 원문이나 새 CSRF 원문을 business idempotency record에 장기 보관하지
않고 digest는 위 수명까지만 유지한다.

새 session과 check-in은 StampTourConfiguration.state=ACTIVE일 때만 허용하고 아니면 409
STAMP_TOUR_NOT_ACTIVE다. session-bootstrap과 session 생성은 path festivalId가
`/festivals/current`의 active pointer와 일치할 때만 허용한다. 이미 같은 festival의 유효
session cookie가 있으면 새 bootstrap/session으로 덮지 않고 409
STAMP_SESSION_ALREADY_EXISTS를 반환해 progress로 보낸다. cookie가 다른 비활성 회차의
유효 session을 가리키면 cookie를 덮거나 지우지 않고 409 STAMP_SESSION_OTHER_FESTIVAL을
반환한다. Problem Details extension은 안전한 `existingFestivalId`, `existingSessionExpiresAt`
만 제공하며 client는 그 회차의 retained progress·claim·DELETE로 안내한다. 사용자가 기존
session을 명시적으로 DELETE하거나 만료한 뒤에만 현재 회차 bootstrap을 발급한다. 하나의
fixed `__Host-festival-stamp` cookie로 두 회차에 동시 참여할 수 있다고 가장하지 않는다.
progress·consent·csrf·DELETE는 session.expiresAt
전까지 회차 상태와 무관하게 허용한다. 공개 설정 GET만 22절의 번역·contentRevision·시간 경계 validator를
사용해 public cache할 수 있다. bootstrap과 session 관련 GET·POST·DELETE, check-in, pass,
claim 응답은 모두 `Cache-Control: private, no-store`이며 CDN/shared cache를 우회한다. cookie를
shared-cache key로 사용하거나 참여자 응답을 저장하지 않는다. progress GET은 22절의 공개
GET ETag·Last-Modified·304 규칙에서 명시적으로 제외하고 항상 현재 session state를 200으로
반환한다.

GET progress의 StampTourProgress는 필수 `participantRef`,
`state: IN_PROGRESS|COMPLETED|REWARD_RESERVED|REWARD_FULFILLED`, `requiredStampCount`,
`completedStampCount`, `checkIns: StampCheckIn[]`, `eligibleForReward`, nullable
`rewardClaim: StampRewardClaimSummary`, `acceptedPolicyVersion`, `currentPolicyVersion`,
`consentRequired`, `acceptedStampSnapshotId`, `participationSnapshotId`,
`configurationRevision`, nullable `rewardConfigurationRevision`,
`checkInAvailability: ACTIVE|DRAINING|ENDED`,
`rewardClaimAvailability: NOT_APPLICABLE|UPCOMING|OPEN|PAUSED|ENDED`,
`completionFeasible`, `remainingAvailableCheckpointCount`,
`configuration: StampTourSessionConfiguration`, `acceptedPrivacyNotice: PrivacyNotice`,
`currentPrivacyNotice: PrivacyNotice`, `currentPolicyConfigurationRevision`,
`currentPolicyUpdatedAt`, `sessionExpiresAt`, `updatedAt`을 가진다.
configurationRevision은 session 생성 때 고정한 participation semantics revision이다.
StampTourSessionConfiguration은 필수 `id`, `participationSnapshotId`,
`configurationRevision`, nullable `rewardConfigurationRevision`, `title`, nullable
`description`, `rules`, `state: UPCOMING|ACTIVE|ENDED`, `startsAt`, `endsAt`,
`requiredStampCount`, `checkpointCount`, `verificationMode=VISITOR_SCANS_CHECKPOINT`,
`qrPolicy: STATIC_SIGNED|ROTATING_SIGNED`, nullable `rotationSeconds`,
`checkpoints: StampCheckpointSummary[]`, `reward: StampTourRewardSummary`, `updatedAt`을
가진 닫힌 schema다. retained revision의 규칙·checkpoint label/order·reward 안내를 반환하되
public StampTourConfiguration의 stampSnapshotId와 privacyNotice는 넣지 않는다.
id는 항상 literal `stamp-tour`다.
state는 retained startsAt·endsAt과 Asia/Seoul serverTime으로 계산한다. qrPolicy와
rotationSeconds의 variant 규칙은 29.2와 같고, ROTATING_SIGNED에서만 rotationSeconds가
non-null이다.
각 retained checkpoint의 ID·label·order·location은 고정하되 state, statusMessage,
statusExpiresAt과 operationalUpdatedAt에는 현재 live-state overlay를 합성해 차단 이유와
재개 예정 시각을 즉시 설명한다.
progress.participationSnapshotId, configurationRevision, rewardConfigurationRevision,
requiredStampCount는 각각 progress.configuration의 같은 이름 필드와 정확히 같다.
completedStampCount는 checkIns.length와 같고 configuration.checkpointCount 이하이며, 각
checkIn.checkpointId는 configuration.checkpoints에 정확히 하나 존재하고 checkIns 안에서
중복되지 않는다. rewardClaim이 non-null이면 그 rewardConfigurationRevision은 progress와
configuration의 같은 non-null 값과 정확히 같다. 서버와 client 모두 중복 필드가 다르면
한쪽을 추정해 복구하지 않고 계약 위반으로 처리한다.
acceptedPrivacyNotice는 사용자가 마지막으로 동의한 policyVersion·contentHash의 불변 고지를
현재 응답에서 선택된 하나의 content language로 투영한 값이고 currentPrivacyNotice는
재동의할 최신 게시 고지의 같은 언어 projection이라 둘이 다를 수 있다. 현재 응답 언어가
마지막 동의 당시 UI 언어와 달라도 정책 identity는 policyVersion·contentHash로 유지하며 동의를
다시 만든 것으로 보지 않는다. currentPolicyConfigurationRevision과
currentPolicyUpdatedAt은 후자의 출처를 고정한다. capability=false drain 중에도 이 세
projection을 session 소유자에게만 계속 제공한다.

progress와 이를 포함하는 consent·check-in 응답은 여러 revision의 private 합성 projection이므로
4.3의 `meta.contentRevision`과 `meta.snapshotUpdatedAt`을 넣지 않는 명시적 예외다. meta는
requestId, serverTime, festivalTimeZone과 번역 시 language만 가지며 body의
acceptedStampSnapshotId·participationSnapshotId·configurationRevision·
rewardConfigurationRevision·currentPolicyConfigurationRevision이 각각의 권위 있는 출처다.
sessionExpiresAt은 저장된 동의
기한과 현재 게시 participantRetentionEndsAt 중 빠른 시각이다. state는 완료 stamp 수와 최신 claim 상태에서 서버가
계산하며 client가 보내지 않는다. checkInAvailability는 capability가 true이고 path가 active
festival이며 tour window 안이면 ACTIVE, retained session은 유효하지만 운영 중 capability나
active pointer가 꺼졌으면 DRAINING, tour 종료 뒤에는 ENDED다. reward mode=NONE이면
rewardClaimAvailability=NOT_APPLICABLE, eligibleForReward=false, rewardClaim=null이다.
LIMITED_IN_PERSON일 때 rewardClaimAvailability는 capability=true·active festival·retained
reward enabled를 먼저 요구하고, 그 조건이 꺼졌으면 PAUSED, 그 밖에는 claim window에 따라
UPCOMING|OPEN|ENDED다. tour가 끝났더라도 별도
claimEndsAt 전이면 OPEN일 수 있다. completedStampCount <
requiredStampCount이면 IN_PROGRESS,
기준을 충족하고 최신 claim이 없거나 EXPIRED·CANCELED이면 COMPLETED, ISSUED이면
REWARD_RESERVED, FULFILLED이면 REWARD_FULFILLED다. rewardMode=NONE이어도 기준 충족 상태는
COMPLETED다. LIMITED_IN_PERSON variant에서만 nested reward.claimState는
rewardClaimAvailability와 같다. NONE variant에는 claimState를 추가하지 않는다.
eligibleForReward는 rewardClaimAvailability=OPEN, LIMITED_IN_PERSON, 완료 기준 충족,
FULFILLED·ISSUED claim 없음, reclaim policy와 시도 한도가 새 claim을 허용할 때만 true이며 claim 창과 현재 재고는
별도로 29.5에서 검사한다. checkIns는 checkpoint.order,
checkedInAt, checkIn.id 순이며 다른 참여자의 데이터나 전체 순위를 제공하지 않는다. session cookie가
없거나 digest가 없거나 삭제·회수됐으면 401 STAMP_SESSION_REQUIRED다. 단, retention worker가
남긴 expiry tombstone의 keyed cookie digest와 일치하면 participant 존재 여부 없이 cookie를
즉시 만료시키고 410 STAMP_SESSION_EXPIRED다. live digest가 확인되지만 expiresAt을 지났을
때도 같은 410과 cookie 만료를 반환한다. 사용자가 cookie를
지우거나 DELETE를 호출하면 복구하지 못한다는 점을 시작 화면에 알린다. 계정 기반 복구를
임의로 추가하지 않는다. `acceptedPrivacyNotice.policyVersion`은
acceptedPolicyVersion과, `currentPrivacyNotice.policyVersion`은 currentPolicyVersion과
정확히 같아야 한다. consentRequired는 두 상위 version이 다를 때에만 true이고 같을 때에는
반드시 false다. consentRequired=true이면 check-in, session-pass, manual-check-in과 새 reward
claim을 409 STAMP_POLICY_VERSION_MISMATCH로 막는다. progress·csrf·consent·DELETE와 이미
ISSUED인 claim의 claim-pass·관리자 fulfillment·cancel은 새 참여 처리나 개인정보 수집이
아니므로 허용해 기존 예약을 고립시키지 않는다.
remainingAvailableCheckpointCount는 아직 적립하지 않았고 CLOSED가 아니며 endsAt이 미래인
retained enabled checkpoint 수다. PAUSED checkpoint는 statusExpiresAt이 미래이고
endsAt보다 최소 15분 앞선 경우에만 포함한다. completionFeasible은
`completedStampCount + remainingAvailableCheckpointCount >= requiredStampCount`다. false이면
checkInAvailability는 tour 시간이 남아도 ENDED로 투영하고 UI는 신규 스캔 CTA 대신 완료
불가 안내를 표시한다. 이미 완료한 session의 completionFeasible은 true다.

DELETE는 활성 claim이 없으면 참여·check-in을 즉시 지우고 204를 반환한다. ISSUED claim이
있으면 먼저 claim을 취소해 재고를 반환한 뒤 같은 transaction에서 삭제한다. FULFILLED
claim의 최소 재고 감사 record는 participantRef와 분리해 보존하고 session·check-in은 지운다.
DELETE transaction은 그 session의 다른 idempotency·pass replay payload를 모두 삭제하거나
participant data가 없는 상태로 redact하고, cookie secret의 keyed digest·DELETE key·request
digest·완료 시각만 가진 deletion tombstone을 `min(24시간, 기존 session expiry까지)` 보존한다.
DELETE는 cookie keyed digest를 constant-time으로 live session, deletion tombstone,
unknown/absent 중 하나로 분류한다. live session일 때만 정확한 same-origin Origin,
X-Stamp-CSRF와 Idempotency-Key를 모두 요구하고 실제 삭제 transaction을 실행한다. tombstone과
cookie·key·request digest가 모두 같으면 204를 멱등 재생한다. tombstone인데 key가 없거나
다르거나, cookie가 unknown/absent이면 participant 상태를 만들거나 바꾸지 않고 enumeration
방지를 위해 세 header 없이도 204를 반환한다. 이 경우를 성공한 live 삭제로 기록하거나 새
idempotency record를 만들지 않는다. 모든 204 응답은 `__Host-festival-stamp`를 Max-Age=0과
과거 Expires로 만료시킨다. 따라서 CSRF 없는 제3자는 live 참여를 지울 수 없고, 이미
삭제됐거나 존재하지 않는 참여 여부도 응답으로 구분할 수 없다.

### 29.4 QR과 check-in

공개 check-in body는 필수 `qrToken`만 가진다. Idempotency-Key와 X-Stamp-CSRF가 필수이고
성공은 200 data로 `checkIn: StampCheckIn`, `duplicate: boolean`, `progress:
StampTourProgress`를 반환한다. StampCheckIn은 필수 `id`, `checkpointId`, `checkedInAt`을
가진다.

서버는 qrToken의 서명, festivalId, stampTourId, checkpointId, issuanceId, 128 bit 이상의
무작위 nonce, not-before, expiry와 revocation을 검증한다. client 시각이나 QR 안의 표시
문구를 신뢰하지 않는다. stampTourId는 path 회차의 literal `stamp-tour`와 정확히 같아야 한다.
session이 유효하고 tour와 checkpoint가 ACTIVE인 경우에만 서버 시각으로 새 check-in을
기록한다. 현재 active festival의 capability.stampTour=true도 같은 transaction에서 확인한다.
동일 key·digest의 완료 replay가 아니면서 capability가 꺼졌으면 기존 unique duplicate 여부와
무관하게 404 FEATURE_NOT_ENABLED, active festival이나 tour window가 아니면 기존 unique
duplicate 여부와 무관하게 STAMP_TOUR_NOT_ACTIVE다. checkpoint 운영
기간·enabled 조건이 아니면 신규 적립에 STAMP_CHECKPOINT_NOT_ACTIVE다. `(stampSessionId, checkpointId)`
unique constraint와 한
transaction의 progress 갱신으로 중복 적립을 막는다. 같은 체크포인트 재요청은 key가 달라도
기존 checkIn과 duplicate=true를 반환하며 새 count를 만들지 않는다.
검증 순서는 session·Origin·CSRF, 동일 key·digest 완료 replay, replay가 아니면 현재 active
festival·capability·tour window와
session.acceptedPolicyVersion=current privacyNotice.policyVersion, QR의
형식·서명·festival/checkpoint identity, 해당 session의 기존 unique checkIn 조회, 그 뒤 신규
요청에만 issuance 시간·폐기·현재 public membership과 checkpoint live-state다. 기존 checkIn이 있으면 QR이 그 뒤 만료·교체되거나 checkpoint가
PAUSED|CLOSED|ENDED여도 200 existing checkIn·duplicate=true를 반환한다. 이는 새 적립을 만들지
않으며 서명 자체가 위조됐거나 다른 checkpoint인 token은 duplicate lookup 전에 거부한다.
기존 checkIn이 없는 요청은 현재 live-state overlay로 29.3의 completionFeasible을 같은
transaction에서 다시 계산한다. false이면 새 적립을 만들지 않고 409
STAMP_COMPLETION_NOT_FEASIBLE을 반환하므로 progress의 checkInAvailability=ENDED와 실제 write
허용 상태가 일치한다.

이 중복 방지는 익명 session 단위 보장이다. cookie 삭제, 다른 브라우저나 기기에서 새
session을 만든 같은 사람을 식별하거나 합치지 않으며, v1은 사람 단위 중복 방지를 달성했다고
표시하지 않는다. LIMITED_IN_PERSON 경품은 총학생회가 이 잔여 위험과 현장 수령 통제를
명시적으로 수용한 ApprovalReference 또는 별도 승인된 최소 식별·일회성 배포 계약이 없으면
게시 검증에서 거부한다. 후자의 계약이 개인정보를 추가하면 29.1의 개인정보·미성년자 gate를
다시 거치며 이 문서의 익명 variant에 암묵적으로 넣지 않는다.

STATIC_SIGNED는 승인된 운영 기간까지 유효한 issuance를 사용하므로 QR 사진 공유를 기술적으로
완전히 막을 수 없다. 이 mode는 그 위험과 현장 대응을 명시한 approvalReference가 있을 때만
게시한다. ROTATING_SIGNED는 rotationSeconds 30~300의 짧은 token을 현장 화면에서 제공하고
만료·이전 issuance를 거부한다. 어느 mode도 GPS, 배경 위치, 카메라 이미지나 주변 기기
정보를 수집하지 않는다. offline 적립은 지원하지 않으며 network 실패는 서버에 기록되지
않은 것으로 표시하고 같은 Idempotency-Key로 재시도한다.

카메라 사용이 어려운 방문자는 session-pass를 현장 담당자에게 제시한다. session-pass는
festivalId, participantRef의 keyed reference, 128 bit nonce와 2분 이하 expiry를 가진 서명된
일회용 token이다. `POST .../session-passes`는 body 없는 요청이며 Idempotency-Key와 X-Stamp-CSRF를 요구하고
현재 active festival의 capability.stampTour=true이고 tour가 ACTIVE일 때만 200 data로
`pass: StampOneTimePass`를 반환한다. StampOneTimePass는 필수 `token`, `expiresAt`
만 가진다. 발급 transaction은 29.3의 completionFeasible도 다시 계산하고 false이면 pass를
만들지 않은 채 409 STAMP_COMPLETION_NOT_FEASIBLE을 반환한다. 같은 key 재시도에서 token이
만료 전이고 아직 소비되지 않았을 때만 암호화 replay record의 같은 token을 반환한다. 이미
소비됐거나 만료됐으면 같은 key에도 410 STAMP_PASS_EXPIRED이며 session-pass가 더 필요하면
현재 feature·policy·completion gate를 통과한 새 key로 다시 발급한다. 담당자는 29.6의
manual-check-in을 사용하고 사유를 남긴다. token은 첫 성공 또는 만료에서 원자 소비하며
경쟁 사용은 하나만 성공한다. 모든 pass 응답은 no-store이고 QR 문자열이나 pass 원문은
로그·감사·분석에 저장하지 않는다.

### 29.5 경품 계약

StampTourConfigurationWrite.rewardMode가 public reward.mode의 유일한 source다. NONE이면
reward-claims와 claim-pass path는 404 FEATURE_NOT_ENABLED이고 reward configuration을 게시할
수 없다. LIMITED_IN_PERSON이면 published StampRewardConfiguration이 필수다.
StampRewardConfigurationWrite는 필수 `translations`, `inventoryTotal`, `claimStartsAt`, `claimEndsAt`,
`claimPassValiditySeconds`, `unclaimedReservationMinutes`,
`reclaimPolicy: NEVER|WHILE_CLAIM_WINDOW_OPEN`, `maxClaimAttemptsPerSession`, `enabled`,
`riskApprovalReference: ApprovalReference`를 가진다. riskApprovalReference.referenceType은
RISK_ACCEPTANCE여야 하며 익명 session 단위 중복의 잔여 위험과 현장 수령 통제를 승인한
근거다. 이 reference는 reward working revision과 불변 revision snapshot·게시 manifest에
고정하고 관리자 revision 응답과 감사에는 보존하되 공개 projection에는 노출하지 않는다.
inventoryTotal은 0 이상의 integer, pass validity는 30~300초, 미수령 예약은 5~1440분,
maxClaimAttemptsPerSession은 1~10이다. translations는 5절의 locale map이고 각 locale value는
필수 `title`, nullable `description`, 필수 `claimGuide`만 가진 닫힌 object다. claimStartsAt < claimEndsAt <=
participantRetentionEndsAt이어야 한다. 주류·담배·연령 제한 품목과 현금성 추첨은 별도
법무·미성년자 정책 계약 없이는 허용하지 않는다.

게시 configuration의 inventoryTotal은 공급 정책이고 실시간 수량은 별도
StampRewardInventoryLedger다. ledger는 필수 `rewardConfigurationId`, `inventoryVersion`,
`inventoryTotal`, `reserved`, `fulfilled`, `available`, `active`, `pendingExpiryCount`,
`effectiveExpiryBoundaryVersion`, `effectiveAt`, `updatedAt`을 가지며 available은
`inventoryTotal-reserved-fulfilled`이다. 네 수량과 pendingExpiryCount는 0 이상이고 active는
현재 public LIMITED_IN_PERSON에서 이 ledger를 사용하는지 나타낸다. config draft PUT은 ledger를
바꾸지 않는다. validate는 expectedInventoryVersion을 고정하고 publish·rollback worker는
이를 CAS해 새 inventoryTotal을 반영하되 기존 reserved·fulfilled를 절대 reset하지 않는다.
후보 total이 reserved+fulfilled보다 작거나 version이 바뀌면 전체 게시를 각각 409
STAMP_INVENTORY_CONFLICT 또는 412 REVISION_MISMATCH로 실패시킨다. claim 생성·만료·취소·수령도
같은 ledger version을 잠그고 claim 상태와 한 transaction으로 갱신한다.
rewardConfigurationId는 UUID가 아니라 회차 안의 singleton literal `stamp-reward`이며 관리자
STAMP_REWARD_CONFIGURATION resourceId와 같다. claim·inventory relation과 unique key도 이
값을 사용한다.
effectiveAt은 요청 시각이 아니라 `updatedAt`, effective projection에 포함된 마지막 claim
expiry 경계와 activeProjectionVersion의 마지막 변경 시각 중 최댓값이다. 따라서 위 ETag
구성요소가 같을 때 data.effectiveAt도 안정적이며 현재 진단 시각은 meta.serverTime을 쓴다.

reward claim 생성은 body 없는 요청이며 enabled=true, eligibleForReward=true이고 serverTime이
`[claimStartsAt, claimEndsAt)`인 session에서만 가능하다. 완료 전이면 409
STAMP_REWARD_NOT_ELIGIBLE, path 회차가 active pointer가 아니거나 창 밖·disabled면 409
STAMP_CLAIM_WINDOW_CLOSED, capability=false는 먼저 404 FEATURE_NOT_ENABLED, 재고가
없으면 409 STAMP_REWARD_UNAVAILABLE다. POST는 Idempotency-Key와 X-Stamp-CSRF를 요구하며
성공은 201과 data의 `claim: StampRewardClaimSummary`를 반환한다. 공개 claim item GET은
두지 않으며 이후 상태는 progress에서 조회한다. 서버는 남은
재고를 잠그고 claim 생성·reservation·session 연결을 한 transaction에 적용한다. 같은 key는
같은 claimId의 현재 effective StampRewardClaimSummary를 반환한다. 이미 ISSUED 또는 FULFILLED claim이 있으면 새 reservation을 만들지
않고 409 STAMP_CLAIM_STATE_CONFLICT다. EXPIRED·CANCELED 뒤 재신청도 아래 reclaimPolicy와
generation 한도를 만족하지 않으면 같은 code를 반환한다.

StampRewardClaimSummary의 필수 필드는 `id`, `generation`, `rewardConfigurationRevision`,
`status: ISSUED|FULFILLED|EXPIRED|CANCELED`, `issuedAt`, `expiresAt`, nullable `fulfilledAt`이다.
fulfilledAt은 status=FULFILLED일 때에만 non-null이고 그때 반드시 non-null이며, 나머지 세
상태에서는 반드시 null이다.
expiresAt은 `min(issuedAt + unclaimedReservationMinutes, claimEndsAt,
session.expiresAt)`이다. `(stampSessionId, rewardConfigurationId, generation)`은 unique이고 한
session에는 ISSUED claim이 최대 하나다. FULFILLED 뒤에는 재신청할 수 없다. EXPIRED 또는
CANCELED 뒤에는 reclaimPolicy=WHILE_CLAIM_WINDOW_OPEN이고 현재 창이 열려 있으며 다음
generation이 maxClaimAttemptsPerSession 이하일 때만 새 claim을 만들 수 있다. NEVER이거나
시도 한도에 닿으면 409 STAMP_CLAIM_STATE_CONFLICT다. deadline worker는 expiresAt에서
ISSUED→EXPIRED와 reserved 반환을 원자 적용한다.
worker는 최소 5초마다 due claim을 처리한다. 지연되더라도 pass 발급·fulfill·cancel·새 claim은
transaction 시작 시 해당 ledger의 `status=ISSUED AND expiresAt <= serverTime` claim을 먼저
CAS 만료·반환한 뒤 본 요청을 판단한다. claim GET과 dashboard는 serverTime이 expiresAt 이상인
ISSUED를 유효 예약으로 표시하지 않는 effective projection을 사용하고 reconciliation lag를
운영 지표로 남긴다. 여기서 claim GET은 관리자 claim 목록·상세를 뜻하며 공개 progress의
rewardClaim과 state도 같은 projection을 쓴다. dashboard와 관리자 inventory GET의 effective
reserved는 아직 materialize되지 않은 due ISSUED 수량을 빼고 available에는 더해
`inventoryTotal=reserved+fulfilled+available`을 항상 유지한다. 응답의
`pendingExpiryCount`는 그 지연 건수이고, 다음 write transaction은 실제 ledger를 먼저 같은
결과로 CAS한다. 만료 시각 뒤 pass 발급·수령은 materialized status가 아직 ISSUED여도
410 STAMP_PASS_EXPIRED다.

`POST .../reward-claims/{claimId}/passes`는 body 없는 요청이며 현재 session에 속한 ISSUED claim에
Idempotency-Key와 X-Stamp-CSRF를 요구하고 200 data로
`pass: StampRewardClaimPass`를 반환한다. StampRewardClaimPass는 필수 `claimId`, `token`,
`expiresAt`만 가진다. claimId가 없거나 현재 session 소유가 아니면 존재 여부를 구분하지 않고
404 RESOURCE_NOT_FOUND, effective EXPIRED이거나 serverTime이 expiresAt 이상이면 410
STAMP_PASS_EXPIRED, FULFILLED 또는 CANCELED이면 409 STAMP_CLAIM_STATE_CONFLICT다. 어느
오류에서도 새 pass나 idempotency 성공 record를 만들지 않는다. exact-key·same-digest 재시도는
encrypted replay record가 가리키는 pass nonce의 상태를 먼저 판정한다. 그 pass가 만료됐거나
소비됐으면 claim terminal 상태와 무관하게 410 STAMP_PASS_EXPIRED를 재생하고, 아직 유효하고
미소비인데 claim이 FULFILLED 또는 CANCELED이면 409 STAMP_CLAIM_STATE_CONFLICT를 반환한다.
claim이 여전히 ISSUED이고 pass도 유효·미소비일 때만 최초 token과 expiresAt을 그대로
재생한다. 이 우선순위는 다른 pass로 먼저 수령된 경우와 해당 pass 자체가 소비된 경우를
결정적으로 구분한다. 현장 표시 payload는 이 claimId와 opaque token을 함께 담아 operator가
정확한 `/stamp-reward-claims/{claimId}/fulfill` path를 구성하게 한다. claim pass token은
festivalId, claimId, claimVersion, 무작위 128 bit 이상 nonce와
`min(claimPassValiditySeconds, claim.expiresAt까지 남은 시간)` expiry를 가진 서명 token이다.
session pass와 같은 encrypted replay·no-store·단일 소비 규칙을 적용한다. 현장 fulfillment는
token 형식·서명이나 path festivalId·claimId가 다르면 400 STAMP_PASS_INVALID, token이
만료됐거나 이미 소비됐으면 410 STAMP_PASS_EXPIRED다. 어느 오류에서도 claim이나 inventory를
바꾸지 않는다. claim 상태와 inventory reservation을 CAS해 한 번만 성공한다. 경품 수령을 위해 이름·전화번호를
받지 않으며 그런 정보가 필요해진 경품은 별도 개인정보·동의 계약 전에는 활성화하지 않는다.

### 29.6 관리자 endpoint와 DTO

| Method | Path | 최소 permission | 설명 |
|---|---|---|---|
| GET, PUT | /admin/festivals/{festivalId}/stamp-tour-configuration | VIEWER+STAMP_MANAGER / EDITOR+STAMP_MANAGER | 설정 draft 조회·전체 교체 |
| GET, POST | /admin/festivals/{festivalId}/stamp-checkpoints | VIEWER+STAMP_MANAGER / EDITOR+STAMP_MANAGER | 체크포인트 목록·생성 |
| GET, PATCH, DELETE | /admin/festivals/{festivalId}/stamp-checkpoints/{checkpointId} | VIEWER+STAMP_MANAGER / EDITOR+STAMP_MANAGER | 상세·수정·미게시 draft 삭제 |
| GET, POST | /admin/festivals/{festivalId}/stamp-checkpoints/{checkpointId}/qr-issuances | VIEWER+STAMP_MANAGER / EDITOR+STAMP_MANAGER | issuance 목록·생성 |
| GET | /admin/festivals/{festivalId}/stamp-checkpoints/{checkpointId}/qr-issuances/{issuanceId} | VIEWER+STAMP_MANAGER | issuance 상세 |
| GET, POST | /admin/festivals/{festivalId}/stamp-checkpoints/{checkpointId}/qr-issuances/{issuanceId}/display-sessions | VIEWER+STAMP_MANAGER / EDITOR+STAMP_MANAGER | 회전 QR 전용 단말 session 목록·발급 |
| GET | /admin/festivals/{festivalId}/stamp-checkpoints/{checkpointId}/qr-issuances/{issuanceId}/display-sessions/{displaySessionId} | VIEWER+STAMP_MANAGER | 단말 session 상세·ETag |
| POST | /admin/festivals/{festivalId}/stamp-checkpoints/{checkpointId}/qr-issuances/{issuanceId}/display-sessions/{displaySessionId}/revoke | EDITOR+STAMP_MANAGER | 사유 기반 단말 session 폐기 |
| POST | /admin/festivals/{festivalId}/stamp-checkpoints/{checkpointId}/qr-issuances/{issuanceId}/revoke | EDITOR+STAMP_MANAGER | issuance 폐기 |
| POST | /admin/festivals/{festivalId}/stamp-checkpoints/{checkpointId}/manual-check-ins | EDITOR+STAMP_MANAGER | participant pass 기반 접근성 보조 적립 |
| POST | /admin/festivals/{festivalId}/stamp-checkpoints/{checkpointId}/live-state | PUBLISHER+STAMP_MANAGER | 현장 안전·운영용 즉시 일시중지·종료·해제 |
| GET, PUT | /admin/festivals/{festivalId}/stamp-reward-configuration | VIEWER+STAMP_MANAGER / EDITOR+STAMP_MANAGER | 경품 정책·총량 draft |
| GET | /admin/festivals/{festivalId}/stamp-reward-inventory | VIEWER+STAMP_MANAGER | 보존되는 live 재고 ledger |
| GET | /admin/festivals/{festivalId}/stamp-reward-claims | VIEWER+(STAMP_REWARD_OPERATOR 또는 STAMP_MANAGER) | claim 상태 목록 |
| GET | /admin/festivals/{festivalId}/stamp-reward-claims/{claimId} | VIEWER+(STAMP_REWARD_OPERATOR 또는 STAMP_MANAGER) | 최소 claim 상세·ETag |
| POST | /admin/festivals/{festivalId}/stamp-reward-claims/{claimId}/fulfill | VIEWER+STAMP_REWARD_OPERATOR | 단기 pass 검증·수령 완료 |
| POST | /admin/festivals/{festivalId}/stamp-reward-claims/{claimId}/cancel | EDITOR+STAMP_MANAGER | 사유 기반 취소·재고 반환 |
| GET | /admin/festivals/{festivalId}/stamp-tour-dashboard | VIEWER+STAMP_MANAGER | 익명 집계·재고·실패 현황 |

조건부 적용 시 AdminAccountInviteWrite·AdminAccountPatchWrite와 AdminAccount,
AdminAccountSummary의 닫힌 schema에 `festivalPermissionGrants`를 추가한다. item은
`festivalId`와 중복 없는 `permissions: (STAMP_MANAGER|STAMP_REWARD_OPERATOR)[]`를 가지며
festivalId 순으로 정렬한다. AdminAccountInviteWrite에서는 이 배열이 필수이고 grant가 없으면
빈 배열을 보낸다. AdminAccountPatchWrite에서는 optional whole-array replacement이며 생략하면
기존 값을 유지하고 빈 배열은 모든 named grant를 회수한다. festivalId는 배열에서 중복할 수
없고 각 item의 permissions는 하나 이상이며 같은 permission을 중복할 수 없다. 저장·응답은
festivalId, permission enum 순으로 정규화한다. `GET /admin/auth/me`의 EffectivePermissions에는 별도 필수
`stamp: { festivalId, actions: (STAMP_MANAGER|STAMP_REWARD_OPERATOR)[] }[]`를 추가하고 같은
순서로 반환한다. 저장 grant와 effective 결과는 목록·상세·수정 response에서 왕복 가능해야
하며 기존 FestivalPermission enum이나 role이 자동으로 두 권한을 얻는 것으로 추정하지
않는다. festival ADMIN과 GLOBAL_ACCOUNTS를 모두 가진 관리자만 grant를 부여·회수할 수 있고,
grant 추가·회수는 모두 accountVersion·authzVersion을 증가시키고 18.3의 권한 확대·축소
session 정책을 적용한다. 모든 grant.festivalId는 같은
요청 결과의 festivalScopes에 포함돼야 하며 아니면 422 VALIDATION_ERROR다. scope를 제거할
때는 해당 grant도 같은 AdminAccountPatchWrite에서 제거해 accountVersion·authzVersion과
session revoke를 한 transaction에 적용한다. dangling grant를 보존하거나 scope 재추가 때
과거 grant를 자동 복구하지 않는다.

`stamp-affecting batch/action`은 STAMP_* target을 하나라도 가지거나, FESTIVAL target의
후보·rollback 결과에서 capabilities.stampTour 값이 바뀌거나 현재·후보 중 하나라도
stampTour=true인 상태에서 defaultLanguage·supportedLanguages·translationPolicy가 바뀌거나,
HOME_CONFIGURATION target의
후보·rollback 결과에서 OPEN_STAMP_TOUR action의 개수·payload가 바뀌거나,
MAP_CONFIGURATION·MAP_AREA·MAP_MARKER·SPACE target의 후보가 현재 published checkpoint 또는
retained session checkpoint의 아래 checkpointLocationFingerprint를 바꾸는 모든 요청이다.
named grant는 generic 관리자 경로에서도 우회할 수 없다. STAMP_* target의 revision
목록·상세·restore, STAMP_TOUR WORKING/BATCH/PUBLISHED preview, stamp-affecting
publication batch·operation의 생성·변경·validate·schedule·apply·cancel·rollback과 목록/상세,
관련 festival-scoped 감사 event는 base 역할 외 STAMP_MANAGER를 추가로 요구한다.
`/admin/audit-logs*`의 전역 감사자는 18.3대로
GLOBAL_AUDIT만으로 stamp event를 포함한 전 회차 감사를 조회하며 STAMP_MANAGER나 festival
scope를 추가로 요구하지 않는다. grant 없는 festival-scoped 호출자의 collection에서는 해당 item 전체를 제외하고 direct ID는
404로 감춘다. generic dashboard도 권한 필터 뒤에 집계한다. STAMP_* resource row,
stamp-affecting mixed batch·publication operation, 29.7상 STAMP_MANAGER를 요구하는 activation
operation과 stamp operational highlight를 item 전체로 제외한다. 그 뒤
resourceCounts·translationCounts, scheduledBatches·failedOperations,
cacheInvalidationCounts.pending/failed와 모든 total을 다시 계산하고 마지막에 limit을 적용한다.
MediaRightsWarning.referenceCount도 보이는 non-stamp reference만 세며 0이면 warning을 제외한다.
translation-status의 stamp row·count·detailPath 역시 제외한다. 따라서 숨긴 item의 targetCount,
error, cache 상태나 total에서 존재를 역산할 수 없다. 공개
capability가 켜진 PUBLISHED 정보는 인증 없는 공개 `/stamp-tour`에서 별개로 볼 수 있지만,
그 사실이 관리자 draft·revision 접근 권한을 주지는 않는다.

적용 release는 19.3 OperationalHighlight의 type enum에 `STAMP_CHECKPOINT`를 추가한다. 이
variant는 `resourceType=STAMP_CHECKPOINT`, `state=PAUSED|CLOSED`,
displayLabelTranslations=현재 published checkpoint 제목의 닫힌 locale map,
updatedAt=operationalUpdatedAt, detailPath=해당 AdminStampCheckpoint 상세 경로인 닫힌 mapping이다.
그 밖의 state나 draft checkpoint는 generic highlight에 포함하지 않는다. STAMP_MANAGER가 없는
호출자는 위 권한 필터로 이 variant 전체를 보지 못한다.

StampTourConfigurationWrite는 필수 `translations`, `startsAt`, `endsAt`,
`requiredStampCount`, `verificationMode=VISITOR_SCANS_CHECKPOINT`, `qrPolicy`, nullable
`rotationSeconds`, `rewardMode: NONE|LIMITED_IN_PERSON`, `privacyNoticeTranslations`,
`policyUrl`, `policyVersion`, `participantRetentionEndsAt`,
`privacyApprovalReference: ApprovalReference`를 가진다. privacyApprovalReference.referenceType은
PRIVACY_REVIEW여야 하며 익명 참여·감사 데이터의 수집 범위, 보관·삭제, 미성년자 처리와
비수집 범위를 승인한 근거다. 이 reference는 tour working revision과 불변 revision snapshot·
게시 manifest에 고정하고 관리자 revision 응답과 감사에는 보존하되 공개 projection에는
노출하지 않는다. translations의 locale
value는 필수 `title`, nullable `description`, 필수 `rules`만 가진 닫힌 object이고,
privacyNoticeTranslations의 locale value는 필수 `summary`, `processingPurpose`만 가진 닫힌
object다. 두 map은 5절의 locale·fallback·게시 완전성 규칙을 따르며 policyUrl은 승인된
HTTPS URL이다.
qrPolicy=ROTATING_SIGNED이면 rotationSeconds는 30~300의 integer로 필수이고,
STATIC_SIGNED이면 rotationSeconds는 반드시 null이다.
StampCheckpointWrite는 필수 `code`, `translations`, `startsAt`,
`endsAt`, nullable `mapMarkerId`, nullable `spaceId`, `order`, `enabled`를 가진다. code는 회차
안에서 유일한 3~64자 기술 식별자이며 학교 약칭에서 만들지 않는다. 연결 ID는 같은
festivalId이고 공개 후보에서 addressable해야 한다. 둘 중 하나 이상은 non-null이고, 둘 다
있으면 `marker.linkedSpaceId=spaceId`이면서 `space.mapMarkerId=mapMarkerId`여야 한다. space만
있으면 public mapMarkerId는 space의 marker에서 파생한다. 시간은 tour window 안에 있어야 한다.
translations의 각 non-null locale value는 필수 non-empty `title`, `locationText`와 nullable
`description`만 가진 닫힌 object이며 map 전체는 5절의 locale·fallback·게시 완전성 규칙을
따른다.

stamp-tour-configuration과 stamp-reward-configuration은 조건부 적용 뒤 19.1 singleton
bootstrap을 그대로 따른다. 즉 없는 GET은 404, 최초 PUT은 `If-None-Match: *`와
Idempotency-Key로 201·Location·admin-1 ETag를, 이후 PUT은 현재 If-Match로 200과 새 ETag를
반환한다. tour singleton resourceId는 `stamp-tour`, reward singleton resourceId는
`stamp-reward`, checkpoint resourceId는 UUID다. 세 PublicationResourceType은 단일
schedule·publish·unpublish·rollback을 모두 `BATCH_REQUIRED`로 거부하고 29.7의 완전한 batch로만
전이한다.

AdminStampTourConfiguration은 StampTourConfigurationWrite 저장 projection과 8.2 lifecycle을
결합한 닫힌 schema이고 tour GET/PUT data는 이 component다. AdminStampCheckpoint도
StampCheckpointWrite, 서버 ID와 같은 lifecycle을 결합하며 collection은 order·id 순으로
`items: AdminStampCheckpoint[]`를 반환한다. AdminStampRewardConfiguration은 29.5의
StampRewardConfigurationWrite 저장 projection과 같은 lifecycle을 결합한다. reward config
GET/PUT data는 정확한 AdminStampRewardConfiguration 하나이고 HTTP ETag와 If-Match는
configuration.adminVersion을 보호한다. inventoryVersion은 draft PUT으로 바꿀 수 없고 live
total은 29.7 publication CAS에서만 바뀐다. inventory GET은 ledger가 한 번도 생성되지 않았으면
404, 그 뒤에는 rewardMode·capability·publicationState와 무관하게 200 data로
StampRewardInventoryLedger를 반환하고 `ETag:
"stamp-inventory-{inventoryVersion}-{effectiveExpiryBoundaryVersion}-{activeProjectionVersion}"`을
제공한다. activeProjectionVersion은 activePointerVersion, 현재 public contentRevision과
stampOperationalVersion을 canonicalize하므로 수량이 그대로여도 active projection이 바뀌면
ETag가 바뀐다. boundary
version은 serverTime까지 지난 claim expiry의 정렬된 안정 식별자에서 계산하므로 worker 지연
중 effective 수량이 바뀌면 ETag도 바뀐다. 이 관리자 live GET은 no-store이며 config
ETag와 섞지 않는다.

checkpoint POST는 Idempotency-Key를 요구하고 201 data로 AdminStampCheckpoint,
item Location과 admin ETag를 반환한다. item GET은 200 data와 같은 ETag, PATCH는
application/merge-patch+json·If-Match로 200 data와 새 ETag, 미게시 draft DELETE는 If-Match로
204다. scheduled batch 또는 pending publication·operational operation이 있으면 PATCH와
DELETE를 409 PUBLICATION_CONFLICT로 막는다. 그 밖에는 published checkpoint도 PATCH로 새
workingRevision을 만들 수 있고 현재 public pointer와 기존 issuance의 불변 checkpointRevision은
유지한다. 게시 이력이 있거나 published·참조 상태인 checkpoint는 물리 DELETE만 금지한다.
유효 session의 checkpoint 집합 불변식은 draft 저장이 아니라 29.7의 publication
validate·apply에서 검사한다. checkpoint는 최대 100개이므로 collection GET은 pagination 없이
전체를 반환한다.

QR issuance 생성 body는 필수 `mode`, `configurationRevision`, `checkpointRevision`,
`notBefore`, `expiresAt`, `reason`, 해당 위험 승인 `approvalReference`를 받고
Idempotency-Key를 필수로 요구한다. approvalReference.referenceType은 RISK_ACCEPTANCE여야
한다. 성공은 201·Location과
data의 `issuance: AdminStampQrIssuance`, nullable `initialQr: StampQrDisplayToken`, HTTP
`ETag: "stamp-issuance-{issuanceVersion}"`을 반환한다.
configurationRevision과 checkpointRevision은 같은 path festival의 존재하는 불변 revision이고
checkpoint는 그 configuration의 tour window 안에 있어야 한다. mode는 해당 configuration의
qrPolicy와 같아야 하며 ROTATING이면 그 revision의 유효 rotationSeconds도 있어야 한다.
notBefore·expiresAt은 offset이 있는 RFC 3339 instant이고
`checkpoint.startsAt <= notBefore < expiresAt <= checkpoint.endsAt`,
`serverTime < expiresAt`을 모두 만족해야 한다. 서버는 이 조건과 approvalReference를 token을
서명하기 전에 검사하며 missing revision은 404, 조합·시간 오류는 422 VALIDATION_ERROR로
아무 issuance나 secret 없이 거부한다.
StampQrDisplayToken은 필수 `token`, `notBefore`, `expiresAt`만 가진다.
STATIC_SIGNED의 initialQr는 notBefore·expiresAt이 issuance의 두 값과 정확히 같다.
ROTATING_SIGNED에서 `intervalStart`는 Unix epoch seconds를 rotationSeconds로 내림한 경계,
`intervalEnd=intervalStart+rotationSeconds`이며 token.notBefore는
`max(intervalStart, issuance.notBefore)`, token.expiresAt은
`min(intervalEnd, issuance.expiresAt)`이다. 서버는 `notBefore < expiresAt`인 current interval만
발급하고 같은 issuance·interval에는 같은 signed token을 반환한다. 따라서 표시 token이
유효하다고 말하는 구간이 issuance나 checkpoint 종료를 넘어가지 않는다.
AdminStampQrIssuance는 필수 `id`, `issuanceVersion`, `checkpointId`,
`configurationRevision`, `checkpointRevision`, `mode: STATIC_SIGNED|ROTATING_SIGNED`,
`status: SCHEDULED|ACTIVE|EXPIRED|REVOKED|SUPERSEDED`, `notBefore`, `expiresAt`,
`approvalReference`, `payloadFingerprint`, nullable `reservedPublicationBatchId`, `createdBy`,
`createdAt`, nullable `revokedBy`, nullable
`revokedAt`, nullable `revokeReason`, `updatedAt`만 가진다. STATIC_SIGNED 생성 응답만 initialQr가
non-null이고 이후 GET·감사 로그에는 fingerprint만 남긴다. initialQr의 ‘initial’은 최초 요청과
같은 멱등 재생에만 원문을 전달한다는 뜻이며 방문자 1회용이라는 뜻이 아니다. STATIC token은 유효 기간
동안 서로 다른 session이 반복 스캔할 수 있고 중복 적립은 session-checkpoint unique key가
막는다. ROTATING_SIGNED 생성 응답의 initialQr는 null이다.

issuance 목록은 status, mode, activeAt, page, size를 받고 createdAt 내림차순·id 오름차순,
7.2 pagination과 `items: AdminStampQrIssuance[]`를 반환한다. 상세는
`ETag: "stamp-issuance-{issuanceVersion}"`을 반환한다.

ROTATING_SIGNED 현장 화면은 사람 관리자 access token을 보관하지 않는다. display-session
POST body는 필수 `expiresAt`, `reason`을 받고 issuance If-Match와 Idempotency-Key를 요구한다.
대상 issuance.mode는 ROTATING_SIGNED이고 status는 SCHEDULED 또는 ACTIVE여야 하며
expiresAt은 serverTime보다 뒤, 현재 issuance expiry 이하이면서 최대 12시간이어야 한다.
STATIC_SIGNED면 409 STAMP_QR_MODE_MISMATCH, terminal issuance면 410 STAMP_QR_EXPIRED다.
issuance당 ACTIVE display
session은 최대 20개다. 성공은 201·item Location, data로
`displaySession: AdminStampDisplaySession`, 최초 응답과 동일 멱등 재생에서만 보이는
`credential`을 반환하며 HTTP
`ETag: "stamp-display-session-{displaySessionVersion}-{displayTemporalPhase}"`를 제공한다.
AdminStampDisplaySession은 필수 `id`, `displaySessionVersion`, `issuanceId`,
`status: ACTIVE|EXPIRED|REVOKED`, `expiresAt`, `createdBy`, `createdAt`, nullable `revokedBy`,
nullable `revokedAt`, nullable `revokeReason`, `updatedAt`을 가진다. 목록은 status, page, size와
7.2 pagination을 받고 createdAt 내림차순·id 오름차순으로 반환하며 credential을 포함하지
않는다. terminal metadata는 승인된 관리자 감사 보관 기간 뒤 purge하고 ACTIVE record는
purge하지 않는다. item GET은 같은 data와 ETag를 반환한다. `/revoke`는 필수 `reason`,
display-session If-Match와 Idempotency-Key를 요구하고 200 data로 갱신 session과 새 ETag를
반환한다. displaySessionVersion·updatedAt을 증가시키고 actor, reason, credential fingerprint와
before/after를 감사하되 secret 원문은 남기지 않는다.
display-session deadline worker는 최소 5초마다 due ACTIVE record를
displaySessionVersion CAS로 EXPIRED 전환하고 updatedAt을 증가시킨다. 관리자 목록·상세는
worker 지연 중에도 serverTime >= expiresAt인 ACTIVE 저장값을 effective EXPIRED로 투영하고
ACTIVE filter에서 제외하며, revoke write는 transaction 시작 시 같은 due 전이를 먼저
materialize한 뒤 상태 충돌을 반환한다. issuance의 REVOKED·SUPERSEDED·EXPIRED 연쇄 전이는
모든 ACTIVE child를 같은 transaction에서 REVOKED로 만들고 각 displaySessionVersion·updatedAt,
revokedAt·revokeReason을 증가·기록한다.
displayTemporalPhase는 요청 serverTime과 저장 상태에서 계산한
`ACTIVE_WINDOW|EXPIRED_WINDOW|REVOKED`다. item GET은 이를 representation ETag에 포함하고
conditional GET이 같은 phase에서만 304가 되게 한다. revoke If-Match도 version과 effective
phase를 모두 비교해 만료 경계를 지난 옛 ACTIVE_WINDOW tag를 거부한다. 따라서 safe GET이
상태를 바꾸지 않아도 같은 ETag에 다른 status body를 만들지 않는다.

STATIC issuance 생성의 initialQr와 display-session credential은 공통 19.4 멱등 business
record와 분리한 envelope encryption replay field에 정확히 24시간 보관한다. 같은 key·digest의
재시도는 그 기간 최초 status·Location·ETag와 동일 secret을 재생하고
`Idempotency-Replayed: true`를 반환한다. domain record·감사·로그에는 fingerprint 또는 keyed
digest만 남긴다. 24시간 뒤에는 replay encryption key를 crypto-shred하고 공통 idempotency
tombstone이 같은 key의 새 issuance/session 생성을 막아 409 IDEMPOTENCY_KEY_REUSED를
반환한다. secret 자체가 먼저 만료·폐기됐더라도 24시간 안의 재생은 같은 이미 무효인 값을
반환하며 유효성이 연장되지는 않는다.

단말은 `GET /api/v1/stamp-displays/{displaySessionId}/qr-token`에
`Authorization: StampDisplay {credential}`을 보내고 ACTIVE issuance에서만 200 data로
`qr: StampQrDisplayToken`을 받는다. credential은 256 bit 무작위 secret이고 저장소에는
festival·checkpoint·issuance·displaySession에 묶인 keyed digest만 둔다. 이 scheme은 이 GET
외 관리자·공개 route에 권한을 주지 않고 승인 display origin CORS만 허용하며 refresh가 없다.
서버는 issuance의 configurationRevision·checkpointRevision·mode가 현재 public stamp
snapshot의 해당 checkpoint 및 유효 issuance set에 정확히 속하는지도 확인한다. batch 실패나
취소 뒤 고아 issuance, 과거 snapshot 또는 아직 current public set에 들지 않은 candidate면
410 STAMP_QR_EXPIRED이고
표시 token을 만들지 않는다. 응답은 no-store이고 같은 rotation interval에는 같은 signed token과 notBefore·expiresAt을
반환한다. token은 interval 동안 여러 참여 session이 스캔할 수 있다. issuance가
REVOKED·SUPERSEDED·EXPIRED가 되면 연결 display session도 즉시 revoke한다.
credential 검증 뒤 issuance의 festivalId가 `/festivals/current` active pointer와 같은지도
확인한다. 회차가 전환됐거나 active pointer가 없으면 유효했던 display session이라도 409
STAMP_TOUR_NOT_ACTIVE이고 token을 발급하지 않는다. 따라서 스캔이 항상 실패할 구 회차 QR을
현장 화면이 계속 표시하지 않는다.
현재 public set에 이미 속한 issuance는 미래 batch가 CAS reservation 중이어도 표시·check-in을
계속 허용한다. reservation은 변경 fence이지 현행 public token의 운영 중단 상태가 아니다.
해당 public checkpoint의 effective state가 PAUSED 또는 CLOSED이면 표시 token도 만들지 않고
409 STAMP_CHECKPOINT_NOT_ACTIVE를 반환한다. Problem Details에는 안전한 `checkpointState`와
PAUSED일 때 nullable `retryAt=statusExpiresAt`만 넣어 단말이 중지·재시도를 결정하게 한다.
이 단말 전용 경로는 관리자 `/admin` 목록과 별도로 적용 OpenAPI endpoint index에 추가하며
credential별 분당 12회, displaySession별 동시 2개와 festival edge capacity bucket을 적용한다.
rotationSeconds=30에서도 정상 갱신이 가능해야 하고 초과는 429 RATE_LIMITED와 Retry-After다.
credential 불일치·REVOKED는 401 STAMP_DISPLAY_SESSION_INVALID, expiresAt 이후는 410
STAMP_DISPLAY_SESSION_EXPIRED다. 유효 session이지만 issuance가 notBefore 전이면 409
STAMP_QR_NOT_YET_ACTIVE와 Problem Details의 `retryAt`, REVOKED·SUPERSEDED·expiresAt 이후면
410 STAMP_QR_EXPIRED다. capability=false이면 앞서 정의한 404다.
revoke는 issuance ETag의 If-Match와 Idempotency-Key, 필수 reason을 요구하며 성공은 200과
갱신 issuance·새 ETag다. 현재 public checkpoint의 마지막 유효 issuance는 독립 revoke할 수
없고 409 RESOURCE_IN_USE다. 동일 checkpoint·configuration revision·mode의 다른 유효
issuance 중 현재 public stamp snapshot의 유효 issuance set membership에 이미 속한 집합만으로
revoke transaction의 serverTime부터 checkpoint.endsAt까지 interval union을 무공백으로
덮는지 검증한 경우에만 기존 것을 즉시 revoke할 수 있다. transaction은 제거 대상과 대체
issuanceVersion뿐 아니라 resulting current-public issuance set 및 stampSnapshotId를 함께 CAS한다.
아직 public set에 들지 않은 candidate·고아·과거 snapshot issuance는 coverage에 세지 않는다.
현재 ACTIVE 하나가 있다는 이유만으로 미래 coverage를 가정하지 않는다. 새 stamp snapshot 게시 transaction은 이전 revision용
issuance를 SUPERSEDED로 만들고, 새 revision용 issuance는 serverTime이
`[notBefore, expiresAt)`이면 ACTIVE, notBefore 전이면 SCHEDULED로 둔다. expiresAt을 지난
issuance는 게시 후보가 될 수 없다. deadline worker는 notBefore에서 SCHEDULED→ACTIVE,
expiresAt에서 ACTIVE|SCHEDULED→EXPIRED를 issuanceVersion CAS로 전환하고 ETag·updatedAt을
갱신한다. worker 지연과 무관하게 token 발급·check-in은 매 요청마다 serverTime,
notBefore·expiresAt과 현재 public revision 일치를 다시 검사해 조기·만료 사용을 거부한다.
safe GET인 display token 발급은 상태를 materialize하지 않고 effective status만 계산해
허용·오류를 결정한다. check-in write는 자신의 transaction에서 due 상태를 CAS할 수 있다.
따라서 deadline worker 지연이 운영 경계를 바꾸거나 GET의 무상태 보장을 깨지 않는다.

manual-check-in body는 path에 이미 checkpointId가 있으므로 필수 `sessionPass`, `reason`만
받는다. Idempotency-Key가 필수이고 signed pass의 session·festival·expiry·nonce가 상태
precondition이므로 이 operational endpoint만 If-Match 예외다. QR check-in과 같은 unique
constraint·progress transaction을 사용하며 200 data로 필수 `checkInId`, `checkpointId`,
`duplicate`, `completed`만 반환한다. raw pass와 participantRef는 응답·감사 로그에 남기지
않는다. 인증과 현재 EDITOR+STAMP_MANAGER 권한을 확인한 뒤 동일 key·digest의 완료 record가
있으면 capability·tour·pass gate보다 먼저 원래 200 결과를 재생하고 mutation을 반복하지
않는다. 다른 key나 digest는 아래 현재 gate를 모두 거친다. sessionPass의 형식·서명이나 path festivalId·retained session identity가 다르면
400 STAMP_PASS_INVALID, 만료됐거나 이미 소비됐으면 410 STAMP_PASS_EXPIRED이며 어느 경우에도
pass·checkIn을 바꾸지 않는다. manual check-in도 path가 현재 active festival인지,
capability.stampTour=true인지, serverTime에 tour가 ACTIVE인지 같은 transaction에서 먼저
검사하며 종료 또는 drain 뒤 pass가 남아 있어도 각각 FEATURE_NOT_ENABLED 또는
STAMP_TOUR_NOT_ACTIVE다. 같은 transaction에서 retained session의 acceptedPolicyVersion이
current privacyNotice.policyVersion과 다르면 pass를 소비하지 않고 409
STAMP_POLICY_VERSION_MISMATCH다. 그 뒤 pass identity를 검증하고 기존 checkIn duplicate를 조회한다.
기존 checkIn이면 path checkpoint가 이후 PAUSED|CLOSED|ENDED여도 pass를 원자 소비하고 200
duplicate=true를 반환한다. 신규 적립에서만 path checkpoint live-state와 현재
completionFeasible을 다시 계산한다. checkpoint가 active가 아니면 pass를 소비하지 않고
STAMP_CHECKPOINT_NOT_ACTIVE이며, completionFeasible=false이면 pass를 소비하거나 새
checkIn을 만들지 않고 409 STAMP_COMPLETION_NOT_FEASIBLE이다.

checkpoint live-state body는 필수 `operatingOverride: NORMAL|PAUSED|CLOSED`, `reason`,
`expectedPublishedRevision`과 선택적 `statusMessageTranslations`, nullable `expiresAt`을 받는다. PAUSED·CLOSED는
회차 translationPolicy를 충족하는 non-empty 메시지가 필수이고 NORMAL은 활성 override를
해제한다. PAUSED의 expiresAt은 필수이며 serverTime보다 뒤, serverTime+2시간 이내이고
checkpoint.endsAt보다 최소 15분 앞서야 한다. 남은 시간이 15분 이하이거나 이 범위를
충족하지 않으면 상태를 바꾸지 않고 422 VALIDATION_ERROR를 반환한다. CLOSED·NORMAL에서는
expiresAt이 null이어야 한다. checkpoint admin ETag의 If-Match와 Idempotency-Key가 필수이며 19.4의
PublicationOperation을 202·Location으로 반환한다. worker는 commit 직전에 PUBLISHER role,
STAMP_MANAGER grant, active festival·capability와 expected revision을 다시 검사한다. PAUSED는
commit serverTime에도 expiresAt이 미래이고 serverTime+2시간 이내이며 checkpoint.endsAt보다
최소 15분 앞선지 다시 검사한다. 이어 요청 결과의 current·future checkpoint 집합에서
PAUSED는 29.3의 유효한 자동 재개 조건을 충족할 때 포함하고 CLOSED는 제외해 완료 기준이
가능한지 다시 계산한다. queue 지연이나 다른 live-state 때문에 하나라도 깨지면 public
overlay를 전혀 바꾸지 않고 pending을 해제한 뒤 operation을 FAILED,
error.code=VALIDATION_ERROR, retryable=false로 끝낸다. 검사를 통과한 경우에만 public
checkpoint overlay, stampOperationalVersion 증가, `/stamp-tour`·CLIENT_MANIFEST purge를
하나의 CAS pipeline으로 적용한다.
operatingOverride=CLOSED는 해당 checkpoint를 제외해도 현재·향후 적립 가능한 enabled
checkpoint 수가 requiredStampCount 이상일 때만 commit한다. 아니면 409 RESOURCE_IN_USE이며
PAUSED로 안전하게 멈추고 운영 결정을 받아야 한다. 유효 session 중 checkpoint ID 집합을
바꾸는 대체 게시도 금지되므로 이를 우회 해법으로 안내하지 않는다. 영구 폐쇄가 불가피하면
capability를 false로 drain하는 범위를 넘어 별도 승인된 참여 migration·보상 계약이 필요하며
v1이 자동 credit을 만들지 않는다. PAUSED는 checkpoint를 영구 제외하는 CLOSED guard 대신
위의 유효한 자동 재개 시각 뒤 future checkpoint로 포함해 완료 가능성을 계산하며 운영 UI에
재개 필요 경보를 유지한다.
AdminStampCheckpoint에는 nullable `activeOperationalOverride`와
`pendingOperationalOperationId`를 추가한다. override는 필수 `id`, `overrideVersion`,
`operatingOverride: PAUSED|CLOSED`, `statusMessageTranslations`, nullable `expiresAt`, `reason`, `createdBy`,
`createdAt`을 가진다. 새 checkpoint revision을 게시·rollback·unpublish할 때는 19.4의
overrideDecision resourceType에 STAMP_CHECKPOINT를 추가해 RETAIN|CLEAR를 명시하고, emergency
live-state는 예약 stamp batch에 priority fence를 세워 옛 draft가 현장 정지를 덮지 못하게
한다. operational override는 participationSnapshotId를 바꾸지 않는 의도적 현장 overlay이며
checkpoint가 정해지는 check-in과 manual-check-in은 target의 effective PAUSED|CLOSED를
STAMP_CHECKPOINT_NOT_ACTIVE로 거부한다. checkpoint를 담지 않는 session-pass 발급은
tour·capability·session completionFeasible까지만 검사하고 target 상태는 소비하는
manual-check-in에서 검사한다.
deadline worker와 첫 관련 write는 PAUSED expiresAt에서 override clear event,
stampOperationalVersion 증가와 같은 cache purge를 적용한다. safe GET은 worker 지연 중에도
override가 없는 것으로 보고 serverTime에서 UPCOMING|ACTIVE|ENDED를 계산하며 상태를 변경하지
않는다. 자동 clear transaction은 읽은 due overrideId·overrideVersion, checkpoint
adminVersion·publishedRevision, pendingOperationalOperationId=null과 public head version을 모두
CAS한다. 그 사이 CLOSED나 새 PAUSED가 적용됐으면 아무것도 지우지 않고 최신 상태를 다시
읽는다. 성공할 때만 clear event, adminVersion·operationalUpdatedAt·stampOperationalVersion과
cache outbox를 한 transaction에서 갱신한다.

AdminStampRewardClaim은 필수 `id`, `claimVersion`, `generation`, `rewardConfigurationRevision`,
`status: ISSUED|FULFILLED|EXPIRED|CANCELED`, `issuedAt`,
`expiresAt`, nullable `fulfilledAt`, nullable `expiredAt`, nullable `canceledAt`, nullable `cancellationReason`,
`inventoryVersion`, `rewardPresentation: StampRewardOperatorPresentation`만 가진다.
StampRewardOperatorPresentation은 claim 생성 때 고정한 필수 `titleTranslations`, nullable
`descriptionTranslations`, `claimGuideTranslations`, `unitsPerClaim=1`만 가진다. referenced reward
revision이 unpublish되거나 capability=false drain이어도 이 최소 projection은 claim expiry·감사
보관 동안 남아 순수 STAMP_REWARD_OPERATOR가 지급 대상을 확인할 수 있다. 참여 session
식별자나 check-in 이동 경로는 포함하지 않는다.
fulfilledAt은 status=FULFILLED일 때만 non-null이고, expiredAt은 status=EXPIRED일 때만
non-null이며, canceledAt과 cancellationReason은 status=CANCELED일 때에만 둘 다 non-null이다.
각 조건의 역도 성립하고 ISSUED에서는 네 필드가 모두 null이다. deadline worker 전 effective
EXPIRED projection은 expiredAt=expiresAt을 합성하며 materialize 뒤에도 같은 값을 보존한다.
목록은 status, issuedFrom, issuedTo, page, size를 받고 issuedAt 내림차순·id 오름차순으로
`items: AdminStampRewardClaim[]`와 7.2 pagination을 반환한다. 상세은
`ETag: "stamp-claim-{claimVersion}-{claimTemporalPhase}"`을 반환한다. claimTemporalPhase는
ISSUED이고 serverTime이 expiresAt 전이면 ISSUED_WINDOW, 해당 경계를 지났으면
EXPIRED_WINDOW, 나머지 terminal 상태는 STABLE이다. worker 전 effective EXPIRED projection도
이 phase를 사용하므로 같은 ETag에 다른 status body를 만들지 않는다. fulfill은 필수
`claimPass`, `reason`, cancel은
필수 `reason`을 받고 둘 다 claim ETag의 If-Match와 Idempotency-Key를 요구한다. 성공은 200
data로 필수 `claim: AdminStampRewardClaim`, `inventory: StampRewardInventoryLedger`와 새 claim
ETag를 반환한다. 요청의 If-Match도 claimTemporalPhase를 비교해 경계를 지난
ISSUED_WINDOW tag를 거부한다. fulfill은 같은 key·digest의 완료 replay를 먼저 반환한 뒤 pass
오류 우선순위를 고정한다. 형식·서명·path festivalId/claimId 불일치는 400
STAMP_PASS_INVALID, token 만료·이미 소비된 nonce 또는 effective EXPIRED claim은 410
STAMP_PASS_EXPIRED다. 유효하고 미소비인 pass인데 claim이 FULFILLED·CANCELED이면 409
STAMP_CLAIM_STATE_CONFLICT다. 그 뒤 If-Match·claimVersion·inventoryVersion을 검사하고
transaction에서 pass nonce와 함께 CAS한다. 같은 pass의 동시 fulfill loser는 소비된 nonce로
410, 다른 pass의 fulfill 또는 cancel에 진 유효 pass는 terminal claim으로 409다. cancel과
fulfill 중 하나만 terminal 전이에 성공하며 일반 stale ETag는 412 REVISION_MISMATCH다. pass 원문과
participantRef를 감사 로그에 남기지 않는다.

StampTourDashboard는 필수 `activeSessionCount`, `completedSessionCount`,
`checkpointCounts: { checkpointId, acceptedCount }[]`, `inventoryTotal`, `inventoryReserved`,
`inventoryFulfilled`, `inventoryAvailable`, `inventoryActive`, `pendingExpiryCount`, nullable
`inventoryVersion`, nullable `effectiveAt`, `failedCheckInCount`,
`failedCheckInWindowStartsAt`, `generatedAt`을 가진다. 모든
count는 0 이상이고 참여자 식별자·IP·user agent를 포함하지 않는다.
ledger가 없으면 네 inventory 수치와 pendingExpiryCount는 0, inventoryActive=false,
inventoryVersion과 effectiveAt은 null이다. ledger가 있으면 둘은 non-null이고 mode가
NONE이거나 unpublish된 뒤에도 보존 수치를 같은 inventoryVersion snapshot에서 반환하되
현재 public LIMITED_IN_PERSON에서 사용 중일 때만 inventoryActive=true다.
dashboard의 모든 값은 generatedAt serverTime에서 같은 consistent-read snapshot으로 계산한다.
activeSessionCount는 아직 retention 내이고 삭제되지 않은 session 전체이며,
completedSessionCount는 그중 완료 기준을 충족한 session으로 REWARD_RESERVED와
REWARD_FULFILLED도 포함하므로 두 count는 배타적이지 않다. checkpointCounts는 retention 내
session의 성공·중복 제거된 check-in만 checkpoint별 누적하고, failedCheckInCount는
`[generatedAt-15분, generatedAt)`의 검증 실패 합계다. rate-limit 거부와 duplicate=true는
failed에 포함하지 않으며 dashboard는 이 고정 15분 window를 함께 표시한다.

### 29.7 게시, 동시성과 관리자 상태

STAMP_TOUR_CONFIGURATION, 모든 공개 STAMP_CHECKPOINT와 reward mode가 LIMITED_IN_PERSON이면
STAMP_REWARD_CONFIGURATION을 하나의 완전한 desired-snapshot PublicationBatch로만 게시한다.
빠지는 공개 checkpoint는 UNPUBLISH target으로 명시한다. capability를 켜거나 홈 진입점을
바꾸는 두 방향의 전이는 FESTIVAL과 HOME_CONFIGURATION을 같은 stamp-affecting batch에 넣고
필요한 stamp target도 함께 포함한다. public head에서 capability.stampTour=true이면 승인된
OPEN_STAMP_TOUR HomeAction이 정확히 하나이고 false이면 0개여야 하며, 이후 HOME 단독 게시나
rollback도 이 cardinality를 깨뜨릴 수 없다. validate는 설정 기간, required count, 번역,
addressable marker/space, QR issuance, capability approval, revision에 고정된 privacy·reward
risk approval, reward 재고와 privacy retention을 전수 검사한다. 각 checkpoint 위치 화면에서
선택될 수 있는 필수 media는 usageExpiresAt이 null이거나 해당 snapshot의
participantRetentionEndsAt보다 뒤여야 한다. rollback도 복원할 revision의
privacyApprovalReference와 riskApprovalReference를 다시 검증한다. 해당 stamp target의 validate,
schedule, apply, unpublish, rollback뿐 아니라 모든 stamp-affecting batch/action은 festival
scope의 PUBLISHER 이상과 STAMP_MANAGER grant를 모두 요구한다. STAMP_MANAGER 관리 route도 EDITOR 이상과 해당 grant를, 수령 route는 VIEWER
이상과 STAMP_REWARD_OPERATOR grant를 모두 요구한다. 어느 하나만 가진 계정은 403이다.
stamp-affecting publication·rollback·scheduled apply worker는 commit 직전에 요청 actor의 현재
authzVersion으로 role, festival scope와 STAMP_MANAGER grant를 모두 다시 검사한다. 하나라도
회수됐으면 public pointer·inventory·issuance를 바꾸지 않고 PERMISSION_DENIED로 실패시키며
target·QR reservation을 terminal transaction에서 해제한다. inactive 회차의 published head가
stampTour=true이면 activate와 DEACTIVATE_EXISTING의 새 대상도 접수·commit에 대상 회차
STAMP_MANAGER grant를 요구한다. 순수 deactivate는 안전한 공개 해제를 막지 않도록 기존
ADMIN·scope 규칙만 적용한다.
현재 published reward가 있는데 rewardMode=NONE으로 바꾸는 desired snapshot은
STAMP_REWARD_CONFIGURATION UNPUBLISH target도 반드시 포함한다. ledger와 비식별 fulfillment
감사 aggregate는 content unpublish로 삭제·reset하지 않고 승인 보관 기간까지 유지한다.

active 회차에서 capability.stampTour=true인 public head는 published configuration과
requiredStampCount 이상의 enabled checkpoint, 각 checkpoint의 유효 QR issuance를 반드시
가진다. issuance 집합은 public configuration의 qrPolicy와 각 checkpoint·configuration
revision이 일치하고 checkpoint의 전체 `[startsAt, endsAt)`을 interval union으로 빈틈없이
덮어야 한다. 연속 issuance는 경계에서 겹칠 수 있고 commit transaction이 새 issuance를
ACTIVE로 만든 뒤 기존 것을 SUPERSEDED로 전환해 공백을 만들지 않는다. STATIC_SIGNED도
checkpoint 종료 전 만료되는 단일 token만으로 게시할 수 없다. LIMITED_IN_PERSON이면
published reward configuration, 그 revision에 고정된 referenceType=RISK_ACCEPTANCE인
riskApprovalReference와 유효 inventory ledger가
필요하다. capability를 켜는 FESTIVAL revision과 최초 stamp snapshot, HOME 진입점은 같은
batch로 적용한다. 이 불변식을 깨는
unpublish·rollback은 409 PUBLICATION_BATCH_INVALID이며 먼저 capability를 false로 같은 batch에
게시해야 한다.

capability false 전환은 HOME 진입점 제거와 같은 batch로 적용하되 stamp snapshot 자체는
유효 session 또는 ISSUED claim이 하나라도 있으면 게시 취소할 수 없다. 이 drain 동안 위
progress·csrf·consent·DELETE, 기존 ISSUED claim의 claim-pass와 관리자 fulfillment만 retained
snapshot으로 동작한다. 완료 idempotency record가 없는 신규 참여·check-in과 다른 key의
unique duplicate를 포함한 모든 적립·session-pass·
manual-check-in·display token·claim은
차단한다. 모든 session이 삭제·만료되고 ISSUED claim이 0이 된 뒤에만 별도 batch로
stamp configuration·checkpoint·reward를 unpublish할 수 있으며, 앞서 제거하려 하면 409
RESOURCE_IN_USE다. rewardMode를 NONE으로 바꾸거나 reward configuration을 unpublish하는
경우도 ISSUED claim=0을 요구한다.

session 생성 시 configurationRevision과 함께 participationSnapshotId인 참여 규칙·위치 맥락 hash를
고정한다. 이 hash는 tour startsAt·endsAt, requiredStampCount, verificationMode, qrPolicy,
rewardMode, LIMITED_IN_PERSON의 enabled·
claim window·pass validity·reservation minutes·reclaim policy·시도
한도와 enabled checkpoint의 ID·운영 기간·checkpointLocationFingerprint 집합을 canonicalize한다.
checkpointLocationFingerprint는 checkpoint의 mapMarkerId·spaceId·locationText 번역과 연결된
public map의 coordinateSystem·contentBounds·지도 경계, area·marker의 공개 주소 가능 상태·
좌표·anchor·분류·표시 번역·linkedSpaceId, space의 mapMarkerId·위치 표시 필드를
canonicalize한 digest다. mediaId·mediaVersion·binary checksum처럼 위치 의미를 바꾸지 않는
presentation asset identity는 이 digest에 넣지 않는다. 지도·area·marker·
space target의 게시가 이 projection을 바꾸는지도 stamp batch 검증기가 역참조해 계산한다.
유효 session이 하나라도 있으면 이 hash를 바꾸는 publish·
rollback과 checkpoint disable/unpublish를 409 RESOURCE_IN_USE로 거부한다. active cohort에서
retained configuration과 UI가 엇갈리지 않도록 tour/reward/checkpoint의 번역·설명·locationText·
addressable map 연결·checkpointLocationFingerprint 변경도 session이 0이 될 때까지 막는다.
다만 media 교체만 있는 지도 batch는 coordinateSystem·contentBounds·pixel dimensions·overlay
정렬과 모든 checkpointLocationFingerprint가 이전 public head와 정확히 같고, 새 media가 위
retention deadline 권리 검증을 통과할 때 active session 중에도 허용한다. 이 호환 교체는 새
contentRevision·mapVersion과 media pin을 발행하되 참여 규칙·check-in ID·좌표 의미를 바꾸지
않으며, rights 만료 asset의 안전한 교체 경로로 사용한다.
privacy policy-only 게시와 별도
inventory ledger total 변경만 각각 재동의·재고 CAS 규칙으로 허용한다. check-in은 현재 issuance의 revision 자체가 아니라
현재 snapshot의 의미 hash가 session에 고정한 hash와 같은지 확인하고, progress는 session의
retained configurationRevision에 있는 checkpoint order·label로 기존 checkIn을 투영한다.
따라서 active cohort 중 완료 기준이 바뀌거나 orphan checkpoint가 생기지 않는다. 의미 변경이
필요하면 capability를 false로 drain하고 모든 session 만료·삭제 뒤 새 snapshot으로 다시
활성화하며 v1은 session migration을 암묵적으로 수행하지 않는다.

유효 stamp session이 하나라도 있는 동안 FESTIVAL의 defaultLanguage, supportedLanguages,
translationPolicy를 바꾸는 validate·publish·rollback은 409 RESOURCE_IN_USE다. retained
configuration과 accepted privacy notice는 session 생성 당시 정책에서 허용된 번역·fallback을
전제로 하기 때문에 current policy만 바꿔 재해석하지 않는다. 이 제한은 lang과
Accept-Language를 생략한 progress 포함 mutation의 동일 raw 요청이 session 수명 중 같은
requestedLanguage와 idempotency digest를 유지하게도 한다. 모든 session이 만료·삭제된 뒤에만
일반 FESTIVAL batch로 언어 정책을 바꿀 수 있다.

session은 생성 당시 nullable rewardConfigurationRevision도 고정하고 progress.configuration의
reward projection과 새 claim 판단은 그 retained revision을 사용한다. claim 생성은 그
rewardConfigurationRevision을 claim에 고정한다. ISSUED claim이 있으면
claimPassValiditySeconds, claim window·reclaim policy·수령 절차를 바꾸는 reward publish·rollback을
409 RESOURCE_IN_USE로 거부한다. inventoryTotal은 별도 ledger CAS 규칙으로만 바꿀 수 있고,
FULFILLED·EXPIRED·CANCELED claim의 고정 revision은 감사 근거로 유지한다.

`(festivalId, policyVersion)`은 privacyNotice의 locale별 summary, policyUrl,
participantRetentionEndsAt과 처리 목적을 canonicalize한 contentHash에 영구적으로 하나만
매핑한다. 같은 version으로 hash가 다른 고지를 게시할 수 없고 의미 있는 변경은 새
policyVersion을 요구한다. 새 version이 게시되면 기존 session은 즉시 consentRequired=true가
되어 재동의·삭제 외 쓰기를 할 수 없다. 새 retention deadline은 모든 claimEndsAt과 발생
가능한 claim expiry뿐 아니라 현재 남은 모든 ISSUED claim.expiresAt 이후여야 하고, active
cohort의 checkpoint 위치 화면에 필요한 모든 media도 usageExpiresAt=null이거나 새 deadline
뒤까지 유효해야 한다. 이 조건을 어기는 단축·연장은 409 RESOURCE_IN_USE다. 기존 session에 더 긴 deadline을 적용하는 것은
29.3의 재동의 transaction 뒤에만 허용한다.

Stamp aggregate도 adminVersion, workingRevision, publishedRevision, 예약 필드, ETag와
Idempotency-Key를 8.2·19.4와 똑같이 사용한다. check-in은 server transaction에서 session,
checkpoint issuance와 unique key를 잠그고, reward claim은 progress와 inventory를 함께 잠근다.
서로 다른 요청이 마지막 stamp나 마지막 재고에 경쟁해도 하나의 결정적 결과만 커밋한다.
stamp snapshot publish·rollback은 validation에서 고정한 inventoryVersion도 public head와
같은 transaction으로 CAS하며, mismatch면 public pointer와 ledger를 모두 보존한 채 operation과
batch를 FAILED로 끝낸다.

조건부 적용은 PublicationValidationResult에 항상 존재하는
`inventoryChecks: StampInventoryValidationCheck[]`와
`qrIssuanceChecks: StampQrIssuanceValidationCheck[]`를 추가한다. stamp reward target이 없으면
첫 배열이 비고 stamp target이 없으면 둘째 배열도 빈다. inventory item은 필수
`rewardConfigurationId`, `action: APPLY_TOTAL|PRESERVE_ON_UNPUBLISH`, nullable
`expectedInventoryVersion`, nullable `candidateInventoryTotal`, `reserved`, `fulfilled`,
`valid`만 가진다. validate는 같은
transaction snapshot에서 이 값을 기록하고 candidateInventoryTotal >= reserved+fulfilled일
때만 valid=true로 만든다. stamp config·reward·checkpoint target 또는 inventory 정책이
바뀌면 기존 validationResult를 무효화한다. schedule은 이 check를 함께 예약하고 apply·rollback
commit은 expectedInventoryVersion을 다시 CAS한다. claim·expire·cancel이 그 사이 ledger를
바꿔 version mismatch가 나면 자동으로 옛 수치를 적용하지 않고 새 validate를 요구한다.
최초 LIMITED_IN_PERSON validate에서 ledger가 없으면 expectedInventoryVersion=null,
reserved=fulfilled=0을 기록한다. commit은 `(festivalId, rewardConfigurationId)` row absence를
CAS해 candidateInventoryTotal, reserved=0, fulfilled=0, inventoryVersion=1의 ledger를 public
head와 같은 transaction에서 만든다. 경쟁 생성이나 그 사이 생긴 row는 412
REVISION_MISMATCH로 전체 실패한다. 이후 게시·rollback은 반드시 non-null version을 CAS하며
unpublish는 ledger를 삭제하지 않는다.
APPLY_TOTAL은 PUBLISH·rollback에서 candidateInventoryTotal이 non-null이고 위 total CAS를
적용한다. PRESERVE_ON_UNPUBLISH는 candidateInventoryTotal이 null이며 현재 ledger와 수량을
그대로 보존하되 expectedInventoryVersion과 ISSUED claim=0을 commit에서 다시 CAS한다. ledger가
없으면 expectedInventoryVersion도 null이고 absence만 확인한다.

StampQrIssuanceValidationCheck는 필수 `checkpointId`, `issuanceId`,
`expectedIssuanceVersion`, `configurationRevision`, `checkpointRevision`,
`mode: STATIC_SIGNED|ROTATING_SIGNED`, `notBefore`, `expiresAt`,
`statusAtValidation: SCHEDULED|ACTIVE|EXPIRED|REVOKED|SUPERSEDED`, `valid`를 가진다.
statusAtValidation은 validatedAt serverTime으로 계산한 effective status이고 SCHEDULED 또는
ACTIVE이며 나머지 revision·mode·interval 검증을 모두 통과할 때만 valid=true다. enabled
checkpoint의 coverage에 실제 사용한 issuance를 모두 중복 없이 고정한다. schedule, 즉시 apply
또는 rollback 접수는 current public stamp snapshot membership을 같은 transaction에서 판정해
coverage issuance마다 batch.reservedQrIssuances에 ReservedStampQrIssuance를 저장한다.
이 item의 필수 필드는 `issuanceId`, `reservationMode: SHARED_CURRENT|EXCLUSIVE_CANDIDATE`,
`validatedIssuanceVersion`, nullable `reservedIssuanceVersion`, `immutableFingerprint`다.
fingerprint는 configuration/checkpoint revision, mode, notBefore, expiresAt과 signing-key ID를
canonicalize하며 secret은 포함하지 않는다.

SHARED_CURRENT는 접수 시 current public set에 속한 issuance다. admin record를 쓰거나
issuanceVersion을 증가시키지 않고 reservedIssuanceVersion은 null이다. 따라서 정상
SCHEDULED→ACTIVE 시간 전이를 허용하며 commit은 최신 version 자체가 아니라 fingerprint,
terminal 아님, serverTime interval과 current validity를 다시 검사한다. EXCLUSIVE_CANDIDATE는
아직 current public set에 없는 issuance다. nullable reservedPublicationBatchId를 batch ID로
issuanceVersion CAS해 기록하고 정확히 1 증가한 post-write version을
reservedIssuanceVersion에 저장한다.
schedule은 applyAt, 즉시 apply는 접수 serverTime을 기준으로 notBefore·expiresAt에서 예상
상태를 다시 계산하고, worker는 commit serverTime으로 최종 유효성을 다시 계산한다.
rollback 대상의 과거 configuration/checkpoint revision용 issuance가 이미 만료됐으면 같은
revision·mode에 맞는 새 issuance를 먼저 발급해 남은 checkpoint interval 전체 coverage를
충족해야 하며 과거 만료 token을 되살리지 않는다.
조건부 적용 시 PublicationBatch의 닫힌 schema에
`reservedQrIssuances: ReservedStampQrIssuance[]`를 필수로 추가하며 stamp target이 없거나 아직
예약 전이면 빈 배열이다.
EXCLUSIVE_CANDIDATE의 SCHEDULED issuance만 public이 아니므로 deadline worker가 조기 ACTIVE로
바꾸지 않고 apply가 serverTime으로 유효 상태를 결정한다. expiry는 reservation보다 우선하며
due candidate가 만료되면 owning batch를 FAILED로 만들고 모든 exclusive reservation을
원자 해제한다. SCHEDULED batch라면 모든 publication target의 scheduledAction·scheduledBatchId·
scheduledApplyAt을 지우고 adminVersion을 증가시키며 batch.scheduledApplyAt=null,
failure.code=PUBLICATION_BATCH_INVALID로 만든다. APPLYING이면 PublicationOperation도 FAILED로
만들고 pendingPublicationOperationId·target reservation을 같은 transaction에서 지운다.
두 경우 모두 batchVersion, issuanceVersion·updatedAt, 감사·운영 경보 outbox를 함께 갱신해
terminal batch가 편집을 막지 않게 한다.

안전 revoke가 SHARED_CURRENT 또는 EXCLUSIVE_CANDIDATE를 바꾸면 이를 참조한 SCHEDULED
batch는 cancellation.reasonCode=SUPERSEDED_BY_EMERGENCY로 CANCELED하고 같은 target·QR
cleanup을 수행한다. APPLYING batch에는 19.4의 emergency priority fence를 세우며 revoke가
먼저 이기면 batch와 PublicationOperation을 CANCELED하고 모든 reservation을 원자 해제한다.
다른 candidate 변경은 409 PUBLICATION_CONFLICT다.
cancel·FAILED·APPLIED terminal 전이는 EXCLUSIVE_CANDIDATE reservation만 해제하고 영향
issuance마다 issuanceVersion·updatedAt을 다시 증가시킨다. acquire와 release 모두 ETag와
감사 before/after에 반영한다. apply·rollback commit은 SHARED_CURRENT의 fingerprint·effective
status와 EXCLUSIVE_CANDIDATE의 reservedIssuanceVersion·reservation batchId를 각각 검사하고,
configuration/checkpoint revision, serverTime interval과 전체 무공백 coverage를 같은
transaction에서 다시 검증한다. 하나라도 달라지면 public pointer·inventory·issuance public
status를 전혀 바꾸지 않고 batch/operation을 FAILED,
error.code=PUBLICATION_BATCH_INVALID로 끝낸다.

관리자 구성·checkpoint·QR issuance·manual check-in·claim 상태 변경은 actor, reason,
requestId와 before/after 상태를 감사한다. approvalReference는 tour revision의
privacyApprovalReference, reward revision의 riskApprovalReference, QR issuance body처럼 해당
요청·불변 revision에 실제 존재하는 경우에만 함께 기록한다. checkpoint CRUD·live-state,
manual check-in과 claim fulfill/cancel은 별도 approvalReference를 만들지 않고 필수 reason을
감사 근거로 쓴다. token, cookie, participantRef, CSRF, QR payload와 claim pass 원문은 감사
change에 넣지 않는다.

### 29.8 보관, 제한, 오류와 검증

participantRetentionEndsAt은 tour 종료 뒤 1~90일 범위이며 claimEndsAt과 모든 가능한 claim
expiry보다 늦은 승인 날짜로 설정한다. 그 시각의 deadline worker는 남은 ISSUED claim을 먼저
EXPIRED로 바꾸고 reservation을 ledger에 원자 반환한 뒤 session, participantRef, check-in과
미수령 claim 연결 및 keyed digest key를 삭제한다. 삭제 transaction은 participant data와
분리된 cookie secret keyed digest, 만료 사유와 expiredAt만 가진 expiry tombstone을 24시간
보존한다. 그 동안 일치 cookie에는 constant-time 조회 뒤 410 STAMP_SESSION_EXPIRED와
Set-Cookie Max-Age=0을 반환하고, 24시간 뒤 tombstone까지 지운 다음 같은 미지 cookie는 401
STAMP_SESSION_REQUIRED가 된다. FULFILLED 재고 감사는 참여 session과
연결할 수 없는 일별 aggregate로만 별도 승인 기간 동안 보존한다. 원본 IP와 user agent는
저장하지 않고 rate-limit counter는 30분 이내 폐기한다.

기본 제한은 유효 token이 있을 때 bootstrap+session 생성을 합쳐 edge anonymous rate token당 분당 10회이고 IP는
같은 수치의 soft signal로만 쓴다. csrf 발급은 session당 분당 30회, consent·DELETE·check-in은
session당 합산 분당 10회, QR·session/claim pass는 session당 합산 분당 6회, reward claim은
session당 분당 3회다. 관리자 stamp write는 account당 분당 60회이고 그중 QR issuance·revoke는
분당 30회, 수령 처리는 operator당 분당 60회다. 새 Idempotency-Key는 bucket을 우회하지
않으며 실패·중복 요청도 비용을 센다. bootstrap record는 IP가 아니라 만료 시각으로 회수하고
festival별 미완료 record 상한·backpressure를 둔다. 행사장 NAT에서는 IP 하나만으로 hard
block하지 않고 익명 session, token nonce, endpoint·edge capacity와 부정 패턴을 함께 사용한다.
초과는 23절의 429·Retry-After를 사용하고 모든 값은 행사 전 부하 시험으로 강화·완화한다.

조건부 오류 code:

| code | HTTP | 의미 |
|---|---:|---|
| STAMP_SESSION_REQUIRED | 401 | 익명 참여 session이 없거나 무효 |
| STAMP_DISPLAY_SESSION_INVALID | 401 | display credential 불일치 또는 폐기된 단말 session |
| STAMP_CSRF_INVALID | 403 | same-origin Origin 또는 session-bound CSRF가 없거나 불일치 |
| STAMP_QR_INVALID | 400 | QR 서명·대상·형식 불일치 |
| STAMP_PASS_INVALID | 400 | session/claim pass 서명·형식·festival 또는 session/claim identity 불일치 |
| STAMP_QR_EXPIRED | 410 | QR issuance 또는 회전 token 만료·폐기 |
| STAMP_SESSION_EXPIRED | 410 | 참여 보관 기한 만료 |
| STAMP_DISPLAY_SESSION_EXPIRED | 410 | display session 자체 만료 |
| STAMP_PASS_EXPIRED | 410 | session/claim pass가 만료됐거나 이미 소비됨 |
| STAMP_SNAPSHOT_MISMATCH | 412 | expectedStampSnapshotId와 현재 public stamp snapshot 불일치 |
| STAMP_BOOTSTRAP_USED | 409 | bootstrap으로 이미 session을 만들었거나 다른 payload에 재사용 |
| STAMP_SESSION_ALREADY_EXISTS | 409 | 같은 active 회차의 유효 session을 새 session으로 덮으려 함 |
| STAMP_SESSION_OTHER_FESTIVAL | 409 | cookie가 다른 보관 중 회차 session을 가리켜 명시적 정리가 필요 |
| STAMP_QR_NOT_YET_ACTIVE | 409 | 유효 issuance의 notBefore 전이며 retryAt 이후 재시도 가능 |
| STAMP_QR_MODE_MISMATCH | 409 | display-session과 issuance QR mode 조합이 맞지 않음 |
| STAMP_TOUR_NOT_ACTIVE | 409 | 현재 active 회차·tour 운영 시간이 아니어서 신규 참여·적립 불가 |
| STAMP_COMPLETION_NOT_FEASIBLE | 409 | 남은 checkpoint로 완료 기준을 충족할 수 없음 |
| STAMP_CHECKPOINT_NOT_ACTIVE | 409 | checkpoint가 disabled·운영 시간 밖·PAUSED·CLOSED 상태임 |
| STAMP_POLICY_VERSION_MISMATCH | 409 | 동의한 정책과 현재 게시 정책 불일치 |
| STAMP_REWARD_NOT_ELIGIBLE | 409 | 완료 기준 미충족 |
| STAMP_REWARD_UNAVAILABLE | 409 | 예약 가능한 경품 재고 없음 |
| STAMP_CLAIM_WINDOW_CLOSED | 409 | 회차가 inactive이거나 경품 예약이 disabled·예약 창 밖임 |
| STAMP_CLAIM_STATE_CONFLICT | 409 | claim 상태가 수령·취소 전이를 허용하지 않음 |
| STAMP_INVENTORY_CONFLICT | 409 | 게시 총량이 현재 예약·수령 수량보다 작음 |

적용 release의 계약 테스트에는 다음을 추가한다.

- bootstrap·session·CSRF 재발급·재동의·cookie의 festival 격리, session 덮어쓰기 방지와 기존
  공개 API 무로그인 회귀 테스트
- QR 변조·만료·폐기·공유 위험 mode와 동일 checkpoint 동시 중복 테스트
- QR interval 무중단 coverage·교체·마지막 issuance revoke 차단과 checkpoint revision pinning
- QR·display credential 암호화 멱등 재생, display expiry·rate limit·현재 snapshot membership
- checkpoint PAUSED 자동 해제·CLOSED 완료 가능성·live-state cache/override 우선순위
- 마지막 stamp·마지막 재고·inventoryVersion 동시성, idempotency replay와 claim 만료 재고 반환
- 수동 check-in·claim fulfillment의 최소 권한 허용·거부, pass 오류 우선순위와 token 로그 배제
- ko·en·zh configuration/checkpoint/reward 번역, 유효 session 중 language policy 변경 차단,
  consent/check-in 멱등 replay의 현재 policy projection과 cookie expiry 복구
- checkpointLocationFingerprint와 stampSnapshotId CAS, 연결 지도·marker·space 변경의
  stamp-affecting 권한·active cohort 차단
- capability·HOME quick link·published aggregate·privacy/risk approvalReference의 원자
  게시·rollback gate, FESTIVAL/HOME 우회 권한 거부와 STAMP_TOUR preview
- policyVersion-contentHash 불변, participantRetentionEndsAt 삭제, session 조기 삭제와 익명
  aggregate 검증
- 익명 session 단위 중복 보장과 사람 단위 잔여 위험 표시·LIMITED_IN_PERSON 승인 gate
- 행사장 NAT·피크 부하, QR 스캔 접근성 현장 절차와 장애 runbook
