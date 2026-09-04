- 기준은 **React/Next.js 계열 프론트 + Spring Boot 백엔드 REST API**를 가정
- 일반 사용자는 **로그인 없이 이용**하는 구조
- 펜타포트처럼 일반 지도 API보다는 **축제 전용 일러스트 지도 위에 마커를 표시하는 방식**을 기준으로 설계

# 학교 축제 웹앱 API 명세서

## 0. 기본 정책

|항목|내용|
|---|---|
|Base URL|`/api/v1`|
|데이터 형식|JSON|
|인증|일반 사용자 API 인증 없음|
|관리자|관리자 API만 별도 JWT 인증|
|기본 언어|`ko`|
|지원 언어|`ko`, `en`, `zh`|
|후순위|`ja`|
|날짜 형식|`YYYY-MM-DD`|
|시간 형식|`HH:mm`|
|DateTime|ISO 8601|

공통 응답 구조는 아래처럼 통일하는 걸 추천

```json
{
  "success": true,
  "data": {},
  "message": null
}
```

에러:

```json
{
  "success": false,
  "data": null,
  "message": "존재하지 않는 공연입니다.",
  "errorCode": "PERFORMANCE_NOT_FOUND"
}
```

---

# 1. 전체 화면 구조

현재 확정된 Bottom Navigation 기준이다.

|메뉴|API Domain|주요 기능|
|---|---|---|
|홈|`/home`|현재 공연, 주요 공지, FAQ, 챗봇, 언어 변경, 공식 SNS, FUN/스탬프 진입|
|라인업|`/artists`|날짜별 메인 아티스트 및 콘테스트 참여팀|
|타임테이블|`/performances`|날짜·무대별 공연 일정|
|부스 & 마켓|`/festival-spaces`|부스 / 주점 / 플리마켓|
|지도|`/map`|축제 지도, 카테고리 필터, 구역 필터|

## 홈에서 접근하는 기능

홈은 축제의 주요 보조 기능으로 진입하는 허브 역할을 한다.

|기능|처리 방식|API|
|---|---|---|
|공지사항|앱 내부 공지 목록/상세 제공. 필요 시 총학생회 공식 게시물 또는 페이지 원문 링크 제공|`/notices`|
|FAQ|총학생회에서 제공하는 실제 FAQ 페이지 또는 공식 안내 페이지로 이동|`/external-links?type=FAQ`|
|챗봇|앱 내부 축제 안내 챗봇|`/chatbot/messages`|
|언어 변경|한국어 / 영어 / 중국어|`lang` Query Parameter|
|공식 SNS|총학생회 Instagram, YouTube 등 공식 외부 채널로 이동|`/external-links?type=OFFICIAL_SNS`|
|스탬프 투어|총학생회 협의 후 적용|`/stamp/...`|
|FUN|후순위 기능|추후 결정|

※ 내 일정 및 관심 공연 저장 기능은 제공하지 않는다.

---

# 2. 다국어 처리

API마다 `lang` Query Parameter를 받는 방식으로 통일하는 걸 추천

```
GET /api/v1/artists?lang=ko
GET /api/v1/artists?lang=en
GET /api/v1/artists?lang=zh
```

### lang ENUM

|값|언어|
|---|---|
|`ko`|한국어|
|`en`|English|
|`zh`|中文|
|`ja`|日本語 / 후순위|

`lang`이 없으면 `ko`.

DB에서는 예를 들어:

```
artist
artist_translation
```

방식으로 번역 테이블을 분리하는 게 좋음.

---

# 3. HOME

## 3-1. 홈 초기 데이터 조회

### `GET /api/v1/home`

홈에 필요한 데이터를 한 번에 내려주는 Aggregate API

홈 접속할 때 API를 여러 번 호출하는 것보다 이 방식이 편함

### Request

```
GET /api/v1/home?lang=ko
```

### Response

