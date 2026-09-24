// OpenAPI authoring source. Run npm run generate; server consumes openapi.json.
import { applyAdminContract } from './admin-contract.mjs';
const text = (description, extra = {}) => ({ type: 'string', minLength: 1, description, ...extra });
const integer = (description, minimum = 0, extra = {}) => ({ type: 'integer', minimum, description, ...extra });
const bool = description => ({ type: 'boolean', description });
const array = (items, description, extra = {}) => ({ type: 'array', items, description, ...extra });
const ref = name => ({ $ref: `#/components/schemas/${name}` });
const nullable = (schema, description) => ({ anyOf: [schema, { type: 'null' }], description });
const object = (properties, required = Object.keys(properties), description = '') => ({ type: 'object', properties, required, additionalProperties: false, description });
const enumeration = (values, description) => ({ type: typeof values[0] === 'number' ? 'integer' : typeof values[0], enum: values, description });
const id = text('표시명에서 유추하지 않는 불투명 ID. 회차 내 안정적으로 유지.', { pattern: '^[a-z0-9][a-z0-9-]{0,63}$' });
const date = text('Asia/Seoul 기준 YYYY-MM-DD.', { format: 'date' });
const timestamp = text('RFC 3339. 응답은 +09:00 offset을 포함하며 밀리초는 선택.', { format: 'date-time', pattern: '^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?\\+09:00$' });
const optionalText = nullable(text('등록된 표시 텍스트'), 'null이면 제목과 영역을 함께 숨김. 빈 문자열로 대체하지 않음.');
const locale = enumeration(['ko', 'en', 'zh-Hans', 'ja'], '준비 완료 언어만 config에서 제공. 생략 시 ko. 이미지 속 글자는 번역하지 않음.');
const availability = enumeration(['ON_SALE', 'SOLD_OUT'], '수량이 아닌 관리자 저장 판매 상태.');
const crowd = enumeration(['RELAXED', 'MODERATE', 'CROWDED', 'FULL'], '관리자가 선택하는 4단계. 운영 전·종료에도 당일 저장은 허용하지만 공개 상태는 시간 상태가 우선한다.');
const pinFilterGroup = enumeration(
  ['RESTROOM', 'PHOTO_BOOTH', 'SMOKING_AREA', 'TRASH_BIN'],
  '디자인의 지도 필터 칩 ID(화장실·포토부스·흡연구역·쓰레기통). 이 시설에 해당하는 PLACE 핀만 가지며 그 밖의 PLACE 핀과 AREA 핀은 null.'
);
const mapTargetDescription = '같은 published FestivalRevision의 현재 map version에 속한 PLACE 핀을 식별하는 canonical 연결. mapId·mapVersion·pinId·placeId는 함께 일치해야 하며 다른 revision·AREA 핀·구버전 핀은 허용하지 않음. 값이 null이면 연결되지 않은 상태.';
export const schemas = {
  Id: id, Date: date, Timestamp: timestamp, Locale: locale,
  Meta: object({ requestId: text('요청 추적 ID. 개인정보와 무관한 값.'), serverTime: timestamp, timezone: enumeration(['Asia/Seoul'], '축제 시간대'), festivalId: id, revision: integer('0은 특정 published FestivalRevision에 안전하게 귀속되지 않는 응답. 1 이상은 응답 데이터가 실제로 귀속된 FestivalRevision.revision_number.', 0), locale, mock: bool('목 서버는 항상 true. 운영 데이터와 구분.') }),
  ConditionalMeta: object({ timezone: enumeration(['Asia/Seoul'], '축제 시간대'), festivalId: id, revision: integer('revision에 귀속된 응답은 published FestivalRevision 번호(1 이상), 혼잡도처럼 revision-independent한 응답은 0.', 0), locale, mock: bool('목 서버는 항상 true. 운영 데이터와 구분.') }, undefined, '조건부 응답의 안정 메타. requestId와 serverTime은 매 요청 달라지므로 응답 헤더로만 제공하며 ETag 계산에 넣지 않는다.'),
  Error: object({ error: object({ code: text('프런트가 분기할 안정적인 오류 코드'), message: text('안전한 진단 문구. 화면별 오류 문구는 프런트 번역에서 선택.'), details: array(object({ field: text('요청 내 필드 또는 파라미터'), reason: text('검증 실패 이유') }), '추가 정보가 없으면 []'), retryable: bool('같은 요청 재시도 가능성. 화면 버튼 노출 요구와는 별개.') }), meta: ref('Meta') }),
  Money: object({ amount: integer('대한민국 원(KRW) 정수. 소수점·문자열·센트 단위 없음.', 0, { maximum: 9007199254740991 }), currency: enumeration(['KRW'], '금액 단위') }),
  Image: object({ url: text('HTTPS 또는 origin 기준 상대 URL. 목 자산은 /__mock/assets/ 아래.', { format: 'uri-reference' }), alt: text('이미지 대체 텍스트'), width: integer('원본 너비(px)', 1), height: integer('원본 높이(px)', 1) }),
  Link: object({ label: text('선택 언어의 링크 표시명'), url: text('외부 HTTPS 주소. 목에서는 송금 불가 예시 주소.', { format: 'uri', pattern: '^https://' }), target: enumeration(['_blank'], '새 탭. rel=noopener noreferrer 권장.') }),
  Channel: object({ id, label: text('공식 채널 표시명'), url: text('확인된 공식 채널 HTTPS 주소', { format: 'uri', pattern: '^https://' }), target: enumeration(['_blank'], '새 탭'), iconKey: text('프런트 아이콘 사전 키. URL이나 공식 명칭에서 추측하지 않음.') }),
  MapTarget: object({ mapId: id, placeId: id, pinId: id, mapVersion: text('좌표와 이미지 버전이 일치해야 함') }, undefined, mapTargetDescription),
  Config: object({ festival: object({ id, title: text('행사 표시명'), dates: array(date, '행사 날짜 오름차순. 자료 미확보 시 [].', { uniqueItems: true }), defaultDate: nullable(date, '축제 전 첫날·기간중 오늘·종료 후 마지막날. 날짜 미확보 시 null.') }), languages: array(object({ code: locale, label: text('원어 언어명') }), '준비 완료 언어만 순서대로 제공', { minItems: 1 }), links: object({ universityNotices: nullable(ref('Link'), '자료 미확보 시 null'), faq: nullable(ref('Link'), 'FAQ 외부 새 탭 연결. 승인 URL 미확보 시 null.'), officialChannels: array(ref('Channel'), '선정·준비 완료 채널만 제공') }) }),
  Crowding: object({ operatingDay: date, opensAt: timestamp, closesAt: timestamp, operatingStatus: enumeration(['BEFORE_OPEN', 'OPEN', 'CLOSED'], '게시된 FestivalDay 운영 일정으로 계산한 실제 운영 상태'), status: enumeration(['BEFORE_OPEN', 'RELAXED', 'MODERATE', 'CROWDED', 'FULL', 'CLOSED'], '운영 시간과 마지막 저장 상태를 결합한 최종 표시 상태'), savedLevel: nullable(crowd, '해당 운영일에 아직 저장하지 않았으면 null'), colorToken: nullable(enumeration(['green', 'orange', 'red', 'black'], '색상 의미 토큰. 정확한 디자인 HEX는 미정.'), '운영 전/종료면 null'), message: text('선택한 상태의 안내 문구'), updatedAt: nullable(timestamp, '운영 전·종료면 null. 같은 상태 재선택은 저장·시각을 갱신하지 않는다.'), timeBasis: enumeration(['NONE', 'OPENING', 'OPERATOR'], '시각 숨김 / 운영 시작 기준 / 관리자 수정시각') }),
  Notice: object({ id, type: enumeration(['GENERAL', 'LOST_FOUND'], '일반은 당일 등록분, 분실물은 날짜와 무관'), contentLocale: enumeration(['ko', 'en', 'zh-Hans', 'ja'], '요청한 공개 언어와 동일. 해당 언어의 제목·본문과 모든 링크 label이 준비되지 않은 공지는 목록에서 제외하며 다른 언어로 대체하지 않음.'), title: text('전체 제목'), body: text('전체 본문, plain text. HTML 실행 금지.'), links: array(ref('Link'), '본문 외부 링크. label은 응답의 contentLocale과 같은 언어. 이미지를 직접 표시하는 필드 없음.'), createdAt: timestamp }),
  Notices: object({ items: array(ref('Notice'), '현재 날짜·언어에 노출 가능한 공지 전체. createdAt 내림차순, 동률 id 오름차순.'), visibleIds: array(id, '이 snapshot에서 노출 가능한 ID 전체. 삭제·날짜 만료·번역 미완료를 즉시 제거할 때 사용.', { uniqueItems: true }), asOfDate: date }),
  GoodsTranslation: object({ name: text('상품명'), description: nullable(text('상품 소개'), '선택 소개') }),
  GoodsTranslations: object({ ko: ref('GoodsTranslation'), en: ref('GoodsTranslation'), 'zh-Hans': nullable(ref('GoodsTranslation'), '이 상품에 zh-Hans 번역이 없으면 null'), ja: nullable(ref('GoodsTranslation'), '이 상품에 ja 번역이 없으면 null') }, ['ko', 'en', 'zh-Hans', 'ja'], '상품은 공지와 같은 ko·en 필수 수동 입력 규칙을 따른다. zh-Hans·ja는 준비된 경우만 값, 없으면 명시적 null(공지의 property 생략과 다름).'),
  GoodsColor: object({ id, name: text('선택 언어로 해석된 색상명') }),
  GoodsSize: object({ id, label: text('선택 언어로 해석된 사이즈명') }),
  GoodsImage: object({ alt: text('상품의 요청 공개 언어와 같은 이미지 대체 텍스트'), masterUrl: text('same-origin master WebP API path', { format: 'uri-reference' }), thumbnail320Url: text('same-origin 320px WebP API path', { format: 'uri-reference' }), thumbnail640Url: text('same-origin 640px WebP API path', { format: 'uri-reference' }) }),
  Goods: object({ id, contentLocale: enumeration(['ko', 'en', 'zh-Hans', 'ja'], '요청한 공개 언어와 동일. 상품·이미지·색상·사이즈 번역이 모두 준비되지 않은 상품은 목록에서 제외하고 상세는 404이며 다른 언어로 대체하지 않음.'), name: text('상품명'), description: optionalText, price: ref('Money'), optionMode: enumeration(['SINGLE', 'OPTIONS'], 'SINGLE은 단일 판매 상태, OPTIONS는 색상×사이즈 조합별 판매 상태'), images: array(ref('GoodsImage'), 'sort_order 순 상품 이미지. 기존 이미지 없는 상품은 [].'), colors: array(ref('GoodsColor'), 'SINGLE이면 []'), sizes: array(ref('GoodsSize'), 'SINGLE이면 []') }),
  GoodsCombinationStatus: object({ combinationId: id, colorId: nullable(id, 'SINGLE 조합이면 null'), sizeId: nullable(id, 'SINGLE 조합이면 null'), status: availability }),
  Availability: object({ goodsId: id, name: text('공개 응답은 선택 공개 언어의 상품명이고, 관리자 응답은 한국어 상품명. 공개 언어 번역이 미완료인 상품은 목록에서 제외.'), combinations: array(ref('GoodsCombinationStatus'), '색상×사이즈 조합별 또는 SINGLE 단일 판매 상태. 수량 필드 없음.'), allSoldOut: bool('조합이 1개 이상이고 모두 SOLD_OUT일 때 true'), updatedAt: nullable(timestamp, '마지막 저장 성공 시각') }),
  GoodsList: object({ items: array(ref('Goods'), '요청 공개 언어 번역이 완결된 상품 전체. 품절 상품도 유지.') }),
  AvailabilityList: object({ items: array(ref('Availability'), '공개 응답은 요청 공개 언어 번역이 완결된 상품의 조합 상태이고, 관리자 응답은 전체 상품의 조합 상태. 실패 시 상품 목록과 독립적으로 오류 처리.') }),
  BankAccount: object({ bankName: text('은행명'), accountNumber: text('계좌 문자열. 목 값은 송금할 수 없는 MOCK-NOT-PAYABLE.'), holder: text('예금주') }),
  PaymentGuide: object({ goodsId: id, name: text('요청 공개 언어의 상품명. 해당 언어 번역이 미완료이면 404.'), price: ref('Money'), account: nullable(ref('BankAccount'), '운영 계좌 CLI로 승인된 GOODS 계좌. 미승인이면 null. 관리자 웹에서는 변경 불가.'), transferLink: nullable(ref('Link'), '승인된 계좌 설정의 송금 링크. 없으면 null.'), instructions: array(text('안내 문구'), '현장 확인·송금·지급 안내. 입금/지급 완료 상태 없음.'), locationText: optionalText, hoursText: optionalText }),
  Artist: object({ id, category: enumeration(['ARTIST', 'CONTEST'], '아티스트 / 콘테스트'), name: text('출연진 이름'), image: ref('Image'), introduction: optionalText, socialLinks: array(ref('Link'), '없으면 []와 영역 숨김'), songs: array(ref('Link'), '대표곡명과 YouTube 주소', { maxItems: 3 }), performances: array(object({ id, date, startsAt: timestamp, endsAt: timestamp }), '이 출연진의 등록 공연 일정') }),
  ArtistHypedItem: object({ artistId: id, hypedCount: integer('축제 전체에서 이 아티스트가 받은 누적 Hyped 수. 공연 날짜와 catalog revision에 독립적.', 0, { maximum: 9007199254740991 }) }),
  ArtistHypedSummary: object({ hypedEnabled: bool('서버의 Asia/Seoul 날짜가 게시된 FestivalDay 중 하나일 때 true. 그날의 운영 시간 밖에서도 true.'), items: array(ref('ArtistHypedItem'), '현재 게시된 ARTIST 전원. artistId 오름차순이며 미참여자는 0. CONTEST는 제외.') }),
  ArtistHypedIncrement: object({ artistId: id, hypedCount: integer('이번 요청의 원자적 +1 후 누적 Hyped 수.', 1, { maximum: 9007199254740991 }) }),
  ArtistHypedInput: object({}, [], '빈 JSON 객체만 허용한다. 로그인·참여자 식별자·멱등 키가 없다.'),
  Lineup: object({ date, category: enumeration(['ARTIST', 'CONTEST'], '선택 분류'), items: array(object({ artistId: id, performanceId: id, name: text('출연진명'), image: ref('Image'), order: integer('선택 날짜·분류 내 공연 순서', 1) }), '공연순, 동률 id순. + 버튼만 상세 이동.') }),
  Performance: object({ id, date, title: text('공연명'), artists: array(object({ id, name: text('출연진명') }), '출연진'), startsAt: timestamp, endsAt: timestamp, description: optionalText }),
  Timetable: object({ dates: array(date, '행사 날짜'), axis: object({ startTime: text('시간축 시작 HH:mm', { pattern: '^([01][0-9]|2[0-3]):[0-5][0-9]$' }), endTime: text('시간축 끝 HH:mm', { pattern: '^([01][0-9]|2[0-3]):[0-5][0-9]$' }) }), items: array(ref('Performance'), '날짜·시작시각·id 순. 공연 일정은 실시간 갱신 대상 아님.') }),
  ProhibitedItems: object({ items: array(text('반입 금지 물품명'), '선택 언어로 사전 번역한 고정 목록. 자료 대기 시 [].'), message: optionalText }),
  Space: object({ id, category: enumeration(['PUB', 'BOOTH', 'FLEA_MARKET', 'FOOD_TRUCK', 'STUDENT_COUNCIL_BOOTH', 'PROMOTION_BOOTH'], '주점/부스/플리마켓/푸드트럭/총학생회 부스/프로모션 부스'), name: text('장소명'), image: ref('Image'), locationText: text('목록 필수 위치 안내'), operator: optionalText, hoursText: optionalText, description: optionalText, contact: nullable(ref('Link'), '없으면 영역 숨김'), experience: optionalText, events: array(text('부스 이벤트'), '부스·총학생회 부스·프로모션 부스만, 없으면 []'), menu: array(object({ name: text('메뉴명'), price: ref('Money') }), '주점·푸드트럭만. 미확정 가격 메뉴의 노출 정책은 별도 결정.'), mapTarget: nullable(ref('MapTarget'), mapTargetDescription), bankTransfer: nullable(ref('BankTransfer'), '부스 수취 계좌. 상세 조회에서만 제공하고 목록에서는 항상 null. 공개할 계좌가 없으면 null이며 이때 계좌 안내와 송금 버튼을 표시하지 않음. 상세 응답은 Cache-Control: no-store로 매번 최신 계좌를 받음.') }),
  BankTransfer: object({ bankId: text('서비스 내부 은행 식별자. 토스 송금 링크의 은행 파라미터와 같은 값이라고 가정하지 않음.'), bankDisplayName: text('화면에 표시할 은행명'), accountNumber: text('앞자리 0을 보존하는 문자열 계좌번호'), accountHolderName: text('부스 담당자가 확인한 공개용 예금주명. 금융기관 실명 검증 결과가 아님.'), tossLinkEnabled: bool('토스 송금 연결 노출 여부. 실기기 검증 전에는 false이며 계좌 복사는 항상 별도로 제공.') }, undefined, '송금 안내 전용. 금액은 저장하지 않으며 메뉴 가격과 분리한다. 입금 확인은 웹앱 밖에서 현장 근무자가 한다.'),
  Spaces: object({ items: array(ref('Space'), '선택 분류 목록. 검색·날짜·페이지 파라미터 없음. 별 우선 정렬은 브라우저.') }),
  Map: object({ id, name: text('지도명'), kind: enumeration(['OVERVIEW', 'AREA'], '전체/구역 지도'), version: text('불변 이미지·좌표 버전'), image: ref('Image') }),
  Maps: object({ items: array(ref('Map'), '등록 지도. overviewId와 id로 연결.'), overviewId: nullable(id, '등록 지도 없으면 null') }),
  PinFilter: object({ id: pinFilterGroup, label: text('현재 locale의 필터 표시명') }, undefined, '현재 지도에 실제 PLACE 핀이 있는 필터만 반환. `전체`는 클라이언트 기본값이라 포함하지 않음.'),
  Pin: object({ id, category: text('핀 세부 종류 ID. 운영 목록에서 제공하며 명칭을 enum으로 고정하지 않음.'), filterGroup: nullable(pinFilterGroup, '화장실·포토부스·흡연구역·쓰레기통 PLACE 핀만 값을 가짐. 그 밖의 PLACE 핀은 null이며 `전체`에서만 표시. AREA 핀은 null이며 필터와 무관하게 항상 표시.'), label: text('선택 언어 표시명'), x: { type: 'number', minimum: 0, maximum: 1, description: '이미지 왼쪽 기준 가로 비율. 지도 조작과 무관.' }, y: { type: 'number', minimum: 0, maximum: 1, description: '이미지 위쪽 기준 세로 비율.' }, target: { oneOf: [object({ kind: enumeration(['PLACE'], '장소 팝업. filterGroup이 있으면 해당 필터에서, 없으면 `전체`에서만 표시.'), placeId: id }), object({ kind: enumeration(['AREA'], '팝업 없이 구역 지도 이동. filterGroup은 null이며 필터와 무관하게 항상 표시.'), mapId: id })] } }),
  Pins: object({ mapId: id, mapVersion: text('요청한 이미지 버전과 동일'), filters: array(ref('PinFilter'), '현재 mapId·mapVersion의 PLACE 핀에 실제로 존재하는 필터만 반환. 화장실·포토부스·흡연구역·쓰레기통 고정 순서이며 locale 표시명을 포함. AREA 핀은 제외.'), items: array(ref('Pin'), '핀 목록. 서버 필터 query는 제공하지 않으며 클라이언트가 PLACE의 filterGroup으로 표시를 제어하고 AREA는 항상 표시.') }),
  Place: object({ id, kind: enumeration(['SPACE', 'FACILITY', 'LANDMARK'], '유형별 팝업 구성'), name: optionalText, locationText: optionalText, hoursText: optionalText, description: optionalText, usage: optionalText, spaceId: nullable(id, 'SPACE 유형만 상세 연결. 나머지는 null.') }),
  TicketGuide: object({ date, status: enumeration(['BEFORE_FESTIVAL', 'TRANSFER_OPEN', 'DAILY_CLOSED', 'FESTIVAL_ENDED', 'UNCONFIGURED'], '시간별 송금 안내 상태. 일정이 없거나 TICKET 계좌 설정이 없으면 UNCONFIGURED다.'), unitPrice: nullable(ref('Money'), '가격 자료 대기 시 null. 0원과 다름.'), transferOpensAt: nullable(timestamp, '운영 자료 대기 시 null'), transferClosesAt: nullable(timestamp, '운영 자료 대기 시 null'), pickupOpensAt: nullable(timestamp, '운영 자료 대기 시 null'), pickupClosesAt: nullable(timestamp, '운영 자료 대기 시 null'), account: nullable(ref('BankAccount'), 'catalog revision 밖의 TICKET 계좌 설정에서 제공. 계좌 설정이 없거나 송금 제공 시간 밖이면 null.'), transferLink: nullable(ref('Link'), '링크 표시명 출처가 정해지기 전까지 항상 null. 계좌 설정은 URL만 보관한다.'), paymentSettingsVersion: nullable(integer('현재 TICKET 계좌 설정의 version. 값이 바뀌면 계좌 설정이 바뀐 것이며 응답 ETag도 함께 바뀐다.', 1), '계좌를 한 번도 설정하지 않았으면 null. 설정을 해제한 뒤에도 version은 남는다.'), mapTarget: nullable(ref('MapTarget'), mapTargetDescription), instructions: array(text('안내'), '승인된 현장 안내. 목에서 환불 정책을 임의로 확정하지 않음.') }),
  StampGuide: object({ title: text('행사 제목'), dates: array(date, '실제 행사 기간'), instructions: array(text('참여·상품 안내'), '없으면 []'), reward: object({ name: text('경품명'), locationText: optionalText, hoursText: optionalText, notice: text('당일 1회·소진 시 현장 안내') }), dailyLimit: enumeration([4], '당일 최대 적립'), timezone: enumeration(['Asia/Seoul'], '자정 초기화'), qrValue: nullable(text('스탬프투어 시작 안내용 공통 주소. 적립에는 쓰지 않으며 비밀키가 아님.'), '배포 방식·책임 미합의 시 null. 적립은 부스별 QR 링크의 토큰(StampCollectionInput.token)으로 한다.') }),
  StampCard: object({ date: text('축제 시간대(Asia/Seoul) 기준 오늘 날짜. 자정이 지나면 다시 START해야 새 스탬프판이 열린다.', { format: 'date' }), dailyLimit: enumeration([4], '하루 최대 적립 개수'), stamps: array(ref('StampCardStamp'), '오늘 적립한 부스. 부스당 하루 1개, 적립 시각순.', { maxItems: 4 }), rewardClaimed: bool('오늘 상품을 받았는지. 서버가 수령 인증 성공 때 기록한다.') }, undefined, '이 브라우저의 익명 참여자(HttpOnly 쿠키)의 오늘 스탬프판. 오늘 START(POST /stamp-participants) 뒤에만 있고, 그 전에는 STAMP_NOT_STARTED(404)이므로 시작 화면을 보여 준다. 계정·복구 없음.'),
  StampCardStamp: object({ boothId: id, boothName: nullable(text('부스 표시명'), '게시 catalog에서 사라진 부스면 null'), collectedAt: timestamp }),
  StampCollectionInput: object({ token: text('부스 QR 링크의 b 쿼리 값. 그대로 보내며 저장·로그하지 않는다. 형식이 다르거나 없는 토큰은 INVALID_STAMP_TOKEN.', { pattern: '^[A-Za-z0-9_-]{16,128}$', minLength: 16, maxLength: 128, writeOnly: true }) }),
  StampReceiptVerificationInput: object({ code: text('멋사 부스 담당자가 현장에서 입력하는 6자리 숫자 수령 인증 코드. 서버는 hash만 보관하며 클라이언트·로그에 저장하지 않음. 형식이 다르거나 틀리면 같은 INVALID_RECEIPT_CODE로 거절.', { pattern: '^[0-9]{6}$', minLength: 6, maxLength: 6, writeOnly: true }) }),
  StampReceiptVerification: object({ verified: enumeration([true], '서버가 현장 수령 인증 코드와 오늘 스탬프 4개를 확인하고 오늘 수령을 기록했음을 뜻함.') }),
  Translation: object({ title: text('제목. 200자는 목 입력 검증 제안.', { maxLength: 200 }), body: text('본문. 10000자는 목 입력 검증 제안.', { maxLength: 10000 }) }),
  Translations: object({ ko: ref('Translation'), en: ref('Translation'), 'zh-Hans': ref('Translation'), ja: ref('Translation') }, ['ko'], 'ko는 필수. 나머지 언어는 준비된 경우만 property로 포함하고 없으면 생략한다.'),
  NoticeTranslations: object({ ko: ref('Translation'), en: ref('Translation'), 'zh-Hans': ref('Translation'), ja: ref('Translation') }, ['ko', 'en'], '공지는 ko·en 필수 수동 입력. 자동 번역 없음. zh-Hans·ja는 준비된 경우만 property로 포함하고 없으면 생략한다.'),
  NoticeLinkInput: object({ url: text('host가 있는 외부 HTTPS 주소. 언어 공통.', { format: 'uri', pattern: '^https://[^/?#\\s]+' }), labels: object({ ko: text('한국어 label'), en: text('영어 label'), 'zh-Hans': nullable(text('중국어 간체 label'), '이 공지에 zh-Hans 번역이 없으면 null'), ja: nullable(text('일본어 label'), '이 공지에 ja 번역이 없으면 null') }, ['ko', 'en', 'zh-Hans', 'ja'], '본문이 있는 언어는 label 필수, 본문이 없는 언어는 반드시 null.') }, undefined, '공지 링크는 언어 공통 URL 하나에 언어별 label을 붙인다.'),
  NoticeInput: object({ type: enumeration(['GENERAL', 'LOST_FOUND'], '공지 유형'), translations: ref('NoticeTranslations'), links: array(ref('NoticeLinkInput'), '외부 링크'), templateId: nullable(id, '선택적 원본 템플릿 ID. 직접 작성이면 null.') }),
  AdminNotice: object({ id, type: enumeration(['GENERAL', 'LOST_FOUND'], '공지 유형'), translations: ref('Translations'), links: array(ref('NoticeLinkInput'), '외부 링크'), templateId: nullable(id, '원본 템플릿'), createdAt: timestamp, updatedAt: timestamp }, undefined, 'translations의 ko·en 필수는 신규 저장(NoticeInput)에만 적용한다. en이 없는 기존 공지도 조회는 가능하다.'),
  AdminNotices: object({ items: array(ref('AdminNotice'), '삭제 제외 전체 공지. updatedAt 내림차순·id 오름차순. 검색·페이지 없음.') }),
  Template: object({ id, name: text('템플릿 이름'), translations: ref('Translations') }),
  Templates: object({ items: array(ref('Template'), '준비된 사전 템플릿') }),
  CrowdingInput: object({ level: crowd, confirmFull: bool('FULL 선택 시 true 필수. 나머지는 false 또는 생략.') }, ['level']),
  AvailabilityInput: object({ status: availability }),
  Deleted: object({ id, deleted: enumeration([true], '삭제 성공. 이후 사용자 조회에 노출되지 않음.') }),
};

