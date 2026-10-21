import { useEffect, useMemo, useRef, useState } from 'react';
import { createNotificationClient, NotificationValidationError, validTimezone, type NotificationActivation, type NotificationPreferences, type NotificationUpdate, type NotificationView } from '../data/notifications';
import { JobPilotError } from '../data/types';
import { openTelegramLink } from '../lib/telegram';

const reasons: Record<string, string> = {
  DISABLED_BY_USER: 'Notifications are off.', PAUSED: 'Notifications are paused.', CONNECT_BOT: 'Connect the bot to receive notifications.',
  BLOCKED: 'Unblock the bot in Telegram, then reconnect.', UNAVAILABLE: 'Telegram delivery is unavailable. Reconnect to try again.',
  INCOMPLETE_PROFILE: 'Complete your search preferences to receive notifications.', BETA_CLOSED: 'Notifications are unavailable while beta access is closed.',
  SYSTEM_DISABLED: 'Notifications are temporarily unavailable.', RECOVERY: 'Notifications are unavailable during maintenance.',
  DISPATCH_DISARMED: 'Delivery is awaiting a service check.',
  UNCERTAIN_HELD: 'A previous delivery could not be confirmed and will not be resent automatically. This does not necessarily stop future notifications.',
  CONSENT_REVOKED: 'Permission was revoked. Save your consent again to resume.', LEGACY_MANAGED: 'Your existing owner alerts are managed by the original settings.',
};
const connections = { DISCONNECTED: 'Telegram disconnected.', CONNECTED: 'Telegram connected.', BLOCKED: 'Telegram connection blocked.', UNAVAILABLE: 'Telegram connection unavailable.', LEGACY_MANAGED: 'Existing owner connection.' };