```json
{
  "success": true,
  "data": {
    "festival": {
      "name": "2026 한양대학교 ERICA 축제",
      "startDate": "2026-09-23",
      "endDate": "2026-09-25",
      "heroImageUrl": "https://..."
    },

    "currentPerformance": {
      "id": 13,
      "title": "학생 공연",
      "stageName": "대운동장",
      "startTime": "18:00",
      "endTime": "18:40"
    },

    "nextPerformance": {
      "id": 14,
      "title": "Artist A",
      "stageName": "대운동장",
      "startTime": "19:00",
      "endTime": "19:40"
    },

    "highlightArtists": [
      {
        "id": 1,
        "name": "Artist A",
        "imageUrl": "https://..."
      }
    ],

    "importantNotice": {
      "id": 8,
      "title": "티켓 부스 운영 시간 안내"
    },

    "quickLinks": [
      {
        "type": "FAQ",
        "title": "FAQ",
        "url": "https://..."
      },
      {
        "type": "CHATBOT",
        "title": "챗봇",
        "url": null
      }
    ],

    "officialSns": [
      {
        "platform": "INSTAGRAM",
        "title": "총학생회 Instagram",
        "url": "<https://instagram.com/>..."
      },
      {
        "platform": "YOUTUBE",
        "title": "총학생회 YouTube",
        "url": "<https://youtube.com/>..."
      }
    ]
  }
}
```

### 프론트 역할

`currentPerformance`가 없으면:

> 현재 진행 중인 공연이 없습니다.

처리.

현재/다음 공연 판정 자체는 서버가 해줘도 되고 프론트가 timetable 데이터를 가지고 계산해도 되는데, **홈에서는 서버에서 계산해서 보내는 걸 추천**

---

# 4. LINE-UP

펜타포트처럼 날짜별 조회가 가능하고, 메인 아티스트와 콘테스트팀을 구분

## 4-1. 라인업 목록

### `GET /api/v1/artists`

### Query

|이름|타입|필수|예시|
|---|---|---|---|
|`date`|Date|X|`2026-09-24`|
|`category`|Enum|X|`MAIN_ARTIST`|
|`keyword`|String|X|`QWER`|
|`lang`|Enum|X|`ko`|

### category

```
MAIN_ARTIST
CONTEST_TEAM
```

### Request

```
GET /api/v1/artists?date=2026-09-24&category=MAIN_ARTIST&lang=ko
```

