/**
 * Hand-rolled SVG marks — a dozen paths beat an icon dependency.
 * All are decorative: every control that uses one carries its own accessible name.
 */

type Props = { size?: number };

const base = (size: number) => ({
  width: size,
  height: size,
  viewBox: '0 0 24 24',
  fill: 'none' as const,
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  'aria-hidden': true,
});

export const IconDiscover = ({ size = 21 }: Props) => (
  <svg {...base(size)}>
    <path d="M12 3.2 14.3 9.7 20.8 12 14.3 14.3 12 20.8 9.7 14.3 3.2 12 9.7 9.7Z" />
  </svg>
);

export const IconReview = ({ size = 21 }: Props) => (
  <svg {...base(size)}>
    <rect x="3.2" y="6.6" width="17.6" height="12.2" rx="2.4" />
    <path d="M6.6 3.6h10.8" />
  </svg>
);

export const IconSaved = ({ size = 21 }: Props) => (
  <svg {...base(size)}>
    <path d="M6.2 3.8h11.6v16.4L12 16.1l-5.8 4.1Z" />
  </svg>
);

export const IconApplications = ({ size = 21 }: Props) => (
  <svg {...base(size)}>
    <path d="M4 6.5h9.5M4 12h9.5M4 17.5h6" />
    <path d="m16.4 16.6 1.9 1.9 3.3-3.9" />
  </svg>
);

export const IconSettings = ({ size = 21 }: Props) => (
  <svg {...base(size)}>
    <path d="M3.5 8h13M20.5 8h-2M3.5 16h5M20.5 16h-8" />
    <circle cx="18.2" cy="8" r="2.2" />
    <circle cx="10.4" cy="16" r="2.2" />
  </svg>
);

export const IconChevronRight = ({ size = 16 }: Props) => (
  <svg {...base(size)}>
    <path d="m9.5 5.5 6.5 6.5-6.5 6.5" />
  </svg>
);

export const IconChevronDown = ({ size = 16 }: Props) => (
  <svg {...base(size)}>
    <path d="m5.5 9.5 6.5 6.5 6.5-6.5" />
  </svg>
);

export const IconExternal = ({ size = 16 }: Props) => (
  <svg {...base(size)}>
    <path d="M14 4.5h5.5V10M19 5l-8 8" />
    <path d="M18.5 14.5v4a1.5 1.5 0 0 1-1.5 1.5H5.5A1.5 1.5 0 0 1 4 18.5V7a1.5 1.5 0 0 1 1.5-1.5h4" />
  </svg>
);

export const IconClose = ({ size = 17 }: Props) => (
  <svg {...base(size)}>
    <path d="m6.5 6.5 11 11M17.5 6.5l-11 11" />
  </svg>
);

/** Empty-state mark: an emptied queue, drawn as one open frame. */
export const IconEmpty = ({ size = 34 }: Props) => (
  <svg {...base(size)} strokeWidth={1.2}>
    <rect x="3" y="6" width="18" height="13" rx="2.5" />
    <path d="M7.5 12.5h9" />
  </svg>
);

export const IconAlert = ({ size = 34 }: Props) => (
  <svg {...base(size)} strokeWidth={1.2}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7.5v5.5" />
    <path d="M12 16.3v.2" />
  </svg>
);
