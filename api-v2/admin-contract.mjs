// Contract delta from the user-supplied ADM specification, 2026-09-13.
export function applyAdminContract(s,ops){
  const ref=n=>({$ref:`#/components/schemas/${n}`});
  const str=d=>({type:'string',minLength:1,description:d});
  const arr=(items,d)=>({type:'array',items,description:d});
  const obj=(properties,d='',required=Object.keys(properties))=>({type:'object',properties,required,additionalProperties:false,description:d});
  const nullable=(schema,d)=>({anyOf:[schema,{type:'null'}],description:d});
  const en=(values,d)=>({type:'string',enum:values,description:d});
  const id=ref('Id'),integer={type:'integer',minimum:0,maximum:Number.MAX_SAFE_INTEGER,description:'0 이상의 정수. 관리자 전용 재고 수량.'};
  const time={type:'string',pattern:'^([01][0-9]|2[0-3]):[0-5][0-9]$',description:'KST HH:mm, 해당 운영일 안의 시각. 종료는 시작보다 이후.'};
  s.Crowding.properties.operatingStatus=en(['BEFORE_OPEN','OPEN','CLOSED'],'관리자 운영 상태. 혼잡도 저장값과 별개.');
  s.Crowding.required.push('operatingStatus');
  s.OperatingHoursInput=obj({opensAt:time,closesAt:time},'해당 운영일의 시간 전체 저장. 기본 13:00~22:00. 같은 날짜 종료>시작.');
  s.OperatingHours=obj({operatingDay:ref('Date'),opensAt:time,closesAt:time,isDefault:{type:'boolean',description:'별도 설정 없이 기본값 사용'},updatedAt:nullable(ref('Timestamp'),'운영 시간 저장 시각. 혼잡도 수정 시각과 별개.')});
  s.OperatingHoursList=obj({items:arr(ref('OperatingHours'),'행사 날짜별 시간, 운영일 오름차순')});
  s.Goods.properties.images=arr(ref('Image'),'상품 복수 이미지. 배열 순서대로 보존, 실제 배치는 GOODS-001에서 정의.');
  s.Goods.properties.colors=arr(obj({id,name:str('색상 이름'),images:arr(ref('Image'),'선택 색상별 이미지')}),'등록 색상. 사이즈와 조합하여 재고 관리');
  s.Goods.required.push('images','colors');
  s.Goods.properties.image=nullable(ref('Image'),'대표 이미지 없으면 null. 등록 이미지 필수 개수는 합의 대기.');
  s.Goods.properties.colorImages.description='호환용 색상 이미지 평탄 목록. 색상당 복수 이미지 가능. colors를 색상 목록 기준으로 사용.';
  const variant=obj({colorId:id,colorName:str('색상명'),sizeId:id,sizeLabel:str('사이즈명'),status:en(['ON_SALE','SOLD_OUT'],'저장 수량>=1 / 0으로 계산. 직접 입력 불가.')});
  delete s.Availability.properties.sizes;s.Availability.required=s.Availability.required.filter(k=>k!=='sizes');
  s.Availability.properties.variants=arr(variant,'색상×사이즈별 공개 상태. 수량 필드 없음.');s.Availability.required.push('variants');
  s.Availability.properties.allSoldOut.description='등록된 조합이 1개 이상이고 모든 조합 수량이 0일 때 true.';
  s.Inventory=obj({...s.Availability.properties,variants:arr(obj({...variant.properties,quantity:integer}),'관리자만 정확한 재고 수량 조회')});
  s.InventoryList=obj({items:arr(ref('Inventory'),'상품별 관리자 재고')});
  s.InventoryInput=obj({quantity:integer},'선택 색상×사이즈의 남은 절대 수량. 증감량이 아님. 마지막 서버 저장 성공값 우선.');
  delete s.AvailabilityInput;
  s.ProductInput=obj({name:str('필수 상품명'),price:ref('Money'),images:arr(ref('Image'),'복수 이미지, URL 등록 방식은 목용 제안'),colors:s.Goods.properties.colors,sizes:s.Goods.properties.sizes,description:nullable(str('상품 소개'),'선택 소개')},'상품 정보 전체 저장. 기존 옵션 ID 유지. 새 옵션은 새 ID; 기존 ID 생략은 삭제 요청으로 간주하여 409. 재고는 여기서 입력하지 않음.');
  s.Translation.properties.title=nullable(str('번역 제목'),'PENDING이면 null 허용');
  s.Translation.properties.body=nullable(str('번역 본문'),'PENDING이면 null 허용');
  s.NoticeSource=obj({title:str('현재 한국어 제목'),body:str('현재 한국어 본문')});
  s.NoticeTranslationInput=ref('NoticeSource');
  s.NoticeTranslationPreview=obj({source:ref('NoticeSource'),translations:ref('Translations'),canSave:{type:'boolean',description:'영어 READY와 한국어 입력 조건 충족 여부'}},'저장 전 번역 미리보기. 실제 번역 엔진 없이 목 문구 반환. 실패 언어는 PENDING.');
  for(const n of ['NoticeInput','AdminNotice']){
    delete s[n].properties.image;s[n].required=s[n].required.filter(k=>k!=='image');
    s[n].properties.translationSource=ref('NoticeSource');s[n].required.push('translationSource');
  }
  s.NoticeInput.description='한국어·영어 READY 필수. 제공 중 중·일은 PENDING 가능. translationSource는 번역 검토 기준 한국어 원문이며 현재 원문과 일치해야 함. 원문 변경 시 재번역 또는 직접 수정·검토 후 함께 갱신.';
  const find=id=>ops.find(o=>o.operationId===id);
  find('getCrowding').scenarios=find('getCrowding').scenarios.filter(x=>x!=='overnight');
  find('getConfig').scenarios.push('all-languages');
  find('getAdminGoods').schema='InventoryList';find('getAdminGoods').summary='관리자 색상×사이즈 재고 조회';
  const old=ops.findIndex(o=>o.operationId==='putAdminAvailability');ops.splice(old,1);
  for(const id of ['postAdminNotice','putAdminNotice']){find(id).provisional=false;find(id).summary=find(id).summary.replace('(검토 필요)','');find(id).scenarios.push('english-incomplete','stale-translation');}
  function add(operationId,method,path,schema,summary,screens,input,scenarios=['normal','error'],provisional=false){
    ops.push({operationId,method,path:'/api/v2'+path,schema,summary,screens,input,scenarios,admin:true,provisional,parameters:[...path.matchAll(/\{(\w+)\}/g)].map(m=>({name:m[1],in:'path',required:true,schema:m[1]==='operatingDay'?ref('Date'):id,description:'운영일 또는 등록된 안정 ID'}))});
  }
  add('getOperatingHours','GET','/admin/operating-hours','OperatingHoursList','운영일별 운영 시간',['ADM-CROWD-HOURS'],undefined,['normal','empty','error']);
  add('putOperatingHours','PUT','/admin/operating-hours/{operatingDay}','OperatingHours','운영 시간 저장',['ADM-CROWD-HOURS'],'OperatingHoursInput',['normal','invalid-range','not-found','error']);
  add('putInventory','PUT','/admin/goods/{goodsId}/colors/{colorId}/sizes/{sizeId}/inventory','Inventory','색상×사이즈 남은 수량 저장',['ADM-GOODS'],'InventoryInput',['normal','sold-out','not-found','error']);
  add('getAdminProducts','GET','/admin/products','GoodsList','관리자 상품 목록',['ADM-GOODS-PRODUCT-LIST'],undefined,['normal','empty','error']);
  add('getAdminProduct','GET','/admin/products/{goodsId}','Goods','상품 수정 초기값',['ADM-GOODS-PRODUCT-EDIT'],undefined,['normal','missing-optional','not-found','error']);
  add('postAdminProduct','POST','/admin/products','Goods','상품 등록·신규 조합 0개 생성',['ADM-GOODS-PRODUCT-EDIT'],'ProductInput',['normal','missing-optional','error']);
  add('putAdminProduct','PUT','/admin/products/{goodsId}','Goods','상품 수정·기존 조합 재고 유지',['ADM-GOODS-PRODUCT-EDIT'],'ProductInput',['normal','new-option','option-removal','not-found','error']);
  add('previewNoticeTranslation','POST','/admin/notice-translations','NoticeTranslationPreview','공지 번역 생성·재시도',['ADM-NOTICE-EDIT','ADM-NOTICE-TEMPLATE'],'NoticeTranslationInput',['normal','english-failed','partial-translation','error'],true);
  find('previewNoticeTranslation').successStatus=200;
}