### Response

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "name": "Artist A",
      "category": "MAIN_ARTIST",
      "date": "2026-09-24",
      "imageUrl": "https://...",
      "description": "대한민국의 밴드...",
      "sns": {
        "instagram": "<https://instagram.com/>...",
        "youtube": "<https://youtube.com/>..."
      },
      "representativeSongs": [
        "Song A",
        "Song B",
        "Song C"
      ]
    }
  ]
}
```

---

## 4-2. 아티스트 상세

카드 확장 또는 추후 상세 페이지가 필요할 경우 사용

### `GET /api/v1/artists/{artistId}`

```
GET /api/v1/artists/1?lang=ko
```

### Response

```json
{
  "success": true,
  "data": {
    "id": 1,
    "name": "Artist A",
    "category": "MAIN_ARTIST",
    "date": "2026-09-24",
    "imageUrl": "https://...",
    "description": "아티스트에 대한 간단한 소개입니다.",
    "sns": {
      "instagram": "https://...",
      "youtube": "https://..."
    },
    "representativeSongs": [
      "Song A",
      "Song B",
      "Song C"
    ]
  }
}
```

현재 기획대로 **라인업 카드에서 정보를 전부 보여준다면 이 API는 필수는 아님**

---

# 5. TIMETABLE

펜타포트 앱처럼

**날짜 → 무대 → 시간**

구조로 내려주면 됨

## 5-1. 타임테이블 조회

### `GET /api/v1/performances`

### Query

|이름|타입|필수|
|---|---|---|
|`date`|Date|O|
|`stageId`|Long|X|
|`lang`|Enum|X|

### Request

```
GET /api/v1/performances?date=2026-09-24&lang=ko
```

### Response

```json
{
  "success": true,
  "data": {
    "date": "2026-09-24",
    "stages": [
      {
        "id": 1,
        "name": "MAIN STAGE",
        "performances": [
          {
            "id": 101,
            "title": "학생 콘테스트",
            "startTime": "17:00",
            "endTime": "18:00",
            "description": "축제 콘테스트 본선 공연입니다."
          },
          {
            "id": 102,
            "title": "Artist A",
            "startTime": "18:00",
            "endTime": "18:40",
            "description": "Artist A 공연입니다."
          }
        ]
      }
    ]
  }
}
```

---

## 5-2. 공연 간단 정보

사용자가 타임테이블 공연을 눌렀을 때 **별도 페이지가 아니라 Bottom Sheet**가 올라오는 현재 기획

### `GET /api/v1/performances/{performanceId}`

```json
{
  "success": true,
  "data": {
    "id": 102,
    "title": "Artist A",
    "date": "2026-09-24",
    "startTime": "18:00",
    "endTime": "18:40",
    "stageName": "MAIN STAGE",
    "description": "Artist A 공연입니다."
  }
}
```

### 중요한 점

**현재 시간 선은 API가 필요 없음**

서버가:

```json
"startTime": "18:00",
"endTime": "18:40"
```

만 주면 프론트가 현재 시간을 기준으로

```
현재 시간선
현재 공연 강조
```

를 처리하면 됨

---

# 6. 부스 & 마켓

하나의 메뉴 아래:

```
부스
주점
플리마켓
```

세 가지로 구분

API도 하나로 통일하는 걸 추천.

## 6-1. 목록 조회

### `GET /api/v1/festival-spaces`

### Query

|이름|설명|
|---|---|
|`type`|공간 유형|
|`areaId`|구역|
|`keyword`|검색|
|`lang`|언어|

### type

```
BOOTH
PUB
FLEA_MARKET
```

### Request

```
GET /api/v1/festival-spaces?type=BOOTH&lang=ko
```

---

## 부스 Response 예시

```json
{
  "id": 21,
  "type": "BOOTH",
  "name": "게임 체험 부스",
  "operator": "총학생회",
  "area": {
    "id": "ENG4_ACADEMIC",
    "name": "학정과 4공 사이"
  },
  "imageUrl": "https://...",
  "description": "게임과 다양한 이벤트를 진행하는 부스입니다.",
  "mapMarkerId": 32
}
```

---

## 주점 Response 예시

```json
{
  "id": 31,
  "type": "PUB",
  "name": "미디어학과 포차",
  "operator": "미디어학과 학생회",
  "area": {
    "id": "PE_PARKING",
    "name": "체대 앞 & 학정 옆 주차장"
  },
  "menuImageUrl": "https://...",
  "mapMarkerId": 45
}
```

---

## 플리마켓 Response 예시

```json
{
  "id": 42,
  "type": "FLEA_MARKET",
  "name": "ERICA MARKET",
  "operator": "OO 동아리",
  "area": {
    "id": "DEMOCRACY_PLAZA",
    "name": "학생복지관 앞 민주광장"
  },
  "menuImageUrl": "https://...",
  "mapMarkerId": 61
}
```

---

## 6-2. 상세 조회

### `GET /api/v1/festival-spaces/{spaceId}`

리스트에서 항목 클릭 시 사용.

---

## 6-3. 위치보기

별도 API는 필요 없음

festival-space 응답에:

```json
"mapMarkerId": 45
```

를 내려준다.

프론트에서:

```
위치보기
↓
/map?markerId=45
```

로 이동시키면 됨

지도 화면 진입 후 해당 마커 자동 선택 → 이 구조 추천

---

# 7. MAP

여기는 이번 서비스에서 중요한 부분

펜타포트처럼 **축제 전용 일러스트 지도 + 필터**를 기준으로 함

---

# 7-1. 지도 구역

현재 유추되는 축제 구역:

|areaId|실제 구역|
|---|---|
|`DEMOCRACY_PLAZA`|학생복지관 앞 민주광장|
|`HOGONG_PICNIC`|호공 & 호공 앞 피크닉존|
|`PE_PARKING`|체대 앞 & 학정 옆 주차장|
|`ENG4_ACADEMIC`|학정과 4공 사이|
|`TICKET_ZONE`|티켓 수령 부스|
|`MAIN_STADIUM`|대운동장|

---

## 7-2. 지도 카테고리

API ENUM은 이렇게 유추 중

|API Enum|화면 표시|
|---|---|
|`INFO`|인포|
|`STUDENT_COUNCIL_BOOTH`|총학생회 부스|
|`BRAND_BOOTH`|브랜드 부스|
|`FLEA_MARKET`|플리마켓|
|`PUB`|주점|
|`TICKET_BOOTH`|티켓부스|
|`RESTROOM`|화장실|
|`SMOKING_AREA`|흡연구역|
|`PICNIC_ZONE`|피크닉존|
|`FOOD_TRUCK`|푸드트럭|
|`EVENT_ZONE`|이벤트존|
|`PHOTO_BOOTH`|포토부스|
|`TRASH_BIN`|쓰레기통|
|`STAGE_GATE`|공연장 게이트|

---

# 7-3. 지도 기본 정보

### `GET /api/v1/map`

```
GET /api/v1/map?lang=ko
```

### Response

```json
{
  "success": true,
  "data": {
    "mapImageUrl": "https://.../festival-map.png",
    "areas": [
      {
        "id": "DEMOCRACY_PLAZA",
        "name": "학생복지관 앞 민주광장"
      },
      {
        "id": "MAIN_STADIUM",
        "name": "대운동장"
      }
    ],
    "categories": [
      {
        "id": "INFO",
        "name": "인포",
        "iconUrl": "https://..."
      },
      {
        "id": "FOOD_TRUCK",
        "name": "푸드트럭",
        "iconUrl": "https://..."
      }
    ]
  }
}
```

---

# 7-4. 지도 마커 조회

### `GET /api/v1/map/markers`

### Query

```
areaId
category
lang
```

예:

```
GET /api/v1/map/markers?areaId=PE_PARKING&category=FOOD_TRUCK
```

### Response

```json
{
  "success": true,
  "data": [
    {
      "id": 101,
      "name": "푸드트럭 A",
      "category": "FOOD_TRUCK",
      "areaId": "PE_PARKING",

      "xRatio": 0.481,
      "yRatio": 0.637,

      "linkedContent": {
        "type": "FESTIVAL_SPACE",
        "id": 31
      }
    }
  ]
}
```

---

# 지도 좌표는 `위도/경도`보다 이것을 추천

펜타포트 화면처럼 **일러스트 축제 지도**를 쓴다면:

```json
"xRatio": 0.481,
"yRatio": 0.637
```

방식을 추천해.

예를 들어 지도 이미지 크기가 달라져도:

```
actualX = imageWidth × xRatio
actualY = imageHeight × yRatio
```

로 마커 위치를 유지 가능

따라서

```
x = 382px
y = 512px
```

처럼 절대 픽셀값을 DB에 저장하는 것보다 훨씬 좋음

---

# 7-5. 마커 상세

### `GET /api/v1/map/markers/{markerId}`

```json
{
  "success": true,
  "data": {
    "id": 101,
    "name": "컴퓨터학부 체험 부스",
    "category": "BRAND_BOOTH",
    "areaName": "학정과 4공 사이",
    "description": "AI 체험 이벤트를 진행합니다.",
    "linkedContent": {
      "type": "FESTIVAL_SPACE",
      "id": 21
    }
  }
}
```

---

# 8. NOTICE

펜타포트 홈의 공지 카드처럼 **큰 운영 안내만 제공**

## 8-1. 공지 목록

### `GET /api/v1/notices`

### Query

```
type
lang
page
size
```

### Notice Type

```
OPERATION
WEATHER
EMERGENCY
SCHEDULE_CHANGE
LOST_AND_FOUND
```

### Response

```json
{
  "success": true,
  "data": {
    "id": 11,
    "title": "공연장 입장 안내",
    "content": "공연장 입장 시 안전요원의 안내에 따라주세요.",
    "important": true,
    "type": "OPERATION",

    "contentType": "HYBRID",

    "externalUrl": "<https://instagram.com/p/>...",
    "sourceType": "INSTAGRAM",
    "sourceName": "한양대학교 ERICA 총학생회",

    "createdAt": "2026-09-23T09:00:00"
  }
}
```

### 공지 콘텐츠 유형

공지사항은 앱 내부 정보와 총학생회 공식 게시물을 함께 활용할 수 있도록 한다.

|값|의미|
|---|---|
|`INTERNAL`|앱 내부에서 공지 전체 내용 제공|
|`EXTERNAL`|제목 등 간단한 정보만 제공하고 총학생회 공식 페이지/SNS로 이동|
|`HYBRID`|앱 내부에서 주요 내용을 제공하고 공식 원문 링크도 함께 제공|

### 외부 링크 관련 필드

|필드|설명|
|---|---|
|`externalUrl`|총학생회 공식 게시물 또는 페이지 URL|
|`sourceType`|Instagram, YouTube, Website 등 출처 플랫폼|
|`sourceName`|예: 한양대학교 ERICA 총학생회|

---

## 8-2. 공지 상세

### `GET /api/v1/notices/{noticeId}`

```json
{
  "success": true,
  "data": {
    "id": 11,
    "title": "공연장 입장 안내",
    "content": "공연장 입장 시...",
    "important": true,
    "type": "OPERATION",
    "createdAt": "2026-09-23T09:00:00"
  }
}
```

# 8-3. External Link

FAQ, 총학생회 공식 SNS, 공식 페이지 등 외부 서비스와 연결되는 링크를 관리

외부 URL을 프론트엔드에 직접 하드코딩하지 않고 서버에서 관리하여 축제 운영 중 링크가 변경되더라도 앱을 재배포하지 않고 수정 가능하도록

## 외부 링크 목록 조회

### `GET /api/v1/external-links`

### Query

|이름|타입|필수|설명|
|---|---|---|---|
|`type`|Enum|X|외부 링크 유형|

### type ENUM

```
FAQ
OFFICIAL_SNS
OFFICIAL_PAGE
```

### Request

**`GET /api/v1/external-links?type=OFFICIAL_SNS`**

### Response

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "type": "OFFICIAL_SNS",
      "title": "총학생회 Instagram",
      "platform": "INSTAGRAM",
      "url": "<https://instagram.com/>...",
      "sortOrder": 1
    },
    {
      "id": 2,
      "type": "OFFICIAL_SNS",
      "title": "총학생회 YouTube",
      "platform": "YOUTUBE",
      "url": "<https://youtube.com/>...",
      "sortOrder": 2
    }
  ]
}
```

