# 프런트 연동 안내

Node.js 22 이상에서 저장소 루트 기준으로 실행합니다. 서버 실행에는 외부 패키지 설치나 DB가 필요하지 않습니다.

```powershell
cd api-v2
npm start
```

목 서버 주소는 **http://127.0.0.1:4010**, API base URL은 **http://127.0.0.1:4010/api/v2**입니다. 루트 주소의 콘솔에서 API와 시나리오를 선택하고 요청을 실행할 수 있습니다. Ctrl+C로 종료합니다. 원격 배포 서버가 아닌 로컬 개발 서버입니다.

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
const imageUrl = goods[0] && new URL(goods[0].image.url, apiOrigin).href;
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
| 홈 | config normal / missing-optional / empty, crowd before-open / closed / unmodified, notices empty / error |
| 목록·상세 | 목록 empty, 상세 missing-optional / not-found / error. 선택 정보가 없는 영역은 제목까지 숨김 |
| 굿즈 | goods 정상 + goods-availability error, availability sold-out, payment-guide missing-optional. 품절과 조회 실패를 구분 |
| 공지 | new-notice / deleted, 관리자 생성→조회→수정→삭제, locale=en의 번역 대기 제외 |
| 지도 | 이미지 정상 + pins error / empty / version-conflict. 장소→상세 및 상세→핀 연결 |
| 티켓 | before-open / closed / ended / unconfigured. 계좌 숨김·가격 미정·오늘 날짜 표시 |
| 관리자 | unauthorized / forbidden / error. 저장 실패 시 기존 값 유지, FULL 확인 및 같은 상태 재선택 |
| 스탬프 | HTTP guide missing-optional와 별도 client-state-examples의 시작 전·2칸·4칸·수령·다음 날짜 |

## 갱신과 프런트 책임

혼잡도·공지 제거는 기존 화면 데이터의 5초 기준을 따릅니다. 상품·재고는 새로고침 없는 자동 반영이 요구되며 최대 지연 시간은 합의 대기입니다. 개발 시작점으로 2초 폴링을 사용할 수 있으나 실제 통신 방식은 합의 대기입니다. 요청 시간과 백그라운드 탭 제한까지 포함한 운영 환경의 5초 보장은 이번 목 검증 범위에 포함하지 않습니다. 페이지를 다시 활성화할 때 즉시 조회하고, 같은 데이터의 이전 revision 또는 먼저 시작한 요청의 늦은 응답이 최신 화면을 덮지 않게 처리합니다.

공지 조회 성공 시 현재 카드 중 `visibleIds`에 없는 ID를 즉시 제거합니다. 기존 ID의 내용은 최신 응답으로 갱신하고 새로운 ID는 대기 목록에 넣어 ‘새 공지’ 버튼을 누를 때 추가합니다. 삭제된 ID는 대기 목록에서도 제거합니다. 최초 조회는 items 전체를 표시합니다. 삭제 API 성공 후 재조회가 실패해도 삭제한 카드를 되살리지 않습니다.

상품 상세·색상×사이즈 판매 상태는 독립 로딩과 오류 영역을 둡니다. 지도 핀 409는 지도 메타데이터·이미지를 다시 읽고 새 version으로 핀을 조회합니다. 이미지 로딩 성공 전에 새 핀을 겹치지 않습니다. 핀 실패 시 이미 로드된 지도 이미지를 유지합니다.

부스 즐겨찾기, 선택 날짜·분류, 지도 확대·이동, 티켓 인원·합계, 스탬프 누적은 프런트 상태입니다. 스탬프는 로그인 없이 공통 QR을 사용하므로 엄격한 중복 참여 차단을 약속할 수 없습니다. QR 권한 거절·카메라 오류는 HTTP 오류가 아니며 로컬 예제와 화면 상태 정의로 개발합니다. 상품 소진 안내는 현장 운영 책임입니다.

## 새 관리자 계약 연동

운영 시간, 재고 수량, 상품 등록·수정, 번역 미리보기의 경로·필드와 시나리오는 [관리자 변경 내역](ADMIN-CHANGES.md)을 확인하세요. `all-languages`는 같은 목 세션의 언어 설정을 바꾸며 `partial-translation`은 중·일 실패를 재현합니다.

## 계약 검증

```powershell
npm ci --ignore-scripts
npm run check
```

`check`는 생성 파일 일치 여부, OpenAPI 표준 파싱, JSON Schema 검증, 실제 HTTP 요청·응답 및 상태 변경을 검사합니다. 수정 후에는 `npm run generate`를 먼저 실행하세요. 모든 경로를 고정 예제로 호출하는 검증 외에 자정·운영일·인증 경계·저장 실패·지도 연결 등 별도 동작 검증을 포함합니다.
