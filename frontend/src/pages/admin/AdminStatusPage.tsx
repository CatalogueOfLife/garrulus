import { useQuery } from '@tanstack/react-query';
import { getAdminStatus } from '../../api';
import styles from '../../ui/pages.module.css';
import { usePageTitle } from '../../usePageTitle';
import classes from './admin.module.css';
import { QueryState } from './adminUi';

type Nested = Record<string, Record<string, number>>;

function NestedTable({ title, data }: { title: string; data: Nested }) {
  const inner = [...new Set(Object.values(data).flatMap((m) => Object.keys(m)))];
  return (
    <section className={classes.section}>
      <h2>{title}</h2>
      <div className={classes.tableWrap}><table className={classes.table}>
        <thead><tr><th /> {inner.map((k) => <th key={k} className={classes.num}>{k}</th>)}</tr></thead>
        <tbody>{Object.entries(data).map(([o, m]) => (
          <tr key={o}><th scope="row">{o}</th>{inner.map((k) => <td key={k} className={classes.num}>{m[k] ?? ''}</td>)}</tr>))}</tbody>
      </table></div>
    </section>
  );
}

const cell = (v: unknown) => (v === null || v === undefined ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v));

function RowsTable({ title, rows }: { title: string; rows: Record<string, unknown>[] }) {
  const cols = [...new Set(rows.flatMap((r) => Object.keys(r)))];
  return (
    <section className={classes.section}>
      <h2>{title}</h2>
      {rows.length === 0 ? <p className={classes.muted}>None.</p> : (
        <div className={classes.tableWrap}><table className={classes.table}>
          <thead><tr>{cols.map((c) => <th key={c}>{c}</th>)}</tr></thead>
          <tbody>{rows.map((r, i) => <tr key={i}>{cols.map((c) => <td key={c}>{cell(r[c])}</td>)}</tr>)}</tbody>
        </table></div>
      )}
    </section>
  );
}

export function AdminStatusPage() {
  usePageTitle('Admin: status');
  const q = useQuery({ queryKey: ['admin', 'status'], queryFn: getAdminStatus, refetchInterval: 5 * 60_000 });
  return (
    <div>
      <h1 className={styles.pageTitle}>Status</h1>
      <QueryState query={q}>
        {q.data && (
          <>
            <NestedTable title="Tasks by type and state" data={q.data.tasks} />
            <NestedTable title="Works by state and text level" data={q.data.works} />
            <RowsTable title="Sources by tier" rows={[q.data.sources]} />
            <RowsTable title="API usage today" rows={q.data.apiUsage} />
            <RowsTable title="Last runs" rows={q.data.harvestRuns} />
            <RowsTable title="XR releases" rows={q.data.xrReleases} />
            <RowsTable title="Backfill runs" rows={q.data.backfillRuns} />
            <RowsTable title="Finder rollouts" rows={q.data.finderRollouts} />
          </>
        )}
      </QueryState>
    </div>
  );
}
