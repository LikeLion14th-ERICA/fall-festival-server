import test,{after,before} from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import SwaggerParser from '@apidevtools/swagger-parser';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { createMockServer } from './server.mjs';
import { validate as localValidate } from './validate.mjs';

const read=name=>readFile(new URL(name,import.meta.url),'utf8').then(JSON.parse);
const spec=await read('./openapi.json'),examples=await read('./examples.json'),coverage=await read('./screen-coverage.json');
const ajv=new Ajv2020({strict:false,allErrors:true});addFormats(ajv);
const compiled=new Map();
function standardValidate(schema,value){const key=JSON.stringify(schema);if(!compiled.has(key))compiled.set(key,ajv.compile({...schema,components:spec.components}));const validate=compiled.get(key);assert.equal(validate(value),true,JSON.stringify(validate.errors));}
let server,base;
before(async()=>{server=await createMockServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));base=`http://127.0.0.1:${server.address().port}`;});
after(async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));});
async function call(path,{method='GET',body,headers={},session='test'}={}){const response=await fetch(base+path,{method,headers:{'X-Mock-Session':session,...(body!==undefined?{'Content-Type':'application/json'}:{}),...headers},...(body!==undefined?{body:typeof body==='string'?body:JSON.stringify(body)}:{})});const json=await response.json();return {status:response.status,body:json,headers:response.headers};}
const admin={Authorization:'Bearer mock-admin'};
const operation=id=>Object.values(spec.paths).flatMap(Object.values).find(o=>o.operationId===id);

