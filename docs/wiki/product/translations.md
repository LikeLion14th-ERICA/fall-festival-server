# 승인 번역표

[위키 홈](../README.md) · 읽는 때: 사용자 화면 문자열·카탈로그 번역·공지 외 콘텐츠 번역 작성

2026-09-18에 받은 번역 자료(한국어·영어·중국어 간체, 24쪽)를 화면별로 옮긴 표다.
[다국어](../engineering/i18n.md) 규칙에 따라 앱 텍스트와 카탈로그 번역은 이 표와
[학교 용어](terminology.md)의 표기만 사용한다. 일본어 번역은 아직 받지 않았다.

- 중국어는 원본 PDF의 글자 렌더링으로 옮겼다. PDF 텍스트 레이어는 `学`·`区`·`见`·`会`
  같은 여러 글자를 `页`로 잘못 매핑하므로 복사한 텍스트를 그대로 쓰지 않는다.
- 서버가 만드는 문구는 혼잡도 `message`뿐이며 `CrowdingMessages`에 반영했다. 나머지는
  프런트엔드 문자열 또는 catalog manifest 번역으로 쓴다.
- 언어 공개는 이 표의 존재와 별개다. 해당 언어의 모든 콘텐츠가 게시 revision에 들어가고
  `PUBLIC_LOCALES`에 넣기 전에는 `LOCALE_NOT_READY`를 유지한다
  ([서버의 언어 공개](../engineering/i18n.md#서버의-언어-공개)).

## 공통·홈

| 한국어 | 영어 | 중국어 간체 |
|---|---|---|
| 홈 | Home | 首页 |
| 굿즈샵 | Merch | 周边商店 |
| 공연 | Shows | 演出 |
| 부스&마켓 | Booths & Market | 展位市集 |
| 지도 | Map | 地图 |
| 총학생회 | Student Council | 校学生会 |
| 공지 | Notices | 公告 |
| 자세히 보기 | View More | 查看更多 |
| FAQ | FAQ | 常见问题 |
| 외부인티켓 | Visitor Tickets | 访客票 |
| 스탬프투어 | Stamp Rally | 集章活动 |
| 웰컴데이 | Welcome Day | 欢迎日 |
| 닫기 | Close | 关闭 |
| 공유하기 | Share | 分享 |

## 재학생존 혼잡도

상태 이름과 짧은 안내다. 서버 `message`는 아래 문장 열을 사용한다.

| 한국어 | 영어 | 중국어 간체 |
|---|---|---|
| 재학생존 혼잡도 | Student Zone Crowd Level | 本校学生区拥挤度 |
| 실시간 재학생존 혼잡도 | Live Student Zone Crowd Level | 实时本校学生区拥挤度 |
| 업데이트 | Updated | 最近更新 |
| {시각} 업데이트 | Updated {시각} | {시각} 更新 |
| 여유 | Low | 空闲 |
| 보통 | Moderate | 适中 |
| 혼잡 | Crowded | 拥挤 |
| 만석 | Full | 已满 |
| 운영 전 | Not Open Yet | 尚未开放 |
| 운영 종료 | Closed | 已关闭 |
| 확인 불가 | Unavailable | 暂不可用 |
| 혼잡도 정보를 불러오지 못했어요 | Unable to load crowd level | 无法加载拥挤度信息 |

| 상태 | 서버 `message` 한국어 | 영어 | 중국어 간체 |
|---|---|---|---|
| `RELAXED` | 재학생존의 공간이 많이 남았어요. | Plenty of space available | 空间充足 |
| `MODERATE` | 재학생존의 공간이 절반 이상 찼어요. | At least half full | 已占用一半以上 |
| `CROWDED` | 재학생존이 많이 혼잡해요. | Very crowded | 非常拥挤 |
| `FULL` | 재학생존이 꽉 차서 외부인존에서만 즐길 수 있어요. | The Student Zone is full. Please use the Visitor Zone. | 本校学生区已满，请前往访客区。 |
| `BEFORE_OPEN` | 오늘 재학생존 입장은 {시각}에 시작해요 | Student Zone entry starts at {시각} today | 今日学生区{시각}开放入场 |
| `CLOSED` | 오늘 재학생존 운영이 종료됐어요 | The Student Zone is closed for today | 今日学生区已关闭 |

## 굿즈샵·결제

| 한국어 | 영어 | 중국어 간체 |
|---|---|---|
| 굿즈샵 | Merch Shop | 周边商店 |
| 축제 공식 굿즈 현장 수령 | Official Merch On-site Pickup | 官方周边现场领取 |
| 2026 축제 공식 · 굿즈 현장 판매 전용 | 2026 Official Festival Merch · On-site Only | 2026 官方周边 · 仅限现场购买 |
| 색상 선택 | Select Color | 选择颜色 |
| 사이즈 선택 | Select Size | 选择尺码 |
| 구매 가능 | Available | 有货 |
| 품절 | Sold Out | 售罄 |
| 굿즈 부스에서 담당자가 실제 재고를 확인한 뒤 지급합니다 | Staff will confirm stock at the merch booth before handing over your item | 工作人员将在周边摊位确认库存后发放商品 |
| 계좌 확인 | Bank Details | 查看账号 |
| 계좌 안내 | Bank Details | 转账信息 |
| 입금 계좌 | Account Number | 收款账号 |
| 은행 | Bank | 银行 |
| 예금주 | Account Holder | 户名 |
| 구매 상품 | Order Details | 订单详情 |
| 구매 상품명 | Item | 商品 |
| 선택 사항 | Options | 选项 |
| 최종 금액 | Total | 合计 |
| 원 | KRW | 韩元 |
| 토스로 송금하기 | Transfer with Toss | 使用 Toss 转账 |
| 계좌번호 복사하기 | Copy Account Number | 复制账号 |
| 현장 송금 수령 안내 | Bank Transfer & On-site Pickup | 转账及现场领取指南 |
| 1. 굿즈 부스의 담당자에게 원하는 상품과 사이즈를 말해주세요 | 1. Tell the staff at the merch booth which item and size you want | 1. 请告知周边摊位工作人员您想要的商品和尺码。 |
| 2. 담당자가 실제 재고를 확인해 드립니다 | 2. Staff will check stock availability | 2. 工作人员将确认库存。 |
| 3. 안내된 계좌로 송금한 뒤 완료 화면을 담당자에게 보여주세요 | 3. Transfer to the account shown and show staff the confirmation screen. | 3. 请转账至所示账户，并向工作人员出示转账成功页面。 |
| 4. 담당자가 송금 내역을 확인 후 현장에서 바로 지급합니다 | 4. Once the transfer is confirmed, staff will hand you your item on site | 4. 工作人员确认转账后，将现场发放商品。 |
| 송금 후 완료 화면을 직원에게 보여주세요 | After transferring, show staff the confirmation screen. | 转账后，请向工作人员出示转账成功页面。 |

상품명(운영 데이터): 야구 유니폼 Baseball Jersey 棒球球衣 · 축구 유니폼 Football Jersey
足球球衣 · 바람막이 Windbreaker 防风外套 · 반다나 Bandana 头巾 · 슬로건 Cheering Towel
应援毛巾 · 짐색 Drawstring Bag 抽绳包 · 키캡키링 Keycap Keychain 键帽钥匙扣

## 공연

| 한국어 | 영어 | 중국어 간체 |
|---|---|---|
| 라인업 | Lineup | 阵容 |
| 타임테이블 | Timetable | 时间表 |
| 아티스트 | Artists | 艺人 |
| 콘테스트 | Contest | 比赛 |
| 행사 | Events | 活动 |
| 아티스트 사진을 눌러 정보를 확인하세요 | Tap an artist for details | 点击艺人照片查看详情 |
| 타임테이블을 눌러 정보를 확인하세요 | Tap the Timetable for details. | 点击时间表查看详情。 |

## 부스&마켓

| 한국어 | 영어 | 중국어 간체 |
|---|---|---|
| 부스 | Booth | 展位 |
| 플리마켓 | Flea Market | 跳蚤市场 |
| 주점 | Pub | 酒水摊位 |
| 푸드트럭 | Food Truck | 餐车 |
| 총학생회 부스 | Student Council Booth | 学生会展位 |
| 프로모션 부스 | Promotion Booth | 宣传展位 |
| 운영 주체 | Operator | 运营方 |
| 진행 이벤트 | Events | 活动 |
| 메뉴 판매 품목 | Menu | 菜单 |
| 학생회 | Student Council | 学生会 |
| 찜하기 | Save | 收藏 |
| 찜하기(찜 완료 후 표기) | Saved | 已收藏 |
| 지도에서 위치 보기 | View on Map | 在地图上查看 |
| 노상존 | Street Zone | 露天区 |
| 푸드트럭존 | Food Truck Zone | 餐车区 |
| 18:00 ~ 익일 00:00 | 18:00–00:00 Next Day | 18:00–次日00:00 |

### 주점 목록(운영 데이터)

catalog manifest의 공간 번역으로 쓴다. 번호는 원본 표의 순서다.

| 한국어 | 영어 | 중국어 간체 |
|---|---|---|
| 1. 썬더이지스 | Thunder AEGIS | 雷霆 AEGIS |
| 운영주체 · 첨단융합대학 동아리 AEGIS | Operator · AEGIS, College of Advanced Technology and Convergence | 运营方 · 尖端技术融合学院 AEGIS 社团 |
| 닭꼬치/염통꼬치 · 오리훈제구이 · 소시지야채볶음 | Chicken Skewers / Chicken Heart Skewers · Grilled Smoked Duck · Stir-Fried Sausage & Vegetables | 鸡肉串 / 鸡心串 · 烤烟熏鸭肉 · 香肠炒蔬菜 |
| 2. 최가네포차 | Choi's Pocha | 崔家大排档 |
| 운영주체 · 국방지능정보융합공학부 국방전략기술공학과 학생회 | Operator · Student Council, Department of Naval Strategic Technology Engineering | 运营方 · 国防智能信息融合工程学部·海军战略技术工程系学生会 |
| 흑후추 우삼겹 볶음 · 최가네 닭볶음 · 두부김치 | Black Pepper Beef Belly Stir-Fry · Choi's Stir-Fried Chicken · Tofu with Kimchi | 黑椒炒牛五花 · 崔家炒鸡 · 豆腐配泡菜 |
| 3. 포토부스 | Photo Booth | 拍照亭 |
| 4. 안녕하세요글문통입니다잘부탁드립니다 | Hello, We're Geulmuntong, Nice to Meet You | 大家好我们是文通请多关照 |
| 운영주체 · 글로벌문화통상학부 학생회 | Operator · Student Council, School of Global Culture and Commerce | 运营方 · 全球文化与贸易学部学生会 |
| 김치전 · 국물무뼈닭발 · 제육볶음 | Kimchi Pancake · Boneless Chicken Feet in Spicy Broth · Spicy Pork Stir-Fry | 泡菜煎饼 · 汤汁无骨辣鸡爪 · 辣炒猪肉 |
| 5. 남월우주정거장점 | Namwol Space Station Branch | Namwol 空间站店 |
| 운영주체 · 중앙동아리 HYCD | Operator · HYCD | 运营方 · 中央社团 HYCD |
| 직화 백짬뽕 칼국수 · 직화 우삼겹 숙주 볶음 · 스페이스X 스테이크 | Flame-Seared White Jjamppong Kalguksu · Flame-Seared Beef Belly & Bean Sprout Stir-Fry · SpaceX Steak | 直火白汤刀切面 · 直火牛五花炒豆芽 · SpaceX 牛排 |
| 6. 카운셀러(타로부스) | Counselor (Tarot Booth) | 塔罗咨询摊位 |
| 6. 프로모션 부스 | Promotion Booth | 宣传展位 |
| 7~10. 노상존 | Street Zone | 露天区 |
| 11~12. 푸드트럭존 | Food Truck Zone | 餐车区 |

## 외부인 티켓

| 한국어 | 영어 | 중국어 간체 |
|---|---|---|
| 외부인 티켓 | Visitor Tickets | 访客票 |
| 오늘 | Today | 今天 |
| 화 / 수 / 목 | Tue / Wed / Thu | 周二 / 周三 / 周四 |
| 외부인 1인 당일 입장권 | Same-Day Visitor Ticket | 访客当日票 |
| 1인 | 1 Person | 1人 |
| 환경부담금 포함 | Includes Eco Fee | 含环保费 |
| 인원 | People | 人数 |
| 금액 | Total | 金额 |
| 당일 구매한 티켓은 당일에만 사용할 수 있으며 다른 날짜로 이월 되지 않습니다. 사용하지 않은 티켓은 환불이 어려울 수 있으니 방문 일정을 확인한 뒤 결제해 주세요. | Valid only on the date of purchase. Tickets cannot be used on another date, and unused tickets may not be refundable. Please check your visit date before paying. | 门票仅限购买当天使用，不可转至其他日期。未使用的门票可能无法退款，请确认到访日期后再付款。 |
| 외부인 티켓 구매하기 | Buy Ticket | 购买门票 |
| 티켓존 위치 확인하기 | Find Ticket Booth | 查看售票处 |
| 구매 및 입장 방법 | Purchase & Entry | 购票及入场 |
| 1. 외부인 티켓존 방문: 외부인 티켓 구매 부스로 이동 | 1. Visit the Ticket Booth | 1. 前往售票处 |
| 2. 티켓 금액 송금: 버튼을 눌러 지정 계좌와 금액이 입력된 송금 화면으로 이동 | 2. Transfer the Ticket Fee: Tap the button to open the pre-filled transfer screen | 2. 支付票款：点击按钮进入已填写账号和金额的转账页面 |
| 3. 송금 완료 화면 확인: 완료 화면을 닫지 말고 티켓 부스 스태프에게 제시 | 3. Show Transfer Confirmation: Keep the confirmation screen open and show it to staff | 3. 出示转账成功页面：请勿关闭页面，并向工作人员出示 |
| 4. 팔찌 수령 후 입장: 확인 후 팔찌를 받고 외부인 존에서 관람 | 4. Get Your Wristband & Enter: After verification, enter the Visitor Zone | 4. 领取手环并入场：确认后进入访客区观演 |
| 외부인 티켓 결제 | Visitor Ticket Payment | 访客票支付 |
| 상품명 | Item | 商品 |
| 외부인 입장 1일권 | 1-Day Visitor Ticket | 访客一日票 |
| 인원 수 | People | 人数 |
| 관람 위치 | Viewing Area | 观演区域 |
| 토스로 바로 송금 | Transfer with Toss | 使用 Toss 转账 |
| 버튼을 누르면 받는 계좌와 결제 금액이 입력된 토스 송금 화면으로 이동합니다. | Tap to open Toss with the account and amount pre-filled. | 点击后进入已填写收款账号和金额的 Toss 转账页面。 |
| 송금 완료 후 화면을 부스 관계자에게 보여주세요. | Show the transfer confirmation screen to booth staff. | 转账后，请向摊位工作人员出示转账成功页面。 |
| 토스가 설치되어 있지 않나요? | Don't have Toss? | 没有安装 Toss？ |
| 아래 계좌번호를 복사해 사용하는 은행 앱에서 송금하세요 | Copy the account number below and transfer using your banking app. | 复制下方账号，并使用您的银行 App 转账。 |

## 스탬프투어

| 한국어 | 영어 | 중국어 간체 |
|---|---|---|
| 스탬프 투어 | Stamp Rally | 集章活动 |
| 부스를 돌고 몬스터 음료 받자 | Visit Booths, Get a Monster Drink | 逛展位，领 Monster 饮料 |
| 상품은 하루 1회 수령 가능 매일 밤 12시 스탬프 초기화 | One prize per day. Stamps reset daily at midnight | 奖品每日限领1次，印章每天0点重置 |
| 참여 방법 | How to Participate | 参与方式 |
| 멋사 부스에서 QR을 스캔해 시작 스탬프 1개 적립 | Scan the QR code at the LIKELION Booth to get your first stamp | 在 LIKELION 展位扫描二维码，获得第1枚印章 |
| 다른 부스 체험 후 운영자가 보여주는 QR 스캔 | After trying activities at other booths, scan the QR code shown by staff. | 体验其他展位后，扫描工作人员出示的二维码 |
| 총 4개를 다 모은 뒤 멋사 부스에서 몬스터 수령 | Collect all 4 stamps and claim your Monster at the LIKELION Booth | 集满4枚印章后，前往 LIKELION 展位领取 Monster |
| 상품은 준비 수량 소진 시 지급이 종료됩니다 | Prizes available while supplies last | 奖品数量有限，领完即止 |
| 시작하기 | Start | 开始集章 |
| 스탬프 4개를 모두 모으면 멋사 부스에서 몬스터를 받을 수 있어요 | Collect all 4 stamps to claim your Monster at the LIKELION Booth | 集满4枚印章后，可前往 LIKELION 展位领取 Monster |
| 오늘 모은 스탬프 | Today's Stamps | 今日印章 |
| 적립 성공 시 다음 스탬프 칸이 채워집니다 | Each stamp earned fills the next slot. | 集章成功后，下一格印章将点亮 |
| 스탬프 QR 찍기 | Scan Stamp QR | 扫描集章二维码 |
| 상품 받으러 가기 | Claim Prize | 领取奖品 |
| 스탬프 수집 완료! | All Stamps Collected! | 集章完成！ |
| 스탬프 수집 완료 | All Stamps Collected | 集章完成 |
| '상품 수령' 버튼은 담당자만 눌러주세요. | Only staff should tap "Confirm Pickup." | "确认领取"仅限工作人员操作 |
| 직접 누르면 수령 완료 처리되어 상품을 받지 못할 수 있어요. | Tapping this yourself marks the prize as claimed and may prevent pickup. | 自行点击将标记为已领取，可能导致无法领奖。 |
| 상품 수령 | Confirm Pickup | 确认领取 |
| 바깥 영역을 누르면 수령 처리 없이 닫혀요 | Tap outside to close without marking it as claimed | 点击外部区域即可关闭，不会标记为已领取 |
| 스탬프 투어 QR을 비춰주세요! | Scan the Stamp Rally QR | 扫描集章二维码 |
| QR을 스캔하려면 카메라 접근을 허용해주세요 | Allow camera access to scan QR codes | 请允许相机权限以扫描二维码 |
| 허용하기 | Allow | 允许 |
| 스탬프투어 QR이 아니에요 | This isn't a Stamp Rally QR code | 这不是集章活动二维码 |

## 지도

원본 자료는 건물명이 학교 홈페이지의 공식 영문·중문 표기를 따른다고 밝힌다.

| 한국어 | 영어 | 중국어 간체 |
|---|---|---|
| 전체 | All | 全部 |
| 화장실 | Restrooms | 洗手间 |
| 포토부스 | Photo Booth | 拍照亭 |
| 흡연구역 | Smoking Area | 吸烟区 |
| 쓰레기통 | Trash Bins | 垃圾桶 |
| 전체 지도 | Full Map | 完整地图 |
| 티켓 수령존 | Ticket Pickup | 取票处 |
| 제1공학관 앞 주차장 | Engineering Building I Parking Lot | 第一工程馆前停车场 |
| 플리마켓 존 | Flea Market Zone | 跳蚤市场区 |
| 민주광장(학생복지관 앞) | Minju Square (in front of Student Welfare Building) | 民主广场（学生福祉馆前） |
| 푸드트럭 & 주점존 | Food Trucks & Pub Zone | 餐车 · 酒水摊位区 |
| 체육관 앞 · 학술정보관 주차장 | In Front of Gymnasium · Academic Information Center Parking Lot | 体育馆前 · 学术信息馆停车场 |
| 이벤트 & 피크닉존 | Events & Picnic Zone | 活动 · 野餐区 |
| 호수공원 · 잔디광장 | Lions' Lake · Lawn | 狮子湖 · 草坪广场 |
| 메인스테이지 | Main Stage | 主舞台 |
| 대운동장 | Stadium | 体育场 |
| 프로모션 & 부스존 | Promotion & Booth Zone | 宣传 · 展位区 |
| 제4공학관 · 학술정보관 사이 | Between Engineering Building IV & Academic Information Center | 第四工程馆与学术信息馆之间 |

## 확인이 필요한 부분

번역 자료와 현재 명세·디자인을 대조한 결과다. 해결되면 이 절에서 지운다.

**번역이 없는 문구**

- 일본어 전체.
- 굿즈: `결제하기` 버튼, 결제 완료 화면의 `결제가 완료되었어요`.
- 티켓: 송금 완료 화면의 `결제가 완료되었어요`.
- 공연: 타임테이블 `안내사항`, `DAY 1~3` 표기 여부, `개막식`·`폐막식`, 공연 정보 팝업 본문 항목.
- 부스: `부스 목록` 절이 비어 있음. `전체` 분류, 운영 시간·위치·문의 라벨.
- 홈: 공식 채널, 에리카 웰컴 데이 전체 이름, 공지 목록·알림 메시지 화면.
- 혼잡도 조회 실패의 `잠시 후 다시 확인해 주세요`(홈 명세 문구의 뒷부분).
- 오류·빈 상태·권한 거절 등 화면 공통 안내, 관리자 화면 전체(관리자는 한국어만 쓰는지 확인 필요).
- 주점 안내 문구는 원본에서 고정 문안 미확정으로 번역 제외.

**명세·디자인과 맞지 않는 부분**

- 번역의 `잔디광장`, `호수공원 → Lions' Lake`는 [학교 용어](terminology.md)에 아직 없다.
- 혼잡도 한국어: 번역 원문 `공간이 충분해요`·`절반 이상 찼어요`·`많이 혼잡해요`는 서버 문장보다
  짧다. 서버 문장은 위 표처럼 번역을 대응시켰다.
- 중국어 `재학생존` 표기가 `本校学生区`와 `学生区`로 섞여 있다.
- 티켓 구매 방법 1·3단계의 영어·중국어는 한국어 뒷부분(구매 부스로 이동, 티켓 부스 스태프)을
  줄였다.
- 굿즈 `슬로건`은 `Cheering Towel`로 번역됐다. 상품 실물과 맞는지 확인이 필요하다.
