import { useEffect, useRef, useState } from 'react';
import type { Job } from '../data/types';
import { BAND_THRESHOLDS } from '../data/types';
import {
  age,
  remoteLabel,
  seniorityLabel,
  shortDate,
  sourceLabel,
  workflowLabel,
} from '../lib/format';
import { holdVerticalSwipes } from '../lib/telegram';
import { IconChevronDown, IconClose } from '../components/icons';
import { ScoreRail } from '../components/ScoreRail';
import { OpenVacancy } from './Review';
import { Points } from './JobCard';

/** Where the sheet rests. The heights themselves belong to the stylesheet, not to this file. */
type Snap = 'collapsed' | 'expanded';

/**
 * How far a drag must travel before it changes snap state.
 *
 * Deliberately measured from where the drag started rather than from the midpoint between
 * the two snaps: that midpoint is a quarter of the screen away, so every state change would
 * need a long deliberate haul instead of the flick a bottom sheet is expected to answer.
 */
const COMMIT_PX = 64;

/** The snap heights in pixels, read back from the stylesheet that defines them. */
function snapMetrics(sheet: HTMLDialogElement) {
  const styles = getComputedStyle(sheet);
  const visible =
    parseFloat(
      getComputedStyle(document.documentElement).getPropertyValue('--tg-viewport-stable-height'),
    ) || window.innerHeight;
  const max = parseFloat(styles.maxHeight) || visible;
  const ratio = (name: string) => parseFloat(styles.getPropertyValue(name)) || 0;
  return {
    collapsed: Math.min(visible * ratio('--sheet-collapsed'), max),
    expanded: Math.min(visible * ratio('--sheet-expanded'), max),
  };
}

/**
 * A native <dialog>: focus trapping, Esc, inert background and the backdrop all come
 * from the platform. Enter and exit are CSS transitions with allow-discrete, so no
 * animation library is involved in showing it.
 *
 * It is a real bottom sheet on top of that: two snap heights, dragged by its handle. The
 * dialog is retained because nothing about that behaviour fights it — the failure it was
 * blamed for is shrink-wrap sizing (see app.css), which an explicit height settles, whereas
 * replacing it would mean hand-rolling the focus trap, inert background, Esc, focus restore
 * and top-layer stacking it provides for nothing.
 */