test('OpenAPI 3.1 document passes standard parser validation',async()=>{await SwaggerParser.validate(structuredClone(spec));});
test('27 screens and 183 active data items are covered without duplicate IDs',()=>{assert.equal(coverage.screens.length,27);assert.equal(coverage.data.length,183);assert.equal(new Set(coverage.data.map(x=>x.id)).size,183);for(const s of coverage.screens)assert.ok(s.operations.length>0);assert.ok(coverage.data.filter(x=>x.owner==='브라우저').length>=10);assert.ok(!coverage.data.some(x=>x.id==='ADM-NOTICE-EDIT-D04'));});
test('Every public route has no authentication requirement; every admin route has one',()=>{for(const [path,methods]of Object.entries(spec.paths))for(const o of Object.values(methods))assert.equal(o.security.length>0,path.includes('/admin/'));});
test('No out-of-scope payment, user identity, stamp write, FAQ or performance admin route',()=>{const paths=Object.keys(spec.paths).join(' ');assert.doesNotMatch(paths,/\/orders|\/payments|\/users|\/login|\/faq|\/admin\/performances|\/stamp\//);});

for(const [opId,group]of Object.entries(examples))for(const [scenario,example]of Object.entries(group.scenarios)){
  test(`${opId}: ${scenario} returns the documented HTTP/schema/body`,async()=>{
    const req=example.request;
    const got=await call(req.path,{method:req.method,body:req.body,headers:{...req.headers,'X-Mock-Session':opId+'-'+scenario}});
    assert.equal(got.status,example.status,JSON.stringify(got.body));
    standardValidate(operation(opId).responses[got.status].content['application/json'].schema,got.body);
    standardValidate(operation(opId).responses[example.status].content['application/json'].schema,example.response);
    assert.equal(got.headers.get('x-request-id'),got.body.meta.requestId);
    assert.equal(got.body.meta.mock,true);
    assert.deepEqual({...got.body,meta:{...got.body.meta,requestId:'mock-example-request'}},example.response);
  });
}
test('Invalid input is rejected, not reflected into a success fixture',async()=>{
  const cases=[['/api/v2/lineup?date=2030-02-30',{},400],['/api/v2/lineup?category=INVALID',{},400],['/api/v2/spaces?search=x',{},400],['/api/v2/spaces?category=PUB&category=BOOTH',{},400],['/api/v2/maps/map-area/pins',{},400],['/api/v2/maps/map-area/pins?mapVersion=old',{},409],['/api/v2/goods/unknown',{},404],['/api/v2/config?locale=ja',{},400],['/api/v2/config?locale=zh-Hans',{},400],['/api/v2/config?__scenario=unknown',{},400],['/api/v2/admin/crowding',{method:'PUT',body:{level:'BOGUS'},headers:admin},422],['/api/v2/admin/crowding',{method:'PUT',body:{level:'FULL'},headers:admin},422],['/api/v2/admin/crowding',{method:'PUT',body:{level:'CROWDED',extra:true},headers:admin},422],['/api/v2/admin/crowding',{method:'PUT',body:'{bad',headers:admin},400],['/api/v2/admin/crowding',{method:'PUT',body:{level:'CROWDED'},headers:{...admin,'Content-Type':'text/plain'}},415],['/api/v2/config',{headers:{'X-Mock-Delay':'3001'}},400]];
  for(const [path,opts,status]of cases){const got=await call(path,opts);assert.equal(got.status,status,path);standardValidate({$ref:'#/components/schemas/Error'},got.body);}
});
test('Mock administrator authorization is checked server-side for every admin method',async()=>{
  for(const [path,methods]of Object.entries(spec.paths))if(path.includes('/admin/'))for(const [method,o]of Object.entries(methods)){
    const sample=examples[o.operationId].scenarios.normal.request.path;
    assert.equal((await call(sample,{method:method.toUpperCase()})).status,401);
    assert.equal((await call(sample,{method:method.toUpperCase(),headers:{Authorization:'Bearer mock-viewer'}})).status,403);
  }
});
test('Crowding no-op, FULL confirmation, day boundary, restoration, shared read and session isolation',async()=>{
  const session='crowding-flow',opts={session,headers:admin};
  const initial=await call('/api/v2/crowding',{session});
  const noop=await call('/api/v2/admin/crowding',{...opts,method:'PUT',body:{level:'MODERATE'}});
  assert.equal(noop.body.data.updatedAt,initial.body.data.updatedAt);assert.equal(noop.body.meta.revision,initial.body.meta.revision);
  assert.equal((await call('/api/v2/admin/crowding',{...opts,method:'PUT',body:{level:'FULL'}})).status,422);
  const full=await call('/api/v2/admin/crowding',{...opts,method:'PUT',body:{level:'FULL',confirmFull:true}});assert.equal(full.status,200);
  assert.equal((await call('/api/v2/crowding',{session})).body.data.status,'FULL');
  assert.equal((await call('/api/v2/crowding',{session:'separate-client'})).body.data.status,'MODERATE');
  const closed=await call('/api/v2/crowding',{session,headers:{'X-Mock-Time':'2030-10-01T23:00:00+09:00'}});assert.equal(closed.body.data.status,'CLOSED');assert.equal(closed.body.data.timeBasis,'NONE');
  await call('/api/v2/admin/operating-hours/2030-10-01',{...opts,method:'PUT',body:{opensAt:'13:00',closesAt:'23:59'}});
  const restored=await call('/api/v2/crowding',{session,headers:{'X-Mock-Time':'2030-10-01T23:00:00+09:00'}});assert.equal(restored.body.data.status,'FULL');assert.equal(restored.body.data.updatedAt,full.body.data.updatedAt);
  const next=await call('/api/v2/crowding',{session,headers:{'X-Mock-Time':'2030-10-02T13:00:00+09:00'}});assert.equal(next.body.data.status,'RELAXED');assert.equal(next.body.data.timeBasis,'OPENING');assert.equal(next.body.data.savedLevel,null);
});
test('Goods save changes only one size and derives sold-out; failed writes do not mutate',async()=>{
  const session='goods-flow',path='/api/v2/admin/goods/goods-shirt/colors/color-a/sizes/size-m/inventory';
  const before=await call('/api/v2/goods/goods-shirt/availability',{session});
  await call(path,{method:'PUT',session,headers:{...admin,'X-Mock-Scenario':'error'},body:{quantity:0}});
  assert.deepEqual((await call('/api/v2/goods/goods-shirt/availability',{session})).body.data,before.body.data);
  assert.equal((await call(path,{method:'PUT',session,headers:admin,body:{quantity:0}})).status,200);
  const sold=(await call('/api/v2/goods/goods-shirt/availability',{session})).body.data;assert.equal(sold.allSoldOut,true);assert.equal(sold.variants[1].status,'SOLD_OUT');
  assert.equal((await call('/api/v2/goods',{session})).body.data.items.length,1);
  assert.ok((await call('/api/v2/goods/goods-shirt/payment-guide',{session})).body.data.account);
  await call(path,{method:'PUT',session,headers:admin,body:{quantity:1}});assert.equal((await call('/api/v2/goods/goods-shirt/availability',{session})).body.data.allSoldOut,false);
});
test('Notice create/edit/delete synchronizes public list, language visibility and immutable template',async()=>{
  const session='notice-flow';
  const template=(await call('/api/v2/admin/notice-templates/template-1',{session,headers:admin})).body.data;
  const body={...examples.postAdminNotice.scenarios.normal.request.body,templateId:'template-1'};
  const created=await call('/api/v2/admin/notices',{session,headers:admin,method:'POST',body});assert.equal(created.status,201);assert.ok(created.headers.get('location'));
  const id=created.body.data.id;
  assert.ok((await call('/api/v2/notices',{session})).body.data.visibleIds.includes(id));
  body.translations.en.status='PENDING';
  assert.equal((await call('/api/v2/admin/notices/'+id,{session,headers:admin,method:'PUT',body})).status,422);
  assert.ok((await call('/api/v2/notices?locale=en',{session})).body.data.visibleIds.includes(id));
  assert.deepEqual((await call('/api/v2/admin/notice-templates/template-1',{session,headers:admin})).body.data,template);
  assert.equal((await call('/api/v2/admin/notices/'+id,{session,headers:admin,method:'DELETE'})).status,200);
  assert.ok(!(await call('/api/v2/notices',{session})).body.data.visibleIds.includes(id));
  assert.equal((await call('/api/v2/admin/notices/'+id,{session,headers:admin})).status,404);
  assert.equal((await call('/api/v2/admin/notices/'+id,{session,headers:admin,method:'DELETE'})).body.error.code,'ALREADY_DELETED');
});
test('Notice KST midnight hides old general notices but retains lost items and admin history',async()=>{
  const headers={'X-Mock-Time':'2030-10-02T00:00:00+09:00'};
  const publicList=(await call('/api/v2/notices',{headers})).body.data.items;
  assert.ok(publicList.length>0);assert.ok(publicList.every(n=>n.type==='LOST_FOUND'));
  assert.ok((await call('/api/v2/admin/notices',{headers:{...headers,...admin}})).body.data.items.some(n=>n.type==='GENERAL'));
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

test('Operating-hour edits re-evaluate shared state, validate same-day range and isolate days',async()=>{
  const session='hours-boundaries',path='/api/v2/admin/operating-hours/2030-10-01';
  const initial=(await call('/api/v2/admin/operating-hours',{session,headers:admin})).body.data.items;
  assert.deepEqual(initial.map(h=>[h.opensAt,h.closesAt,h.isDefault]),Array(3).fill(['13:00','22:00',true]));
  for(const body of [{opensAt:'22:00',closesAt:'13:00'},{opensAt:'13:00',closesAt:'13:00'},{opensAt:'24:00',closesAt:'25:00'},{opensAt:'13:00'},{}])assert.equal((await call(path,{session,headers:admin,method:'PUT',body})).status,422);
  for(const [time,state]of [['12:59:59','BEFORE_OPEN'],['13:00:00','OPEN'],['21:59:59','OPEN'],['22:00:00','CLOSED']])assert.equal((await call('/api/v2/crowding',{session,headers:{'X-Mock-Time':`2030-10-01T${time}+09:00`}})).body.data.operatingStatus,state);
  const saved=(await call('/api/v2/crowding',{session})).body.data;
  for(const [hours,status]of [[{opensAt:'13:00',closesAt:'17:00'},'CLOSED'],[{opensAt:'13:00',closesAt:'22:00'},'MODERATE'],[{opensAt:'19:00',closesAt:'22:00'},'BEFORE_OPEN']]){
    assert.equal((await call(path,{session,headers:admin,method:'PUT',body:hours})).status,200);
    const shared=(await call('/api/v2/crowding',{session})).body.data;assert.equal(shared.status,status);assert.equal(shared.updatedAt,saved.updatedAt);
  }
  const before=(await call('/api/v2/admin/operating-hours',{session,headers:admin})).body.data;
  assert.deepEqual(before.items[1],initial[1]);
  await call(path,{session,headers:{...admin,'X-Mock-Scenario':'error'},method:'PUT',body:{opensAt:'13:00',closesAt:'22:00'}});
  assert.deepEqual((await call('/api/v2/admin/operating-hours',{session,headers:admin})).body.data,before);
});

test('Inventory is private, integer-only, color-specific and last successful absolute count wins',async()=>{
  const session='inventory-private',root='/api/v2/admin/goods/goods-shirt/colors/',path=root+'color-b/sizes/size-m/inventory';
  const initial=(await call('/api/v2/admin/goods',{session,headers:admin})).body.data.items[0];
  for(const quantity of [-1,1.5,'2',null,Number.MAX_SAFE_INTEGER+1])assert.equal((await call(path,{session,headers:admin,method:'PUT',body:{quantity}})).status,422);
  assert.equal((await call(path,{session,headers:admin,method:'PUT',body:{status:'SOLD_OUT'}})).status,422);
  assert.equal((await call(root+'unknown/sizes/size-m/inventory',{session,headers:admin,method:'PUT',body:{quantity:1}})).status,404);
  for(const quantity of [7,3])assert.equal((await call(path,{session,headers:admin,method:'PUT',body:{quantity}})).status,200);
  const current=(await call('/api/v2/admin/goods',{session,headers:admin})).body.data.items[0];
  assert.equal(current.variants.find(v=>v.colorId==='color-b'&&v.sizeId==='size-m').quantity,3);
  assert.deepEqual(current.variants.filter(v=>v.colorId==='color-a'),initial.variants.filter(v=>v.colorId==='color-a'));
  for(const publicPath of ['/api/v2/goods','/api/v2/goods/goods-shirt','/api/v2/goods-availability','/api/v2/goods/goods-shirt/availability'])assert.doesNotMatch(JSON.stringify((await call(publicPath,{session})).body),/"quantity"|"quantities"/);
  assert.equal((await call('/api/v2/admin/goods/goods-shirt/sizes/size-m',{session,headers:admin,method:'PUT',body:{status:'SOLD_OUT'}})).status,404);
});

test('Product creation and option additions start at zero, renaming preserves inventory and removals fail',async()=>{
  const session='product-flow',body=structuredClone(examples.postAdminProduct.scenarios.normal.request.body);
  const created=await call('/api/v2/admin/products',{session,headers:admin,method:'POST',body});assert.equal(created.status,201);assert.ok(created.headers.get('location').includes('/products/'));
  const id=created.body.data.id,productPath='/api/v2/admin/products/'+id,stockPath=`/api/v2/admin/goods/${id}/colors/color-a/sizes/size-m/inventory`;
  const inventory=async()=>((await call('/api/v2/admin/goods',{session,headers:admin})).body.data.items.find(g=>g.goodsId===id));
  assert.equal((await inventory()).variants.length,4);assert.ok((await inventory()).variants.every(v=>v.quantity===0));assert.equal((await inventory()).allSoldOut,true);
  await call(stockPath,{session,headers:admin,method:'PUT',body:{quantity:9}});
  body.colors[0].name='이름 수정';body.sizes[0].label='새 사이즈 표시명';body.colors.push({id:'color-c',name:'새 색상',images:[]});body.sizes.push({id:'size-xl',label:'XL'});
  assert.equal((await call(productPath,{session,headers:admin,method:'PUT',body})).status,200);
  const current=await inventory();assert.equal(current.variants.length,9);assert.equal(current.variants.find(v=>v.colorId==='color-a'&&v.sizeId==='size-m').quantity,9);assert.ok(current.variants.filter(v=>v.colorId==='color-c'||v.sizeId==='size-xl').every(v=>v.quantity===0));
  const publicProduct=(await call('/api/v2/goods/'+id,{session})).body.data;assert.equal(publicProduct.colors[0].name,'이름 수정');
  const removal=structuredClone(body);removal.colors.pop();assert.equal((await call(productPath,{session,headers:admin,method:'PUT',body:removal})).status,409);
  const duplicate=structuredClone(body);duplicate.sizes.push(duplicate.sizes[0]);assert.equal((await call(productPath,{session,headers:admin,method:'PUT',body:duplicate})).status,422);
  await call(productPath,{session,headers:{...admin,'X-Mock-Scenario':'error'},method:'PUT',body:{...body,name:'실패값'}});
  assert.deepEqual((await call('/api/v2/goods/'+id,{session})).body.data,publicProduct);assert.deepEqual(await inventory(),current);
  assert.equal((await call(productPath,{session,headers:admin,method:'DELETE'})).status,405);
});

test('Translation preview and save enforce English, source freshness and active-language partial failures',async()=>{
  const session='translation-flow',input={title:'한국어 제목',body:'한국어 본문'},path='/api/v2/admin/notice-translations';
  const preview=await call(path,{session,headers:admin,method:'POST',body:input});assert.equal(preview.status,200);assert.equal(preview.body.data.canSave,true);assert.equal(preview.headers.get('location'),null);
  const failed=await call(path,{session,headers:{...admin,'X-Mock-Scenario':'english-failed'},method:'POST',body:input});assert.equal(failed.body.data.canSave,false);
  const body={type:'GENERAL',translations:failed.body.data.translations,translationSource:input,links:[],templateId:null};
  assert.equal((await call('/api/v2/admin/notices',{session,headers:admin,method:'POST',body})).status,422);
  await call('/api/v2/config?__scenario=all-languages',{session});
  const partial=(await call(path,{session,headers:{...admin,'X-Mock-Scenario':'partial-translation'},method:'POST',body:input})).body.data;
  body.translations=partial.translations;
  const created=await call('/api/v2/admin/notices',{session,headers:admin,method:'POST',body});assert.equal(created.status,201);const id=created.body.data.id;
  assert.ok((await call('/api/v2/notices?locale=en',{session})).body.data.visibleIds.includes(id));assert.ok(!(await call('/api/v2/notices?locale=ja',{session})).body.data.visibleIds.includes(id));
  body.translations.ko.title='변경된 제목';assert.equal((await call('/api/v2/admin/notices/'+id,{session,headers:admin,method:'PUT',body})).body.error.code,'STALE_TRANSLATION_SOURCE');
  body.translationSource={title:body.translations.ko.title,body:body.translations.ko.body};body.translations.en.title='Manually reviewed title';
  const updated=await call('/api/v2/admin/notices/'+id,{session,headers:admin,method:'PUT',body});assert.equal(updated.status,200);assert.equal(updated.body.data.createdAt,created.body.data.createdAt);assert.notEqual(updated.body.data.updatedAt,created.body.data.updatedAt);
  assert.equal((await call('/api/v2/admin/notices',{session,headers:admin,method:'POST',body:{...body,image:null}})).status,422);
});
