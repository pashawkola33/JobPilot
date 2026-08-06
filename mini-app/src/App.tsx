import { useEffect, useState } from 'react';
import { AnimatePresence, MotionConfig, motion } from 'motion/react';
import type { Job } from './data/types';
import { useJobPilot } from './lib/useJobPilot';
import { colorScheme, onColorSchemeChange, ready, setBackButton } from './lib/telegram';
import { BottomNav } from './components/BottomNav';
import { Skeleton, State } from './components/States';
import { IconAlert } from './components/icons';
import { Discover } from './features/Discover';
import { Review } from './features/Review';
import { Saved } from './features/Saved';
import { Applications } from './features/Applications';
import { Settings } from './features/Settings';
import { JobDetails } from './features/JobDetails';

export type Screen = 'discover' | 'review' | 'saved' | 'applications' | 'settings';
export type ThemeMode = 'telegram' | 'light' | 'dark';

export interface Preferences {
  theme: ThemeMode;
  reduceMotion: boolean;
  notifyStrongMatches: boolean;
  diagnostics: boolean;
}

export function App() {
  const store = useJobPilot();
  const [screen, setScreen] = useState<Screen>('discover');
  const [details, setDetails] = useState<Job | null>(null);
  const [preferences, setPreferences] = useState<Preferences>({
    theme: 'telegram',
    reduceMotion: false,
    notifyStrongMatches: true,
    diagnostics: false,
  });

  useTheme(preferences);
  useEffect(ready, []);

  /** Telegram's own back button closes the sheet; elsewhere Esc already does. */
  useEffect(() => setBackButton(details ? () => setDetails(null) : null), [details]);

  const queueLeft = store.total - store.position + (store.current ? 1 : 0);

  return (
    <MotionConfig reducedMotion={preferences.reduceMotion ? 'always' : 'user'}>
      <div className="app">
        <main className="app__main">
          {store.phase === 'loading' && <Skeleton />}

          {store.phase === 'error' && (
            <State
              mark={<IconAlert />}
              title="Vacancies did not load"
              text="The request to JobPilot failed before any vacancy arrived. Nothing was lost — try again."
              action={
                <button type="button" className="btn btn--primary" onClick={store.reload}>
                  Try again
                </button>
              }
            />
          )}

          {store.phase === 'ready' && (
            <AnimatePresence mode="wait" initial={false}>
              <motion.div
                key={screen}
                className="app__main"
                initial={{ opacity: 0, y: 4 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.15, ease: [0.22, 0.61, 0.36, 1] }}
              >
                {screen === 'discover' && (
                  <Discover
                    stats={store.stats}
                    jobs={store.jobs}
                    applications={store.applications}
                    onReview={() => setScreen('review')}
                    onDetails={setDetails}
                    onApplications={() => setScreen('applications')}
                  />
                )}
                {screen === 'review' && (
                  <Review
                    job={store.current}
                    position={store.position}
                    total={store.total}
                    direction={store.direction}
                    undo={store.undo}
                    onDecide={store.decide}
                    onUndo={store.revert}
                    onNext={store.skipToNext}
                    onDetails={setDetails}
                    onGoSaved={() => setScreen('saved')}
                  />
                )}
                {screen === 'saved' && (
                  <Saved
                    jobs={store.jobs}
                    onDetails={setDetails}
                    onReview={() => setScreen('review')}
                  />
                )}
                {screen === 'applications' && (
                  <Applications
                    applications={store.applications}
                    jobs={store.jobs}
                    onDetails={setDetails}
                    onReview={() => setScreen('review')}
                  />
                )}
                {screen === 'settings' && (
                  <Settings
                    preferences={preferences}
                    onChange={(next) => setPreferences((current) => ({ ...current, ...next }))}
                  />
                )}
              </motion.div>
            </AnimatePresence>
          )}
        </main>

        <BottomNav
          screen={screen}
          queueCount={store.phase === 'ready' ? Math.max(0, queueLeft) : 0}
          onSelect={setScreen}
        />
      </div>

      <JobDetails
        job={details}
        showDiagnostics={preferences.diagnostics}
        onClose={() => setDetails(null)}
      />
    </MotionConfig>
  );
}

/**
 * Writes the two attributes tokens.css keys off. `data-scheme` only matters in
 * telegram mode, where it selects the dark fallback set.
 */
function useTheme({ theme, reduceMotion }: Preferences) {
  const [scheme, setScheme] = useState(colorScheme);

  useEffect(() => onColorSchemeChange(() => setScheme(colorScheme())), []);

  useEffect(() => {
    const root = document.documentElement;
    root.dataset.theme = theme;
    root.dataset.scheme = theme === 'telegram' ? scheme : theme;
    root.dataset.motion = reduceMotion ? 'reduced' : 'full';
  }, [theme, scheme, reduceMotion]);
}
