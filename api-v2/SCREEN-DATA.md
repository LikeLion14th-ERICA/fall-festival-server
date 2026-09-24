# 화면 데이터 → API 필드

Product Context v5 기준 26개 화면의 데이터 178개를 추적합니다. 제외 항목은 이력으로 표시합니다. 필드 경로는 응답 data 기준이며 [화면 상태·조회 규칙](SCREEN-STATES.md)을 함께 적용합니다. 이전 Google Sheets 스냅샷은 현재 계약의 기준이 아닙니다.

| 데이터 ID | 항목 | 처리 | 계약/프런트 책임 | 위키 근거 |
|---|---|---|---|---|
| BOOTH-LIST-D01 | 대표 사진 | API | Space.image | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-LIST-D02 | 이름 | API | Space.name | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-LIST-D03 | 위치 | API | Space.locationText | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-LIST-D04 | 분류 | API | Space.category | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-LIST-D05 | 별 저장 여부 | 브라우저 | 브라우저: savedSpaceIds | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-LIST-D06 | 선택 분류·스크롤 위치 | 브라우저 | 브라우저: 선택 분류·스크롤 | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D01 | 대표 사진 | API | Space.image | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D02 | 이름 | API | Space.name | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D03 | 분류 | API | Space.category | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D04 | 운영 주체 | API | Space.operator | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D05 | 운영 시간 | API | Space.hoursText | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D06 | 위치 | API | Space.locationText | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D07 | 문의 SNS | API | Space.contact | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D08 | 소개·설명 | API | Space.description | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D09 | 체험 방법 | API | Space.experience | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D10 | 진행 이벤트 | API | Space.events | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D11 | 메뉴명 | API | Space.menu[].name | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D12 | 가격 | API | Space.menu[].price | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D13 | 별 저장 여부 | 브라우저 | 브라우저: savedSpaceIds | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D14 | 대상 구역·장소 연결 정보 | API | Space.mapTarget | [위키](../docs/wiki/product/spaces.md) |
| BOOTH-DETAIL-D15 | 현재 상세 링크 | 브라우저 | 브라우저: 현재 상세 페이지 URL | [위키](../docs/wiki/product/spaces.md) |
| MAP-OVERVIEW-D01 | 지도 이미지 | API | Map.image | [위키](../docs/wiki/product/map.md) |
| MAP-OVERVIEW-D02 | 장소별 핀 위치 | API | Pin.x + Pin.y | [위키](../docs/wiki/product/map.md) |
| MAP-OVERVIEW-D03 | 장소별 핀 종류 | API | Pin.category | [위키](../docs/wiki/product/map.md) |
| MAP-OVERVIEW-D04 | 필터 선택지 | 브라우저 | 브라우저: Pins.items에서 실제 종류 추출; 필터 단위 미정 | [위키](../docs/wiki/product/map.md) |
| MAP-OVERVIEW-D05 | 선택 필터·선택 핀 | 브라우저 | 브라우저: 선택 필터·핀 | [위키](../docs/wiki/product/map.md) |
| MAP-AREA-D01 | 지도 이미지 | API | Map.image | [위키](../docs/wiki/product/map.md) |
| MAP-AREA-D02 | 장소별 핀 위치 | API | Pin.x + Pin.y | [위키](../docs/wiki/product/map.md) |
| MAP-AREA-D03 | 장소별 핀 종류 | API | Pin.category | [위키](../docs/wiki/product/map.md) |
| MAP-AREA-D04 | 필터 선택지 | 브라우저 | 브라우저: Pins.items에서 실제 종류 추출; 필터 단위 미정 | [위키](../docs/wiki/product/map.md) |
| MAP-AREA-D05 | 선택 필터·선택 핀 | 브라우저 | 브라우저: 선택 필터·핀 | [위키](../docs/wiki/product/map.md) |
| MAP-OVERVIEW-D06 | 구역 이동 대상 | API | Pin.target | [위키](../docs/wiki/product/map.md) |
| MAP-AREA-D06 | 진입 대상 장소 | API | Space.mapTarget | [위키](../docs/wiki/product/map.md) |
| MAP-POPUP-D01 | 장소 이름 | API | Place.name | [위키](../docs/wiki/product/map.md) |
| MAP-POPUP-D02 | 운영 시간 | API | Place.hoursText | [위키](../docs/wiki/product/map.md) |
| MAP-POPUP-D03 | 간단한 소개 | API | Place.description | [위키](../docs/wiki/product/map.md) |
| MAP-POPUP-D04 | 위치 안내 | API | Place.locationText | [위키](../docs/wiki/product/map.md) |
| MAP-POPUP-D05 | 이용 안내 | API | Place.usage | [위키](../docs/wiki/product/map.md) |
| MAP-POPUP-D06 | 상세 대상 항목 | API | Place.spaceId | [위키](../docs/wiki/product/map.md) |
| MAP-OVERVIEW-D07 | 장소명 또는 번호 | API | Map.image (장소명·번호 포함; 구체 표기 방식은 디자인 협의) | [위키](../docs/wiki/product/map.md) |
| MAP-AREA-D07 | 장소명 또는 번호 | API | Map.image (장소명·번호 포함; 구체 표기 방식은 디자인 협의) | [위키](../docs/wiki/product/map.md) |
| MAP-OVERVIEW-D08 | 외부인 티켓존 위치 | API | TicketGuide.mapTarget | [위키](../docs/wiki/product/map.md) |
| HOME-D01 | 혼잡도 단계 | API | Crowding.savedLevel | [위키](../docs/wiki/product/home.md) |
| HOME-D02 | 운영 상태 | API | Crowding.status | [위키](../docs/wiki/product/home.md) |
| HOME-D03 | 마지막 수정 시각 | API | Crowding.updatedAt + Crowding.timeBasis | [위키](../docs/wiki/product/home.md) |
| HOME-D04 | 당일 입장 시작 시각 | API | Crowding.opensAt | [위키](../docs/wiki/product/home.md) |
| HOME-D05 | 당일 운영 종료 시각 | API | Crowding.closesAt | [위키](../docs/wiki/product/home.md) |
| HOME-D06 | 혼잡도 색상 | 프런트 | 프런트 고정 UI: Crowding.colorToken → 디자인 색상 | [위키](../docs/wiki/product/home.md) |
| HOME-D07 | 혼잡도 안내 문구 | 프런트 | 프런트 번역: Crowding.status; Crowding.message는 참고 | [위키](../docs/wiki/product/home.md) |
| HOME-D08 | 제공 언어 목록 | API | Config.languages | [위키](../docs/wiki/product/home.md) |
| HOME-D09 | 선택 언어 | 브라우저 | 브라우저: 선택 언어, 기본 ko | [위키](../docs/wiki/product/home.md) |
| HOME-D10 | 최신 공지 제목 | API | Notices.items[0].title | [위키](../docs/wiki/product/home.md) |
| HOME-D11 | 공지사항 URL | API | Config.links.universityNotices | [위키](../docs/wiki/product/home.md) |
| HOME-D12 | FAQ 연결 대상 | API | Config.links.faq | [위키](../docs/wiki/product/home.md) |
| HOME-D13 | 웰컴 데이 URL | 제외 | 제외: 웰컴데이(WELCOME-001) 기능 제거(2026-09-22) | [위키](../docs/wiki/product/home.md) |
| HOME-D14 | 공식 채널 링크 | API | Config.links.officialChannels | [위키](../docs/wiki/product/home.md) |
| HOME-D15 | 공식 채널 표시명·아이콘 | API | Channel.label + Channel.iconKey | [위키](../docs/wiki/product/home.md) |
| HOME-D16 | 당일 참여 시작 여부 | API | StampCard (GET /stamp-card가 404 STAMP_NOT_STARTED면 미참여) | [위키](../docs/wiki/product/home.md) |
| NOTICE-LIST-D01 | 제목 | API | Notice.title | [위키](../docs/wiki/product/notice.md) |
| NOTICE-LIST-D02 | 등록 시각 | API | Notice.createdAt | [위키](../docs/wiki/product/notice.md) |
| NOTICE-LIST-D03 | 본문 텍스트 | API | Notice.body | [위키](../docs/wiki/product/notice.md) |
| NOTICE-LIST-D04 | 본문 링크 | API | Notice.links | [위키](../docs/wiki/product/notice.md) |
| NOTICE-LIST-D05 | 공지 유형 | API | Notice.type | [위키](../docs/wiki/product/notice.md) |
| NOTICE-LIST-D06 | 언어별 번역 완료 여부 | API | 서버: 요청 공개 언어 번역이 완결된 공지만 Notices.items에 반환 | [위키](../docs/wiki/product/notice.md) |
| NOTICE-LIST-D07 | 새 공지 존재 여부 | 브라우저 | 브라우저: Notices.visibleIds와 현재 목록 차이 비교 | [위키](../docs/wiki/product/notice.md) |
| GOODS-LIST-D01 | 상품 이미지 | 보류 | 보류: 이미지 필드는 별도 마이그레이션 | [위키](../docs/wiki/product/goods.md) |
| GOODS-LIST-D02 | 상품명 | API | Goods.name | [위키](../docs/wiki/product/goods.md) |
| GOODS-LIST-D03 | 가격 | API | Goods.price | [위키](../docs/wiki/product/goods.md) |
| GOODS-LIST-D04 | 색상 | API | Goods.colors | [위키](../docs/wiki/product/goods.md) |
| GOODS-LIST-D05 | 사이즈 | API | Goods.sizes | [위키](../docs/wiki/product/goods.md) |
| GOODS-LIST-D06 | 색상×사이즈별 구매 가능 여부 | API | Availability.combinations | [위키](../docs/wiki/product/goods.md) |
| GOODS-DETAIL-D01 | 상품 이미지 | 보류 | 보류: 이미지 필드는 별도 마이그레이션 | [위키](../docs/wiki/product/goods.md) |
| GOODS-DETAIL-D02 | 상품명 | API | Goods.name | [위키](../docs/wiki/product/goods.md) |
| GOODS-DETAIL-D03 | 가격 | API | Goods.price | [위키](../docs/wiki/product/goods.md) |
| GOODS-DETAIL-D04 | 색상 | API | Goods.colors | [위키](../docs/wiki/product/goods.md) |
| GOODS-DETAIL-D05 | 사이즈 | API | Goods.sizes | [위키](../docs/wiki/product/goods.md) |
| GOODS-DETAIL-D06 | 색상×사이즈별 구매 가능 여부 | API | Availability.combinations | [위키](../docs/wiki/product/goods.md) |
| GOODS-DETAIL-D07 | 현장 확인·수령 안내 | 프런트 | 프런트 고정 UI: 현장 상품·색상·사이즈 확인 후 송금 안내 | [위키](../docs/wiki/product/goods.md) |
| GOODS-PAYMENT-D01 | 상품명 | API | PaymentGuide.name | [위키](../docs/wiki/product/goods.md) |
| GOODS-PAYMENT-D02 | 상품 가격 | API | PaymentGuide.price | [위키](../docs/wiki/product/goods.md) |
| GOODS-PAYMENT-D03 | 은행명 | API | BankAccount.bankName | [위키](../docs/wiki/product/goods.md) |
| GOODS-PAYMENT-D04 | 계좌번호 | API | BankAccount.accountNumber | [위키](../docs/wiki/product/goods.md) |
| GOODS-PAYMENT-D05 | 예금주 | API | BankAccount.holder | [위키](../docs/wiki/product/goods.md) |
| GOODS-PAYMENT-D06 | 현장 송금·수령 안내 | API | PaymentGuide.instructions | [위키](../docs/wiki/product/goods.md) |
| SHOW-LINEUP-D01 | 대표 사진 | API | Lineup.items[].image | [위키](../docs/wiki/product/lineup.md) |
| SHOW-LINEUP-D02 | 이름 | API | Lineup.items[].name | [위키](../docs/wiki/product/lineup.md) |
| SHOW-LINEUP-D03 | 공연 날짜 | API | Lineup.date | [위키](../docs/wiki/product/lineup.md) |
| SHOW-LINEUP-D04 | 분류 | API | Lineup.category | [위키](../docs/wiki/product/lineup.md) |
| SHOW-LINEUP-D05 | 공연 순서 | API | Lineup.items[].order | [위키](../docs/wiki/product/lineup.md) |
| SHOW-ARTIST-D01 | 대표 사진 | API | Artist.image | [위키](../docs/wiki/product/lineup.md) |
| SHOW-ARTIST-D02 | 이름 | API | Artist.name | [위키](../docs/wiki/product/lineup.md) |
| SHOW-ARTIST-D03 | 공연 날짜 | API | Artist.performances[].date | [위키](../docs/wiki/product/lineup.md) |
| SHOW-ARTIST-D04 | 공연 시간 | API | Artist.performances[].startsAt + Artist.performances[].endsAt | [위키](../docs/wiki/product/lineup.md) |
| SHOW-ARTIST-D05 | 소개 | API | Artist.introduction | [위키](../docs/wiki/product/lineup.md) |
| SHOW-ARTIST-D06 | SNS 링크 | API | Artist.socialLinks | [위키](../docs/wiki/product/lineup.md) |
| SHOW-ARTIST-D07 | 대표곡명 | API | Artist.songs[].label | [위키](../docs/wiki/product/lineup.md) |
| SHOW-ARTIST-D08 | 대표곡 유튜브 링크 | API | Artist.songs[].url | [위키](../docs/wiki/product/lineup.md) |
| SHOW-TIMETABLE-D01 | 공연명 | API | Performance.title | [위키](../docs/wiki/product/timetable.md) |
| SHOW-TIMETABLE-D02 | 공연 날짜 | API | Performance.date | [위키](../docs/wiki/product/timetable.md) |
| SHOW-TIMETABLE-D03 | 시작 시각 | API | Performance.startsAt | [위키](../docs/wiki/product/timetable.md) |
| SHOW-TIMETABLE-D04 | 종료 시각 | API | Performance.endsAt | [위키](../docs/wiki/product/timetable.md) |
| SHOW-TIMETABLE-D05 | 고정 반입 금지 물품 안내 | API | ProhibitedItems.message | [위키](../docs/wiki/product/timetable.md) |
| SHOW-TIMETABLE-D06 | 현재 시각 | 프런트 | 프런트: Meta.serverTime + 경과 시간, 축제 당일 17:00~22:00 오늘 열만 (시간축 변경과 별개) | [위키](../docs/wiki/product/timetable.md) |
| SHOW-POPUP-D01 | 공연명 | API | Performance.title | [위키](../docs/wiki/product/timetable.md) |
| SHOW-POPUP-D02 | 출연진 | API | Performance.artists | [위키](../docs/wiki/product/timetable.md) |
| SHOW-POPUP-D03 | 시작·종료 시간 | API | Performance.startsAt + Performance.endsAt | [위키](../docs/wiki/product/timetable.md) |
| SHOW-POPUP-D04 | 간단한 안내 | API | Performance.description | [위키](../docs/wiki/product/timetable.md) |
| TICKET-D01 | 이용 날짜 | API | TicketGuide.date (서버 KST 기준을 사용) | [위키](../docs/wiki/product/ticket.md) |
| TICKET-D02 | 1인 금액 | API | TicketGuide.unitPrice | [위키](../docs/wiki/product/ticket.md) |
| TICKET-D03 | 선택 인원 | 브라우저 | 브라우저: 기본·최소 1명, 토스 복귀 시 1명 | [위키](../docs/wiki/product/ticket.md) |
| TICKET-D04 | 총액 | 프런트 | 프런트: 인원 × Money.amount, 안전 정수 범위 확인 | [위키](../docs/wiki/product/ticket.md) |
| TICKET-D05 | 송금 제공 일정 | API | TicketGuide.transferOpensAt + TicketGuide.transferClosesAt | [위키](../docs/wiki/product/ticket.md) |
| TICKET-D06 | 현장 수령 시간 | API | TicketGuide.pickupOpensAt + TicketGuide.pickupClosesAt | [위키](../docs/wiki/product/ticket.md) |
| TICKET-D07 | 은행명 | API | BankAccount.bankName | [위키](../docs/wiki/product/ticket.md) |
| TICKET-D08 | 계좌번호 | API | TicketGuide.account (운영 밖 null) | [위키](../docs/wiki/product/ticket.md) |
| TICKET-D09 | 예금주 | API | BankAccount.holder | [위키](../docs/wiki/product/ticket.md) |
| TICKET-D10 | 티켓존 위치 | API | TicketGuide.mapTarget | [위키](../docs/wiki/product/ticket.md) |
| TICKET-D11 | 송금·수령·환불 안내 | API | TicketGuide.instructions | [위키](../docs/wiki/product/ticket.md) |
| STAMP-START-D01 | 축제 정보 | API | StampGuide.title + StampGuide.reward.name | [위키](../docs/wiki/product/stamp.md) |
| STAMP-START-D02 | 행사 기간 | API | StampGuide.dates | [위키](../docs/wiki/product/stamp.md) |
| STAMP-START-D03 | 참여 방법 | API | StampGuide.instructions | [위키](../docs/wiki/product/stamp.md) |
| STAMP-START-D04 | 지급 조건 | API | StampGuide.reward.notice | [위키](../docs/wiki/product/stamp.md) |
| STAMP-COLLECT-D01 | 참여 시작 여부 | API | StampCard (GET /stamp-card가 404 STAMP_NOT_STARTED면 미참여) | [위키](../docs/wiki/product/stamp.md) |
| STAMP-COLLECT-D02 | 당일 스탬프 개수 | API | StampCard.stamps (부스당 하루 1개, 최대 StampCard.dailyLimit) | [위키](../docs/wiki/product/stamp.md) |
| STAMP-COLLECT-D03 | 상품 수령 여부 | API | StampCard.rewardClaimed | [위키](../docs/wiki/product/stamp.md) |
| STAMP-COLLECT-D04 | 기록 기준 날짜 | API | StampCard.date (축제 시간대 자정 초기화) | [위키](../docs/wiki/product/stamp.md) |
| STAMP-COLLECT-D05 | 공통 QR 식별값 | API | StampCollectionInput.token ← 부스 QR 링크의 b 값 | [위키](../docs/wiki/product/stamp.md) |
| STAMP-REWARD-D01 | 담당자 제시·수령 인증 코드 입력 안내 | 프런트 | 프런트 고정 UI: 담당자에게 제시·수령 인증 코드 입력·확인 버튼; 상품 수령 버튼 없음 | [위키](../docs/wiki/product/stamp.md) |
| ADM-CROWD-D01 | 저장 혼잡도 | API | Crowding.savedLevel | [위키](../docs/wiki/product/admin/crowd.md) |
| ADM-CROWD-D02 | 운영 상태 | API | Crowding.operatingStatus | [위키](../docs/wiki/product/admin/crowd.md) |
| ADM-CROWD-D03 | 상태 색상 | API | Crowding.colorToken | [위키](../docs/wiki/product/admin/crowd.md) |
| ADM-CROWD-D04 | 사용자 노출 문구 | API | Crowding.message | [위키](../docs/wiki/product/admin/crowd.md) |
| ADM-CROWD-D05 | 최종 수정 시각 | API | Crowding.updatedAt | [위키](../docs/wiki/product/admin/crowd.md) |
| ADM-CROWD-D06 | 운영일 | API | Crowding.operatingDay | [위키](../docs/wiki/product/admin/crowd.md) |
| ADM-CROWD-D07 | 운영 시작 시각 | API | Crowding.opensAt | [위키](../docs/wiki/product/admin/crowd.md) |
| ADM-CROWD-D08 | 운영 종료 시각 | API | Crowding.closesAt | [위키](../docs/wiki/product/admin/crowd.md) |
| ADM-NOTICE-LIST-D01 | 공지 식별 정보 | API | AdminNotice.id | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-LIST-D02 | 유형 | API | AdminNotice.type | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-LIST-D03 | 제목 | API | AdminNotice.translations.ko.title | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-LIST-D04 | 최종 수정 시각 | API | AdminNotice.updatedAt | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-EDIT-D01 | 유형 | API | AdminNotice.type | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-EDIT-D02 | 한국어 제목 | API | AdminNotice.translations.ko.title | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-EDIT-D03 | 한국어 본문 | API | AdminNotice.translations.ko.body | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-EDIT-D05 | 템플릿 정보 | API | AdminNotice.templateId | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-EDIT-D06 | 언어별 번역 제목·본문 | API | AdminNotice.translations | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-DELETE-D01 | 삭제 대상 식별 정보 | API | AdminNotice.id | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-DELETE-D02 | 대상 공지 제목 | API | AdminNotice.translations.ko.title | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-TEMPLATE-D01 | 사용 가능 템플릿 목록 | API | Templates.items | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-TEMPLATE-D02 | 템플릿 한국어 제목 | API | Template.translations.ko.title | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-TEMPLATE-D03 | 템플릿 한국어 본문 | API | Template.translations.ko.body | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-TEMPLATE-D04 | 언어별 번역문 | API | Template.translations | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-GOODS-D01 | 상품 식별 정보 | API | Availability.goodsId | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-D02 | 상품명 | API | Availability.name | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-D03 | 색상×사이즈 조합 | API | AdminGoodsCombination.colorId + AdminGoodsCombination.sizeId | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-D04 | 판매 상태 | API | AdminGoodsCombination.status | [위키](../docs/wiki/product/admin/goods.md) |
| MAP-OVERVIEW-D09 | 혼잡도 최종 상태 | 제외 | 제외: 지도 혼잡도 표시 없음 | [위키](../docs/wiki/product/map.md) |
| MAP-OVERVIEW-D10 | 혼잡도 색상 | 제외 | 제외: 지도 혼잡도 색상 없음 | [위키](../docs/wiki/product/map.md) |
| MAP-OVERVIEW-D11 | 혼잡도 안내 문구 | 제외 | 제외: 지도 혼잡도 안내 없음 | [위키](../docs/wiki/product/map.md) |
| MAP-OVERVIEW-D12 | 혼잡도 수정 시각·최초 표시 | 제외 | 제외: 지도 혼잡도 수정 시각 없음 | [위키](../docs/wiki/product/map.md) |
| ADM-NOTICE-LIST-D05 | 최초 등록 시각 | API | AdminNotice.createdAt | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-EDIT-D07 | 본문 링크 | API | AdminNotice.links | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-NOTICE-EDIT-D08 | 번역 완료 상태 | API | AdminNotice.translations (수동 ko·en 필수 입력, 자동 번역 없음) | [위키](../docs/wiki/product/admin/notice.md) |
| ADM-GOODS-D05 | 남은 재고 수량 | 제외 | 제외: 실제 재고 수량 미관리 | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-D06 | 상품 전체 품절 여부 | API | Availability.allSoldOut | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-CROWD-D09 | 혼잡도 판단 기준 | 프런트 | 프런트 고정 UI: 운영자 판단 기준·85% 자동 만석 아님 | [위키](../docs/wiki/product/admin/crowd.md) |
| ADM-CROWD-HOURS-D01 | 운영일 | 제외 | 제외: 운영 시간은 개발자 등록, 관리자 편집 없음 | [위키](../docs/wiki/product/admin/crowd.md) |
| ADM-CROWD-HOURS-D02 | 입장 시작 시각 | 제외 | 제외: 운영 시간은 개발자 등록, 관리자 편집 없음 | [위키](../docs/wiki/product/admin/crowd.md) |
| ADM-CROWD-HOURS-D03 | 운영 종료 시각 | 제외 | 제외: 운영 시간은 개발자 등록, 관리자 편집 없음 | [위키](../docs/wiki/product/admin/crowd.md) |
| ADM-CROWD-HOURS-D04 | 기본값 적용 여부 | 제외 | 제외: 운영 시간은 개발자 등록, 관리자 편집 없음 | [위키](../docs/wiki/product/admin/crowd.md) |
| ADM-CROWD-HOURS-D05 | 입력 중 시간 | 제외 | 제외: 운영 시간은 개발자 등록, 관리자 편집 없음 | [위키](../docs/wiki/product/admin/crowd.md) |
| ADM-GOODS-PRODUCT-LIST-D01 | 상품 ID | API | Goods.id | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-PRODUCT-LIST-D02 | 상품명 | API | Goods.name | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-PRODUCT-LIST-D03 | 가격 | API | Goods.price | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-PRODUCT-LIST-D04 | 상품 이미지 | 보류 | 보류: 이미지 필드는 별도 마이그레이션 | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-PRODUCT-LIST-D05 | 색상 | API | Goods.colors | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-PRODUCT-LIST-D06 | 사이즈 | API | Goods.sizes | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-PRODUCT-EDIT-D01 | 상품 ID | API | Goods.id | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-PRODUCT-EDIT-D02 | 상품명 | API | Goods.name | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-PRODUCT-EDIT-D03 | 가격 | API | Goods.price | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-PRODUCT-EDIT-D04 | 상품 이미지 | 보류 | 보류: 이미지 필드는 별도 마이그레이션 | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-PRODUCT-EDIT-D05 | 색상 | API | Goods.colors | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-PRODUCT-EDIT-D06 | 사이즈 | API | Goods.sizes | [위키](../docs/wiki/product/admin/goods.md) |
| ADM-GOODS-PRODUCT-EDIT-D07 | 작성 중 입력값 | 브라우저 | 브라우저: 상품 작성 중 입력값 | [위키](../docs/wiki/product/admin/goods.md) |
| GOODS-DETAIL-D08 | 실제 제공 조합 | API | Availability.combinations | [위키](../docs/wiki/product/goods.md) |
| ADM-GOODS-PRODUCT-EDIT-D08 | 실제 제공 조합 | API | ProductInput.options | [위키](../docs/wiki/product/admin/goods.md) |
| SHOW-TIMETABLE-D07 | 반입 금지 물품 목록 | API | ProhibitedItems.items | [위키](../docs/wiki/product/timetable.md) |
| STAMP-REWARD-D02 | 수령 인증 결과 | API | StampReceiptVerificationInput.code → StampReceiptVerification.verified | [위키](../docs/wiki/product/stamp.md) |
| SHOW-LINEUP-D06 | 아티스트별 Hyped 누적 수 | API | ArtistHypedSummary.items[].hypedCount (artistId로 결합, CONTEST 제외) | [위키](../docs/wiki/product/lineup.md) |
| SHOW-ARTIST-D09 | Hyped 누적 수와 참여 가능 상태 | API | ArtistHypedSummary.items[].hypedCount + hypedEnabled → ArtistHypedIncrement.hypedCount | [위키](../docs/wiki/product/lineup.md) |
