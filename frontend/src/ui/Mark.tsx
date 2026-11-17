// The Garrulus mark, variant 3m (docs/brand/README.md), on a 100-unit square: one path filled even-odd, the same as
// docs/brand/garrulus-mark.svg. Its subpaths are the head with its swept-back crest, then what is cut out of it: the
// eye, the two crest cuts (which start in the crest notches) and the moustache cut from the bill.
const MARK = [
  'M 97 52 C 90 47.5 82 45 74 44.5 C 71 35 63 28.4 52 26 C 40 23.38 24 16 5 15 C 17 21 24 26 28 30 ' +
    'C 19 30 12 33 6 39 C 16 39 24 41 30 45 C 22 57 23 74 36 95 C 42 84 49.9 73.9 60 67 ' +
    'C 66 62.9 72 60.38 78 59 C 85 57.39 91 55.5 97 52 Z',
  'M 56.4 41 A 4.6 4.6 0 1 0 65.6 41 A 4.6 4.6 0 1 0 56.4 41 Z',
  'M 28 30 C 40 28.6 49.5 40 48 62 C 45.3 46.2 37.8 36.4 28 30 Z',
  'M 30 45 C 39 51 41.2 61.5 38 82 C 35 67 34.3 53.5 30 45 Z',
  'M 78 54.5 C 70 49.6 54.6 61.6 49 73.5 C 56.5 65.5 67 59.8 78 54.5 Z',
].join(' ');

/**
 * The mark in `currentColor`. With a `title` it is an image of that name, without one it is decoration and hidden
 * from assistive technology. It has no ids, so any number of marks can share a page.
 */
export function Mark({ size = 38, title }: { size?: number; title?: string }) {
  const a11y = title ? { role: 'img', 'aria-label': title } : { 'aria-hidden': true };
  return (
    <svg width={size} height={size} viewBox="0 0 100 100" {...a11y}>
      <path fill="currentColor" fillRule="evenodd" d={MARK} />
    </svg>
  );
}
