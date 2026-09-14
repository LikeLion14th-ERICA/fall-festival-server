# 가을 축제 논리 ERD

이 디렉터리의 ERD는 최신 Product v5와 API v2 draft.3을 근거로 만든 **구현 전 논리 모델**이다.
현재 Spring Boot scaffold에 Entity·Flyway migration·운영 DB schema가 구현되어 있다는 뜻이 아니다.
구현을 시작할 때는 [데이터 모델·ERD 설계 검토](../wiki/engineering/data-model.md)의 결정 게이트와
관계 제약을 먼저 확인한다.

| 영역 | 인터랙티브 도식 | 재생성 원본 | 핵심 범위 |
|---|---|---|---|
| 게시·운영일 | [HTML](festival-publication-erd.html) | [JSON](festival-publication-erd.json) | FestivalRevision, 운영일, 혼잡도, 공지·번역·링크·템플릿 |
| 굿즈 | [HTML](festival-goods-erd.html) | [JSON](festival-goods-erd.json) | 색상·사이즈·실제 옵션 조합, 판매 상태, 송금 안내 |
| 공연·출연진 | [HTML](festival-program-erd.html) | [JSON](festival-program-erd.json) | 일정, 출연진, 대표곡·SNS, 반입 금지 안내 |
| 부스·지도 | [HTML](festival-map-erd.html) | [JSON](festival-map-erd.json) | 공간·메뉴·이벤트, 장소, 지도 자산 version, 핀·구역 |
| 티켓·스탬프·외부 안내 | [HTML](festival-guide-erd.html) | [JSON](festival-guide-erd.json) | 티켓·스탬프 안내, 경품, 공식 외부 링크 |

모든 HTML은 독립 실행 파일이다. 원본 JSON을 수정한 뒤에는 Archify showcase 검증과 HTML delivery,
Chrome visual-check를 다시 실행한다. 생성 Viewer의 고정 UI는 영어일 수 있으나 제목·도식·카드는
한국어로 작성했다.

## 공통 제외 범위

- 공개 서비스 사용자 계정, 로그인, 방문·클릭·관람 이력
- 굿즈 실제 수량·자동 품절·주문·입금 확인·지급 완료·예약
- 티켓 수량·주문·결제·환불·입장·수령 완료와 구매자 정보
- 스탬프 사용자별 참여 상태, 누적 수, `claimed` 이력, 엄격한 중복 차단

관리자 인증·권한·감사 actor 모델은 필요하지만 아직 Product 수준에서 확정되지 않았다. 이를
추측해 Entity나 migration으로 만들지 않는다.
