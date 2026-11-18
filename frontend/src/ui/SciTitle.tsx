import type { ReactNode } from 'react';
import type { MentionSpan } from '../api';
import { isItalicName, type NameInfo } from './nameStyle';
import { NameText } from './SciName';

export function SciTitle({ title, mentions, names }: { title: string; mentions: MentionSpan[]; names?: Map<number, NameInfo> }) {
  const cps = Array.from(title);
  const out: ReactNode[] = [];
  let pos = 0;
  mentions
    .filter((m) => Number.isInteger(m.startCp) && Number.isInteger(m.endCp))
    .sort((a, b) => a.startCp - b.startCp)
    .forEach((m) => {
      if (m.startCp < pos || m.endCp <= m.startCp || m.endCp > cps.length) return;
      if (m.startCp > pos) out.push(<span key={`t${pos}`}>{cps.slice(pos, m.startCp).join('')}</span>);
      const text = cps.slice(m.startCp, m.endCp).join('');
      const italic = isItalicName(text, names?.get(m.nameId) ?? {});
      out.push(<span key={m.id}><NameText text={text} italic={italic} /></span>);
      pos = m.endCp;
    });
  if (pos < cps.length) out.push(<span key={`t${pos}`}>{cps.slice(pos).join('')}</span>);
  return <>{out}</>;
}
