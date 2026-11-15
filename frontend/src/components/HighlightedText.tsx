import { Anchor } from '@mantine/core';
import { Link } from 'react-router-dom';
import type { TextBlock } from '../api';

/** A block of text with its name mentions as links. Offsets are Unicode code points, never UTF-16 indices. */
export function HighlightedText({ block }: { block: TextBlock }) {
  const cps = Array.from(block.text);
  const mentions = [...block.mentions].sort((a, b) => a.startCp - b.startCp);
  const parts: (string | JSX.Element)[] = [];
  let pos = 0;
  for (const m of mentions) {
    if (m.startCp < pos || m.endCp > cps.length || m.endCp <= m.startCp) continue; // overlapping or out of range
    if (m.startCp > pos) parts.push(cps.slice(pos, m.startCp).join(''));
    parts.push(
      <Anchor key={m.id} component={Link} to={`/name/${m.nameId}`} fs="italic"
        style={{ background: 'var(--mantine-color-jay-0)', borderRadius: 3, padding: '0 2px' }}>
        {cps.slice(m.startCp, m.endCp).join('')}
      </Anchor>,
    );
    pos = m.endCp;
  }
  if (pos < cps.length) parts.push(cps.slice(pos).join(''));
  return <p style={{ margin: 0 }}>{parts}</p>;
}
