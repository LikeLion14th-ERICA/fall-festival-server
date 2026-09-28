# 2026 한양문화제 운영 카탈로그 초안

제1회 한양문화제 동심(2026-09-29 ~ 10-01)의 실제 운영 자료를 manifest 형식으로 옮긴 **초안**이다.
[개발용 manifest](../../../dev/catalog/README.md)와 달리 가상 값이 없고, 확인된 내용만 넣었다.
아직 받지 못한 필수 값은 `null`로 두었기 때문에 **이 상태로는 import할 수 없다.**

| 파일 | 용도 |
|---|---|
| `catalog-draft.json` | 전체 운영 카탈로그 초안. 아직 남은 필수값 때문에 단독 import 불가. |
| `prepare-artist-roster.py` | 현재 published export의 목 ARTIST만 실제 8팀으로 교체한 후보 manifest 생성. DB 쓰기 없음. |
| `goods-draft.json` | 굿즈 7종의 `POST /api/v2/admin/products` 요청 body 초안 |

`OperationalCatalogDraftTest`와 `OperationalGoodsDraftTest`는 두 가지를 확인한다. 초안의 빈 값이 아래
표의 항목뿐이라는 것, 그리고 그 항목만 채우면 import 검증과 상품 API 검증을 통과한다는 것이다.
값을 채우면 테스트의 채움 목록에서도 해당 항목을 뺀다.

이 저장소는 공개되어 있다. 계좌번호·예금주·연락처 같은 개인정보는 이 디렉터리에 넣지 않는다.

## 출처

- 행사명·날짜: 공식 포스터, 일정 이미지
- 출연진·설명 문구·대표곡: 각 아티스트 공식 게시물. 대표곡은 조회수·스트리밍·차트를 종합한 대중성과
  최근 타이틀곡 기준으로 팀이 선정했다.
- 주점·주점존 시설: 주점존 안내 이미지와 배치도
- 프로모션 스트리트: 올해 프로모션 스트리트 배치도
- 플리팅·스개팅: 플리팅 안내, 공식 모집 양식
- 굿즈: 유니폼 사전구매 카드뉴스. 사전구매가 끝나서 **현장 판매가**를 넣었다. 바람막이는 현장
  할인가 47,000원이다.
- 티켓·스탬프 안내 문구와 영어·중국어, 굿즈 이름의 영어·중국어:
  [승인 번역표](../../../docs/wiki/product/translations.md)

## 2026-09-28 기획팀 자료로 추가한 것

QA 중 라이브 사이트가 여전히 개발용 mock 카탈로그를 서빙하고 있는 걸 발견해(안태규,
`festival.likelionerica.com`이 실서비스 URL로 승격됐는데 실제 데이터로 재publish가 안
된 상태) 실제 publish를 서두르며 기획팀이 준 아래 자료를 반영했다. 여전히 남은
차단 항목(축제 운영시간, 나머지 부스 이미지, 기존 주점 메뉴 가격, 반입금지물품, 티켓 송금가능시간)이 있어 이번 반영만으로 publish는 안 된다.

- 플리마켓 셀러 12곳(셀러 10·MD스토어 1·운영본부 1)
- 프로모션 스트리트 신규 10곳(ic-pbl·신한은행·상담센터·인권센터·학생지원팀·글로벌
  사회혁신팀·멋쟁이사자처럼·투루카·CALIVAK·스포뉴바) + 기존 19곳 운영시간(10:00~18:00)·
  일부 SNS 갱신
- 파티스마트(주점존 프로모션 부스) 신규 추가
- 푸드트럭 12개 업체 개별 반영(기존 "구역 11/12번" 2개 자리 제거) — 메뉴·가격 포함
- 잔디공터 이벤트 4곳(피크닉존·하냥공방·하냥오락실·하냥문방구)
- 캠퍼스 이벤트 4곳(ERICA WALL·ERICA WAY·ERICA WISH·동심 찾아 3만리)
- 콘테스트 공연팀 8명을 `CONTEST` 카테고리 아티스트로 추가(2일차 학생 스페셜공연 2팀,
  3일차 TO:GETHER 본선 6팀) — 대표곡은 명세대로 넣지 않았다. 같은 시간대 팀은 공연
  하나로 묶었다(`performanceArtists`).
