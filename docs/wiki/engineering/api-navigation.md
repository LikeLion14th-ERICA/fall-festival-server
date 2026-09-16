# API 명세에서 필요한 계약 찾기

[위키 홈](../README.md) · 읽는 때: API 구현·검토·문서 변경

제품 API의 단일 정본은 [API v2 명세](../../../api-v2/README.md)다. 생성물만 직접
고치지 않고 `contract-source.mjs`와 `admin-contract.mjs`를 수정한 뒤 OpenAPI와 예제를
생성한다.

| 변경 영역 | 확인할 자료 |
|---|---|
| 공통 HTTP·오류·locale·시간 | [v2 명세](../../../api-v2/README.md) 공통 계약과 [OpenAPI](../../../api-v2/openapi.json) schema |
| 공개 화면 경로·상태 | [경로·시나리오](../../../api-v2/ENDPOINTS.md), [화면별 데이터](../../../api-v2/SCREEN-DATA.md), [화면 상태](../../../api-v2/SCREEN-STATES.md) |
| 관리자 인증·쓰기 | [관리자 변경](../../../api-v2/ADMIN-CHANGES.md), OpenAPI의 `/api/v2/admin/**` |
| 목 동작·프런트 연동 | [프런트 안내](../../../api-v2/FRONTEND.md), `domain.mjs`, `admin-domain.mjs` |
| 합의 전 기술·운영값 | [결정 대기](../../../api-v2/DECISIONS.md)와 해당 Product 문서 |

저장소 루트에서 경로나 schema 위치를 찾는 예시:

```powershell
rg -n 'getPins|/api/v2/maps|Pin' api-v2/contract-source.mjs api-v2/openapi.json
```

계약 변경은 source·생성 OpenAPI·예시·consumer/provider 테스트를 같은 변경에서 갱신한다.
