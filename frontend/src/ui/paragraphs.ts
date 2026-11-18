import type { MentionSpan, TextBlock } from '../api';

/**
 * A text split at blank lines into paragraphs, each trimmed and carrying the spans that lie inside it, moved to its own
 * offsets. Offsets are Unicode code points, never UTF-16 indices; a span across a break or out of range is dropped.
 */
export function paragraphs(text: string, mentions: MentionSpan[] = [], type = 'PARAGRAPH'): TextBlock[] {
  const cps = Array.from(text);
  const out: TextBlock[] = [];
  const flush = (from: number, to: number) => {
    let s = from;
    let e = to;
    while (s < e && /\s/.test(cps[s])) s++;
    while (e > s && /\s/.test(cps[e - 1])) e--;
    if (e <= s) return;
    out.push({
      id: `p${s}`, type, level: 0, sectionPath: [], text: cps.slice(s, e).join(''),
      mentions: mentions
        .filter((m) => m.startCp >= s && m.endCp <= e && m.endCp > m.startCp)
        .map((m) => ({ ...m, startCp: m.startCp - s, endCp: m.endCp - s })),
    });
  };
  let start = 0;
  for (let k = 0; k < cps.length; k++) {
    if (cps[k] === '\n' && cps[k + 1] === '\n') {
      flush(start, k);
      while (k + 1 < cps.length && cps[k + 1] === '\n') k++;
      start = k + 1;
    }
  }
  flush(start, cps.length);
  return out;
}
