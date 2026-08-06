import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import type { UndoEntry } from '../lib/useJobPilot';
import { UNDO_MS } from '../lib/useJobPilot';

/**
 * Confirms the action and holds the window to take it back. role="status" means the
 * confirmation is announced, not only seen.
 */
export function UndoToast({ undo, onUndo }: { undo: UndoEntry | null; onUndo: () => void }) {
  const reduced = useReducedMotion();

  return (
    <AnimatePresence>
      {undo && (
        <motion.div
          key={`${undo.jobId}-${undo.action}`}
          className="toast"
          role="status"
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: 6 }}
          transition={{ duration: 0.18, ease: [0.22, 0.61, 0.36, 1] }}
        >
          <span className="toast__text">
            {undo.action}
            <span className="toast__title">{undo.title}</span>
          </span>
          <button type="button" className="toast__undo" onClick={onUndo}>
            Undo
          </button>
          {!reduced && (
            <motion.span
              className="toast__timer"
              aria-hidden="true"
              initial={{ scaleX: 1 }}
              animate={{ scaleX: 0 }}
              transition={{ duration: UNDO_MS / 1000, ease: 'linear' }}
            />
          )}
        </motion.div>
      )}
    </AnimatePresence>
  );
}
