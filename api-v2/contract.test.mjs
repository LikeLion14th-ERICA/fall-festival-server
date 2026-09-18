import test,{after,before} from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import SwaggerParser from '@apidevtools/swagger-parser';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { createMockServer } from './server.mjs';
import { validate as localValidate } from './validate.mjs';
import { createState,DATES } from './domain.mjs';

const read=name=>readFile(new URL(name,import.meta.url),'utf8').then(JSON.parse);
const spec=await read('./openapi.json'),examples=await read('./examples.json'),coverage=await read('./screen-coverage.json'),clientStates=await read('./client-state-examples.json');
const ajv=new Ajv2020({strict:false,allErrors:true});addFormats(ajv);
const compiled=new Map();
function standardValidate(schema,value){const key=JSON.stringify(schema);if(!compiled.has(key))compiled.set(key,ajv.compile({...schema,components:spec.components}));const validate=compiled.get(key);assert.equal(validate(value),true,JSON.stringify(validate.errors));}
let server,base;
before(async()=>{server=await createMockServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));base=`http://127.0.0.1:${server.address().port}`;});
after(async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));});
async function call(path,{method='GET',body,headers={},session='test'}={}){const response=await fetch(base+path,{method,headers:{'X-Mock-Session':session,...(body!==undefined?{'Content-Type':'application/json'}:{}),...headers},...(body!==undefined?{body:typeof body==='string'?body:JSON.stringify(body)}:{})});const json=[204,304].includes(response.status)?null:await response.json();return {status:response.status,body:json,headers:response.headers};}
async function enableAllMockLocales(session){
  const response=await call('/api/v2/config',{session,headers:{'X-Mock-Scenario':'all-languages'}});
  assert.equal(response.status,200);
  assert.deepEqual(response.body.data.languages.map(language=>language.code),['ko','en','zh-Hans','ja']);
}
const admin={Authorization:'Bearer mock-admin'};
const operation=id=>Object.values(spec.paths).flatMap(Object.values).find(o=>o.operationId===id);

