import { Alert, Anchor, Loader, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listRegistrations } from '../api';
import { STATUS_TEXT, notIdentifiedText } from './RegisterJournalDialog';
import styles from '../ui/pages.module.css';

const date = (iso: string) => iso.slice(0, 10);

/** The signed-in user's journal registrations on the account page. */
export function RegisteredJournals() {
  const list = useQuery({ queryKey: ['registrations'], queryFn: listRegistrations });
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>Registered journals</h2>
      {list.isError ? <Alert color="red">Could not load your registered journals.</Alert>
        : !list.data ? <Loader size="sm" />
        : list.data.length === 0 ? <Text size="sm" c="dimmed">You have not registered a journal. Use Register a journal on the Journals page.</Text>
        : (
          <ul className={styles.ruled}>
            {list.data.map((r) => {
              const j = r.journal;
              const title = j?.title ?? null;
              const public_ = j && (j.tier === 'CORE' || j.tier === 'BROAD');
              return (
                <li key={r.id}>
                  <div className={styles.rowLine}>
                    <span className={styles.rowTitle}>{r.input}</span>
                    <span className={styles.rowCount}>{date(r.created)}</span>
                  </div>
                  <p className={styles.rowMeta}>
                    {STATUS_TEXT[r.status]}
                    {r.status === 'NOT_IDENTIFIED' ? ` · ${notIdentifiedText(r.reason)}` : ''}
                    {title ? <> · {public_ ? <Anchor component={Link} to={`/source/${j.id}`} inherit>{title}</Anchor> : title}</> : null}
                  </p>
                </li>
              );
            })}
          </ul>
        )}
    </section>
  );
}
