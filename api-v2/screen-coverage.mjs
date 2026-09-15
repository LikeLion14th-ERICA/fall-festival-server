// Explicit mapping: every screen data row must be accounted for, including non-HTTP state.
const groups={
  'BOOTH-LIST':['Space.image','Space.name','Space.locationText','Space.category','브라우저: savedSpaceIds','브라우저: 선택 분류·스크롤'],
  'BOOTH-DETAIL':['Space.image','Space.name','Space.category','Space.operator','Space.hoursText','Space.locationText','Space.contact','Space.description','Space.experience','Space.events','Space.menu[].name','Space.menu[].price','브라우저: savedSpaceIds','Space.mapTarget','브라우저: 현재 상세 페이지 URL'],
  'MAP-OVERVIEW':['Map.image','Pin.x + Pin.y','Pin.category','브라우저: Pins.items에서 실제 종류 추출; 필터 단위 미정','브라우저: 선택 필터·핀','Pin.target','Map.image (장소명·번호 포함; 구체 표기 방식은 디자인 협의)','TicketGuide.mapTarget','제외: 지도 혼잡도 표시 없음','제외: 지도 혼잡도 색상 없음','제외: 지도 혼잡도 안내 없음','제외: 지도 혼잡도 수정 시각 없음'],
  'MAP-AREA':['Map.image','Pin.x + Pin.y','Pin.category','브라우저: Pins.items에서 실제 종류 추출; 필터 단위 미정','브라우저: 선택 필터·핀','Space.mapTarget','Map.image (장소명·번호 포함; 구체 표기 방식은 디자인 협의)'],
  'MAP-POPUP':['Place.name','Place.hoursText','Place.description','Place.locationText','Place.usage','Place.spaceId'],
  'HOME':['Crowding.savedLevel','Crowding.status','Crowding.updatedAt + Crowding.timeBasis','Crowding.opensAt','Crowding.closesAt','프런트 고정 UI: Crowding.colorToken → 디자인 색상','프런트 번역: Crowding.status; Crowding.message는 참고','Config.languages','브라우저: 선택 언어, 기본 ko','Notices.items[0].title','Config.links.universityNotices','Config.links.faq','Config.links.welcomeDay','Config.links.officialChannels','Channel.label + Channel.iconKey','브라우저: 당일 stamp.started'],
  'NOTICE-LIST':['Notice.title','Notice.createdAt','Notice.body','Notice.links','Notice.type','서버: Translations의 READY 필터 후 Notices.items 반환','브라우저: Notices.visibleIds와 현재 목록 차이 비교'],
  'GOODS-LIST':['Goods.image','Goods.name','Goods.price','Goods.colorImages','Goods.sizes','Availability.sizes'],
  'GOODS-DETAIL':['Goods.colorImages','Goods.name','Goods.price','Goods.colorImages','Goods.sizes','Availability.sizes','프런트 고정 UI: 현장 상품·색상·사이즈 확인 후 송금 안내'],
  'GOODS-PAYMENT':['PaymentGuide.name','PaymentGuide.price','BankAccount.bankName','BankAccount.accountNumber','BankAccount.holder','PaymentGuide.instructions'],
  'SHOW-LINEUP':['Lineup.items[].image','Lineup.items[].name','Lineup.date','Lineup.category','Lineup.items[].order'],
  'SHOW-ARTIST':['Artist.image','Artist.name','Artist.performances[].date','Artist.performances[].startsAt + Artist.performances[].endsAt','Artist.introduction','Artist.socialLinks','Artist.songs[].label','Artist.songs[].url'],
  'SHOW-TIMETABLE':['Performance.title','Performance.date','Performance.startsAt','Performance.endsAt','ProhibitedItems.message','프런트: Meta.serverTime + 경과 시간, 축제 당일 17:00~22:00 오늘 열만 (시간축 변경과 별개)'],
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
const resolvedLegacyPendingIds=new Set(['HOME-Q04','STAMP-COLLECT-Q01']);
const currentPendingIds=value=>(value||'').split(',').map(id=>id.trim()).filter(id=>id&&!resolvedLegacyPendingIds.has(id)).join(', ');

export function buildCoverage(source,operations){
  groups['GOODS-LIST'][3]='Goods.colors';groups['GOODS-DETAIL'][3]='Goods.colors';
  groups['GOODS-LIST'][5]='Availability.variants';groups['GOODS-DETAIL'][5]='Availability.variants';
  groups['ADM-CROWD'][1]='Crowding.operatingStatus';groups['ADM-CROWD'][8]='프런트 고정 UI: 운영자 판단 기준·85% 자동 만석 아님';
  groups['ADM-NOTICE-LIST'][4]='AdminNotice.createdAt';
  groups['ADM-NOTICE-EDIT'][3]='보류: 이미지 필드 폐기';
  groups['ADM-NOTICE-EDIT'][6]='AdminNotice.links';groups['ADM-NOTICE-EDIT'][7]='AdminNotice.translations + NoticeTranslationPreview.canSave';
  groups['ADM-GOODS']=['Availability.goodsId','Availability.name','Availability.variants[].colorId + Availability.variants[].sizeId','Availability.variants[].status','제외: 실제 재고 수량 미관리','Availability.allSoldOut'];
  groups['ADM-CROWD-HOURS']=Array(5).fill('제외: 운영 시간은 개발자 등록, 관리자 편집 없음');
  groups['ADM-GOODS-PRODUCT-LIST']=['Goods.id','Goods.name','Goods.price','Goods.images','Goods.colors','Goods.sizes'];
  groups['ADM-GOODS-PRODUCT-EDIT']=[...groups['ADM-GOODS-PRODUCT-LIST'],'브라우저: 상품 작성 중 입력값'];
  const wikiSource=screen=>'../docs/wiki/product/'+(screen.startsWith('ADM-CROWD')?'admin/crowd.md':screen.startsWith('ADM-NOTICE')?'admin/notice.md':screen.startsWith('ADM-GOODS')?'admin/goods.md':screen.startsWith('MAP')?'map.md':screen.startsWith('GOODS')?'goods.md':screen.startsWith('BOOTH')?'spaces.md':screen.startsWith('STAMP')?'stamp.md':screen==='TICKET'?'ticket.md':screen==='HOME'?'home.md':screen==='NOTICE-LIST'?'notice.md':screen==='SHOW-TIMETABLE'||screen==='SHOW-POPUP'?'timetable.md':'lineup.md');
  const rows=source.tabs.find(t=>t.title==='02 데이터와 책임').rows.filter(r=>r[0]==='작성');
  const data=rows.map(r=>{const index=Number(r[2].match(/-D(\d+)$/)?.[1])-1;const target=groups[r[1]]?.[index];if(!target)throw new Error(`Missing screen data mapping: ${r[2]}`);return {id:r[2],screenId:r[1],label:r[2]==='SHOW-TIMETABLE-D05'?'고정 반입 금지 물품 안내':r[2]==='ADM-GOODS-D04'?'판매 상태':r[4],wikiSource:wikiSource(r[1]),legacySource:{owner:r[9],decision:r[10],material:r[11],pendingIds:currentPendingIds(r[13]),requiredInScreen:r[6]},owner:target.startsWith('제외')?'제외':target.startsWith('브라우저')?'브라우저':target.startsWith('프런트')?'프런트':target.startsWith('보류')?'보류':'API',target,requiredInScreen:'OpenAPI required 및 SCREEN-STATES.md 참조',sourceDecision:'위키 v5',sourceMaterial:wikiSource(r[1])};});
  for(const [screenId,id,label,target] of [['GOODS-DETAIL','GOODS-DETAIL-D08','실제 제공 조합','Goods.options'],['ADM-GOODS-PRODUCT-EDIT','ADM-GOODS-PRODUCT-EDIT-D08','실제 제공 조합','Goods.options'],['SHOW-TIMETABLE','SHOW-TIMETABLE-D07','반입 금지 물품 목록','ProhibitedItems.items']])data.push({id,screenId,label,target,owner:'API',wikiSource:wikiSource(screenId),requiredInScreen:'필수',sourceDecision:'위키 v5',sourceMaterial:'운영 자료 대기',pendingIds:''});
  const screenRows=source.tabs.find(t=>t.title==='01 화면 현황').rows.filter(r=>groups[r[0]]&&r[0]!=='ADM-CROWD-HOURS');
  const screens=screenRows.map(r=>({id:r[0],name:r[1],operations:operations.filter(o=>o.screens.includes(r[0])).map(o=>o.operationId),browserData:data.filter(d=>d.screenId===r[0]&&d.owner!=='API').map(d=>d.id),legacySource:{pendingIds:currentPendingIds(r[9])}}));
  if(data.length!==186||screens.length!==26)throw new Error('Screen source changed; review coverage counts.');
  return {basis:'Product Context v5 (user confirmed 2026-09-14)',baseCommit:'21eb76dacd78b3ad79ed4d9589dd341fbc25b883',legacySourceUrl:source.url,screens,data};
}
