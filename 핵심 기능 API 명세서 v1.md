# 한양대학교 ERICA 가을 축제 웹 앱 API 명세서 v1.0

## 1. 문서 정보

| 항목 | 값 |
|---|---|
| API 버전 | v1 |
| Base path | /api/v1 |
| 전송 형식 | HTTPS, JSON, UTF-8 |
| 공개 사용자 인증 | 없음 |
| 관리자 Base path | /api/v1/admin |
| 기준 시간대 | Asia/Seoul |
| 식별자 | 클라이언트가 해석하지 않는 UUID 문자열 |

이 문서는 공개 웹 앱과 관리자 운영 도구가 공유하는 v1 HTTP 계약이다. 구현 언어와
프레임워크는 이 계약에 포함하지 않는다. 현재 저장소에는 축제 서비스 scaffold와
OpenAPI artifact가 없으므로 이 문서를 규범적 계약으로 사용한다. 최초 구현 PR은 이
문서와 일치하는 OpenAPI 파일과 계약 테스트를 함께 추가하고, 그 이후에는 OpenAPI를
기계 판독 가능한 계약 원본으로 유지한다.

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

다음 기능은 제품 승인과 운영 정책이 완성될 때까지 endpoint, enum, placeholder를
공개하지 않는다.

- 스탬프 투어
- 하루 단위 분실물 안내
- 폴라로이드
- 텐텐식 술게임
- 일본어

고등학생 방문 안내는 총학생회가 승인한 콘텐츠와 노출 조건을 받은 뒤 외부 링크 또는
홈 구성 항목으로 게시한다. 승인 전에는 빈 카드나 임시 문구를 반환하지 않는다.

## 4. 공통 HTTP 규약

### 4.1 요청

- Content-Type이 필요한 요청은 application/json을 사용한다.
- 클라이언트는 X-Request-Id를 보낼 수 있다. 없으면 서버가 생성한다.
- GET과 HEAD는 상태를 변경하지 않으며 재시도 가능해야 한다.
- 관리자 생성 요청은 Idempotency-Key를 지원한다.
- 관리자 수정·삭제·게시 요청은 리소스의 ETag를 If-Match로 전달해야 한다.
- 알 수 없는 query parameter는 400 INVALID_QUERY_PARAMETER를 반환한다.
- 목록의 반복 query parameter는 허용된 필터에서만 사용한다.

### 4.2 성공 상태

| 상태 | 의미 |
|---:|---|
| 200 | 조회·수정 성공 |
| 201 | 생성 성공 |
| 204 | 응답 본문이 없는 삭제·로그아웃 성공 |

목록이 비어 있으면 404가 아니라 200과 빈 items 배열을 반환한다.

### 4.3 성공 envelope

모든 JSON 성공 응답은 data와 meta를 가진다. 204 응답에는 본문이 없다.

    {
      "data": {},
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul",
        "contentRevision": "184",
        "language": {
          "requested": "en",
          "content": "ko",
          "fallbackApplied": true
        }
      }
    }

meta 규칙:

- requestId는 모든 JSON 응답에 포함하며 응답 헤더 X-Request-Id와 동일하다.
- serverTime은 모든 JSON 응답에 포함하며 서버가 응답을 만든 시각이다.
- festivalTimeZone은 축제 회차에 속한 응답에 포함하는 IANA time zone이다.
- contentRevision은 게시 콘텐츠를 반환하는 응답에 포함하며 응답을 구성한 revision이다.
- language는 번역 가능한 콘텐츠가 있는 응답에 포함한다.
- pagination은 cursor 기반 목록 응답에 추가한다.
- 번역 가능한 응답의 Content-Language 헤더는 meta.language.content와 같다.

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
| 401 | 관리자 인증 없음 또는 만료 |
| 403 | 관리자 권한 부족 |
| 404 | 존재하지 않거나 공개되지 않은 리소스 |
| 409 | 참조, 일정, 게시 상태 또는 idempotency 충돌 |
| 412 | If-Match와 현재 revision 불일치 |
| 415 | 지원하지 않는 media type |
| 422 | 필드 값 검증 실패 |
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
- 검색, 정렬용 텍스트, CDN 및 API cache key는 실제 language를 포함한다.

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
- cursor는 filter, sort, lang, festivalId와 함께 서명하거나 검증한다.
- 다음 페이지가 없으면 nextCursor는 null이다.
- 정렬은 마지막 키에 id를 포함해 결정적으로 유지한다.

    {
      "data": {
        "items": []
      },
      "meta": {
        "pagination": {
          "nextCursor": null,
          "hasNext": false,
          "limit": 20
        }
      }
    }

공연 목록, 지도 메타데이터·마커, 외부 링크는 축제 회차별 상한이 작은 전체 목록이며
pagination을 사용하지 않는다. 서버는 운영 상한을 넘는 게시를 거부한다.

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
| mimeType | string | 서버가 검증한 MIME |
| alt | string | 콘텐츠 언어의 대체 텍스트 |
| blurHash | string, nullable | 선택적 로딩 placeholder |

### 8.2 PublicationStatus

- DRAFT
- SCHEDULED
- PUBLISHED
- ARCHIVED

공개 API는 PUBLISHED이며 공개 기간 안에 있는 revision만 반환한다.

### 8.3 OperatingStatus

- UPCOMING
- OPEN
- PAUSED
- CLOSED
- CANCELED

