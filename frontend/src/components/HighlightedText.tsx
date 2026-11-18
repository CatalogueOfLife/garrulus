import type { TextBlock } from '../api';
import { NameMark } from '../ui/NameMark';
import type { NameInfo } from '../ui/nameStyle';

type Part = { start: number; end: number; render: (text: string) => JSX.Element };

/**
 * A block of text with its name mentions as links and its act markers ("sp. nov.") in red. Offsets are Unicode code
 * points, never UTF-16 indices; an overlapping or out-of-range span is left out.
 */
export function HighlightedText({ block, as = 'p', names }: {
  block: TextBlock; as?: 'p' | 'span'; names?: Map<number, NameInfo>;
}) {
  const cps = Array.from(block.text);
  const marks: Part[] = [
    ...block.mentions.map((m) => ({
      start: m.startCp, end: m.endCp,
      render: (t: string) => <NameMark key={`m${m.id}`} span={m} name={names?.get(m.nameId)}>{t}</NameMark>,
    })),
    ...(block.acts ?? []).map((a) => ({
      start: a.startCp, end: a.endCp,
      render: (t: string) => <span key={`a${a.mentionId}-${a.startCp}`} className="gr-act-mark">{t}</span>,
    })),
  ].sort((a, b) => a.start - b.start);
  const parts: (string | JSX.Element)[] = [];
  let pos = 0;
  for (const m of marks) {
    if (m.start < pos || m.end > cps.length || m.end <= m.start) continue; // overlapping or out of range
    if (m.start > pos) parts.push(cps.slice(pos, m.start).join(''));
    parts.push(m.render(cps.slice(m.start, m.end).join('')));
    pos = m.end;
  }
  if (pos < cps.length) parts.push(cps.slice(pos).join(''));
  const Tag = as;
  return <Tag style={{ margin: 0 }}>{parts}</Tag>;
}
