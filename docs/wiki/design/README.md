# Design / Wireframe Reference

[위키 홈](../README.md)

## 읽는 때

- Product 기능과 사용자 화면 흐름을 함께 확인할 때
- 화면 상태나 외부 이동 위치를 Figma에서 바로 찾아볼 때

Figma는 화면 설계의 기준이며, 기능 요구사항은 [`docs/wiki/product/**`](../product/overview.md)를
기준으로 한다. 이 문서는 백엔드 개발자가 Product 기능과 실제 화면 흐름을 빠르게
연결해서 확인하기 위한 참고용 인덱스다.

Figma의 문구와 날짜·장소·가격·계좌 등은 화면 이해를 위한 예시일 수 있다. 운영값과
미확정 정책은 Product 문서와 [결정 대기](../product/decisions.md)를 우선하며, 이 문서의
링크만으로 API·DB·인증·상태 저장 방식을 확정하지 않는다.

## 전체 와이어프레임

- [전체 Figma 와이어프레임](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=438-2) (`438:2`)
- [와이어프레임 읽는 법](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=438-5) (`438:5`)
- [화면 ↔ 기능 ID 인덱스](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=450-40) (`450:40`)

## Home

Product:

- [`HOME-001`](../product/home.md)
- [`HOME-002`](../product/home.md)
- [`HOME-003`](../product/home.md)
- [`HOME-004`](../product/home.md)
- [`HOME-005`](../product/home.md)
- [`HOME-006`](../product/home.md)
- [`HOME-007`](../product/home.md)
- [`HOME-008`](../product/home.md)
- [`WELCOME-001`](../product/home.md)

Figma:

- [Home](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=438-23) (`438:23`)
- [혼잡도 안내](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=441-2) (`441:2`)
- [언어 선택](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=441-98) (`441:98`)
- [외부 웹 연결 예시](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=452-3) (`452:3`)

Backend 참고:

- Home에서 공지, 스탬프투어, 외부인 티켓, 에리카 웰컴 데이와 외부 채널로 이어지는
  진입 흐름을 확인할 수 있다.
- 외부 웹 연결 예시는 공통 이동 형태를 보여 주며, 기능별 실제 목적지는 Product 문서를 따른다.

## Notice

Product:

- [`NOTICE-001`](../product/notice.md)

Figma:

- [Notice](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=438-29) (`438:29`)
- [Home·알림 메시지 규칙 보드](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=448-187) (`448:187`)

Backend 참고:

- Home의 공지 진입과 공지 목록 화면의 연결을 확인할 수 있다.

## Stamp Tour

Product:

- [`STAMP-001`](../product/stamp.md)

Figma:

- [Stamp Tour 시작](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=438-31) (`438:31`)
- [스탬프 수집 3/4](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=438-33) (`438:33`)
- [QR 스캔](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=456-3) (`456:3`)
- [경품 수령 안내](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=444-2) (`444:2`)
- [스탬프 완료 4/4](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=457-2) (`457:2`)
- [경품 수령 완료](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=457-62) (`457:62`)
- [카메라 권한 거부](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=457-149) (`457:149`)
- [유효하지 않은 QR](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=457-202) (`457:202`)
- [스탬프 규칙 보드](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=449-2) (`449:2`)

Backend 참고:

- 시작, 수집, 스캔, 완료, 수령과 오류 상태의 사용자 흐름을 확인할 수 있다.
- QR 계약과 상태 저장 방식은 Figma 링크에서 추론하지 않고 `STAMP-001`을 따른다.

## External Ticket

Product:

- [`TICKET-001`](../product/ticket.md)

Figma:

- [External Ticket](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=438-37) (`438:37`)
- [외부인 티켓 규칙 보드](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=449-75) (`449:75`)
- [토스 앱 연결 예시](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=452-26) (`452:26`)

Backend 참고:

- 송금 안내와 외부 앱 이동 흐름을 확인할 수 있다. 가격·계좌·티켓존 등 운영값은 Product의 미확정 상태를 따른다.

## Goods Shop

Product:

- [`GOODS-001`](../product/goods.md)

Figma:

- [상품 목록](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=477-3) (`477:3`)
- [상품 상세](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=478-3) (`478:3`)
- [계좌·토스·복사 흐름](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=478-65) (`478:65`)
- [재고 조회 오류](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=480-12) (`480:12`)
- [굿즈 규칙 보드](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=481-114) (`481:114`)

