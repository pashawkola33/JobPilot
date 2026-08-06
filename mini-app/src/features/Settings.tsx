import type { Preferences, ThemeMode } from '../App';
import { available } from '../lib/telegram';

const THEMES: { id: ThemeMode; label: string }[] = [
  { id: 'telegram', label: 'Telegram' },
  { id: 'light', label: 'Light' },
  { id: 'dark', label: 'Dark' },
];

export function Settings({
  preferences,
  onChange,
}: {
  preferences: Preferences;
  onChange: (next: Partial<Preferences>) => void;
}) {
  return (
    <>
      <header className="topbar">
        <h1 className="topbar__title">Settings</h1>
      </header>

      <div className="scroll">
        <div className="setting">
          <div>
            <div className="setting__label" id="theme-label">
              Theme
            </div>
            <p className="setting__hint">
              {available
                ? 'Telegram follows the theme of your client.'
                : 'Telegram is unavailable here, so that option follows your system.'}
            </p>
          </div>
          <div className="segmented" role="group" aria-labelledby="theme-label">
            {THEMES.map(({ id, label }) => (
              <button
                key={id}
                type="button"
                className="segmented__option"
                aria-pressed={preferences.theme === id}
                onClick={() => onChange({ theme: id })}
              >
                {label}
              </button>
            ))}
          </div>
        </div>

        <label className="setting">
          <div>
            <div className="setting__label">Reduce motion</div>
            <p className="setting__hint">
              Turns off card and sheet transitions. Your system setting already applies on
              its own.
            </p>
          </div>
          <input
            type="checkbox"
            className="switch"
            checked={preferences.reduceMotion}
            onChange={(event) => onChange({ reduceMotion: event.target.checked })}
          />
        </label>

        <label className="setting">
          <div>
            <div className="setting__label">Notify on strong matches</div>
            <p className="setting__hint">
              Sends a Telegram message when a vacancy scores 85 or above. Local preview
              only — nothing is sent from this build.
            </p>
          </div>
          <input
            type="checkbox"
            className="switch"
            checked={preferences.notifyStrongMatches}
            onChange={(event) => onChange({ notifyStrongMatches: event.target.checked })}
          />
        </label>

        {import.meta.env.DEV && (
          <>
            <label className="setting">
              <div>
                <div className="setting__label">Show diagnostics</div>
                <p className="setting__hint">
                  Adds tenant, external id and screening codes to vacancy details.
                  Development builds only.
                </p>
              </div>
              <input
                type="checkbox"
                className="switch"
                checked={preferences.diagnostics}
                onChange={(event) => onChange({ diagnostics: event.target.checked })}
              />
            </label>

            <div className="diagnostics">
              {`environment  ${available ? 'telegram' : 'browser'}\n`}
              {`data source  in-memory mock\n`}
              {`fail switch  add ?mock=fail to the url`}
            </div>
          </>
        )}
      </div>
    </>
  );
}