상태는 운영자가 명시적으로 설정한 값과 운영 시간 계산을 혼동하지 않는다.
응답은 operatingStatus와 nextStatusAt을 함께 제공할 수 있다.

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
| 16 | POST | /festivals/{festivalId}/chatbot/messages | 챗봇 질의 |

표의 path에는 공통 Base path /api/v1이 생략되어 있다.

## 10. 축제와 홈

### 10.1 GET /festivals/current

현재 공개 서비스의 canonical festivalId를 반환한다. 진행 전에도 공개가 승인된 가장
가까운 회차를 반환하며 selectionReason으로 선택 근거를 설명한다. 공개 회차가 없으면
404 FESTIVAL_NOT_PUBLISHED다.

    {
      "data": {
        "festivalId": "7ef50e0b-49c1-465f-a098-796d89f39c1a",
        "selectionReason": "LIVE",
        "resourcePath": "/api/v1/festivals/7ef50e0b-49c1-465f-a098-796d89f39c1a"
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00"
      }
    }

selectionReason:

- UPCOMING
- LIVE
- MOST_RECENT

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
        "heroMedia": null,
        "lineupCategories": [],
        "capabilities": {
          "artistSearch": false,
          "artistDetail": false,
          "artistSocialLinks": false,
          "artistTracks": false,
          "externalLinks": false,
          "chatbot": true
        },
        "updatedAt": "2030-09-01T10:00:00+09:00"
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul",
        "contentRevision": "184",
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

### 10.3 GET /festivals/{festivalId}/home

Query:

| 이름 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| lang | language | 아니요 | 응답 언어 |

    {
      "data": {
        "festival": {},
        "currentItems": [],
        "upcomingPerformances": [],
        "importantNotices": [],
        "highlightArtists": [],
        "quickLinks": [],
        "configuration": {
          "currentItemTypes": ["PERFORMANCE"],
          "chatbotEnabled": true
        }
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul",
        "contentRevision": "184",
        "language": {
          "requested": "ko",
          "content": "ko",
          "fallbackApplied": false
        }
      }
    }

- currentItems는 동시 항목을 모두 반환하는 HomeCurrentItem 배열이다. type은
  PERFORMANCE, SPACE_EVENT, CURATED이며, sourceId, title, effectiveStartsAt,
  effectiveEndsAt, status, mapMarkerId를 가진다. CURATED는 홈 구성에서 직접 관리하며
  sourceId 대신 승인된 내부 또는 외부 action을 가진다.
- 어떤 type을 현재 진행 항목에 포함할지는 승인된 home configuration의
  currentItemTypes로 정한다. API schema가 type을 지원한다는 사실만으로 공개 노출을
  승인하지 않는다.
- PERFORMANCE는 status가 LIVE일 때만, SPACE_EVENT와 CURATED는 status가 ACTIVE이고
  effectiveStartsAt <= serverTime < effectiveEndsAt일 때만 currentItems에 포함한다.
  DELAYED와 CANCELED 항목은 현재 진행 중으로 표시하지 않는다.
- PERFORMANCE 항목의 status는 PerformanceStatus다. SPACE_EVENT와 CURATED의 status는
  SCHEDULED, ACTIVE, DELAYED, COMPLETED, CANCELED 중 하나다.
- upcomingPerformances는 각 무대의 다음 공연을 포함하고 effectiveStartsAt 순으로
  정렬한다.
- 각 배열 항목은 상세 endpoint와 같은 ID를 사용한다.
- 중요 공지와 현재 공연은 동일 contentRevision으로 묶는다.
- 승인되지 않은 FUN, 스탬프, 고등학생 안내 항목은 quickLinks에 포함하지 않는다.

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
| performanceIds | UUID array | 연결 공연 |
| updatedAt | datetime | 마지막 게시 수정 |

LineupCategory는 id, code, label, order를 가진 축제 운영 데이터다. 공식 분류가 확정되지
않은 명칭을 전역 enum으로 만들지 않는다.

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

기본 정렬은 category.order, artist.order, artist.id 순이다. 검색어는 Unicode 정규화,
길이 제한, locale별 검색 인덱스를 적용한다.

artistSearch capability가 비활성화된 회차에서 q를 보내면 400
FILTER_NOT_SUPPORTED를 반환한다.

### 11.3 GET /festivals/{festivalId}/artists/{artistId}

artistDetail capability가 활성화된 회차에서만 Artist 전체 필드와 연결된 공개
Performance 요약을 반환한다. 비활성화되었거나 존재하지만 비공개인 경우 404
FEATURE_NOT_ENABLED 또는 ARTIST_NOT_FOUND를 반환한다.

socialLinks와 tracks는 각각의 capability가 활성화된 경우에만 값을 반환하며, 그렇지
않으면 빈 배열이다. 외부 링크는 HTTPS만 허용하고 rel=noopener 적용에 필요한 external
값을 함께 제공한다. 추천곡은 메타데이터와 승인된 URL만 반환하며 미디어를 API가
재배포하지 않는다.

## 12. 공연 시간표

### 12.1 Performance

StageSummary는 id, name, order, mapMarkerId를 가진다. 공연장 위치는 Stage가 소유하며
같은 무대의 여러 Performance가 하나의 공연장 marker를 공유한다.

