import type { ReactNode } from 'react';
import { Rule } from './Rule';
import classes from './list.module.css';

/** The right column: the lede under a rule, then the filters. */
export function SideColumn({ lede, children }: { lede?: ReactNode; children: ReactNode }) {
  return (
    <aside className={classes.side}>
      {lede ? (
        <>
          <Rule />
          <p className={classes.lede}>{lede}</p>
        </>
      ) : null}
      {children}
    </aside>
  );
}