export function JobDetails({
  job,
  showDiagnostics,
  onClose,
}: {
  job: Job | null;
  showDiagnostics: boolean;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  /** Held through the closing transition so the sheet does not empty mid-fade. */
  const [shown, setShown] = useState<Job | null>(job);
  const [snap, setSnap] = useState<Snap>('collapsed');
  const open = job !== null;
  /** Live drag, never rendered: a height per pointermove through React would be a frame behind. */
  const drag = useRef<{ pointer: number; from: number; startHeight: number; height: number } | null>(
    null,
  );

  /**
   * Telegram's own vertical gesture competes with scrolling this sheet, so the host is asked
   * to stand down for exactly as long as the sheet is up. Declared before the effect that
   * calls showModal(), so the gesture is ours by the time the dialog is interactive.
   *
   * The restore is the effect's cleanup rather than anything in `onClose`, which is what
   * makes it unconditional: React runs it on every path out — the close button, Esc, the
   * backdrop, the job going null, and unmount alike. A close handler would cover only the
   * paths someone remembered to route through it.
   */
  useEffect(() => (open ? holdVerticalSwipes() : undefined), [open]);

  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    if (job) {
      setShown(job);
      if (!element.open) {
        // Every open starts at the near snap, whatever the last one was left at.
        setSnap('collapsed');
        element.showModal();
        // The body outlives the dialog and keeps its offset, so a reopen would resume
        // wherever the last vacancy was left. Only a shown dialog has a scroll box to
        // reset, which is why this follows showModal() rather than preceding it.
        element.querySelector('.sheet__body')?.scrollTo(0, 0);
      }
    } else if (element.open) {
      element.close();
    }
  }, [job]);

  /**
   * Local drag, on the handle only. Pointer events cover mouse, touch and pen through one
   * path, and pointer capture keeps the gesture attached to the handle once the finger has
   * left it — so there is no document-level listener and nothing global to unwind.
   */
  const startDrag = (event: React.PointerEvent<HTMLDivElement>) => {
    const element = dialog.current;
    // The close button lives in this region; a press on a control belongs to the control.
    if (!element || (event.target as HTMLElement).closest('button, a')) return;
    const height = element.getBoundingClientRect().height;
    drag.current = { pointer: event.pointerId, from: event.clientY, startHeight: height, height };
    event.currentTarget.setPointerCapture(event.pointerId);
    element.dataset.dragging = 'true';
  };

  const moveDrag = (event: React.PointerEvent<HTMLDivElement>) => {
    const element = dialog.current;
    const state = drag.current;
    if (!element || !state || state.pointer !== event.pointerId) return;
    const { collapsed, expanded } = snapMetrics(element);
    // Below the collapsed height is allowed so a dismissing drag tracks the finger rather
    // than sticking; above the expanded height is not, since that is the ceiling it snaps to.
    state.height = Math.min(
      expanded,
      Math.max(collapsed * 0.4, state.startHeight - (event.clientY - state.from)),
    );
    element.style.setProperty('--sheet-drag', `${state.height}px`);
  };

  /**
   * Ends the gesture and hands the height back to the snap ratio, which the transition then
   * animates to. Returns how far the drag travelled, or null if this pointer was not the one
   * dragging.
   */
  const releaseDrag = (event: React.PointerEvent<HTMLDivElement>) => {
    const element = dialog.current;
    const state = drag.current;
    if (!element || !state || state.pointer !== event.pointerId) return null;
    drag.current = null;
    delete element.dataset.dragging;
    element.style.removeProperty('--sheet-drag');
    return state.height - state.startHeight;
  };

  /** A completed gesture: the distance the user chose is what decides where the sheet lands. */
  const endDrag = (event: React.PointerEvent<HTMLDivElement>) => {
    const travelled = releaseDrag(event);
    if (travelled === null) return;
    if (travelled > COMMIT_PX) setSnap('expanded');
    else if (travelled < -COMMIT_PX) {
      if (snap === 'expanded') setSnap('collapsed');
      // Already at the near snap and still pulling down: that is a dismissal.
      else onClose();
    }
  };

  /**
   * The browser or the OS took the pointer away — an interrupted gesture, not a decision. It
   * expresses no intent, so committing on the distance it happened to reach would expand,
   * collapse or even dismiss the sheet on something the user never finished. Tear down and
   * let it spring back to the snap it started from.
   */
  const cancelDrag = (event: React.PointerEvent<HTMLDivElement>) => {
    releaseDrag(event);
  };

  return (
    <dialog
      ref={dialog}
      className="sheet"
      data-snap={snap}
      aria-label={shown ? `${shown.title} at ${shown.company}` : 'Vacancy details'}
      onCancel={onClose}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === dialog.current) onClose();
      }}
    >
      {shown && (
        <>
          <div
            className="sheet__handle"
            onPointerDown={startDrag}
            onPointerMove={moveDrag}
            onPointerUp={endDrag}
            onPointerCancel={cancelDrag}
          >
            <span className="sheet__grip" aria-hidden="true" />
            <header className="sheet__head">
              <div style={{ flex: 1, minWidth: 0 }}>
                <p className="eyebrow">
                  {sourceLabel(shown.source)} · {age(shown.publishedAt)}
                </p>
                <h2 className="title" style={{ fontSize: 19, marginTop: 4 }}>
                  {shown.title}
                </h2>
                <p className="job__company">{shown.company}</p>
              </div>
              <button
                type="button"
                className="sheet__close"
                onClick={onClose}
                aria-label="Close details"
              >
                <IconClose />
              </button>
            </header>
          </div>

          <div className="sheet__body">
            <section>
              <ScoreRail score={shown.score} band={shown.band} />
              <dl className="meta" style={{ marginTop: 'var(--s3)' }}>
                <Cell label="Location" value={shown.location} />
                <Cell label="Work mode" value={remoteLabel(shown.remoteType)} />
                <Cell label="Level" value={seniorityLabel(shown.seniority)} />
                <Cell label="Employment" value={shown.employmentType ?? 'Not stated'} />
                <Cell label="Posted" value={shortDate(shown.publishedAt)} />
                <Cell label="Status" value={workflowLabel(shown.workflowStatus)} />
              </dl>
            </section>

            <section>
              <h3 className="block__title">Why this matched</h3>
              {shown.matchSummary !== null ? (
                <p className="prose">{shown.matchSummary}</p>
              ) : (
                <p className="prose" style={{ color: 'var(--muted)' }}>
                  The score above comes from deterministic screening. A written summary needs
                  a full analysis, which JobPilot runs on request rather than for every vacancy.
                </p>
              )}
              <p className="eyebrow" style={{ marginTop: 'var(--s3)', lineHeight: 1.5 }}>
                Bands — {BAND_THRESHOLDS[2]}+ excellent · {BAND_THRESHOLDS[1]}+ good ·{' '}
                {BAND_THRESHOLDS[0]}+ possible
              </p>
            </section>

            <div>
              <Disclosure title="What fits" count={shown.strengths.length} open>
                <Points title="" mark="+" tone="up" items={shown.strengths} />
              </Disclosure>

              <Disclosure title="What to weigh" count={shown.risks.length}>
                <Points title="" mark="–" tone="down" items={shown.risks} />
              </Disclosure>

              <Disclosure title="Requirements" count={shown.requirements.length}>
                <ul className="points">
                  {shown.requirements.map((requirement, index) => (
                    <li key={requirement} className="points__item">
                      <span className="points__mark points__mark--index" aria-hidden="true">
                        {index + 1}
                      </span>
                      <span>{requirement}</span>
                    </li>
                  ))}
                </ul>
              </Disclosure>

              <Disclosure title="Activity" count={shown.activity.length}>
                <ul className="timeline">
                  {[...shown.activity].reverse().map((entry) => (
                    <li key={entry.at + entry.label} className="timeline__item">
                      <span className="timeline__when">{shortDate(entry.at)}</span>
                      <span>{entry.label}</span>
                    </li>
                  ))}
                </ul>
              </Disclosure>

              {showDiagnostics && shown.diagnostics && (
                <Disclosure title="Diagnostics" count={shown.diagnostics.screeningReasons.length}>
                  <pre className="diagnostics" style={{ padding: '0 0 var(--s3)' }}>
                    {`job ${shown.id}\ntenant ${shown.diagnostics.providerTenant}\nexternal ${shown.diagnostics.externalId}\n\n${shown.diagnostics.screeningReasons
                      .map((reason) => `${reason.stage} ${reason.code} — ${reason.message}`)
                      .join('\n')}`}
                  </pre>
                </Disclosure>
              )}
            </div>

            <OpenVacancy url={shown.canonicalUrl} />
          </div>
        </>
      )}
    </dialog>
  );
}

function Cell({ label, value }: { label: string; value: string }) {
  return (
    <div className="meta__cell">
      <dt className="eyebrow">{label}</dt>
      <dd className="meta__value" style={{ margin: 0 }}>
        {value}
      </dd>
    </div>
  );
}

function Disclosure({
  title,
  count,
  open,
  children,
}: {
  title: string;
  count: number;
  open?: boolean;
  children: React.ReactNode;
}) {
  if (count === 0) return null;
  return (
    <details className="disclose" open={open}>
      <summary className="disclose__summary">
        <span>
          {title}
          <span className="count">{count}</span>
        </span>
        <span className="disclose__chevron">
          <IconChevronDown />
        </span>
      </summary>
      <div className="disclose__content">{children}</div>
    </details>
  );
}