| 필드 | 타입 | 설명 |
|---|---|---|
| id | UUID | 공연 ID |
| title | string | 공연명 |
| stage | StageSummary | 무대 |
| artists | ArtistSummary array | 출연자 |
| scheduledStartsAt | datetime | 최초 확정 시작 |
| scheduledEndsAt | datetime | 최초 확정 종료 |
| effectiveStartsAt | datetime | 현재 적용 시작 |
| effectiveEndsAt | datetime | 현재 적용 종료 |
| status | PerformanceStatus | 공연 상태 |
| statusMessage | string, nullable | 승인된 지연·취소 안내 |
| description | string, nullable | 공연 설명 |
| updatedAt | datetime | 마지막 게시 수정 |

### 12.2 GET /festivals/{festivalId}/performances

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
        "dates": [],
        "currentPerformanceIds": []
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul",
        "contentRevision": "184",
        "language": {
          "requested": "ko",
          "content": "ko",
          "fallbackApplied": false
        }
      }
    }

currentPerformanceIds는 서버 시각 기준이며 홈 currentItems 중 PERFORMANCE 항목과
동일한 판정 규칙을 사용한다.

### 12.3 GET /festivals/{festivalId}/performances/{performanceId}

Performance 전체 필드, 연결 아티스트 요약, 이전·다음 공연 ID를 반환한다. 이전·다음
공연은 같은 무대의 effective 시각을 기준으로 계산한다.

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
| operatingPeriods | array | 시작·종료 일시 목록 |
| operatingStatus | OperatingStatus | 현재 운영 상태 |
| nextStatusAt | datetime, nullable | 다음 상태 전환 예상 |
| contact | object, nullable | 공개 승인된 문의 정보 |
| media | Media array | 대표·메뉴 이미지 |
| area | MapAreaSummary, nullable | 지도 구역 |
| mapMarkerId | UUID, nullable | 안정적 마커 연결 |
| menuSections | MenuSection array | 구조화 메뉴·품목 |
| events | array | 승인된 현장 이벤트 |
| updatedAt | datetime | 마지막 게시 수정 |

SpaceEvent는 id, title, description, effectiveStartsAt, effectiveEndsAt, status와
선택적 action을 가진다. 해당 FestivalSpace의 mapMarkerId를 재사용하며 별도 장소
좌표를 만들지 않는다.

### 13.2 MenuSection과 MenuItem

MenuSection은 id, name, order, items를 가진다.

MenuItem:

| 필드 | 타입 | 설명 |
|---|---|---|
| id | UUID | 품목 ID |
| name | string | 품목명 |
| description | string, nullable | 설명 |
| price.amount | integer, nullable | 정수 금액 |
| price.currency | string | KRW |
| availability | enum | AVAILABLE, SOLD_OUT, UNAVAILABLE |
| allergens | string array | 승인된 알레르기 정보 |
| media | Media, nullable | 품목 이미지 |
| order | integer | 표시 순서 |

가격 미정·무료·현장 문의는 amount에 임의의 0을 넣지 않고 별도의 priceLabel과 null
amount로 표현한다. 이미지 메뉴판만 제공하지 않으며 구조화 텍스트를 함께 게시한다.

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

서로 다른 필터 축은 AND로 결합한다. 기본 정렬은 type, order, id 순이다.

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

MapArea는 UUID, 표시명, 설명, 표시 순서, 선택적 bounds를 가진 운영 데이터다. 학교
약칭이나 추정 영문명으로 enum을 만들지 않는다.

기획 자료에서 받은 한국어 구역 원문:

- 학생복지관 앞 민주광장
- 호수공원 이벤트존
- 호수공원 앞 피크닉존
- 예체능대학 앞 & 학술정보관 옆 주차장
- 학술정보관과 제4공학관 사이
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
| mapType | enum | ILLUSTRATION 또는 GEOGRAPHIC |
| mapVersion | string | 지도 설정과 marker 집합의 불변 버전 |
| media | Media, nullable | ILLUSTRATION일 때 필요한 지도 자산 |
| coordinateSystem | enum | NORMALIZED_CONTENT_BOUNDS 또는 WGS84 |
| contentBounds | object, nullable | 일러스트 원본 안의 실제 지도 경계 |
| defaultViewport | object | 초기 center와 scale |
| categories | array | code와 locale별 label |
| areas | MapArea array | 구역 |
| filterConfiguration | object | 선택 수와 축 결합 규칙 |
| updatedAt | datetime | 게시 시각 |

filterConfiguration은 selectionMode의 SINGLE 또는 MULTIPLE, sameAxisOperator의
ANY 또는 ALL, crossAxisOperator의 AND 또는 OR를 반환한다. 운영 결정 전에는 특정
조합을 코드 기본값으로 고정하지 않는다.

ILLUSTRATION은 coordinateSystem NORMALIZED_CONTENT_BOUNDS를 사용하고 contentBounds에
x, y, width, height 원본 pixel을 제공한다. 지도 파일의 crop, 투명 여백, 원본 크기가
바뀌면 새 mapVersion을 발행하고 모든 좌표를 검증한 뒤 하나의 revision으로 게시한다.

GEOGRAPHIC은 coordinateSystem WGS84를 사용하고 media와 contentBounds를 요구하지
않는다. 이 선택이 현재 위치 수집이나 길찾기 제공을 뜻하지 않으며, 해당 기능은 별도
제품 승인과 개인정보 검토 없이는 활성화하지 않는다.

### 14.4 MapMarker