### platform ENUM

```
INSTAGRAM
YOUTUBE
FACEBOOK
NAVER_BLOG
X
WEBSITE
ETC
```

### 활용 예시

```
{
  "type":"OFFICIAL_SNS",
  "title":"총학생회 Instagram",
  "platform":"INSTAGRAM",
  "url":"<https://instagram.com/>...",
  "sortOrder":1
}
```

### 관리자에서 실제로 이렇게 관리

```
외부 링크 관리

FAQ
→ https://...

Instagram
→ <https://instagram.com/>...

YouTube
→ <https://youtube.com/>...
```

총학생회 URL이 변경되면 여기만 수정.

---

# 9. 분실물 - 협의해야하는 기능

확정된다면 공지와 완전히 합치기보다는 별도 API가 더 좋음

### `GET /api/v1/lost-items`

```
GET /api/v1/lost-items?date=2026-09-24
```

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "itemName": "검정색 지갑",
      "imageUrl": "https://...",
      "foundLocation": "대운동장 입구",
      "storageLocation": "총학생회 안내부스"
    }
  ]
}
```

---

# 10. CHATBOT

홈 → 챗봇으로 진입.

펜타포트처럼 축제 데이터만 답하는 **축제 안내 챗봇**으로 제한

## 10-1. 질문

### `POST /api/v1/chatbot/messages`

### Request

```json
{
  "message": "푸드트럭 어디 있어?",
  "lang": "ko"
}
```

### Response

```json
{
  "success": true,
  "data": {
    "answer": "푸드트럭은 체대 앞 및 학정 옆 주차장 구역에 있습니다.",
    "action": {
      "type": "OPEN_MAP",
      "areaId": "PE_PARKING",
      "category": "FOOD_TRUCK"
    }
  }
}
```

이 `action` 구조가 중요해.

예를 들어:

```
OPEN_MAP
OPEN_TIMETABLE
OPEN_NOTICE
OPEN_FESTIVAL_SPACE
NONE
```

를 지원하면 챗봇 답변의

> 지도에서 보기

버튼을 바로 만들기 가능

---

# 11. STAMP TOUR - 승인 시 추가

총학생회와 협의 후 진행한다면 이전에 논의했던 구조로 추가

### 주요 API

|Method|URL|기능|
|---|---|---|
|POST|`/stamp/participants`|최초 참여 등록|
|GET|`/stamp/status`|내 스탬프판|
|POST|`/stamp/scan`|QR 스캔|
|POST|`/rewards/roulette`|룰렛 실행|
|GET|`/rewards/current`|당첨 경품 조회|
|POST|`/admin/rewards/{id}/redeem`|경품 교환 완료|

예:

```json
POST /api/v1/stamp/scan

