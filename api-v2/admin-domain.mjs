export function initializeAdmin(state){
  state.languages=['ko'];state.inventory={};
  state.idempotency??={};
  for(const g of state.goods){
    state.inventory[g.id]={
      updatedAt:null,
      statuses:Object.fromEntries(g.combinations.map((combo,i)=>[combo.id,i===0?'ON_SALE':'SOLD_OUT'])),
    };
  }
  return state;
}
export function hoursFor(state,operatingDay){
  const day=state.festivalDays?.find(item=>item.operatingDay===operatingDay);
  if(!day)throw new Error(`Missing mock FestivalDay for ${operatingDay}`);
  return day;
}
export function inventoryFor(state,goodsId,{admin=false,sold=false,locale='ko',failure}={}){
  const g=state.goods.find(g=>g.id===goodsId);if(!g)failure(404,'NOT_FOUND','상품이 없습니다.');
  const stock=state.inventory[goodsId];
  const combinations=g.combinations.map(combo=>({
    combinationId:combo.id,
    colorId:combo.colorId,
    sizeId:combo.sizeId,
    status:sold?'SOLD_OUT':stock.statuses[combo.id],
  }));
  return {goodsId:g.id,name:g.translations[admin?'ko':locale].name,combinations,allSoldOut:combinations.length>0&&combinations.every(v=>v.status==='SOLD_OUT'),updatedAt:stock.updatedAt};
}
function toAdminGoods(state,g){
  const stock=state.inventory[g.id];
  return {
    id:g.id,
    optionMode:g.optionMode,
    translations:g.translations,
    price:g.price,
    images:g.images,
    colors:g.colors,
    sizes:g.sizes,
    combinations:g.combinations.map(c=>({combinationId:c.id,colorId:c.colorId,sizeId:c.sizeId,status:stock.statuses[c.id]})),
    createdAt:g.createdAt,
    updatedAt:g.updatedAt,
  };
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
    case 'postAdminGoodsImage':return {data:{mediaId:'00000000-0000-4000-8000-000000000050'},status:201};
    case 'putAdminAvailability':{
      const g=state.goods.find(g=>g.id===params.goodsId);if(!g)failure(404,'NOT_FOUND','상품이 없습니다.');
      if(!g.combinations.some(c=>c.id===params.combinationId))failure(404,'NOT_FOUND','등록된 조합이 없습니다.');
      const stock=state.inventory[params.goodsId];stock.statuses[params.combinationId]=body.status;stock.updatedAt=mutate();return {data:inv(params.goodsId)};
    }
    case 'getAdminProducts':return {data:{items:scenario==='empty'?[]:state.goods.map(g=>toAdminGoods(state,g))}};
    case 'getAdminProduct':{
      const g=state.goods.find(g=>g.id===params.goodsId);if(!g)failure(404,'NOT_FOUND','상품이 없습니다.');
      return {data:toAdminGoods(state,g)};
    }
    case 'postAdminProduct':case 'putAdminProduct':{
      const old=op.method==='PUT'?state.goods.find(g=>g.id===params.goodsId):null;
      if(op.method==='PUT'&&!old)failure(404,'NOT_FOUND','상품이 없습니다.');
      validateProduct(body,failure);
      const combinations=body.optionMode==='SINGLE'
        ? [{id:old?.combinations.find(c=>c.colorId===null)?.id||`combo-${state.nextId++}`,colorId:null,sizeId:null}]
        : body.options.map(o=>({
            id:old?.combinations.find(c=>c.colorId===o.colorId&&c.sizeId===o.sizeId)?.id||`combo-${state.nextId++}`,
            colorId:o.colorId,sizeId:o.sizeId,
          }));
      const stock=structuredClone(state.inventory[old?.id]||{updatedAt:null,statuses:{}});
      const keptIds=new Set(combinations.map(c=>c.id));
      const removed=Object.keys(stock.statuses).filter(key=>!keptIds.has(key));
      for(const key of removed)delete stock.statuses[key];
      const added=combinations.filter(c=>!Object.hasOwn(stock.statuses,c.id));
      for(const combo of added)stock.statuses[combo.id]='ON_SALE';
      const goodsId=old?.id||`goods-created-${state.nextId++}`;
      const changedAt=mutate();if(added.length||removed.length)stock.updatedAt=changedAt;
      const g={
        id:goodsId,
        optionMode:body.optionMode,
        translations:structuredClone(body.translations),
        price:structuredClone(body.price),
        images:body.images.map(image=>({
          mediaId:image.mediaId,
          alt:structuredClone(image.alt),
          masterUrl:`/api/v2/media/goods-images/${image.mediaId}/master`,
          thumbnail320Url:`/api/v2/media/goods-images/${image.mediaId}/320`,
          thumbnail640Url:`/api/v2/media/goods-images/${image.mediaId}/640`,
        })),
        colors:structuredClone(body.colors),
        sizes:structuredClone(body.sizes),
        combinations,
        createdAt:old?.createdAt||changedAt,
        updatedAt:changedAt,
      };
      state.inventory[goodsId]=stock;if(old)state.goods[state.goods.indexOf(old)]=g;else state.goods.push(g);
      return {data:toAdminGoods(state,g),status:old?200:201};
    }
    case 'deleteAdminProduct':{
      const index=state.goods.findIndex(g=>g.id===params.goodsId);
      if(index===-1)failure(404,'NOT_FOUND','상품이 없습니다.');
      const [removed]=state.goods.splice(index,1);
      delete state.inventory[removed.id];
      mutate();
      return {data:{id:removed.id,deleted:true}};
    }
  }
  return null;
}
// SINGLE products carry no colors/sizes/options at all. OPTIONS products must
// register at least one color, size and option, every option must reference a
// registered color/size, and every registered color/size must be used by at
// least one option (no unused registrations, no auto cross-product).
export function validateProduct(body,failure){
  const filledName=t=>typeof t?.name==='string'&&t.name.trim();
  const filledDescription=t=>t.description===null||(typeof t.description==='string'&&t.description.trim());
  if(!filledName(body.translations?.ko)||!filledDescription(body.translations.ko))failure(422,'KOREAN_REQUIRED','한국어 상품명은 필수입니다.');
  if(!filledName(body.translations?.en)||!filledDescription(body.translations.en))failure(422,'ENGLISH_REQUIRED','영어 상품명은 필수입니다.');
  for(const locale of ['zh-Hans','ja']){
    const t=body.translations?.[locale];
    if(t!=null&&(!filledName(t)||!filledDescription(t)))failure(422,'TRANSLATION_CONTENT_REQUIRED','입력한 번역은 상품명이 필요합니다.');
  }
  const descriptionsPresent=body.translations.ko.description!==null;
  for(const locale of ['en','zh-Hans','ja']){
    const t=body.translations[locale];
    if(t!=null&&descriptionsPresent!==(t.description!==null))failure(422,'TRANSLATION_CONTENT_REQUIRED','상품 소개는 모든 상품 번역에 함께 입력하거나 모두 비워 주세요.');
  }
  if(!Array.isArray(body.images)||body.images.length<1||body.images.length>2)failure(422,'VALIDATION_FAILED','상품 이미지는 1~2개가 필요합니다.');
  const mediaIds=new Set();
  for(const image of body.images){
    if(mediaIds.has(image.mediaId))failure(422,'DUPLICATE_MEDIA','상품 이미지가 중복됩니다.');
    mediaIds.add(image.mediaId);
    if(typeof image.alt?.ko!=='string'||!image.alt.ko.trim())failure(422,'KOREAN_REQUIRED','상품 이미지의 한국어 대체 텍스트는 필수입니다.');
    if(typeof image.alt?.en!=='string'||!image.alt.en.trim())failure(422,'ENGLISH_REQUIRED','상품 이미지의 영어 대체 텍스트는 필수입니다.');
    for(const locale of ['zh-Hans','ja']){
      const productHasTranslation=body.translations?.[locale]!=null;
      const alt=image.alt?.[locale];
      const imageHasAlt=typeof alt==='string'&&Boolean(alt.trim());
      if(alt!=null&&!imageHasAlt)failure(422,'VALIDATION_FAILED','상품 이미지 대체 텍스트는 공백일 수 없습니다.');
      if(productHasTranslation!==imageHasAlt)failure(422,'TRANSLATION_CONTENT_REQUIRED','상품 번역과 이미지 대체 텍스트의 언어를 일치시켜 주세요.');
    }
  }
  if(body.optionMode==='SINGLE'){
    if(body.colors.length||body.sizes.length||body.options.length)failure(422,'VALIDATION_FAILED','SINGLE 상품은 색상·사이즈·조합을 등록할 수 없습니다.');
    return;
  }
  if(!body.colors.length||!body.sizes.length||!body.options.length)failure(422,'EMPTY_CONFIGURATION','OPTIONS 상품은 색상·사이즈·조합이 모두 필요합니다.');
  const filledField=(t,field)=>typeof t?.[field]==='string'&&t[field].trim();
  for(const [key,field] of [['colors','name'],['sizes','label']]){
    if(new Set(body[key].map(x=>x.id)).size!==body[key].length)failure(422,'DUPLICATE_OPTION','색상·사이즈 ID가 중복됩니다.');
    for(const entry of body[key]){
      const t=entry.translations;
      if(!filledField(t?.ko,field))failure(422,'KOREAN_REQUIRED','색상·사이즈의 한국어 이름은 필수입니다.');
      if(!filledField(t?.en,field))failure(422,'ENGLISH_REQUIRED','색상·사이즈의 영어 이름은 필수입니다.');
      for(const locale of ['zh-Hans','ja']){
        const productHasTranslation=body.translations[locale]!=null;
        const optionHasTranslation=filledField(t?.[locale],field);
        if(productHasTranslation!==Boolean(optionHasTranslation))failure(422,'TRANSLATION_CONTENT_REQUIRED','상품 번역과 색상·사이즈 번역의 언어를 일치시켜 주세요.');
      }
    }
  }
  const keys=body.options.map(v=>`${v.colorId}/${v.sizeId}`);
  if(new Set(keys).size!==keys.length)failure(422,'DUPLICATE_OPTION','판매 조합이 중복됩니다.');
  if(body.options.some(v=>!body.colors.some(c=>c.id===v.colorId)||!body.sizes.some(z=>z.id===v.sizeId)))failure(422,'INVALID_OPTION','등록된 색상·사이즈만 조합할 수 있습니다.');
  const usedColors=new Set(body.options.map(o=>o.colorId));
  const usedSizes=new Set(body.options.map(o=>o.sizeId));
  if(body.colors.some(c=>!usedColors.has(c.id))||body.sizes.some(s=>!usedSizes.has(s.id)))failure(422,'UNUSED_OPTION','등록한 모든 색상·사이즈는 조합에서 사용돼야 합니다.');
}
// JSON schema's minLength:1 rejects empty strings but not whitespace-only ones,
// so title/body blankness still needs an explicit check here. Link labels are a
// cross-object invariant schema can't express at all: they must exist exactly
// for the locales that have a body translation.
export function validateNotice(body,failure){
  const filled=t=>typeof t?.title==='string'&&t.title.trim()&&typeof t?.body==='string'&&t.body.trim();
  const hasHttpsHost=value=>{
    if(typeof value!=='string'||!/^https:\/\/[^/?#\s]+/.test(value))return false;
    try{const url=new URL(value);return url.protocol==='https:'&&Boolean(url.hostname);}catch{return false;}
  };
  if(!filled(body.translations.ko))failure(422,'KOREAN_REQUIRED','한국어 제목·본문은 필수입니다.');
  if(!filled(body.translations.en))failure(422,'ENGLISH_REQUIRED','영어 제목·본문은 필수입니다.');
  const present=new Set(Object.keys(body.translations));
  for(const locale of ['zh-Hans','ja']){
    if(present.has(locale)&&!filled(body.translations[locale]))failure(422,'TRANSLATION_CONTENT_REQUIRED','입력한 번역은 제목과 본문이 필요합니다.');
  }
  for(const link of body.links){
    if(!hasHttpsHost(link.url))failure(422,'VALIDATION_FAILED','요청 필드를 확인해 주세요.');
    for(const locale of ['zh-Hans','ja']){
      const hasBody=present.has(locale);
      const label=link.labels[locale];
      if(hasBody&&(typeof label!=='string'||!label.trim()))failure(422,'LINK_LABEL_REQUIRED','본문이 있는 언어의 링크 label이 필요합니다.');
      if(!hasBody&&label!=null)failure(422,'LINK_LABEL_UNEXPECTED','본문이 없는 언어의 링크 label은 null이어야 합니다.');
    }
  }
}
