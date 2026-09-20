-- Run with psql after every migration has been applied.
-- Example variables are intentionally omitted: role and schema names are supplied by each DB provider.
-- Required psql variables: schema, runtime_role, cleanup_role, account_operator_role,
-- catalog_export_role, catalog_publish_role.
-- Rerun after every migration that adds a table outside the catalog, so a
-- provider-wide catalog grant never reaches it.
-- The migration role owns the tables and SECURITY DEFINER trigger functions; do not run this as runtime.

BEGIN;

REVOKE ALL ON TABLE :"schema".operational_account_settings FROM PUBLIC;
REVOKE ALL ON TABLE :"schema".operational_account_setting_history FROM PUBLIC;
REVOKE ALL ON SEQUENCE :"schema".operational_account_setting_history_id_seq FROM PUBLIC;
REVOKE ALL ON FUNCTION :"schema".validate_operational_account_setting() FROM PUBLIC;
REVOKE ALL ON FUNCTION :"schema".record_operational_account_setting_history() FROM PUBLIC;

GRANT USAGE ON SCHEMA :"schema" TO :"runtime_role";
GRANT SELECT ON TABLE :"schema".operational_account_settings TO :"runtime_role";

GRANT USAGE ON SCHEMA :"schema" TO :"account_operator_role";
GRANT SELECT, INSERT, UPDATE ON TABLE :"schema".operational_account_settings TO :"account_operator_role";
GRANT SELECT ON TABLE :"schema".operational_account_setting_history TO :"account_operator_role";

GRANT USAGE ON SCHEMA :"schema" TO :"cleanup_role";
GRANT SELECT (festival_id, purpose, scope_id, version)
    ON TABLE :"schema".operational_account_settings TO :"cleanup_role";
GRANT SELECT (id, festival_id, purpose, scope_id, version, after_state, occurred_at), DELETE
    ON TABLE :"schema".operational_account_setting_history TO :"cleanup_role";

REVOKE ALL ON TABLE :"schema".operational_account_settings FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".operational_account_setting_history FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".operational_account_settings FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".operational_account_setting_history FROM :"catalog_publish_role";

-- The legacy ticket account and transfer-link columns stay in place for
-- history. Catalog roles read and copy the ticket guide by explicit column
-- list, so they receive column privileges and never table-wide SELECT. A
-- later SELECT * or an added account column therefore fails instead of
-- exporting an account.
REVOKE ALL ON TABLE :"schema".ticket_guide FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".ticket_guide FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".ticket_guide_revisions FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".ticket_guide_revisions FROM :"catalog_publish_role";

GRANT SELECT (
        festival_revision_id, id, unit_price_amount, map_id, place_id, pin_id,
        map_version, instructions, festival_start_date, festival_end_date,
        daily_transfer_open_time, daily_transfer_close_time,
        daily_pickup_open_time, daily_pickup_close_time, updated_at
    ) ON TABLE :"schema".ticket_guide_revisions TO :"catalog_export_role";

GRANT SELECT (
        festival_revision_id, id, unit_price_amount, map_id, place_id, pin_id,
        map_version, instructions, festival_start_date, festival_end_date,
        daily_transfer_open_time, daily_transfer_close_time,
        daily_pickup_open_time, daily_pickup_close_time, updated_at
    ), INSERT (
        festival_revision_id, id, unit_price_amount, map_id, place_id, pin_id,
        map_version, instructions, festival_start_date, festival_end_date,
        daily_transfer_open_time, daily_transfer_close_time,
        daily_pickup_open_time, daily_pickup_close_time, updated_at
    ) ON TABLE :"schema".ticket_guide_revisions TO :"catalog_publish_role";

-- Crowding and notices are operated outside the catalog. The local catalog
-- workbench connects with these catalog roles, so they get no access to
-- those tables even when the provider granted the whole schema.
REVOKE ALL ON TABLE :"schema".crowding_state FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".crowding_state FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".crowding_state_dynamic FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".crowding_state_dynamic FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".notices FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".notices FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".notice_translations FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".notice_translations FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".notice_links FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".notice_links FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".notice_link_translations FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".notice_link_translations FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".notice_templates FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".notice_templates FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".notice_template_translations FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".notice_template_translations FROM :"catalog_publish_role";

-- Goods are operated outside the catalog as well (V21).
REVOKE ALL ON TABLE :"schema".goods FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".goods FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".goods_translations FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".goods_translations FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".goods_colors FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".goods_colors FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".goods_color_translations FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".goods_color_translations FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".goods_sizes FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".goods_sizes FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".goods_size_translations FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".goods_size_translations FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".goods_combinations FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".goods_combinations FROM :"catalog_publish_role";

-- Goods images and their media assets are uploaded and served outside the catalog.
REVOKE ALL ON TABLE :"schema".media_assets FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".media_assets FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".goods_images FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".goods_images FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".goods_image_translations FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".goods_image_translations FROM :"catalog_publish_role";

-- Admin credentials, refresh sessions, audit events and idempotency records
-- must never be reachable from the catalog workbench.
REVOKE ALL ON TABLE :"schema".admin_accounts FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".admin_accounts FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".admin_refresh_sessions FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".admin_refresh_sessions FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".admin_audit_events FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".admin_audit_events FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".admin_idempotency_records FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".admin_idempotency_records FROM :"catalog_publish_role";

COMMIT;
