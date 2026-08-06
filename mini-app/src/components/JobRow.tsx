import type { ReactNode } from 'react';
import { IconChevronRight } from './icons';

/**
 * One scannable line, shared by Discover, Saved and Applications. The score sits in a
 * fixed left gutter so the column reads straight down without scanning across.
 *
 * Without `onOpen` the row is static: tracked applications whose vacancy is no longer
 * in the review set have nothing to open, and a dead button is worse than no button.
 */
export function JobRow({
  score,
  title,
  meta,
  trailing,
  onOpen,
}: {
  score: number;
  title: string;
  meta: string;
  trailing?: ReactNode;
  onOpen?: () => void;
}) {
  const body = (
    <>
      <span className="row__score" aria-hidden="true">
        {score}
      </span>
      <span className="row__body">
        <span className="row__title">{title}</span>
        <span className="row__meta">{meta}</span>
      </span>
      {/* The chevron slot is reserved even when the row does not open, so status
          labels stay on one right edge down the whole list. */}
      <span className="row__trailing">
        {trailing}
        <span style={{ visibility: onOpen ? 'visible' : 'hidden', display: 'flex' }}>
          <IconChevronRight />
        </span>
      </span>
    </>
  );

  return (
    <li>
      {onOpen ? (
        <button
          type="button"
          className="row"
          onClick={onOpen}
          aria-label={`${title}. ${meta}. Match score ${score} out of 100. Open details.`}
        >
          {body}
        </button>
      ) : (
        <div className="row">
          <span className="visually-hidden">Match score {score} out of 100.</span>
          {body}
        </div>
      )}
    </li>
  );
}
