import { motion, useReducedMotion } from 'motion/react';
import { BAND_THRESHOLDS, type ScoreBand } from '../data/types';
import { bandLabel } from '../lib/format';

/**
 * The score, drawn as a calibrated instrument. Ticks mark the real band boundaries
 * used by JobMatchingService, so the position of the fill is readable against the
 * same scale the backend scored against.
 *
 * Score is carried three ways — numeral, band name, bar position — never colour alone.
 */
export function ScoreRail({ score, band }: { score: number; band: ScoreBand }) {
  const reduced = useReducedMotion();
  const label = bandLabel(band);

  return (
    <div className="rail" role="img" aria-label={`Match score ${score} out of 100. ${label}.`}>
      <div className="rail__head">
        <span className="rail__score">{score}</span>
        <span className="rail__of">/100</span>
        <span className="rail__band">{label}</span>
      </div>

      <div className="rail__track">
        <motion.div
          className="rail__fill"
          initial={{ scaleX: reduced ? score / 100 : 0 }}
          animate={{ scaleX: score / 100 }}
          transition={{ duration: 0.55, ease: [0.22, 0.61, 0.36, 1] }}
        />
      </div>

      <div className="rail__ticks" aria-hidden="true">
        {BAND_THRESHOLDS.map((threshold) => (
          <span key={threshold}>
            <span className="rail__tick" style={{ left: `${threshold}%` }} />
            <span className="rail__tick-label" style={{ left: `${threshold}%`, top: '5px' }}>
              {threshold}
            </span>
          </span>
        ))}
      </div>
    </div>
  );
}