| 필드 | 타입 | 설명 |
|---|---|---|
| id | UUID | 안정적 marker ID |
| mapVersion | string | 지도 자산 버전 |
| category | category code | 15개 중 하나 |
| areaId | UUID, nullable | 구역 |
| name | string | 표시명 |
| description | string, nullable | 안내 |
| position | object | coordinateSystem에 맞는 위치 variant |
| linkedSpaceId | UUID, nullable | 연결된 부스·주점·플리마켓 |
| stageId | UUID, nullable | 연결된 공연장 |
| aliases | string array | 검색용 승인 별칭 |
| accessibilityText | string | 지도 없이 위치를 찾는 설명 |

NORMALIZED_CONTENT_BOUNDS position은 xRatio, yRatio, anchor를 가지며 비율 값은
0 이상 1 이하이고 전체 이미지가 아니라 contentBounds 기준이다. WGS84 position은
latitude와 longitude를 가진다. 한 marker는 현재 coordinateSystem에 맞는 variant
하나만 가진다. marker.mapVersion과 MapConfiguration.mapVersion은 반드시 일치한다.

FestivalSpace와 MapMarker의 연결은 각각 최대 하나다. 공연장 marker는 Stage와
연결되고 같은 Stage의 여러 Performance가 공유한다. linkedSpaceId와 stageId는 동시에
설정할 수 없다. 연결 대상이 없는 화장실, 쓰레기통 같은 시설 marker는 허용한다.

### 14.5 GET /festivals/{festivalId}/map

현재 게시된 MapConfiguration을 반환한다. 지도 자산과 marker revision이 불일치하면
부분 응답을 반환하지 않고 직전의 일관된 게시 revision을 제공한다.

### 14.6 GET /festivals/{festivalId}/map/markers

Query:

| 이름 | 타입 | 설명 |
|---|---|---|
| areaId | UUID, 반복 가능 | 허용 개수와 결합은 지도 설정을 따름 |
| category | category code, 반복 가능 | 허용 개수와 결합은 지도 설정을 따름 |
| q | string | 표시명과 alias 검색 |
| lang | language | 응답 언어 |

반복 parameter 허용 수와 같은 축·다른 축의 결합은 현재 MapConfiguration의
filterConfiguration을 따른다. SINGLE인데 같은 parameter를 둘 이상 보내면 400
FILTER_SELECTION_LIMIT이다. 결과는 category 표시 순서, marker.order, marker.id
순으로 정렬한다.

### 14.7 GET /festivals/{festivalId}/map/markers/{markerId}

MapMarker 전체 필드와 연결된 Space 또는 Stage 요약을 반환한다. Stage 요약에서 같은
무대의 공개 Performance로 이동할 수 있다. accessibilityText는 항상 제공한다.

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
| emergency | boolean | 긴급 공지 |
| sourceName | string, nullable | 공식 출처 |
| externalUrl | HTTPS URL, nullable | 승인된 외부 링크 |
| visibleFrom | datetime | 노출 시작 |
| visibleUntil | datetime, nullable | 노출 종료 |
| publishedAt | datetime | 게시 시각 |
| updatedAt | datetime | 마지막 수정 |

### 15.1 GET /festivals/{festivalId}/notices

Query:

| 이름 | 타입 | 설명 |
|---|---|---|
| type | NoticeType, 반복 가능 | 공지 분류 |
| important | boolean | 중요 공지만 조회 |
| cursor | string | 다음 페이지 |
| limit | integer | 1~100 |
| lang | language | 응답 언어 |

긴급 공지, 중요 공지, publishedAt 내림차순, id 순으로 정렬한다.

### 15.2 GET /festivals/{festivalId}/notices/{noticeId}

Notice 전체 필드를 반환한다. contentType별 필수값:

- INTERNAL: body 필수
- EXTERNAL: externalUrl 필수
- HYBRID: body와 externalUrl 모두 필수

만료되거나 게시 취소된 공지는 404 NOTICE_NOT_FOUND를 반환한다.

## 16. 외부 링크

ExternalLinkType:

- OFFICIAL_PAGE
- OFFICIAL_SNS
- VISITOR_GUIDE
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
채널만 게시하고, 고등학생 방문 안내는 승인 자료를 받은 경우에만 VISITOR_GUIDE로
게시한다.

## 17. 챗봇

### POST /festivals/{festivalId}/chatbot/messages

공개·비로그인 endpoint다. 계정이나 영구 사용자 프로필을 만들지 않는다.

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

- message는 trim 후 1~500자다.
- conversation은 선택값이며 최대 6개 turn, turn당 최대 1,000자다.
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
            "markerId": "ae3ed717-7070-48c1-9666-e3312a449ac1"
          }
        ],
        "safety": {
          "grounded": true
        }
      },
      "meta": {
        "requestId": "8b9ad0af-2d86-4469-883c-af86a3fbcc6a",
        "serverTime": "2030-09-18T18:05:00+09:00",
        "festivalTimeZone": "Asia/Seoul",
        "contentRevision": "184",
        "language": {
          "requested": "ko",
          "content": "ko",
          "fallbackApplied": false
        }
      }
    }

ActionType:

- OPEN_MAP
- OPEN_TIMETABLE
- OPEN_NOTICE
- OPEN_FESTIVAL_SPACE
- NONE

OPEN_MAP은 markerId, UUID areaIds 또는 category code만 사용한다. 추정한 학교 영문
구역 enum을 반환하지 않는다.

## 18. 관리자 API

### 18.1 전체 엔드포인트

