# API v2 경로·시나리오 목록

자동 생성 문서입니다. 필드별 계약은 [OpenAPI](openapi.json), 요청·응답 원문은 [examples.json](examples.json)을 확인하세요.

| 메서드 | 경로 | 내용 | 화면 | 시나리오 |
|---|---|---|---|---|
| GET | `/api/v2/config` | 홈 공통 설정 | HOME | normal, empty, missing-optional, faq-ready, welcome-ready, error, all-languages, bad-request, rate-limited |
| GET | `/api/v2/crowding` | 홈 재학생존 혼잡도 | HOME | normal, before-open, closed, unmodified, unconfigured, error, bad-request, rate-limited |
| GET | `/api/v2/notices` | 사용자 공지 전체 | HOME, NOTICE-LIST | normal, empty, missing-optional, new-notice, deleted, error, bad-request, rate-limited |
| GET | `/api/v2/goods` | 상품 목록 | GOODS-LIST | normal, empty, error, bad-request, rate-limited |
| GET | `/api/v2/goods-availability` | 상품 목록의 판매 상태 | GOODS-LIST | normal, empty, sold-out, error, bad-request, rate-limited |
| GET | `/api/v2/goods/{goodsId}` | 상품 상세 | GOODS-DETAIL | normal, missing-optional, not-found, error, bad-request, rate-limited |
| GET | `/api/v2/goods/{goodsId}/availability` | 상품 상세의 판매 상태 | GOODS-DETAIL | normal, sold-out, not-found, error, bad-request, rate-limited |
| GET | `/api/v2/goods/{goodsId}/payment-guide` | 굿즈 계좌 안내 | GOODS-PAYMENT | normal, missing-optional, not-found, error, bad-request, rate-limited |
| GET | `/api/v2/lineup` | 날짜·분류별 라인업 | SHOW-LINEUP | normal, empty, error, bad-request, rate-limited |
| GET | `/api/v2/artists/{artistId}` | 출연진 상세 | SHOW-ARTIST | normal, missing-optional, not-found, error, bad-request, rate-limited |
| GET | `/api/v2/timetable` | 3일 타임테이블 | SHOW-TIMETABLE | normal, empty, error, bad-request, rate-limited |
| GET | `/api/v2/performances/{performanceId}` | 공연 정보 팝업 | SHOW-POPUP | normal, missing-optional, not-found, error, bad-request, rate-limited |
| GET | `/api/v2/prohibited-items` | 고정 반입 금지 물품 안내 | SHOW-TIMETABLE | normal, empty, error, bad-request, rate-limited |
| GET | `/api/v2/spaces` | 부스&마켓 목록 | BOOTH-LIST | normal, empty, error, bad-request, rate-limited |
| GET | `/api/v2/spaces/{spaceId}` | 부스·주점·플리마켓 상세 | BOOTH-DETAIL | normal, missing-optional, not-found, error, bad-request, rate-limited |
| GET | `/api/v2/maps` | 지도 목록 | MAP-OVERVIEW, MAP-AREA | normal, empty, error, bad-request, rate-limited |
| GET | `/api/v2/maps/{mapId}` | 지도 이미지·버전 | MAP-OVERVIEW, MAP-AREA | normal, not-found, error, bad-request, rate-limited |
| GET | `/api/v2/maps/{mapId}/pins` | 지도별 핀 | MAP-OVERVIEW, MAP-AREA | normal, empty, not-found, version-conflict, error, bad-request, rate-limited |
| GET | `/api/v2/places/{placeId}` | 장소 팝업 | MAP-POPUP | normal, missing-optional, not-found, error, bad-request, rate-limited |
| GET | `/api/v2/ticket-guide` | 외부인 티켓 안내 | TICKET | normal, before-open, closed, ended, unconfigured, error, bad-request, rate-limited |
| GET | `/api/v2/stamp-guide` | 스탬프 안내·공통 QR | STAMP-START, STAMP-COLLECT, STAMP-REWARD | normal, missing-optional, error, bad-request, rate-limited |
| POST | `/api/v2/stamp-receipt-verifications` | 스탬프 상품 수령 인증 | STAMP-REWARD | normal, invalid-code, error, bad-request, rate-limited |
| GET | `/api/v2/admin/crowding` | 관리자 혼잡도 | ADM-CROWD | normal, before-open, closed, unmodified, unconfigured, error, bad-request, rate-limited, unauthorized, forbidden |
| PUT | `/api/v2/admin/crowding` | 실제 FestivalDay 혼잡도 저장·운영 전후 허용·비운영일 409·동일 상태 시각 유지 | ADM-CROWD | normal, full, not-festival-day, error, precondition-required, edit-conflict, bad-request, rate-limited, unauthorized, forbidden |
| GET | `/api/v2/admin/notices` | 관리자 공지 목록 | ADM-NOTICE-LIST | normal, empty, error, bad-request, rate-limited, unauthorized, forbidden |
| POST | `/api/v2/admin/notices` | 공지 등록 | ADM-NOTICE-EDIT | normal, error, validation-failed, bad-request, rate-limited, unauthorized, forbidden |
| GET | `/api/v2/admin/notices/{noticeId}` | 공지 수정 초기값 | ADM-NOTICE-EDIT | normal, missing-optional, not-found, error, bad-request, rate-limited, unauthorized, forbidden |
| PUT | `/api/v2/admin/notices/{noticeId}` | 공지 수정 | ADM-NOTICE-EDIT | normal, not-found, error, precondition-required, edit-conflict, validation-failed, bad-request, rate-limited, unauthorized, forbidden |
| DELETE | `/api/v2/admin/notices/{noticeId}` | 공지 삭제 | ADM-NOTICE-DELETE | normal, already-deleted, error, precondition-required, edit-conflict, bad-request, rate-limited, unauthorized, forbidden |
| GET | `/api/v2/admin/notice-templates` | 공지 템플릿 목록 | ADM-NOTICE-TEMPLATE | normal, empty, error, bad-request, rate-limited, unauthorized, forbidden |
| GET | `/api/v2/admin/notice-templates/{templateId}` | 템플릿 초기값 | ADM-NOTICE-TEMPLATE | normal, missing-optional, not-found, error, bad-request, rate-limited, unauthorized, forbidden |
| GET | `/api/v2/admin/goods` | 관리자 실제 제공 조합별 판매 상태 | ADM-GOODS | normal, empty, sold-out, error, bad-request, rate-limited, unauthorized, forbidden |
| PUT | `/api/v2/admin/goods/{goodsId}/combinations/{combinationId}/availability` | 조합 판매 상태 저장. last-write-wins 예외로 If-Match 불필요. | ADM-GOODS | normal, sold-out, not-found, precondition-required, error, bad-request, rate-limited, unauthorized, forbidden |
| GET | `/api/v2/admin/products` | 관리자 상품 목록 | ADM-GOODS-PRODUCT-LIST | normal, empty, error, bad-request, rate-limited, unauthorized, forbidden |
| GET | `/api/v2/admin/products/{goodsId}` | 상품 수정 초기값 | ADM-GOODS-PRODUCT-EDIT | normal, not-found, error, bad-request, rate-limited, unauthorized, forbidden |
| POST | `/api/v2/admin/products` | 상품 등록·신규 조합은 ON_SALE | ADM-GOODS-PRODUCT-EDIT | normal, validation-failed, idempotency-key-required, invalid-media-reference, error, bad-request, rate-limited, unauthorized, forbidden |
| PUT | `/api/v2/admin/products/{goodsId}` | 상품 수정·유지 조합 상태 보존, 신규 ON_SALE, 삭제 허용 | ADM-GOODS-PRODUCT-EDIT | normal, new-option, option-removal, validation-failed, invalid-media-reference, not-found, idempotency-key-required, precondition-required, edit-conflict, error, bad-request, rate-limited, unauthorized, forbidden |
| DELETE | `/api/v2/admin/products/{goodsId}` | 상품 완전 삭제 | ADM-GOODS-PRODUCT-LIST | normal, not-found, idempotency-key-required, precondition-required, edit-conflict, error, bad-request, rate-limited, unauthorized, forbidden |
| POST | `/api/v2/admin/media/goods-images` | 상품 이미지 업로드·상품 연결 전 unattached media 생성 | ADM-GOODS-PRODUCT-EDIT | normal, validation-failed, payload-too-large, unsupported-media-type, idempotency-key-required, error, bad-request, rate-limited, unauthorized, forbidden |
| GET | `/api/v2/media/goods-images/{mediaId}/{variant}` | 연결된 상품 이미지 WebP variant 조회 | GOODS-LIST, GOODS-DETAIL | normal, not-found, error, bad-request, rate-limited |
| POST | `/api/v2/admin/sessions` | 관리자 로그인 |  | normal, invalid-credentials, disabled, invalid-origin, error, bad-request, rate-limited |
| POST | `/api/v2/admin/sessions/refresh` | 관리자 세션 갱신·refresh rotation |  | normal, expired, revoked, unknown, disabled, invalid-origin, error, bad-request, rate-limited |
| DELETE | `/api/v2/admin/sessions/current` | 현재 관리자 세션 로그아웃·refresh cookie가 없어도 성공 |  | normal, invalid-origin, error, bad-request, rate-limited, unauthorized, forbidden |
| GET | `/api/v2/admin/me` | 현재 인증 관리자 확인 |  | normal, disabled, error, bad-request, rate-limited, unauthorized, forbidden |
