import { useQuery } from '@tanstack/react-query';
import { getAdminLayers, type LayerHealthDay } from '../../api';
import { languageName } from '../../languages';
import { EmptyState } from '../../ui/EmptyState';
import styles from '../../ui/pages.module.css';
import { usePageTitle } from '../../usePageTitle';
import classes from './admin.module.css';
import { QueryState } from './adminUi';

const DAYS = 28;

function Grid({ health }: { health: LayerHealthDay[] }) {
  const layers = [...new Set(health.map((h) => h.layer))].sort();
  const days = [...new Set(health.map((h) => h.day))].sort().slice(-DAYS);
  const by = new Map(health.map((h) => [`${h.layer}|${h.day}`, h]));
  return (
    <>
    <p className={classes.muted}>Layer health, last {days.length} {days.length === 1 ? 'day' : 'days'} (empty outline: OK, grey: degraded, red: failing)</p>
    <div className={classes.tableWrap}>
    <table className={classes.grid}>
      <tbody>
        {layers.map((l) => (
          <tr key={l}>
            <th scope="row">{l}</th>
            {days.map((d) => {
              const h = by.get(`${l}|${d}`);
              const st = h?.state;
              return (
                <td key={d} className={st ? classes[`cell${st}`] : classes.cell} title={`${d}: ${st ?? 'no data'}`}>
                  {`${l} ${d} ${st ?? 'no data'}`}
                </td>
              );
            })}
          </tr>
        ))}
      </tbody>
    </table>
    </div>
    </>
  );
}

/** One line in place of a table that has no rows yet. */
function NoRows() {
  return <p className={classes.muted}>None yet.</p>;
}

function Contribution({ c }: { c: Record<string, Record<string, number>> }) {
  return (
    <table className={classes.table}>
      <thead><tr><th>Layer</th><th className={classes.num}>First found</th><th className={classes.num}>Only found</th></tr></thead>
      <tbody>{Object.entries(c).map(([l, v]) => <tr key={l}><td>{l}</td><td className={classes.num}>{v.first ?? 0}</td><td className={classes.num}>{v.only ?? 0}</td></tr>)}</tbody>
    </table>
  );
}

export function AdminLayersPage() {
  usePageTitle('Admin: layers');
  const q = useQuery({ queryKey: ['admin', 'layers'], queryFn: getAdminLayers });
  return (
    <div>
      <h1 className={styles.pageTitle}>Layers</h1>
      <QueryState query={q}>
        {q.data && (q.data.health.length === 0 && Object.keys(q.data.contribution).length === 0 && Object.keys(q.data.contributionByLanguage ?? {}).length === 0 && q.data.coverage.length === 0 ? (
          <EmptyState title="No coverage check yet.">
            The coverage check runs daily at 06:30 UTC and fills this page once the layers have harvested.
          </EmptyState>
        ) : (
          <>
            {q.data.health.length ? <Grid health={q.data.health} /> : <p className={classes.muted}>No layer health yet.</p>}
            <section className={classes.section}><h2>Contribution</h2>
              {Object.keys(q.data.contribution).length ? <Contribution c={q.data.contribution} /> : <NoRows />}</section>
            <section className={classes.section}>
              <h2>Contribution by country</h2>
              {Object.keys(q.data.contributionByCountry).length === 0 ? <NoRows /> : (
              <table className={classes.table}>
                <thead><tr><th>Country</th><th>Layer</th><th className={classes.num}>First found</th><th className={classes.num}>Only found</th></tr></thead>
                <tbody>{Object.entries(q.data.contributionByCountry).flatMap(([c, ls]) => Object.entries(ls).map(([l, v]) => (
                  <tr key={`${c}|${l}`}><td>{c}</td><td>{l}</td><td className={classes.num}>{v.first ?? 0}</td><td className={classes.num}>{v.only ?? 0}</td></tr>)))}</tbody>
              </table>)}
            </section>
            <section className={classes.section}>
              <h2>Contribution by language</h2>
              {Object.keys(q.data.contributionByLanguage ?? {}).length === 0 ? <NoRows /> : (
              <table className={classes.table}>
                <thead><tr><th>Language</th><th>Layer</th><th className={classes.num}>First found</th><th className={classes.num}>Only found</th></tr></thead>
                <tbody>{Object.entries(q.data.contributionByLanguage ?? {}).flatMap(([c, ls]) => Object.entries(ls).map(([l, v]) => (
                  <tr key={`${c}|${l}`}><td>{c === 'UNDECIDED' ? 'Undecided' : languageName(c)}</td><td>{l}</td><td className={classes.num}>{v.first ?? 0}</td><td className={classes.num}>{v.only ?? 0}</td></tr>)))}</tbody>
              </table>)}
            </section>
            <section className={classes.section}>
              <h2>Coverage</h2>
              {q.data.coverage.length === 0 ? <NoRows /> : (
              <table className={classes.table}>
                <thead><tr><th>Journal</th><th>Layer</th><th className={classes.num}>Sightings (28 days)</th><th className={classes.num}>Misses</th></tr></thead>
                <tbody>{q.data.coverage.map((r) => (
                  <tr key={`${r.sourceId}|${r.layer}`}><td>{r.title}</td><td>{r.layer}</td><td className={classes.num}>{r.sightings28d}</td><td className={classes.num}>{r.misses}</td></tr>))}</tbody>
              </table>)}
            </section>
          </>
        ))}
      </QueryState>
    </div>
  );
}