- 콘테스트 공연팀 8명 사진을 실제로 반영: `ops/catalog/hanyang-2026/assets/contest-artists/`에
  원본 커밋, `imageUrl`은 raw GitHub URL 참조(굿즈와 달리 관리자 업로드 API가 없어 기존
  artist 이미지 필드와 같은 방식을 그대로 씀). 부스·이벤트 쪽 "대표 이미지"는 전부 텍스트가
  박힌 홍보 포스터라(제목·날짜·소속팀 표기 포함) 카드 이미지로 못 쓴다고 판단해 넣었다가
  되돌렸다 — 아래 표 참고.

## 채워야 하는 값 (import 차단)

| 항목 | 위치 | 비고 |
|---|---|---|
| 축제일 운영 시작·종료 시각 | `festivalDays[].opensAt/closesAt` | 재학생존 혼잡도의 카탈로그 초기값이다. 관리자 저장 전에는 이 값을 쓰고, 관리자가 날짜별 시간을 저장하면 그 값이 우선한다. 2026-09-28 결정으로 익일 `00:00`까지 허용하므로 이를 피하려고 23:59로 줄이지 않는다. 주간·야간 중 실제 승인 시간은 확인 후 관리자가 날짜별로 저장한다. |
| 부스·주점 대표 이미지 | `spaces[].imageUrl/imageWidth/imageHeight` | 68곳 전부 null. 기획팀이 준 "대표 이미지" 파일들을 확인해보니 실제로는 행사 홍보 포스터(제목·날짜·소속팀 텍스트가 이미지에 박혀 있음)라 카드형 대표 이미지로 못 쓴다 — 한 번 imageUrl로 넣었다가 실제 파일 열어보고 되돌렸다(2026-09-28). 부스 카드용 별도 사진(텍스트 없는 순수 이미지)이 있어야 채울 수 있다. |
| 주점 메뉴 가격 | `spaceMenuItems[].priceAmount` | 기존 주점 4곳의 13개 메뉴는 여전히 가격 없음(0원은 무료로 표시되므로 넣지 않는다). 신규 추가한 푸드트럭 12곳·24개 메뉴는 실제 가격까지 채워 넣었다. |
| 공연 시각 | `performances[].endsAt` | 메인 라인업 8팀과 콘테스트 공연 2건의 시각은 확정 반영했다. 스개팅은 시작 17:30만 확인돼 종료 시각이 없다. |

## 실제 아티스트 8팀만 교체하는 게시 후보

사용자가 확정한 공연 시각과 배포된 프런트 카드 사진을 메인 8팀에 반영했다. 사진 URL은
https://festival.likelionerica.com/artists/ 아래 배포된 파일이며 8개 모두 HTTP 200과
원본 픽셀 크기를 확인했다. 한국어 소개·대표곡은 기존 초안의 내용을 쓴다. 아티스트 ID는
nct-wish, nowimyoung, kim-haon, rescene, heegyu, ahof, alphadrive1, fromis-9다.
alphadriveone은 현재 프런트의 DAY 3 ID alphadrive1로 통일했다. 리센느는 공식 영문 표기 re:scene에 맞춰 ID를 rescene으로 통일했다.
사용자가 제공한 Instagram 주소 8개도 링크로 넣었다.

| 날짜 | 공연 시각 (KST) | 아티스트 |
|---|---|---|
| 9/29 | 19:30~20:00 · 20:00~20:45 · 20:45~21:30 · 22:00~22:30 | NCT WISH · 나우아임영 · 김하온 · 리센느 |
| 10/1 | 20:00~20:40 · 20:40~21:20 · 21:20~22:00 · 22:00~22:30 | 희규 · 아홉 · 알파드라이브원 · 프로미스나인 |

전체 초안은 위의 운영시간·부스 이미지·주점 메뉴 가격·스개팅 종료 시각 등으로 계속
import 차단 상태다. 아래 절차는 현재 게시본의 다른 영역과 CONTEST를 그대로 유지하는
ARTIST 범위의 후보를 만든다. 따라서 다른 mock 콘텐츠는 남는다.