관리자 API의 공통 Base path는 /api/v1/admin이다. 아래 표의 path에는 /api/v1이
생략되어 있다. 최소 권한은 해당 요청에 필요한 가장 낮은 역할이며, 리소스 상태와
작업 종류에 따라 더 높은 권한이 필요할 수 있다.

| 분류 | Method | Path | 최소 권한 | 설명 |
|---|---|---|---|---|
| 인증 | POST | /admin/auth/login | 없음 | 관리자 로그인 |
| 인증 | POST | /admin/auth/refresh | refresh session | access token 갱신 |
| 인증 | POST | /admin/auth/logout | refresh session | 현재 session 폐기 |
| 인증 | GET | /admin/auth/me | 인증됨 | 현재 관리자와 권한 조회 |
| 축제 회차 | GET | /admin/festivals | VIEWER | 축제 회차 목록 |
| 축제 회차 | POST | /admin/festivals | ADMIN | 축제 회차 생성 |
| 축제 회차 | GET | /admin/festivals/{festivalId} | VIEWER | 축제 회차 상세 |
| 축제 회차 | PATCH | /admin/festivals/{festivalId} | ADMIN | 축제 회차 수정 |
| 축제 회차 | POST | /admin/festivals/{festivalId}/activate | ADMIN | 공개 서비스 회차 활성화 |
| 축제 회차 | POST | /admin/festivals/{festivalId}/deactivate | ADMIN | 공개 서비스 회차 비활성화 |
| 홈 구성 | GET | /admin/festivals/{festivalId}/home-configuration | VIEWER | 홈 구성 조회 |
| 홈 구성 | PUT | /admin/festivals/{festivalId}/home-configuration | EDITOR | 홈 구성 전체 갱신 |
| 지도 구성 | GET | /admin/festivals/{festivalId}/map | VIEWER | 지도 구성 조회 |
| 지도 구성 | PUT | /admin/festivals/{festivalId}/map | EDITOR | 지도 구성 전체 갱신 |
| 콘텐츠 | GET | /admin/festivals/{festivalId}/{resources} | VIEWER | 리소스 목록 |
| 콘텐츠 | POST | /admin/festivals/{festivalId}/{resources} | EDITOR | draft 리소스 생성 |
| 콘텐츠 | GET | /admin/festivals/{festivalId}/{resources}/{resourceId} | VIEWER | 리소스 상세 |
| 콘텐츠 | PATCH | /admin/festivals/{festivalId}/{resources}/{resourceId} | EDITOR | 리소스 수정 |
| 콘텐츠 | DELETE | /admin/festivals/{festivalId}/{resources}/{resourceId} | EDITOR | 참조되지 않은 draft 삭제 |
| 메뉴 | POST | /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections | EDITOR | 메뉴 section 생성 |
| 메뉴 | PATCH | /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId} | EDITOR | 메뉴 section 수정 |
| 메뉴 | DELETE | /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId} | EDITOR | 메뉴 section 삭제 |
| 메뉴 | POST | /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId}/items | EDITOR | 메뉴 item 생성 |
| 메뉴 | PATCH | /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId}/items/{itemId} | EDITOR | 메뉴 item 수정 |
| 메뉴 | DELETE | /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId}/items/{itemId} | EDITOR | 메뉴 item 삭제 |
| 게시 | POST | /admin/festivals/{festivalId}/{resources}/{resourceId}/schedule | PUBLISHER | 예약 게시 |
| 게시 | POST | /admin/festivals/{festivalId}/{resources}/{resourceId}/publish | PUBLISHER | 즉시 게시 |
| 게시 | POST | /admin/festivals/{festivalId}/{resources}/{resourceId}/unpublish | PUBLISHER | 게시 취소 |
| 게시 | POST | /admin/festivals/{festivalId}/{resources}/{resourceId}/archive | PUBLISHER | 리소스 보관 |
| 게시 | POST | /admin/festivals/{festivalId}/{resources}/{resourceId}/restore | ADMIN | 보관 리소스 복원 |
| 게시 배치 | POST | /admin/festivals/{festivalId}/publication-batches | PUBLISHER | 원자 게시 batch 생성 |
| 게시 배치 | GET | /admin/festivals/{festivalId}/publication-batches/{batchId} | VIEWER | batch 상태 조회 |
| 게시 배치 | POST | /admin/festivals/{festivalId}/publication-batches/{batchId}/validate | PUBLISHER | batch 정합성 검증 |
| 게시 배치 | POST | /admin/festivals/{festivalId}/publication-batches/{batchId}/publish | PUBLISHER | batch 원자 게시 |
| 게시 배치 | POST | /admin/festivals/{festivalId}/publication-batches/{batchId}/rollback | PUBLISHER | 이전 revision 재게시 |
| 감사 | GET | /admin/audit-logs | ADMIN | 감사 로그 목록 |
| 감사 | GET | /admin/audit-logs/{auditLogId} | ADMIN | 감사 로그 상세 |
| 미디어 | POST | /admin/media/upload-intents | EDITOR | 업로드 intent 발급 |
| 미디어 | POST | /admin/media/{mediaId}/complete | EDITOR | 업로드 검증 완료 |
| 미디어 | GET | /admin/media/{mediaId} | VIEWER | 미디어 상태 조회 |
| 미디어 | DELETE | /admin/media/{mediaId} | EDITOR | 미참조 미디어 삭제 |
| 관리자 계정 | GET | /admin/accounts | ADMIN | 관리자 계정 목록 |
| 관리자 계정 | POST | /admin/accounts | ADMIN | 관리자 초대 |
| 관리자 계정 | GET | /admin/accounts/{accountId} | ADMIN | 관리자 계정 상세 |
| 관리자 계정 | PATCH | /admin/accounts/{accountId} | ADMIN | 역할·활성 상태 수정 |
| 관리자 계정 | POST | /admin/accounts/{accountId}/revoke-sessions | ADMIN | 계정 session 강제 폐기 |

