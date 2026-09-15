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
test('26 screens and 176 active data items plus 10 retired items are covered without duplicate IDs',()=>{assert.equal(coverage.screens.length,26);assert.equal(coverage.data.length,186);assert.equal(new Set(coverage.data.map(x=>x.id)).size,186);for(const s of coverage.screens)assert.ok(s.operations.length>0);assert.ok(coverage.data.filter(x=>x.owner==='브라우저').length>=10);assert.ok(!coverage.data.some(x=>x.id==='ADM-NOTICE-EDIT-D04'));});
test('Every public route has no authentication requirement; every admin route has one',()=>{for(const [path,methods]of Object.entries(spec.paths))for(const o of Object.values(methods))assert.equal(o.security.length>0,path.includes('/admin/'));});
test('No out-of-scope payment, user identity, stamp write, FAQ or performance admin route',()=>{const paths=Object.keys(spec.paths).join(' ');assert.doesNotMatch(paths,/\/orders|\/payments|\/users|\/login|\/faq|\/admin\/performances|\/stamp\//);});

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
  assert.equal(state.spaces.length,30);
  assert.equal(state.spaces.filter(space=>space.category==='BOOTH').length,12);
  assert.equal(state.spaces.filter(space=>space.category==='PUB').length,10);
  assert.equal(state.spaces.filter(space=>space.category==='FLEA_MARKET').length,8);
  assert.equal(state.spaces.flatMap(space=>space.menu).length,50);
  assert.equal(state.maps.length,7);
  assert.equal(Object.values(state.pins).flat().length,48);
  assert.equal(state.places.length,42);
  assert.equal(state.notices.length,10);
});

test('Fictional image variants are served from the local mock asset route',async()=>{
  const state=createState();
  const urls=[
    ...state.artists.map(artist=>artist.image.url),
    ...state.spaces.map(space=>space.image.url),
    ...state.maps.map(map=>map.image.url),
    ...state.goods.flatMap(goods=>[goods.image.url,...goods.colorImages.map(color=>color.image.url)]),
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
  const midnight=await call('/api/v2/crowding',{session,headers:{'X-Mock-Time':'2030-10-02T00:00:00+09:00'}});assert.equal(midnight.body.data.savedLevel,null);assert.equal(midnight.body.data.updatedAt,null);
  const next=await call('/api/v2/crowding',{session,headers:{'X-Mock-Time':'2030-10-02T13:00:00+09:00'}});assert.equal(next.body.data.status,'RELAXED');assert.equal(next.body.data.timeBasis,'OPENING');assert.equal(next.body.data.savedLevel,null);
});
test('Goods save changes only one size and derives sold-out; failed writes do not mutate',async()=>{
  const session='goods-flow',path='/api/v2/admin/goods/goods-shirt/colors/color-a/sizes/size-m/availability';
  const before=await call('/api/v2/goods/goods-shirt/availability',{session});
  await call(path,{method:'PUT',session,headers:{...admin,'X-Mock-Scenario':'error'},body:{status:'SOLD_OUT'}});
  assert.deepEqual((await call('/api/v2/goods/goods-shirt/availability',{session})).body.data,before.body.data);
  assert.equal((await call(path,{method:'PUT',session,headers:admin,body:{status:'SOLD_OUT'}})).status,200);
  const sold=(await call('/api/v2/goods/goods-shirt/availability',{session})).body.data;assert.equal(sold.allSoldOut,true);assert.equal(sold.variants[1].status,'SOLD_OUT');
  assert.equal((await call('/api/v2/goods',{session})).body.data.items.length,1);
  assert.ok((await call('/api/v2/goods/goods-shirt/payment-guide',{session})).body.data.account);
  await call(path,{method:'PUT',session,headers:admin,body:{status:'ON_SALE'}});assert.equal((await call('/api/v2/goods/goods-shirt/availability',{session})).body.data.allSoldOut,false);
});
test('Notice create/edit/delete synchronizes public list, language visibility and immutable template',async()=>{
  const session='notice-flow';
  const template=(await call('/api/v2/admin/notice-templates/template-1',{session,headers:admin})).body.data;
  const body={...examples.postAdminNotice.scenarios.normal.request.body,templateId:'template-1'};
  const created=await call('/api/v2/admin/notices',{session,headers:admin,method:'POST',body});assert.equal(created.status,201);assert.ok(created.headers.get('location'));
  const id=created.body.data.id;
  assert.ok((await call('/api/v2/notices',{session})).body.data.visibleIds.includes(id));
  body.translations.en.status='PENDING';
  assert.equal((await call('/api/v2/admin/notices/'+id,{session,headers:admin,method:'PUT',body})).status,200);
  assert.ok(!(await call('/api/v2/notices?locale=en',{session})).body.data.visibleIds.includes(id));
  assert.ok((await call('/api/v2/notices',{session})).body.data.visibleIds.includes(id));
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

test('v5 removes operating-hour and quantity writes and map crowd consumers',async()=>{
  assert.equal(coverage.data.filter(d=>d.owner!=='제외').length,176);
  for(const s of coverage.screens.filter(s=>s.id.startsWith('MAP')))assert.ok(!s.operations.includes('getCrowding'));
  assert.deepEqual(operation('getCrowding')['x-screen-ids'],['HOME']);
  for(const path of ['/api/v2/admin/operating-hours','/api/v2/admin/operating-hours/2030-10-01','/api/v2/admin/goods/goods-shirt/colors/color-a/sizes/size-m/inventory','/api/v2/performance-alert'])assert.equal((await call(path,{headers:admin})).status,404);
  assert.doesNotMatch(JSON.stringify(createState()),/"quantit(?:y|ies)"/);
  for(const path of ['/api/v2/admin/goods','/api/v2/goods-availability']){
    const d=(await call(path,{headers:admin})).body.data;
    assert.equal(d.items[0].variants.length,3);assert.doesNotMatch(JSON.stringify(d),/quantity/);
  }
});

test('v5 sparse options stay independent; counts and nonexistent combinations are rejected',async()=>{
  const session='sparse-options',path='/api/v2/admin/goods/goods-shirt/colors/color-b/sizes/size-m/availability';
  for(const body of [{quantity:3},{status:'ON_SALE',quantity:3},{status:'UNKNOWN'}])assert.equal((await call(path,{session,headers:admin,method:'PUT',body})).status,422);
  const before=(await call('/api/v2/goods/goods-shirt/availability',{session})).body.data;
  assert.equal((await call(path,{session,headers:admin,method:'PUT',body:{status:'ON_SALE'}})).status,200);
  const after=(await call('/api/v2/goods/goods-shirt/availability',{session})).body.data;
  assert.deepEqual(after.variants.filter(v=>v.colorId==='color-a'),before.variants.filter(v=>v.colorId==='color-a'));
  assert.equal(after.variants.find(v=>v.colorId==='color-b').status,'ON_SALE');
  assert.equal((await call(path.replace('size-m','size-l'),{session,headers:admin,method:'PUT',body:{status:'ON_SALE'}})).status,404);
});

test('New options require an explicit mock policy; existing states survive product edits',async()=>{
  const session='products-v5',body=structuredClone(examples.postAdminProduct.scenarios.normal.request.body);
  const opts={session,headers:admin,method:'POST',body};
  const blocked=await call('/api/v2/admin/products',opts);assert.equal(blocked.status,409);assert.equal(blocked.body.error.code,'INITIAL_AVAILABILITY_UNRESOLVED');
  assert.equal((await call('/api/v2/goods',{session})).body.data.items.length,1);
  const created=await call('/api/v2/admin/products',{...opts,headers:{...admin,'X-Mock-Scenario':'new-option-on-sale'}});assert.equal(created.status,201);
  const id=created.body.data.id;
  body.name='수정 상품';body.price.amount=3000;
  assert.equal((await call('/api/v2/admin/products/'+id,{session,headers:admin,method:'PUT',body})).status,200);
  assert.ok((await call('/api/v2/goods/'+id+'/availability',{session})).body.data.variants.every(v=>v.status==='ON_SALE'));
  body.options.push({colorId:'color-b',sizeId:'size-l'});
  assert.equal((await call('/api/v2/admin/products/'+id,{session,headers:admin,method:'PUT',body})).status,409);
  assert.equal((await call('/api/v2/admin/products/'+id,{session,headers:{...admin,'X-Mock-Scenario':'new-option-sold-out'},method:'PUT',body})).status,200);
  const variants=(await call('/api/v2/goods/'+id+'/availability',{session})).body.data.variants;
  assert.equal(variants.length,4);assert.equal(variants.filter(v=>v.status==='ON_SALE').length,3);
  const invalid=structuredClone(body);invalid.options.push({colorId:'unknown',sizeId:'size-m'});
  assert.equal((await call('/api/v2/admin/products/'+id,{session,headers:admin,method:'PUT',body:invalid})).status,422);
});

test('Korean notice publishes despite failed English; retry enables only READY languages',async()=>{
  const session='notice-v5',source={title:'한국어 제목',body:'첫 줄\n다음 줄'};
  const preview=(await call('/api/v2/admin/notice-translations',{session,headers:{...admin,'X-Mock-Scenario':'english-failed'},method:'POST',body:source})).body.data;
  assert.equal(preview.canSave,true);assert.equal(preview.translations.en.status,'FAILED');
  const body={type:'GENERAL',translations:preview.translations,links:[],templateId:null};
  const saved=await call('/api/v2/admin/notices',{session,headers:admin,method:'POST',body});assert.equal(saved.status,201);
  const id=saved.body.data.id;
  assert.ok((await call('/api/v2/notices',{session})).body.data.visibleIds.includes(id));
  assert.ok(!(await call('/api/v2/notices?locale=en',{session})).body.data.visibleIds.includes(id));
  const retry=(await call('/api/v2/admin/notice-translations',{session,headers:admin,method:'POST',body:source})).body.data;
  body.translations=retry.translations;
  const updated=await call('/api/v2/admin/notices/'+id,{session,headers:admin,method:'PUT',body});
  assert.equal(updated.status,200);assert.equal(updated.body.data.createdAt,saved.body.data.createdAt);
  assert.ok((await call('/api/v2/notices?locale=en',{session})).body.data.visibleIds.includes(id));
  assert.equal(updated.body.data.translations.ko.body,source.body);
  assert.equal((await call('/api/v2/admin/notices',{session,headers:admin,method:'POST',body:{...body,translations:{ko:{title:' ',body:' ',status:'READY'}}}})).status,422);
  assert.equal((await call('/api/v2/admin/notices',{session,headers:admin,method:'POST',body:{...body,image:null}})).status,422);
});

test('Fixed prohibited-items guidance is available outside performance hours',async()=>{
  for(const time of ['2030-09-30T08:00:00+09:00','2030-10-01T18:00:00+09:00','2030-10-04T00:00:00+09:00'])assert.ok((await call('/api/v2/prohibited-items',{headers:{'X-Mock-Time':time}})).body.data.items.length);
  assert.equal((await call('/api/v2/timetable')).body.data.axis.endTime,'22:00');
  const english=(await call('/api/v2/prohibited-items?locale=en')).body.data;
  assert.doesNotMatch(JSON.stringify(english),/[가-힣]/);
});
