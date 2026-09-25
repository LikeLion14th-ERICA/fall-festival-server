# DB 역할(role) 설계

[위키 홈](../README.md) · 읽는 때: A1(또는 다른 provider) DB에 실제 role을 만들고 권한을
나눌 때, [계좌 운영 설정](operational-account-settings.md)·[역할 provisioning](../../dev-deployment-decision.md#역할-provisioning)을
실행하기 전

## 왜 이 문서가 필요한가

[`tools/database/provision-operational-account-roles.sql`](../../../tools/database/provision-operational-account-roles.sql)은
role을 **만들지 않는다.** `runtime_role`, `account_operator_role`, `catalog_export_role`,
`catalog_publish_role`, `cleanup_role`라는 **이미 존재하는** role 이름을 받아서 계좌·티켓
계좌·혼잡도·공지·굿즈 테이블에 대한 세부 권한만 다듬는 스크립트다. role 자체를 만들고 각
role이 앱을 실제로 돌리는 데 필요한 나머지 테이블 권한(카탈로그 조회, 굿즈/공지/혼잡도
읽기·쓰기 등)을 주는 건 "DB 제공자"가 할 일로 전제돼 있다(A1의 경우 제원 님).

이 문서는 그 "제공자가 할 일"을 코드 기준으로 구체적인 권한표로 정리한 것이다. **이 문서
자체는 SQL을 실행하지 않는다.** 실제 `CREATE ROLE`/`GRANT`는 검토 후 별도로 실행한다.

## 필요한 role 7개와 각 역할의 실행 주체

| role | 실행 주체(현재 코드) | 프로세스 수명 |
|---|---|---|
| `migration` | Flyway (웹 앱·모든 CLI가 시작 시 `spring.flyway.enabled`가 켜져 있으면 실행. CLI들은 명시적으로 꺼둔다) | 짧음, migration 때만 |
| `runtime` | 메인 웹 앱(`FallFestivalServerApplication`) 하나가 공개 API 읽기, 관리자 CRUD 쓰기, 굿즈 이미지 업로드, 공지 템플릿 CLI 전부를 이 role로 수행한다 — **현재 코드는 이 role을 더 잘게 쪼개지 않는다** | 상시 |
| `cleanup` | 코드에 이미 선택적 분리가 구현돼 있다(`CleanupDataSourceProvider`). `FESTIVAL_CLEANUP_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD`/`_ROLE`을 모두 설정하면 전용 pool을 쓰고, 하나라도 비면 `runtime`과 같은 datasource를 그대로 쓴다 | 상시(스케줄러) 또는 runtime과 공유 |
| `account_operator` | `AccountSettingsCliApplication` (운영자가 그때그때 실행) | 짧음, 계좌 변경 때만 |
| `catalog_export` | `CatalogCliApplication export`, 로컬 catalog workbench의 export pool | 짧음 |
| `catalog_publish` | `CatalogCliApplication import`/`publish`/`rollback`, workbench의 publish pool | 짧음 |
| `preflight` | `DatabasePreflightApplication` | 짧음, 최초 조사 때만 |

`runtime`이 단일 role로 공개 읽기·관리자 쓰기·cleanup(옵션)까지 다 하는 건 지금 코드 구조의
실제 한계다. 나중에 admin 쓰기 경로를 별도 프로세스/role로 더 쪼개려면 코드 변경이 먼저
필요하고, 이 문서는 **현재 코드가 실제로 요구하는 권한**만 기준으로 삼는다.

## 권한표

출처: `src/main/java/dev/espero/festival/persistence/*Store.java`, `idempotency/AdminIdempotencyStore.java`,
`cleanup/*CleanupTarget.java`, `CatalogExportService.java`, `CatalogManifestReader.java`,
`CatalogRevisionService.java`를 grep해 실제 `INSERT`/`UPDATE`/`DELETE`/`SELECT` 대상 테이블을
확인했다. ✅ 표시는 코드에서 직접 확인한 것, ⚠️는 초안이라 실제 부여 전 재확인을 권하는 것이다.

### `migration` — schema owner

- 모든 테이블·시퀀스·함수·trigger에 대한 전체 권한(schema owner 또는 그에 준하는 role).
  `V15`의 `SECURITY DEFINER` trigger 함수(계좌 이력 기록)를 이 role이 소유해야 한다 —
  provisioning script 첫 줄 주석이 이를 명시한다.
- 다른 role에는 이 권한을 주지 않는다. `runtime`이 DDL 권한을 가지면 안 된다.

### `runtime` — 웹 앱

**카탈로그(전부 SELECT만, 쓰기 없음)** — 공개 API가 게시된 revision을 읽는 용도.

`festivals`, `festival_revisions`, `festival_days`, `festival_title_translations`,
`festival_links`, `festival_link_translations`, `spaces`, `space_translations`,
`space_sort_orders`, `space_events`, `space_menu_items`, `places`, `place_translations`,
`maps`, `map_translations`, `map_asset_versions`, `map_asset_translations`, `map_areas`,
`map_pins`, `map_pin_translations`, `map_pin_filter_group_translations`, `space_map_targets`,
`artists`, `artist_translations`, `artist_links`, `artist_link_translations`, `artist_songs`,
`artist_song_translations`, `performances`, `performance_translations`, `performance_artists`,
`timetable_configs`, `prohibited_items`, `prohibited_item_translations`, `prohibited_messages`,
`ticket_guide_revisions`, `ticket_guide_translations`, `stamp_guide_revisions`,
`stamp_guide_translations`, `stamp_booths`, `stamp_booth_tokens`. (`ticket_guide`/`stamp_guide` legacy singleton 테이블은 `TicketGuideStore`
등이 여전히 읽는지 확인 후 포함 여부 결정 — ⚠️)

**운영 데이터(직접 CRUD, ✅ `*Store.java`에서 확인)**

| 테이블 | 권한 |
|---|---|
| `crowding_state_dynamic` | SELECT, INSERT, UPDATE |
| `artist_hyped_counts` | SELECT, INSERT, UPDATE (익명 아티스트 Hyped 누적 수; 카탈로그 revision 밖) |
| `stamp_participants`, `stamp_participant_days`, `stamp_collections`, `stamp_rewards` | SELECT, INSERT (부스 스탬프 V27·일일 START V28, `StampStore` 기준) |
| `notices`, `notice_translations`, `notice_links`, `notice_link_translations` | SELECT, INSERT, UPDATE, DELETE |
| `notice_templates`, `notice_template_translations` | SELECT, INSERT, DELETE (템플릿 CLI가 전체 교체; `NoticeTemplateStore` 기준) |
| `goods`, `goods_translations`, `goods_colors`, `goods_color_translations`, `goods_sizes`, `goods_size_translations`, `goods_combinations` | SELECT, INSERT, UPDATE, DELETE |
| `goods_images`, `goods_image_translations` | SELECT, INSERT, DELETE |
| `media_assets` | SELECT, INSERT, UPDATE |
| `admin_accounts` | SELECT, INSERT, UPDATE (bootstrap·로그인) |
| `admin_refresh_sessions` | SELECT, INSERT, UPDATE |
| `admin_idempotency_records` | SELECT, INSERT, UPDATE (lease·완료 기록. DELETE는 `cleanup`만) |
| `admin_audit_events` | SELECT, INSERT (DELETE는 `cleanup`만) |
| `operational_account_settings` | **SELECT만** (계좌 값 자체는 `account_operator`만 쓴다) |

**접근 금지**: `operational_account_setting_history`, `flyway_schema_history`, `catalog_revision_audit`(읽기도 불필요 — 감사 열람 기능이 아직 없음).

### `cleanup` (전용 pool 구성 시)

- `admin_audit_events`: SELECT, DELETE (`AdminAuditCleanupTarget`)
- `admin_idempotency_records`: SELECT, DELETE (`AdminIdempotencyCleanupTarget`)
- `media_assets`: SELECT, DELETE (`GoodsDetachedMediaCleanupTarget`, `GoodsUnattachedMediaCleanupTarget`)
- `operational_account_setting_history`: `(festival_id, purpose, scope_id, version)` 컬럼 SELECT,
  `(id, festival_id, purpose, scope_id, version, after_state, occurred_at)` 컬럼 SELECT, DELETE
  (provisioning script가 이미 이 컬럼 제한으로 GRANT한다)
- ⚠️ `goods_images` 조인 여부는 media cleanup target 쿼리를 한 번 더 확인 후 필요시 SELECT 추가

전용 pool을 안 쓰면(`FESTIVAL_CLEANUP_DATASOURCE_*` 미설정) `runtime`이 이 권한을 전부
포함해야 한다.

### `account_operator` — `AccountSettingsCliApplication`

- `operational_account_settings`: SELECT, INSERT, UPDATE
- `operational_account_setting_history`: SELECT
- 그 외 테이블 접근 불필요.

### `catalog_export` — export만

`runtime`의 "카탈로그(SELECT만)" 목록과 거의 같지만, **계좌·혼잡도·공지·굿즈·스탬프 참여 기록은
절대 포함하지 않는다** — provisioning script가 명시적으로 이걸 막는다. `ticket_guide_revisions`는 계좌 컬럼이
애초에 없으므로 전체 컬럼 SELECT 가능(레거시 `ticket_guide`/`ticket_guide_revisions`의 옛 계좌
컬럼이 남아있다면 그 컬럼만 제외 — provisioning script의 컬럼 목록 참고).

### `catalog_publish` — import/publish/rollback (✅ `CatalogRevisionService`/`CatalogManifestReader`에서 확인)

`catalog_export`와 같은 테이블 목록 + 아래 쓰기 권한.

- 모든 카탈로그 자식 테이블(spaces, artists, performances, maps, ... 위 목록 전체): INSERT
- `festival_revisions`: SELECT, INSERT, UPDATE (draft 생성, published/archived 상태 전환)
- `festivals`: SELECT (`SELECT ... FOR UPDATE`로 행 잠금만 함, UPDATE 불필요)
- `catalog_revision_audit`: INSERT
- ⚠️ import 시 기존 draft 삭제/치환 로직이 있는지(DELETE 필요 여부)는 `CatalogManifestReader`
  전체를 한 번 더 읽어 확인 권장 — 이번 조사에서는 INSERT만 확인했다.

### `preflight` — `DatabasePreflightApplication`

- 조사 대상 schema의 모든 테이블·뷰에 SELECT만. DDL·DML 전부 없음.
- [DB 읽기 전용 사전 점검](database-preflight.md)에 이미 상세히 정의돼 있다. 여기서는 목록만
  참조.

## role 이름 제안과 CREATE ROLE 초안

실제 이름은 provider(A1이면 제원 님)가 정해도 되지만, 충돌 방지를 위한 제안:

```text
fall_festival_migration
fall_festival_runtime
fall_festival_cleanup
fall_festival_account_operator
fall_festival_catalog_export
fall_festival_catalog_publish
fall_festival_preflight
```

```sql
-- 예시. 비밀번호는 각자 안전하게 생성해서 직접 채워 넣는다 — 이 문서·커밋·채팅에 남기지 않는다.
CREATE ROLE fall_festival_migration LOGIN PASSWORD '...';
CREATE ROLE fall_festival_runtime LOGIN PASSWORD '...';
CREATE ROLE fall_festival_cleanup LOGIN PASSWORD '...';
CREATE ROLE fall_festival_account_operator LOGIN PASSWORD '...';
CREATE ROLE fall_festival_catalog_export LOGIN PASSWORD '...';
CREATE ROLE fall_festival_catalog_publish LOGIN PASSWORD '...';
CREATE ROLE fall_festival_preflight LOGIN PASSWORD '...';
```

## 적용 순서

1. 위 role을 만든다(`CREATE ROLE`, 각각 다른 임의 비밀번호).
2. `migration` role로 `ALTER SCHEMA public OWNER TO fall_festival_migration` 또는 provider의
   기존 소유자 정책에 맞게 소유권을 정리한다(기존에 이미 다른 소유자가 있으면 임의로 바꾸지
   않는다 — 먼저 [DB 읽기 전용 사전 점검](database-preflight.md)으로 현재 소유자를 확인한다).
3. 위 권한표대로 각 role에 `GRANT`를 실행한다(이 문서의 표를 SQL로 옮기는 작업 — 아직 별도
   스크립트로 만들지 않았다. 검토 후 만든다).
4. `tools/database/provision-operational-account-roles.sql`을 `-v schema=public
   -v runtime_role=fall_festival_runtime -v cleanup_role=fall_festival_cleanup
   -v account_operator_role=fall_festival_account_operator
   -v catalog_export_role=fall_festival_catalog_export
   -v catalog_publish_role=fall_festival_catalog_publish`로 실행한다.
5. A1의 각 프로세스가 쓰는 datasource 환경변수를 해당 role로 바꾼다 — 메인 웹 앱은
   `SPRING_DATASOURCE_*`를 `fall_festival_runtime`으로, 필요하면 cleanup은
   `FESTIVAL_CLEANUP_DATASOURCE_*`를 `fall_festival_cleanup`으로 추가 설정한다. CLI는 실행할 때만
   해당 role의 credential을 그때그때 넣는다.
6. 바꾼 뒤 `/healthz`·`/readyz`와 관리자 로그인·공지/굿즈 조회·계좌 CLI dry-run을 한 번씩
   실행해 새 role로도 정상 동작하는지 확인한다. 실패하면 이전 단일 계정으로 되돌릴 수 있게
   변경 전 `SPRING_DATASOURCE_*` 값을 따로 적어둔다.

## 이 설계의 한계

- `runtime`이 공개 읽기와 관리자 쓰기를 구분하지 않는다. "관리자 쓰기 전용 role"을 따로
  만들고 싶으면 앱을 두 프로세스(공개 읽기 전용 / 관리자 API)로 나누는 코드 변경이 먼저
  필요하다 — 이 문서 범위 밖이다.
- `catalog_publish`의 삭제(DELETE) 필요 여부(⚠️ 표시)와 `runtime`의 legacy `ticket_guide`/
  `stamp_guide` 테이블 접근 필요 여부(⚠️ 표시)는 코드를 한 번 더 확인한 뒤 확정한다.
- 이 문서만으로 A1 DB에 아무것도 바뀌지 않는다. 실제 `CREATE ROLE`/`GRANT`는 검토 후 별도
  세션에서, 가능하면 먼저 백업을 뜬 뒤 실행한다.
