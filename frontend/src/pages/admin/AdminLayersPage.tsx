import { useQuery } from '@tanstack/react-query';
import { getAdminLayers, type LayerHealthDay } from '../../api';
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
    <table className={classes.grid}>
      <caption className={classes.muted}>Layer health, last {days.length} days (empty outline: OK, grey: degraded, red: failing)</caption>
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
  );
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
        {q.data && (
          <>
            <div className={classes.tableWrap}><Grid health={q.data.health} /></div>
            <section className={classes.section}><h2>Contribution</h2><Contribution c={q.data.contribution} /></section>
            <section className={classes.section}>
              <h2>Contribution by country</h2>
              <table className={classes.table}>
                <thead><tr><th>Country</th><th>Layer</th><th className={classes.num}>First found</th><th className={classes.num}>Only found</th></tr></thead>
                <tbody>{Object.entries(q.data.contributionByCountry).flatMap(([c, ls]) => Object.entries(ls).map(([l, v]) => (
                  <tr key={`${c}|${l}`}><td>{c}</td><td>{l}</td><td className={classes.num}>{v.first ?? 0}</td><td className={classes.num}>{v.only ?? 0}</td></tr>)))}</tbody>
              </table>
            </section>
            <section className={classes.section}>
              <h2>Coverage</h2>
              <table className={classes.table}>
                <thead><tr><th>Journal</th><th>Layer</th><th className={classes.num}>Sightings (28 days)</th><th className={classes.num}>Misses</th></tr></thead>
                <tbody>{q.data.coverage.map((r) => (
                  <tr key={`${r.sourceId}|${r.layer}`}><td>{r.title}</td><td>{r.layer}</td><td className={classes.num}>{r.sightings28d}</td><td className={classes.num}>{r.misses}</td></tr>))}</tbody>
              </table>
            </section>
          </>
        )}
      </QueryState>
    </div>
  );
}
