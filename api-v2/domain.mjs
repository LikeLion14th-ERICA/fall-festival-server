// Fictional fixtures only. No DB, actual festival dates, account, or user records.
import { initializeAdmin,hoursFor,inventoryFor,adminExecute,validateNotice } from './admin-domain.mjs';
export const MOCK_NOW = '2030-10-01T18:00:00+09:00';
export const DATES = ['2030-10-01','2030-10-02','2030-10-03'];
export const IMAGE = { url: '/__mock/assets/sample.svg', alt: '개발용 예시 이미지 · 실제 행사 자료 아님', width: 800, height: 600 };
// Fictional six-digit reward code used only by the mock server.
const MOCK_STAMP_RECEIPT_CODE = '482913';
// Fictional booth QR tokens; the link is https://festival.likelionerica.com/stamps?b=<token>.
export const MOCK_STAMP_BOOTHS = [['likelion','멋사 부스'],['booth-mock-1','예시 부스 1'],['booth-mock-2','예시 부스 2'],['booth-mock-3','예시 부스 3'],['booth-mock-4','예시 부스 4']]
  .map(([id,name],index)=>({id,name,token:`mock-booth-token-${String(index+1).padStart(4,'0')}`}));
/**
 * Seeds a session's stamp card: the first `count` booths, collected today. A
 * session without a card gets the demo card its example scenario describes;
 * once a session has started, its real card is used.
 */
export function mockStampCard(state,date,count,rewardClaimed=false) {
  state.stampCard={date,rewardClaimed,stamps:MOCK_STAMP_BOOTHS.slice(0,count).map((booth,index)=>({boothId:booth.id,boothName:booth.name,collectedAt:`${date}T1${index}:00:00+09:00`}))};
}
const MAP_VERSION = 'mock-map-1';
// Design filter chips (docs/wiki/product/translations.md). Japanese labels are mock-only until approved.
const PIN_FILTER_GROUP_ORDER = ['RESTROOM','PHOTO_BOOTH','SMOKING_AREA','TRASH_BIN'];
const PIN_FILTER_GROUP_LABELS = {
  ko:{RESTROOM:'화장실',PHOTO_BOOTH:'포토부스',SMOKING_AREA:'흡연구역',TRASH_BIN:'쓰레기통'},
  en:{RESTROOM:'Restrooms',PHOTO_BOOTH:'Photo Booth',SMOKING_AREA:'Smoking Area',TRASH_BIN:'Trash Bins'},
  'zh-Hans':{RESTROOM:'洗手间',PHOTO_BOOTH:'拍照亭',SMOKING_AREA:'吸烟区',TRASH_BIN:'垃圾桶'},
  ja:{RESTROOM:'トイレ',PHOTO_BOOTH:'フォトブース',SMOKING_AREA:'喫煙所',TRASH_BIN:'ゴミ箱'},
};
// Only facility pins that match a design chip carry a group; other PLACE pins appear under "all" only.
const pinFilterGroup = (pin) => {
  if (pin.target.kind === 'AREA') return null;
  return ({toilet:'RESTROOM','photo-booth':'PHOTO_BOOTH',smoking:'SMOKING_AREA',trash:'TRASH_BIN'}[pin.category] || null);
};
const pinFilterLabel = (group,locale) => {
  const label=PIN_FILTER_GROUP_LABELS[locale]?.[group];
  if(!label)throw new Error(`Missing approved mock filter label for ${locale}/${group}`);
  return label;
};
const image = (alt=IMAGE.alt,variant='default') => ({...structuredClone(IMAGE),url:variant==='default'?IMAGE.url:`${IMAGE.url}?variant=${encodeURIComponent(variant)}`,alt});
const mockImage = (kind,id,name) => image(`개발용 가상 ${kind} ${name} 이미지 · 실제 축제 자료 아님`,`${kind}-${id}`);
const money = amount => ({ amount, currency: 'KRW' });
const link = (label,path='mock-link') => ({ label, url: `https://example.invalid/${path}`, target: '_blank' });
const noticeLink = (labels,path='mock-notice-link') => ({ url: `https://example.invalid/${path}`, labels: { ko: null, en: null, 'zh-Hans': null, ja: null, ...labels } });
// Goods (unlike notice) always carries all 4 locale keys, null for absent ones.
const goodsTranslations = (ko,en) => ({ ko, en, 'zh-Hans': null, ja: null });
function hasContentLocale(translations,locale) {
  return translations?.[locale] != null;
}
export const isoKst = time => new Date(new Date(time).getTime() + 9 * 3600000).toISOString().replace('Z', '+09:00');
export const dayKst = time => isoKst(time).slice(0,10);

function goodsReadyForLocale(g,locale) {
  return hasContentLocale(g.translations,locale)
    && g.images.every(image=>hasContentLocale(image.alt,locale))
    && g.colors.every(color=>hasContentLocale(color.translations,locale))
    && g.sizes.every(size=>hasContentLocale(size.translations,locale));
}
function resolveGoodsResponse(g,locale) {
  const t = g.translations[locale];
  return {
    id: g.id,
    contentLocale:locale,
    name: t.name,
    description: t.description ?? null,
    price: g.price,
    optionMode: g.optionMode,
    images: g.images.map(image => ({
      alt: image.alt[locale],
      masterUrl: image.masterUrl,
      thumbnail320Url: image.thumbnail320Url,
      thumbnail640Url: image.thumbnail640Url,
    })),
    colors: g.colors.map(c => ({ id: c.id, name: c.translations[locale].name })),
    sizes: g.sizes.map(s => ({ id: s.id, label: s.translations[locale].label })),
  };
}

const artist = (id,category,name,songs=[],social=true) => ({
  id,category,name,image:mockImage(category==='ARTIST'?'artist':'contest',id,name),
  introduction:`${name}은(는) 프런트 목록·상세 화면 검증을 위한 가상 ${category==='ARTIST'?'아티스트':'콘테스트 팀'}입니다. 실제 축제 출연 정보가 아닙니다.`,
  socialLinks:social?[link('개발용 공식 채널',`mock-social/${id}`)]:[],
  songs:songs.slice(0,3).map((title,index)=>link(title,`mock-song/${id}/${index+1}`)),
});

