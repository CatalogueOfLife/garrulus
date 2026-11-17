import { readFileSync } from 'node:fs';
import { parseTokens } from './tokenValues';

function luminance(hex: string): number {
  const n = parseInt(hex.slice(1), 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

// tokens.css?raw comes back empty under vitest's css:false, so read the file directly (vitest runs from frontend/).
const tokens = parseTokens(readFileSync('src/styles/tokens.css', 'utf8'));

describe('token contrast', () => {
  for (const scheme of ['light', 'dark'] as const) {
    for (const name of ['--gr-ink', '--gr-grey', '--gr-red']) {
      it(`${name} on paper is at least 4.5:1 in ${scheme}`, () => {
        const t = tokens[scheme];
        expect(t['--gr-paper']).toMatch(/^#[0-9A-Fa-f]{6}$/);
        expect(t[name]).toMatch(/^#[0-9A-Fa-f]{6}$/);
        expect(contrast(t[name], t['--gr-paper'])).toBeGreaterThanOrEqual(4.5);
      });
    }
  }

  it('takes the top-level blocks only, not those inside media queries', () => {
    expect(tokens.light['--gr-type-display-size']).toBe('132px');
  });

  it('defines the layout, space and type tokens', () => {
    expect(tokens.light['--gr-width']).toBe('1240px');
    expect(tokens.light['--gr-side']).toBe('340px');
    expect(tokens.light['--gr-space-8']).toBe('64px');
    expect(tokens.light['--gr-type-title-size']).toBe('40px');
  });
});