resources에 허용되는 값:

- lineup-categories
- artists
- stages
- performances
- spaces
- map-areas
- map-markers
- notices
- external-links

표에 사용된 placeholder는 위 허용 목록의 값으로만 치환한다. 임의 리소스 이름은
허용하지 않는다. 인증·인가, 동시성, 상태 전이와 요청·응답의 세부 규칙은 아래 절을
따른다.

### 18.2 인증과 권한

#### 인증 endpoint

| Method | Path | 설명 |
|---|---|---|
| POST | /admin/auth/login | 관리자 로그인 |
| POST | /admin/auth/refresh | 세션 갱신 |
| POST | /admin/auth/logout | 현재 refresh session 폐기 |
| GET | /admin/auth/me | 현재 관리자와 권한 |

표의 path에는 /api/v1이 생략되어 있다.

인증 규칙:

- access token은 짧은 수명의 서명된 JWT이며 Authorization: Bearer로 전달한다.
- refresh token은 Secure, HttpOnly, SameSite=Strict cookie로만 전달한다.
- refresh token rotation과 재사용 탐지를 적용한다.
- access token을 브라우저 영구 저장소에 저장하지 않는다.
- 공개 관리자 회원가입 endpoint는 제공하지 않는다.
- 로그인 실패는 IP와 계정 기준으로 제한하고 잠금·복구 이벤트를 감사 기록한다.
- 로그아웃, 비밀번호 변경, 권한 회수 시 관련 refresh session을 폐기한다.
- 관리자 웹의 cookie 사용 요청은 CSRF 방어를 적용한다.

Role:

| 역할 | 권한 |
|---|---|
| VIEWER | 관리자 데이터 조회·미리보기 |
| EDITOR | draft 생성·수정, 미디어 업로드 |
| PUBLISHER | 예약·게시·게시 취소·긴급 공지 |
| ADMIN | 축제 활성화, 관리자 계정·권한·세션 관리 |

서버는 화면 노출 여부와 무관하게 모든 관리자 endpoint에서 역할을 검사한다.

## 19. 관리자 콘텐츠 API

### 19.1 공통 CRUD 패턴

    GET    /admin/festivals/{festivalId}/{resources}
    POST   /admin/festivals/{festivalId}/{resources}
    GET    /admin/festivals/{festivalId}/{resources}/{resourceId}
    PATCH  /admin/festivals/{festivalId}/{resources}/{resourceId}
    DELETE /admin/festivals/{festivalId}/{resources}/{resourceId}

지원 resources:

- lineup-categories
- artists
- stages
- performances
- spaces
- map-areas
- map-markers
- notices
- external-links

Festival 자체 관리:

    GET   /admin/festivals
    POST  /admin/festivals
    GET   /admin/festivals/{festivalId}
    PATCH /admin/festivals/{festivalId}
    POST  /admin/festivals/{festivalId}/activate
    POST  /admin/festivals/{festivalId}/deactivate

activate와 deactivate는 ADMIN만 실행한다. 활성 회차는 public festivals/current의
선택 대상이며, 운영 환경에는 동시에 하나의 활성 회차만 둔다. activate는 필수 설정과
게시 revision 검증을 통과해야 한다.

activate body:

    {
      "onExistingActive": "REJECT"
    }

onExistingActive는 REJECT 또는 DEACTIVATE_EXISTING이다. 기본값은 REJECT다.
DEACTIVATE_EXISTING은 기존 회차 비활성화와 새 회차 활성화를 원자적으로 수행한다.

단일 구성 리소스:

    GET /admin/festivals/{festivalId}/home-configuration
    PUT /admin/festivals/{festivalId}/home-configuration
    GET /admin/festivals/{festivalId}/map
    PUT /admin/festivals/{festivalId}/map

메뉴 section과 item은 space aggregate 안에서 갱신한다. 독립 수정이 필요하면 다음
하위 경로를 사용하며 다른 최상위 ID를 만들지 않는다.

    POST   /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections
    PATCH  /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId}
    DELETE /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId}
    POST   /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId}/items
    PATCH  /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId}/items/{itemId}
    DELETE /admin/festivals/{festivalId}/spaces/{spaceId}/menu-sections/{sectionId}/items/{itemId}

### 19.2 쓰기 요청

- POST는 Idempotency-Key를 필수로 받는다.
- PATCH는 JSON Merge Patch 형식과 application/merge-patch+json을 사용한다.
- PUT은 단일 구성 전체를 대체하며 누락 필드를 삭제로 해석한다.
- PATCH, PUT, DELETE와 상태 전이는 If-Match를 필수로 받는다.
- 성공한 생성은 201과 Location을 반환한다.
- 성공한 수정은 새 ETag를 반환한다.
- 이미 처리한 Idempotency-Key와 다른 body가 오면 409 IDEMPOTENCY_KEY_REUSED다.
- 참조 중인 draft 삭제는 409 RESOURCE_IN_USE다.
- 게시된 리소스는 물리 삭제하지 않고 archive한다.

