import type { TextBlock } from '../api';
import { NameMark } from '../ui/NameMark';
import type { NameInfo } from '../ui/nameStyle';

/** A block of text with its name mentions as links. Offsets are Unicode code points, never UTF-16 indices. */
export function HighlightedText({ block, as = 'p', names }: {
  block: TextBlock; as?: 'p' | 'span'; names?: Map<number, NameInfo>;
}) {
  const cps = Array.from(block.text);
  const mentions = [...block.mentions].sort((a, b) => a.startCp - b.startCp);
  const parts: (string | JSX.Element)[] = [];
  let pos = 0;
  for (const m of mentions) {
    if (m.startCp < pos || m.endCp > cps.length || m.endCp <= m.startCp) continue; // overlapping or out of range
    if (m.startCp > pos) parts.push(cps.slice(pos, m.startCp).join(''));
    parts.push(
      <NameMark key={m.id} span={m} name={names?.get(m.nameId)}>
        {cps.slice(m.startCp, m.endCp).join('')}
      </NameMark>,
    );
    pos = m.endCp;
  }
  if (pos < cps.length) parts.push(cps.slice(pos).join(''));
  const Tag = as;
  return <Tag style={{ margin: 0 }}>{parts}</Tag>;
}