const artistFixtures = [
  artist('artist-a','ARTIST','목 루미너스',['첫 파동','빛의 경로','새벽 기록']),
  artist('artist-breeze','ARTIST','목 블루아워',['푸른 잔상','느린 계단']),
  artist('artist-cosmos','ARTIST','목 코스믹웨이브',['별의 좌표','밤의 항해','원점으로']),
  artist('artist-drift','ARTIST','목 드리프트',['정류장','작은 파도']),
  artist('artist-ember','ARTIST','목 엠버라인',['불씨','온도의 거리','끝나지 않은 오후']),
  artist('artist-fable','ARTIST','목 페이블',['종이달','이야기의 끝']),
  artist('artist-groove','ARTIST','목 그루브메이커',['오후 6시','리듬의 결']),
  artist('artist-halo','ARTIST','목 헤일로',['가벼운 중력','반짝이는 틈']),
  artist('artist-iris','ARTIST','목 아이리스',['보랏빛 산책','일렁임','한 장의 계절']),
  artist('artist-junction','ARTIST','목 정션',['교차점','다른 방향'],false),
  artist('artist-kite','ARTIST','목 카이트',['바람의 문장','높이']),
  artist('artist-lunar','ARTIST','목 루나폴',['밤의 우편함','은하수 아래']),
  artist('artist-mono','ARTIST','목 모노크롬',['검은 종이','선명한 여백','흰 소음']),
  artist('artist-nova','ARTIST','목 노바셀',['새로운 궤도','천천히 밝아져']),
  artist('artist-orbit','ARTIST','목 오르빗',['회전목마','가까운 별']),
  artist('artist-b','CONTEST','목 캠퍼스 밴드 01',['목 라이브 세션']),
  artist('contest-d1-2','CONTEST','목 캠퍼스 댄스 01',[],false),
  artist('contest-d1-3','CONTEST','목 보컬 스테이지 01',['목 보컬 클립']),
  artist('contest-d2-1','CONTEST','목 캠퍼스 밴드 02',['목 밴드 세션']),
  artist('contest-d2-2','CONTEST','목 퍼포먼스 팀 02',[],false),
  artist('contest-d2-3','CONTEST','목 보컬 스테이지 02',['목 보컬 클립 02']),
  artist('contest-d3-1','CONTEST','목 캠퍼스 밴드 03',['목 밴드 세션 03']),
  artist('contest-d3-2','CONTEST','목 퍼포먼스 팀 03',[],false),
  artist('contest-d3-3','CONTEST','목 보컬 스테이지 03',['목 보컬 클립 03']),
];

const artistById = new Map(artistFixtures.map(value=>[value.id,value]));
const lineupGroups = [
  {artists:['artist-a','artist-breeze','artist-cosmos','artist-drift','artist-ember'],contests:['artist-b','contest-d1-2','contest-d1-3']},
  {artists:['artist-fable','artist-groove','artist-halo','artist-iris','artist-junction'],contests:['contest-d2-1','contest-d2-2','contest-d2-3']},
  {artists:['artist-kite','artist-lunar','artist-mono','artist-nova','artist-orbit'],contests:['contest-d3-1','contest-d3-2','contest-d3-3']},
];
const contestSlots = [['17:00','17:20'],['17:25','17:45'],['17:50','18:10']];
const artistSlots = [['18:20','18:50'],['19:00','19:35'],['19:45','20:20'],['20:30','21:05'],['21:15','21:50']];
const performance = (id,date,title,artistId,[start,end],description) => {
  const performer=artistById.get(artistId);
  return {id,date,title,artists:[{id:performer.id,name:performer.name}],startsAt:`${date}T${start}:00+09:00`,endsAt:`${date}T${end}:00+09:00`,description};
};
const performanceFixtures = DATES.flatMap((date,dayIndex) => {
  const group=lineupGroups[dayIndex];
  return [
    ...group.contests.map((artistId,index)=>performance(index===0?`contest-${dayIndex+1}`:`contest-d${dayIndex+1}-${index+1}`,date,`목 콘테스트 DAY ${dayIndex+1}-${index+1}`,artistId,contestSlots[index],'가상 콘테스트 무대 순서 예시입니다.')),
    ...group.artists.map((artistId,index)=>performance(index===0?`show-${dayIndex+1}`:`show-d${dayIndex+1}-${index+1}`,date,`목 메인 스테이지 DAY ${dayIndex+1}-${index+1}`,artistId,artistSlots[index],'가상 메인 무대 순서 예시입니다. 실제 공연 일정이 아닙니다.')),
  ];
});

