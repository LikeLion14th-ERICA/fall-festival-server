// Explicit mapping: every screen data row must be accounted for, including non-HTTP state.
const groups={
  'BOOTH-LIST':['Space.image','Space.name','Space.locationText','Space.category','브라우저: savedSpaceIds','브라우저: 선택 분류·스크롤'],
  'BOOTH-DETAIL':['Space.image','Space.name','Space.category','Space.operator','Space.hoursText','Space.locationText','Space.contact','Space.description','Space.experience','Space.events','Space.menu[].name','Space.menu[].price','브라우저: savedSpaceIds','Space.mapTarget','브라우저: 현재 상세 페이지 URL'],
  'MAP-OVERVIEW':['Map.image','Pin.x + Pin.y','Pin.category','브라우저: Pins.items에서 실제 종류 추출; 필터 단위 미정','브라우저: 선택 필터·핀','Pin.target','Map.image (장소명·번호는 최종 이미지 자산에 포함; 미정)','TicketGuide.mapTarget','Crowding.status','Crowding.colorToken','Crowding.message','Crowding.updatedAt + Crowding.timeBasis'],
  'MAP-AREA':['Map.image','Pin.x + Pin.y','Pin.category','브라우저: Pins.items에서 실제 종류 추출; 필터 단위 미정','브라우저: 선택 필터·핀','Space.mapTarget','Map.image (장소명·번호는 최종 이미지 자산에 포함; 미정)'],
  'MAP-POPUP':['Place.name','Place.hoursText','Place.description','Place.locationText','Place.usage','Place.spaceId'],
  'HOME':['Crowding.savedLevel','Crowding.status','Crowding.updatedAt + Crowding.timeBasis','Crowding.opensAt','Crowding.closesAt','프런트 고정 UI: Crowding.colorToken → 디자인 색상','프런트 번역: Crowding.status; Crowding.message는 참고','Config.languages','브라우저: 선택 언어, 기본 ko','Notices.items[0].title','Config.links.universityNotices','보류: Config.faqEnabled=false, FAQ 대상 미정','Config.links.welcomeDay','Config.links.officialChannels','Channel.label + Channel.iconKey','브라우저: 당일 stamp.started'],
  'NOTICE-LIST':['Notice.title','Notice.createdAt','Notice.body','Notice.links','Notice.type','서버: Translations의 READY 필터 후 Notices.items 반환','브라우저: Notices.visibleIds와 현재 목록 차이 비교'],
  'GOODS-LIST':['Goods.image','Goods.name','Goods.price','Goods.colorImages','Goods.sizes','Availability.sizes'],
  'GOODS-DETAIL':['Goods.colorImages','Goods.name','Goods.price','Goods.colorImages','Goods.sizes','Availability.sizes','프런트 고정 UI: 현장 상품·색상·사이즈 확인 후 송금 안내'],
  'GOODS-PAYMENT':['PaymentGuide.name','PaymentGuide.price','BankAccount.bankName','BankAccount.accountNumber','BankAccount.holder','PaymentGuide.instructions'],
  'SHOW-LINEUP':['Lineup.items[].image','Lineup.items[].name','Lineup.date','Lineup.category','Lineup.items[].order'],
  'SHOW-ARTIST':['Artist.image','Artist.name','Artist.performances[].date','Artist.performances[].startsAt + Artist.performances[].endsAt','Artist.introduction','Artist.socialLinks','Artist.songs[].label','Artist.songs[].url'],
  'SHOW-TIMETABLE':['Performance.title','Performance.date','Performance.startsAt','Performance.endsAt','PerformanceAlert.message','프런트: Meta.serverTime + 경과 시간, Timetable.axis 범위 내 오늘 열만'],
  'SHOW-POPUP':['Performance.title','Performance.artists','Performance.startsAt + Performance.endsAt','Performance.description'],
  'TICKET':['TicketGuide.date (서버 KST 기준을 사용)','TicketGuide.unitPrice','브라우저: 기본·최소 1명, 토스 복귀 시 1명','프런트: 인원 × Money.amount, 안전 정수 범위 확인','TicketGuide.transferOpensAt + TicketGuide.transferClosesAt','TicketGuide.pickupOpensAt + TicketGuide.pickupClosesAt','BankAccount.bankName','TicketGuide.account (운영 밖 null)','BankAccount.holder','TicketGuide.mapTarget','TicketGuide.instructions'],
  'STAMP-START':['StampGuide.title + StampGuide.reward.name','StampGuide.dates','StampGuide.instructions','StampGuide.reward.notice'],
  'STAMP-COLLECT':['브라우저: stamp.started','브라우저: stamp.count (0~4)','브라우저: stamp.claimed','브라우저: stamp.date (KST 자정 초기화)','StampGuide.qrValue (제공 책임·배포 방식은 검토 필요)'],
  'STAMP-REWARD':['프런트 고정 UI: 담당자에게 제시·사용자 직접 수령 선택 금지 안내'],
  'ADM-CROWD':['Crowding.savedLevel','Crowding.status','Crowding.colorToken','Crowding.message','Crowding.updatedAt','Crowding.operatingDay','Crowding.opensAt','Crowding.closesAt'],
  'ADM-NOTICE-LIST':['AdminNotice.id','AdminNotice.type','AdminNotice.translations.ko.title','AdminNotice.updatedAt'],
  'ADM-NOTICE-EDIT':['AdminNotice.type','AdminNotice.translations.ko.title','AdminNotice.translations.ko.body','AdminNotice.image (입력·노출 정책 미정)','AdminNotice.templateId','AdminNotice.translations'],
  'ADM-NOTICE-DELETE':['AdminNotice.id','AdminNotice.translations.ko.title'],
  'ADM-NOTICE-TEMPLATE':['Templates.items','Template.translations.ko.title','Template.translations.ko.body','Template.translations'],
  'ADM-GOODS':['Availability.goodsId','Availability.name','Availability.sizes[].label','Availability.sizes[].status'],
};
export function buildCoverage(source,operations){
  groups['GOODS-LIST'][3]='Goods.colors';groups['GOODS-DETAIL'][3]='Goods.colors';
  groups['GOODS-LIST'][5]='Availability.variants';groups['GOODS-DETAIL'][5]='Availability.variants';
  groups['ADM-CROWD'][1]='Crowding.operatingStatus';groups['ADM-CROWD'][8]='프런트 고정 UI: 운영자 판단 기준·85% 자동 만석 아님';
  groups['ADM-NOTICE-LIST'][4]='AdminNotice.createdAt';
  groups['ADM-NOTICE-EDIT'][3]='보류: 이미지 필드 폐기';
  groups['ADM-NOTICE-EDIT'][6]='AdminNotice.links';groups['ADM-NOTICE-EDIT'][7]='AdminNotice.translations + NoticeTranslationPreview.canSave';
  groups['ADM-GOODS']=['Inventory.goodsId','Inventory.name','Inventory.variants[].colorId + Inventory.variants[].sizeId','Inventory.variants[].status','Inventory.variants[].quantity','Inventory.allSoldOut'];
  groups['ADM-CROWD-HOURS']=['OperatingHours.operatingDay','OperatingHours.opensAt','OperatingHours.closesAt','OperatingHours.isDefault','브라우저: 저장 전 운영 시간 입력값'];
  groups['ADM-GOODS-PRODUCT-LIST']=['Goods.id','Goods.name','Goods.price','Goods.images','Goods.colors','Goods.sizes'];
  groups['ADM-GOODS-PRODUCT-EDIT']=[...groups['ADM-GOODS-PRODUCT-LIST'],'브라우저: 상품 작성 중 입력값'];
  const rows=source.tabs.find(t=>t.title==='02 데이터와 책임').rows.filter(r=>r[0]==='작성');
  const data=rows.map(r=>{const index=Number(r[2].match(/-D(\d+)$/)?.[1])-1;const target=groups[r[1]]?.[index];if(!target)throw new Error(`Missing screen data mapping: ${r[2]}`);return {id:r[2],screenId:r[1],label:r[4],sourceOwner:r[9],owner:target.startsWith('브라우저')?'브라우저':target.startsWith('프런트')?'프런트':target.startsWith('보류')?'보류':'API',target,requiredInScreen:r[6],sourceDecision:r[10],sourceMaterial:r[11],pendingIds:r[13]||''};});
  const screenRows=source.tabs.find(t=>t.title==='01 화면 현황').rows.filter(r=>groups[r[0]]);
  const screens=screenRows.map(r=>({id:r[0],name:r[1],operations:operations.filter(o=>o.screens.includes(r[0])).map(o=>o.operationId),browserData:data.filter(d=>d.screenId===r[0]&&d.owner!=='API').map(d=>d.id),pendingIds:r[9]}));
  if(data.length!==183||screens.length!==27)throw new Error('Screen source changed; review coverage counts.');
  return {sourceUrl:source.url,sourceModifiedTime:source.modifiedTime,screens,data};
}
