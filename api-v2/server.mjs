import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { createState,execute,ApiFailure,failure,MOCK_NOW,isoKst,scenarioTime } from './domain.mjs';
import { validate } from './validate.mjs';

export async function createMockServer({origins=['http://localhost:3000','http://127.0.0.1:3000','http://localhost:5173','http://127.0.0.1:5173']}={}) {
  const spec=JSON.parse(await readFile(new URL('./openapi.json',import.meta.url),'utf8'));
  const examples=JSON.parse(await readFile(new URL('./examples.json',import.meta.url),'utf8'));
  const sessions=new Map();
  const routes=Object.entries(spec.paths).flatMap(([path,methods])=>Object.entries(methods).map(([method,o])=>({path,method:method.toUpperCase(),definition:o,operationId:o.operationId,input:o.requestBody?.content['application/json'].schema.$ref?.split('/').at(-1),admin:!!o.security?.length,scenarios:o['x-mock-scenarios'],regex:new RegExp('^'+path.replace(/\{\w+\}/g,'([a-z0-9][a-z0-9-]{0,63})')+'$'),keys:[...path.matchAll(/\{(\w+)\}/g)].map(m=>m[1])})));
  const headers={'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Vary':'Origin, X-Mock-Session, X-Mock-Scenario, X-Mock-Time'};
  const server=http.createServer(async(req,res)=>{
    let now=MOCK_NOW,locale='ko',scenario='normal',state=createState();const requestId=randomUUID();
    const meta=()=>({requestId,serverTime:isoKst(now),timezone:'Asia/Seoul',festivalId:'festival-mock',revision:state.revision,locale,mock:true});
    const send=(status,value,extra={})=>{res.writeHead(status,{...headers,'X-Request-Id':requestId,...extra});res.end(JSON.stringify(value));};
    try{
      const origin=req.headers.origin;
      if(origin&&!origins.includes(origin))failure(403,'ORIGIN_NOT_ALLOWED','이 개발 서버에 허용되지 않은 origin입니다.');
      if(origin){res.setHeader('Access-Control-Allow-Origin',origin);res.setHeader('Access-Control-Expose-Headers','X-Request-Id, Retry-After, Location');}
      if(req.method==='OPTIONS'){res.writeHead(204,{...headers,'Access-Control-Allow-Methods':'GET, POST, PUT, DELETE, OPTIONS','Access-Control-Allow-Headers':'Content-Type, Authorization, X-Mock-Session, X-Mock-Scenario, X-Mock-Time, X-Mock-Delay','Access-Control-Max-Age':'600'});return res.end();}
      const url=new URL(req.url,'http://127.0.0.1');
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
      if(route.admin){const token=req.headers.authorization;if(!token||token==='Bearer mock-expired')failure(401,'UNAUTHORIZED','관리자 인증이 필요합니다.');if(token!=='Bearer mock-admin')failure(403,'FORBIDDEN','관리자 권한이 없습니다.');}
      const params=Object.fromEntries(route.keys.map((key,i)=>[key,route.regex.exec(url.pathname)[i+1]]));
      const query={};
      for(const [key,v]of url.searchParams){if(Object.hasOwn(query,key))failure(400,'INVALID_QUERY','중복 쿼리 파라미터입니다.');if(key!=='__scenario'&&!route.definition.parameters.some(p=>p.in==='query'&&p.name===key))failure(400,'INVALID_QUERY','정의되지 않은 쿼리 파라미터입니다.');query[key]=v;}
      for(const p of route.definition.parameters){const value=p.in==='path'?params[p.name]:query[p.name];if(value===undefined){if(p.required)failure(400,'INVALID_QUERY','필수 파라미터가 없습니다.',[{field:p.name,reason:'필수 값입니다.'}]);}else{const issues=validate(p.schema,value,spec,p.name);if(issues.length)failure(400,'INVALID_QUERY','파라미터 형식이 잘못되었습니다.',issues);}}
      locale=query.locale||'ko';
      if(!state.languages.includes(locale)){locale='ko';failure(400,'LOCALE_NOT_READY','준비 완료 언어만 요청할 수 있습니다.');}
      scenario=req.headers['x-mock-scenario']||query.__scenario||'normal';delete query.__scenario;
      if(!route.scenarios.includes(scenario))failure(400,'UNKNOWN_SCENARIO','이 요청에서 지원하지 않는 시나리오입니다.');
      if(req.headers['x-mock-time']){const time=req.headers['x-mock-time'];const issues=validate(spec.components.schemas.Timestamp,time,spec);if(issues.length)failure(400,'INVALID_MOCK_TIME','유효한 KST RFC 3339 시각이 필요합니다.');now=time;}
      now=scenarioTime(scenario,now);
      let body;
      if(route.input){if(!req.headers['content-type']?.startsWith('application/json'))failure(415,'UNSUPPORTED_MEDIA_TYPE','application/json 요청이 필요합니다.');body=await readJson(req);const issues=validate(spec.components.schemas[route.input],body,spec);if(issues.length)failure(422,'VALIDATION_FAILED','요청 필드를 확인해 주세요.',issues);}
      const delay=req.headers['x-mock-delay']||'0';if(!/^\d+$/.test(delay)||Number(delay)>3000)failure(400,'INVALID_MOCK_DELAY','목 지연은 0~3000ms입니다.');
      if(Number(delay))await new Promise(resolve=>setTimeout(resolve,Number(delay)));
      if(scenario==='bad-request')failure(400,'INVALID_QUERY','잘못된 요청 예시입니다.');
      if(scenario==='unauthorized')failure(401,'UNAUTHORIZED','관리자 인증이 필요합니다.');
      if(scenario==='forbidden')failure(403,'FORBIDDEN','관리자 권한이 없습니다.');
      if(scenario==='rate-limited')failure(429,'RATE_LIMITED','잠시 후 다시 요청해 주세요.');
      const result=execute(route,state,{params,query,body,scenario,now});now=result.now;
      const response={data:result.data,meta:meta()};
      const responseSchema=route.definition.responses[result.status].content['application/json'].schema;
      const issues=validate(responseSchema,response,spec);if(issues.length)throw new Error('Response contract mismatch: '+JSON.stringify(issues));
      return send(result.status,response,result.status===201?{Location:`/api/v2/admin/${route.operationId==='postAdminProduct'?'products':'notices'}/${result.data.id}`}:{ });
    }catch(error){
      const known=error instanceof ApiFailure;
      const status=known?error.status:500;
      // Never log requests, tokens, bodies, or personal data.
      if(!known)process.stderr.write('mock internal response/handler failure\n');
      return send(status,{error:{code:known?error.code:'INTERNAL_ERROR',message:known?error.message:'목 서버 처리 중 오류가 발생했습니다.',details:known?error.details:[],retryable:[429,500,503].includes(status)},meta:meta()},status===429?{'Retry-After':'1'}:{});
    }
  });
  server.requestTimeout=10000;server.headersTimeout=5000;
  return server;
}
async function readJson(req){let bytes=0,parts=[];for await(const part of req){bytes+=part.length;if(bytes>65536)failure(413,'PAYLOAD_TOO_LARGE','요청 본문은 64KiB 이하입니다.');parts.push(part);}try{return JSON.parse(Buffer.concat(parts).toString('utf8'));}catch{failure(400,'INVALID_JSON','JSON 형식이 잘못되었습니다.');}}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const port=Number(process.env.MOCK_PORT||4010);if(!Number.isInteger(port)||port<1||port>65535)throw new Error('Invalid MOCK_PORT');
  const options=process.env.MOCK_CORS_ORIGINS?{origins:process.env.MOCK_CORS_ORIGINS.split(',').map(s=>s.trim())}:{};
  const server=await createMockServer(options);
  server.on('error',error=>{process.stderr.write(`목 서버 시작 실패: ${error.code}\n`);process.exitCode=1;});
  server.listen(port,'127.0.0.1',()=>process.stdout.write(`API v2 MOCK (not production): http://127.0.0.1:${port}\n`));
  const stop=()=>{server.close();server.closeAllConnections();};process.on('SIGINT',stop);process.on('SIGTERM',stop);
}
