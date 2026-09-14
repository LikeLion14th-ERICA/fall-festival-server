export function initializeAdmin(state){
  state.operatingHours={};state.languages=['ko','en'];state.inventory={};
  for(const g of state.goods){
    g.images=[g.image];g.colors=g.colorImages.map(c=>({id:c.colorId,name:c.colorName,images:[c.image]}));
    state.inventory[g.id]={updatedAt:null,quantities:Object.fromEntries(g.colors.flatMap((c,ci)=>g.sizes.map((z,zi)=>[`${c.id}/${z.id}`,ci===0&&zi===0?5:0])))};
  }
  delete state.availability;
  for(const n of state.notices){delete n.image;n.translations.en??={title:'Mock English notice',body:'Mock only',status:'READY'};n.translations.en.status='READY';n.translationSource={title:n.translations.ko.title,body:n.translations.ko.body};}
  return state;
}
export function hoursFor(state,operatingDay){return {operatingDay,...(state.operatingHours[operatingDay]||{opensAt:'13:00',closesAt:'22:00',isDefault:true,updatedAt:null})};}
export function inventoryFor(state,goodsId,{admin=false,sold=false,failure}={}){
  const g=state.goods.find(g=>g.id===goodsId);if(!g)failure(404,'NOT_FOUND','상품이 없습니다.');
  const stock=state.inventory[goodsId];
  const variants=g.colors.flatMap(c=>g.sizes.map(z=>{const quantity=sold?0:stock.quantities[`${c.id}/${z.id}`];return {colorId:c.id,colorName:c.name,sizeId:z.id,sizeLabel:z.label,status:quantity>0?'ON_SALE':'SOLD_OUT',...(admin?{quantity}:{})};}));
  return {goodsId:g.id,name:g.name,variants,allSoldOut:variants.length>0&&variants.every(v=>v.status==='SOLD_OUT'),updatedAt:stock.updatedAt};
}
export function adminExecute(op,state,ctx){
  const {params,body,scenario,mutate,failure,DATES}=ctx;
  const inv=id=>inventoryFor(state,id,{admin:true,failure});
  switch(op.operationId){
    case 'getOperatingHours':return {data:{items:scenario==='empty'?[]:DATES.map(d=>hoursFor(state,d))}};
    case 'putOperatingHours':{
      if(!DATES.includes(params.operatingDay))failure(404,'NOT_FOUND','등록된 운영일이 아닙니다.');
      if(body.closesAt<=body.opensAt||scenario==='invalid-range')failure(422,'INVALID_TIME_RANGE','종료 시각은 시작 시각보다 이후여야 합니다.');
      state.operatingHours[params.operatingDay]={...body,isDefault:false,updatedAt:mutate()};return {data:hoursFor(state,params.operatingDay)};
    }
    case 'putInventory':{
      const a=inv(params.goodsId);if(!a.variants.some(v=>v.colorId===params.colorId&&v.sizeId===params.sizeId))failure(404,'NOT_FOUND','등록된 색상·사이즈 조합이 없습니다.');
      const stock=state.inventory[params.goodsId];stock.quantities[`${params.colorId}/${params.sizeId}`]=body.quantity;stock.updatedAt=mutate();return {data:inv(params.goodsId)};
    }
    case 'getAdminProducts':return {data:{items:scenario==='empty'?[]:structuredClone(state.goods)}};
    case 'getAdminProduct':{
      const g=state.goods.find(g=>g.id===params.goodsId);if(!g)failure(404,'NOT_FOUND','상품이 없습니다.');return {data:{...structuredClone(g),...(scenario==='missing-optional'?{description:null}:{})}};
    }
    case 'postAdminProduct':case 'putAdminProduct':{
      const old=op.method==='PUT'?state.goods.find(g=>g.id===params.goodsId):null;
      if(op.method==='PUT'&&!old)failure(404,'NOT_FOUND','상품이 없습니다.');
      if(!body.name.trim())failure(422,'VALIDATION_FAILED','상품명을 입력해 주세요.');
      for(const key of ['colors','sizes']){
        if(new Set(body[key].map(x=>x.id)).size!==body[key].length)failure(422,'DUPLICATE_OPTION','옵션 ID가 중복됩니다.');
        if(old&&old[key].some(x=>!body[key].some(y=>y.id===x.id)))failure(409,'OPTION_DELETION_NOT_SUPPORTED','등록된 색상·사이즈 삭제는 제공하지 않습니다.');
      }
      const goodsId=old?.id||`goods-created-${state.nextId++}`;
      const stock=structuredClone(state.inventory[goodsId]||{updatedAt:null,quantities:{}});
      for(const c of body.colors)for(const z of body.sizes)stock.quantities[`${c.id}/${z.id}`]??=0;
      const g={...structuredClone(body),id:goodsId,image:body.images[0]||null,colorImages:body.colors.flatMap(c=>c.images.map(image=>({colorId:c.id,colorName:c.name,image})))};
      mutate();state.inventory[goodsId]=stock;if(old)state.goods[state.goods.indexOf(old)]=g;else state.goods.push(g);
      return {data:g,status:old?200:201};
    }
    case 'previewNoticeTranslation':{
      if(!body.title.trim()||!body.body.trim())failure(422,'KOREAN_REQUIRED','한국어 제목과 본문이 필요합니다.');
      const translations={ko:{...body,status:'READY'}};
      for(const locale of state.languages.filter(l=>l!=='ko')){
        const failed=locale==='en'?scenario==='english-failed':scenario==='partial-translation';
        translations[locale]=failed?{title:null,body:null,status:'PENDING'}:{title:`[MOCK ${locale}] ${body.title}`,body:`[MOCK ${locale}] ${body.body}`,status:'READY'};
      }
      return {data:{source:structuredClone(body),translations,canSave:translations.en?.status==='READY'}};
    }
  }
  return null;
}
export function validateNotice(body,state,{scenario,failure}){
  const ready=t=>t?.status==='READY'&&typeof t.title==='string'&&t.title.trim()&&typeof t.body==='string'&&t.body.trim();
  if(!ready(body.translations.ko))failure(422,'KOREAN_REQUIRED','한국어 제목·본문은 필수입니다.');
  if(!ready(body.translations.en)||scenario==='english-incomplete')failure(422,'ENGLISH_TRANSLATION_REQUIRED','영어 번역 완료 후 저장해 주세요.');
  if(body.translationSource.title!==body.translations.ko.title||body.translationSource.body!==body.translations.ko.body||scenario==='stale-translation')failure(422,'STALE_TRANSLATION_SOURCE','변경된 한국어 원문으로 번역을 갱신하거나 직접 검토해 주세요.');
  for(const [locale,t]of Object.entries(body.translations)){
    if(!state.languages.includes(locale))failure(422,'LANGUAGE_NOT_ACTIVE','실제 제공 중인 언어만 저장할 수 있습니다.');
    if(t.status==='READY'&&!ready(t))failure(422,'TRANSLATION_CONTENT_REQUIRED','완료 번역은 제목과 본문이 필요합니다.');
  }
  for(const locale of state.languages)if(!body.translations[locale])body.translations[locale]={title:null,body:null,status:'PENDING'};
}