// Fictional receiving accounts; only the detail response carries them.
const MOCK_BANK_TRANSFERS = {
  'space-pub':{bankId:'mock-bank',bankDisplayName:'개발용 은행',accountNumber:'000000000000',accountHolderName:'개발용 예금주',tossLinkEnabled:false},
  'space-food-truck':{bankId:'mock-bank',bankDisplayName:'개발용 은행',accountNumber:'012345678901',accountHolderName:'개발용 푸드트럭',tossLinkEnabled:false},
};
const mapForCategory = {BOOTH:'map-area',PUB:'map-pub',FLEA_MARKET:'map-market',FOOD_TRUCK:'map-food',STUDENT_COUNCIL_BOOTH:'map-area',PROMOTION_BOOTH:'map-area'};
const EVENT_CATEGORIES = new Set(['BOOTH','STUDENT_COUNCIL_BOOTH','PROMOTION_BOOTH']);
const MENU_CATEGORIES = new Set(['PUB','FOOD_TRUCK']);
const space = ({id,category,name,locationText,operator,hoursText,description,contact,experience,events,menu}) => {
  const key=id.replace(/^space-/,'');
  const mapId=mapForCategory[category];
  return {
    id,category,name,image:mockImage('space',id,name),locationText,operator,hoursText,description,contact,
    experience:EVENT_CATEGORIES.has(category)?experience:null,
    events:EVENT_CATEGORIES.has(category)?events:[],
    menu:MENU_CATEGORIES.has(category)?menu:[],
    mapTarget:{mapId,placeId:`place-${key}`,pinId:`pin-${key}`,mapVersion:MAP_VERSION},
    bankTransfer:null,
  };
};
const pubMenu = (theme,index) => [
  {name:`${theme} 시그니처 하이볼`,price:money(5000+(index%3)*500)},
  {name:`${theme} 과일 에이드`,price:money(4500+(index%2)*500)},
  {name:'바삭 감자 플레이트',price:money(7500+(index%3)*500)},
  {name:'숯불 닭꼬치',price:money(6500+(index%2)*500)},
  {name:'목 야식 한상',price:money(11500+(index%3)*1000)},
];
const boothDefinitions = [
  ['booth','목 별자리 연구소','나만의 별자리 카드를 조합하는 가상 체험',['목 스탬프 카드 꾸미기','목 현장 퀴즈']],
  ['booth-02','목 리듬 아케이드','리듬 버튼으로 완성하는 가상 미션',['목 리듬 미션']],
  ['booth-03','목 초록 공방','재사용 소재로 만드는 가상 키링 체험',[]],
  ['booth-04','목 사운드 랩','소리 조합을 듣고 기록하는 가상 체험',['목 사운드 투표']],
  ['booth-05','목 미션 스튜디오','단계별 선택으로 진행하는 가상 추리 게임',['목 미션 완주 선물']],
  ['booth-06','목 포스터 인쇄소','문구와 색상을 고르는 가상 포스터 제작',[]],
  ['booth-07','목 퀘스트 아지트','팀 단위로 풀어보는 가상 미니 퀘스트',['목 팀 기록판']],
  ['booth-08','목 업사이클 공방','폐현수막 조각을 활용한 가상 소품 만들기',[]],
  ['booth-09','목 향기 연구소','향 조합을 고르는 가상 감각 체험',['목 향기 투표']],
  ['booth-10','목 캠퍼스 라디오','사연을 남기고 듣는 가상 라디오 부스',['목 사연 소개']],
  ['booth-11','목 픽셀 아틀리에','픽셀 스티커를 완성하는 가상 드로잉 체험',[]],
  ['booth-12','목 드로잉 살롱','한 장의 엽서를 꾸미는 가상 체험',['목 엽서 전시']],
];
const pubDefinitions = [
  ['pub','목 달빛포차','달빛'],['pub-02','목 노을상회','노을'],['pub-03','목 파도주점','파도'],['pub-04','목 별빛식당','별빛'],['pub-05','목 청춘포차','청춘'],
  ['pub-06','목 오후열한시','오후'],['pub-07','목 골목작전','골목'],['pub-08','목 밤산책','밤산책'],['pub-09','목 한잔연구소','연구소'],['pub-10','목 열두번째테이블','열두번째'],
];
const marketDefinitions = [
  ['market','목 필름마켓'],['market-02','목 레코드마켓'],['market-03','목 그래픽마켓'],['market-04','목 빈티지서랍'],
  ['market-05','목 작은책방'],['market-06','목 손편지상점'],['market-07','목 리빙마켓'],['market-08','목 주말공방'],
];
const foodTruckDefinitions = [
  ['food-truck','목 불맛트럭',[['목 불닭 타코',6000],['목 치즈 핫도그',4500],['목 레몬 에이드',3500]]],
  ['food-truck-02','목 달콤트럭',[['목 크로플',5000],['목 츄러스',4000],['목 딸기 스무디',4500]]],
];
const spaceFixtures = [
  ...boothDefinitions.map(([key,name,experience,events],index)=>space({id:`space-${key}`,category:'BOOTH',name,locationText:`목 부스 구역 A-${String(index+1).padStart(2,'0')}`,operator:`목 개발 운영팀 ${String(index+1).padStart(2,'0')}`,hoursText:index===7?null:'개발용 15:00~22:00',description:`${name}의 가상 체험 안내입니다. 실제 참여 부스·운영 정보가 아닙니다.`,contact:index===4||index===9?null:link('개발용 문의',`mock-contact/${key}`),experience,events})),
  ...pubDefinitions.map(([key,name,theme],index)=>space({id:`space-${key}`,category:'PUB',name,locationText:`목 주점 구역 P-${String(index+1).padStart(2,'0')}`,operator:`목 개발 운영팀 P${String(index+1).padStart(2,'0')}`,hoursText:index===6?null:'개발용 17:00~23:00',description:`${name}의 가상 메뉴·운영 안내입니다. 실제 판매 정보가 아닙니다.`,contact:index===3?null:link('개발용 문의',`mock-contact/${key}`),menu:pubMenu(theme,index)})),
  ...foodTruckDefinitions.map(([key,name,items],index)=>space({id:`space-${key}`,category:'FOOD_TRUCK',name,locationText:`목 푸드트럭 구역 F-${String(index+1).padStart(2,'0')}`,operator:`목 개발 푸드트럭 ${String(index+1).padStart(2,'0')}`,hoursText:'개발용 12:00~24:00',description:`${name}의 가상 메뉴 안내입니다. 실제 판매 정보가 아닙니다.`,contact:null,menu:items.map(([name,price])=>({name,price:money(price)}))})),
  space({id:'space-student-council',category:'STUDENT_COUNCIL_BOOTH',name:'목 총학생회 부스',locationText:'목 부스 구역 S-01',operator:'목 총학생회',hoursText:'개발용 12:00~21:00',description:'가상 총학생회 부스 안내입니다. 실제 운영 정보가 아닙니다.',contact:link('개발용 문의','mock-contact/student-council'),experience:'가상 축제 안내와 굿즈 수령 확인',events:['목 축제 퀴즈']}),
  space({id:'space-promotion',category:'PROMOTION_BOOTH',name:'목 프로모션 부스',locationText:'목 부스 구역 R-01',operator:'목 협찬사',hoursText:'개발용 13:00~20:00',description:'가상 프로모션 부스 안내입니다. 실제 협찬 정보가 아닙니다.',contact:null,experience:'가상 제품 체험',events:['목 경품 추첨']}),
  ...marketDefinitions.map(([key,name],index)=>space({id:`space-${key}`,category:'FLEA_MARKET',name,locationText:`목 플리마켓 구역 M-${String(index+1).padStart(2,'0')}`,operator:index===5?null:`목 개발 셀러 ${String(index+1).padStart(2,'0')}`,hoursText:index===2?null:'개발용 14:00~21:00',description:`${name}의 가상 셀러 소개입니다. 실제 판매 품목·운영 정보가 아닙니다.`,contact:index===1||index===6?null:link('개발용 문의',`mock-contact/${key}`)})),
];

