# 프런트 연동 안내

## 실제 서버와 목 서버

| 대상 | API base URL | 용도 |
|---|---|---|
| 실제 로컬 Spring Boot | `http://127.0.0.1:8080/api/v2` | 구현 완료 API와 DB의 published revision 연동 |
| 로컬 runtime mock | `http://127.0.0.1:4010/api/v2` | 미구현 계약, 오류·빈 상태와 UI 시나리오 개발 |
| 원격 개발 서버 | TBD — 아직 provision되지 않음 | `/api/v2` same-origin proxy 기본안; [배포 결정](../docs/dev-deployment-decision.md) 참고 |

기계 판독 계약은 [openapi.json](openapi.json)입니다. runtime springdoc/Swagger UI는 없으며 정적
OpenAPI 3.1 문서를 source of truth로 사용합니다. 현재 실제 서버에 구현된 공연 조회는 lineup,
artist detail, timetable, performance detail, prohibited items입니다. 공개 GET은 인증 없이
호출합니다. 실제 서버의 `/api/v2/admin/**`는 login과 refresh 진입을 제외하면 `ADMIN` access
JWT가 필요하며, refresh token은 브라우저가 관리하는 Secure·HttpOnly cookie입니다.

실제 서버는 `/healthz`로 process liveness를 확인합니다. `db` profile에서는 `/readyz`가 설정한
festival의 published catalog 준비 상태를 나타냅니다. `/readyz`가 200인 뒤 실제 공개 API를
호출하고 응답의 `meta.revision`을 확인하세요. DB 없는 profile에는 `/readyz`가 등록되지 않습니다.
locale은 생략하면 `ko`이며 현재 실제 공개 언어도 `ko`입니다. 성공은 `{ data, meta }`, 오류는
`{ error, meta }`이고 응답 `X-Request-Id`와 `meta.requestId`로 요청을 추적합니다.

현재 실제 서버의 CORS 설정은 관리자 경로에만 명시되어 있습니다. 초기 원격 개발 환경은 browser가
상대 경로 `/api/v2`를 호출하고 Next.js가 backend로 rewrite하는 same-origin proxy를 기본안으로
사용하므로 public GET의 browser CORS를 추가하지 않습니다. Remote frontend host가 rewrite를
지원하지 않거나 browser가 backend를 직접 cross-origin 호출하게 되면 정확한 frontend origin
allowlist를 별도로 검토합니다. 자세한 조건은 [배포 결정](../docs/dev-deployment-decision.md)을
따릅니다.

### Runtime mock 실행

Node.js 22 이상에서 저장소 루트 기준으로 실행합니다. 목 서버 실행에는 외부 package 설치나
DB가 필요하지 않습니다.

```powershell
cd api-v2
npm start
```

목 서버 주소는 **http://127.0.0.1:4010**, API base URL은
**http://127.0.0.1:4010/api/v2**입니다. 루트 주소의 콘솔에서 API와 시나리오를 선택하고 요청을
실행할 수 있습니다. Ctrl+C로 종료합니다. 원격 배포 서버가 아닌 로컬 개발 서버입니다. 실제
백엔드에 구현된 공연 API의 정상 연동에는 목 대신 `http://127.0.0.1:8080/api/v2`를 권장합니다.

포트 변경은 PowerShell에서 `$env:MOCK_PORT='4011'`로 설정 후 실행합니다. 기본 CORS 허용 origin은 localhost와 127.0.0.1의 3000·5173 포트입니다. 다른 프런트 주소는 `$env:MOCK_CORS_ORIGINS='http://localhost:3001,http://127.0.0.1:3001'`처럼 지정합니다. 서버는 127.0.0.1에만 바인딩하므로 휴대폰에서 직접 접근하는 환경은 별도 구성해야 합니다.

## 첫 요청

```javascript
const apiOrigin = 'http://127.0.0.1:4010';
const response = await fetch(`${apiOrigin}/api/v2/goods`, {
  headers: { 'X-Mock-Session': 'frontend-a', 'X-Mock-Scenario': 'normal' }
});
const payload = await response.json();
if (!response.ok) throw new Error(payload.error.code);
const goods = payload.data.items;
// 이미지 상대 URL은 프런트 origin이 아닌 API origin으로 해석합니다.
const imageUrl = goods[0]?.image?.url ? new URL(goods[0].image.url, apiOrigin).href : null;
```

