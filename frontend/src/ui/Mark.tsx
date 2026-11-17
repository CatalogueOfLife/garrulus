import { useId } from 'react';

// The Garrulus mark, variant 3m (docs/brand/README.md): a jay's head with a swept-back crest, on a 100-unit square.
const HEAD =
  'M 97 52 C 90 47.5 82 45 74 44.5 C 71 35 63 28.4 52 26 C 40 23.38 24 16 5 15 C 17 21 24 26 28 30 ' +
  'C 19 30 12 33 6 39 C 16 39 24 41 30 45 C 22 57 23 74 36 95 C 42 84 49.9 73.9 60 67 ' +
  'C 66 62.9 72 60.38 78 59 C 85 57.39 91 55.5 97 52 Z';

// Cut out of the head: the two crest cuts, which start in the crest notches, and the moustache cut from the bill.
const CUTS = [
  'M 28 30 C 40 28.6 49.5 40 48 62 C 45.3 46.2 37.8 36.4 28 30 Z',
  'M 30 45 C 39 51 41.2 61.5 38 82 C 35 67 34.3 53.5 30 45 Z',
  'M 78 54.5 C 70 49.6 54.6 61.6 49 73.5 C 56.5 65.5 67 59.8 78 54.5 Z',
];

/**
 * The mark in `currentColor`. With a `title` it is an image of that name, without one it is decoration and hidden
 * from assistive technology. Each instance has its own mask id, so several marks can share a page.
 */
export function Mark({ size = 38, title }: { size?: number; title?: string }) {
  const mask = `gr-mark-${useId().replace(/[^\w-]/g, '')}`;
  const a11y = title ? { role: 'img', 'aria-label': title } : { 'aria-hidden': true };
  return (
    <svg width={size} height={size} viewBox="0 0 100 100" {...a11y}>
      {/* A luminance mask: white keeps the head, black cuts the eye and the three cuts out. Not paint colours. */}
      <mask id={mask} maskUnits="userSpaceOnUse" x="0" y="0" width="100" height="100">
        <rect width="100" height="100" fill="#fff" />
        <circle cx="61" cy="41" r="4.6" fill="#000" />
        {CUTS.map((d) => (
          <path key={d} d={d} fill="#000" />
        ))}
      </mask>
      <path d={HEAD} fill="currentColor" mask={`url(#${mask})`} />
    </svg>
  );
}
