import { readFile,writeFile } from 'node:fs/promises';
import { schemas,operations,envelopeSchema } from './contract-source.mjs';
import { createState,execute,MOCK_NOW,isoKst,scenarioTime,ApiFailure } from './domain.mjs';
import { validate } from './validate.mjs';
import { buildCoverage } from './screen-coverage.mjs';

export const sampleParams={operatingDay:'2030-10-01',colorId:'color-a',goodsId:'goods-shirt',sizeId:'size-m',artistId:'artist-a',performanceId:'show-1',spaceId:'space-booth',mapId:'map-area',placeId:'place-booth',noticeId:'notice-1',templateId:'template-1'};
const noticeInput={type:'GENERAL',translations:{ko:{title:'개발용 새 공지',body:'개발용 본문',status:'READY'},en:{title:'New mock notice',body:'Mock body',status:'READY'}},links:[],templateId:null};
const g=createState().goods[0];
const productInput=Object.fromEntries(['name','price','images','colors','sizes','options','description'].map(k=>[k,g[k]]));
const inputExamples={CrowdingInput:{level:'CROWDED'},AvailabilityInput:{status:'ON_SALE'},ProductInput:productInput,NoticeInput:noticeInput,NoticeTranslationInput:{title:'개발용 제목',body:'개발용 본문'}};
const spec={openapi:'3.1.0',info:{title:'Espero 화면 기반 API 명세서 v2',version:'2.0.0-draft.3',description:'프런트 연동용 계약 초안. 기존 v1에서 독립. x-contract-status를 확인하고 운영 미정 값을 확정하지 않는다. 모든 examples는 가상 개발 데이터이며 실제 송금을 지원하지 않는다.'},servers:[{url:'http://127.0.0.1:4010',description:'로컬 목 전용. 실제 운영 서버 미정.'}],security:[],paths:{},components:{schemas:{...schemas},securitySchemes:{AdminBearer:{type:'http',scheme:'bearer',description:'관리자 서버 권한 검증 경계. 실 인증 프로토콜은 DECISIONS.md의 관리자 인증 항목 참조. 목에서만 Bearer mock-admin을 사용하며 운영 자격증명이 아니다.'}}},'x-source':{basis:'Product Context wiki v5; user decision 2026-09-14',commit:'21eb76dacd78b3ad79ed4d9589dd341fbc25b883',paths:['docs/wiki/product/','docs/wiki/product/admin/'],legacySnapshot:'source-screen-requirements.json'},'x-mock-controls':{scenario:'X-Mock-Scenario 또는 __scenario 쿼리(목 전용)',session:'X-Mock-Session',time:'X-Mock-Time',delay:'X-Mock-Delay (0~3000ms)'}};
const examples={};
const genericErrors={400:['INVALID_QUERY','잘못된 요청 예시입니다.'],401:['UNAUTHORIZED','관리자 인증이 필요합니다.'],403:['FORBIDDEN','관리자 권한이 없습니다.'],404:['NOT_FOUND','요청한 정보를 찾을 수 없습니다.'],405:['METHOD_NOT_ALLOWED','지원하지 않는 메서드입니다.'],409:['CONFLICT','요청 상태가 충돌합니다.'],413:['PAYLOAD_TOO_LARGE','요청 본문은 64KiB 이하입니다.'],415:['UNSUPPORTED_MEDIA_TYPE','application/json 요청이 필요합니다.'],422:['VALIDATION_FAILED','요청 필드를 확인해 주세요.'],429:['RATE_LIMITED','잠시 후 다시 요청해 주세요.'],500:['INTERNAL_ERROR','목 서버 처리 중 오류가 발생했습니다.'],503:['SERVICE_UNAVAILABLE','일시적으로 정보를 불러올 수 없습니다.']};
for(const op of operations){
  const responseName=`${op.schema}Response`;spec.components.schemas[responseName]=envelopeSchema(op.schema);
  const scenarios=[...op.scenarios,'bad-request','rate-limited',...(op.admin?['unauthorized','forbidden']:[])];
  const statuses=[200,400,403,404,405,409,429,500,503,...(op.input?[413,415,422]:[]),...(op.admin?[401]:[])];if(op.method==='POST'&&op.successStatus!==200){statuses.splice(statuses.indexOf(200),1);statuses.push(201);}
  const responses=Object.fromEntries(statuses.map(status=>[status,{description:status<300?'성공':genericErrors[status][1],headers:{'X-Request-Id':{schema:{type:'string'},description:'응답 meta.requestId와 동일'},...(status===429?{'Retry-After':{schema:{type:'integer',minimum:0},description:'재시도 전 대기 초'}}:{})},content:{'application/json':{schema:{$ref:`#/components/schemas/${status<300?responseName:'Error'}`},examples:{}}}}]));
  const operation={operationId:op.operationId,summary:op.summary,description:`연결 화면: ${op.screens.join(', ')}. ${op.provisional?'미정 기획을 위한 검토 필요 계약. ':''}목의 인증/시나리오 헤더는 연동 안내를 참고.`,tags:[op.admin?'관리자':'공개'],security:op.admin?[{AdminBearer:[]}]:[],parameters:op.parameters,responses,'x-screen-ids':op.screens,'x-contract-status':op.provisional?'provisional':'screen-specified','x-mock-scenarios':scenarios};
  if(op.input)operation.requestBody={required:true,description:op.provisional?'목 연동용 입력 초안. 기획 합의 전 운영 구현 금지.':'저장할 변경값',content:{'application/json':{schema:{$ref:`#/components/schemas/${op.input}`},example:inputExamples[op.input]}}};
  spec.paths[op.path]??={};spec.paths[op.path][op.method.toLowerCase()]=operation;
  examples[op.operationId]={screens:op.screens,method:op.method,path:op.path,scenarios:{}};
  for(const scenario of scenarios){
    const state=createState();let status=200,response;
    const query=op.operationId==='getPins'?{mapVersion:'mock-map-1'}:{};
    let body=op.input?structuredClone(inputExamples[op.input]):undefined;
    if(op.operationId==='putAdminCrowding'&&scenario==='full')body={level:'FULL',confirmFull:true};
    if(op.operationId==='putAdminAvailability'&&scenario==='sold-out')body={status:'SOLD_OUT'};
    if(op.operationId==='putAdminProduct'&&scenario.startsWith('new-option')){body.colors.push({id:'color-new',name:'새 예시 색상',images:[]});body.options.push({colorId:'color-new',sizeId:'size-m'});}
    if(op.operationId==='putAdminProduct'&&scenario==='option-removal')body.colors.pop();
    if(op.operationId==='postAdminProduct'&&scenario==='missing-optional')Object.assign(body,{images:[],description:null});
    if((op.operationId==='postAdminProduct'||op.operationId==='putAdminProduct')&&scenario==='empty-configuration')Object.assign(body,{images:[],colors:[],sizes:[],options:[]});
    if(body?.translations&&scenario==='english-incomplete')body.translations.en={title:null,body:null,status:'PENDING'};
    if(body?.translations&&scenario==='english-failed')body.translations.en={title:null,body:null,status:'FAILED'};
    let now=scenarioTime(scenario,MOCK_NOW);
    const meta=()=>({requestId:'mock-example-request',serverTime:isoKst(now),timezone:'Asia/Seoul',festivalId:'festival-mock',revision:state.revision,locale:'ko',mock:true});
    try{
      const special={ 'bad-request':400,'rate-limited':429,unauthorized:401,forbidden:403 }[scenario];
      if(special)throw new ApiFailure(special,...genericErrors[special]);
      if(body){const issues=validate(spec.components.schemas[op.input],body,spec);if(issues.length)throw new ApiFailure(422,'VALIDATION_FAILED','요청 필드를 확인해 주세요.',issues);}
      const result=execute(op,state,{params:sampleParams,query,body,scenario,now});now=result.now;status=result.status;response={data:result.data,meta:meta()};
    }catch(e){if(!(e instanceof ApiFailure))throw e;status=e.status;response={error:{code:e.code,message:e.message,details:e.details,retryable:[429,500,503].includes(status)},meta:meta()};}
    if(!responses[status])throw new Error(`Missing response ${op.operationId} ${status}`);
    responses[status].content['application/json'].examples[scenario]={summary:`${op.summary}: ${scenario}`,value:response};
    const actualPath=op.path.replace(/\{(\w+)\}/g,(_,key)=>sampleParams[key]);
    const qs=new URLSearchParams(query).toString();
    examples[op.operationId].scenarios[scenario]={request:{method:op.method,path:actualPath+(qs?'?'+qs:''),headers:{'X-Mock-Scenario':scenario,'X-Mock-Session':'frontend-demo',...(op.admin?{Authorization:'Bearer mock-admin'}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body}:{})},status,response};
  }
}
const source=JSON.parse(await readFile(new URL('./source-screen-requirements.json',import.meta.url),'utf8'));
const coverage=buildCoverage(source,operations);
const clientExamples={stamp:{notStarted:{date:'2030-10-01',started:false,count:0,claimed:false},collecting:{date:'2030-10-01',started:true,count:2,claimed:false},complete:{date:'2030-10-01',started:true,count:4,claimed:false},claimed:{date:'2030-10-01',started:true,count:4,claimed:true},afterMidnight:{date:'2030-10-02',started:false,count:0,claimed:false}},failures:{cameraDenied:'QR을 찍으려면 카메라 접근을 허용해 주세요',wrongQr:'스탬프투어 QR이 아니에요',language:'언어를 변경하지 못했어요. 다시 시도해 주세요'},note:'브라우저 상태 예시이며 HTTP 응답이 아니다. 스탬프 적립·수령 서버 API는 추가하지 않는다.'};
const endpointMarkdown='# API v2 경로·시나리오 목록\n\n자동 생성 문서입니다. 필드별 계약은 [OpenAPI](openapi.json), 요청·응답 원문은 [examples.json](examples.json)을 확인하세요.\n\n| 메서드 | 경로 | 내용 | 화면 | 시나리오 |\n|---|---|---|---|---|\n'+operations.map(op=>`| ${op.method} | \`${op.path}\` | ${op.summary} | ${op.screens.join(', ')} | ${examples[op.operationId]?Object.keys(examples[op.operationId].scenarios).join(', '):''} |`).join('\n')+'\n';
const dataMarkdown=`# 화면 데이터 → API 필드\n\nProduct Context v5 기준 ${coverage.screens.length}개 화면의 데이터 ${coverage.data.filter(d=>d.owner!=='제외').length}개를 추적합니다. 제외 항목은 이력으로 표시합니다. 필드 경로는 응답 data 기준이며 [화면 상태·조회 규칙](SCREEN-STATES.md)을 함께 적용합니다. 이전 Google Sheets 스냅샷은 현재 계약의 기준이 아닙니다.\n\n| 데이터 ID | 항목 | 처리 | 계약/프런트 책임 | 위키 근거 |\n|---|---|---|---|---|\n`+coverage.data.map(d=>`| ${d.id} | ${d.label} | ${d.owner} | ${d.target} | ${'[위키]('+d.wikiSource+')'} |`).join('\n')+'\n';
for(const [name,value]of Object.entries({'openapi.json':spec,'examples.json':examples,'screen-coverage.json':coverage,'client-state-examples.json':clientExamples,'ENDPOINTS.md':endpointMarkdown,'SCREEN-DATA.md':dataMarkdown})){
  const content=typeof value==='string'?value:JSON.stringify(value,null,2)+'\n';const file=new URL(name,import.meta.url);
  if(process.argv.includes('--check')){if(await readFile(file,'utf8')!==content)throw new Error(`${name} is stale. Run npm run generate.`);}else await writeFile(file,content,'utf8');
}
console.log(`API v2: ${operations.length} operations, ${coverage.screens.length} screens, ${coverage.data.length} data mappings, ${Object.values(examples).reduce((n,v)=>n+Object.keys(v.scenarios).length,0)} example responses.`);