관리자 요청에는 `Authorization: Bearer mock-admin`을 추가합니다. 토큰 없음 또는 `Bearer mock-expired`는 401, 다른 값은 403입니다. 이 토큰을 실제 인증 기능에 사용하지 않습니다. POST·PUT은 `Content-Type: application/json`이 필요하며 입력 본문은 [examples.json](examples.json)의 해당 요청을 복사할 수 있습니다.

## 시나리오 전환

| 제어 | 사용법 |
|---|---|
| 응답 상태 | `X-Mock-Scenario: empty` 또는 `?__scenario=empty`. 둘 다 있으면 헤더 우선 |
| 지원 목록 | [ENDPOINTS.md](ENDPOINTS.md), `GET /__mock/catalog`, 콘솔의 선택 목록. 요청별 지원 시나리오가 다르며 미지원은 400 |
| 독립 작업 | `X-Mock-Session: frontend-a`. 같은 세션의 저장 결과는 후속 조회에 반영. 생략하면 공용 default 세션 |
| 초기화 | `POST /__mock/reset`, JSON `{}`, 같은 X-Mock-Session. 서버 재시작 시 전부 초기화 |
| 시각 | `X-Mock-Time: 2030-10-02T00:00:00+09:00`. 기본은 2030-10-01 18:00 KST로 고정 |
| 로딩 | `X-Mock-Delay: 1500` (0~3000ms). 화면 로딩 관찰용 |

세션은 최대 64개, 마지막 사용 후 1시간 유지하며 용량 초과 시 오래된 세션을 제거합니다. 저장·삭제는 메모리만 변경합니다. 조회용 `empty`, `sold-out` 등은 해당 응답을 변형하는 예제이므로 이후 요청까지 유지하려면 관리자 저장 API를 사용합니다. 시간 상태 시나리오는 가상 시각을 바꿉니다. 정확한 경계 시각을 시험하려면 `normal`과 `X-Mock-Time`을 함께 사용합니다. 고정된 목 시각을 실제 브라우저 날짜로 해석해 운영 종료로 처리하지 않도록 `meta.serverTime`을 사용하세요.

| 개발할 화면 | 확인할 조합 |
|---|---|
| 홈 | config normal / faq-ready / missing-optional / empty, crowd before-open / closed / unmodified / unconfigured, notices empty / error |
| 목록·상세 | 목록 empty, 상세 missing-optional / not-found / error. 선택 정보가 없는 영역은 제목까지 숨김 |
| 굿즈 | goods 정상 + goods-availability error, availability sold-out, payment-guide missing-optional. 품절과 조회 실패를 구분 |
| 공지 | new-notice / deleted, 관리자 생성→조회→수정→삭제, all-languages를 적용한 목 세션의 locale=en 번역 대기 제외 |
| 지도 | 이미지 정상 + pins error / empty / version-conflict. 장소→상세 및 상세→핀 연결 |
| 티켓 | before-open / closed / ended / unconfigured. 계좌 숨김·가격 미정·오늘 날짜 표시 |
| 관리자 | unauthorized / forbidden / error. 저장 실패 시 기존 값 유지, FULL 확인. 혼잡도는 `If-Match`·`Idempotency-Key`를 사용하며 동일 상태 재선택은 204로 성공하고 저장 시각을 유지 |
| 스탬프 | guide missing-optional, 수령 인증 normal / invalid-code / error, 별도 client-state-examples의 시작 전·직접 QR 시작 전·2칸·4칸·코드 오류·수령·다음 날짜 |

## 갱신과 프런트 책임

