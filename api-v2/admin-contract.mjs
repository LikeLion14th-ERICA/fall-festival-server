// Authoritative product rules: docs/wiki/product/admin/ (v5).
export function applyAdminContract(s,ops){
  const ref=n=>({$ref:`#/components/schemas/${n}`});
  const str=d=>({type:'string',minLength:1,description:d});
  const arr=(items,d)=>({type:'array',items,description:d});
  const obj=(properties,d='',required=Object.keys(properties))=>({type:'object',properties,required,additionalProperties:false,description:d});
  const nullable=(schema,d)=>({anyOf:[schema,{type:'null'}],description:d});
  const en=(values,d)=>({type:'string',enum:values,description:d});
  const id=ref('Id');
  s.Goods.properties.options={...arr(obj({colorId:id,sizeId:id}),'실제 제공하는 조합만 명시. 자동 곱집합 생성 없음.'),minItems:1};
  s.Goods.required.push('options');
  s.Goods.properties.images=arr(ref('Image'),'상품 복수 이미지. 배열 순서대로 보존, 실제 배치는 GOODS-001에서 정의.');
  s.Goods.properties.colors={...arr(obj({id,name:str('색상 이름'),images:arr(ref('Image'),'선택 색상별 이미지')}),'등록 색상. 실제 제공 조합은 options에 별도 등록'),minItems:1};
  s.Goods.required.push('images','colors');
  s.Goods.properties.image=nullable(ref('Image'),'대표 이미지 없으면 null. 등록 이미지 필수 개수는 합의 대기.');
  s.Goods.properties.colorImages.description='호환용 색상 이미지 평탄 목록. 색상당 복수 이미지 가능. colors를 색상 목록 기준으로 사용.';
  const variant=obj({colorId:id,colorName:str('색상명'),sizeId:id,sizeLabel:str('사이즈명'),status:en(['ON_SALE','SOLD_OUT'],'관리자가 구매 가능 / 품절 직접 선택. 수량 계산 없음.')});
  delete s.Availability.properties.sizes;s.Availability.required=s.Availability.required.filter(k=>k!=='sizes');
  s.Availability.properties.variants=arr(variant,'색상×사이즈별 공개 상태. 수량 필드 없음.');s.Availability.required.push('variants');
  s.Availability.properties.allSoldOut.description='실제 제공 조합이 1개 이상이고 모두 SOLD_OUT일 때 true.';
  s.AvailabilityInput=obj({status:en(['ON_SALE','SOLD_OUT'],'구매 가능 / 품절 직접 저장')});
  s.ProductInput=obj({name:str('필수 상품명'),price:ref('Money'),images:arr(ref('Image'),'복수 이미지. 개수·크기·배치·업로드 방식은 미정.'),colors:s.Goods.properties.colors,sizes:{...s.Goods.properties.sizes,minItems:1},options:s.Goods.properties.options,description:nullable(str('상품 소개'),'선택 소개')},'상품명·가격·실제 제공 색상·사이즈·조합은 필수이며 각 배열은 1개 이상이어야 한다. 이미지 입력 구성과 옵션 없는 상품 입력 방식은 미정이며 불완전 상품은 저장하지 않는다. 유지한 조합의 판매 상태는 보존하고 신규 조합은 ON_SALE로 생성하며 삭제된 조합의 상태는 함께 제거한다.');
  s.Translation.properties.title=nullable(str('번역 제목'),'PENDING/FAILED이면 null 허용');
  s.Translation.properties.body=nullable(str('번역 본문'),'PENDING/FAILED이면 null 허용');
  s.Translation.properties.status=en(['READY','PENDING','FAILED'],'완료 / 준비 중 / 실패. READY만 사용자 노출.');
  s.NoticeSource=obj({title:str('현재 한국어 제목'),body:str('현재 한국어 본문')});
  s.NoticeTranslationInput=ref('NoticeSource');
  s.NoticeTranslationPreview=obj({source:ref('NoticeSource'),translations:ref('Translations'),canSave:{type:'boolean',description:'한국어 입력 조건 충족 여부. 영어 실패도 한국어 저장 가능'}},'저장 전 번역 미리보기. 실제 번역 엔진 없이 목 문구 반환. 실패 언어는 FAILED.');
  s.AdminIdentity=obj({id,username:{type:'string',minLength:1,maxLength:100,description:'관리자 로그인 식별자'},authority:en(['ADMIN'],'현재 Product 범위의 단일 관리자 권한'),enabled:{type:'boolean',description:'false이면 로그인·refresh·관리자 API 인증 거부'}});
  s.AdminSession=obj({accessToken:{type:'string',minLength:1,description:'15분 유효한 signed JWT. Authorization Bearer로 전달'},expiresAt:ref('Timestamp'),admin:ref('AdminIdentity')});
  s.AdminLoginInput=obj({username:{type:'string',minLength:1,maxLength:100},password:{type:'string',minLength:1,maxLength:200,writeOnly:true}},'공개 회원가입 없이 환경 bootstrap으로 만든 관리자 계정으로 로그인');
  s.AdminLogout=obj({loggedOut:{type:'boolean',enum:[true],description:'현재 refresh session revoke 및 cookie 만료 완료'}});
  for(const n of ['NoticeInput','AdminNotice']){
    delete s[n].properties.image;s[n].required=s[n].required.filter(k=>k!=='image');

  }
  s.NoticeInput.description='한국어 제목·본문 READY 필수. 외국어 PENDING/FAILED는 한국어 저장을 막지 않음. 게시 후 상세 재번역 절차는 미정.';
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
  find('getAdminGoods').summary='관리자 실제 제공 옵션별 판매 상태';
  const old=ops.findIndex(o=>o.operationId==='putAdminAvailability');ops.splice(old,1);
  for(const id of ['postAdminNotice','putAdminNotice']){find(id).provisional=false;find(id).summary=find(id).summary.replace('(검토 필요)','');find(id).scenarios.push('english-incomplete','english-failed');}
  function add(operationId,method,path,schema,summary,screens,input,scenarios=['normal','error'],provisional=false){
    ops.push({operationId,method,path:'/api/v2'+path,schema,summary,screens,input,scenarios,admin:true,provisional,parameters:[...path.matchAll(/\{(\w+)\}/g)].map(m=>({name:m[1],in:'path',required:true,schema:m[1]==='operatingDay'?ref('Date'):id,description:'운영일 또는 등록된 안정 ID'}))});
  }
  add('putAdminAvailability','PUT','/admin/goods/{goodsId}/colors/{colorId}/sizes/{sizeId}/availability','Availability','옵션 판매 상태 저장',['ADM-GOODS'],'AvailabilityInput',['normal','sold-out','not-found','error']);
  add('getAdminProducts','GET','/admin/products','GoodsList','관리자 상품 목록',['ADM-GOODS-PRODUCT-LIST'],undefined,['normal','empty','error']);
  add('getAdminProduct','GET','/admin/products/{goodsId}','Goods','상품 수정 초기값',['ADM-GOODS-PRODUCT-EDIT'],undefined,['normal','missing-optional','not-found','error']);
  add('postAdminProduct','POST','/admin/products','Goods','상품 등록·신규 옵션은 ON_SALE',['ADM-GOODS-PRODUCT-EDIT'],'ProductInput',['normal','missing-optional','empty-configuration','error'],true);
  add('putAdminProduct','PUT','/admin/products/{goodsId}','Goods','상품 수정·유지 조합 상태 보존, 신규 ON_SALE, 삭제 허용',['ADM-GOODS-PRODUCT-EDIT'],'ProductInput',['normal','new-option','option-removal','empty-configuration','not-found','error'],true);
  add('previewNoticeTranslation','POST','/admin/notice-translations','NoticeTranslationPreview','공지 번역 생성·재시도',['ADM-NOTICE-EDIT','ADM-NOTICE-TEMPLATE'],'NoticeTranslationInput',['normal','english-failed','partial-translation','error'],true);
  find('previewNoticeTranslation').successStatus=200;
  ops.push(
    {operationId:'createAdminSession',method:'POST',path:'/api/v2/admin/sessions',schema:'AdminSession',summary:'관리자 로그인',screens:[],input:'AdminLoginInput',scenarios:['normal','invalid-credentials','disabled','invalid-origin','error'],admin:true,authRequired:false,security:[],parameters:[],provisional:false,successStatus:200},
    {operationId:'refreshAdminSession',method:'POST',path:'/api/v2/admin/sessions/refresh',schema:'AdminSession',summary:'관리자 세션 갱신·refresh rotation',screens:[],scenarios:['normal','expired','revoked','unknown','disabled','invalid-origin','error'],admin:true,authRequired:false,security:[{AdminRefreshCookie:[]}],parameters:[],provisional:false,successStatus:200},
    {operationId:'deleteCurrentAdminSession',method:'DELETE',path:'/api/v2/admin/sessions/current',schema:'AdminLogout',summary:'현재 관리자 세션 로그아웃·refresh cookie가 없어도 성공',screens:[],scenarios:['normal','invalid-origin','error'],admin:true,authRequired:true,security:[{AdminBearer:[]}],parameters:[],provisional:false},
    {operationId:'getCurrentAdmin',method:'GET',path:'/api/v2/admin/me',schema:'AdminIdentity',summary:'현재 인증 관리자 확인',screens:[],scenarios:['normal','disabled','error'],admin:true,authRequired:true,parameters:[],provisional:false},
  );
}
