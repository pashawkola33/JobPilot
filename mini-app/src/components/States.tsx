import type { ReactNode } from 'react';

/** Every empty and error state names what happened and what to do next. */
export function State({
  mark,
  title,
  text,
  action,
}: {
  mark: ReactNode;
  title: string;
  text: string;
  action?: ReactNode;
}) {
  return (
    <div className="state">
      <span className="state__mark">{mark}</span>
      <h2 className="state__title">{title}</h2>
      <p className="state__text">{text}</p>
      {action && <div className="state__action">{action}</div>}
    </div>
  );
}

const WIDTHS = ['62%', '96%', '88%', '40%', '92%', '78%'];

export function Skeleton() {
  return (
    <div className="skeleton" role="status" aria-label="Loading vacancies">
      {WIDTHS.map((width, index) => (
        <span
          key={width + index}
          className="skeleton__bar"
          style={{ width, animationDelay: `${index * 90}ms` }}
        />
      ))}
    </div>
  );
}