### 19.3 게시 endpoint와 상태 전이

    POST /admin/festivals/{festivalId}/{resources}/{resourceId}/schedule
    POST /admin/festivals/{festivalId}/{resources}/{resourceId}/publish
    POST /admin/festivals/{festivalId}/{resources}/{resourceId}/unpublish
    POST /admin/festivals/{festivalId}/{resources}/{resourceId}/archive
    POST /admin/festivals/{festivalId}/{resources}/{resourceId}/restore

schedule body:

    {
      "publishAt": "2030-09-01T10:00:00+09:00"
    }

상태 전이:

- DRAFT → SCHEDULED: PUBLISHER 이상, 게시 검증 통과
- DRAFT 또는 SCHEDULED → PUBLISHED: PUBLISHER 이상
- PUBLISHED → DRAFT: 게시 취소, 사유 필수
- DRAFT, SCHEDULED 또는 PUBLISHED → ARCHIVED: 권한과 참조 검증
- ARCHIVED → DRAFT: ADMIN, 복원 사유 필수

게시 검증:

- festivalId 참조 일치
- 필수 번역과 fallback 정책
- 일시와 축제 기간
- 동일 무대 공연 겹침
- 외부 URL과 미디어 상태
- 지도 mapVersion, contentBounds, 좌표 범위
- 중복 티켓 장소와 marker 연결
- linked resource의 게시 가능 상태
- rich text XSS 정화

게시·게시 취소가 성공하면 contentRevision을 증가시키고 관련 API, CDN,
애플리케이션 cache를 무효화한다. 여러 리소스가 함께 바뀌는 시간표와 지도는 하나의
publication batch로 원자적으로 게시한다.

publication batch endpoint:

    POST /admin/festivals/{festivalId}/publication-batches
    GET  /admin/festivals/{festivalId}/publication-batches/{batchId}
    POST /admin/festivals/{festivalId}/publication-batches/{batchId}/validate
    POST /admin/festivals/{festivalId}/publication-batches/{batchId}/publish
    POST /admin/festivals/{festivalId}/publication-batches/{batchId}/rollback

생성 body는 포함할 resourceType, resourceId, expectedRevision 목록과 batch 목적을
가진다. 생성과 상태 전이 POST는 Idempotency-Key를, validate·publish·rollback은
If-Match를 필수로 받는다.

PublicationBatchStatus:

- DRAFT
- VALIDATED
- PUBLISHED
- FAILED
- ROLLED_BACK

validate는 개별 게시 검증과 리소스 간 참조·시간표·지도 정합성을 모두 확인하고 오류
목록을 반환한다. publish는 VALIDATED batch의 모든 리소스와 단일 contentRevision을
하나의 transaction 또는 동등한 원자적 방식으로 공개한다. 일부만 공개할 수 없다.
rollback은 직전의 검증된 revision을 새 revision으로 재게시하며 대상 revision, 사유,
실행자를 감사 로그에 남긴다.

### 19.4 감사 로그

    GET /admin/audit-logs
    GET /admin/audit-logs/{auditLogId}

감사 레코드:

- actorId와 역할
- action
- festivalId, resourceType, resourceId
- 이전·이후 revision과 민감값을 제거한 변경 요약
- requestId, IP, user agent
- 발생 시각
- 성공·실패와 실패 code

감사 로그는 관리자도 수정·삭제할 수 없다. 보관 기간과 접근 역할은 운영 보안 정책으로
관리한다.

## 20. 미디어 업로드

| Method | Path | 설명 |
|---|---|---|
| POST | /admin/media/upload-intents | 업로드 intent 발급 |
| POST | /admin/media/{mediaId}/complete | 업로드 검증 완료 |
| GET | /admin/media/{mediaId} | 상태 조회 |
| DELETE | /admin/media/{mediaId} | 미참조 미디어 삭제 |

upload intent request:

    {
      "fileName": "map-image.png",
      "mimeType": "image/png",
      "size": 482103,
      "purpose": "MAP",
      "altTranslations": {
        "ko": "축제 지도"
      }
    }

MediaStatus:

- PENDING_UPLOAD
- PROCESSING
- READY
- REJECTED
- DELETED

서버 규칙:

- 확장자, 선언 MIME, 실제 MIME과 디코딩 결과를 모두 검증한다.
- purpose별 최대 크기와 pixel 수를 제한한다.
- 악성 파일 검사와 불필요한 EXIF 제거를 수행한다.
- READY인 미디어만 게시 콘텐츠가 참조할 수 있다.
- 참조 중인 미디어 삭제는 409 RESOURCE_IN_USE다.
- 지도 원본 교체는 새 mapVersion과 좌표 검증을 요구한다.
- 저작권·사용 권한·출처 메타데이터를 관리자 입력에 보존한다.

## 21. 관리자 계정

ADMIN만 사용할 수 있다.

    GET   /admin/accounts
    POST  /admin/accounts
    GET   /admin/accounts/{accountId}
    PATCH /admin/accounts/{accountId}
    POST  /admin/accounts/{accountId}/revoke-sessions

- 계정 생성은 초대 기반이며 임시 비밀번호를 응답에 반환하지 않는다.
- 역할 변경과 비활성화는 감사 로그에 남긴다.
- 마지막 활성 ADMIN을 비활성화하거나 강등할 수 없다.
- 계정 목록과 감사 로그는 필요한 개인정보만 반환한다.

## 22. Cache와 조건부 요청

공개 GET은 ETag와 Last-Modified를 제공하고 If-None-Match,
If-Modified-Since에 304로 응답한다.

