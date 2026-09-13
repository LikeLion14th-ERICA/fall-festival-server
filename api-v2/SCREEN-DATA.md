# 화면 데이터 → API 필드

작성 데이터 183개를 API·브라우저 상태·고정 UI·보류로 추적합니다. 필드 경로는 응답 data 기준입니다. 폐기한 이미지 데이터 ID는 출처 표에 이력으로 보존합니다.

| 데이터 ID | 항목 | 처리 | 계약/프런트 책임 |
|---|---|---|---|
| BOOTH-LIST-D01 | 대표 사진 | API | Space.image |
| BOOTH-LIST-D02 | 이름 | API | Space.name |
| BOOTH-LIST-D03 | 위치 | API | Space.locationText |
| BOOTH-LIST-D04 | 분류 | API | Space.category |
| BOOTH-LIST-D05 | 별 저장 여부 | 브라우저 | 브라우저: savedSpaceIds |
| BOOTH-LIST-D06 | 선택 분류·스크롤 위치 | 브라우저 | 브라우저: 선택 분류·스크롤 |
| BOOTH-DETAIL-D01 | 대표 사진 | API | Space.image |
| BOOTH-DETAIL-D02 | 이름 | API | Space.name |
| BOOTH-DETAIL-D03 | 분류 | API | Space.category |
| BOOTH-DETAIL-D04 | 운영 주체 | API | Space.operator |
| BOOTH-DETAIL-D05 | 운영 시간 | API | Space.hoursText |
| BOOTH-DETAIL-D06 | 위치 | API | Space.locationText |
| BOOTH-DETAIL-D07 | 문의 SNS | API | Space.contact |
| BOOTH-DETAIL-D08 | 소개·설명 | API | Space.description |
| BOOTH-DETAIL-D09 | 체험 방법 | API | Space.experience |
| BOOTH-DETAIL-D10 | 진행 이벤트 | API | Space.events |
| BOOTH-DETAIL-D11 | 메뉴명 | API | Space.menu[].name |
| BOOTH-DETAIL-D12 | 가격 | API | Space.menu[].price |
| BOOTH-DETAIL-D13 | 별 저장 여부 | 브라우저 | 브라우저: savedSpaceIds |
| BOOTH-DETAIL-D14 | 대상 구역·장소 연결 정보 | API | Space.mapTarget |
| BOOTH-DETAIL-D15 | 현재 상세 링크 | 브라우저 | 브라우저: 현재 상세 페이지 URL |
| MAP-OVERVIEW-D01 | 지도 이미지 | API | Map.image |
| MAP-OVERVIEW-D02 | 장소별 핀 위치 | API | Pin.x + Pin.y |
| MAP-OVERVIEW-D03 | 장소별 핀 종류 | API | Pin.category |
| MAP-OVERVIEW-D04 | 필터 선택지 | 브라우저 | 브라우저: Pins.items에서 실제 종류 추출; 필터 단위 미정 |
| MAP-OVERVIEW-D05 | 선택 필터·선택 핀 | 브라우저 | 브라우저: 선택 필터·핀 |
| MAP-AREA-D01 | 지도 이미지 | API | Map.image |
| MAP-AREA-D02 | 장소별 핀 위치 | API | Pin.x + Pin.y |
| MAP-AREA-D03 | 장소별 핀 종류 | API | Pin.category |
| MAP-AREA-D04 | 필터 선택지 | 브라우저 | 브라우저: Pins.items에서 실제 종류 추출; 필터 단위 미정 |
| MAP-AREA-D05 | 선택 필터·선택 핀 | 브라우저 | 브라우저: 선택 필터·핀 |
| MAP-OVERVIEW-D06 | 구역 이동 대상 | API | Pin.target |
| MAP-AREA-D06 | 진입 대상 장소 | API | Space.mapTarget |
| MAP-POPUP-D01 | 장소 이름 | API | Place.name |
| MAP-POPUP-D02 | 운영 시간 | API | Place.hoursText |
| MAP-POPUP-D03 | 간단한 소개 | API | Place.description |
| MAP-POPUP-D04 | 위치 안내 | API | Place.locationText |
| MAP-POPUP-D05 | 이용 안내 | API | Place.usage |
| MAP-POPUP-D06 | 상세 대상 항목 | API | Place.spaceId |
| MAP-OVERVIEW-D07 | 장소명 또는 번호 | API | Map.image (장소명·번호는 최종 이미지 자산에 포함; 미정) |
| MAP-AREA-D07 | 장소명 또는 번호 | API | Map.image (장소명·번호는 최종 이미지 자산에 포함; 미정) |
| MAP-OVERVIEW-D08 | 외부인 티켓존 위치 | API | TicketGuide.mapTarget |
| HOME-D01 | 혼잡도 단계 | API | Crowding.savedLevel |
| HOME-D02 | 운영 상태 | API | Crowding.status |
| HOME-D03 | 마지막 수정 시각 | API | Crowding.updatedAt + Crowding.timeBasis |
| HOME-D04 | 당일 입장 시작 시각 | API | Crowding.opensAt |
| HOME-D05 | 당일 운영 종료 시각 | API | Crowding.closesAt |
| HOME-D06 | 혼잡도 색상 | 프런트 | 프런트 고정 UI: Crowding.colorToken → 디자인 색상 |
| HOME-D07 | 혼잡도 안내 문구 | 프런트 | 프런트 번역: Crowding.status; Crowding.message는 참고 |
| HOME-D08 | 제공 언어 목록 | API | Config.languages |
| HOME-D09 | 선택 언어 | 브라우저 | 브라우저: 선택 언어, 기본 ko |
| HOME-D10 | 최신 공지 제목 | API | Notices.items[0].title |
| HOME-D11 | 공지사항 URL | API | Config.links.universityNotices |
| HOME-D12 | FAQ 연결 대상 | 보류 | 보류: Config.faqEnabled=false, FAQ 대상 미정 |
| HOME-D13 | 웰컴 데이 URL | API | Config.links.welcomeDay |
| HOME-D14 | 공식 채널 링크 | API | Config.links.officialChannels |
| HOME-D15 | 공식 채널 표시명·아이콘 | API | Channel.label + Channel.iconKey |
| HOME-D16 | 당일 참여 시작 여부 | 브라우저 | 브라우저: 당일 stamp.started |
| NOTICE-LIST-D01 | 제목 | API | Notice.title |
| NOTICE-LIST-D02 | 등록 시각 | API | Notice.createdAt |
| NOTICE-LIST-D03 | 본문 텍스트 | API | Notice.body |
| NOTICE-LIST-D04 | 본문 링크 | API | Notice.links |
| NOTICE-LIST-D05 | 공지 유형 | API | Notice.type |
| NOTICE-LIST-D06 | 언어별 번역 완료 여부 | API | 서버: Translations의 READY 필터 후 Notices.items 반환 |
| NOTICE-LIST-D07 | 새 공지 존재 여부 | 브라우저 | 브라우저: Notices.visibleIds와 현재 목록 차이 비교 |
| GOODS-LIST-D01 | 상품 이미지 | API | Goods.image |
| GOODS-LIST-D02 | 상품명 | API | Goods.name |
| GOODS-LIST-D03 | 가격 | API | Goods.price |
| GOODS-LIST-D04 | 색상 | API | Goods.colors |
| GOODS-LIST-D05 | 사이즈 | API | Goods.sizes |
| GOODS-LIST-D06 | 색상×사이즈별 구매 가능 여부 | API | Availability.variants |
| GOODS-DETAIL-D01 | 상품 이미지 | API | Goods.colorImages |
| GOODS-DETAIL-D02 | 상품명 | API | Goods.name |
| GOODS-DETAIL-D03 | 가격 | API | Goods.price |
| GOODS-DETAIL-D04 | 색상 | API | Goods.colors |
| GOODS-DETAIL-D05 | 사이즈 | API | Goods.sizes |
| GOODS-DETAIL-D06 | 색상×사이즈별 구매 가능 여부 | API | Availability.variants |
| GOODS-DETAIL-D07 | 현장 확인·수령 안내 | 프런트 | 프런트 고정 UI: 현장 상품·색상·사이즈 확인 후 송금 안내 |
| GOODS-PAYMENT-D01 | 상품명 | API | PaymentGuide.name |
| GOODS-PAYMENT-D02 | 상품 가격 | API | PaymentGuide.price |
| GOODS-PAYMENT-D03 | 은행명 | API | BankAccount.bankName |
| GOODS-PAYMENT-D04 | 계좌번호 | API | BankAccount.accountNumber |
| GOODS-PAYMENT-D05 | 예금주 | API | BankAccount.holder |
| GOODS-PAYMENT-D06 | 현장 송금·수령 안내 | API | PaymentGuide.instructions |
| SHOW-LINEUP-D01 | 대표 사진 | API | Lineup.items[].image |
| SHOW-LINEUP-D02 | 이름 | API | Lineup.items[].name |
| SHOW-LINEUP-D03 | 공연 날짜 | API | Lineup.date |
| SHOW-LINEUP-D04 | 분류 | API | Lineup.category |
| SHOW-LINEUP-D05 | 공연 순서 | API | Lineup.items[].order |
| SHOW-ARTIST-D01 | 대표 사진 | API | Artist.image |
| SHOW-ARTIST-D02 | 이름 | API | Artist.name |
| SHOW-ARTIST-D03 | 공연 날짜 | API | Artist.performances[].date |
| SHOW-ARTIST-D04 | 공연 시간 | API | Artist.performances[].startsAt + Artist.performances[].endsAt |
| SHOW-ARTIST-D05 | 소개 | API | Artist.introduction |
| SHOW-ARTIST-D06 | SNS 링크 | API | Artist.socialLinks |
| SHOW-ARTIST-D07 | 대표곡명 | API | Artist.songs[].label |
| SHOW-ARTIST-D08 | 대표곡 유튜브 링크 | API | Artist.songs[].url |
| SHOW-TIMETABLE-D01 | 공연명 | API | Performance.title |
| SHOW-TIMETABLE-D02 | 공연 날짜 | API | Performance.date |
| SHOW-TIMETABLE-D03 | 시작 시각 | API | Performance.startsAt |
| SHOW-TIMETABLE-D04 | 종료 시각 | API | Performance.endsAt |
| SHOW-TIMETABLE-D05 | 중요 공지 문구 | API | PerformanceAlert.message |
| SHOW-TIMETABLE-D06 | 현재 시각 | 프런트 | 프런트: Meta.serverTime + 경과 시간, Timetable.axis 범위 내 오늘 열만 |
| SHOW-POPUP-D01 | 공연명 | API | Performance.title |
| SHOW-POPUP-D02 | 출연진 | API | Performance.artists |
| SHOW-POPUP-D03 | 시작·종료 시간 | API | Performance.startsAt + Performance.endsAt |
| SHOW-POPUP-D04 | 간단한 안내 | API | Performance.description |
| TICKET-D01 | 이용 날짜 | API | TicketGuide.date (서버 KST 기준을 사용) |
| TICKET-D02 | 1인 금액 | API | TicketGuide.unitPrice |
| TICKET-D03 | 선택 인원 | 브라우저 | 브라우저: 기본·최소 1명, 토스 복귀 시 1명 |
| TICKET-D04 | 총액 | 프런트 | 프런트: 인원 × Money.amount, 안전 정수 범위 확인 |
| TICKET-D05 | 송금 제공 일정 | API | TicketGuide.transferOpensAt + TicketGuide.transferClosesAt |
| TICKET-D06 | 현장 수령 시간 | API | TicketGuide.pickupOpensAt + TicketGuide.pickupClosesAt |
| TICKET-D07 | 은행명 | API | BankAccount.bankName |
| TICKET-D08 | 계좌번호 | API | TicketGuide.account (운영 밖 null) |
| TICKET-D09 | 예금주 | API | BankAccount.holder |
| TICKET-D10 | 티켓존 위치 | API | TicketGuide.mapTarget |
| TICKET-D11 | 송금·수령·환불 안내 | API | TicketGuide.instructions |
| STAMP-START-D01 | 축제 정보 | API | StampGuide.title + StampGuide.reward.name |
| STAMP-START-D02 | 행사 기간 | API | StampGuide.dates |
| STAMP-START-D03 | 참여 방법 | API | StampGuide.instructions |
| STAMP-START-D04 | 지급 조건 | API | StampGuide.reward.notice |
| STAMP-COLLECT-D01 | 참여 시작 여부 | 브라우저 | 브라우저: stamp.started |
| STAMP-COLLECT-D02 | 당일 스탬프 개수 | 브라우저 | 브라우저: stamp.count (0~4) |
| STAMP-COLLECT-D03 | 상품 수령 여부 | 브라우저 | 브라우저: stamp.claimed |
| STAMP-COLLECT-D04 | 기록 기준 날짜 | 브라우저 | 브라우저: stamp.date (KST 자정 초기화) |
| STAMP-COLLECT-D05 | 공통 QR 식별값 | API | StampGuide.qrValue (제공 책임·배포 방식은 검토 필요) |
| STAMP-REWARD-D01 | 담당자 제시·직접 선택 금지 문구 | 프런트 | 프런트 고정 UI: 담당자에게 제시·사용자 직접 수령 선택 금지 안내 |
| ADM-CROWD-D01 | 저장 혼잡도 | API | Crowding.savedLevel |
| ADM-CROWD-D02 | 운영 상태 | API | Crowding.operatingStatus |
| ADM-CROWD-D03 | 상태 색상 | API | Crowding.colorToken |
| ADM-CROWD-D04 | 사용자 노출 문구 | API | Crowding.message |
| ADM-CROWD-D05 | 최종 수정 시각 | API | Crowding.updatedAt |
| ADM-CROWD-D06 | 운영일 | API | Crowding.operatingDay |
| ADM-CROWD-D07 | 운영 시작 시각 | API | Crowding.opensAt |
| ADM-CROWD-D08 | 운영 종료 시각 | API | Crowding.closesAt |
| ADM-NOTICE-LIST-D01 | 공지 식별 정보 | API | AdminNotice.id |
| ADM-NOTICE-LIST-D02 | 유형 | API | AdminNotice.type |
| ADM-NOTICE-LIST-D03 | 제목 | API | AdminNotice.translations.ko.title |
| ADM-NOTICE-LIST-D04 | 최종 수정 시각 | API | AdminNotice.updatedAt |
| ADM-NOTICE-EDIT-D01 | 유형 | API | AdminNotice.type |
| ADM-NOTICE-EDIT-D02 | 한국어 제목 | API | AdminNotice.translations.ko.title |
| ADM-NOTICE-EDIT-D03 | 한국어 본문 | API | AdminNotice.translations.ko.body |
| ADM-NOTICE-EDIT-D05 | 템플릿 정보 | API | AdminNotice.templateId |
| ADM-NOTICE-EDIT-D06 | 언어별 번역 제목·본문 | API | AdminNotice.translations |
| ADM-NOTICE-DELETE-D01 | 삭제 대상 식별 정보 | API | AdminNotice.id |
| ADM-NOTICE-DELETE-D02 | 대상 공지 제목 | API | AdminNotice.translations.ko.title |
| ADM-NOTICE-TEMPLATE-D01 | 사용 가능 템플릿 목록 | API | Templates.items |
| ADM-NOTICE-TEMPLATE-D02 | 템플릿 한국어 제목 | API | Template.translations.ko.title |
| ADM-NOTICE-TEMPLATE-D03 | 템플릿 한국어 본문 | API | Template.translations.ko.body |
| ADM-NOTICE-TEMPLATE-D04 | 언어별 번역문 | API | Template.translations |
| ADM-GOODS-D01 | 상품 식별 정보 | API | Inventory.goodsId |
| ADM-GOODS-D02 | 상품명 | API | Inventory.name |
| ADM-GOODS-D03 | 색상×사이즈 조합 | API | Inventory.variants[].colorId + Inventory.variants[].sizeId |
| ADM-GOODS-D04 | 재고 상태 | API | Inventory.variants[].status |
| MAP-OVERVIEW-D09 | 혼잡도 최종 상태 | API | Crowding.status |
| MAP-OVERVIEW-D10 | 혼잡도 색상 | API | Crowding.colorToken |
| MAP-OVERVIEW-D11 | 혼잡도 안내 문구 | API | Crowding.message |
| MAP-OVERVIEW-D12 | 혼잡도 수정 시각·최초 표시 | API | Crowding.updatedAt + Crowding.timeBasis |
| ADM-NOTICE-LIST-D05 | 최초 등록 시각 | API | AdminNotice.createdAt |
| ADM-NOTICE-EDIT-D07 | 본문 링크 | API | AdminNotice.links |
| ADM-NOTICE-EDIT-D08 | 번역 완료 상태 | API | AdminNotice.translations + NoticeTranslationPreview.canSave |
| ADM-GOODS-D05 | 남은 재고 수량 | API | Inventory.variants[].quantity |
| ADM-GOODS-D06 | 상품 전체 품절 여부 | API | Inventory.allSoldOut |
| ADM-CROWD-D09 | 혼잡도 판단 기준 | 프런트 | 프런트 고정 UI: 운영자 판단 기준·85% 자동 만석 아님 |
| ADM-CROWD-HOURS-D01 | 운영일 | API | OperatingHours.operatingDay |
| ADM-CROWD-HOURS-D02 | 입장 시작 시각 | API | OperatingHours.opensAt |
| ADM-CROWD-HOURS-D03 | 운영 종료 시각 | API | OperatingHours.closesAt |
| ADM-CROWD-HOURS-D04 | 기본값 적용 여부 | API | OperatingHours.isDefault |
| ADM-CROWD-HOURS-D05 | 입력 중 시간 | 브라우저 | 브라우저: 저장 전 운영 시간 입력값 |
| ADM-GOODS-PRODUCT-LIST-D01 | 상품 ID | API | Goods.id |
| ADM-GOODS-PRODUCT-LIST-D02 | 상품명 | API | Goods.name |
| ADM-GOODS-PRODUCT-LIST-D03 | 가격 | API | Goods.price |
| ADM-GOODS-PRODUCT-LIST-D04 | 상품 이미지 | API | Goods.images |
| ADM-GOODS-PRODUCT-LIST-D05 | 색상 | API | Goods.colors |
| ADM-GOODS-PRODUCT-LIST-D06 | 사이즈 | API | Goods.sizes |
| ADM-GOODS-PRODUCT-EDIT-D01 | 상품 ID | API | Goods.id |
| ADM-GOODS-PRODUCT-EDIT-D02 | 상품명 | API | Goods.name |
| ADM-GOODS-PRODUCT-EDIT-D03 | 가격 | API | Goods.price |
| ADM-GOODS-PRODUCT-EDIT-D04 | 상품 이미지 | API | Goods.images |
| ADM-GOODS-PRODUCT-EDIT-D05 | 색상 | API | Goods.colors |
| ADM-GOODS-PRODUCT-EDIT-D06 | 사이즈 | API | Goods.sizes |
| ADM-GOODS-PRODUCT-EDIT-D07 | 작성 중 입력값 | 브라우저 | 브라우저: 상품 작성 중 입력값 |
