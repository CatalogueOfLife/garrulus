import { Fragment, type ReactNode } from 'react';
import classes from './detail.module.css';

/** The page title (h1) and the grey sub-line of the parts that are present. */
export function DetailHead({ title, parts }: { title: ReactNode; parts: ReactNode[] }) {
  const shown = parts.filter((p) => p !== null && p !== undefined && p !== false && p !== '');
  return (
    <>
      <h1 className={classes.title}>{title}</h1>
      {shown.length ? (
        <p className={classes.sub}>
          {shown.map((p, i) => <Fragment key={i}>{i > 0 && ' '}<span className={classes.subPart}>{p}</span></Fragment>)}
        </p>
      ) : null}
    </>
  );
}