기본 Cache-Control:

| 리소스 | 정책 |
|---|---|
| festivals/current | public, max-age=30 |
| festival, artists, spaces | public, max-age=60, stale-while-revalidate=300 |
| home, performances, notices | public, max-age=15, stale-while-revalidate=30 |
| map metadata, markers | public, max-age=60, stale-while-revalidate=300 |
| versioned media | public, max-age=31536000, immutable |
| chatbot, admin, auth | no-store |

- cache key에는 festivalId, contentRevision, language, filter, sort, cursor와 mapVersion 등
  응답을 바꾸는 값을 포함한다.
- 언어 협상 응답은 Vary: Accept-Language를 포함한다.
- 긴급 공지 게시·회수와 시간표 변경은 TTL 만료를 기다리지 않고 purge한다.
- stale 응답을 제공하면 Warning과 데이터의 updatedAt을 통해 오래된 상태를 알린다.
- 안전과 관련된 긴급 공지는 무기한 stale로 제공하지 않는다.

## 23. 요청 제한

기본 제한은 배포 환경에서 더 엄격하게 조정할 수 있다.

| 경계 | 기본 제한 |
|---|---|
| 공개 GET | IP당 분당 120회 |
| 챗봇 | IP당 분당 20회 |
| 관리자 로그인 | IP와 계정당 15분에 5회 |
| 관리자 쓰기 | 관리자당 분당 60회 |

429 응답은 Retry-After와 RateLimit-Limit, RateLimit-Remaining,
RateLimit-Reset을 제공한다. 신뢰 가능한 proxy chain을 명시하고 임의의
X-Forwarded-For 값을 그대로 IP로 사용하지 않는다.

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
| UNSUPPORTED_LANGUAGE | 400 | 지원하지 않는 명시적 언어 |
| FILTER_NOT_SUPPORTED | 400 | 비활성 capability의 필터 사용 |
| FILTER_SELECTION_LIMIT | 400 | 지도 설정의 선택 개수 초과 |
| AUTHENTICATION_REQUIRED | 401 | 관리자 인증 필요 |
| TOKEN_EXPIRED | 401 | access token 만료 |
| PERMISSION_DENIED | 403 | 역할 부족 |
| FESTIVAL_NOT_PUBLISHED | 404 | 공개 축제 회차 없음 |
| RESOURCE_NOT_FOUND | 404 | 리소스 없음 또는 비공개 |
| ARTIST_NOT_FOUND | 404 | 공개 아티스트 없음 |
| PERFORMANCE_NOT_FOUND | 404 | 공개 공연 없음 |
| SPACE_NOT_FOUND | 404 | 공개 공간 없음 |
| MARKER_NOT_FOUND | 404 | 공개 marker 없음 |
| NOTICE_NOT_FOUND | 404 | 공개 공지 없음 |
| FEATURE_NOT_ENABLED | 404 | 해당 회차에서 공개하지 않는 capability |
| RESOURCE_IN_USE | 409 | 참조 중인 리소스 |
| SCHEDULE_CONFLICT | 409 | 같은 무대의 공연 시간 중복 |
| PUBLICATION_CONFLICT | 409 | 게시 상태·batch 충돌 |
| PUBLICATION_BATCH_INVALID | 409 | batch 검증 실패 또는 검증 후 변경 |
| IDEMPOTENCY_KEY_REUSED | 409 | 다른 body로 key 재사용 |
| MAP_VERSION_MISMATCH | 409 | 지도 자산과 marker 버전 불일치 |
| REVISION_MISMATCH | 412 | If-Match 불일치 |
| UNSUPPORTED_MEDIA_TYPE | 415 | 지원하지 않는 MIME |
| VALIDATION_ERROR | 422 | 필드 검증 실패 |
| RATE_LIMITED | 429 | 요청 제한 초과 |
| INTERNAL_ERROR | 500 | 안전하게 복구되지 않은 오류 |
| SERVICE_UNAVAILABLE | 503 | 일시적 의존성 장애 |

같은 원인에는 endpoint별로 다른 code를 만들지 않는다. 새 code를 추가할 때 OpenAPI,
클라이언트 fallback 처리, 관측 대시보드와 계약 테스트를 함께 갱신한다.

## 28. 출시 계약 검증

API 변경 PR은 영향받는 항목을 자동화한다.

- OpenAPI 문법과 example validation
- 공개·관리자 인증 경계 테스트
- 이전 v1 consumer 호환 테스트
- 요청·응답 schema와 RFC 9457 오류 계약 테스트
- locale 선택, fallback, cache key 분리
- Asia/Seoul 경계와 동시 공연·지연·취소 테스트
- cursor 안정성, 정렬, 최대 limit
- 지도 좌표 범위, mapVersion, 티켓 marker 중복 방지
- 게시 상태 전이, If-Match, Idempotency-Key
- 게시 batch 원자성, cache purge, rollback
- 관리자 역할별 허용·거부
- 업로드 MIME·크기·디코딩·악성 파일 검사
- rate limit과 개인정보·비밀 로그 배제
- 백업 복원과 행사 피크 트래픽 부하 검증

출시 전에는 실제 운영 데이터의 축제명, 날짜, 무대, 출연진, 운영 시간, 메뉴, 지도
자산·좌표, 공지, 링크, 번역을 승인 자료와 대조한다. 예시 값은 운영 데이터로 자동
승격하지 않는다.
