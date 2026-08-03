-- Phase 3.2.5: admit RESPONSE_TOO_LARGE as a distinct tenant failure category.
--
-- Additive and non-destructive: only the two CHECK constraints are widened. No table is
-- recreated, no row is deleted or rewritten, and historical RESPONSE_PARSE_ERROR rows
-- caused by the previous 2 MiB limit are deliberately preserved exactly as recorded.
-- Attempt ids, ingestion run UUIDs, timestamps, counters, uniqueness and indexes are
-- untouched.

ALTER TABLE source_tenant_fetch_logs DROP CONSTRAINT source_tenant_fetch_logs_category_ck;
ALTER TABLE source_tenant_fetch_logs ADD CONSTRAINT source_tenant_fetch_logs_category_ck CHECK (
    failure_category IN ('NONE', 'INVALID_TENANT', 'CLIENT_ERROR', 'AUTHORIZATION_ERROR',
        'RATE_LIMITED', 'TIMEOUT', 'NETWORK_ERROR', 'SERVER_ERROR', 'RESPONSE_PARSE_ERROR',
        'RESPONSE_TOO_LARGE', 'CONFIGURATION_ERROR', 'UNKNOWN_ERROR'));

ALTER TABLE source_tenant_health DROP CONSTRAINT source_tenant_health_category_ck;
ALTER TABLE source_tenant_health ADD CONSTRAINT source_tenant_health_category_ck CHECK (
    last_failure_category IN ('NONE', 'INVALID_TENANT', 'CLIENT_ERROR', 'AUTHORIZATION_ERROR',
        'RATE_LIMITED', 'TIMEOUT', 'NETWORK_ERROR', 'SERVER_ERROR', 'RESPONSE_PARSE_ERROR',
        'RESPONSE_TOO_LARGE', 'CONFIGURATION_ERROR', 'UNKNOWN_ERROR'));