const param = (name, schema, description, required = false) => ({ name, in: 'query', required, schema, description });
const lang = param('locale', { ...locale, default: 'ko' }, 'config.languages의 준비 완료 언어만 요청. 알려졌지만 준비되지 않은 언어는 LOCALE_NOT_READY, 알 수 없는 값은 INVALID_QUERY. Accept-Language는 사용하지 않음.');
const pathParam = name => ({ name, in: 'path', required: true, schema: id, description: '목의 실제 ID는 /__mock/catalog 또는 examples.json 참고.' });
// status = 계약 근거 상태, schema/URI 설계 자체는 이 v2에서 처음 제안한 프런트 연동 계약.
export const operations = [
  ['getConfig','GET','/config','Config','홈 공통 설정',['HOME'],[],['normal','empty','missing-optional','faq-ready','error']],
  ['getCrowding','GET','/crowding','Crowding','홈 혼잡도',['HOME'],[],['normal','before-open','closed','unmodified','unconfigured','error']],
  ['getNotices','GET','/notices','Notices','사용자 공지 전체',['HOME','NOTICE-LIST'],[],['normal','empty','missing-optional','new-notice','deleted','error']],
  ['getGoods','GET','/goods','GoodsList','상품 목록',['GOODS-LIST'],[],['normal','empty','error']],
  ['getGoodsAvailability','GET','/goods-availability','AvailabilityList','상품 목록의 판매 상태',['GOODS-LIST'],[],['normal','empty','sold-out','error']],
  ['getGood','GET','/goods/{goodsId}','Goods','상품 상세',['GOODS-DETAIL'],[],['normal','missing-optional','not-found','error']],
  ['getGoodAvailability','GET','/goods/{goodsId}/availability','Availability','상품 상세의 판매 상태',['GOODS-DETAIL'],[],['normal','sold-out','not-found','error']],
  ['getPaymentGuide','GET','/goods/{goodsId}/payment-guide','PaymentGuide','굿즈 계좌 안내',['GOODS-PAYMENT'],[],['normal','missing-optional','not-found','error']],
  ['getLineup','GET','/lineup','Lineup','날짜·분류별 라인업',['SHOW-LINEUP'],[param('date',date,'생략하면 config.defaultDate. 제공되지 않는 행사 날짜는 400.'),param('category',{...schemas.Artist.properties.category,default:'ARTIST'},'기본 ARTIST')],['normal','empty','error']],
  ['getArtist','GET','/artists/{artistId}','Artist','출연진 상세',['SHOW-ARTIST'],[],['normal','missing-optional','not-found','error']],
  ['getArtistHyped','GET','/artist-hyped','ArtistHypedSummary','아티스트별 Hyped 누적 수와 참여 가능 상태',['SHOW-LINEUP','SHOW-ARTIST'],[],['normal','closed','ended','empty','error']],
  ['postArtistHyped','POST','/artists/{artistId}/hyped','ArtistHypedIncrement','아티스트 Hyped +1',['SHOW-ARTIST'],[],['normal','closed','ended','contest','not-found','error'],'ArtistHypedInput'],
  ['getTimetable','GET','/timetable','Timetable','3일 타임테이블',['SHOW-TIMETABLE'],[],['normal','empty','error']],
  ['getPerformance','GET','/performances/{performanceId}','Performance','공연 정보 팝업',['SHOW-POPUP'],[],['normal','missing-optional','not-found','error']],
  ['getProhibitedItems','GET','/prohibited-items','ProhibitedItems','고정 반입 금지 물품 안내',['SHOW-TIMETABLE'],[],['normal','empty','error']],
  ['getSpaces','GET','/spaces','Spaces','부스&마켓 목록',['BOOTH-LIST'],[param('category',enumeration(['ALL','PUB','BOOTH','FLEA_MARKET','FOOD_TRUCK','STUDENT_COUNCIL_BOOTH','PROMOTION_BOOTH'],'생략하면 ALL'),'분류 선택')],['normal','empty','error']],
  ['getSpace','GET','/spaces/{spaceId}','Space','부스·주점·플리마켓 상세',['BOOTH-DETAIL'],[],['normal','missing-optional','not-found','error']],
  ['getMaps','GET','/maps','Maps','지도 목록',['MAP-OVERVIEW','MAP-AREA'],[],['normal','empty','error']],
  ['getMap','GET','/maps/{mapId}','Map','지도 이미지·버전',['MAP-OVERVIEW','MAP-AREA'],[],['normal','not-found','error']],
  ['getPins','GET','/maps/{mapId}/pins','Pins','지도별 핀',['MAP-OVERVIEW','MAP-AREA'],[param('mapVersion',text('조회한 Map.version'),'이미지와 다른 버전 요청은 409',true)],['normal','empty','not-found','version-conflict','error']],
  ['getPlace','GET','/places/{placeId}','Place','장소 팝업',['MAP-POPUP'],[],['normal','missing-optional','not-found','error']],
  ['getTicketGuide','GET','/ticket-guide','TicketGuide','외부인 티켓 안내',['TICKET'],[],['normal','before-open','closed','ended','unconfigured','error']],
  ['getStampGuide','GET','/stamp-guide','StampGuide','스탬프 안내·공통 QR',['STAMP-START','STAMP-COLLECT','STAMP-REWARD'],[],['normal','missing-optional','error']],
  ['startStampParticipation','POST','/stamp-participants','StampCard','스탬프투어 시작(축제일마다 1회)·익명 참여 쿠키 발급',['STAMP-START'],[],['normal','already-started','error']],
  ['getStampCard','GET','/stamp-card','StampCard','오늘의 스탬프판',['STAMP-COLLECT','STAMP-REWARD'],[],['normal','empty','not-started','error']],
  ['collectStamp','POST','/stamp-collections','StampCard','부스 QR 스탬프 적립(부스당 하루 1회)',['STAMP-COLLECT'],[],['normal','not-started','invalid-token','already-collected','card-full','reward-claimed','error'],'StampCollectionInput'],
  ['verifyStampReceipt','POST','/stamp-receipt-verifications','StampReceiptVerification','스탬프 상품 수령 인증',['STAMP-REWARD'],[],['normal','invalid-code','card-incomplete','reward-claimed','error'],'StampReceiptVerificationInput'],
  ['getAdminCrowding','GET','/admin/crowding','Crowding','관리자 혼잡도',['ADM-CROWD'],[],['normal','before-open','closed','unmodified','unconfigured','error']],
  ['putAdminCrowding','PUT','/admin/crowding','Crowding','실제 FestivalDay 혼잡도 저장·운영 전후 허용·비운영일 409·동일 상태 시각 유지',['ADM-CROWD'],[],['normal','full','not-festival-day','error'],'CrowdingInput'],
  ['getAdminNotices','GET','/admin/notices','AdminNotices','관리자 공지 목록',['ADM-NOTICE-LIST'],[],['normal','empty','error']],
  ['postAdminNotice','POST','/admin/notices','AdminNotice','공지 등록(검토 필요)',['ADM-NOTICE-EDIT'],[],['normal','error'],'NoticeInput'],
  ['getAdminNotice','GET','/admin/notices/{noticeId}','AdminNotice','공지 수정 초기값',['ADM-NOTICE-EDIT'],[],['normal','missing-optional','not-found','error']],
  ['putAdminNotice','PUT','/admin/notices/{noticeId}','AdminNotice','공지 수정(검토 필요)',['ADM-NOTICE-EDIT'],[],['normal','not-found','error'],'NoticeInput'],
  ['deleteAdminNotice','DELETE','/admin/notices/{noticeId}','Deleted','공지 삭제',['ADM-NOTICE-DELETE'],[],['normal','already-deleted','error']],
  ['getTemplates','GET','/admin/notice-templates','Templates','공지 템플릿 목록',['ADM-NOTICE-TEMPLATE'],[],['normal','empty','error']],
  ['getTemplate','GET','/admin/notice-templates/{templateId}','Template','템플릿 초기값',['ADM-NOTICE-TEMPLATE'],[],['normal','missing-optional','not-found','error']],
  ['getAdminGoods','GET','/admin/goods','AvailabilityList','관리자 상품별 판매 상태',['ADM-GOODS'],[],['normal','empty','sold-out','error']],
  ['putAdminAvailability','PUT','/admin/goods/{goodsId}/sizes/{sizeId}','Availability','사이즈 판매 상태 저장',['ADM-GOODS'],[],['normal','sold-out','not-found','error'],'AvailabilityInput'],
].map(([operationId,method,path,schema,summary,screens,parameters,scenarios,input]) => ({ operationId,method,path:`/api/v2${path}`,schema,summary,screens,parameters:[...(path.startsWith('/admin')?[]:[lang]),...parameters,...[...path.matchAll(/\{(\w+)\}/g)].map(m=>pathParam(m[1]))],scenarios,input,admin:path.startsWith('/admin'),provisional: ['postAdminNotice','putAdminNotice','getStampGuide','putAdminAvailability'].includes(operationId) }));
Object.assign(operations.find(operation=>operation.operationId==='verifyStampReceipt'), {
  successStatus: 200,
  cacheControl: 'no-store',
});
operations.find(operation=>operation.operationId==='getPaymentGuide').cacheControl='no-store';
for(const operationId of ['getArtistHyped','postArtistHyped']){
  const operation=operations.find(candidate=>candidate.operationId===operationId);
  operation.cacheControl='no-store';
  operation.cacheControlOnErrors=true;
}
Object.assign(operations.find(operation=>operation.operationId==='postArtistHyped'), {
  successStatus: 200,
  responseOverrides: {409:{description:'축제일 밖에는 Hyped 참여 불가',code:'HYPED_CLOSED',message:'축제일에만 기대돼요에 참여할 수 있습니다.',retryable:false}},
});
// Booth stamps: an anonymous HttpOnly participant cookie, never cached.
Object.assign(operations.find(operation=>operation.operationId==='startStampParticipation'), {
  successStatus: 201,
  additionalSuccessStatuses: [200],
  cacheControl: 'no-store',
});
operations.find(operation=>operation.operationId==='getStampCard').cacheControl='no-store';
Object.assign(operations.find(operation=>operation.operationId==='collectStamp'), {
  successStatus: 200,
  cacheControl: 'no-store',
});
// The ticket guide combines static catalog content with the current TICKET
// account setting, so clients poll it and revalidate with If-None-Match.
Object.assign(operations.find(operation=>operation.operationId==='getTicketGuide'), {
  conditional: true,
  cacheControl: 'private, no-cache',
});

applyAdminContract(schemas,operations);
export const envelopeSchema = (name, metaName = 'Meta') => object({ data: ref(name), meta: ref(metaName) });