Backend 참고:

- 목록, 상세, 옵션별 품절 상태, 송금 안내와 재고 조회 오류의 표시 흐름을 확인할 수
  있다. `구매 가능 / 품절`은 담당자가 직접 저장한 상태이며 실제 수량을 뜻하지 않는다.

## Performance

Product:

- [`SHOW-001` 라인업](../product/lineup.md)
- [`SHOW-001` 타임테이블](../product/timetable.md)

Figma:

- [Lineup](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=530-77) (`530:77`)
- [Performer Detail](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=530-79) (`530:79`)
- [Timetable](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=530-81) (`530:81`)
- [공연 상세 팝업](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=530-83) (`530:83`)
- [공연 규칙 보드](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=542-76) (`542:76`)

Backend 참고:

- 라인업에서 출연진 상세로, 타임테이블에서 공연 상세 팝업으로 이어지는 흐름을 확인할 수 있다.
- 타임테이블 상단 안내는 Product의 고정 반입 금지 물품 안내를 기준으로 하며 관리자
  공연 공지 기능을 의미하지 않는다.

## Booth & Market

Product:

- [`BOOTH-001`](../product/spaces.md)

Figma:

- [부스&마켓 목록](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=530-85) (`530:85`)
- [부스 상세](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=530-87) (`530:87`)
- [주점 상세](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=530-89) (`530:89`)
- [플리마켓 상세](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=530-91) (`530:91`)
- [부스&마켓 규칙 보드](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=543-76) (`543:76`)

Backend 참고:

- 부스·주점·플리마켓 목록에서 유형별 상세로 이동하는 흐름을 확인할 수 있다.

## Map

Product:

- [`MAP-001`](../product/map.md)

Figma:

- [전체 지도](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=580-78) (`580:78`)
- [필터 선택](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=580-80) (`580:80`)
- [일반 장소 팝업](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=580-82) (`580:82`)
- [지도 로딩 실패](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=580-84) (`580:84`)
- [주점 구역 상세](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=580-86) (`580:86`)
- [플리마켓 구역 상세](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=580-88) (`580:88`)
- [부스 구역 상세](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=580-90) (`580:90`)
- [구역 핀 상세 팝업](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=580-92) (`580:92`)
- [지도 핀·장소 분류 보드](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=596-76) (`596:76`)
- [지도 규칙 보드](https://www.figma.com/design/07TGnBzcBOboiHOUWadmGC/%EC%B6%95%EC%A0%9C-%EC%96%B4%ED%94%8C-%EA%B8%B0%ED%9A%8D?node-id=597-76) (`597:76`)

Backend 참고:

- 전체 지도, 필터, 장소·구역별 상세, 로딩 실패의 표시 흐름을 확인할 수 있다.
- 필터는 현재 지도에 실제 존재하는 타입만 제공하고 한 번에 하나만 선택한다. 전체
  지도에는 `주점 / 플리마켓 / 부스 / 대운동장 / 푸드트럭 / 호수공원` 구역 이동 핀을
  제공한다.
- 최종 지도·디자인 자산, 실제 핀 좌표·장소 위치·명칭, 좌표 데이터 모델과 운영
  데이터는 확정 자료를 받은 뒤 반영한다.

## 확인 필요

- `HOME-005` FAQ: 외부 FAQ 페이지를 새 탭으로 여는 동작이 확정됐다. 독립 앱 Frame은
  필요하지 않으며 실제 URL은 운영 자료를 받은 뒤 연결한다.
- `WELCOME-001`: Home Frame `438:23`에서 진입 UI를 확인할 수 있지만 전용 앱 Frame은
  없다. 외부 학교 소개 페이지를 새 탭으로 여는 기능이며 실제 콘텐츠와 URL은 자료
  수령 후 확정한다.
- `HOME-004`, `HOME-008`: Figma에는 기능별 독립 화면 대신 공통 외부 웹 연결 예시만 있다.
  실제 목적지와 동작은 각 Product 요구사항을 따른다.
- `SHOW-001`: 출연진 상세의 펼치기 UI와 관리자 등록 안내는 Figma에서 확인되지만 Product의
  백엔드 기능으로 확정되어 있지 않다.
- Figma에 보이는 축제 날짜·운영 시간·가격·계좌·출연진·부스·장소는 예시다. 확정 운영
  데이터로 사용하지 않는다.
