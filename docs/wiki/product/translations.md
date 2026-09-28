# 승인 번역표

[위키 홈](../README.md) · 읽는 때: 사용자 화면 문자열·카탈로그 번역·공지 외 콘텐츠 번역 작성

2026-09-18에 받은 번역 자료(한국어·영어·중국어 간체, 24쪽)를 화면별로 옮긴 표다.
[다국어](../engineering/i18n.md) 규칙에 따라 앱 텍스트와 카탈로그 번역은 이 표와
[학교 용어](terminology.md)의 표기만 사용한다. 일본어 번역은 아직 받지 않았다.
원본 PDF의 결제·송금 문구는 2026-09-28 사용자 결정으로 사용 중단했다.
티켓은 가격만, 굿즈는 가격·재고만, 부스는 콘텐츠·메뉴 가격만 안내한다.
아래 가격·수령 시간 번역은 이 결정에 맞춰 추가한 운영 문구이며 원본 PDF의 인용은 아니다.

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

## 굿즈샵

| 한국어 | 영어 | 중국어 간체 |
|---|---|---|
| 굿즈샵 | Merch Shop | 周边商店 |
| 축제 공식 굿즈 현장 수령 | Official Merch On-site Pickup | 官方周边现场领取 |
| 2026 축제 공식 · 굿즈 현장 판매 전용 | 2026 Official Festival Merch · On-site Only | 2026 官方周边 · 仅限现场购买 |
| 색상 선택 | Select Color | 选择颜色 |
| 사이즈 선택 | Select Size | 选择尺码 |
| 구매 가능 | Available | 有货 |
| 품절 | Sold Out | 售罄 |
| 가격 | Price | 价格 |
| 원 | KRW | 韩元 |

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
| 메뉴 가격 | Menu Price | 菜单价格 |
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
| 외부인 티켓 가격: 25,000원 | Visitor ticket price: KRW 25,000 | 访客门票价格：25,000韩元 |

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
| 상품 수령 시간: 매일 11:00~17:00 | Prize pickup: daily 11:00–17:00 | 奖品领取时间：每日 11:00–17:00 |
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
- 굿즈 `슬로건`은 `Cheering Towel`로 번역됐다. 상품 실물과 맞는지 확인이 필요하다.
