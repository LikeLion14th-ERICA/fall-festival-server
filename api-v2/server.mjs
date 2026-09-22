import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { createState,execute,ApiFailure,crowdingDayFor,failure,MOCK_NOW,isoKst,scenarioTime } from './domain.mjs';
import { validate } from './validate.mjs';

const KNOWN_LOCALES=new Set(['ko','en','zh-Hans','ja']);
const noBodyStatuses=new Set([204,304]);
const stableJson=value=>{
  if(Array.isArray(value))return `[${value.map(stableJson).join(',')}]`;
  if(value&&typeof value==='object')return `{${Object.keys(value).sort().map(key=>`${JSON.stringify(key)}:${stableJson(value[key])}`).join(',')}}`;
  return JSON.stringify(value);
};
const strongEtag=value=>`"${createHash('sha256').update(stableJson(value)).digest('hex')}"`;
const matchesEtag=(header,current)=>Boolean(header&&header.split(',').some(candidate=>{
  const normalized=candidate.trim();
  return normalized==='*'||(normalized.startsWith('W/')?normalized.slice(2).trim():normalized)===current;
}));

export async function createMockServer({origins=['http://localhost:3000','http://127.0.0.1:3000','http://localhost:5173','http://127.0.0.1:5173']}={}) {
  const spec=JSON.parse(await readFile(new URL('./openapi.json',import.meta.url),'utf8'));
  const examples=JSON.parse(await readFile(new URL('./examples.json',import.meta.url),'utf8'));
  const sessions=new Map();
  const routes=Object.entries(spec.paths).flatMap(([path,methods])=>Object.entries(methods).map(([method,o])=>{const schemes=(o.security||[]).flatMap(requirement=>Object.keys(requirement));const jsonBody=o.requestBody?.content?.['application/json'];return {path,method:method.toUpperCase(),definition:o,operationId:o.operationId,input:jsonBody?.schema?.$ref?.split('/').at(-1),multipart:Boolean(o.requestBody?.content?.['multipart/form-data']),requiresBearer:schemes.includes('AdminBearer'),requiresRefreshCookie:schemes.includes('AdminRefreshCookie'),cookieCsrf:['createAdminSession','refreshAdminSession','deleteCurrentAdminSession'].includes(o.operationId),scenarios:o['x-mock-scenarios'],regex:new RegExp('^'+path.replace(/\{\w+\}/g,'([a-z0-9][a-z0-9-]{0,63})')+'$'),keys:[...path.matchAll(/\{(\w+)\}/g)].map(m=>m[1])};}));
  const headers={'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Vary':'Origin, X-Mock-Session, X-Mock-Scenario, X-Mock-Time'};
  const unscopedOperations=new Set([
    'createAdminSession','refreshAdminSession','deleteCurrentAdminSession','getCurrentAdmin',
    'getCrowding','getAdminCrowding','putAdminCrowding',
    'getNotices','getAdminNotice','getAdminNotices','postAdminNotice','putAdminNotice','deleteAdminNotice',
    'getGoods','getGoodsAvailability','getGood','getGoodAvailability','getPaymentGuide',
    'getAdminGoods','getAdminProducts','getAdminProduct','postAdminProduct','putAdminProduct','deleteAdminProduct','putAdminAvailability',
    'postAdminGoodsImage','getGoodsImage'
  ]);
  const server=http.createServer(async(req,res)=>{
    let now=MOCK_NOW,locale='ko',scenario='normal',state=createState();const requestId=randomUUID();
    const meta=revision=>({requestId,serverTime:isoKst(now),timezone:'Asia/Seoul',festivalId:'festival-mock',revision,locale,mock:true});
    const send=(status,value,extra={})=>{res.writeHead(status,{...headers,'X-Request-Id':requestId,...extra});res.end(noBodyStatuses.has(status)?undefined:JSON.stringify(value));};
    try{
      const origin=req.headers.origin;
      const url=new URL(req.url,'http://127.0.0.1');
      const csrfRoute=routes.find(route=>route.method===req.method&&route.regex.test(url.pathname)&&route.cookieCsrf);
      if(origin&&!origins.includes(origin)){
        if(csrfRoute)failure(403,'ADMIN_CSRF_INVALID','허용되지 않은 관리자 요청 출처입니다.');
        failure(403,'ORIGIN_NOT_ALLOWED','이 개발 서버에 허용되지 않은 origin입니다.');
      }
      if(origin){res.setHeader('Access-Control-Allow-Origin',origin);res.setHeader('Access-Control-Expose-Headers','X-Request-Id, Retry-After, Location, ETag, X-Server-Time');}
      if(req.method==='OPTIONS'){res.writeHead(204,{...headers,'Access-Control-Allow-Methods':'GET, POST, PUT, DELETE, OPTIONS','Access-Control-Allow-Headers':'Content-Type, Authorization, If-Match, Idempotency-Key, If-None-Match, X-Mock-Session, X-Mock-Scenario, X-Mock-Time, X-Mock-Delay','Access-Control-Max-Age':'600'});return res.end();}
      const session=req.headers['x-mock-session']||'default';
      if(!/^[a-zA-Z0-9_-]{1,64}$/.test(session))failure(400,'INVALID_MOCK_SESSION','목 세션은 영숫자·밑줄·하이픈 1~64자입니다.');
      const wall=Date.now();for(const [key,item]of sessions)if(wall-item.used>3600000)sessions.delete(key);
      if(!sessions.has(session)){if(sessions.size>=64)sessions.delete(sessions.keys().next().value);sessions.set(session,{state:createState(),used:wall});}
      const entry=sessions.get(session);state=entry.state;entry.used=wall;
      if(req.method==='GET'&&url.pathname==='/healthz')return send(200,{status:'ok',mock:true});
      if(req.method==='GET'&&url.pathname==='/openapi.json')return send(200,spec);
      if(req.method==='GET'&&url.pathname==='/examples.json')return send(200,examples);
      if(req.method==='GET'&&url.pathname==='/__mock/catalog')return send(200,{baseUrl:`http://127.0.0.1:${server.address().port}`,defaultTime:MOCK_NOW,examples,operations:routes.map(r=>({method:r.method,path:r.path,operationId:r.operationId,scenarios:r.scenarios}))});
      if(req.method==='POST'&&url.pathname==='/__mock/reset'){if(!req.headers['content-type']?.startsWith('application/json'))failure(415,'UNSUPPORTED_MEDIA_TYPE','application/json 요청이 필요합니다.');await readJson(req);sessions.set(session,{state:createState(),used:wall});return send(200,{reset:true,session});}
      if(req.method==='GET'&&url.pathname==='/__mock/assets/sample.svg'){
        const variant=(url.searchParams.get('variant')||'default').replace(/[^a-z0-9-]/gi,'').slice(0,48)||'default';
        const palette=['#dbeafe','#ede9fe','#fce7f3','#dcfce7','#fef3c7','#cffafe'];
        const tone=palette[[...variant].reduce((sum,char)=>sum+char.charCodeAt(0),0)%palette.length];
        res.writeHead(200,{...headers,'Content-Type':'image/svg+xml; charset=utf-8'});return res.end(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 600"><rect width="800" height="600" fill="${tone}"/><circle cx="165" cy="150" r="110" fill="#ffffff" fill-opacity=".38"/><circle cx="645" cy="455" r="150" fill="#ffffff" fill-opacity=".28"/><path d="M0 430C170 350 290 500 420 420S670 350 800 430" fill="none" stroke="#64748b" stroke-opacity=".45" stroke-width="12"/><text x="400" y="278" text-anchor="middle" font-family="sans-serif" font-size="36" fill="#334155">MOCK ASSET</text><text x="400" y="338" text-anchor="middle" font-family="sans-serif" font-size="22" fill="#475569">Fictional development image</text></svg>`);
      }
      if(req.method==='GET'&&url.pathname==='/'){res.writeHead(200,{...headers,'Content-Type':'text/html; charset=utf-8'});return res.end(await readFile(new URL('./index.html',import.meta.url)));}
      const pathRoutes=routes.filter(r=>r.regex.test(url.pathname));
      const route=pathRoutes.find(r=>r.method===req.method);
      if(!route){if(pathRoutes.length){res.setHeader('Allow',pathRoutes.map(r=>r.method).join(', '));failure(405,'METHOD_NOT_ALLOWED','지원하지 않는 메서드입니다.');}failure(404,'NOT_FOUND','경로가 없습니다.');}
      if(route.cookieCsrf&&!origin)failure(403,'ADMIN_CSRF_INVALID','허용되지 않은 관리자 요청 출처입니다.');
      if(route.requiresBearer){const token=req.headers.authorization;if(!token||token==='Bearer mock-expired')failure(401,'UNAUTHORIZED','관리자 인증이 필요합니다.');if(token!=='Bearer mock-admin')failure(403,'FORBIDDEN','관리자 권한이 없습니다.');}
      if(route.requiresRefreshCookie&&!req.headers.cookie?.includes('__Host-festival-admin-refresh=MOCK-OPAQUE-REFRESH-TOKEN'))failure(401,'ADMIN_REFRESH_TOKEN_INVALID','관리자 세션을 갱신할 수 없습니다.');
      const params=Object.fromEntries(route.keys.map((key,i)=>[key,route.regex.exec(url.pathname)[i+1]]));
      const query={};
      for(const [key,v]of url.searchParams){if(Object.hasOwn(query,key))failure(400,'INVALID_QUERY','중복 쿼리 파라미터입니다.');if(key!=='__scenario'&&!route.definition.parameters.some(p=>p.in==='query'&&p.name===key))failure(400,'INVALID_QUERY','정의되지 않은 쿼리 파라미터입니다.');query[key]=v;}
      for(const p of route.definition.parameters){
        const value=p.in==='path'?params[p.name]:p.in==='query'?query[p.name]:req.headers[p.name.toLowerCase()];
        if(value===undefined){
          if(p.required){
            if(p.name==='If-Match')failure(428,'PRECONDITION_REQUIRED','최신 상태를 확인한 뒤 다시 저장해 주세요.');
            if(p.name==='Idempotency-Key')failure(428,'IDEMPOTENCY_KEY_REQUIRED','Idempotency-Key 헤더가 필요합니다.');
            failure(400,p.in==='header'?'INVALID_HEADER':'INVALID_QUERY','필수 요청 값이 없습니다.',[{field:p.name,reason:'필수 값입니다.'}]);
          }
        }else{
          const issues=validate(p.schema,value,spec,p.name);
          if(issues.length&&route.operationId==='getGoodsImage'&&p.name==='variant')failure(404,'NOT_FOUND','요청한 정보를 찾을 수 없습니다.');
          if(issues.length)failure(400,p.name==='If-Match'?'INVALID_IF_MATCH':p.name==='Idempotency-Key'?'INVALID_IDEMPOTENCY_KEY':p.in==='header'?'INVALID_HEADER':'INVALID_QUERY','요청 값 형식이 잘못되었습니다.',issues);
        }
      }
      scenario=req.headers['x-mock-scenario']||query.__scenario||'normal';delete query.__scenario;
      if(!route.scenarios.includes(scenario))failure(400,'UNKNOWN_SCENARIO','이 요청에서 지원하지 않는 시나리오입니다.');
      if(scenario==='all-languages')state.languages=['ko','en','zh-Hans','ja'];
      locale=query.locale||'ko';
      if(!state.languages.includes(locale)){
        const known=KNOWN_LOCALES.has(locale);locale='ko';
        failure(400,known?'LOCALE_NOT_READY':'INVALID_QUERY',known?'준비 완료 언어만 요청할 수 있습니다.':'요청 파라미터를 확인해 주세요.');
      }
      if(req.headers['x-mock-time']){const time=req.headers['x-mock-time'];const issues=validate(spec.components.schemas.Timestamp,time,spec);if(issues.length)failure(400,'INVALID_MOCK_TIME','유효한 KST RFC 3339 시각이 필요합니다.');now=time;}
      now=scenarioTime(scenario,now);
      let body;
      if(route.input){if(!req.headers['content-type']?.startsWith('application/json'))failure(415,'UNSUPPORTED_MEDIA_TYPE','application/json 요청이 필요합니다.');body=await readJson(req);const issues=validate(spec.components.schemas[route.input],body,spec);if(issues.length&&route.operationId==='verifyStampReceipt')failure(422,'INVALID_RECEIPT_CODE','수령 인증 코드를 확인해 주세요.');if(issues.length&&route.operationId==='collectStamp')failure(422,'INVALID_STAMP_TOKEN','스탬프투어 QR이 아니에요.');if(issues.length)failure(422,'VALIDATION_FAILED','요청 필드를 확인해 주세요.',issues);}
      if(route.multipart){if(!req.headers['content-type']?.startsWith('multipart/form-data'))failure(415,'UNSUPPORTED_MEDIA_TYPE','multipart/form-data 요청이 필요합니다.');await drainMultipart(req);}
      const delay=req.headers['x-mock-delay']||'0';if(!/^\d+$/.test(delay)||Number(delay)>3000)failure(400,'INVALID_MOCK_DELAY','목 지연은 0~3000ms입니다.');
      if(Number(delay))await new Promise(resolve=>setTimeout(resolve,Number(delay)));
      if(scenario==='bad-request')failure(400,'INVALID_QUERY','잘못된 요청 예시입니다.');
      if(scenario==='unauthorized')failure(401,'UNAUTHORIZED','관리자 인증이 필요합니다.');
      if(scenario==='forbidden')failure(403,'FORBIDDEN','관리자 권한이 없습니다.');
      if(scenario==='rate-limited')failure(429,'RATE_LIMITED','잠시 후 다시 요청해 주세요.');
      if(scenario==='precondition-required')failure(428,'PRECONDITION_REQUIRED','최신 상태를 확인한 뒤 다시 저장해 주세요.');
      if(scenario==='idempotency-key-required')failure(428,'IDEMPOTENCY_KEY_REQUIRED','Idempotency-Key 헤더가 필요합니다.');
      if(scenario==='invalid-media-reference')failure(422,'INVALID_MEDIA_REFERENCE','사용할 수 없는 상품 이미지가 포함되어 있습니다.');
      if(scenario==='validation-failed')failure(422,'VALIDATION_FAILED','요청 파일을 확인해 주세요.');
      if(scenario==='payload-too-large')failure(413,'PAYLOAD_TOO_LARGE','업로드 파일은 10 MiB 이하여야 합니다.');
      if(scenario==='unsupported-media-type')failure(415,'UNSUPPORTED_MEDIA_TYPE','multipart/form-data 요청이 필요합니다.');
      if(route.operationId==='postAdminGoodsImage'&&scenario==='error')failure(503,'SERVICE_UNAVAILABLE','일시적으로 이미지를 처리할 수 없습니다.');
      if(route.operationId==='getGoodsImage'&&scenario==='error')failure(503,'SERVICE_UNAVAILABLE','일시적으로 이미지를 처리할 수 없습니다.');
      if(scenario==='edit-conflict')failure(409,'EDIT_CONFLICT','다른 관리자가 먼저 변경했습니다. 최신 상태를 확인해 주세요.');
      let result;
      if(route.operationId==='putAdminCrowding'){
        const key=req.headers['idempotency-key'];
        const fingerprint=stableJson({
          operatingDay:crowdingDayFor(now),
          payload:{level:body.level,confirmFull:body.confirmFull===true},
        });
        const prior=state.idempotency[key];
        if(prior){
          if(prior.fingerprint!==fingerprint)failure(409,'IDEMPOTENCY_KEY_REUSED','같은 Idempotency-Key를 다른 요청에 사용할 수 없습니다.');
          result={status:prior.status,data:null,now,locale};
        }else{
          result=execute(route,state,{params,query,body,scenario,now});
          if(result.status>=200&&result.status<300)state.idempotency[key]={fingerprint,status:result.status};
        }
      }else{
        result=execute(route,state,{params,query,body,scenario,now});
      }
      now=result.now;
      if(route.definition['x-binary-response']){
        const etag=strongEtag(`goods-media-v1\n${params.mediaId}\n${params.variant}`);
        const mediaHeaders={
          ...headers,
          'Content-Type':'image/webp',
          'Content-Disposition':'inline',
          'X-Content-Type-Options':'nosniff',
          'Cache-Control':'public, max-age=31536000, immutable',
          'ETag':etag,
          'X-Request-Id':requestId,
        };
        if(matchesEtag(req.headers['if-none-match'],etag)){
          res.writeHead(304,mediaHeaders);return res.end();
        }
        res.writeHead(200,mediaHeaders);return res.end(Buffer.from(`MOCK-WEBP:${params.mediaId}:${params.variant}`));
      }
      const responseRevision=unscopedOperations.has(route.operationId)?0:state.revision;
      const responseMeta=route.definition['x-conditional']||route.definition['x-conditional-meta']
        ? {timezone:'Asia/Seoul',festivalId:'festival-mock',revision:responseRevision,locale,mock:true}
        : meta(responseRevision);
      const response=noBodyStatuses.has(result.status)?null:{data:result.data,meta:responseMeta};
      let responseStatus=result.status;
      const locationResource=route.operationId==='postAdminProduct'?'products':route.operationId==='postAdminNotice'?'notices':null;
      const extra=result.status===201&&locationResource?{Location:`/api/v2/admin/${locationResource}/${result.data.id}`}:{ };
      if(route.definition.parameters.some(parameter=>parameter.name==='If-None-Match')&&result.status===200){
        const etag=strongEtag({data:response.data,meta:{timezone:response.meta.timezone,festivalId:response.meta.festivalId,revision:response.meta.revision,locale:response.meta.locale,mock:response.meta.mock}});
        extra.ETag=etag;
        extra['X-Server-Time']=meta(0).serverTime;
        const declaredCacheControl=route.definition.responses['200']?.headers?.['Cache-Control']?.schema?.enum?.[0];
        if(declaredCacheControl)extra['Cache-Control']=declaredCacheControl;
        if(matchesEtag(req.headers['if-none-match'],etag))responseStatus=304;
      }
      const responseDefinition=route.definition.responses[responseStatus];
      if(responseDefinition?.content){
        const responseSchema=responseDefinition.content['application/json'].schema;
        const issues=validate(responseSchema,response,spec);if(issues.length)throw new Error('Response contract mismatch: '+JSON.stringify(issues));
      }
      if(['createAdminSession','refreshAdminSession'].includes(route.operationId))extra['Set-Cookie']='__Host-festival-admin-refresh=MOCK-OPAQUE-REFRESH-TOKEN; Path=/; Secure; HttpOnly; SameSite=Strict';
      if(route.operationId==='deleteCurrentAdminSession')extra['Set-Cookie']='__Host-festival-admin-refresh=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict';
      return send(responseStatus,responseStatus===304?null:response,extra);
    }catch(error){
      const known=error instanceof ApiFailure;
      const status=known?error.status:500;
      // Never log requests, tokens, bodies, or personal data.
      if(!known)process.stderr.write('mock internal response/handler failure\n');
      return send(status,{error:{code:known?error.code:'INTERNAL_ERROR',message:known?error.message:'목 서버 처리 중 오류가 발생했습니다.',details:known?error.details:[],retryable:[429,500,503].includes(status)},meta:meta(0)},status===429?{'Retry-After':'1'}:{});
    }
  });
  server.requestTimeout=10000;server.headersTimeout=5000;
  return server;
}
async function readJson(req){let bytes=0,parts=[];for await(const part of req){bytes+=part.length;if(bytes>65536)failure(413,'PAYLOAD_TOO_LARGE','요청 본문은 64KiB 이하입니다.');parts.push(part);}try{return JSON.parse(Buffer.concat(parts).toString('utf8'));}catch{failure(400,'INVALID_JSON','JSON 형식이 잘못되었습니다.');}}
async function drainMultipart(req){let bytes=0;for await(const part of req){bytes+=part.length;if(bytes>12582912)failure(413,'PAYLOAD_TOO_LARGE','업로드 요청은 12 MiB 이하여야 합니다.');}if(bytes===0)failure(422,'VALIDATION_FAILED','요청 파일을 확인해 주세요.');}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const port=Number(process.env.MOCK_PORT||4010);if(!Number.isInteger(port)||port<1||port>65535)throw new Error('Invalid MOCK_PORT');
  const options=process.env.MOCK_CORS_ORIGINS?{origins:process.env.MOCK_CORS_ORIGINS.split(',').map(s=>s.trim())}:{};
  const server=await createMockServer(options);
  server.on('error',error=>{process.stderr.write(`목 서버 시작 실패: ${error.code}\n`);process.exitCode=1;});
  server.listen(port,'127.0.0.1',()=>process.stdout.write(`API v2 MOCK (not production): http://127.0.0.1:${port}\n`));
  const stop=()=>{server.close();server.closeAllConnections();};process.on('SIGINT',stop);process.on('SIGTERM',stop);
}
