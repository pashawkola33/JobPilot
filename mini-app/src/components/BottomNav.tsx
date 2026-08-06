import { motion } from 'motion/react';
import type { Screen } from '../App';
import {
  IconApplications,
  IconDiscover,
  IconReview,
  IconSaved,
  IconSettings,
} from './icons';

const TABS = [
  { id: 'discover', label: 'Discover', Icon: IconDiscover },
  { id: 'review', label: 'Review', Icon: IconReview },
  { id: 'saved', label: 'Saved', Icon: IconSaved },
  { id: 'applications', label: 'Track', Icon: IconApplications },
  { id: 'settings', label: 'Settings', Icon: IconSettings },
] as const satisfies readonly { id: Screen; label: string; Icon: typeof IconDiscover }[];

export function BottomNav({
  screen,
  queueCount,
  onSelect,
}: {
  screen: Screen;
  queueCount: number;
  onSelect: (screen: Screen) => void;
}) {
  return (
    <nav className="nav" aria-label="Sections">
      {TABS.map(({ id, label, Icon }) => {
        const active = screen === id;
        return (
          <button
            key={id}
            type="button"
            className="nav__item"
            aria-current={active ? 'page' : undefined}
            aria-label={
              id === 'review' && queueCount > 0 ? `Review, ${queueCount} waiting` : undefined
            }
            onClick={() => onSelect(id)}
          >
            {active && (
              <motion.span layoutId="nav-indicator" className="nav__indicator" aria-hidden="true" />
            )}
            <Icon />
            <span className="nav__label">{label}</span>
            {id === 'review' && queueCount > 0 && (
              <span className="nav__badge" aria-hidden="true">
                {queueCount}
              </span>
            )}
          </button>
        );
      })}
    </nav>
  );
}