export function NotificationSettings() {
  const client = useMemo(() => createNotificationClient(), []);
  const alive = useRef(false);
  const inFlight = useRef(false);
  const pending = useRef<NotificationUpdate | null>(null);
  const [view, setView] = useState<NotificationView | null>(null);
  const [draft, setDraft] = useState<NotificationPreferences | null>(null);
  const [activation, setActivation] = useState<NotificationActivation | null>(null);
  const [busy, setBusy] = useState(true);
  const [conflict, setConflict] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const detectedTimezone = useMemo(() => Intl.DateTimeFormat().resolvedOptions().timeZone, []);
  const accept = (next: NotificationView) => {
    setView(next); setDraft({ ...next.preferences }); setActivation(null); pending.current = null;
    setConflict(false); setUncertain(false);
  };
  useEffect(() => {
    let current = true; alive.current = true; inFlight.current = true;
    client.get().then(next => { if (current) { accept(next); setBusy(false); } }, () => {
      if (current) { setError('Notification settings could not be loaded. Refresh to retrieve confirmed settings.'); setBusy(false); }
    }).finally(() => { if (current) inFlight.current = false; });
    return () => { current = false; alive.current = false; };
  }, [client]);
  const change = <K extends keyof NotificationPreferences>(key: K, value: NotificationPreferences[K]) => {
    pending.current = null; setActivation(null); setDraft(old => old && ({ ...old, [key]: value })); setError(''); setNotice('');
  };
  const dirty = view && draft && JSON.stringify(view.preferences) !== JSON.stringify(draft);
  async function perform(action: 'save' | 'refresh' | 'activate' | 'disconnect') {
    if (inFlight.current) return;
    if (action === 'save') {
      if (!draft || !view || conflict) return;
      let problem = '';
      if (draft.enabled && !draft.immediate && !draft.digest) problem = 'Choose immediate notifications or the daily digest before enabling notifications.';
      else if (!Number.isInteger(draft.minimumScore) || draft.minimumScore < 0 || draft.minimumScore > 100) problem = 'Minimum score must be a whole number from 0 to 100.';
      else if (!validTimezone(draft.timezone)) problem = 'Enter a valid timezone, such as Europe/Bucharest or UTC.';
      else if ((draft.quietStart === null) !== (draft.quietEnd === null)) problem = 'Choose both quiet-hour times, or leave both empty.';
      else if (draft.quietStart !== null && draft.quietStart === draft.quietEnd) problem = 'Quiet-hour start and end must be different.';
      if (problem) { setError(problem); return; }
    }
    inFlight.current = true; setBusy(true); setError(''); setNotice(''); setActivation(null);
    try {
      if (action === 'activate') {
        const next = await client.activate(); if (alive.current) setActivation(next);
      } else {
        let next: NotificationView;
        if (action === 'save') {
          pending.current ??= { expectedRevision: view!.revision, requestId: crypto.randomUUID(), ...draft! };
          next = await client.save(pending.current);
        } else next = await (action === 'disconnect' ? client.disconnect() : client.get());
        if (alive.current) { accept(next); setNotice(action === 'save' ? 'Notification settings saved.' : action === 'disconnect' ? 'Connection removed and notifications disabled.' : 'Confirmed settings loaded. Any unsaved edits were discarded.'); }
      }
    } catch (failure) {
      if (!alive.current) return;
      if (failure instanceof JobPilotError && failure.kind === 'conflict') {
        setConflict(true); setError('Settings changed. Refresh notification status to load the current settings before continuing.');
      } else if (failure instanceof NotificationValidationError) setError('Check your notification settings, then save again. Your draft is still here.');
      else if (action === 'save') { setUncertain(true); setError('Save not confirmed. Your draft is still here. Try the same selections again to safely confirm the save, or refresh notification status.'); }
      else setError(action === 'activate' ? 'Bot connection could not be prepared. Try again when you are ready.' : action === 'disconnect' ? 'Disconnect not confirmed. Refresh notification status or try disconnecting again.' : 'Notification settings could not be loaded. Refresh to retrieve confirmed settings.');
    } finally { inFlight.current = false; if (alive.current) setBusy(false); }
  }
  const toggle = (key: 'enabled' | 'paused' | 'immediate' | 'digest', label: string) => <label className="setting">
    <span className="setting__label">{label}</span><input className="switch" type="checkbox" checked={draft![key]} onChange={event => change(key, event.target.checked)} /></label>;
  return <section className="notification-settings" aria-labelledby="notification-heading">
    <h2 id="notification-heading">Notifications</h2>
    <p>Only eligible, newly discovered vacancies can be sent after you allow notifications and connect the bot. Unknown or ineligible matches are never sent.</p>
    <p>All matches remain available in the Mini App, including those beyond notification limits. Resuming starts from new discoveries, without a catch-up flood.</p>
    {busy && <p role="status">Loading notification settings…</p>}
    {error && <p role="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {view && draft && <>
      <div className="notification-status" role="status"><p>{connections[view.connectionStatus]}</p>
        {[...new Set(view.pauseReasons.map(reason => Object.hasOwn(reasons, reason) ? reasons[reason] : 'Some notifications are temporarily unavailable.'))].map(copy => <p key={copy}>{copy}</p>)}
      </div>
      <form onSubmit={event => { event.preventDefault(); void perform('save'); }}>
        <fieldset className="profile-fields" disabled={busy || conflict || view.legacyManaged}>
          <legend className="visually-hidden">Notification preferences</legend>
          {toggle('enabled', 'Allow Telegram notifications')}
          {toggle('paused', 'Pause notifications')}
          {toggle('immediate', 'Immediate notifications')}
          {toggle('digest', 'Daily digest at 09:00 local time')}
          <p className="setting__hint">Up to three immediate messages and one optional daily digest with up to ten other vacancies. The digest uses your saved timezone.</p>
          <p className="setting__hint">Saved timezone: {view.preferences.timezone}. Timezone changes apply only when you save.</p>
          <details><summary>Advanced notification settings</summary>
            <label className="profile-field">Minimum score<input type="number" min="0" max="100" step="1" value={Number.isNaN(draft.minimumScore) ? '' : draft.minimumScore} onChange={event => change('minimumScore', event.target.value === '' ? NaN : Number(event.target.value))} /></label>
            <label className="profile-field">Timezone<input value={draft.timezone} maxLength={120} autoComplete="off" onChange={event => change('timezone', event.target.value)} /></label>
            {validTimezone(detectedTimezone) && detectedTimezone !== draft.timezone && <button className="btn btn--quiet" type="button" onClick={() => change('timezone', detectedTimezone)}>Use device timezone ({detectedTimezone})</button>}
            <label className="profile-field">Quiet hours start<input type="time" value={draft.quietStart ?? ''} onChange={event => change('quietStart', event.target.value || null)} /></label>
            <label className="profile-field">Quiet hours end<input type="time" value={draft.quietEnd ?? ''} onChange={event => change('quietEnd', event.target.value || null)} /></label>
            <p className="setting__hint">Leave both times empty for no quiet hours. Quiet hours use your saved timezone.</p>
          </details>
          <button className="btn btn--primary" type="submit">Save notification settings</button>
        </fieldset>
      </form>
      <p>Save your permission first, then prepare a connection and tap the bot link. In Telegram, press Start. Return here and refresh notification status.</p>
      <div className="beta-actions">
        <button className="btn btn--quiet" type="button" disabled={busy || conflict || uncertain || !!dirty || !view.preferences.enabled || view.legacyManaged} onClick={() => void perform('activate')}>Prepare bot connection</button>
        <button className="btn btn--quiet" type="button" disabled={busy || view.legacyManaged} onClick={() => void perform('disconnect')}>Disconnect Telegram</button>
      </div>
    </>}
    {activation && <a className="btn btn--primary" href={activation.activationUrl} target="_blank" rel="noopener noreferrer" onClick={event => {
      if (Date.parse(activation.expiresAt) <= Date.now()) { event.preventDefault(); setActivation(null); setError('This bot connection link expired. Prepare a new connection.'); }
      else if (openTelegramLink(activation.activationUrl)) event.preventDefault();
    }}>Open bot to connect</a>}
    <button className="btn btn--quiet" type="button" disabled={busy} onClick={() => void perform('refresh')}>Refresh notification status</button>
  </section>;
}