test('OpenAPI 3.1 document passes standard parser validation',async()=>{await SwaggerParser.validate(structuredClone(spec));});
test('Meta revision distinguishes aligned content from unscoped and error responses',()=>{
  const metaSchema={$ref:'#/components/schemas/Meta'};
  const baseMeta={requestId:'revision-contract',serverTime:'2030-10-01T12:00:00+09:00',timezone:'Asia/Seoul',festivalId:'festival-mock',locale:'ko',mock:true};
  standardValidate(metaSchema,{...baseMeta,revision:0});
  const validateNegative=ajv.compile({...metaSchema,components:spec.components});
  assert.equal(validateNegative({...baseMeta,revision:-1}),false);
  const unscopedOperations=new Set([
    'createAdminSession','refreshAdminSession','deleteCurrentAdminSession','getCurrentAdmin',
    'getCrowding','getAdminCrowding','putAdminCrowding',
    'getNotices','getAdminNotice','getAdminNotices','postAdminNotice','putAdminNotice','deleteAdminNotice',
    'getGoods','getGoodsAvailability','getGood','getGoodAvailability','getPaymentGuide',
    'getAdminGoods','getAdminProducts','getAdminProduct','postAdminProduct','putAdminProduct','deleteAdminProduct','putAdminAvailability'
  ]);
  for(const [operationId,group]of Object.entries(examples))for(const example of Object.values(group.scenarios)){
    if(example.status>=400||unscopedOperations.has(operationId))assert.equal(example.response?.meta?.revision??0,0,operationId);
    else if(example.response)assert.ok(example.response.meta.revision>=1,operationId);
  }
  assert.ok(examples.getStampGuide.scenarios.normal.response.meta.revision>=1);
  assert.ok(examples.getTicketGuide.scenarios.normal.response.meta.revision>=1);
  assert.equal(examples.getCrowding.scenarios.normal.response.meta.revision,0);
});
test('26 screens and 177 active data items plus 10 retired items are covered without duplicate IDs',()=>{assert.equal(coverage.screens.length,26);assert.equal(coverage.data.length,187);assert.equal(new Set(coverage.data.map(x=>x.id)).size,187);for(const s of coverage.screens)assert.ok(s.operations.length>0);assert.ok(coverage.data.filter(x=>x.owner==='브라우저').length>=10);assert.ok(!coverage.data.some(x=>x.id==='ADM-NOTICE-EDIT-D04'));assert.equal(coverage.data.find(d=>d.id==='STAMP-REWARD-D01').label,'담당자 제시·수령 인증 코드 입력 안내');assert.equal(coverage.data.find(d=>d.id==='STAMP-REWARD-D02').target,'StampReceiptVerificationInput.code → StampReceiptVerification.verified');});
test('Public routes stay anonymous and admin routes require the documented bearer/cookie credential',()=>{for(const [path,methods]of Object.entries(spec.paths))for(const o of Object.values(methods)){const isLogin=o.operationId==='createAdminSession';assert.equal(o.security.length>0,path.includes('/admin/')&&!isLogin);}});
test('No out-of-scope payment, user identity, FAQ or performance admin route',()=>{const paths=Object.keys(spec.paths).join(' ');assert.doesNotMatch(paths,/\/orders|\/payments|\/users|\/login|\/faq|\/admin\/performances/);assert.match(paths,/\/stamp-receipt-verifications/);});
test('FAQ is an external config link and direct QR before START stays in local start state',async()=>{
  const unconfigured=(await call('/api/v2/config')).body.data;assert.equal(unconfigured.links.faq,null);
  const ready=(await call('/api/v2/config',{headers:{'X-Mock-Scenario':'faq-ready'}})).body.data.links.faq;
  assert.equal(ready.target,'_blank');assert.match(ready.url,/^https:\/\//);
  assert.deepEqual(clientStates.stamp.directQrBeforeStart,{date:'2030-10-01',started:false,count:0,claimed:false,route:'STAMP-START',startRecorded:false,stampAdded:false});
});
test('Stamp receipt verification hides the code and changes claimed only after success',async()=>{
  const guide=(await call('/api/v2/stamp-guide')).body.data;assert.doesNotMatch(JSON.stringify(guide),/482913/);
  assert.equal(spec.components.schemas.StampReceiptVerificationInput.properties.code.writeOnly,true);assert.doesNotMatch(JSON.stringify(clientStates),/482913/);
  const invalid=await call('/api/v2/stamp-receipt-verifications',{method:'POST',body:{code:'wrong-code'},session:'stamp-receipt'});
  assert.equal(invalid.status,422);assert.equal(invalid.body.error.code,'INVALID_RECEIPT_CODE');assert.doesNotMatch(JSON.stringify(invalid.body),/wrong-code/);
  const verified=await call('/api/v2/stamp-receipt-verifications',{method:'POST',body:{code:'482913'},session:'stamp-receipt'});
  assert.equal(verified.status,200);assert.deepEqual(verified.body.data,{verified:true});
  for(const code of ['48291','4829130','48291a','482 913'])assert.equal((await call('/api/v2/stamp-receipt-verifications',{method:'POST',body:{code},session:'stamp-receipt'})).status,422);
  assert.equal(spec.components.schemas.StampReceiptVerificationInput.properties.code.pattern,'^[0-9]{6}$');
  assert.deepEqual(clientStates.stamp.receiptCodeRejected,{date:'2030-10-01',started:true,count:4,claimed:false,route:'STAMP-REWARD',message:'코드를 확인해 주세요'});
  assert.deepEqual(clientStates.stamp.claimed,{date:'2030-10-01',started:true,count:4,claimed:true});
});

test('Fictional fixtures provide dense, linked data for frontend list and detail layouts',()=>{
  const state=createState(),artistById=new Map(state.artists.map(artist=>[artist.id,artist]));
  assert.equal(state.artists.length,24);
  assert.equal(state.artists.filter(artist=>artist.category==='ARTIST').length,15);
  assert.equal(state.artists.filter(artist=>artist.category==='CONTEST').length,9);
  assert.ok(state.artists.every(artist=>artist.songs.length<=3));
  assert.equal(state.performances.length,24);
  for(const date of DATES){
    const day=state.performances.filter(performance=>performance.date===date);
    assert.equal(day.filter(performance=>artistById.get(performance.artists[0].id).category==='ARTIST').length,5);
    assert.equal(day.filter(performance=>artistById.get(performance.artists[0].id).category==='CONTEST').length,3);
  }
  assert.equal(state.spaces.length,34);
  assert.equal(state.spaces.filter(space=>space.category==='BOOTH').length,12);
  assert.equal(state.spaces.filter(space=>space.category==='PUB').length,10);
  assert.equal(state.spaces.filter(space=>space.category==='FLEA_MARKET').length,8);
  assert.equal(state.spaces.filter(space=>space.category==='FOOD_TRUCK').length,2);
  assert.equal(state.spaces.filter(space=>space.category==='STUDENT_COUNCIL_BOOTH').length,1);
  assert.equal(state.spaces.filter(space=>space.category==='PROMOTION_BOOTH').length,1);
  assert.equal(state.spaces.flatMap(space=>space.menu).length,56);
  assert.equal(state.maps.length,7);
  assert.equal(Object.values(state.pins).flat().length,53);
  assert.equal(state.places.length,47);
  assert.equal(state.notices.length,10);
});

test('Fictional image variants are served from the local mock asset route',async()=>{
  const state=createState();
  const urls=[
    ...state.artists.map(artist=>artist.image.url),
    ...state.spaces.map(space=>space.image.url),
    ...state.maps.map(map=>map.image.url),
  ];
  for(const url of new Set(urls)){
    const response=await fetch(new URL(url,base));
    assert.equal(response.status,200);
    assert.match(response.headers.get('content-type'),/^image\/svg\+xml/);
    assert.match(await response.text(),/Fictional development image/);
  }
});

for(const [opId,group]of Object.entries(examples))for(const [scenario,example]of Object.entries(group.scenarios)){
  test(`${opId}: ${scenario} returns the documented HTTP/schema/body`,async()=>{
    const req=example.request;
    const got=await call(req.path,{method:req.method,body:req.body,headers:{...req.headers,'X-Mock-Session':opId+'-'+scenario}});
    assert.equal(got.status,example.status,JSON.stringify(got.body));
    if(got.status===204||got.status===304)assert.equal(got.body,null);
    else standardValidate(operation(opId).responses[got.status].content['application/json'].schema,got.body);
    if(example.status===204||example.status===304)assert.equal(example.response,null);
    else standardValidate(operation(opId).responses[example.status].content['application/json'].schema,example.response);
    if(got.body && !operation(opId)['x-conditional'])assert.equal(got.headers.get('x-request-id'),got.body.meta.requestId);
    if(got.body){
      assert.equal(got.body.meta.mock,true);
      if(operation(opId)['x-conditional']&&got.status===200){
        assert.match(got.headers.get('x-request-id'),/^[0-9a-f-]{36}$/);
        assert.ok(got.headers.get('x-server-time'));
        assert.deepEqual(got.body,example.response);
      }else assert.deepEqual({...got.body,meta:{...got.body.meta,requestId:'mock-example-request'}},example.response);
    }
  });
}
test('Invalid input is rejected, not reflected into a success fixture',async()=>{
  const crowdingHeaders={...admin,'If-Match':'"'+'0'.repeat(64)+'"','Idempotency-Key':'invalid-input'};
  const cases=[['/api/v2/lineup?date=2030-02-30',{},400],['/api/v2/lineup?category=INVALID',{},400],['/api/v2/spaces?search=x',{},400],['/api/v2/spaces?category=PUB&category=BOOTH',{},400],['/api/v2/maps/map-area/pins',{},400],['/api/v2/maps/map-area/pins?mapVersion=old',{},409],['/api/v2/goods/unknown',{},404],['/api/v2/config?locale=ja',{},400],['/api/v2/config?locale=zh-Hans',{},400],['/api/v2/config?__scenario=unknown',{},400],['/api/v2/admin/crowding',{method:'PUT',body:{level:'BOGUS'},headers:crowdingHeaders},422],['/api/v2/admin/crowding',{method:'PUT',body:{level:'FULL'},headers:crowdingHeaders},422],['/api/v2/admin/crowding',{method:'PUT',body:{level:'CROWDED',extra:true},headers:crowdingHeaders},422],['/api/v2/admin/crowding',{method:'PUT',body:'{bad',headers:crowdingHeaders},400],['/api/v2/admin/crowding',{method:'PUT',body:{level:'CROWDED'},headers:{...crowdingHeaders,'Content-Type':'text/plain'}},415],['/api/v2/config',{headers:{'X-Mock-Delay':'3001'}},400]];
  for(const [path,opts,status]of cases){const got=await call(path,opts);assert.equal(got.status,status,path);standardValidate({$ref:'#/components/schemas/Error'},got.body);}
});
test('Mock administrator authorization is checked server-side for every admin method',async()=>{
  for(const [path,methods]of Object.entries(spec.paths))if(path.includes('/admin/'))for(const [method,o]of Object.entries(methods)){
    const sample=examples[o.operationId].scenarios.normal.request.path;
    if(o.operationId==='createAdminSession')continue;
    if(o.operationId==='refreshAdminSession'){assert.equal((await call(sample,{method:method.toUpperCase(),headers:{Origin:'http://localhost:5173'}})).status,401);continue;}
    const cookieHeaders=['refreshAdminSession','deleteCurrentAdminSession'].includes(o.operationId)?{Origin:'http://localhost:5173',Cookie:'__Host-festival-admin-refresh=MOCK-OPAQUE-REFRESH-TOKEN'}:{};
    assert.equal((await call(sample,{method:method.toUpperCase(),headers:cookieHeaders})).status,401);
    assert.equal((await call(sample,{method:method.toUpperCase(),headers:{...cookieHeaders,Authorization:'Bearer mock-viewer'}})).status,403);
  }
});
test('Admin cookie endpoints use the CSRF error contract and logout stays idempotent without a cookie',async()=>{
  const loginBody={username:'mock-admin',password:'MOCK-NOT-A-REAL-SECRET'};
  const missing=await call('/api/v2/admin/sessions',{method:'POST',body:loginBody});
  assert.equal(missing.status,403);assert.equal(missing.body.error.code,'ADMIN_CSRF_INVALID');
  const suffix=await call('/api/v2/admin/sessions',{method:'POST',body:loginBody,headers:{Origin:'http://localhost:5173.attacker.com'}});
  assert.equal(suffix.status,403);assert.equal(suffix.body.error.code,'ADMIN_CSRF_INVALID');
  const exact=await call('/api/v2/admin/sessions',{method:'POST',body:loginBody,headers:{Origin:'http://localhost:5173'}});
  assert.equal(exact.status,200);
  const logout=await call('/api/v2/admin/sessions/current',{method:'DELETE',headers:{...admin,Origin:'http://localhost:5173'}});
  assert.equal(logout.status,200);assert.equal(logout.body.data.loggedOut,true);assert.match(logout.headers.get('set-cookie'),/Max-Age=0/);
  assert.deepEqual(operation('deleteCurrentAdminSession').security,[{AdminBearer:[]}]);
});
test('Crowding no-op, FULL confirmation, day boundary, restoration, shared read and session isolation',async()=>{
  const session='crowding-flow',initial=await call('/api/v2/crowding',{session});
  const current=await call('/api/v2/admin/crowding',{session,headers:admin});
  const write=(body,extra={})=>call('/api/v2/admin/crowding',{session,method:'PUT',headers:{...admin,'If-Match':current.headers.get('etag'),'Idempotency-Key':`crowding-${Math.random()}`,...extra},body});
  const noop=await write({level:'MODERATE'});
  assert.equal(noop.status,204);
  assert.equal((await call('/api/v2/crowding',{session})).body.data.updatedAt,initial.body.data.updatedAt);
  assert.equal((await write({level:'FULL'})).status,422);
  const freshAdmin=await call('/api/v2/admin/crowding',{session,headers:admin});
  const full=await call('/api/v2/admin/crowding',{session,method:'PUT',headers:{...admin,'If-Match':freshAdmin.headers.get('etag'),'Idempotency-Key':'crowding-full'},body:{level:'FULL',confirmFull:true}});assert.equal(full.status,204);
  const replay=await call('/api/v2/admin/crowding',{session,method:'PUT',headers:{...admin,'If-Match':'"'+'f'.repeat(64)+'"','Idempotency-Key':'crowding-full'},body:{level:'FULL',confirmFull:true}});assert.equal(replay.status,204);
  assert.equal((await call('/api/v2/crowding',{session})).body.data.status,'FULL');
  assert.equal((await call('/api/v2/crowding',{session:'separate-client'})).body.data.status,'MODERATE');
  const closed=await call('/api/v2/crowding',{session,headers:{'X-Mock-Time':'2030-10-01T23:00:00+09:00'}});assert.equal(closed.body.data.status,'CLOSED');assert.equal(closed.body.data.timeBasis,'NONE');assert.equal(closed.body.data.updatedAt,null);
  const closedAdmin=await call('/api/v2/admin/crowding',{session,headers:{...admin,'X-Mock-Time':'2030-10-01T23:00:00+09:00'}});
  const closedSave=await call('/api/v2/admin/crowding',{session,method:'PUT',headers:{...admin,'If-Match':closedAdmin.headers.get('etag'),'Idempotency-Key':'crowding-closed','X-Mock-Time':'2030-10-01T23:00:00+09:00'},body:{level:'CROWDED'}});
  assert.equal(closedSave.status,204);
  const closedState=await call('/api/v2/admin/crowding',{session,headers:{...admin,'X-Mock-Time':'2030-10-01T23:00:00+09:00'}});
  assert.equal(closedState.body.data.status,'CLOSED');assert.equal(closedState.body.data.savedLevel,'CROWDED');assert.equal(closedState.body.data.updatedAt,null);
  const closedNoop=await call('/api/v2/admin/crowding',{session,method:'PUT',headers:{...admin,'If-Match':closedState.headers.get('etag'),'Idempotency-Key':'crowding-closed-noop','X-Mock-Time':'2030-10-01T23:00:00+09:00'},body:{level:'CROWDED'}});
  assert.equal(closedNoop.status,204);
  const reopened=await call('/api/v2/crowding',{session,headers:{'X-Mock-Time':'2030-10-01T21:00:00+09:00'}});assert.equal(reopened.body.data.status,'CROWDED');assert.ok(reopened.body.data.updatedAt);
  const midnight=await call('/api/v2/crowding',{session,headers:{'X-Mock-Time':'2030-10-02T00:00:00+09:00'}});assert.equal(midnight.body.data.savedLevel,null);assert.equal(midnight.body.data.updatedAt,null);
  const next=await call('/api/v2/crowding',{session,headers:{'X-Mock-Time':'2030-10-02T13:00:00+09:00'}});assert.equal(next.body.data.status,'RELAXED');assert.equal(next.body.data.timeBasis,'OPENING');assert.equal(next.body.data.savedLevel,null);
  const outsideSession='crowding-outside-festival-day';
  const outsideAdmin=await call('/api/v2/admin/crowding',{session:outsideSession,headers:{...admin,'X-Mock-Time':'2030-09-30T10:00:00+09:00'}});
  const outsideSave=await call('/api/v2/admin/crowding',{session:outsideSession,method:'PUT',headers:{...admin,'If-Match':outsideAdmin.headers.get('etag'),'Idempotency-Key':'crowding-outside','X-Mock-Time':'2030-09-30T10:00:00+09:00'},body:{level:'CROWDED'}});
  assert.equal(outsideSave.status,409);assert.equal(outsideSave.body.error.code,'NOT_FESTIVAL_DAY');
  const festivalOpening=await call('/api/v2/crowding',{session:outsideSession,headers:{'X-Mock-Time':'2030-10-01T14:00:00+09:00'}});
  assert.equal(festivalOpening.body.data.status,'MODERATE');
});
test('Crowding conditional reads return an ETag and 304 without a body',async()=>{
  const first=await call('/api/v2/crowding',{session:'crowding-conditional'});
  assert.match(first.headers.get('etag'),/^"[0-9a-f]{64}"$/);
  const second=await call('/api/v2/crowding',{session:'crowding-conditional',headers:{'If-None-Match':first.headers.get('etag')}});
  assert.equal(second.status,304);assert.equal(second.body,null);assert.equal(second.headers.get('etag'),first.headers.get('etag'));
});
test('Goods save changes only one combination and derives sold-out; failed writes do not mutate',async()=>{
  const session='goods-flow',path='/api/v2/admin/goods/goods-shirt/combinations/combo-shirt-a-m/availability';
  const write=(status,extra={})=>call(path,{method:'PUT',session,headers:{...admin,'Idempotency-Key':`goods-flow-${Math.random()}`,...extra},body:{status}});
  const before=await call('/api/v2/goods/goods-shirt/availability',{session});
  await write('SOLD_OUT',{'X-Mock-Scenario':'error'});
  assert.deepEqual((await call('/api/v2/goods/goods-shirt/availability',{session})).body.data,before.body.data);
  assert.equal((await write('SOLD_OUT')).status,200);
  const sold=(await call('/api/v2/goods/goods-shirt/availability',{session})).body.data;
  assert.equal(sold.allSoldOut,true);
  assert.equal(sold.combinations.find(c=>c.combinationId==='combo-shirt-a-m').status,'SOLD_OUT');
  assert.equal((await call('/api/v2/goods',{session})).body.data.items.length,1);
  assert.ok((await call('/api/v2/goods/goods-shirt/payment-guide',{session})).body.data.account);
  await write('ON_SALE');
  assert.equal((await call('/api/v2/goods/goods-shirt/availability',{session})).body.data.allSoldOut,false);
});
test('Notice create/edit/delete synchronizes public list, contentLocale fallback and immutable template',async()=>{
  const session='notice-flow';
  const template=(await call('/api/v2/admin/notice-templates/template-1',{session,headers:admin})).body.data;
  const body={...examples.postAdminNotice.scenarios.normal.request.body,templateId:'template-1'};
  const created=await call('/api/v2/admin/notices',{session,headers:{...admin,'Idempotency-Key':'notice-create'},method:'POST',body});
  assert.equal(created.status,201);assert.ok(created.headers.get('location'));
  const id=created.body.data.id;
  assert.ok((await call('/api/v2/notices',{session})).body.data.visibleIds.includes(id));
  const enView=(await call('/api/v2/notices?locale=en',{session})).body.data.items.find(n=>n.id===id);
  assert.equal(enView.contentLocale,'en');assert.equal(enView.title,body.translations.en.title);
  const zhBefore=(await call('/api/v2/notices?locale=zh-Hans',{session})).body.data.items.find(n=>n.id===id);
  assert.equal(zhBefore.contentLocale,'en');
  const current=await call('/api/v2/admin/notices/'+id,{session,headers:admin});
  const updatedBody={
    ...body,
    translations:{...body.translations,'zh-Hans':{title:'模拟标题',body:'模拟正文'}},
    links:body.links.map(l=>({...l,labels:{...l.labels,'zh-Hans':'示例链接'}})),
  };
  const updated=await call('/api/v2/admin/notices/'+id,{session,method:'PUT',headers:{...admin,'If-Match':current.headers.get('etag'),'Idempotency-Key':'notice-update'},body:updatedBody});
  assert.equal(updated.status,200);
  const zhAfter=(await call('/api/v2/notices?locale=zh-Hans',{session})).body.data.items.find(n=>n.id===id);
  assert.equal(zhAfter.contentLocale,'zh-Hans');assert.equal(zhAfter.title,'模拟标题');
  assert.deepEqual((await call('/api/v2/admin/notice-templates/template-1',{session,headers:admin})).body.data,template);
  const beforeDelete=await call('/api/v2/admin/notices/'+id,{session,headers:admin});
  const deleted=await call('/api/v2/admin/notices/'+id,{session,method:'DELETE',headers:{...admin,'If-Match':beforeDelete.headers.get('etag'),'Idempotency-Key':'notice-delete'}});
  assert.equal(deleted.status,200);
  assert.ok(!(await call('/api/v2/notices',{session})).body.data.visibleIds.includes(id));
  assert.equal((await call('/api/v2/admin/notices/'+id,{session,headers:admin})).status,404);
  const alreadyDeleted=await call('/api/v2/admin/notices/'+id,{session,method:'DELETE',headers:{...admin,'If-Match':beforeDelete.headers.get('etag'),'Idempotency-Key':'notice-delete-2'}});
  assert.equal(alreadyDeleted.body.error.code,'ALREADY_DELETED');
});
test('Notice KST midnight hides old general notices but retains lost items and admin history',async()=>{
  const headers={'X-Mock-Time':'2030-10-02T00:00:00+09:00'};
  const publicList=(await call('/api/v2/notices',{headers})).body.data.items;
  assert.ok(publicList.length>0);assert.ok(publicList.every(n=>n.type==='LOST_FOUND'));
  assert.ok((await call('/api/v2/admin/notices',{headers:{...headers,...admin}})).body.data.items.some(n=>n.type==='GENERAL'));
});
test('Notice with no English translation falls back to Korean for every requested locale',async()=>{
  const koOnly=(await call('/api/v2/notices?locale=en')).body.data.items.find(n=>n.id==='notice-ko-only');
  assert.equal(koOnly.contentLocale,'ko');
  const koOnlyJa=(await call('/api/v2/notices?locale=ja')).body.data.items.find(n=>n.id==='notice-ko-only');
  assert.equal(koOnlyJa.contentLocale,'ko');
  const adminView=await call('/api/v2/admin/notices/notice-ko-only',{headers:admin});
  assert.equal(adminView.status,200);assert.equal(Object.hasOwn(adminView.body.data.translations,'en'),false);
});
test('Ticket close boundary hides account; next day reopens; price uses integer KRW',async()=>{
  for(const [now,expected]of [['2030-10-01T20:59:59+09:00','TRANSFER_OPEN'],['2030-10-01T21:00:00+09:00','DAILY_CLOSED'],['2030-10-02T00:00:00+09:00','TRANSFER_OPEN'],['2030-10-04T00:00:00+09:00','FESTIVAL_ENDED']]){
    const data=(await call('/api/v2/ticket-guide',{headers:{'X-Mock-Time':now}})).body.data;assert.equal(data.status,expected);assert.equal(data.account!==null,expected==='TRANSFER_OPEN');assert.equal(data.unitPrice.currency,'KRW');assert.ok(Number.isInteger(data.unitPrice.amount));
  }
});
test('Map links resolve to the same version, place, pin and space',async()=>{
  const spaces=(await call('/api/v2/spaces')).body.data.items;
  for(const space of spaces){const target=space.mapTarget;const pins=(await call(`/api/v2/maps/${target.mapId}/pins?mapVersion=${target.mapVersion}`)).body.data;assert.equal(pins.mapVersion,target.mapVersion);const pin=pins.items.find(p=>p.id===target.pinId);assert.equal(pin.target.placeId,target.placeId);const place=(await call('/api/v2/places/'+target.placeId)).body.data;assert.equal(place.spaceId,space.id);}
  assert.equal((await call('/api/v2/places/place-toilet')).body.data.spaceId,null);
});
test('MapTarget is a canonical current PLACE pin and null means unlinked',async()=>{
  const description=spec.components.schemas.MapTarget.description;
  assert.match(description,/published FestivalRevision/);
  assert.match(description,/현재 map version/);
  assert.match(description,/PLACE/);
  assert.match(description,/null/);
  assert.equal(spec.components.schemas.Space.properties.mapTarget.description,description);
  assert.equal(spec.components.schemas.TicketGuide.properties.mapTarget.description,description);
  const session='map-target-contract',spacesResponse=await call('/api/v2/spaces',{session}),spaces=spacesResponse.body.data.items;
  assert.ok(spaces.length>0);
  for(const space of spaces){
    const target=space.mapTarget;assert.ok(target);
    const response=await call(`/api/v2/maps/${target.mapId}/pins?mapVersion=${target.mapVersion}`,{session});
    assert.deepEqual({festivalId:response.body.meta.festivalId,revision:response.body.meta.revision},{festivalId:spacesResponse.body.meta.festivalId,revision:spacesResponse.body.meta.revision});
    const pin=response.body.data.items.find(p=>p.id===target.pinId);assert.ok(pin);assert.deepEqual(pin.target,{kind:'PLACE',placeId:target.placeId});
  }
  const ticket=await call('/api/v2/ticket-guide',{session}),ticketTarget=ticket.body.data.mapTarget;assert.ok(ticketTarget);
  const ticketPins=await call(`/api/v2/maps/${ticketTarget.mapId}/pins?mapVersion=${ticketTarget.mapVersion}`,{session});
  const ticketPin=ticketPins.body.data.items.find(p=>p.id===ticketTarget.pinId);assert.ok(ticketPin);assert.deepEqual(ticketPin.target,{kind:'PLACE',placeId:ticketTarget.placeId});
  assert.equal((await call('/api/v2/ticket-guide',{session,headers:{'X-Mock-Scenario':'unconfigured'}})).body.data.mapTarget,null);
  assert.equal((await call('/api/v2/spaces/space-booth',{session,headers:{'X-Mock-Scenario':'missing-optional'}})).body.data.mapTarget,null);
});
test('Map pin filters follow the design chips in stable order and AREA pins stay visible',async()=>{
  const expectedOrder=['RESTROOM','PHOTO_BOOTH','SMOKING_AREA','TRASH_BIN'];
  for(const map of (await call('/api/v2/maps')).body.data.items){
    const data=(await call(`/api/v2/maps/${map.id}/pins?mapVersion=${map.version}`)).body.data;
    const placeGroups=data.items.filter(pin=>pin.target.kind==='PLACE'&&pin.filterGroup!==null).map(pin=>pin.filterGroup);
    const expected=[...new Set(placeGroups)].sort((a,b)=>expectedOrder.indexOf(a)-expectedOrder.indexOf(b));
    assert.deepEqual(data.filters.map(filter=>filter.id),expected);
    assert.ok(data.items.filter(pin=>pin.target.kind==='AREA').every(pin=>pin.filterGroup===null));
    assert.ok(data.items.filter(pin=>pin.target.kind==='PLACE').every(pin=>pin.filterGroup===null||expectedOrder.includes(pin.filterGroup)));
    assert.ok(data.filters.every(filter=>expectedOrder.includes(filter.id)&&typeof filter.label==='string'&&filter.label.length>0));
  }
});
test('CORS preflight and response metadata support frontend dev origins only',async()=>{
  const catalog=await call('/__mock/catalog');assert.equal(catalog.body.baseUrl,base);
  const ok=await fetch(base+'/api/v2/config',{method:'OPTIONS',headers:{Origin:'http://localhost:5173','Access-Control-Request-Method':'GET'}});assert.equal(ok.status,204);assert.equal(ok.headers.get('access-control-allow-origin'),'http://localhost:5173');
  const denied=await call('/api/v2/config',{headers:{Origin:'https://unrelated.invalid'}});assert.equal(denied.status,403);assert.equal(denied.headers.get('access-control-allow-origin'),null);
});
test('Runtime validator rejects representative schema violations independently of examples',()=>{
  const badMoney={amount:-1,currency:'USD'};assert.ok(localValidate(spec.components.schemas.Money,badMoney,spec).length>=2);
  assert.ok(localValidate(spec.components.schemas.CrowdingInput,{level:'FULL',extra:1},spec).length);
  assert.ok(localValidate(spec.components.schemas.Date,'2030-02-30',spec).length);
  assert.ok(localValidate(spec.components.schemas.Pin,{id:'p',label:'p',category:'p',x:1.1,y:0,target:{kind:'PLACE',placeId:'a',mapId:'b'}},spec).length);
});

test('v5 removes operating-hour and quantity writes and map crowd consumers',async()=>{
  assert.equal(coverage.data.filter(d=>d.owner!=='제외').length,177);
  for(const s of coverage.screens.filter(s=>s.id.startsWith('MAP')))assert.ok(!s.operations.includes('getCrowding'));
  assert.deepEqual(operation('getCrowding')['x-screen-ids'],['HOME']);
  for(const path of ['/api/v2/admin/operating-hours','/api/v2/admin/operating-hours/2030-10-01','/api/v2/admin/goods/goods-shirt/colors/color-a/sizes/size-m/inventory','/api/v2/performance-alert'])assert.equal((await call(path,{headers:admin})).status,404);
  assert.doesNotMatch(JSON.stringify(createState()),/"quantit(?:y|ies)"/);
  for(const path of ['/api/v2/admin/goods','/api/v2/goods-availability']){
    const d=(await call(path,{headers:admin})).body.data;
    assert.equal(d.items[0].combinations.length,3);assert.doesNotMatch(JSON.stringify(d),/quantity/);
  }
});

test('Combinations stay independent; malformed bodies and nonexistent combinations are rejected',async()=>{
  const session='sparse-options',path='/api/v2/admin/goods/goods-shirt/combinations/combo-shirt-b-m/availability';
  const write=(body,key=`sparse-${Math.random()}`)=>call(path,{session,headers:{...admin,'Idempotency-Key':key},method:'PUT',body});
  for(const body of [{quantity:3},{status:'ON_SALE',quantity:3},{status:'UNKNOWN'}])assert.equal((await write(body)).status,422);
  const before=(await call('/api/v2/goods/goods-shirt/availability',{session})).body.data;
  assert.equal((await write({status:'ON_SALE'})).status,200);
  const after=(await call('/api/v2/goods/goods-shirt/availability',{session})).body.data;
  assert.deepEqual(
    after.combinations.filter(c=>c.colorId==='color-a'),
    before.combinations.filter(c=>c.colorId==='color-a')
  );
  assert.equal(after.combinations.find(c=>c.colorId==='color-b').status,'ON_SALE');
  assert.equal((await write({status:'ON_SALE'},'sparse-missing')).status,200);
  assert.equal((await call(path.replace('combo-shirt-b-m','unknown-combo'),{session,headers:{...admin,'Idempotency-Key':'sparse-unknown'},method:'PUT',body:{status:'ON_SALE'}})).status,404);
});

test('OPTIONS products with an empty configuration are rejected before state mutation',async()=>{
  const session='products-empty-configuration';
  const body={...structuredClone(examples.postAdminProduct.scenarios.normal.request.body),colors:[],sizes:[],options:[]};
  const goodsBefore=(await call('/api/v2/goods',{session})).body.data;
  const availabilityBefore=(await call('/api/v2/goods/goods-shirt/availability',{session})).body.data;
  const response=await call('/api/v2/admin/products',{session,headers:{...admin,'Idempotency-Key':'products-empty-config'},method:'POST',body});
  assert.equal(response.status,422);
  assert.equal(response.body.error.code,'EMPTY_CONFIGURATION');
  assert.deepEqual((await call('/api/v2/goods',{session})).body.data,goodsBefore);
  assert.deepEqual((await call('/api/v2/goods/goods-shirt/availability',{session})).body.data,availabilityBefore);
});

test('New product and option combinations start on sale while existing states survive edits',async()=>{
  const session='products-v5',body=structuredClone(examples.postAdminProduct.scenarios.normal.request.body);
  const created=await call('/api/v2/admin/products',{session,headers:{...admin,'Idempotency-Key':'products-v5-create'},method:'POST',body});
  assert.equal(created.status,201);
  const id=created.body.data.id;
  assert.equal((await call('/api/v2/goods',{session})).body.data.items.length,2);
  let etag=(await call('/api/v2/admin/products/'+id,{session,headers:admin})).headers.get('etag');
  body.translations.ko.name='수정 상품';body.price.amount=3000;
  assert.equal((await call('/api/v2/admin/products/'+id,{session,headers:{...admin,'If-Match':etag,'Idempotency-Key':'products-v5-update-1'},method:'PUT',body})).status,200);
  assert.ok((await call('/api/v2/goods/'+id+'/availability',{session})).body.data.combinations.every(c=>c.status==='ON_SALE'));
  etag=(await call('/api/v2/admin/products/'+id,{session,headers:admin})).headers.get('etag');
  body.options.push({colorId:'color-b',sizeId:'size-l'});
  assert.equal((await call('/api/v2/admin/products/'+id,{session,headers:{...admin,'If-Match':etag,'Idempotency-Key':'products-v5-update-2'},method:'PUT',body})).status,200);
  const combinations=(await call('/api/v2/goods/'+id+'/availability',{session})).body.data.combinations;
  assert.equal(combinations.length,4);assert.equal(combinations.filter(c=>c.status==='ON_SALE').length,4);
  const invalid=structuredClone(body);invalid.options.push({colorId:'unknown',sizeId:'size-m'});
  assert.equal((await call('/api/v2/admin/products/'+id,{session,headers:{...admin,'If-Match':etag,'Idempotency-Key':'products-v5-invalid'},method:'PUT',body:invalid})).status,422);
});

test('Color, size, and option deletion removes obsolete availability and preserves retained states',async()=>{
  const session='products-option-deletion';
  const availabilityBefore=(await call('/api/v2/goods/goods-shirt/availability',{session})).body.data;
  const etag=(await call('/api/v2/admin/products/goods-shirt',{session,headers:admin})).headers.get('etag');
  const body=structuredClone(examples.putAdminProduct.scenarios['option-removal'].request.body);
  const response=await call('/api/v2/admin/products/goods-shirt',{session,headers:{...admin,'If-Match':etag,'Idempotency-Key':'products-option-deletion'},method:'PUT',body});
  assert.equal(response.status,200);
  const availabilityAfter=(await call('/api/v2/goods/goods-shirt/availability',{session})).body.data;
  assert.deepEqual(availabilityAfter.combinations.map(c=>`${c.colorId}/${c.sizeId}`),['color-a/size-m','color-a/size-l']);
  assert.deepEqual(availabilityAfter.combinations.map(c=>c.status),availabilityBefore.combinations.filter(c=>c.colorId==='color-a').map(c=>c.status));
});

test('Notice requires manual ko·en input and rejects link labels that do not match the notice languages',async()=>{
  const session='notice-validation';
  const base=structuredClone(examples.postAdminNotice.scenarios.normal.request.body);
  const post=(body,key)=>call('/api/v2/admin/notices',{session,headers:{...admin,'Idempotency-Key':key},method:'POST',body});

  // English is required now that translation is manual; Korean alone is not enough.
  const koOnly=await post({...base,translations:{ko:base.translations.ko}},'notice-validation-ko-only');
  assert.equal(koOnly.status,422);

  // Blank required text still fails minLength.
  const blank=await post({...base,translations:{ko:{title:' ',body:' '},en:base.translations.en}},'notice-validation-blank');
  assert.equal(blank.status,422);

  // image is no longer part of the contract; an unknown property is rejected.
  const withImage=await post({...base,image:null},'notice-validation-image');
  assert.equal(withImage.status,422);

  // A link label present for a language the notice doesn't have is rejected.
  const extraLabel=await post({...base,links:[{...base.links[0],labels:{...base.links[0].labels,'zh-Hans':'不应该出现'}}]},'notice-validation-extra-label');
  assert.equal(extraLabel.status,422);assert.equal(extraLabel.body.error.code,'LINK_LABEL_UNEXPECTED');

  // A link missing a label for a language the notice does have is rejected.
  const missingLabel=await post({...base,translations:{...base.translations,'zh-Hans':{title:'标题',body:'正文'}}},'notice-validation-missing-label');
  assert.equal(missingLabel.status,422);assert.equal(missingLabel.body.error.code,'LINK_LABEL_REQUIRED');
});

test('Fixed prohibited-items guidance is available outside performance hours',async()=>{
  for(const time of ['2030-09-30T08:00:00+09:00','2030-10-01T18:00:00+09:00','2030-10-04T00:00:00+09:00'])assert.ok((await call('/api/v2/prohibited-items',{headers:{'X-Mock-Time':time}})).body.data.items.length);
  assert.equal((await call('/api/v2/timetable')).body.data.axis.endTime,'22:00');
  const session='prohibited-items-locales';await enableAllMockLocales(session);
  const english=(await call('/api/v2/prohibited-items?locale=en',{session})).body.data;
  assert.doesNotMatch(JSON.stringify(english),/[가-힣]/);
});
test('Crowd messages use the approved translation for each ready locale',async()=>{
  const session='crowd-message-locales';await enableAllMockLocales(session);
  const crowd=async locale=>(await call(`/api/v2/crowding?locale=${locale}`,{session})).body.data;
  assert.equal((await crowd('ko')).status,'MODERATE');
  assert.equal((await crowd('ko')).message,'재학생존의 공간이 절반 이상 찼어요.');
  assert.equal((await crowd('en')).message,'At least half full');
  assert.equal((await crowd('zh-Hans')).message,'已占用一半以上');
  const beforeOpen=async locale=>(await call(`/api/v2/crowding?locale=${locale}`,{session,headers:{'X-Mock-Scenario':'before-open'}})).body.data.message;
  assert.match(await beforeOpen('en'),/^Student Zone entry starts at \d{2}:\d{2} today$/);
  assert.match(await beforeOpen('zh-Hans'),/^今日学生区\d{2}:\d{2}开放入场$/);
});
test('Booth bank transfer appears only on the detail response',async()=>{
  const detail=(await call('/api/v2/spaces/space-pub')).body.data;
  assert.equal(detail.bankTransfer.accountNumber,'000000000000');
  assert.equal(typeof detail.bankTransfer.tossLinkEnabled,'boolean');
  assert.equal((await call('/api/v2/spaces/space-booth')).body.data.bankTransfer,null);
  const list=(await call('/api/v2/spaces')).body.data.items;
  assert.ok(list.every(space=>space.bankTransfer===null));
});
