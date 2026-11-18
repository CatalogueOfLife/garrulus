import { Fragment } from 'react';
import { Link } from 'react-router-dom';
import { ActMarker } from './ActMarker';
import { isItalicName, nameRuns, type NameInfo } from './nameStyle';

/** A name's text: italic parts in `<i>`, rank markers and hybrid signs between them roman. */
export function NameText({ text, italic }: { text: string; italic: boolean }) {
  return (
    <>
      {nameRuns(text, italic).map((r, i) => (r.italic ? <i key={i}>{r.text}</i> : <Fragment key={i}>{r.text}</Fragment>))}
    </>
  );
}

export function SciName({ name, rank, usageRank, kind, to, acts }: NameInfo & { name: string; to?: string; acts?: string[] }) {
  const text = <NameText text={name} italic={isItalicName(name, { rank, usageRank, kind })} />;
  return (
    <>
      {to ? <Link to={to}>{text}</Link> : text}
      {(acts ?? []).map((a) => <ActMarker key={a} type={a} />)}
    </>
  );
}
