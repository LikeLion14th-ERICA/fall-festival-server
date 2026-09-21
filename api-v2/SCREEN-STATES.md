# 화면 상태·조회 규칙

[Product Context](../docs/PRODUCT_CONTEXT.md)·[관리자 v5](../docs/wiki/product/admin/README.md)가 기준입니다.
필드는 [화면 데이터 표](SCREEN-DATA.md)·[OpenAPI](openapi.json), 요청별 지원 예제는
[ENDPOINTS.md](ENDPOINTS.md)를 확인합니다. 로딩은 X-Mock-Delay, 조회·저장 오류는 error로 재현합니다.
운영 데이터가 없는 상태를 가짜 데이터로 채우지 않습니다.

| 화면 ID | 정상 조회·동작 | 빈 데이터·선택 누락·오류·특수 상태 |
|---|---|---|
| HOME | config + crowding + notices | 공지 없음이어도 전체보기 유지. 혼잡도 최초 실패는 확인 불가, 갱신 실패는 마지막 정상 상태 유지. `faq`와 `welcomeDay`는 null이면 임의 이동하지 않고, 값이 있으면 외부 새 탭으로 연다. 언어 저장은 브라우저 |
| NOTICE-LIST | notices, 최초 등록 최신순 | 빈 목록 안내. 선택 언어 READY만 표시. 새 ID는 새 공지 버튼으로 추가, visibleIds에서 사라진 ID는 자동 제거. 자정 일반 공지 제외, 분실물 유지 |
| GOODS-LIST | goods + goods-availability 독립 조회 | 상품 없음과 상태 조회 실패 구분. 전 조합 품절이어도 상품 유지 |
| GOODS-DETAIL | goods/{id} + availability | null 선택 영역 숨김. options의 실제 조합만 표시. 상세 오류와 상태 오류 분리. 전체 품절이어도 계좌 확인 진입 유지. 색상 선택 이미지 전환 없음 |
| GOODS-PAYMENT | goods/{id}/payment-guide | 누락 계좌를 임의 생성하지 않음. 안내 오류 재조회. 주문·입금 확인·자동 수량 감소 없음 |
| SHOW-LINEUP | lineup, 날짜·분류 선택 | 해당 날짜·분류 items=[]는 빈 목록. 필터·스크롤은 프런트 |
| SHOW-ARTIST | artists/{id} | 소개·SNS·대표곡 누락 영역 제목까지 숨김. 404/일시 오류 구분. 외부 링크 새 탭 |
| SHOW-TIMETABLE | timetable + prohibited-items | 공연 없음과 고정 안내 없음 분리. 현재선은 축제 당일 오늘 열17:00~22:00. 시간축 변경과 별개. 안내는 공연 밖에도 유지 |
| SHOW-POPUP | performances/{id} | 설명·출연진 누락 영역 숨김. 선택 공연 오류 재시도 |
| BOOTH-LIST | spaces, 분류 선택 | 분류별 빈 목록. 별 우선·그룹 내 가나다순 정렬·스크롤은 같은 브라우저 상태. 목록 오류 재시도 |
| BOOTH-DETAIL | spaces/{id} | 소개·체험·이벤트·메뉴·연락처 누락 영역 숨김. mapTarget으로 지도 연결. 공유는 현재 상세 URL |
| MAP-OVERVIEW | maps + pins | 핀이 비어도 이미지 유지. 이미지/핀 오류 분리, 실패 부분 재조회. mapVersion409는 버전 재동기화. 혼잡도 표시 없음 |
| MAP-AREA | maps/{id} + pins | 없는 핀 분류 숨김. 기본안은 확대 없음·가로 스크롤. 전체 복귀는 기본 위치/전체 필터. 상세에서 복귀는 이전 상태 유지 |
| MAP-POPUP | places/{id} | null 안내 영역 숨김. 정보 실패여도 지도 이미지 유지. SPACE만 부스 상세 연결. 바깥 선택으로 닫기 |
| TICKET | ticket-guide, 서버 KST 오늘 | unconfigured는 자료 대기. before-open/closed/ended는 계좌null. 메뉴 유지. 인원 기본·최소1, 합계KRW×인원, 송금 복귀1명 |
| STAMP-START | stamp-guide + 당일 로컬 상태 | 선택 수령 안내·QR 누락은 자료 대기. START만으로 적립하지 않음. START 전 기본 카메라 QR 직접 진입은 이 화면으로 이동하며 자동 시작·적립 없음 |
| STAMP-COLLECT | guide + 로컬 count/date/started/claimed | 처음0칸. 공통 QR1회당1개·하루4개. 자정 초기화. START 후 기본 카메라 QR 진입만 적립 처리하며 카메라 오류는 HTTP 오류와 분리 |
| STAMP-REWARD | 로컬4칸·수령 상태 + `POST /stamp-receipt-verifications` | 4칸 전 수령 불가. 안내 창에는 상품 수령 버튼 없이 담당자용 코드 입력칸과 확인 버튼을 둔다. `verified: true`일 때만 `claimed=true`로 저장·창 닫기. 코드 오류·통신 실패는 미수령 상태·창을 유지하고 코드값은 저장하지 않는다. 서버 지급 기록·재고·엄격한 중복 차단 없음 |
| ADM-CROWD | admin/crowding | 저장 전 savedLevel/updatedAt=null. FULL 확인 취소는 요청 없음. 실제 `FestivalDay`인 날짜는 운영 전·운영 종료 뒤에도 저장할 수 있다. 실제 FestivalDay가 아닌 날짜의 PUT은 `409 NOT_FESTIVAL_DAY`이며 기존 상태를 유지한다. 저장 실패 기존값 유지. 자정 초기화. 시간 읽기 전용 |
| ADM-NOTICE-LIST | admin/notices, 최종 수정순 | 등록 공지 없음. 조회 실패 재시도. 지난 일반 공지도 관리자에는 유지 |
| ADM-NOTICE-EDIT | 수정 초기값 + 선택적 번역 미리보기 | 영어 PENDING/FAILED도 한국어 저장 성공. 한국어 공백·잘못된 링크422. 실패 시 입력 유지. 이미지 직접 첨부 없음 |
| ADM-NOTICE-DELETE | 확인 후 DELETE | 취소는 요청 없음. 실패 기존 공지 유지. 성공 모든 언어·홈에서 제거. 이미 삭제409/404는 목록 재동기화 |
| ADM-NOTICE-TEMPLATE | admin/notice-templates | 빈 목록·번역 누락·실패여도 직접 작성 가능. 원본 불변. 게시 전 원문 변경 시 번역 다시 준비 |
| ADM-GOODS | admin/goods | 상품 없음이면 등록 진입. 실제 조합별 상태만 변경. 다른 조합 유지. 저장 실패 시 전체 품절 여부도 유지 |
| ADM-GOODS-PRODUCT-LIST | admin/products | 등록 상품 없음이면 신규 등록 진입. 조회 실패 재시도. 수량 표시 없음 |
| ADM-GOODS-PRODUCT-EDIT | products/{id}, POST/PUT products | 이름·가격·실제 색상·사이즈·조합 검증. 등록 실패와 수정 실패 문구를 분리하고 입력·기존 이미지·판매 상태를 유지한다. 이미지 업로드 실패는 실패 안내·재시도와 수정 중 기존 이미지 유지를 제공한다. 이미지 구성·옵션 없는 상품 입력은 미정이며 빈 구성은 422로 차단한다. 기존 옵션 상태 보존. 신규 상태 기본409, 명시적 목 시나리오로 성공 검토 |

공개 조회는 로그인 없이, 관리자는 서버 권한 검증을 전제로 합니다. null/[]/404/503의 의미는
[공통 계약](README.md)을 따르며 갱신 실패를 정상 빈 응답으로 바꾸지 않습니다.
새로고침 없는 반영의 통신 방식·최대 지연, 실제 이미지 업로드·번역 서비스는 별도 합의 대상입니다.
비HTTP 예제는 [client-state-examples.json](client-state-examples.json)을 사용합니다.
실기기 QR·모바일 렌더링·실제 송금 연결은 목 HTTP 검증으로 대체하지 않습니다.