1. D는 DB 상태·역할과 DB/미디어 변경 전 짝 백업을 확인하고
   [읽기 전용 사전 점검](../../../docs/wiki/engineering/database-preflight.md)을 수행한다.
   STOP_AND_REVIEW면 D/DB 제공자의 검토와 명시적 변경 승인이 있기 전까지 중단한다.
   B(카탈로그 게시 담당)는 현재 축제 published revision UUID를 확인하고
   [로컬 카탈로그 워크벤치](../../../docs/wiki/engineering/catalog-workbench.md)에서 그 revision을
   export해 ignored out/ 아래 저장한다. export finding을 확인한다.
   LEGACY_TICKET_SCHEDULE_UNCONFIGURED 등 import 차단 finding이 있으면 중단한다.
   export·후보에는 운영 데이터가 있으므로 commit하지 않는다.
2. export의 festivalId가 대상 회차이고 baselineRevisionId가 방금 export한 published
   revision UUID와 같은지 확인한다. 다르면 새 published revision을 다시 확인하고 export한다.
   후보는 아래 명령으로 만들며 기존 파일을 덮어쓰지 않는다.

   ```powershell
   python ops/catalog/hanyang-2026/prepare-artist-roster.py out/published.json out/artist-candidate.json --festival-id <festival-uuid> --expected-revision <exported-revision-uuid>
   ```

3. 워크벤치에서 후보를 검증하고 현재 게시본과 비교한다. 차이는 ARTIST 3팀 제거·8팀 추가,
   이들 번역·링크·대표곡과 관련된 기존 ARTIST 공연 제거·8건 추가만 있어야 한다. CONTEST,
   festivalDays, timetableConfig와 나머지 section 변경이 있으면 중단한다. 현재 published
   pointer가 여전히 baselineRevisionId와 같아야 한다.
4. B가 후보를 가져와 draft를 게시한다. C는 B와 정한 통제 시간에 A1 백엔드를 재시작한다.
   B는 GET /api/v2/lineup의 9/29 ARTIST 4팀·9/30 0팀·10/1 4팀,
   GET /api/v2/artist-hyped의 8개 ID 참여 가능 여부, /readyz 및 공개 응답의
   meta.revision을 확인한다. 문제 시 [게시 CLI rollback](../../../docs/wiki/engineering/publishing.md)의
   --expected-current로 이전 published revision을 새 revision으로 복제·게시하고,
   C가 재시작한 뒤 다시 확인한다. Hyped 누적 수는 catalog 밖이므로 rollback 대상이 아니다.

프런트 후속 작업: DAY 3 ARTIST 강제 정적 목록 분기와 DAY 1의
mock-artist-wish 한 팀일 때 목록을 보충하는 분기를 제거하고, 실제 ID의 상세를 API에서
조회하도록 변경한다. 현재 프런트 저장소는 확인되지 않아 이 작업 범위에 포함하지 않는다.
그 전에도 DAY 3의 alphadrive1 ID는 이번 백엔드 후보와 일치하지만, 프런트가 정적
상세를 보여주는 문제는 남는다. 리센느 역시 현재 프런트의 rescene 정적 상세가
API 공연 시각을 가릴 수 있다. 프런트 영어 정적 이름·소개에 남은
RESCENE 표기도 re:scene으로 고쳐야 한다.

굿즈 7종은 실제 관리자 API로 상품과 이미지를 등록 완료했다(2026-09-21). 업로드 이미지는 원본이
1024×1024보다 작아 비율 유지 확대·투명 여백 추가로 처리했다. A1 이미지 저장 volume이 최초 배포
때 미연결 상태였다가 재배포로 한 번 유실된 뒤, volume 연결을 확인하고 재업로드했다(재시작 전후
서빙 상태로 영속성 검증). `goods-draft.json`의 `mediaId`는 최종 등록에 쓴 값으로 갱신했다.

굿즈 입금 계좌만 남았다 — 확인되는 대로 `AccountSettingsCliApplication`으로 CLI 등록한다.

## 비워 둔 선택 값

- 지도·핀·지도 목표: 지도 이미지와 좌표가 없다. 주점존 시설 13곳은 핀 없이 `places`에만 넣었다.
- 타임테이블 축(`timetableConfig`), 반입 금지 물품
  (QR값은 `https://festival.likelionerica.com/stamps`로, 스탬프 투어 날짜는 축제 3일
  전체(`2026-09-29`~`10-01`)로 확정 반영했다. 현장 상품 수령 운영 시간은 아직 없다.)
