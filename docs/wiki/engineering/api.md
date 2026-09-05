# API와 저장소 구조

[위키 홈](../README.md) · 읽는 때: API·데이터 모델·기술 구조 변경

API v1은 공개 앱과 관리자 운영 도구가 공유하는 HTTP 계약이다. 구현 기술 스택과
독립적으로 URI, 요청·응답, 인증 경계, 오류, pagination, locale, 시간대,
캐시·동시성 규칙을 정의한다. 최초 서비스 scaffold는 같은 계약의 OpenAPI artifact와
계약 테스트를 추가한다.

공개 API의 핵심 기능은 다음과 같다.

- 홈 집계
- 아티스트 목록
- 공연 목록·상세
- 축제 공간 목록·상세
- 지도 메타데이터와 마커 목록·상세
- 공지 목록·상세
- 챗봇 메시지

아티스트 검색·상세·SNS·추천곡과 FAQ·공식 외부 링크는 API schema의 capability로만
준비하며, 제품 승인과 회차별 활성화 전에는 공개 기능으로 취급하지 않는다.

계약 원칙:

- 공개 조회와 챗봇 진입은 사용자 인증을 요구하지 않는다. 관리자 API는 별도 인증·권한
  경계를 사용한다.
- 공통 응답과 오류 형식, 안정적 pagination과 정렬, request ID를 일관되게 적용한다.
- locale fallback과 실제 응답 locale, 축제 회차, 콘텐츠 revision, map version,
  서버 시각과 timezone을 필요한 응답에 포함한다.
- 여러 무대의 동시 공연, 공연 상태, 콘텐츠 게시 상태, 구조화 메뉴, 의무실과 공연장
  전체를 표현할 수 있어야 한다.
- v1 필드 제거, 의미 변경, enum 재해석처럼 기존 클라이언트를 깨는 변경은 새 버전과
  마이그레이션 계획 없이 배포하지 않는다.
- 계약 변경은 OpenAPI 또는 동등한 명세, 예시, consumer·provider 계약 테스트를 같은
  변경에서 갱신한다.

`test/frontend`의 Next.js UI, `test/backend`의 Spring Boot API,
`test/docker-compose.yml`과 Caddy 구성은 PWA·스탬프·Web Push의 실기기 검증
환경이다. 실행·보안·복구 기준은 `test/README.md`와
`test/docs/FREE_SERVER_DEPLOYMENT.md`에 기록한다. 이 검증 환경은 해당 기능의 공개
서비스 채택이나 전체 제품 아키텍처를 대신 결정하지 않는다.

## 구현 계약

- `test/frontend`에는 Next.js 기반 PWA·실기기 검증 UI가 있고, 선언된 검증 명령은
  `npm run check`다.
- `test/backend`에는 Java 21·Spring Boot·PostgreSQL 기반 검증 API가 있다. Maven
  변경은 최소 `mvn verify`로 검증하며 통합 환경이 필요한 검사는 별도로 기록한다.
- `test/docker-compose.yml`과 `test/Caddyfile`은 동일 HTTPS origin의 실행 구성을,
  `test/README.md`와 `test/docs/FREE_SERVER_DEPLOYMENT.md`는 해당 검증 서비스의
  실행·운영 계약을 설명한다.
- `test/`의 스탬프·Web Push 코드는 실기기 검증용이다. 이 코드의 존재는 스탬프,
  PWA, Push를 공개 서비스 범위로 승인하거나 전체 제품의 아키텍처로 확정한 것이 아니다.
- API v1 문서를 공개 앱과 관리자 도구가 공유하는 계약으로 사용한다. 구현 기술 스택은
  계약과 분리하며 승인된 아키텍처 결정 기록을 따른다.
- API 요청·응답·enum·오류·페이지네이션을 변경하는 PR은 같은 PR에서 명세와 계약
  테스트를 갱신한다.
- v1의 호환성을 유지한다. 기존 클라이언트를 깨는 필드 제거·의미 변경·enum 재해석은
  새 API 버전과 마이그레이션 계획 없이 배포하지 않는다.
- 목록/상세 응답, 오류, pagination, locale fallback, 시간 표현을 OpenAPI 또는 동등한
  기계 판독 계약으로 검증한다. 오류 응답은 안전한 메시지와 추적 가능한 request ID를
  포함하고 내부 정보는 노출하지 않는다.
- 모든 운영 콘텐츠는 축제 회차와 revision에 귀속한다. 지도 위치는 map version에도
  귀속하며, 표시 이름과 안정적 ID를 분리한다.
- 날짜·시각은 timezone을 잃지 않는 형식으로 교환하고, 정렬과 pagination은 동일 요청의
  반복에서도 안정적이어야 한다.
- 학교 약칭이나 번역 문구에서 식별자를 만들지 않는다.
