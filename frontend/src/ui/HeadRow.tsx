import type { ReactNode } from 'react';
import classes from './headRow.module.css';

/**
 * The first row under the page header: the page's own head (a title, or the search's result count) on the left and,
 * when there are any, the page actions (Atom feed, Follow) in the side column's track, flush right under the account
 * menu. Every page puts them there, so they are found at the same place. The two columns below both start with a rule,
 * at the same height. Without actions the head keeps the full width.
 */
export function HeadRow({ children, actions, compact }: {
  children: ReactNode;
  actions?: ReactNode;
  /** A one-line head (the result count): the actions are centred on it instead of on a title's first line. */
  compact?: boolean;
}) {
  if (!actions) return <>{children}</>;
  return (
    <div className={`${classes.row} ${compact ? classes.compact : ''}`}>
      <div className={classes.main}>{children}</div>
      <div className={classes.actions}>{actions}</div>
    </div>
  );
}
