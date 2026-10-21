CREATE FUNCTION reject_notification_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Notification history is immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER notification_preference_revisions_immutable BEFORE UPDATE OR DELETE ON notification_preference_revisions FOR EACH ROW EXECUTE FUNCTION reject_notification_history_change();
CREATE TRIGGER notification_attempts_immutable BEFORE UPDATE OR DELETE ON notification_attempts FOR EACH ROW EXECUTE FUNCTION reject_notification_history_change();
CREATE TRIGGER notification_discoveries_immutable BEFORE UPDATE OR DELETE ON notification_discoveries FOR EACH ROW EXECUTE FUNCTION reject_notification_history_change();
ALTER TABLE notification_preferences ADD CONSTRAINT notification_quiet_time_bounds CHECK(quiet_start IS NULL OR (quiet_start ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$' AND quiet_end ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'));

CREATE TRIGGER notification_attempt_items_immutable BEFORE UPDATE OR DELETE ON notification_attempt_items FOR EACH ROW EXECUTE FUNCTION reject_notification_history_change();
