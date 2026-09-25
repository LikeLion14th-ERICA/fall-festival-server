import { readFile,writeFile } from 'node:fs/promises';
import { schemas,operations,envelopeSchema } from './contract-source.mjs';
import { createState,execute,MOCK_NOW,isoKst,scenarioTime,ApiFailure } from './domain.mjs';
import { validate } from './validate.mjs';
import { buildCoverage } from './screen-coverage.mjs';
import { buildReleaseOperationCoverage } from './release-operation-coverage.mjs';

export const sampleParams={operatingDay:'2030-10-01',colorId:'color-a',goodsId:'goods-shirt',sizeId:'size-m',combinationId:'combo-shirt-a-m',artistId:'artist-a',performanceId:'show-1',spaceId:'space-booth',mapId:'map-area',placeId:'place-booth',noticeId:'notice-1',templateId:'template-1',mediaId:'00000000-0000-4000-8000-000000000050',variant:'master'};
const noticeInput={type:'GENERAL',translations:{ko:{title:'개발용 새 공지',body:'개발용 본문'},en:{title:'New mock notice',body:'Mock body'}},links:[{url:'https://example.invalid/mock-notice-link',labels:{ko:'예시 링크',en:'Sample link','zh-Hans':null,ja:null}}],templateId:null};
const g=createState().goods[0];
const productColorIds=new Map(g.colors.map((color,index)=>[color.id,`00000000-0000-4000-8000-${String(101+index).padStart(12,'0')}`]));
const productSizeIds=new Map(g.sizes.map((size,index)=>[size.id,`00000000-0000-4000-8000-${String(201+index).padStart(12,'0')}`]));
const productInput={
  optionMode:g.optionMode,
  translations:g.translations,
  price:g.price,
  images:g.images.map(({mediaId,alt})=>({mediaId,alt})),
  colors:g.colors.map(color=>({...color,id:productColorIds.get(color.id)})),
  sizes:g.sizes.map(size=>({...size,id:productSizeIds.get(size.id)})),
  options:g.combinations.map(({colorId,sizeId})=>({colorId:productColorIds.get(colorId),sizeId:productSizeIds.get(sizeId)})),
};
const inputExamples={CrowdingInput:{level:'CROWDED'},AvailabilityInput:{status:'ON_SALE'},ProductInput:productInput,NoticeInput:noticeInput,StampReceiptVerificationInput:{code:'482913'},StampCollectionInput:{token:'mock-booth-token-0001'},ArtistHypedInput:{},AdminLoginInput:{username:'mock-admin',password:'MOCK-NOT-A-REAL-SECRET'}};
const spec={openapi:'3.1.0',info:{title:'Espero 화면 기반 API 명세서 v2',version:'2.0.0-draft.3',description:'프런트 연동용 계약 초안. 기존 v1에서 독립. x-contract-status를 확인하고 운영 미정 값을 확정하지 않는다. 모든 examples는 가상 개발 데이터이며 실제 송금을 지원하지 않는다.'},servers:[{url:'http://127.0.0.1:4010',description:'로컬 목 전용. 실제 운영 서버 미정.'}],security:[],paths:{},components:{schemas:{...schemas},securitySchemes:{AdminBearer:{type:'http',scheme:'bearer',description:'15분 유효 signed JWT access token. Authorization: Bearer로 전달.'},AdminRefreshCookie:{type:'apiKey',in:'cookie',name:'__Host-festival-admin-refresh',description:'7일 유효 opaque refresh token. Secure·HttpOnly·SameSite=Strict이며 서버에는 SHA-256 hash만 저장.'}}},'x-source':{basis:'Product Context wiki v5; user decision 2026-09-16',commit:'1247890eaa2010d25955662aa172e5839c4da652',paths:['docs/wiki/product/','docs/wiki/product/admin/'],legacySnapshot:'source-screen-requirements.json'},'x-mock-controls':{scenario:'X-Mock-Scenario 또는 __scenario 쿼리(목 전용)',session:'X-Mock-Session',time:'X-Mock-Time',delay:'X-Mock-Delay (0~3000ms)'}};
const examples={};
const genericErrors={400:['INVALID_QUERY','잘못된 요청 예시입니다.'],401:['UNAUTHORIZED','관리자 인증이 필요합니다.'],403:['FORBIDDEN','관리자 권한이 없습니다.'],404:['NOT_FOUND','요청한 정보를 찾을 수 없습니다.'],405:['METHOD_NOT_ALLOWED','지원하지 않는 메서드입니다.'],409:['CONFLICT','요청 상태가 충돌합니다.'],413:['PAYLOAD_TOO_LARGE','요청 본문은 64KiB 이하입니다.'],415:['UNSUPPORTED_MEDIA_TYPE','application/json 요청이 필요합니다.'],422:['VALIDATION_FAILED','요청 필드를 확인해 주세요.'],429:['RATE_LIMITED','잠시 후 다시 요청해 주세요.'],500:['INTERNAL_ERROR','목 서버 처리 중 오류가 발생했습니다.'],503:['SERVICE_UNAVAILABLE','일시적으로 정보를 불러올 수 없습니다.']};
const noBodyStatuses=new Set([204,304]);
const strongEtagHeader={schema:{type:'string',pattern:'^\"[0-9a-f]{64}\"$'},description:'현재 조건부 응답 표현의 strong ETag'};
const unscopedOperations=new Set([
  'createAdminSession','refreshAdminSession','deleteCurrentAdminSession','getCurrentAdmin',
  'getCrowding','getAdminCrowding','putAdminCrowding',
  'getNotices','getAdminNotice','getAdminNotices','postAdminNotice','putAdminNotice','deleteAdminNotice',
  'getGoods','getGoodsAvailability','getGood','getGoodAvailability','getPaymentGuide','getArtistHyped','postArtistHyped',
  'getAdminGoods','getAdminProducts','getAdminProduct','postAdminProduct','putAdminProduct','deleteAdminProduct','putAdminAvailability',
  'postAdminGoodsImage','getGoodsImage'
]);
for(const op of operations){
  const usesConditionalMeta=op.conditional||op.conditionalMeta;
  const responseName=op.schema?`${op.schema}${usesConditionalMeta?'Conditional':''}Response`:null;
  if(responseName)spec.components.schemas[responseName]=envelopeSchema(op.schema,usesConditionalMeta?'ConditionalMeta':'Meta');
  const hasLocale=op.parameters.some(parameter=>parameter.in==='query'&&parameter.name==='locale');
  const scenarios=[...new Set([...op.scenarios,...(hasLocale?['locale-not-ready']:[]),'bad-request','rate-limited',...(op.admin&&op.authRequired!==false?['unauthorized','forbidden']:[])])];
  const successStatus=op.successStatus??(op.method==='POST'?201:200);
  const statuses=[...(successStatus===200||op.additionalSuccessStatuses?.includes(200)?[200]:[]),400,403,404,405,409,429,500,503,...(op.input||op.multipartInput?[413,415,422]:[]),...(op.admin?[401]:[]),...(op.ifMatchRequired||op.idempotencyKeyRequired?[428]:[]),...(op.conditional?[304]:[]),successStatus].filter((status,index,array)=>array.indexOf(status)===index);
  const responses=Object.fromEntries(statuses.map(status=>{
    const conditionalHeaders=op.conditional&&(status<300||status===304)?{ETag:strongEtagHeader,...(op.binaryResponse?{}:{'X-Server-Time':{schema:{type:'string',format:'date-time'},description:'조건부 응답의 서버 시각. 본문 meta에 넣지 않아 ETag를 바꾸지 않는다.'}})}:{};
    const cacheControlHeaders=op.cacheControl&&(status<300||(op.conditional&&status===304)||op.cacheControlOnErrors)?{'Cache-Control':{schema:{type:'string',enum:[op.cacheControl]},description:op.cacheControl==='no-store'?'브라우저와 중간 캐시가 응답을 저장하지 못하게 한다.':op.binaryResponse?'응답에 적용되는 캐시 지시문.':'공유 캐시 금지와 매 요청 재검증. proxy는 이 값과 ETag를 그대로 전달한다.'}}:{};
    const binaryHeaders=op.binaryResponse&&(status===200||status===304)?{'Content-Disposition':{schema:{type:'string',enum:['inline']},description:'브라우저 inline 표시'},'X-Content-Type-Options':{schema:{type:'string',enum:['nosniff']},description:'MIME sniffing 차단'}}:{};
    const locationHeaders=op.locationHeader&&status===successStatus?{Location:{schema:{type:'string',format:'uri-reference',pattern:`^${op.path}/[^/]+$`},description:`생성된 리소스의 상세 경로(${op.path}/{id})`}}:{};
    const requestIdHeader={'X-Request-Id':{schema:{type:'string'},description:op.binaryResponse||usesConditionalMeta?'요청 추적 ID':'응답 meta.requestId와 동일'}};
    const responseOverride=op.responseOverrides?.[status];
    const response={description:status<300?'성공':responseOverride?.description??genericErrors[status]?.[1]??'조건부 요청이 필요합니다.',headers:{...requestIdHeader,...(status===429?{'Retry-After':{schema:{type:'integer',minimum:0},description:'재시도 전 대기 초'}}:{}),...locationHeaders,...conditionalHeaders,...cacheControlHeaders,...binaryHeaders}};
    if(op.binaryResponse&&status===200)response.content={'image/webp':{schema:{type:'string',format:'binary'}}};
    else if(!noBodyStatuses.has(status))response.content={'application/json':{schema:{$ref:`#/components/schemas/${status<300?responseName:'Error'}`},examples:{}}};
    return [status,response];
  }));
  if(op.operationId==='startStampParticipation')responses[201].headers['Set-Cookie']={schema:{type:'string'},description:'__Host-festival-stamp 익명 참여자 쿠키. Secure; HttpOnly; SameSite=Lax; Path=/; Max-Age 30일(마지막 START부터). 오늘 처음 START하면(201) 발급·갱신하고, 오늘 이미 START했으면 200과 함께 보내지 않는다.'};
  if(['createAdminSession','refreshAdminSession','deleteCurrentAdminSession'].includes(op.operationId))responses[200].headers['Set-Cookie']={schema:{type:'string'},description:op.operationId==='deleteCurrentAdminSession'?'refresh cookie 만료(Max-Age=0)':'Secure; HttpOnly; SameSite=Strict refresh cookie'};
  const headerParameters=[
    ...(op.conditional?[{name:'If-None-Match',in:'header',required:false,schema:op.binaryResponse?{type:'string',pattern:'^(?:\\*|(?:W/)?"[0-9a-f]{64}")$'}:strongEtagHeader.schema,description:op.binaryResponse?'현재 media ETag. weak validator와 *도 GET 비교에 허용.':'표현이 변경되지 않았으면 304를 요청하는 strong ETag'}]:[]),
    ...(op.ifMatchRequired?[{name:'If-Match',in:'header',required:true,schema:strongEtagHeader.schema,description:'현재 표현의 strong ETag. 누락 시 428, 불일치 시 409.'}]:[]),
    ...(op.idempotencyKeyRequired?[{name:'Idempotency-Key',in:'header',required:true,schema:{type:'string',minLength:1,maxLength:128,pattern:'^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'},description:op.idempotencyKeyDescription??'같은 저장 요청 재시도에 사용하는 1~128자 키. 누락 시 428.'}]:[]),
  ];
  const operation={operationId:op.operationId,summary:op.summary,description:`연결 화면: ${op.screens.join(', ')||'관리자 공통 인증'}. ${op.provisional?'미정 기획을 위한 검토 필요 계약. ':''}목의 인증/시나리오 헤더는 연동 안내를 참고.`,tags:[op.admin?'관리자':'공개'],security:op.security??(op.admin?[{AdminBearer:[]}]:[]),parameters:[...op.parameters,...headerParameters],responses,'x-screen-ids':op.screens,'x-contract-status':op.provisional?'provisional':'screen-specified','x-mock-scenarios':scenarios,...(op.conditional?{'x-conditional':true}: {}),...(op.conditionalMeta?{'x-conditional-meta':true}: {}),...(op.binaryResponse?{'x-binary-response':true}: {})};
  if(op.input)operation.requestBody={required:true,description:op.provisional?'목 연동용 입력 초안. 기획 합의 전 운영 구현 금지.':'저장할 변경값',content:{'application/json':{schema:{$ref:`#/components/schemas/${op.input}`},example:inputExamples[op.input]}}};
  if(op.multipartInput)operation.requestBody={required:true,description:'10 MiB 이하의 JPEG, PNG 또는 WebP 원본. 파일명과 client MIME은 format 판정에 사용하지 않는다.',content:{'multipart/form-data':{schema:{type:'object',properties:{file:{type:'string',format:'binary'}},required:['file'],additionalProperties:false}}}};
  spec.paths[op.path]??={};spec.paths[op.path][op.method.toLowerCase()]=operation;
  examples[op.operationId]={screens:op.screens,method:op.method,path:op.path,scenarios:{}};
  for(const scenario of scenarios){
    const state=createState();let status=successStatus,response;
    const query={...(op.operationId==='getPins'?{mapVersion:'mock-map-1'}:{}),...(scenario==='locale-not-ready'?{locale:'en'}:{})};
    let body=op.input?structuredClone(inputExamples[op.input]):undefined;
    if(op.operationId==='putAdminCrowding'&&scenario==='full')body={level:'FULL',confirmFull:true};
    if(op.operationId==='putAdminAvailability'&&scenario==='sold-out')body={status:'SOLD_OUT'};
    if(op.operationId==='verifyStampReceipt'&&scenario==='invalid-code')body={code:'000000'};
    if(op.operationId==='collectStamp'&&scenario==='invalid-token')body={token:'mock-unknown-token-0000'};
    if(op.operationId==='putAdminProduct'&&scenario==='new-option'){
      body.colors.push({id:'00000000-0000-4000-8000-000000000103',translations:{ko:{name:'새 예시 색상'},en:{name:'New sample color'},'zh-Hans':null,ja:null}});
      body.options.push({colorId:'00000000-0000-4000-8000-000000000103',sizeId:'00000000-0000-4000-8000-000000000201'});
    }
    if(op.operationId==='putAdminProduct'&&scenario==='option-removal'){
      const removedColor=body.colors.pop();
      body.options=body.options.filter(option=>option.colorId!==removedColor.id);
    }
    if(body?.translations&&scenario==='validation-failed')delete body.translations.en;
    let now=scenarioTime(scenario,MOCK_NOW);
    const meta=revision=>({requestId:'mock-example-request',serverTime:isoKst(now),timezone:'Asia/Seoul',festivalId:'festival-mock',revision,locale:'ko',mock:true});
    const conditionalMeta=revision=>({timezone:'Asia/Seoul',festivalId:'festival-mock',revision,locale:'ko',mock:true});
    try{
      const disabledFailure=op.operationId==='createAdminSession'?[401,'ADMIN_AUTHENTICATION_FAILED','관리자 인증에 실패했습니다.']:op.operationId==='refreshAdminSession'?[401,'ADMIN_REFRESH_TOKEN_INVALID','관리자 세션을 갱신할 수 없습니다.']:[401,...genericErrors[401]];
      const special={ 'bad-request':[400,...genericErrors[400]],'rate-limited':[429,...genericErrors[429]],unauthorized:[401,...genericErrors[401]],forbidden:[403,...genericErrors[403]],'invalid-credentials':[401,'ADMIN_AUTHENTICATION_FAILED','관리자 인증에 실패했습니다.'],'invalid-origin':[403,'ADMIN_CSRF_INVALID','허용되지 않은 관리자 요청 출처입니다.'],expired:[401,'ADMIN_REFRESH_TOKEN_INVALID','관리자 세션을 갱신할 수 없습니다.'],revoked:[401,'ADMIN_REFRESH_TOKEN_INVALID','관리자 세션을 갱신할 수 없습니다.'],unknown:[401,'ADMIN_REFRESH_TOKEN_INVALID','관리자 세션을 갱신할 수 없습니다.'],disabled:disabledFailure,'precondition-required':[428,'PRECONDITION_REQUIRED','최신 상태를 확인한 뒤 다시 저장해 주세요.'],'idempotency-key-required':[428,'IDEMPOTENCY_KEY_REQUIRED','Idempotency-Key 헤더가 필요합니다.'],'invalid-media-reference':[422,'INVALID_MEDIA_REFERENCE','사용할 수 없는 상품 이미지가 포함되어 있습니다.'],...(op.multipartInput?{'validation-failed':[422,'VALIDATION_FAILED','요청 파일을 확인해 주세요.'],'payload-too-large':[413,'PAYLOAD_TOO_LARGE','업로드 파일은 10 MiB 이하여야 합니다.'],'unsupported-media-type':[415,'UNSUPPORTED_MEDIA_TYPE','multipart/form-data 요청이 필요합니다.']}:{}),'edit-conflict':[409,'EDIT_CONFLICT','다른 관리자가 먼저 변경했습니다. 최신 상태를 확인해 주세요.']}[scenario];
      if(special)throw new ApiFailure(...special);
      if(body){const issues=validate(spec.components.schemas[op.input],body,spec);if(issues.length)throw new ApiFailure(422,'VALIDATION_FAILED','요청 필드를 확인해 주세요.',issues);}
      const result=execute(op,state,{params:sampleParams,query,body,scenario,now});now=result.now;status=result.status;const responseRevision=unscopedOperations.has(op.operationId)?0:state.revision;response=noBodyStatuses.has(status)||op.binaryResponse?null:{data:result.data,meta:usesConditionalMeta?conditionalMeta(responseRevision):meta(responseRevision)};
    }catch(e){if(!(e instanceof ApiFailure))throw e;status=e.status;const override=op.responseOverrides?.[status];response={error:{code:override?.code??e.code,message:override?.message??e.message,details:e.details,retryable:override?.retryable??[429,500,503].includes(status)},meta:meta(0)};}
    if(!responses[status])throw new Error(`Missing response ${op.operationId} ${status}`);
    if(responses[status].content?.['application/json'])responses[status].content['application/json'].examples[scenario]={summary:`${op.summary}: ${scenario}`,value:response};
    const actualPath=op.path.replace(/\{(\w+)\}/g,(_,key)=>sampleParams[key]);
    const qs=new URLSearchParams(query).toString();
    const cookieEndpoint=['createAdminSession','refreshAdminSession','deleteCurrentAdminSession'].includes(op.operationId);
    const mutationHeaders={...(op.ifMatchRequired?{'If-Match':'"'+'0'.repeat(64)+'"'}:{}),...(op.idempotencyKeyRequired?{'Idempotency-Key':`mock-${op.operationId}-${scenario}`}:{})};
    const session=scenario==='locale-not-ready'?`locale-not-ready-${op.operationId}`:'frontend-demo';
    examples[op.operationId].scenarios[scenario]={request:{method:op.method,path:actualPath+(qs?'?'+qs:''),headers:{'X-Mock-Scenario':scenario,'X-Mock-Session':session,...(op.admin&&op.authRequired!==false?{Authorization:'Bearer mock-admin'}:{}),...(cookieEndpoint?{Origin:scenario==='invalid-origin'?'https://attacker.invalid':'http://localhost:5173'}:{}),...(['refreshAdminSession','deleteCurrentAdminSession'].includes(op.operationId)?{Cookie:'__Host-festival-admin-refresh=MOCK-OPAQUE-REFRESH-TOKEN'}:{}),...(body?{'Content-Type':'application/json'}:{}),...(op.multipartInput?{'Content-Type':scenario==='unsupported-media-type'?'application/json':'multipart/form-data; boundary=<generated>'}:{}),...mutationHeaders},...(body?{body}:{}),...(op.multipartInput&&scenario!=='unsupported-media-type'?{multipart:{file:'<binary>'}}:{})},status,response};
  }
}
const source=JSON.parse(await readFile(new URL('./source-screen-requirements.json',import.meta.url),'utf8'));
const coverage=buildCoverage(source,operations);
const releaseOperationCoverage=buildReleaseOperationCoverage(spec);
const clientExamples={stamp:{notStarted:{date:'2030-10-01',started:false,count:0,claimed:false},directQrBeforeStart:{date:'2030-10-01',started:false,count:0,claimed:false,route:'STAMP-START',startRecorded:false,stampAdded:false},collecting:{date:'2030-10-01',started:true,count:2,claimed:false},complete:{date:'2030-10-01',started:true,count:4,claimed:false},receiptCodeRejected:{date:'2030-10-01',started:true,count:4,claimed:false,route:'STAMP-REWARD',message:'코드를 확인해 주세요'},claimed:{date:'2030-10-01',started:true,count:4,claimed:true},afterMidnight:{date:'2030-10-02',started:false,count:0,claimed:false}},failures:{cameraDenied:'QR을 찍으려면 카메라 접근을 허용해 주세요',wrongQr:'스탬프투어 QR이 아니에요',language:'언어를 변경하지 못했어요. 다시 시도해 주세요'},note:'화면 상태 예시이며 HTTP 응답이 아니다. started·count·claimed는 GET /stamp-card(StampCard)에서 읽는다. 시작 화면은 GET /stamp-card가 STAMP_NOT_STARTED일 때만 보여 주며, 오늘 START 뒤로는 다음 날 0시까지 나오지 않는다. 오늘 START 전 기본 카메라로 부스 QR 링크에 직접 들어오면(STAMP_NOT_STARTED) 시작 화면으로 이동하고 적립하지 않으며, START 뒤에는 바로 적립하고 수집 화면을 보여 준다. 상품 수령은 현장 담당자가 입력한 코드를 서버가 확인하고 오늘 수령을 기록할 때만 성공하며, 코드 자체는 저장하지 않는다.'};
const endpointMarkdown='# API v2 경로·시나리오 목록\n\n자동 생성 문서입니다. 필드별 계약은 [OpenAPI](openapi.json), 요청·응답 원문은 [examples.json](examples.json)을 확인하세요.\n\n| 메서드 | 경로 | 내용 | 화면 | 시나리오 |\n|---|---|---|---|---|\n'+operations.map(op=>`| ${op.method} | \`${op.path}\` | ${op.summary} | ${op.screens.join(', ')} | ${examples[op.operationId]?Object.keys(examples[op.operationId].scenarios).join(', '):''} |`).join('\n')+'\n';
const dataMarkdown=`# 화면 데이터 → API 필드\n\nProduct Context v5 기준 ${coverage.screens.length}개 화면의 데이터 ${coverage.data.filter(d=>d.owner!=='제외').length}개를 추적합니다. 제외 항목은 이력으로 표시합니다. 필드 경로는 응답 data 기준이며 [화면 상태·조회 규칙](SCREEN-STATES.md)을 함께 적용합니다. 이전 Google Sheets 스냅샷은 현재 계약의 기준이 아닙니다.\n\n| 데이터 ID | 항목 | 처리 | 계약/프런트 책임 | 위키 근거 |\n|---|---|---|---|---|\n`+coverage.data.map(d=>`| ${d.id} | ${d.label} | ${d.owner} | ${d.target} | ${'[위키]('+d.wikiSource+')'} |`).join('\n')+'\n';
for(const [name,value]of Object.entries({'openapi.json':spec,'examples.json':examples,'screen-coverage.json':coverage,'client-state-examples.json':clientExamples,'release-operation-coverage.json':releaseOperationCoverage,'ENDPOINTS.md':endpointMarkdown,'SCREEN-DATA.md':dataMarkdown})){
  const content=typeof value==='string'?value:JSON.stringify(value,null,2)+'\n';const file=new URL(name,import.meta.url);
  if(process.argv.includes('--check')){if(await readFile(file,'utf8')!==content)throw new Error(`${name} is stale. Run npm run generate.`);}else await writeFile(file,content,'utf8');
}
console.log(`API v2: ${operations.length} operations, ${coverage.screens.length} screens, ${coverage.data.length} data mappings, ${Object.values(examples).reduce((n,v)=>n+Object.keys(v.scenarios).length,0)} example responses.`);
