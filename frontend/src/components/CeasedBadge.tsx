import { Link } from 'react-router-dom';
import type { PublicationStatus, SourceRef } from '../api';
import classes from './CeasedBadge.module.css';

/** Says that a journal has ceased publication, with the last issue's year and the journal that continues it. Nothing for ACTIVE. */
export function CeasedBadge({ status, lastIssueYear, continuedBy, inline }: {
  status?: PublicationStatus | null;
  lastIssueYear?: number | null;
  continuedBy?: SourceRef | null;
  inline?: boolean;
}) {
  if (status !== 'CEASED') return null;
  return (
    <p className={`${classes.badge} ${inline ? classes.inline : ''}`}>
      <span>Ceased publication{lastIssueYear ? ` · last issue ${lastIssueYear}` : ''}</span>
      {continuedBy ? (
        <>
          {' · '}Continued as <Link to={`/source/${continuedBy.id}`}>{continuedBy.title}</Link>
        </>
      ) : null}
    </p>
  );
}