export function createState() {
  const translation = (title,body) => ({title,body});
  const notices = [
    {id:'notice-1',type:'GENERAL',translations:{ko:translation('예시 공지','개발용 공지 본문입니다.'),en:translation('Sample notice','Mock content only.')},links:[noticeLink({ko:'예시 안내',en:'Sample link'})],templateId:null,createdAt:'2030-10-01T16:00:00+09:00',updatedAt:'2030-10-01T16:00:00+09:00'},
    {id:'notice-lost',type:'LOST_FOUND',translations:{ko:translation('예시 분실물','실제 분실물이 아닙니다.'),en:translation('Sample lost item','Mock item, not a real report.')},links:[],templateId:null,createdAt:'2030-09-30T16:00:00+09:00',updatedAt:'2030-09-30T16:00:00+09:00'},
    {id:'notice-old',type:'GENERAL',translations:{ko:translation('지난 예시 공지','사용자 목록에서는 제외합니다.'),en:translation('Past sample notice','Excluded from the user list.')},links:[],templateId:null,createdAt:'2030-09-30T18:00:00+09:00',updatedAt:'2030-10-01T17:00:00+09:00'},
    {id:'notice-ko-only',type:'GENERAL',translations:{ko:translation('영어 미번역 예시','과거 이관 데이터처럼 영어 번역이 없는 예시입니다. en 공개 목록에서는 제외됩니다.')},links:[],templateId:null,createdAt:'2030-10-01T17:00:00+09:00',updatedAt:'2030-10-01T17:00:00+09:00'},
    {id:'notice-route',type:'GENERAL',translations:{ko:translation('목 구역 이동 안내','가상 구역 이동 동선을 확인하는 개발용 공지입니다.'),en:translation('Mock route notice','A fictional route notice for frontend work.')},links:[],templateId:null,createdAt:'2030-10-01T17:10:00+09:00',updatedAt:'2030-10-01T17:10:00+09:00'},
    {id:'notice-stage',type:'GENERAL',translations:{ko:translation('목 공연 대기 안내','가상 공연 목록과 대기 상태를 검증하는 개발용 공지입니다.'),en:translation('Mock stage notice','A fictional stage notice for frontend work.')},links:[],templateId:null,createdAt:'2030-10-01T17:20:00+09:00',updatedAt:'2030-10-01T17:20:00+09:00'},
    {id:'notice-weather',type:'GENERAL',translations:{ko:translation('목 날씨 대비 안내','가상 날씨 안내 카드 표시를 위한 개발용 공지입니다.'),en:translation('Mock weather notice','A fictional weather notice for frontend work.')},links:[],templateId:null,createdAt:'2030-10-01T17:30:00+09:00',updatedAt:'2030-10-01T17:30:00+09:00'},
    {id:'notice-safety',type:'GENERAL',translations:{ko:translation('목 안전 안내','가상 안전 안내의 긴 본문과 링크 영역을 확인하는 개발용 공지입니다.'),en:translation('Mock safety notice','A fictional safety notice for frontend work.')},links:[noticeLink({ko:'개발용 안내 링크',en:'Mock guidance link'},'mock-notice/safety')],templateId:null,createdAt:'2030-10-01T17:40:00+09:00',updatedAt:'2030-10-01T17:40:00+09:00'},
    {id:'notice-lost-card',type:'LOST_FOUND',translations:{ko:translation('목 분실물 안내: 카드지갑','가상 분실물 카드 예시입니다. 실제 신고가 아닙니다.'),en:translation('Mock lost card holder','This is a fictional lost-item example.')},links:[],templateId:null,createdAt:'2030-09-29T15:00:00+09:00',updatedAt:'2030-10-01T15:00:00+09:00'},
    {id:'notice-lost-earbuds',type:'LOST_FOUND',translations:{ko:translation('목 분실물 안내: 이어폰 케이스','가상 분실물 카드 예시입니다. 실제 신고가 아닙니다.'),en:translation('Mock lost earbuds case','This is a fictional lost-item example.')},links:[],templateId:null,createdAt:'2030-09-28T15:00:00+09:00',updatedAt:'2030-10-01T14:00:00+09:00'},
  ];
  const maps = [
    ['map-overview','목 전체 지도','OVERVIEW'],['map-area','목 부스 구역 지도','AREA'],['map-pub','목 주점 구역 지도','AREA'],['map-market','목 플리마켓 구역 지도','AREA'],
    ['map-stage','목 공연장 구역 지도','AREA'],['map-food','목 푸드트럭 구역 지도','AREA'],['map-lake','목 호수공원 구역 지도','AREA'],
  ].map(([id,name,kind])=>({id,name,kind,version:MAP_VERSION,image:mockImage('map',id,name)}));
  const pins=Object.fromEntries(maps.map(map=>[map.id,[]]));
  pins['map-overview'].push(
    {id:'pin-area',category:'area-booth',label:'목 부스 구역',x:0.16,y:0.28,target:{kind:'AREA',mapId:'map-area'}},
    {id:'pin-area-pub',category:'area-pub',label:'목 주점 구역',x:0.34,y:0.68,target:{kind:'AREA',mapId:'map-pub'}},
    {id:'pin-area-market',category:'area-market',label:'목 플리마켓 구역',x:0.50,y:0.24,target:{kind:'AREA',mapId:'map-market'}},
    {id:'pin-area-stage',category:'area-stage',label:'목 공연장 구역',x:0.72,y:0.56,target:{kind:'AREA',mapId:'map-stage'}},
    {id:'pin-area-food',category:'area-food',label:'목 푸드트럭 구역',x:0.28,y:0.48,target:{kind:'AREA',mapId:'map-food'}},
    {id:'pin-area-lake',category:'area-lake',label:'목 호수공원 구역',x:0.82,y:0.20,target:{kind:'AREA',mapId:'map-lake'}},
  );
  const coordinate=index => ({x:0.14+(index%4)*0.23,y:0.18+Math.floor(index/4)*0.23});
  const mapIndexes={};
  for(const space of spaceFixtures){
    const mapId=space.mapTarget.mapId,index=mapIndexes[mapId]||0;
    mapIndexes[mapId]=index+1;
    pins[mapId].push({id:space.mapTarget.pinId,category:{BOOTH:'booth',PUB:'pub',FLEA_MARKET:'market',FOOD_TRUCK:'food-truck',STUDENT_COUNCIL_BOOTH:'student-council-booth',PROMOTION_BOOTH:'promotion-booth'}[space.category],label:space.name,...coordinate(index),target:{kind:'PLACE',placeId:space.mapTarget.placeId}});
  }
  const places=spaceFixtures.map(space=>({id:space.mapTarget.placeId,kind:'SPACE',name:space.name,locationText:space.locationText,hoursText:space.hoursText,description:space.description,usage:null,spaceId:space.id}));
  const landmarks = [
    {id:'place-toilet',mapId:'map-area',pinId:'pin-toilet',category:'toilet',label:'목 화장실',x:0.88,y:0.78,kind:'FACILITY',name:'목 화장실',locationText:'목 부스 구역 동쪽',hoursText:null,description:'가상 편의시설 위치입니다.',usage:null},
    {id:'place-smoking',mapId:'map-area',pinId:'pin-smoking',category:'smoking',label:'목 흡연구역',x:0.08,y:0.78,kind:'FACILITY',name:'목 흡연구역',locationText:'목 부스 구역 서쪽',hoursText:null,description:'가상 편의시설 위치입니다.',usage:null},
    {id:'place-trash',mapId:'map-area',pinId:'pin-trash',category:'trash',label:'목 쓰레기통',x:0.48,y:0.88,kind:'FACILITY',name:'목 쓰레기통',locationText:'목 부스 구역 남쪽',hoursText:null,description:'가상 편의시설 위치입니다.',usage:null},
    {id:'place-information',mapId:'map-market',pinId:'pin-information',category:'information',label:'목 인포메이션',x:0.88,y:0.78,kind:'LANDMARK',name:'목 인포메이션',locationText:'목 플리마켓 구역 입구',hoursText:'개발용 14:00~21:00',description:'가상 안내 데스크입니다.',usage:'실제 안내소가 아닙니다.'},
    {id:'place-toilet-pub',mapId:'map-pub',pinId:'pin-toilet-pub',category:'toilet',label:'목 화장실',x:0.88,y:0.78,kind:'FACILITY',name:'목 화장실',locationText:'목 주점 구역 동쪽',hoursText:null,description:'가상 편의시설 위치입니다.',usage:null},
    {id:'place-smoking-pub',mapId:'map-pub',pinId:'pin-smoking-pub',category:'smoking',label:'목 흡연구역',x:0.08,y:0.78,kind:'FACILITY',name:'목 흡연구역',locationText:'목 주점 구역 서쪽',hoursText:null,description:'가상 편의시설 위치입니다.',usage:null},
    {id:'place-stage',mapId:'map-stage',pinId:'pin-stage',category:'stage',label:'목 메인 스테이지',x:0.25,y:0.34,kind:'LANDMARK',name:'목 메인 스테이지',locationText:'목 공연장 구역 중앙',hoursText:'개발용 17:00~22:00',description:'가상 공연장 위치입니다.',usage:'실제 공연 장소가 아닙니다.'},
    {id:'place-stage-gate',mapId:'map-stage',pinId:'pin-stage-gate',category:'gate',label:'목 공연장 게이트',x:0.58,y:0.30,kind:'LANDMARK',name:'목 공연장 게이트',locationText:'목 공연장 구역 입구',hoursText:'개발용 16:30~22:00',description:'가상 입장 동선입니다.',usage:null},
    {id:'place-student-zone',mapId:'map-stage',pinId:'pin-student-zone',category:'student-zone',label:'목 재학생존',x:0.36,y:0.68,kind:'LANDMARK',name:'목 재학생존',locationText:'목 공연장 구역 왼쪽',hoursText:'개발용 13:00~22:00',description:'가상 관람 구역입니다.',usage:'실제 입장 정책이 아닙니다.'},
    {id:'place-visitor-zone',mapId:'map-stage',pinId:'pin-visitor-zone',category:'visitor-zone',label:'목 외부인존',x:0.68,y:0.68,kind:'LANDMARK',name:'목 외부인존',locationText:'목 공연장 구역 오른쪽',hoursText:'개발용 13:00~22:00',description:'가상 관람 구역입니다.',usage:'실제 입장 정책이 아닙니다.'},
    {id:'place-food-truck',mapId:'map-food',pinId:'pin-food-truck',category:'food-truck',label:'목 푸드트럭',x:0.50,y:0.48,kind:'LANDMARK',name:'목 푸드트럭',locationText:'목 푸드트럭 구역 중앙',hoursText:'개발용 14:00~22:00',description:'가상 푸드트럭 안내입니다.',usage:'실제 판매 정보가 아닙니다.'},
    {id:'place-photo-booth',mapId:'map-lake',pinId:'pin-photo-booth',category:'photo-booth',label:'목 포토부스',x:0.50,y:0.48,kind:'LANDMARK',name:'목 포토부스',locationText:'목 호수공원 구역 중앙',hoursText:'개발용 14:00~21:00',description:'가상 포토부스 안내입니다.',usage:'실제 운영 여부가 확정되지 않았습니다.'},
    {id:'place-ticket',mapId:'map-overview',pinId:'pin-ticket',category:'ticket',label:'목 티켓 안내존',x:0.64,y:0.78,kind:'LANDMARK',name:'목 티켓 안내존',locationText:'목 전체 지도 남쪽',hoursText:'개발용 13:00~21:00',description:'가상 티켓 안내 위치입니다.',usage:'실제 수령 장소가 아닙니다.'},
  ];
  for(const landmark of landmarks){
    pins[landmark.mapId].push({id:landmark.pinId,category:landmark.category,label:landmark.label,x:landmark.x,y:landmark.y,target:{kind:'PLACE',placeId:landmark.id}});
    places.push({id:landmark.id,kind:landmark.kind,name:landmark.name,locationText:landmark.locationText,hoursText:landmark.hoursText,description:landmark.description,usage:landmark.usage,spaceId:null});
  }
  return initializeAdmin({
    revision:1,nextId:1,hypedCounts:{},festivalDays:[
      {operatingDay:'2030-10-01',opensAt:'13:00',closesAt:'22:00'},
      {operatingDay:'2030-10-02',opensAt:'12:00',closesAt:'21:00'},
      {operatingDay:'2030-10-03',opensAt:'14:00',closesAt:'20:00'},
    ],artists:structuredClone(artistFixtures),performances:structuredClone(performanceFixtures),notices,deleted:new Set(),crowding:{'2030-10-01':{level:'MODERATE',updatedAt:'2030-10-01T17:00:00+09:00'}},
    goods:[{
      id:'goods-shirt',
      optionMode:'OPTIONS',
      translations:goodsTranslations(
        {name:'예시 의류',description:'실제 상품·가격이 아닙니다.'},
        {name:'Sample apparel',description:'Not a real product or price.'}
      ),
      price:money(1000),
      images:[{
        mediaId:'00000000-0000-4000-8000-000000000050',
        alt:{ko:'개발용 가상 상품 앞면',en:'Fictional product front','zh-Hans':null,ja:null},
        masterUrl:'/api/v2/media/goods-images/00000000-0000-4000-8000-000000000050/master',
        thumbnail320Url:'/api/v2/media/goods-images/00000000-0000-4000-8000-000000000050/320',
        thumbnail640Url:'/api/v2/media/goods-images/00000000-0000-4000-8000-000000000050/640',
      }],
      colors:[
        {id:'color-a',translations:goodsTranslations({name:'예시 색상 A'},{name:'Sample Color A'})},
        {id:'color-b',translations:goodsTranslations({name:'예시 색상 B'},{name:'Sample Color B'})},
      ],
      sizes:[
        {id:'size-m',translations:goodsTranslations({label:'M'},{label:'M'})},
        {id:'size-l',translations:goodsTranslations({label:'L'},{label:'L'})},
      ],
      combinations:[
        {id:'combo-shirt-a-m',colorId:'color-a',sizeId:'size-m'},
        {id:'combo-shirt-a-l',colorId:'color-a',sizeId:'size-l'},
        {id:'combo-shirt-b-m',colorId:'color-b',sizeId:'size-m'},
      ],
      createdAt:'2030-10-01T16:00:00+09:00',
      updatedAt:'2030-10-01T16:00:00+09:00',
    }],
    spaces:structuredClone(spaceFixtures),maps,pins,places,
    templates:[{id:'template-1',name:'예시 일반 공지',translations:{ko:translation('예시 제목','예시 본문'),en:translation('Sample title','Sample body')}}],
  });
}
export class ApiFailure extends Error {
  constructor(status,code,message,details=[]) { super(message);Object.assign(this,{status,code,details}); }
}
export function failure(status,code,message,details=[]) { throw new ApiFailure(status,code,message,details); }
const KNOWN_LOCALES=new Set(['ko','en','zh-Hans','ja']);
function find(items,id) { const item=items.find(x=>x.id===id);if(!item)failure(404,'NOT_FOUND','요청한 정보를 찾을 수 없습니다.');return structuredClone(item); }
function findPublicGoods(state,goodsId,locale) {
  const goods=state.goods.find(item=>item.id===goodsId);
  if(!goods||!goodsReadyForLocale(goods,locale))failure(404,'NOT_FOUND','요청한 정보를 찾을 수 없습니다.');
  return goods;
}
const defaultDate = date => date < DATES[0] ? DATES[0] : date > DATES.at(-1) ? DATES.at(-1) : date;
export const crowdingDayFor = now => defaultDate(dayKst(now));
// Approved crowd messages (docs/wiki/product/translations.md). Japanese has no approved copy yet.
const CROWD_MESSAGES={
  ko:{BEFORE_OPEN:t=>`오늘 재학생존 입장은 ${t}에 시작해요`,RELAXED:'재학생존의 공간이 많이 남았어요.',MODERATE:'재학생존의 공간이 절반 이상 찼어요.',CROWDED:'재학생존이 많이 혼잡해요.',FULL:'재학생존이 꽉 차서 외부인존에서만 즐길 수 있어요.',CLOSED:'오늘 재학생존 운영이 종료됐어요'},
  en:{BEFORE_OPEN:t=>`Student Zone entry starts at ${t} today`,RELAXED:'Plenty of space available',MODERATE:'At least half full',CROWDED:'Very crowded',FULL:'The Student Zone is full. Please use the Visitor Zone.',CLOSED:'The Student Zone is closed for today'},
  'zh-Hans':{BEFORE_OPEN:t=>`今日学生区${t}开放入场`,RELAXED:'空间充足',MODERATE:'已占用一半以上',CROWDED:'非常拥挤',FULL:'本校学生区已满，请前往访客区。',CLOSED:'今日学生区已关闭'},
};
function crowdInfo(state,now,scenario,locale='ko',includeSelectedDaySavedState=false) {
  const today=dayKst(now);
  let operatingDay=defaultDate(today);
  const hours=hoursFor(state,operatingDay);
  const opensAt=`${operatingDay}T${hours.opensAt}:00+09:00`;
  const closesAt=`${operatingDay}T${hours.closesAt}:00+09:00`;
  const stored=scenario==='unmodified'||(!includeSelectedDaySavedState&&today!==operatingDay)?null:state.crowding[operatingDay];
  const status=+new Date(now)<+new Date(opensAt)?'BEFORE_OPEN':+new Date(now)>=+new Date(closesAt)?'CLOSED':stored?.level||'RELAXED';
  const colors={RELAXED:'green',MODERATE:'orange',CROWDED:'red',FULL:'black'};
  const messages=CROWD_MESSAGES[locale]||CROWD_MESSAGES.ko;
  const active=!!colors[status];
  return {operatingDay,opensAt,closesAt,operatingStatus:active?'OPEN':status,status,savedLevel:stored?.level||null,colorToken:colors[status]||null,message:CROWD_MESSAGES[locale]?(status==='BEFORE_OPEN'?messages.BEFORE_OPEN(hours.opensAt):messages[status]):`Mock crowd status: ${status}`,updatedAt:active?stored?.updatedAt||null:null,timeBasis:active?(stored?'OPERATOR':'OPENING'):'NONE'};
}
export function scenarioTime(scenario,now) {
  return ({'before-open':'2030-09-30T10:00:00+09:00',closed:'2030-10-01T23:00:00+09:00',ended:'2030-10-04T00:00:00+09:00',overnight:'2030-10-02T01:00:00+09:00'})[scenario]||now;
}
// Korean is the only ready default. Controlled mock scenarios can enable complete fictional translations.
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
/** Today's card of the session; a new festival day starts an empty card. */
function stampCardData(state,date) {
  if(state.stampCard.date!==date)state.stampCard={date,rewardClaimed:false,stamps:[]};
  return {date,dailyLimit:4,stamps:structuredClone(state.stampCard.stamps),rewardClaimed:state.stampCard.rewardClaimed};
}
export function execute(op,state,{params={},query={},body,scenario='normal',now=MOCK_NOW}={}) {
  now=scenarioTime(scenario,now);
  if(scenario==='all-languages')state.languages=['ko','en','zh-Hans','ja'];
  const locale=query.locale||'ko';
  if(!state.languages.includes(locale)){
    failure(400,KNOWN_LOCALES.has(locale)?'LOCALE_NOT_READY':'INVALID_QUERY',KNOWN_LOCALES.has(locale)?'준비 완료 언어만 요청할 수 있습니다.':'요청 파라미터를 확인해 주세요.');
  }
  if(scenario==='error')failure(503,'SERVICE_UNAVAILABLE','일시적으로 정보를 불러올 수 없습니다.');
  if(scenario==='unconfigured'&&['getCrowding','getAdminCrowding'].includes(op.operationId))failure(503,'CROWDING_SCHEDULE_UNCONFIGURED','재학생존 운영 일정이 아직 등록되지 않았습니다.');
  if(scenario==='not-found')failure(404,'NOT_FOUND','요청한 정보를 찾을 수 없습니다.');
  if(scenario==='already-deleted')failure(409,'ALREADY_DELETED','이미 삭제된 공지입니다.');
  if(scenario==='version-conflict')failure(409,'MAP_VERSION_MISMATCH','지도 이미지를 다시 조회해 주세요.');
  const empty=scenario==='empty',missing=scenario==='missing-optional',sold=scenario==='sold-out';
  const date=dayKst(now);
  let data,status=200;
  const mutate=()=>{state.revision++;now=isoKst(+new Date(now)+state.revision);return now;};
  const getAvailability=goodsId=>inventoryFor(state,goodsId,{failure,sold,locale});
  const extra=adminExecute(op,state,{params,body,scenario,mutate,failure,DATES});
  if(extra)return {status:extra.status||200,data:extra.data,now,locale};
  switch(op.operationId){
    case 'getConfig':data={festival:{id:'festival-mock',title:'개발용 가상 축제',dates:empty?[]:DATES,defaultDate:empty?null:defaultDate(date)},languages:[{code:'ko',label:'한국어'},{code:'en',label:'English'}],links:{universityNotices:missing?null:link('예시 학교 공지'),faq:scenario==='faq-ready'?link('예시 축제 FAQ','mock-faq'):null,officialChannels:empty||missing?[]:[{id:'channel-mock',...link('예시 공식 채널'),iconKey:'website'}]}};break;
    case 'getCrowding':case 'getAdminCrowding':data=crowdInfo(state,now,scenario,locale,op.operationId==='getAdminCrowding');break;
    case 'putAdminCrowding':{
      if(body.level==='FULL'&&body.confirmFull!==true)failure(422,'CONFIRMATION_REQUIRED','만석 변경 확인이 필요합니다.');
      const savedDay=crowdingDayFor(now);
      const stored=state.crowding[savedDay];
      if(stored?.level!==body.level)state.crowding[savedDay]={level:body.level,updatedAt:mutate()};
      return {status:204,data:null,now,locale};
    }
    case 'getNotices':{
      let items=state.notices.filter(n=>!state.deleted.has(n.id)
        &&(n.type==='LOST_FOUND'||dayKst(n.createdAt)===date)
        &&hasContentLocale(n.translations,locale)
        &&n.links.every(link=>hasContentLocale(link.labels,locale))).map(n=>{
        const t=n.translations[locale];
        return {id:n.id,type:n.type,contentLocale:locale,title:t.title,body:t.body,links:n.links.map(l=>({url:l.url,label:l.labels[locale],target:'_blank'})),createdAt:n.createdAt};
      });
      if(scenario==='new-notice'&&['ko','en'].includes(locale))items.push({id:'notice-new',type:'GENERAL',contentLocale:locale,title:locale==='ko'?'추가 예시 공지':'New sample',body:locale==='ko'?'새 공지 버튼 검증용':'Mock new content',links:[],createdAt:now});
      if(scenario==='deleted')items=items.filter(n=>n.id!=='notice-1');
      if(empty)items=[];
      items.sort((a,b)=>Date.parse(b.createdAt)-Date.parse(a.createdAt)||a.id.localeCompare(b.id));
      if(missing)items.forEach(n=>n.links=[]);
      data={items,visibleIds:items.map(n=>n.id),asOfDate:date};break;
    }
    case 'getGoods':data={items:empty?[]:state.goods.filter(g=>goodsReadyForLocale(g,locale)).map(g=>resolveGoodsResponse(g,locale))};break;
    case 'getGoodsImage':data=null;break;
    case 'getGoodsAvailability':data={items:empty?[]:state.goods.filter(g=>goodsReadyForLocale(g,locale)).map(g=>getAvailability(g.id))};break;
    case 'getAdminGoods':data={items:empty?[]:state.goods.map(g=>inventoryFor(state,g.id,{admin:true,sold,failure}))};break;
    case 'getGood':{
      const g=findPublicGoods(state,params.goodsId,locale);
      data=resolveGoodsResponse(g,locale);
      if(missing)data.description=null;
      break;
    }
    case 'getGoodAvailability':data=getAvailability(findPublicGoods(state,params.goodsId,locale).id);break;
    case 'getPaymentGuide':{
      const g=findPublicGoods(state,params.goodsId,locale);
      const resolved=resolveGoodsResponse(g,locale);
      data={goodsId:g.id,name:resolved.name,price:g.price,account:missing?null:{bankName:'개발용 은행',accountNumber:'MOCK-NOT-PAYABLE',holder:'개발용 예금주'},transferLink:null,instructions:['현장에서 상품과 색상, 사이즈를 확인한 후 송금해 주세요.','목 응답은 실제 송금을 지원하지 않습니다.'],locationText:missing?null:'예시 판매 장소',hoursText:missing?null:'예시 운영 시간'};
      break;
    }
    case 'getLineup':{
      const selected=query.date||defaultDate(date),category=query.category||'ARTIST';
      if(!DATES.includes(selected))failure(400,'INVALID_DATE','행사 날짜 중에서 선택해 주세요.');
      const items=state.performances.filter(p=>p.date===selected&&state.artists.find(artist=>artist.id===p.artists[0]?.id)?.category===category).sort((a,b)=>a.startsAt.localeCompare(b.startsAt)||a.id.localeCompare(b.id)).map((p,i)=>{
        const performer=state.artists.find(artist=>artist.id===p.artists[0].id);
        return {artistId:performer.id,performanceId:p.id,name:performer.name,image:structuredClone(performer.image),order:i+1};
      });
      data={date:selected,category,items:empty?[]:items};break;
    }
    case 'getArtist':{
      const performer=find(state.artists,params.artistId);
      data={...performer,performances:state.performances.filter(p=>p.artists.some(a=>a.id===params.artistId)).map(({id,date,startsAt,endsAt})=>({id,date,startsAt,endsAt}))};
      if(missing)Object.assign(data,{introduction:null,socialLinks:[],songs:[]});break;
    }
    case 'getArtistHyped':{
      data={hypedEnabled:DATES.includes(date),items:(empty?[]:state.artists.filter(artist=>artist.category==='ARTIST'))
        .map(artist=>({artistId:artist.id,hypedCount:state.hypedCounts[artist.id]||0}))
        .sort((a,b)=>a.artistId.localeCompare(b.artistId))};
      break;
    }
    case 'postArtistHyped':{
      const artist=state.artists.find(candidate=>candidate.id===params.artistId);
      if(!artist||artist.category!=='ARTIST'||scenario==='contest')failure(404,'NOT_FOUND','요청한 정보를 찾을 수 없습니다.');
      if(!DATES.includes(date))failure(409,'HYPED_CLOSED','축제일에만 기대돼요에 참여할 수 있습니다.');
      if(!body||Object.keys(body).length)failure(422,'VALIDATION_FAILED','요청 필드를 확인해 주세요.');
      const hypedCount=(state.hypedCounts[artist.id]||0)+1;
      state.hypedCounts[artist.id]=hypedCount;
      data={artistId:artist.id,hypedCount};
      break;
    }
    case 'getTimetable':data={dates:DATES,axis:{startTime:'17:00',endTime:'22:00'},items:empty?[]:structuredClone(state.performances).sort((a,b)=>a.startsAt.localeCompare(b.startsAt)||a.id.localeCompare(b.id))};break;
    case 'getPerformance':data=find(state.performances,params.performanceId);if(missing)data.description=null;break;
    case 'getProhibitedItems':data={items:empty?[]:['개발용 반입 금지 물품 예시'],message:empty?null:'총학생회 확정 자료를 사전 번역해 고정 표시합니다. 이 내용은 예시입니다.'};break;
    case 'getSpaces':data={items:empty?[]:structuredClone(state.spaces).filter(s=>!query.category||query.category==='ALL'||s.category===query.category).sort((a,b)=>a.name.localeCompare(b.name,'ko')||a.id.localeCompare(b.id))};break;
    case 'getSpace':data=find(state.spaces,params.spaceId);data.bankTransfer=MOCK_BANK_TRANSFERS[data.id]||null;if(missing)Object.assign(data,{operator:null,hoursText:null,description:null,contact:null,experience:null,events:[],menu:[],mapTarget:null,bankTransfer:null});break;
    case 'getMaps':data={items:empty?[]:structuredClone(state.maps),overviewId:empty?null:'map-overview'};break;
    case 'getMap':data=find(state.maps,params.mapId);break;
    case 'getPins':{
      const m=find(state.maps,params.mapId);
      if(query.mapVersion!==m.version)failure(409,'MAP_VERSION_MISMATCH','지도 이미지 버전이 다릅니다.');
      const items=empty?[]:structuredClone(state.pins[m.id]).map(pin=>({...pin,filterGroup:pinFilterGroup(pin)}));
      const groups=[...new Set(items.filter(pin=>pin.target.kind==='PLACE'&&pin.filterGroup).map(pin=>pin.filterGroup))]
        .sort((a,b)=>PIN_FILTER_GROUP_ORDER.indexOf(a)-PIN_FILTER_GROUP_ORDER.indexOf(b));
      data={mapId:m.id,mapVersion:m.version,filters:groups.map(id=>({id,label:pinFilterLabel(id,locale)})),items};
      break;
    }
    case 'getPlace':data=find(state.places,params.placeId);if(missing)Object.assign(data,{hoursText:null,description:null,usage:null});break;
    case 'getTicketGuide':{
      const unconfigured=scenario==='unconfigured';
      const ticketStatus=unconfigured?'UNCONFIGURED':date<DATES[0]?'BEFORE_FESTIVAL':date>DATES.at(-1)?'FESTIVAL_ENDED':isoKst(now).slice(11,16)>='21:00'?'DAILY_CLOSED':'TRANSFER_OPEN';
      const schedule=defaultDate(date),open=ticketStatus==='TRANSFER_OPEN';
      data={date,status:ticketStatus,unitPrice:unconfigured?null:money(1500),transferOpensAt:unconfigured?null:`${schedule}T00:00:00+09:00`,transferClosesAt:unconfigured?null:`${schedule}T21:00:00+09:00`,pickupOpensAt:unconfigured?null:`${schedule}T13:00:00+09:00`,pickupClosesAt:unconfigured?null:`${schedule}T21:00:00+09:00`,account:open?{bankName:'개발용 은행',accountNumber:'MOCK-NOT-PAYABLE',holder:'개발용 예금주'}:null,transferLink:null,paymentSettingsVersion:unconfigured?null:1,mapTarget:unconfigured?null:{mapId:'map-overview',placeId:'place-ticket',pinId:'pin-ticket',mapVersion:'mock-map-1'},instructions:['실제 가격·계좌·환불 정책이 아닌 개발용 예시입니다.','입금과 지급 여부는 현장에서 확인합니다.']};break;
    }
    case 'getStampGuide':data={title:'개발용 스탬프투어',dates:DATES,instructions:['축제일마다 START를 누르면 그날 참여가 시작됩니다.','부스마다 다른 QR을 찍어 부스당 하루 1개, 하루 4개까지 적립합니다.'],reward:{name:'몬스터',locationText:missing?null:'예시 수령 장소',hoursText:missing?null:'예시 수령 시간',notice:'하루 1회·당일 수령. 준비 수량 소진 시 현장에서 안내합니다.'},dailyLimit:4,timezone:'Asia/Seoul',qrValue:missing?null:'MOCK-COMMON-QR'};break;
    case 'startStampParticipation':
      // START is once per festival day: a card from an earlier day means not started today.
      if(scenario==='already-started'&&state.stampCard?.date!==date)mockStampCard(state,date,0);
      status=state.stampCard?.date===date?200:201;
      if(status===201)mockStampCard(state,date,0);
      data=stampCardData(state,date);break;
    case 'getStampCard':
      if(!state.stampCard&&scenario!=='not-started')mockStampCard(state,date,scenario==='empty'?0:2);
      if(scenario==='not-started'||state.stampCard?.date!==date)failure(404,'STAMP_NOT_STARTED','스탬프투어를 먼저 시작해 주세요.');
      data=stampCardData(state,date);break;
    case 'collectStamp':{
      if(!state.stampCard&&scenario!=='not-started')mockStampCard(state,date,0);
      if(scenario==='not-started'||state.stampCard?.date!==date)failure(404,'STAMP_NOT_STARTED','스탬프투어를 먼저 시작해 주세요.');
      const booth=MOCK_STAMP_BOOTHS.find(candidate=>candidate.token===body.token);
      if(scenario==='invalid-token'||!booth)failure(422,'INVALID_STAMP_TOKEN','스탬프투어 QR이 아니에요.');
      const card=stampCardData(state,date);
      if(scenario==='reward-claimed'||card.rewardClaimed)failure(409,'STAMP_REWARD_CLAIMED','오늘은 이미 상품을 받았어요.');
      if(scenario==='already-collected'||card.stamps.some(stamp=>stamp.boothId===booth.id))failure(409,'STAMP_ALREADY_COLLECTED','이 부스의 스탬프는 오늘 이미 받았어요.');
      if(scenario==='card-full'||card.stamps.length>=4)failure(409,'STAMP_CARD_FULL','오늘 받을 수 있는 스탬프를 모두 모았어요.');
      state.stampCard.stamps.push({boothId:booth.id,boothName:booth.name,collectedAt:isoKst(now)});
      data=stampCardData(state,date);break;
    }
    case 'verifyStampReceipt':{
      if(scenario==='invalid-code'||typeof body.code!=='string'||body.code!==MOCK_STAMP_RECEIPT_CODE)failure(422,'INVALID_RECEIPT_CODE','수령 인증 코드를 확인해 주세요.');
      if(!state.stampCard&&scenario==='normal')mockStampCard(state,date,4);
      const card=state.stampCard?stampCardData(state,date):null;
      if(scenario==='reward-claimed'||card?.rewardClaimed)failure(409,'STAMP_REWARD_CLAIMED','오늘은 이미 상품을 받았어요.');
      if(scenario==='card-incomplete'||!card||card.stamps.length<4)failure(409,'STAMP_CARD_INCOMPLETE','스탬프 4개를 모두 모아야 상품을 받을 수 있어요.');
      state.stampCard.rewardClaimed=true;
      data={verified:true};break;
    }
    case 'getAdminNotices':data={items:empty?[]:structuredClone(state.notices).filter(n=>!state.deleted.has(n.id)).sort((a,b)=>Date.parse(b.updatedAt)-Date.parse(a.updatedAt)||a.id.localeCompare(b.id))};break;
    case 'getAdminNotice':if(state.deleted.has(params.noticeId))failure(404,'NOT_FOUND','삭제된 공지입니다.');data=find(state.notices,params.noticeId);if(missing)Object.assign(data,{templateId:null,links:[]});break;
    case 'postAdminNotice':case 'putAdminNotice':{
      validateNotice(body,failure);
      if(body.templateId)find(state.templates,body.templateId);
      let old;
      if(op.method==='PUT'){if(state.deleted.has(params.noticeId))failure(404,'NOT_FOUND','삭제된 공지입니다.');old=find(state.notices,params.noticeId);}
      const time=mutate();data={...structuredClone(body),id:old?.id||`notice-created-${state.nextId++}`,createdAt:old?.createdAt||time,updatedAt:time};
      if(old)state.notices[state.notices.findIndex(n=>n.id===old.id)]=data;else {state.notices.push(data);status=201;}break;
    }
    case 'deleteAdminNotice':if(state.deleted.has(params.noticeId))failure(409,'ALREADY_DELETED','이미 삭제된 공지입니다.');find(state.notices,params.noticeId);state.deleted.add(params.noticeId);mutate();data={id:params.noticeId,deleted:true};break;
    case 'getTemplates':data={items:empty?[]:structuredClone(state.templates)};break;
    case 'getTemplate':data=find(state.templates,params.templateId);if(missing)delete data.translations.en;break;
    default:failure(404,'NOT_FOUND','경로가 없습니다.');
  }
  if(op.operationId==='getConfig')data.languages=state.languages.map(code=>({code,label:{ko:'한국어',en:'English','zh-Hans':'中文',ja:'日本語'}[code]}));
  return {status,data:op.admin?data:localize(data,locale),now,locale};
}
