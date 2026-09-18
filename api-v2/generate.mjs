import { readFile,writeFile } from 'node:fs/promises';
import { schemas,operations,envelopeSchema } from './contract-source.mjs';
import { createState,execute,MOCK_NOW,isoKst,scenarioTime,ApiFailure } from './domain.mjs';
import { validate } from './validate.mjs';
import { buildCoverage } from './screen-coverage.mjs';

export const sampleParams={operatingDay:'2030-10-01',colorId:'color-a',goodsId:'goods-shirt',sizeId:'size-m',artistId:'artist-a',performanceId:'show-1',spaceId:'space-booth',mapId:'map-area',placeId:'place-booth',noticeId:'notice-1',templateId:'template-1'};
const noticeInput={type:'GENERAL',translations:{ko:{title:'개발용 새 공지',body:'개발용 본문'},en:{title:'New mock notice',body:'Mock body'}},links:[{url:'https://example.invalid/mock-notice-link',labels:{ko:'예시 링크',en:'Sample link','zh-Hans':null,ja:null}}],templateId:null};
const g=createState().goods[0];
const productInput=Object.fromEntries(['name','price','images','colors','sizes','options','description'].map(k=>[k,g[k]]));
const inputExamples={CrowdingInput:{level:'CROWDED'},AvailabilityInput:{status:'ON_SALE'},ProductInput:productInput,NoticeInput:noticeInput,StampReceiptVerificationInput:{code:'MOCK-RECEIPT-CODE'},AdminLoginInput:{username:'mock-admin',password:'MOCK-NOT-A-REAL-SECRET'}};
const spec={openapi:'3.1.0',info:{title:'Espero 화면 기반 API 명세서 v2',version:'2.0.0-draft.3',description:'프런트 연동용 계약 초안. 기존 v1에서 독립. x-contract-status를 확인하고 운영 미정 값을 확정하지 않는다. 모든 examples는 가상 개발 데이터이며 실제 송금을 지원하지 않는다.'},servers:[{url:'http://127.0.0.1:4010',description:'로컬 목 전용. 실제 운영 서버 미정.'}],security:[],paths:{},components:{schemas:{...schemas},securitySchemes:{AdminBearer:{type:'http',scheme:'bearer',description:'15분 유효 signed JWT access token. Authorization: Bearer로 전달.'},AdminRefreshCookie:{type:'apiKey',in:'cookie',name:'__Host-festival-admin-refresh',description:'7일 유효 opaque refresh token. Secure·HttpOnly·SameSite=Strict이며 서버에는 SHA-256 hash만 저장.'}}},'x-source':{basis:'Product Context wiki v5; user decision 2026-09-16',commit:'1247890eaa2010d25955662aa172e5839c4da652',paths:['docs/wiki/product/','docs/wiki/product/admin/'],legacySnapshot:'source-screen-requirements.json'},'x-mock-controls':{scenario:'X-Mock-Scenario 또는 __scenario 쿼리(목 전용)',session:'X-Mock-Session',time:'X-Mock-Time',delay:'X-Mock-Delay (0~3000ms)'}};
const examples={};
const genericErrors={400:['INVALID_QUERY','잘못된 요청 예시입니다.'],401:['UNAUTHORIZED','관리자 인증이 필요합니다.'],403:['FORBIDDEN','관리자 권한이 없습니다.'],404:['NOT_FOUND','요청한 정보를 찾을 수 없습니다.'],405:['METHOD_NOT_ALLOWED','지원하지 않는 메서드입니다.'],409:['CONFLICT','요청 상태가 충돌합니다.'],413:['PAYLOAD_TOO_LARGE','요청 본문은 64KiB 이하입니다.'],415:['UNSUPPORTED_MEDIA_TYPE','application/json 요청이 필요합니다.'],422:['VALIDATION_FAILED','요청 필드를 확인해 주세요.'],429:['RATE_LIMITED','잠시 후 다시 요청해 주세요.'],500:['INTERNAL_ERROR','목 서버 처리 중 오류가 발생했습니다.'],503:['SERVICE_UNAVAILABLE','일시적으로 정보를 불러올 수 없습니다.']};
const noBodyStatuses=new Set([204,304]);
const strongEtagHeader={schema:{type:'string',pattern:'^\"[0-9a-f]{64}\"$'},description:'현재 조건부 응답 표현의 strong ETag'};
const unscopedOperations=new Set([
  'createAdminSession','refreshAdminSession','deleteCurrentAdminSession','getCurrentAdmin',
  'getCrowding','getAdminCrowding','putAdminCrowding'
]);
for(const op of operations){
  const responseName=`${op.schema}${op.conditional?'Conditional':''}Response`;spec.components.schemas[responseName]=envelopeSchema(op.schema,op.conditional?'ConditionalMeta':'Meta');
  const scenarios=[...op.scenarios,'bad-request','rate-limited',...(op.admin&&op.authRequired!==false?['unauthorized','forbidden']:[])];
  const successStatus=op.successStatus??(op.method==='POST'?201:200);
  const statuses=[...(successStatus===200?[200]:[]),400,403,404,405,409,429,500,503,...(op.input?[413,415,422]:[]),...(op.admin?[401]:[]),...(op.ifMatchRequired||op.idempotencyKeyRequired?[428]:[]),...(op.conditional?[304]:[]),successStatus].filter((status,index,array)=>array.indexOf(status)===index);
  const responses=Object.fromEntries(statuses.map(status=>{
    const conditionalHeaders=op.conditional&&status<300?{ETag:strongEtagHeader,'X-Server-Time':{schema:{type:'string',format:'date-time'},description:'조건부 응답의 서버 시각. 본문 meta에 넣지 않아 ETag를 바꾸지 않는다.'},...(op.cacheControl?{'Cache-Control':{schema:{type:'string',enum:[op.cacheControl]},description:'공유 캐시 금지와 매 요청 재검증. proxy는 이 값과 ETag를 그대로 전달한다.'}}:{})}:{};
    const response={description:status<300?'성공':genericErrors[status]?.[1]??'조건부 요청이 필요합니다.',headers:{'X-Request-Id':{schema:{type:'string'},description:'응답 meta.requestId와 동일'},...(status===429?{'Retry-After':{schema:{type:'integer',minimum:0},description:'재시도 전 대기 초'}}:{}),...conditionalHeaders}};
    if(!noBodyStatuses.has(status))response.content={'application/json':{schema:{$ref:`#/components/schemas/${status<300?responseName:'Error'}`},examples:{}}};
    return [status,response];
  }));
  if(['createAdminSession','refreshAdminSession','deleteCurrentAdminSession'].includes(op.operationId))responses[200].headers['Set-Cookie']={schema:{type:'string'},description:op.operationId==='deleteCurrentAdminSession'?'refresh cookie 만료(Max-Age=0)':'Secure; HttpOnly; SameSite=Strict refresh cookie'};
  const headerParameters=[
    ...(op.conditional?[{name:'If-None-Match',in:'header',required:false,schema:strongEtagHeader.schema,description:'표현이 변경되지 않았으면 304를 요청하는 strong ETag'}]:[]),
    ...(op.ifMatchRequired?[{name:'If-Match',in:'header',required:true,schema:strongEtagHeader.schema,description:'현재 표현의 strong ETag. 누락 시 428, 불일치 시 409.'}]:[]),
    ...(op.idempotencyKeyRequired?[{name:'Idempotency-Key',in:'header',required:true,schema:{type:'string',minLength:1,maxLength:128,pattern:'^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'},description:'같은 저장 요청 재시도에 사용하는 1~128자 키. 누락 시 428.'}]:[]),
  ];
  const operation={operationId:op.operationId,summary:op.summary,description:`연결 화면: ${op.screens.join(', ')||'관리자 공통 인증'}. ${op.provisional?'미정 기획을 위한 검토 필요 계약. ':''}목의 인증/시나리오 헤더는 연동 안내를 참고.`,tags:[op.admin?'관리자':'공개'],security:op.security??(op.admin?[{AdminBearer:[]}]:[]),parameters:[...op.parameters,...headerParameters],responses,'x-screen-ids':op.screens,'x-contract-status':op.provisional?'provisional':'screen-specified','x-mock-scenarios':scenarios,...(op.conditional?{'x-conditional':true}: {})};
  if(op.input)operation.requestBody={required:true,description:op.provisional?'목 연동용 입력 초안. 기획 합의 전 운영 구현 금지.':'저장할 변경값',content:{'application/json':{schema:{$ref:`#/components/schemas/${op.input}`},example:inputExamples[op.input]}}};
  spec.paths[op.path]??={};spec.paths[op.path][op.method.toLowerCase()]=operation;
  examples[op.operationId]={screens:op.screens,method:op.method,path:op.path,scenarios:{}};
  for(const scenario of scenarios){
    const state=createState();let status=successStatus,response;
    const query=op.operationId==='getPins'?{mapVersion:'mock-map-1'}:{};
    let body=op.input?structuredClone(inputExamples[op.input]):undefined;
    if(op.operationId==='putAdminCrowding'&&scenario==='full')body={level:'FULL',confirmFull:true};
    if(op.operationId==='putAdminAvailability'&&scenario==='sold-out')body={status:'SOLD_OUT'};
    if(op.operationId==='verifyStampReceipt'&&scenario==='invalid-code')body={code:'MOCK-INVALID-RECEIPT-CODE'};
    if(op.operationId==='putAdminProduct'&&scenario.startsWith('new-option')){body.colors.push({id:'color-new',name:'새 예시 색상',images:[]});body.options.push({colorId:'color-new',sizeId:'size-m'});}
    if(op.operationId==='putAdminProduct'&&scenario==='option-removal'){
      const removedColor=body.colors.pop();
      body.options=body.options.filter(option=>option.colorId!==removedColor.id);
    }
    if(op.operationId==='postAdminProduct'&&scenario==='missing-optional')Object.assign(body,{images:[],description:null});
    if((op.operationId==='postAdminProduct'||op.operationId==='putAdminProduct')&&scenario==='empty-configuration')Object.assign(body,{images:[],colors:[],sizes:[],options:[]});
    if(body?.translations&&scenario==='validation-failed')delete body.translations.en;
    let now=scenarioTime(scenario,MOCK_NOW);
    const meta=revision=>({requestId:'mock-example-request',serverTime:isoKst(now),timezone:'Asia/Seoul',festivalId:'festival-mock',revision,locale:'ko',mock:true});
    const conditionalMeta=revision=>({timezone:'Asia/Seoul',festivalId:'festival-mock',revision,locale:'ko',mock:true});
    try{
      const disabledFailure=op.operationId==='createAdminSession'?[401,'ADMIN_AUTHENTICATION_FAILED','관리자 인증에 실패했습니다.']:op.operationId==='refreshAdminSession'?[401,'ADMIN_REFRESH_TOKEN_INVALID','관리자 세션을 갱신할 수 없습니다.']:[401,...genericErrors[401]];
      const special={ 'bad-request':[400,...genericErrors[400]],'rate-limited':[429,...genericErrors[429]],unauthorized:[401,...genericErrors[401]],forbidden:[403,...genericErrors[403]],'invalid-credentials':[401,'ADMIN_AUTHENTICATION_FAILED','관리자 인증에 실패했습니다.'],'invalid-origin':[403,'ADMIN_CSRF_INVALID','허용되지 않은 관리자 요청 출처입니다.'],expired:[401,'ADMIN_REFRESH_TOKEN_INVALID','관리자 세션을 갱신할 수 없습니다.'],revoked:[401,'ADMIN_REFRESH_TOKEN_INVALID','관리자 세션을 갱신할 수 없습니다.'],unknown:[401,'ADMIN_REFRESH_TOKEN_INVALID','관리자 세션을 갱신할 수 없습니다.'],disabled:disabledFailure,'precondition-required':[428,'PRECONDITION_REQUIRED','최신 상태를 확인한 뒤 다시 저장해 주세요.'],'not-festival-day':[409,'NOT_FESTIVAL_DAY','현재 날짜는 축제 운영일이 아닙니다.'],'edit-conflict':[409,'EDIT_CONFLICT','다른 관리자가 먼저 변경했습니다. 최신 상태를 확인해 주세요.']}[scenario];
      if(special)throw new ApiFailure(...special);
      if(body){const issues=validate(spec.components.schemas[op.input],body,spec);if(issues.length)throw new ApiFailure(422,'VALIDATION_FAILED','요청 필드를 확인해 주세요.',issues);}
      const result=execute(op,state,{params:sampleParams,query,body,scenario,now});now=result.now;status=result.status;const responseRevision=unscopedOperations.has(op.operationId)?0:state.revision;response=noBodyStatuses.has(status)?null:{data:result.data,meta:op.conditional?conditionalMeta(responseRevision):meta(responseRevision)};
    }catch(e){if(!(e instanceof ApiFailure))throw e;status=e.status;response={error:{code:e.code,message:e.message,details:e.details,retryable:[429,500,503].includes(status)},meta:meta(0)};}
    if(!responses[status])throw new Error(`Missing response ${op.operationId} ${status}`);
    if(responses[status].content)responses[status].content['application/json'].examples[scenario]={summary:`${op.summary}: ${scenario}`,value:response};
    const actualPath=op.path.replace(/\{(\w+)\}/g,(_,key)=>sampleParams[key]);
    const qs=new URLSearchParams(query).toString();
    const cookieEndpoint=['createAdminSession','refreshAdminSession','deleteCurrentAdminSession'].includes(op.operationId);
    const mutationHeaders={...(op.ifMatchRequired?{'If-Match':'"'+'0'.repeat(64)+'"'}:{}),...(op.idempotencyKeyRequired?{'Idempotency-Key':`mock-${op.operationId}-${scenario}`}:{})};
    examples[op.operationId].scenarios[scenario]={request:{method:op.method,path:actualPath+(qs?'?'+qs:''),headers:{'X-Mock-Scenario':scenario,'X-Mock-Session':'frontend-demo',...(op.admin&&op.authRequired!==false?{Authorization:'Bearer mock-admin'}:{}),...(cookieEndpoint?{Origin:scenario==='invalid-origin'?'https://attacker.invalid':'http://localhost:5173'}:{}),...(['refreshAdminSession','deleteCurrentAdminSession'].includes(op.operationId)?{Cookie:'__Host-festival-admin-refresh=MOCK-OPAQUE-REFRESH-TOKEN'}:{}),...(body?{'Content-Type':'application/json'}:{}),...mutationHeaders},...(body?{body}:{})},status,response};
  }
}
const source=JSON.parse(await readFile(new URL('./source-screen-requirements.json',import.meta.url),'utf8'));
const coverage=buildCoverage(source,operations);
const clientExamples={stamp:{notStarted:{date:'2030-10-01',started:false,count:0,claimed:false},directQrBeforeStart:{date:'2030-10-01',started:false,count:0,claimed:false,route:'STAMP-START',startRecorded:false,stampAdded:false},collecting:{date:'2030-10-01',started:true,count:2,claimed:false},complete:{date:'2030-10-01',started:true,count:4,claimed:false},receiptCodeRejected:{date:'2030-10-01',started:true,count:4,claimed:false,route:'STAMP-REWARD',message:'코드를 확인해 주세요'},claimed:{date:'2030-10-01',started:true,count:4,claimed:true},afterMidnight:{date:'2030-10-02',started:false,count:0,claimed:false}},failures:{cameraDenied:'QR을 찍으려면 카메라 접근을 허용해 주세요',wrongQr:'스탬프투어 QR이 아니에요',language:'언어를 변경하지 못했어요. 다시 시도해 주세요'},note:'브라우저 상태 예시이며 HTTP 응답이 아니다. START 전 기본 카메라 QR 직접 진입은 시작 화면으로 이동한다. 상품 수령은 현장 담당자가 입력한 코드를 서버가 확인해 verified:true를 돌려줄 때만 claimed를 브라우저에 저장하며, 코드 자체는 저장하지 않는다.'};
const endpointMarkdown='# API v2 경로·시나리오 목록\n\n자동 생성 문서입니다. 필드별 계약은 [OpenAPI](openapi.json), 요청·응답 원문은 [examples.json](examples.json)을 확인하세요.\n\n| 메서드 | 경로 | 내용 | 화면 | 시나리오 |\n|---|---|---|---|---|\n'+operations.map(op=>`| ${op.method} | \`${op.path}\` | ${op.summary} | ${op.screens.join(', ')} | ${examples[op.operationId]?Object.keys(examples[op.operationId].scenarios).join(', '):''} |`).join('\n')+'\n';
const dataMarkdown=`# 화면 데이터 → API 필드\n\nProduct Context v5 기준 ${coverage.screens.length}개 화면의 데이터 ${coverage.data.filter(d=>d.owner!=='제외').length}개를 추적합니다. 제외 항목은 이력으로 표시합니다. 필드 경로는 응답 data 기준이며 [화면 상태·조회 규칙](SCREEN-STATES.md)을 함께 적용합니다. 이전 Google Sheets 스냅샷은 현재 계약의 기준이 아닙니다.\n\n| 데이터 ID | 항목 | 처리 | 계약/프런트 책임 | 위키 근거 |\n|---|---|---|---|---|\n`+coverage.data.map(d=>`| ${d.id} | ${d.label} | ${d.owner} | ${d.target} | ${'[위키]('+d.wikiSource+')'} |`).join('\n')+'\n';
for(const [name,value]of Object.entries({'openapi.json':spec,'examples.json':examples,'screen-coverage.json':coverage,'client-state-examples.json':clientExamples,'ENDPOINTS.md':endpointMarkdown,'SCREEN-DATA.md':dataMarkdown})){
  const content=typeof value==='string'?value:JSON.stringify(value,null,2)+'\n';const file=new URL(name,import.meta.url);
  if(process.argv.includes('--check')){if(await readFile(file,'utf8')!==content)throw new Error(`${name} is stale. Run npm run generate.`);}else await writeFile(file,content,'utf8');
}
console.log(`API v2: ${operations.length} operations, ${coverage.screens.length} screens, ${coverage.data.length} data mappings, ${Object.values(examples).reduce((n,v)=>n+Object.keys(v.scenarios).length,0)} example responses.`);
