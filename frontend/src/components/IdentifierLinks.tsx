import type { IdentifierLink } from '../api';
import classes from '../ui/work.module.css';

/** One row of labelled identifier links; an identifier without a page is plain text. */
export function IdentifierLinks({ identifiers }: { identifiers?: IdentifierLink[] }) {
  if (!identifiers?.length) return null;
  return (
    <ul className={`${classes.identifiers} gr-wrap`} aria-label="Identifiers">
      {identifiers.map((i) => (
        <li key={`${i.scheme}:${i.value}`}>
          {i.url
            ? <a href={i.url} target="_blank" rel="noopener">{i.label} {i.value}</a>
            : <span>{i.label} {i.value}</span>}
        </li>
      ))}
    </ul>
  );
}