혼잡도(홈만)·공지·굿즈 판매 상태·외부인 티켓 안내는 화면이 보이는 동안 15초 HTTP polling으로 갱신합니다.
진입·재활성화·온라인 복귀 때 즉시 조회하고, 숨김·오프라인이면 중단합니다. 요청을 겹치게
보내지 않고 실패는 30초, 60초 순으로 backoff하며 마지막 정상값을 유지합니다. 먼저 시작한
요청의 늦은 응답이 최신 화면을 덮지 않게 generation을 비교해 폐기합니다. `meta.revision`이
같아도 동적 운영 상태 응답을 버리지 않습니다. 목은 HTTP 재조회 동작만 제공하며 운영 환경의
실제 부하·지연을 검증하지 않습니다.

공지 조회 성공 시 현재 카드 중 `visibleIds`에 없는 ID를 즉시 제거합니다. 기존 ID의 내용은 최신 응답으로 갱신하고 새로운 ID는 대기 목록에 넣어 ‘새 공지’ 버튼을 누를 때 추가합니다. 삭제된 ID는 대기 목록에서도 제거합니다. 최초 조회는 items 전체를 표시합니다. 삭제 API 성공 후 재조회가 실패해도 삭제한 카드를 되살리지 않습니다.

상품 상세·색상×사이즈 판매 상태는 독립 로딩과 오류 영역을 둡니다. 지도 핀 409는 지도 메타데이터·이미지를 다시 읽고 새 version으로 핀을 조회합니다. 이미지 로딩 성공 전에 새 핀을 겹치지 않습니다. 핀 실패 시 이미 로드된 지도 이미지를 유지합니다.

홈 FAQ 메뉴는 `Config.links.faq`가 있을 때만 그 HTTPS URL을 새 탭으로 엽니다. `faq=null`이면 임의 이동이나 앱 내부 FAQ 화면을 만들지 않습니다.

### 조건부 응답과 티켓 안내

혼잡도와 `/api/v2/ticket-guide`는 조건부 응답입니다. 본문 `meta`에는 안정적인
`timezone`·`festivalId`·`revision`·`locale`·`mock`만 있고, 매 요청 달라지는 요청 ID와 서버
시각은 `X-Request-Id`, `X-Server-Time` 헤더로 옵니다. 응답의 `ETag`를 저장했다가 다음
polling에서 `If-None-Match`로 보내면 변경이 없을 때 304와 빈 본문을 받습니다. 304에서는
직전 표현을 그대로 유지하고 새 `ETag`만 갱신합니다.

티켓 안내는 정적 카탈로그 안내와 현재 `TICKET` 계좌 설정을 합친 표현입니다. 계좌가 등록되지
않았거나 해제되면 일정이 있어도 `status`는 `UNCONFIGURED`이고 `account`는 null입니다.
`paymentSettingsVersion`은 계좌 설정의 version이며, 계좌가 바뀌면 이 값과 `ETag`가 함께
바뀌므로 다음 polling(최대 15초)에서 새 계좌를 받습니다. 계좌를 한 번도 설정하지 않았으면
null입니다. `transferLink`는 표시명 출처가 정해질 때까지 항상 null이므로 토스 버튼 문구는
프런트가 소유합니다.

서버는 `Cache-Control: private, no-cache`를 보냅니다. Next.js same-origin proxy는 `ETag`,
`If-None-Match`, `Cache-Control`, 304 상태를 그대로 전달해야 하며 자체 캐시를 추가하지
않습니다. 이 proxy 동작의 확인은 웹 저장소의 integration acceptance 범위입니다.

### 부스 계좌 송금 안내

부스 상세(`GET /spaces/{spaceId}`)의 `bankTransfer`로 계좌를 표시합니다. 목록에서는 항상 null이고,
상세에서도 null이면 계좌 안내와 송금 버튼을 숨깁니다. 상세 응답은 `Cache-Control: no-store`이므로
송금 안내를 열 때 상세를 다시 불러 최신 계좌를 씁니다. 금액은 메뉴 가격으로 프런트가 계산합니다.
같은 부스 메뉴만 `단가 × 수량`으로 합산하고, 가격이 null인 메뉴는 0원으로 계산하지 않으며 다른
부스 메뉴는 한 합계로 묶지 않습니다. 합계는 안내값일 뿐 입금 증거가 아닙니다.
`tossLinkEnabled`가 true일 때만 토스 버튼을 보이고, 계좌·금액 복사는 항상 제공합니다. `bankId`를
토스 링크의 은행 파라미터로 그대로 쓰지 않습니다. 토스 송금 URL 규격은 아직 실기기 검증 전입니다.

