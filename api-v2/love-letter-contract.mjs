// LOVE-001. Personal fields are confined to the opened response and admin seed input.
const text = (description, extra={}) => ({type:'string',description,...extra});
const obj = properties => ({type:'object',properties,required:Object.keys(properties),additionalProperties:false});
const ref = name => ({$ref:`#/components/schemas/${name}`});
const nullable = schema => ({anyOf:[schema,{type:'null'}]});
const uuid = text('Opaque UUID', {format:'uuid'});
const dateTime = text('RFC 3339 instant', {format:'date-time'});
const gender = {type:'string',enum:['MALE','FEMALE']};
const bool = {type:'boolean'};
const personal = (maximum,oneLine=false) => text('사용자 작성 원문. 번역·로그·분석 수집 금지.',{minLength:1,maxLength:maximum,...(oneLine?{pattern:'^[^\\r\\n]+$'}:{})});

export const LOVE_LETTER_OPERATION_IDS = [
  'getLoveLetterGuide','startLoveLetterParticipant','getLoveLetterStatus','registerLoveLetter',
  'openLoveLetter','reportLoveLetter','claimLoveLetterInvitation',
  'seedLoveLetter','reissueLoveLetterInvitation','getLoveLetterReports','getLoveLetterReport',
  'blockLoveLetter','restrictLoveLetterParticipant','configureLoveLetters','enableLoveLetters',
];