{
  "qrToken": "BOOTH-A-X82K1"
}
```

Response:

```json
{
  "success": true,
  "data": {
    "stampCount": 7,
    "requiredCount": 10,
    "rewardAvailable": false
  }
}
```

이 부분은 운영 여부가 결정되면 별도 **Stamp API 명세 v1 작성 필요**

---

# 12. 관리자 API

관리자만 인증 필요.

Base:

```
/api/v1/admin
```

### 관리자 기능

|Method|API|기능|
|---|---|---|
|POST|`/artists`|아티스트 등록|
|PATCH|`/artists/{id}`|아티스트 수정|
|DELETE|`/artists/{id}`|아티스트 삭제|
|POST|`/performances`|공연 등록|
|PATCH|`/performances/{id}`|공연 변경|
|POST|`/festival-spaces`|부스/주점/마켓 등록|
|PATCH|`/festival-spaces/{id}`|수정|
|POST|`/map/markers`|지도 마커 생성|
|PATCH|`/map/markers/{id}`|마커 수정|
|DELETE|`/map/markers/{id}`|마커 삭제|
|POST|`/notices`|공지 등록|
|PATCH|`/notices/{id}`|공지 수정|
|DELETE|`/notices/{id}`|공지 삭제|
|GET|`/admin/external-links`|외부 링크 목록 조회|
|POST|`/admin/external-links`|외부 링크 등록|
|PATCH|`/admin/external-links/{id}`|외부 링크 수정|
|DELETE|`/admin/external-links/{id}`|외부 링크 삭제|

---

# 13. 핵심 데이터 모델

개발 초기 DB를 잡을 때는 대략 이 구조면 충분

```
Festival
 ├─ Artist
 │    └─ ArtistTranslation
 │
 ├─ Stage
 │    └─ Performance
 │
 ├─ FestivalSpace
 │    └─ FestivalSpaceTranslation
 │
 ├─ MapArea
 │
 ├─ MapMarker
 │
 ├─ Notice
 │    └─ NoticeTranslation
 │
 ├─ ExternalLink
 │
 └─ LostItem
