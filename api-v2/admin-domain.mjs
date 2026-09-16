const NOTICE_TRANSLATION_LOCALES=['ko','en','zh-Hans','ja'];

export function initializeAdmin(state){
  state.languages=['ko'];state.translationLocales=NOTICE_TRANSLATION_LOCALES;state.inventory={};
  for(const g of state.goods){
    g.images=[g.image];g.colors=g.colorImages.map(c=>({id:c.colorId,name:c.colorName,images:[c.image]}));
    g.options=[{colorId:'color-a',sizeId:'size-m'},{colorId:'color-a',sizeId:'size-l'},{colorId:'color-b',sizeId:'size-m'}];
    state.inventory[g.id]={updatedAt:null,statuses:Object.fromEntries(g.options.map((v,i)=>[`${v.colorId}/${v.sizeId}`,i===0?'ON_SALE':'SOLD_OUT']))};
  }
  for(const n of state.notices)delete n.image;
  return state;
}
export function hoursFor(state,operatingDay){return {operatingDay,opensAt:'13:00',closesAt:'22:00'};}
export function inventoryFor(state,goodsId,{admin=false,sold=false,failure}={}){
  const g=state.goods.find(g=>g.id===goodsId);if(!g)failure(404,'NOT_FOUND','상품이 없습니다.');
  const stock=state.inventory[goodsId];
  const variants=g.options.map(v=>({...v,colorName:g.colors.find(c=>c.id===v.colorId).name,sizeLabel:g.sizes.find(z=>z.id===v.sizeId).label,status:sold?'SOLD_OUT':stock.statuses[`${v.colorId}/${v.sizeId}`]}));
  return {goodsId:g.id,name:g.name,variants,allSoldOut:variants.length>0&&variants.every(v=>v.status==='SOLD_OUT'),updatedAt:stock.updatedAt};
}
export function adminExecute(op,state,ctx){
  const {params,body,scenario,mutate,failure,DATES}=ctx;
  if(op.operationId==='createAdminSession'&&['invalid-credentials','disabled'].includes(scenario))failure(401,'ADMIN_AUTHENTICATION_FAILED','관리자 인증에 실패했습니다.');
  if(op.operationId==='refreshAdminSession'&&['expired','revoked','unknown','disabled'].includes(scenario))failure(401,'ADMIN_REFRESH_TOKEN_INVALID','관리자 세션을 갱신할 수 없습니다.');
  if(op.operationId==='getCurrentAdmin'&&scenario==='disabled')failure(401,'UNAUTHORIZED','관리자 인증이 필요합니다.');
  const inv=id=>inventoryFor(state,id,{admin:true,failure});
  switch(op.operationId){
    case 'createAdminSession':case 'refreshAdminSession':return {data:{accessToken:'MOCK-SIGNED-ACCESS-TOKEN',expiresAt:'2030-10-01T18:15:00+09:00',admin:{id:'00000000-0000-4000-8000-000000000001',username:'mock-admin',authority:'ADMIN',enabled:true}}};
    case 'deleteCurrentAdminSession':return {data:{loggedOut:true}};
    case 'getCurrentAdmin':return {data:{id:'00000000-0000-4000-8000-000000000001',username:'mock-admin',authority:'ADMIN',enabled:true}};
    case 'putAdminAvailability':{
      const a=inv(params.goodsId);if(!a.variants.some(v=>v.colorId===params.colorId&&v.sizeId===params.sizeId))failure(404,'NOT_FOUND','등록된 색상·사이즈 조합이 없습니다.');
      const stock=state.inventory[params.goodsId];stock.statuses[`${params.colorId}/${params.sizeId}`]=body.status;stock.updatedAt=mutate();return {data:inv(params.goodsId)};
    }
    case 'getAdminProducts':return {data:{items:scenario==='empty'?[]:structuredClone(state.goods)}};
    case 'getAdminProduct':{
      const g=state.goods.find(g=>g.id===params.goodsId);if(!g)failure(404,'NOT_FOUND','상품이 없습니다.');return {data:{...structuredClone(g),...(scenario==='missing-optional'?{description:null}:{})}};
    }
    case 'postAdminProduct':case 'putAdminProduct':{
      const old=op.method==='PUT'?state.goods.find(g=>g.id===params.goodsId):null;
      if(op.method==='PUT'&&!old)failure(404,'NOT_FOUND','상품이 없습니다.');
      if(!body.name.trim())failure(422,'VALIDATION_FAILED','상품명을 입력해 주세요.');
      if(!body.images.length)failure(409,'IMAGE_CONFIGURATION_UNRESOLVED','상품 이미지 입력 구성은 합의 대기입니다.');
      for(const key of ['colors','sizes']){
        if(new Set(body[key].map(x=>x.id)).size!==body[key].length)failure(422,'DUPLICATE_OPTION','옵션 ID가 중복됩니다.');
      }
      const stock=structuredClone(state.inventory[old?.id]||{updatedAt:null,statuses:{}});
      const keys=body.options.map(v=>`${v.colorId}/${v.sizeId}`);
      if(new Set(keys).size!==keys.length)failure(422,'DUPLICATE_OPTION','판매 조합이 중복됩니다.');
      if(body.options.some(v=>!body.colors.some(c=>c.id===v.colorId)||!body.sizes.some(z=>z.id===v.sizeId)))failure(422,'INVALID_OPTION','등록된 색상·사이즈만 조합할 수 있습니다.');
      const removed=Object.keys(stock.statuses).filter(key=>!keys.includes(key));
      for(const key of removed)delete stock.statuses[key];
      const added=keys.filter(k=>!Object.hasOwn(stock.statuses,k));
      for(const key of added)stock.statuses[key]='ON_SALE';
      const goodsId=old?.id||`goods-created-${state.nextId++}`;
      const g={...structuredClone(body),id:goodsId,image:body.images[0]||null,colorImages:body.colors.flatMap(c=>c.images.map(image=>({colorId:c.id,colorName:c.name,image})))};
      const changedAt=mutate();if(added.length||removed.length)stock.updatedAt=changedAt;
      state.inventory[goodsId]=stock;if(old)state.goods[state.goods.indexOf(old)]=g;else state.goods.push(g);
      return {data:g,status:old?200:201};
    }
    case 'previewNoticeTranslation':{
      if(!body.title.trim()||!body.body.trim())failure(422,'KOREAN_REQUIRED','한국어 제목과 본문이 필요합니다.');
      const translations={ko:{...body,status:'READY'}};
      for(const locale of state.translationLocales.filter(l=>l!=='ko')){
        const failed=locale==='en'?scenario==='english-failed':scenario==='partial-translation';
        translations[locale]=failed?{title:null,body:null,status:'FAILED'}:{title:`[MOCK ${locale}] ${body.title}`,body:`[MOCK ${locale}] ${body.body}`,status:'READY'};
      }
      return {data:{source:structuredClone(body),translations,canSave:true}};
    }
  }
  return null;
}
export function validateNotice(body,state,{scenario,failure}){
  const ready=t=>t?.status==='READY'&&typeof t.title==='string'&&t.title.trim()&&typeof t.body==='string'&&t.body.trim();
  if(!ready(body.translations.ko))failure(422,'KOREAN_REQUIRED','한국어 제목·본문은 필수입니다.');


  for(const [locale,t]of Object.entries(body.translations)){
    // Inactive translations may be prepared, but public locale selection remains gated.
    if(t.status==='READY'&&!ready(t))failure(422,'TRANSLATION_CONTENT_REQUIRED','완료 번역은 제목과 본문이 필요합니다.');
  }
  for(const locale of state.translationLocales)if(!body.translations[locale])body.translations[locale]={title:null,body:null,status:'PENDING'};
}
