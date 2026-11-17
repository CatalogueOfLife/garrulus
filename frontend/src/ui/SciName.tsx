import { Link } from 'react-router-dom';
import { ActMarker } from './ActMarker';
import { isItalicName, type NameInfo } from './nameStyle';

export function SciName({ name, rank, usageRank, kind, to, acts }: NameInfo & { name: string; to?: string; acts?: string[] }) {
  const text = isItalicName(name, { rank, usageRank, kind }) ? <i>{name}</i> : <>{name}</>;
  return (
    <>
      {to ? <Link to={to}>{text}</Link> : text}
      {(acts ?? []).map((a) => <ActMarker key={a} type={a} />)}
    </>
  );
}