- 티켓 송금 가능시간·현장 수령 정확한 종료 시각: 티켓 가격은 25,000원으로 확정해 반영했다
  (`ticketGuide.unitPriceAmount`). 현장 수령(티켓부스) 운영은 13:00 시작이 확정됐지만, 종료는
  "마지막 무대 종료 30분 전"으로 공연 타임테이블이 확정돼야 정해지는 값이라 아직 고정 시각을
  넣지 않았다. `dailyTransferOpenTime/CloseTime`·`dailyPickupOpenTime/CloseTime` 네 값은
  검증기가 전부 채워야만 통과시키므로(all-or-nothing), 현장 수령 종료 시간이 확정되기 전에는 하나만
  채우지 않는다.
- 영어·중국어 공간·아티스트 번역: 공개 조건을 채우지 못하므로 한국어만 넣었다. 주점 이름·운영
  주체·메뉴의 승인 번역은 번역표에 있다.
- 지도·핀 연결이 없는 신규 space 다수(플리마켓 12곳, 푸드트럭 12곳, 신규 프로모션 부스 10곳,
  잔디공터 4곳, 캠퍼스 이벤트 4곳): `places`/`spaceMapTargets`에 아직 안 넣었다. 지도 이미지·좌표
  자체가 없어서 기존 28곳과 같은 상태다.

## 초안에서 정한 것 (확인 필요)

- **남월 우주정거장점 운영 주체:** 최신 자료의 `중앙동아리 HYCO`로 넣었다. 번역표에는 `HYCD`로 되어 있다.
- **프로모션 스트리트 상 5번째 이름:** 이름 칸의 `교목실`로 넣었다. 인접 설명에는 `교육실`로 적혀 있다.
- **반다나:** 색상(민트·블루)만 있고 사이즈가 없다. 옵션 상품은 사이즈가 필수라 `FREE` 한 가지를 두었다.
- **굿즈 중국어:** 색상 이름의 승인 번역이 없다. 그래서 옵션 상품 4종(유니폼 2종, 바람막이, 반다나)은
  한국어·영어만 넣고, 옵션 없는 3종(슬로건, 짐색, 키캡키링)에만 중국어 이름을 넣었다.
- **슬로건 영어:** 번역표대로 `Cheering Towel`로 넣었다. 번역표에도 실물과 맞는지 확인하라고 되어 있다.
- **이미지 대체 텍스트:** 상품은 상품 이름을 그대로 대체 텍스트로 썼다.
- **프로미스나인:** 설명 끝에 `* 지원 불참`을 넣었다.
- **공연:** 아티스트마다 공연 하나씩 만들었다. 같은 날 여러 팀이 한 공연으로 묶이면 합친다.
- **주류 판매 부스 운영시간:** `18:00 ~ 23:30`으로 넣었다. 올해 확정 자료가 아니라 작년 축제
  참고값이다.

## 넣지 않은 것

- **굿즈 입금 계좌:** 받은 것은 플리마켓 구글폼에서 추정한 계좌다. 확인되지 않은 계좌라 넣지 않았다.
  확인된 계좌는 [운영 계좌 설정](../../../docs/wiki/engineering/operational-account-settings.md) CLI로 등록한다.
- **행사명:** 축제명은 manifest가 아니라 `festivals.title`에 있다. 운영 DB에서 `제1회 한양문화제 동심`으로
  맞추는 작업은 서버 운영자가 한다.
- **"카운셀러" 이름 중복:** 프로모션 스트리트의 `promo-counselor`("카운셀러")와 주점존의
  `pubzone-tarot-booth`("카운셀러(타로부스)")는 이름은 비슷하지만 이미 서로 다른 space로
  분리돼 있다. 같은 팀인지 다른 팀인지는 확인하지 않았다 — 이름으로 구분되니 지금 상태로도
  화면엔 문제없다.

## import 순서

1. 위 표의 값을 채우고 두 초안 테스트를 통과시킨다.
2. [DB 읽기 전용 사전 점검](../../../docs/wiki/engineering/database-preflight.md) 뒤, 로컬 카탈로그
   워크벤치 또는 Catalog CLI로 import → validate → publish한다. festival id와 기준 revision은
   명령에서 명시한다.
3. 서버를 재시작한 뒤 `/readyz`와 공개 API를 확인한다.
4. 굿즈 이미지를 업로드하고, 받은 id를 넣어 상품을 등록한다. 입금 계좌는 운영 계좌 CLI로 따로 넣는다.
