-- Run with psql after V15 (or its merge-time renumbered successor) has migrated.
-- Example variables are intentionally omitted: role and schema names are supplied by each DB provider.
-- Required psql variables: schema, runtime_role, cleanup_role, account_operator_role,
-- catalog_export_role, catalog_publish_role.
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
GRANT SELECT (festival_id, purpose, version)
    ON TABLE :"schema".operational_account_settings TO :"cleanup_role";
GRANT SELECT, DELETE ON TABLE :"schema".operational_account_setting_history TO :"cleanup_role";

REVOKE ALL ON TABLE :"schema".operational_account_settings FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".operational_account_setting_history FROM :"catalog_export_role";
REVOKE ALL ON TABLE :"schema".operational_account_settings FROM :"catalog_publish_role";
REVOKE ALL ON TABLE :"schema".operational_account_setting_history FROM :"catalog_publish_role";

COMMIT;
