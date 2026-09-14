// Fictional fixtures only. No DB, actual festival dates, account, or user records.
import { initializeAdmin,hoursFor,inventoryFor,adminExecute,validateNotice } from './admin-domain.mjs';
export const MOCK_NOW = '2030-10-01T18:00:00+09:00';
export const DATES = ['2030-10-01','2030-10-02','2030-10-03'];
export const IMAGE = { url: '/__mock/assets/sample.svg', alt: '개발용 예시 이미지 · 실제 행사 자료 아님', width: 800, height: 600 };
const image = () => structuredClone(IMAGE);
const money = amount => ({ amount, currency: 'KRW' });
const link = label => ({ label, url: 'https://example.invalid/mock-not-payable', target: '_blank' });
export const isoKst = time => new Date(new Date(time).getTime() + 9 * 3600000).toISOString().replace('Z', '+09:00');
export const dayKst = time => isoKst(time).slice(0,10);
const mapTarget = { mapId: 'map-area', placeId: 'place-booth', pinId: 'pin-booth', mapVersion: 'mock-map-1' };
const commonSpace = { image: image(), locationText: '예시 구역 A', operator: '예시 운영팀', hoursText: '개발용 12:00~23:00', description: '실제 축제 자료가 아닌 화면 개발용 설명입니다.', contact: link('예시 문의'), experience: null, events: [], menu: [], mapTarget: null };
export function createState() {
  const performances = DATES.flatMap((date,i) => [
    { id: `show-${i+1}`,date,title:`예시 공연 ${i+1}`,artists:[{id:'artist-a',name:'예시 아티스트 A'}],startsAt:`${date}T18:00:00+09:00`,endsAt:`${date}T18:40:00+09:00`,description:'개발용 공연 안내' },
    { id: `contest-${i+1}`,date,title:`예시 콘테스트 ${i+1}`,artists:[{id:'artist-b',name:'예시 참가팀 B'}],startsAt:`${date}T17:00:00+09:00`,endsAt:`${date}T17:30:00+09:00`,description:null },
  ]);
  const translation = (title,body,status='READY') => ({title,body,status});
  const notices = [
    {id:'notice-1',type:'GENERAL',translations:{ko:translation('예시 공지','개발용 공지 본문입니다.'),en:translation('Sample notice','Mock content only.')},links:[link('예시 안내')],image:null,templateId:null,createdAt:'2030-10-01T16:00:00+09:00',updatedAt:'2030-10-01T16:00:00+09:00'},
    {id:'notice-lost',type:'LOST_FOUND',translations:{ko:translation('예시 분실물','실제 분실물이 아닙니다.'),en:translation('Sample lost item','Mock item, not a real report.')},links:[],image:null,templateId:null,createdAt:'2030-09-30T16:00:00+09:00',updatedAt:'2030-09-30T16:00:00+09:00'},
    {id:'notice-old',type:'GENERAL',translations:{ko:translation('지난 예시 공지','사용자 목록에서는 제외합니다.')},links:[],image:null,templateId:null,createdAt:'2030-09-30T18:00:00+09:00',updatedAt:'2030-10-01T17:00:00+09:00'},
    {id:'notice-pending',type:'GENERAL',translations:{ko:translation('번역 대기 예시','한국어에는 표시합니다.'),en:translation('Pending','Not visible in English.','PENDING')},links:[],image:null,templateId:null,createdAt:'2030-10-01T17:00:00+09:00',updatedAt:'2030-10-01T17:00:00+09:00'},
  ];
  return initializeAdmin({
    revision:1,nextId:1,performances,notices,deleted:new Set(),crowding:{'2030-10-01':{level:'MODERATE',updatedAt:'2030-10-01T17:00:00+09:00'}},
    goods:[{id:'goods-shirt',name:'예시 의류',price:money(1000),image:image(),colorImages:[{colorId:'color-a',colorName:'예시 색상 A',image:image()},{colorId:'color-b',colorName:'예시 색상 B',image:image()}],sizes:[{id:'size-m',label:'M'},{id:'size-l',label:'L'}],description:'실제 상품·가격이 아닙니다.'}],
    spaces:[{...structuredClone(commonSpace),id:'space-booth',category:'BOOTH',name:'예시 체험 부스',experience:'개발용 체험 방법',events:['개발용 이벤트'],mapTarget:structuredClone(mapTarget)}, { ...structuredClone(commonSpace),id:'space-pub',category:'PUB',name:'예시 주점',menu:[{name:'예시 메뉴',price:money(2000)}],mapTarget:{...mapTarget,placeId:'place-pub',pinId:'pin-pub'} },{...structuredClone(commonSpace),id:'space-market',category:'FLEA_MARKET',name:'예시 마켓',mapTarget:{...mapTarget,placeId:'place-market',pinId:'pin-market'}}],
    maps:[{id:'map-overview',name:'예시 전체 지도',kind:'OVERVIEW',version:'mock-map-1',image:image()},{id:'map-area',name:'예시 구역 지도',kind:'AREA',version:'mock-map-1',image:image()}],
    pins:{'map-overview':[{id:'pin-area',category:'area',label:'예시 구역 이동',x:0.2,y:0.3,target:{kind:'AREA',mapId:'map-area'}},{id:'pin-ticket',category:'ticket',label:'예시 티켓존',x:0.6,y:0.7,target:{kind:'PLACE',placeId:'place-ticket'}}], 'map-area':[{id:'pin-booth',category:'booth',label:'예시 부스',x:0.2,y:0.4,target:{kind:'PLACE',placeId:'place-booth'}},{id:'pin-pub',category:'pub',label:'예시 주점',x:0.4,y:0.5,target:{kind:'PLACE',placeId:'place-pub'}},{id:'pin-market',category:'market',label:'예시 마켓',x:0.5,y:0.5,target:{kind:'PLACE',placeId:'place-market'}},{id:'pin-toilet',category:'toilet',label:'예시 편의시설',x:0.8,y:0.5,target:{kind:'PLACE',placeId:'place-toilet'}}]},
    places:[{id:'place-booth',kind:'SPACE',name:'예시 체험 부스',locationText:'예시 구역 A',hoursText:'개발용 12~23시',description:'예시 체험 소개',usage:null,spaceId:'space-booth'}, {id:'place-pub',kind:'SPACE',name:'예시 주점',locationText:'예시 구역 A',hoursText:null,description:'예시 소개',usage:null,spaceId:'space-pub'}, {id:'place-market',kind:'SPACE',name:'예시 마켓',locationText:'예시 구역 A',hoursText:null,description:'예시 소개',usage:null,spaceId:'space-market'}, {id:'place-toilet',kind:'FACILITY',name:null,locationText:'예시 구역 동쪽',hoursText:null,description:null,usage:null,spaceId:null}, {id:'place-ticket',kind:'LANDMARK',name:'예시 티켓존',locationText:'예시 지도 우측',hoursText:null,description:null,usage:'실제 수령 장소가 아닙니다.',spaceId:null}],
    templates:[{id:'template-1',name:'예시 일반 공지',translations:{ko:translation('예시 제목','예시 본문'),en:translation('Sample title','Sample body')}}],
  });
}
export class ApiFailure extends Error {
  constructor(status,code,message,details=[]) { super(message);Object.assign(this,{status,code,details}); }
}
export function failure(status,code,message,details=[]) { throw new ApiFailure(status,code,message,details); }
function find(items,id) { const item=items.find(x=>x.id===id);if(!item)failure(404,'NOT_FOUND','요청한 정보를 찾을 수 없습니다.');return structuredClone(item); }
const defaultDate = date => date < DATES[0] ? DATES[0] : date > DATES.at(-1) ? DATES.at(-1) : date;
function crowdInfo(state,now,scenario,locale='ko') {
  const today=dayKst(now);
  let operatingDay=defaultDate(today);
  const hours=hoursFor(state,operatingDay);
  const opensAt=`${operatingDay}T${hours.opensAt}:00+09:00`;
  const closesAt=`${operatingDay}T${hours.closesAt}:00+09:00`;
  const stored=scenario==='unmodified'||today!==operatingDay?null:state.crowding[operatingDay];
  const status=+new Date(now)<+new Date(opensAt)?'BEFORE_OPEN':+new Date(now)>=+new Date(closesAt)?'CLOSED':stored?.level||'RELAXED';
  const colors={RELAXED:'green',MODERATE:'orange',CROWDED:'red',FULL:'black'};
  const messages={BEFORE_OPEN:'오늘 재학생존 입장은 12:00에 시작해요',RELAXED:'재학생존의 공간이 많이 남았어요.',MODERATE:'재학생존의 공간이 절반 이상 찼어요.',CROWDED:'재학생존이 많이 혼잡해요.',FULL:'재학생존이 꽉 차서 외부인존에서만 즐길 수 있어요.',CLOSED:'오늘 재학생존 운영이 종료됐어요'};
  const active=!!colors[status];
  messages.BEFORE_OPEN=`오늘 재학생존 입장은 ${hours.opensAt}에 시작해요`;
  return {operatingDay,opensAt,closesAt,operatingStatus:active?'OPEN':status,status,savedLevel:stored?.level||null,colorToken:colors[status]||null,message:locale==='ko'?messages[status]:`Mock crowd status: ${status}`,updatedAt:stored?.updatedAt||null,timeBasis:active?(stored?'OPERATOR':'OPENING'):'NONE'};
}
export function scenarioTime(scenario,now) {
  return ({'before-open':'2030-09-30T10:00:00+09:00',closed:'2030-10-01T23:00:00+09:00',ended:'2030-10-04T00:00:00+09:00',overnight:'2030-10-02T01:00:00+09:00'})[scenario]||now;
}
// Same locale policy in the mock: ko/en are ready; non-notice translation failure never silently falls back.
function localize(value,locale) {
  if(locale==='ko')return value;
  const translatable=new Set(['name','title','label','description','operator','hoursText','locationText','experience','usage','alt','message','notice','introduction','colorName']);
  const walk=(v,key='')=> {
    if(Array.isArray(v))return v.map(x=>walk(x,key));
    if(v&&typeof v==='object')return Object.fromEntries(Object.entries(v).map(([k,x])=>[k,['translations','languages'].includes(k)?x:walk(x,k)]));
    return typeof v==='string' && /[가-힣]/.test(v) && (translatable.has(key)||['instructions','events','items'].includes(key)) ? `Mock ${key}` : v;
  };
  return walk(value);
}
export function execute(op,state,{params={},query={},body,scenario='normal',now=MOCK_NOW}={}) {
  now=scenarioTime(scenario,now);
  const locale=query.locale||'ko';
  if(!state.languages.includes(locale))failure(400,'LOCALE_NOT_READY','준비 완료 언어만 요청할 수 있습니다.');
  if(scenario==='error')failure(503,'SERVICE_UNAVAILABLE','일시적으로 정보를 불러올 수 없습니다.');
  if(scenario==='not-found')failure(404,'NOT_FOUND','요청한 정보를 찾을 수 없습니다.');
  if(scenario==='already-deleted')failure(409,'ALREADY_DELETED','이미 삭제된 공지입니다.');
  if(scenario==='version-conflict')failure(409,'MAP_VERSION_MISMATCH','지도 이미지를 다시 조회해 주세요.');
  const empty=scenario==='empty',missing=scenario==='missing-optional',sold=scenario==='sold-out';
  const date=dayKst(now);
  let data,status=200;
  const mutate=()=>{state.revision++;now=isoKst(+new Date(now)+state.revision);return now;};
  if(scenario==='all-languages'||scenario==='partial-translation')state.languages=['ko','en','zh-Hans','ja'];
  const getAvailability=goodsId=>inventoryFor(state,goodsId,{failure,sold});
  const extra=adminExecute(op,state,{params,body,scenario,mutate,failure,DATES});
  if(extra)return {status:extra.status||200,data:extra.data,now,locale};
  switch(op.operationId){
    case 'getConfig':data={festival:{id:'festival-mock',title:'개발용 가상 축제',dates:empty?[]:DATES,defaultDate:empty?null:defaultDate(date)},languages:[{code:'ko',label:'한국어'},{code:'en',label:'English'}],links:{universityNotices:missing?null:link('예시 학교 공지'),officialChannels:empty||missing?[]:[{id:'channel-mock',...link('예시 공식 채널'),iconKey:'website'}],welcomeDay:scenario==='welcome-ready'?link('에리카 웰컴 데이'):null},faqEnabled:false};break;
    case 'getCrowding':case 'getAdminCrowding':data=crowdInfo(state,now,scenario,locale);break;
    case 'putAdminCrowding':{
      const before=crowdInfo(state,now,scenario);
      if(body.level==='FULL'&&body.confirmFull!==true)failure(422,'CONFIRMATION_REQUIRED','만석 변경 확인이 필요합니다.');
      if(['BEFORE_OPEN','CLOSED'].includes(before.status))failure(409,'OUTSIDE_OPERATING_HOURS','운영 시간 밖 변경 정책은 미정입니다.');
      if(before.status!==body.level)state.crowding[before.operatingDay]={level:body.level,updatedAt:mutate()};
      data=crowdInfo(state,now,scenario);break;
    }
    case 'getNotices':{
      let items=state.notices.filter(n=>!state.deleted.has(n.id)&&(n.type==='LOST_FOUND'||dayKst(n.createdAt)===date)&&n.translations[locale]?.status==='READY').map(n=>({id:n.id,type:n.type,title:n.translations[locale].title,body:n.translations[locale].body,links:n.links,createdAt:n.createdAt}));
      if(scenario==='new-notice')items.push({id:'notice-new',type:'GENERAL',title:locale==='ko'?'추가 예시 공지':'New sample',body:locale==='ko'?'새 공지 버튼 검증용':'Mock new content',links:[],createdAt:now});
      if(scenario==='deleted')items=items.filter(n=>n.id!=='notice-1');
      if(empty)items=[];
      items.sort((a,b)=>Date.parse(b.createdAt)-Date.parse(a.createdAt)||a.id.localeCompare(b.id));
      if(missing)items.forEach(n=>n.links=[]);
      data={items,visibleIds:items.map(n=>n.id),asOfDate:date};break;
    }
    case 'getGoods':data={items:empty?[]:structuredClone(state.goods)};break;
    case 'getGoodsAvailability':data={items:empty?[]:state.goods.map(g=>getAvailability(g.id))};break;
    case 'getAdminGoods':data={items:empty?[]:state.goods.map(g=>inventoryFor(state,g.id,{admin:true,sold,failure}))};break;
    case 'getGood':data=find(state.goods,params.goodsId);if(missing)data.description=null;break;
    case 'getGoodAvailability':data=getAvailability(params.goodsId);break;
    case 'getPaymentGuide':{
      const g=find(state.goods,params.goodsId);data={goodsId:g.id,name:g.name,price:g.price,account:missing?null:{bankName:'개발용 은행',accountNumber:'MOCK-NOT-PAYABLE',holder:'개발용 예금주'},transferLink:null,instructions:['현장에서 상품과 색상, 사이즈를 확인한 후 송금해 주세요.','목 응답은 실제 송금을 지원하지 않습니다.'],locationText:missing?null:'예시 판매 장소',hoursText:missing?null:'예시 운영 시간'};break;
    }
    case 'getLineup':{
      const selected=query.date||defaultDate(date),category=query.category||'ARTIST';
      if(!DATES.includes(selected))failure(400,'INVALID_DATE','행사 날짜 중에서 선택해 주세요.');
      const items=state.performances.filter(p=>p.date===selected&&(category==='ARTIST'?p.id.startsWith('show'):p.id.startsWith('contest'))).sort((a,b)=>a.startsAt.localeCompare(b.startsAt)||a.id.localeCompare(b.id)).map((p,i)=>({artistId:p.artists[0].id,performanceId:p.id,name:p.artists[0].name,image:image(),order:i+1}));
      data={date:selected,category,items:empty?[]:items};break;
    }
    case 'getArtist':{
      if(!['artist-a','artist-b'].includes(params.artistId))failure(404,'NOT_FOUND','출연진이 없습니다.');
      data={id:params.artistId,category:params.artistId==='artist-a'?'ARTIST':'CONTEST',name:params.artistId==='artist-a'?'예시 아티스트 A':'예시 참가팀 B',image:image(),introduction:missing?null:'개발용 소개',socialLinks:missing?[]:[link('예시 SNS')],songs:missing?[]:[link('예시 대표곡')],performances:state.performances.filter(p=>p.artists.some(a=>a.id===params.artistId)).map(({id,date,startsAt,endsAt})=>({id,date,startsAt,endsAt}))};break;
    }
    case 'getTimetable':data={dates:DATES,axis:{startTime:'17:00',endTime:'22:00'},items:empty?[]:structuredClone(state.performances).sort((a,b)=>a.startsAt.localeCompare(b.startsAt)||a.id.localeCompare(b.id))};break;
    case 'getPerformance':data=find(state.performances,params.performanceId);if(missing)data.description=null;break;
    case 'getProhibitedItems':data={items:empty?[]:['개발용 반입 금지 물품 예시'],message:empty?null:'총학생회 확정 자료를 사전 번역해 고정 표시합니다. 이 내용은 예시입니다.'};break;
    case 'getSpaces':data={items:empty?[]:structuredClone(state.spaces).filter(s=>!query.category||query.category==='ALL'||s.category===query.category).sort((a,b)=>a.name.localeCompare(b.name,'ko')||a.id.localeCompare(b.id))};break;
    case 'getSpace':data=find(state.spaces,params.spaceId);if(missing)Object.assign(data,{operator:null,hoursText:null,description:null,contact:null,experience:null,events:[],menu:[],mapTarget:null});break;
    case 'getMaps':data={items:empty?[]:structuredClone(state.maps),overviewId:empty?null:'map-overview'};break;
    case 'getMap':data=find(state.maps,params.mapId);break;
    case 'getPins':{const m=find(state.maps,params.mapId);if(query.mapVersion!==m.version)failure(409,'MAP_VERSION_MISMATCH','지도 이미지 버전이 다릅니다.');data={mapId:m.id,mapVersion:m.version,items:empty?[]:structuredClone(state.pins[m.id])};break;}
    case 'getPlace':data=find(state.places,params.placeId);if(missing)Object.assign(data,{hoursText:null,description:null,usage:null});break;
    case 'getTicketGuide':{
      const unconfigured=scenario==='unconfigured';
      const ticketStatus=unconfigured?'UNCONFIGURED':date<DATES[0]?'BEFORE_FESTIVAL':date>DATES.at(-1)?'FESTIVAL_ENDED':isoKst(now).slice(11,16)>='21:00'?'DAILY_CLOSED':'TRANSFER_OPEN';
      const schedule=defaultDate(date),open=ticketStatus==='TRANSFER_OPEN';
      data={date,status:ticketStatus,unitPrice:unconfigured?null:money(1500),transferOpensAt:unconfigured?null:`${schedule}T00:00:00+09:00`,transferClosesAt:unconfigured?null:`${schedule}T21:00:00+09:00`,pickupOpensAt:unconfigured?null:`${schedule}T13:00:00+09:00`,pickupClosesAt:unconfigured?null:`${schedule}T21:00:00+09:00`,account:open?{bankName:'개발용 은행',accountNumber:'MOCK-NOT-PAYABLE',holder:'개발용 예금주'}:null,transferLink:null,mapTarget:unconfigured?null:{mapId:'map-overview',placeId:'place-ticket',pinId:'pin-ticket',mapVersion:'mock-map-1'},instructions:['실제 가격·계좌·환불 정책이 아닌 개발용 예시입니다.','입금과 지급 여부는 현장에서 확인합니다.']};break;
    }
    case 'getStampGuide':data={title:'개발용 스탬프투어',dates:DATES,instructions:['START는 참여 시작만 기록합니다.','공통 QR 인식 1회당 1개, 하루 4개 적립합니다.'],reward:{name:'몬스터',locationText:missing?null:'예시 수령 장소',hoursText:missing?null:'예시 수령 시간',notice:'하루 1회·당일 수령. 준비 수량 소진 시 현장에서 안내합니다.'},dailyLimit:4,timezone:'Asia/Seoul',qrValue:missing?null:'MOCK-COMMON-QR'};break;
    case 'getAdminNotices':data={items:empty?[]:structuredClone(state.notices).filter(n=>!state.deleted.has(n.id)).sort((a,b)=>Date.parse(b.updatedAt)-Date.parse(a.updatedAt)||a.id.localeCompare(b.id))};break;
    case 'getAdminNotice':if(state.deleted.has(params.noticeId))failure(404,'NOT_FOUND','삭제된 공지입니다.');data=find(state.notices,params.noticeId);if(missing)Object.assign(data,{templateId:null,links:[]});break;
    case 'postAdminNotice':case 'putAdminNotice':{
      validateNotice(body,state,{scenario,failure});
      if(body.templateId)find(state.templates,body.templateId);
      let old;
      if(op.method==='PUT'){if(state.deleted.has(params.noticeId))failure(404,'NOT_FOUND','삭제된 공지입니다.');old=find(state.notices,params.noticeId);}
      const time=mutate();data={...structuredClone(body),id:old?.id||`notice-created-${state.nextId++}`,createdAt:old?.createdAt||time,updatedAt:time};
      if(old)state.notices[state.notices.findIndex(n=>n.id===old.id)]=data;else {state.notices.push(data);status=201;}break;
    }
    case 'deleteAdminNotice':if(state.deleted.has(params.noticeId))failure(409,'ALREADY_DELETED','이미 삭제된 공지입니다.');find(state.notices,params.noticeId);state.deleted.add(params.noticeId);mutate();data={id:params.noticeId,deleted:true};break;
    case 'getTemplates':data={items:empty?[]:structuredClone(state.templates)};break;
    case 'getTemplate':data=find(state.templates,params.templateId);if(missing)data.translations.en={title:null,body:null,status:'PENDING'};break;
    default:failure(404,'NOT_FOUND','경로가 없습니다.');
  }
  if(op.operationId==='getConfig')data.languages=state.languages.map(code=>({code,label:{ko:'한국어',en:'English','zh-Hans':'中文',ja:'日本語'}[code]}));
  return {status,data:op.admin?data:localize(data,locale),now,locale};
}
