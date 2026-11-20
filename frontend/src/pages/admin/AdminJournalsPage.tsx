import { useState } from 'react';
import { Button, Checkbox, Group, NativeSelect, TextInput } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { listAdminSources, type AdminSource, type AdminSourceFilter } from '../../api';
import styles from '../../ui/pages.module.css';
import { usePageTitle } from '../../usePageTitle';
import classes from './admin.module.css';
import { QueryState } from './adminUi';

const PAGE = 50;
const ORIGINS = ['SEED_TOPIC', 'SEED_LIST', 'DISCOVERED', 'MANUAL', 'DOAJ', 'PKP_BEACON', 'PLAZI', 'COL_REFERENCES', 'BLR', 'REGISTERED'];
const TIERS = ['CANDIDATE', 'CORE', 'BROAD', 'EXCLUDED'];

const fmt = (n?: number | null, d = 1) => (n === null || n === undefined ? '–' : n.toFixed(d));

function filterOf(p: URLSearchParams): AdminSourceFilter {
  const status = p.get('status');
  return {
    tier: p.get('tier') ?? 'CANDIDATE', origin: p.get('origin') || undefined, country: p.get('country') || undefined,
    publicationStatus: status === 'ACTIVE' || status === 'CEASED' ? status : undefined,
    dormant: status === 'dormant' ? true : undefined,
    registered: p.get('registered') === '1' ? true : undefined, proposal: p.get('proposal') === '1' ? true : undefined,
    q: p.get('q') || undefined, limit: PAGE, offset: Number(p.get('offset')) || 0,
  };
}

export const flagsOf = (s: AdminSource) => [
  s.dormant && { t: 'dormant', red: false },
  s.revival && { t: 'revival', red: false },
  s.publicationStatus === 'CEASED' && { t: 'ceased', red: true },
  s.proposal && { t: `${s.proposal.toLowerCase()} proposal`, red: false },
].filter(Boolean) as { t: string; red: boolean }[];

function Row({ s }: { s: AdminSource }) {
  const sm = s.sample;
  const top = sm ? Object.entries(sm.taxgroups ?? {}).sort((a, b) => b[1] - a[1])[0]?.[0] : undefined;
  return (
    <tr>
      <td><Link to={`/admin/journals/${s.id}`}>{s.title}</Link></td>
      <td>{s.country ?? '–'}</td>
      <td>{s.origin}</td>
      <td>{s.signalOrigins.map((o) => <span key={o} className={classes.chip}>{o}</span>)}</td>
      <td>{sm ? `${fmt(sm.namesPerArticle)} names/article · acts ${fmt((sm.actShare ?? 0) * 100, 0)}%${top ? ` · ${top}` : ''}` : <span className={classes.muted}>no sample</span>}</td>
      <td>{Object.entries(s.routes).map(([k, n]) => <span key={k} className={classes.chip}>{k}:{n}</span>)}</td>
      <td className={classes.num}>{fmt(s.candidateScore, 2)}</td>
      <td>{flagsOf(s).map((f) => <span key={f.t} className={f.red ? classes.flagRed : classes.flag}>{f.t}</span>)}</td>
    </tr>
  );
}

export function AdminJournalsPage() {
  usePageTitle('Admin: journals');
  const [params, setParams] = useSearchParams();
  const f = filterOf(params);
  const [country, setCountry] = useState(f.country ?? '');
  const set = (k: string, v: string | null) => setParams((old) => {
    const n = new URLSearchParams(old);
    if (v) n.set(k, v); else n.delete(k);
    if (k !== 'offset') n.delete('offset');
    return n;
  }, { replace: true });
  const q = useQuery({ queryKey: ['admin', 'sources', f], queryFn: () => listAdminSources({ ...f, sort: f.tier === 'CANDIDATE' ? 'score' : undefined }) });
  const offset = f.offset ?? 0;
  return (
    <div>
      <h1 className={styles.pageTitle}>Journals</h1>
      <div className={classes.filters}>
        <NativeSelect label="Tier" value={f.tier} onChange={(e) => set('tier', e.target.value)} data={TIERS} />
        <NativeSelect label="Origin" value={f.origin ?? ''} onChange={(e) => set('origin', e.target.value)}
          data={[{ value: '', label: 'Any' }, ...ORIGINS]} />
        <TextInput label="Country" value={country} maxLength={2} w={90}
          onChange={(e) => setCountry(e.target.value.toUpperCase())} onBlur={() => set('country', country)} />
        <NativeSelect label="Status" value={params.get('status') ?? ''} onChange={(e) => set('status', e.target.value)}
          data={[{ value: '', label: 'Any' }, 'ACTIVE', 'CEASED', 'dormant']} />
        <Checkbox label="Registered only" checked={params.get('registered') === '1'} onChange={(e) => set('registered', e.target.checked ? '1' : null)} />
        <Checkbox label="Proposals only" checked={params.get('proposal') === '1'} onChange={(e) => set('proposal', e.target.checked ? '1' : null)} />
      </div>
      <QueryState query={q}>
        {q.data && (
          <>
            <p>{q.data.total} journals</p>
            <div className={classes.tableWrap}>
              <table className={classes.table}>
                <thead><tr><th>Title</th><th>Country</th><th>Origin</th><th>Signals</th><th>Sample</th><th>Routes</th><th className={classes.num}>Score</th><th>Flags</th></tr></thead>
                <tbody>{q.data.items.map((s) => <Row key={s.id} s={s} />)}</tbody>
              </table>
            </div>
            <Group mt="md">
              <Button disabled={offset === 0} onClick={() => set('offset', String(Math.max(0, offset - PAGE)) || null)}>Previous</Button>
              <Button disabled={offset + PAGE >= q.data.total} onClick={() => set('offset', String(offset + PAGE))}>Next</Button>
            </Group>
          </>
        )}
      </QueryState>
    </div>
  );
}