```

관계를 보면:

```
FestivalSpace
      │
      │ mapMarkerId
      ▼
MapMarker

MapMarker
 ├─ category
 ├─ area
 ├─ xRatio
 └─ yRatio
```

---

# 14. 프론트에서만 처리해도 되는 것

백엔드 API를 만들 필요 없는 기능

|기능|처리|
|---|---|
|현재 시간선|Frontend|
|현재 공연 카드 강조|Frontend|
|Bottom Sheet 열기/닫기|Frontend|
|언어 선택 상태|Frontend|
|지도 필터 UI 상태|Frontend|
|지도 Zoom/Pan|Frontend|
|위치보기 후 마커 선택|Frontend Routing|
|Bottom Navigation|Frontend|

---

# 15. 1차 개발 기준 API 목록

**우선 이것부터 구현해야함**

|Domain|Method|Endpoint|
|---|---|---|
|Home|GET|`/home`|
|Lineup|GET|`/artists`|
|Lineup|GET|`/artists/{id}`|
|Timetable|GET|`/performances`|
|Timetable|GET|`/performances/{id}`|
|Booth & Market|GET|`/festival-spaces`|
|Booth & Market|GET|`/festival-spaces/{id}`|
|Map|GET|`/map`|
|Map|GET|`/map/markers`|
|Map|GET|`/map/markers/{id}`|
|Notice|GET|`/notices`|
|Notice|GET|`/notices/{id}`|
|Chatbot|POST|`/chatbot/messages`|
|External Link|GET|`/external-links`|

**총 13개 정도로 사용자용 MVP API를 시작**

---

# 16. 주의 사항 및 중요한 점

특히 이번 서비스에서는 `FestivalSpace ↔ MapMarker` 연결을 잘 만들어 놓는 게 중요

그래야 **부스 & 마켓에서 `위치보기` → 지도 이동 → 해당 마커 자동 선택**이라는 펜타포트식 UX를 아주 깔끔하게 구현 가능

FAQ와 공식 SNS는 총학생회가 실제로 운영하는 페이지 및 SNS 채널과 연결하는 방식으로 제공한다.

공지사항은 앱 내부에서 운영 정보를 제공하는 것을 기본으로 하되, 필요할 경우 총학생회의 공식 SNS 게시물 또는 공식 페이지 원문으로 연결할 수 있도록 한다.

FAQ 및 공식 SNS URL은 프론트엔드에 직접 저장하지 않고 `ExternalLink` API와 관리자 페이지를 통해 관리하여 축제 운영 중 링크 변경에 대응할 수 있도록 한다.