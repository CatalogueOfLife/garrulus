import type { ReactNode } from 'react';
import type { MentionSpan } from '../api';
import { isItalicName, type NameInfo } from './nameStyle';

export function SciTitle({ title, mentions, names }: { title: string; mentions: MentionSpan[]; names?: Map<number, NameInfo> }) {
  const cps = Array.from(title);
  const out: ReactNode[] = [];
  let pos = 0;
  [...mentions].sort((a, b) => a.startCp - b.startCp).forEach((m) => {
    if (m.startCp < pos || m.endCp <= m.startCp || m.endCp > cps.length) return;
    if (m.startCp > pos) out.push(<span key={`t${pos}`}>{cps.slice(pos, m.startCp).join('')}</span>);
    const text = cps.slice(m.startCp, m.endCp).join('');
    out.push(isItalicName(text, names?.get(m.nameId) ?? {}) ? <i key={m.id}>{text}</i> : <span key={m.id}>{text}</span>);
    pos = m.endCp;
  });
  if (pos < cps.length) out.push(<span key={`t${pos}`}>{cps.slice(pos).join('')}</span>);
  return <>{out}</>;
}
