// Authoritative product rules: docs/wiki/product/admin/ (v5).
export function applyAdminContract(s,ops){
  const ref=n=>({$ref:`#/components/schemas/${n}`});
  const str=d=>({type:'string',minLength:1,description:d});
  const arr=(items,d)=>({type:'array',items,description:d});
  const obj=(properties,d='',required=Object.keys(properties))=>({type:'object',properties,required,additionalProperties:false,description:d});
  const nullable=(schema,d)=>({anyOf:[schema,{type:'null'}],description:d});
  const en=(values,d)=>({type:'string',enum:values,description:d});
  const id=ref('Id');
  s.AvailabilityInput=obj({status:en(['ON_SALE','SOLD_OUT'],'구매 가능 / 품절 직접 저장')});
  s.GoodsColorTranslation=obj({name:str('색상명')});
  s.GoodsColorTranslations=obj({ko:ref('GoodsColorTranslation'),en:ref('GoodsColorTranslation'),'zh-Hans':nullable(ref('GoodsColorTranslation'),'이 색상에 zh-Hans 번역이 없으면 null'),ja:nullable(ref('GoodsColorTranslation'),'이 색상에 ja 번역이 없으면 null')},'상품과 같은 ko·en 필수 규칙.',['ko','en','zh-Hans','ja']);
  s.GoodsSizeTranslation=obj({label:str('사이즈명')});
  s.GoodsSizeTranslations=obj({ko:ref('GoodsSizeTranslation'),en:ref('GoodsSizeTranslation'),'zh-Hans':nullable(ref('GoodsSizeTranslation'),'이 사이즈에 zh-Hans 번역이 없으면 null'),ja:nullable(ref('GoodsSizeTranslation'),'이 사이즈에 ja 번역이 없으면 null')},'상품과 같은 ko·en 필수 규칙.',['ko','en','zh-Hans','ja']);
  s.GoodsColorInput=obj({id,translations:ref('GoodsColorTranslations')},'id는 번역문에서 파생하지 않는 클라이언트 생성 안정 UUID.');
  s.GoodsSizeInput=obj({id,translations:ref('GoodsSizeTranslations')},'id는 번역문에서 파생하지 않는 클라이언트 생성 안정 UUID.');
  s.GoodsOptionInput=obj({colorId:id,sizeId:id},'실제 제공하는 조합만 명시. 자동 곱집합 생성 없음.');
  s.ProductInput=obj({
    optionMode:en(['SINGLE','OPTIONS'],'단일 판매 상태 / 색상×사이즈 조합별 판매 상태'),
    translations:ref('GoodsTranslations'),
    price:ref('Money'),
    colors:arr(ref('GoodsColorInput'),'SINGLE이면 []'),
    sizes:arr(ref('GoodsSizeInput'),'SINGLE이면 []'),
    options:arr(ref('GoodsOptionInput'),'SINGLE이면 []. OPTIONS면 비어있지 않아야 하며 모든 색상·사이즈가 실제 조합에서 쓰여야 함.'),
  },'SINGLE은 colors/sizes/options가 모두 빈 배열이어야 한다. OPTIONS는 셋 다 비어있지 않아야 하며 등록한 모든 색상·사이즈가 실제 조합에서 쓰여야 한다. 유지한 조합의 판매 상태는 보존하고 신규 조합은 ON_SALE로 생성하며 삭제된 조합의 상태는 함께 제거한다.');
  s.AdminGoodsColor=obj({id,translations:ref('GoodsColorTranslations')});
  s.AdminGoodsSize=obj({id,translations:ref('GoodsSizeTranslations')});
  s.AdminGoodsCombination=obj({combinationId:id,colorId:nullable(id,'SINGLE 조합이면 null'),sizeId:nullable(id,'SINGLE 조합이면 null'),status:en(['ON_SALE','SOLD_OUT'],'관리자가 구매 가능 / 품절 직접 선택. 수량 계산 없음.')});
  s.AdminGoods=obj({
    id,
    optionMode:en(['SINGLE','OPTIONS'],'단일 판매 상태 / 색상×사이즈 조합별 판매 상태'),
    translations:ref('GoodsTranslations'),
    price:ref('Money'),
    colors:arr(ref('AdminGoodsColor'),'SINGLE이면 []'),
    sizes:arr(ref('AdminGoodsSize'),'SINGLE이면 []'),
    combinations:arr(ref('AdminGoodsCombination'),'SINGLE이면 조합 1개, OPTIONS면 실제 제공 조합 전체'),
    createdAt:ref('Timestamp'),
    updatedAt:ref('Timestamp'),
  });
  s.AdminGoodsList=obj({items:arr(ref('AdminGoods'),'삭제 제외 전체 상품. updatedAt 내림차순·id 오름차순.')});
  s.AdminGoodsImageUpload=obj({mediaId:{type:'string',format:'uuid',description:'저장경로를 노출하지 않는 opaque media UUID.'}},'상품과 아직 연결되지 않은 관리자 이미지 업로드 결과.');
  s.AdminIdentity=obj({id,username:{type:'string',minLength:1,maxLength:100,description:'관리자 로그인 식별자'},authority:en(['ADMIN'],'현재 Product 범위의 단일 관리자 권한'),enabled:{type:'boolean',description:'false이면 로그인·refresh·관리자 API 인증 거부'}});
  s.AdminSession=obj({accessToken:{type:'string',minLength:1,description:'15분 유효한 signed JWT. Authorization Bearer로 전달'},expiresAt:ref('Timestamp'),admin:ref('AdminIdentity')});
  s.AdminLoginInput=obj({username:{type:'string',minLength:1,maxLength:100},password:{type:'string',minLength:1,maxLength:200,writeOnly:true}},'공개 회원가입 없이 환경 bootstrap으로 만든 관리자 계정으로 로그인');
  s.AdminLogout=obj({loggedOut:{type:'boolean',enum:[true],description:'현재 refresh session revoke 및 cookie 만료 완료'}});
  s.NoticeInput.description='한국어·영어 제목·본문 필수 수동 입력. 자동 번역 없음. 중국어 간체·일본어는 준비된 경우만 포함. 링크는 본문이 있는 언어마다 label 필수, 없는 언어는 null.';
  const find=id=>ops.find(o=>o.operationId===id);
  find('getCrowding').scenarios=find('getCrowding').scenarios.filter(x=>x!=='overnight');
  find('getCrowding').screens=['HOME'];find('getCrowding').summary='홈 재학생존 혼잡도';
  for(const operationId of ['getCrowding','getAdminCrowding'])find(operationId).conditional=true;
  const crowdingPut=find('putAdminCrowding');
  crowdingPut.successStatus=204;
  crowdingPut.ifMatchRequired=true;
  crowdingPut.idempotencyKeyRequired=true;
  crowdingPut.scenarios.push('precondition-required','not-festival-day','edit-conflict');
  find('getConfig').scenarios.push('all-languages');
  find('getNotices').conditional=true;
  find('getNotices').cacheControl='private, no-cache, must-revalidate';
  find('getAdminNotice').conditional=true;
  find('getGoodsAvailability').conditional=true;
  find('getGoodsAvailability').cacheControl='private, no-cache, must-revalidate';
  const noticePost=find('postAdminNotice');
  noticePost.idempotencyKeyRequired=true;
  const noticePut=find('putAdminNotice');
  noticePut.ifMatchRequired=true;
  noticePut.idempotencyKeyRequired=true;
  noticePut.scenarios.push('precondition-required','edit-conflict');
  const noticeDelete=find('deleteAdminNotice');
  noticeDelete.ifMatchRequired=true;
  noticeDelete.idempotencyKeyRequired=true;
  noticeDelete.scenarios.push('precondition-required','edit-conflict');
  find('getAdminGoods').summary='관리자 실제 제공 조합별 판매 상태';
  const old=ops.findIndex(o=>o.operationId==='putAdminAvailability');ops.splice(old,1);
  for(const id of ['postAdminNotice','putAdminNotice']){find(id).provisional=false;find(id).summary=find(id).summary.replace('(검토 필요)','');find(id).scenarios.push('validation-failed');}
  function add(operationId,method,path,schema,summary,screens,input,scenarios=['normal','error'],provisional=false){
    ops.push({operationId,method,path:'/api/v2'+path,schema,summary,screens,input,scenarios,admin:true,provisional,parameters:[...path.matchAll(/\{(\w+)\}/g)].map(m=>({name:m[1],in:'path',required:true,schema:m[1]==='operatingDay'?ref('Date'):id,description:'운영일 또는 등록된 안정 ID'}))});
  }
  add('putAdminAvailability','PUT','/admin/goods/{goodsId}/combinations/{combinationId}/availability','Availability','조합 판매 상태 저장. last-write-wins 예외로 If-Match 불필요.',['ADM-GOODS'],'AvailabilityInput',['normal','sold-out','not-found','precondition-required','error']);
  find('putAdminAvailability').idempotencyKeyRequired=true;
  add('getAdminProducts','GET','/admin/products','AdminGoodsList','관리자 상품 목록',['ADM-GOODS-PRODUCT-LIST'],undefined,['normal','empty','error']);
  add('getAdminProduct','GET','/admin/products/{goodsId}','AdminGoods','상품 수정 초기값',['ADM-GOODS-PRODUCT-EDIT'],undefined,['normal','not-found','error']);
  add('postAdminProduct','POST','/admin/products','AdminGoods','상품 등록·신규 조합은 ON_SALE',['ADM-GOODS-PRODUCT-EDIT'],'ProductInput',['normal','validation-failed','precondition-required','error']);
  add('putAdminProduct','PUT','/admin/products/{goodsId}','AdminGoods','상품 수정·유지 조합 상태 보존, 신규 ON_SALE, 삭제 허용',['ADM-GOODS-PRODUCT-EDIT'],'ProductInput',['normal','new-option','option-removal','validation-failed','not-found','precondition-required','edit-conflict','error']);
  add('deleteAdminProduct','DELETE','/admin/products/{goodsId}','Deleted','상품 완전 삭제',['ADM-GOODS-PRODUCT-LIST'],undefined,['normal','not-found','precondition-required','edit-conflict','error']);
  ops.push({
    operationId:'postAdminGoodsImage',method:'POST',path:'/api/v2/admin/media/goods-images',
    schema:'AdminGoodsImageUpload',summary:'상품 이미지 업로드·상품 연결 전 unattached media 생성',screens:['ADM-GOODS-PRODUCT-EDIT'],
    scenarios:['normal','validation-failed','payload-too-large','unsupported-media-type','precondition-required','error'],
    admin:true,provisional:false,parameters:[],multipartInput:true,idempotencyKeyRequired:true,
  });
  find('getAdminProduct').conditional=true;
  const productPost=find('postAdminProduct');
  productPost.idempotencyKeyRequired=true;
  const productPut=find('putAdminProduct');
  productPut.ifMatchRequired=true;
  productPut.idempotencyKeyRequired=true;
  const productDelete=find('deleteAdminProduct');
  productDelete.ifMatchRequired=true;
  productDelete.idempotencyKeyRequired=true;
  ops.push(
    {operationId:'createAdminSession',method:'POST',path:'/api/v2/admin/sessions',schema:'AdminSession',summary:'관리자 로그인',screens:[],input:'AdminLoginInput',scenarios:['normal','invalid-credentials','disabled','invalid-origin','error'],admin:true,authRequired:false,security:[],parameters:[],provisional:false,successStatus:200},
    {operationId:'refreshAdminSession',method:'POST',path:'/api/v2/admin/sessions/refresh',schema:'AdminSession',summary:'관리자 세션 갱신·refresh rotation',screens:[],scenarios:['normal','expired','revoked','unknown','disabled','invalid-origin','error'],admin:true,authRequired:false,security:[{AdminRefreshCookie:[]}],parameters:[],provisional:false,successStatus:200},
    {operationId:'deleteCurrentAdminSession',method:'DELETE',path:'/api/v2/admin/sessions/current',schema:'AdminLogout',summary:'현재 관리자 세션 로그아웃·refresh cookie가 없어도 성공',screens:[],scenarios:['normal','invalid-origin','error'],admin:true,authRequired:true,security:[{AdminBearer:[]}],parameters:[],provisional:false},
    {operationId:'getCurrentAdmin',method:'GET',path:'/api/v2/admin/me',schema:'AdminIdentity',summary:'현재 인증 관리자 확인',screens:[],scenarios:['normal','disabled','error'],admin:true,authRequired:true,parameters:[],provisional:false},
  );
}