부스 즐겨찾기, 선택 날짜·분류, 지도 확대·이동, 티켓 인원·합계, 스탬프 누적은 프런트 상태입니다. 스탬프는 로그인 없이 공통 QR을 사용하므로 엄격한 중복 참여 차단을 약속할 수 없습니다. 4칸을 모은 사용자의 수령 안내 창에는 `상품 수령` 버튼을 만들지 말고 수령 인증 코드 입력칸과 `확인` 버튼을 둡니다. 코드는 6자리 숫자이므로 입력칸은 `inputmode="numeric"`로 6자리만 받습니다. 멋사 부스 담당자가 입력한 코드만 `POST /stamp-receipt-verifications`로 보내며, `verified: true`일 때만 로컬 `stamp.claimed=true`로 바꾸고 입력값은 저장하지 않습니다. `INVALID_RECEIPT_CODE`·통신 오류에서는 미수령 상태와 안내 창을 유지합니다. START 전 기본 카메라로 QR URL에 직접 들어오면 `STAMP-START`로 이동하며 시작 기록·적립을 만들지 않습니다. QR 권한 거절·카메라 오류는 HTTP 오류가 아니며 로컬 예제와 화면 상태 정의로 개발합니다. 상품 소진 안내는 현장 운영 책임입니다.

## 새 관리자 계약 연동

[관리자 변경 내역](ADMIN-CHANGES.md)의 draft.3 경로와 필드를 사용합니다. 수량 및 운영 시간 편집 요청은 제거했습니다.

- 굿즈 상태는 실제 제공 조합에 `PUT /admin/goods/{goodsId}/colors/{colorId}/sizes/{sizeId}/availability`, 본문 `{ "status": "SOLD_OUT" }`로 저장합니다. quantity는 422입니다.
- 신규 상품·옵션 조합은 ON_SALE로 생성합니다. 색상·사이즈·조합 삭제도 허용하며, 삭제한 조합의 판매 상태는 제거하고 유지 조합의 상태는 그대로 둡니다.
- 상품명·가격·실제 제공 색상·사이즈·조합은 비어 있지 않아야 합니다. 빈 구성은 422로 거절하며 저장 전후 상품과 판매 상태를 바꾸지 않습니다. 이미지 개수·배치·업로드 방식과 옵션 없는 상품 입력 방식은 미정이고, 이미지 없는 저장은 409 IMAGE_CONFIGURATION_UNRESOLVED로 공개 상태 반영을 막습니다.
- 영어 PENDING/FAILED에서도 한국어 저장은 성공합니다. 미리보기 canSave는 한국어 필수값 기준입니다. 템플릿 원문을 바꾸면 게시 전 기존 번역을 READY로 재사용하지 말고 변경 원문 기준으로 준비합니다. 늦은 미리보기 응답은 source와 현재 입력을 비교해 폐기합니다.
- 현재 공개 언어는 한국어뿐입니다. all-languages는 프런트 검증용 목 세션의 준비 언어를 바꾸며 partial-translation은 중·일 실패를 재현합니다. english-failed는 영어 실패와 한국어 게시를 함께 확인합니다. 실제 공개 전에는 모든 필수 콘텐츠·정렬·필터 label이 완결돼야 하며 한국어 fallback은 없습니다.
- /prohibited-items의 items·message는 상시 안내입니다. 기존 /performance-alert를 교체하세요.

## 계약 검증

```powershell
npm ci --ignore-scripts
npm run check
```

`check`는 생성 파일 일치 여부, OpenAPI 표준 파싱, JSON Schema 검증, 실제 HTTP 요청·응답 및 상태 변경을 검사합니다. 수정 후에는 `npm run generate`를 먼저 실행하세요. 모든 경로를 고정 예제로 호출하는 검증 외에 자정·운영일·인증 경계·저장 실패·지도 연결 등 별도 동작 검증을 포함합니다.
