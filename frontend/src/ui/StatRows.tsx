import type { ReactNode } from 'react';
import classes from './detail.module.css';

export interface StatRow { label: string; value: ReactNode }

/** Label left, value right in grey, like the rows of a facet group but not clickable. */
export function StatRows({ rows }: { rows: StatRow[] }) {
  return (
    <ul className={classes.stats}>
      {rows.map((r) => (
        <li key={r.label} className={classes.stat}>
          <span>{r.label}</span>
          <span className={classes.statValue}>{r.value}</span>
        </li>
      ))}
    </ul>
  );
}
