// Fictional data only. The production API enforces cookie ownership, CSRF and DB serialization.
const resultId='00000000-0000-4000-8000-000000000701';
const participantId='00000000-0000-4000-8000-000000000702';
const letterId='00000000-0000-4000-8000-000000000703';
const invitationToken='MOCK_ONLY_INVITATION_TOKEN_0000000000000001';
const csrfToken='mock-only-csrf-token';
const availableAt='2030-10-01T12:01:00+09:00';
const baseStatus=(state, overrides={})=>({state:state.love.opened?'OPENED':state.love.drawn?'SEALED':state.love.registered?'WAITING':'WRITABLE',
  canParticipate:!state.love.registered&&!state.love.drawn,nextParticipationAt:state.love.registered||state.love.drawn?'2030-10-02T00:00:00+09:00':null,
  exchangeId:state.love.drawn?resultId:null,contact:state.love.opened?'@mock-only-contact':null,
  csrfToken:state.love.started?csrfToken:null,canDraw:false,drawAvailableAt:state.love.registered&&!state.love.drawn?availableAt:null,...overrides});

export function executeLoveLetter(op,state,{body,scenario,now,locale,failure}) {
  if (!/LoveLetter|LoveLetters/.test(op.operationId)) return null;
  state.love ||= {started:false,registered:false,drawn:false,opened:false,enabled:true,seeded:false,reports:[]};
  const love=state.love;
  if (scenario==='pool-empty') failure(409,'LOVE_POOL_EMPTY','받을 수 있는 쪽지가 아직 없습니다.');
  if (scenario==='waiting'&&['drawLoveLetter','drawSeededLoveLetter'].includes(op.operationId)) failure(409,'LOVE_WAITING','작성 후 1분이 지나면 추첨할 수 있습니다.');
  if (scenario==='not-registered') failure(409,'LOVE_NOT_REGISTERED','오늘 등록한 쪽지가 없습니다.');
  if (scenario==='already-participated') failure(409,'LOVE_ALREADY_PARTICIPATED','오늘은 이미 참여했습니다.');
  if (scenario==='invalid-link') failure(409,'LOVE_INVITATION_INVALID','연결 링크가 유효하지 않습니다.');
  if (scenario==='idempotency-conflict') failure(409,'LOVE_IDEMPOTENCY_CONFLICT','요청 키가 다른 내용에 재사용되었습니다.');
  if (scenario==='invalid-csrf') failure(403,'LOVE_CSRF_INVALID','요청 보호 토큰이 유효하지 않습니다.');
  if (scenario==='restricted'&&op.operationId!=='getLoveLetterStatus') failure(403,'LOVE_RESTRICTED','참여가 제한되었습니다.');
  if (scenario==='blocked'&&op.operationId==='openLoveLetter') failure(403,'LOVE_RESULT_BLOCKED','차단된 쪽지입니다.');
  if (scenario==='closed'&&['startLoveLetterParticipant','openLoveLetter'].includes(op.operationId))
    failure(503,'LOVE_CLOSED','러브레터 운영 시간이 아닙니다.');
  let status=200,data;
  switch(op.operationId){
    case 'getLoveLetterGuide':data={enabled:scenario!=='closed'&&love.enabled,opensAt:'2030-10-01T09:00:00+09:00',closesAt:'2030-10-03T22:00:00+09:00',consentVersion:'mock-v1',timezone:'Asia/Seoul',participationRule:'BROWSER_DAILY',minimumAge:19,maxNameChars:20,maxMessageChars:100,maxContactChars:100,ownContactConfirmationRequired:true,disclosureConsentRequired:true,drawDelaySeconds:60};break;
    case 'startLoveLetterParticipant':status=love.started||scenario==='already-started'?200:201;love.started=true;data=baseStatus(state);break;
    case 'getLoveLetterStatus':{
      if(scenario==='sealed')love.drawn=true;
      if(scenario==='opened'){love.drawn=true;love.opened=true;}
      if(scenario==='waiting'||scenario==='draw-ready')love.registered=true;
      data=baseStatus(state,scenario==='waiting'?{state:'WAITING',canParticipate:false,canDraw:false,drawAvailableAt:availableAt}:scenario==='draw-ready'?{state:'DRAW_READY',canParticipate:false,canDraw:true,drawAvailableAt:availableAt}:scenario==='blocked'?{state:'RESULT_BLOCKED',canParticipate:false,exchangeId:resultId,contact:null}:scenario==='restricted'?{state:'RESTRICTED',canParticipate:false,exchangeId:null,contact:null}:scenario==='closed'?{state:'CLOSED',canParticipate:false,exchangeId:null,contact:null,csrfToken:null}:{});break;
    }
    case 'registerLoveLetter':love.started=true;love.registered=true;data={letterId,drawAvailableAt:availableAt,state:'WAITING'};break;
    case 'drawLoveLetter':case 'drawSeededLoveLetter':love.started=true;love.drawn=true;data={exchangeId:resultId,state:'SEALED'};break;
    case 'openLoveLetter':love.opened=true;data={exchangeId:resultId,name:'개발용 별명',message:'개발용 한 줄 쪽지',contact:'@mock-only-contact'};break;
    case 'reportLoveLetter':love.reports.push(resultId);data={reported:true};break;
    case 'claimLoveLetterInvitation':love.started=true;love.seeded=true;data=baseStatus(state,{state:'SEEDED',canParticipate:true});break;
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
