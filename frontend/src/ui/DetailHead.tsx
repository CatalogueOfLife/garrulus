import { Fragment, type ReactNode } from 'react';
import { HeadRow } from './HeadRow';
import classes from './detail.module.css';

/**
 * The page title (h1) and the grey sub-line of the parts that are present; with `actions` (Atom feed, Follow) they
 * go in the same first row, over the side column (see HeadRow).
 */
export function DetailHead({ title, parts, actions }: { title: ReactNode; parts: ReactNode[]; actions?: ReactNode }) {
  const shown = parts.filter((p) => p !== null && p !== undefined && p !== false && p !== '');
  return (
    <HeadRow actions={actions}>
      <h1 className={classes.title}>{title}</h1>
      {shown.length ? (
        <p className={classes.sub}>
          {shown.map((p, i) => <Fragment key={i}>{i > 0 && ' '}<span className={classes.subPart}>{p}</span></Fragment>)}
        </p>
      ) : null}
    </HeadRow>
  );
}