export function applyLoveLetterContract(schemas,operations) {
  Object.assign(schemas,{
    LoveLetterGuide:obj({enabled:bool,opensAt:nullable(dateTime),closesAt:nullable(dateTime),consentVersion:nullable(text('동의문 버전')),timezone:{type:'string',enum:['Asia/Seoul']},dailyOpensAt:{type:'string',enum:['09:00']},dailyClosesAt:{type:'string',enum:['24:00']},participationRule:{type:'string',enum:['BROWSER_DAILY']},minimumAge:{type:'integer',enum:[19]},maxNameChars:{type:'integer',enum:[20]},maxMessageChars:{type:'integer',enum:[100]},maxContactChars:{type:'integer',enum:[100]},ownContactConfirmationRequired:bool,disclosureConsentRequired:bool,revealDelaySeconds:{type:'integer',enum:[60]}}),
    LoveLetterStatus:obj({state:{type:'string',enum:['CLOSED','BEFORE_OPEN','WRITABLE','WAITING','SEEDED','SEALED','OPENED','RESULT_BLOCKED','RESTRICTED']},canParticipate:bool,nextParticipationAt:nullable(dateTime),exchangeId:nullable(uuid),contact:nullable(personal(100)),csrfToken:nullable(text('쓰기 헤더 X-Love-Letter-CSRF 값. 세션 브라우저에만 전달.')),revealAt:nullable(dateTime)}),
    LoveLetterInput:obj({gender,name:personal(20),message:personal(100,true),contact:personal(100),adultConfirmed:bool,ownContactConfirmed:bool,consentVersion:text('안내와 일치하는 동의문 버전',{minLength:1,maxLength:64})}),
    LoveLetterRegistered:obj({letterId:uuid,revealAt:dateTime,state:{type:'string',enum:['WAITING']}}),
    LoveLetterOpened:obj({exchangeId:uuid,name:personal(20),message:personal(100,true),contact:personal(100)}),
    LoveLetterReportAck:obj({reported:bool}),
    LoveLetterClaimInput:obj({invitationToken:text('일회용 연결 토큰. 로그에 남기지 않음.',{minLength:32,maxLength:128,writeOnly:true})}),
    LoveLetterSeedInput:obj({operatingDate:{type:'string',format:'date'},letter:ref('LoveLetterInput'),consentAt:dateTime}),
    LoveLetterSeeded:obj({participantId:uuid,invitationToken:text('한 번만 표시·전달할 연결 토큰',{writeOnly:true})}),
    LoveLetterReport:obj({id:uuid,letterId:uuid,reporterId:uuid,createdAt:dateTime}),
    LoveLetterReports:{type:'array',items:ref('LoveLetterReport')},
    LoveLetterAdminReport:obj({id:uuid,letterId:uuid,authorId:uuid,reporterId:uuid,name:personal(20),message:personal(100,true),contact:personal(100),blocked:bool}),
    LoveLetterRestrictionInput:obj({restricted:bool}),
    LoveLetterEnabledInput:obj({enabled:bool}),
    LoveLetterSettingsInput:obj({opensAt:text('첫 행사일 09:00 KST',{format:'date-time'}),closesAt:text('마지막 행사일 다음 날 00:00 KST (24:00 종료)',{format:'date-time'}),consentVersion:text('동의문 버전',{minLength:1,maxLength:64})}),
    LoveLetterFlag:obj({enabled:bool}),
    LoveLetterBlockAck:obj({blocked:bool}),
    LoveLetterRestrictionAck:obj({restricted:bool}),
  });
  const add=(operationId,method,path,schema,summary,scenarios,input,admin=false)=>{
    const publicWrite=!admin&&method!=='GET';
    operations.push({operationId,method,path:`/api/v2${path}`,schema,summary,screens:[admin?'ADM-LOVE':'LOVE-001'],
      parameters:[...(!admin?[{name:'locale',in:'query',required:false,schema:{type:'string',enum:['ko','en','zh-Hans','ja'],default:'ko'}}]:[]),
        ...(!admin&&operationId!=='getLoveLetterGuide'&&operationId!=='startLoveLetterParticipant'?
          [{name:'__Host-festival-love',in:'cookie',required:operationId!=='getLoveLetterStatus',schema:text('난수 토큰'),description:'전용 익명 HttpOnly 쿠키. 브라우저 credentials: include.'}]:[]),
        ...(publicWrite?[{name:'Origin',in:'header',required:true,schema:text('허용된 앱 origin'),description:'서버 설정의 허용 origin과 정확히 일치해야 한다.'}]:[]),
        ...(publicWrite&&operationId!=='startLoveLetterParticipant'?
          [{name:'X-Love-Letter-CSRF',in:'header',required:true,schema:text('세션에서 받은 CSRF 토큰'),description:'POST /love-letter-participants 또는 GET /love-letter-status의 csrfToken.'}]:[]),
        ...[...path.matchAll(/\{(\w+)\}/g)].map(match=>({name:match[1],in:'path',required:true,schema:uuid}))],
      scenarios,input,admin,cacheControl:'no-store',successStatus:200});
    return operations.at(-1);
  };
  add('getLoveLetterGuide','GET','/love-letter-guide','LoveLetterGuide','러브레터 운영 안내',['normal','closed','error']);
  const start=add('startLoveLetterParticipant','POST','/love-letter-participants','LoveLetterStatus','익명 참여 세션 발급',['normal','already-started','closed','error']);
  start.successStatus=201;start.additionalSuccessStatuses=[200];
  add('getLoveLetterStatus','GET','/love-letter-status','LoveLetterStatus','미리 배정한 쪽지의 60초 열람 대기·최근 결과 조회',['normal','waiting','sealed','opened','blocked','restricted','closed','error']);
  const register=add('registerLoveLetter','POST','/love-letters','LoveLetterRegistered','쪽지 등록과 원자적 사전 배정, 60초 열람 대기',['normal','replay','pool-empty','already-participated','restricted','idempotency-conflict','invalid-csrf','error'],'LoveLetterInput');register.idempotencyKeyRequired=true;
  add('openLoveLetter','POST','/love-letter-results/{id}/open','LoveLetterOpened','60초 후 봉투 개봉',['normal','waiting','blocked','closed','error']);
  add('reportLoveLetter','POST','/love-letter-results/{id}/reports','LoveLetterReportAck','받은 쪽지 신고',['normal','error']);
  add('claimLoveLetterInvitation','POST','/love-letter-invitations/claim','LoveLetterStatus','사전 연결 링크 귀속',['normal','invalid-link','restricted','error'],'LoveLetterClaimInput');
  add('seedLoveLetter','POST','/admin/love-letters/seeds','LoveLetterSeeded','동의 확보된 사전 쪽지 등록',['normal','error'],'LoveLetterSeedInput',true);
  add('reissueLoveLetterInvitation','POST','/admin/love-letters/participants/{id}/invitation','LoveLetterSeeded','일회용 링크 재발급',['normal','error'],undefined,true);
  add('getLoveLetterReports','GET','/admin/love-letters/reports','LoveLetterReports','신고 목록',['normal','empty','error'],undefined,true);
  add('getLoveLetterReport','GET','/admin/love-letters/reports/{id}','LoveLetterAdminReport','신고 상세(관리자 개인정보 열람 감사)',['normal','error'],undefined,true);
  add('blockLoveLetter','POST','/admin/love-letters/{id}/block','LoveLetterBlockAck','쪽지 차단',['normal','error'],undefined,true);
  add('restrictLoveLetterParticipant','PUT','/admin/love-letters/participants/{id}/restriction','LoveLetterRestrictionAck','참여 제한·해제',['normal','error'],'LoveLetterRestrictionInput',true);
  add('configureLoveLetters','PUT','/admin/love-letters/configuration','LoveLetterFlag','운영 기간·동의문 설정, 기본 비활성화',['normal','error'],'LoveLetterSettingsInput',true);
  add('enableLoveLetters','PUT','/admin/love-letters/settings','LoveLetterFlag','기능 활성화·중지',['normal','error'],'LoveLetterEnabledInput',true);
}
