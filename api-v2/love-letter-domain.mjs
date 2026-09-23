// Fictional data only. The production API enforces cookie ownership, CSRF and DB serialization.
const resultId='00000000-0000-4000-8000-000000000701';
const participantId='00000000-0000-4000-8000-000000000702';
const letterId='00000000-0000-4000-8000-000000000703';
const invitationToken='MOCK_ONLY_INVITATION_TOKEN_0000000000000001';
const csrfToken='mock-only-csrf-token';
const availableAt='2030-10-01T12:01:00+09:00';
const baseStatus=(state, overrides={})=>({state:state.love.opened?'OPENED':state.love.revealed?'SEALED':state.love.registered?'WAITING':'WRITABLE',
  canParticipate:!state.love.registered,nextParticipationAt:state.love.registered?'2030-10-02T09:00:00+09:00':null,
  exchangeId:state.love.revealed?resultId:null,contact:state.love.opened?'@mock-only-contact':null,
  csrfToken:state.love.started?csrfToken:null,revealAt:state.love.registered&&!state.love.revealed?availableAt:null,...overrides});

export function executeLoveLetter(op,state,{body,scenario,now,locale,failure}) {
  if (!/LoveLetter|LoveLetters/.test(op.operationId)) return null;
  state.love ||= {started:false,registered:false,revealed:false,opened:false,enabled:true,seeded:false,reports:[]};
  const love=state.love;
  if (scenario==='pool-empty') failure(409,'LOVE_POOL_EMPTY','받을 수 있는 쪽지가 아직 없습니다.');
  if (scenario==='waiting'&&op.operationId==='openLoveLetter') failure(409,'LOVE_WAITING','배정 후 1분이 지나면 열어볼 수 있습니다.');
  if (scenario==='not-registered') failure(409,'LOVE_NOT_REGISTERED','오늘 등록한 쪽지가 없습니다.');
  if (scenario==='already-participated') failure(409,'LOVE_ALREADY_PARTICIPATED','오늘은 이미 참여했습니다.');
  if (scenario==='invalid-link') failure(409,'LOVE_INVITATION_INVALID','연결 링크가 유효하지 않습니다.');
  if (scenario==='idempotency-conflict') failure(409,'LOVE_IDEMPOTENCY_CONFLICT','요청 키가 다른 내용에 재사용되었습니다.');
  if (scenario==='invalid-csrf') failure(403,'LOVE_CSRF_INVALID','요청 보호 토큰이 유효하지 않습니다.');
  if (scenario==='restricted'&&op.operationId!=='getLoveLetterStatus') failure(403,'LOVE_RESTRICTED','참여가 제한되었습니다.');
  if (scenario==='blocked'&&op.operationId==='openLoveLetter') failure(403,'LOVE_RESULT_BLOCKED','차단된 쪽지입니다.');
  if (scenario==='closed'&&['startLoveLetterParticipant','openLoveLetter'].includes(op.operationId))
    failure(503,'LOVE_CLOSED','러브레터 운영 시간이 아닙니다.');
  const koreanHour=Number(new Intl.DateTimeFormat('en-GB',{timeZone:'Asia/Seoul',hour:'2-digit',hourCycle:'h23'}).format(new Date(now)));
  const inHours=koreanHour>=9&&Date.parse(now)>=Date.parse('2030-10-01T09:00:00+09:00')&&Date.parse(now)<Date.parse('2030-10-04T00:00:00+09:00');
  if(!inHours&&['startLoveLetterParticipant','registerLoveLetter','openLoveLetter','reportLoveLetter','claimLoveLetterInvitation'].includes(op.operationId))
    failure(503,'LOVE_CLOSED','러브레터 운영 시간이 아닙니다.');
  let status=200,data;
  switch(op.operationId){
    case 'getLoveLetterGuide':data={enabled:scenario!=='closed'&&love.enabled&&inHours,opensAt:'2030-10-01T09:00:00+09:00',closesAt:'2030-10-04T00:00:00+09:00',consentVersion:'mock-v1',timezone:'Asia/Seoul',dailyOpensAt:'09:00',dailyClosesAt:'24:00',participationRule:'BROWSER_DAILY',minimumAge:19,maxNameChars:20,maxMessageChars:100,maxContactChars:100,ownContactConfirmationRequired:true,disclosureConsentRequired:true,revealDelaySeconds:60};break;
    case 'startLoveLetterParticipant':status=love.started||scenario==='already-started'?200:201;love.started=true;data=baseStatus(state);break;
    case 'getLoveLetterStatus':{
      if(!inHours&&scenario==='normal'){
        const afterClose=Date.parse(now)>=Date.parse('2030-10-04T00:00:00+09:00');
        const kstDate=new Date(Date.parse(now)+9*3600000).toISOString().slice(0,10);
        const nextOpening=Date.parse(now)<Date.parse('2030-10-01T09:00:00+09:00')?'2030-10-01T09:00:00+09:00':`${kstDate}T09:00:00+09:00`;
        data=baseStatus(state,{state:afterClose?'CLOSED':'BEFORE_OPEN',canParticipate:false,nextParticipationAt:afterClose?null:nextOpening,exchangeId:null,contact:null,revealAt:null});
        break;
      }
      if(scenario==='sealed'){love.registered=true;love.revealed=true;}
      if(scenario==='opened'){love.registered=true;love.revealed=true;love.opened=true;}
      if(scenario==='waiting')love.registered=true;
      if(scenario==='waiting')love.revealed=false;
      if(scenario==='normal'&&love.registered&&Date.parse(now)>=Date.parse(availableAt))love.revealed=true;
      data=baseStatus(state,scenario==='waiting'?{state:'WAITING',canParticipate:false,exchangeId:null,revealAt:availableAt}:scenario==='blocked'?{state:'RESULT_BLOCKED',canParticipate:false,exchangeId:resultId,contact:null}:scenario==='restricted'?{state:'RESTRICTED',canParticipate:false,exchangeId:null,contact:null}:scenario==='closed'?{state:'CLOSED',canParticipate:false,exchangeId:null,contact:null,csrfToken:null}:{});break;
    }
    case 'registerLoveLetter':love.started=true;love.registered=true;love.revealed=false;data={letterId,revealAt:availableAt,state:'WAITING'};break;
    case 'openLoveLetter':love.registered=true;love.revealed=true;love.opened=true;data={exchangeId:resultId,name:'개발용 별명',message:'개발용 한 줄 쪽지',contact:'@mock-only-contact'};break;
    case 'reportLoveLetter':love.reports.push(resultId);data={reported:true};break;
    case 'claimLoveLetterInvitation':love.started=true;love.seeded=true;love.registered=true;data=baseStatus(state,{state:'WAITING',canParticipate:false,exchangeId:null,revealAt:availableAt});break;
    case 'seedLoveLetter':case 'reissueLoveLetterInvitation':data={participantId,invitationToken};break;
    case 'getLoveLetterReports':data=scenario==='empty'?[]:[{id:'00000000-0000-4000-8000-000000000704',letterId,reporterId:participantId,createdAt:now}];break;
    case 'getLoveLetterReport':data={id:'00000000-0000-4000-8000-000000000704',letterId,authorId:participantId,
      reporterId:participantId,name:'개발용 별명',message:'개발용 한 줄 쪽지',contact:'@mock-only-contact',blocked:false};break;
    case 'blockLoveLetter':data={blocked:true};break;
    case 'restrictLoveLetterParticipant':data={restricted:body?.restricted??true};break;
    case 'configureLoveLetters':love.enabled=false;data={enabled:false};break;
    case 'enableLoveLetters':love.enabled=body?.enabled??true;data={enabled:love.enabled};break;
    default:return null;
  }
  return {status,data,now,locale};
}
