import { useState, type ReactNode } from 'react';
import { Alert, Button, Checkbox, NativeSelect, Textarea, TextInput } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import {
  addAdminRoute, getAdminSource, listSources, patchAdminSource, sampleAdminSource, setAdminRouteStatus,
  type AdminPatch, type AdminSample, type AdminSourceDetail,
} from '../../api';
import { ConfirmButton } from '../../components/ConfirmButton';
import { useDisciplines } from '../../disciplines';
import styles from '../../ui/pages.module.css';
import { NotFound, retryUnlessNotFound } from '../../ui/NotFound';
import { usePageTitle } from '../../usePageTitle';
import classes from './admin.module.css';
import { QueryState, SafeUrl, errMessage } from './adminUi';
import { flagsOf } from './AdminJournalsPage';

const when = (s?: string | null) => (s ? s.slice(0, 10) : '–');
/** The reasons the server accepts (SourceStatusService), in words. */
const CEASED_REASONS = [
  { value: 'STOPPED', label: 'Stopped publishing' },
  { value: 'TITLE_CHANGE', label: 'Continued under a new title' },
  { value: 'MERGED', label: 'Merged into another journal' },
  { value: 'UNKNOWN', label: 'Unknown' },
];
const reasonLabel = (r: string) => CEASED_REASONS.find((x) => x.value === r)?.label ?? r;
/** The server's bounds for the last issue year. */
const MIN_YEAR = 1800;
const Section = ({ title, children }: { title: string; children: ReactNode }) => (
  <section className={classes.section}><h2>{title}</h2>{children}</section>
);

/** One detail value as text: scalars as they are, objects as compact JSON. */
const show = (v: unknown) => (typeof v === 'string' ? v : JSON.stringify(v));
const KeyValues = ({ o }: { o: Record<string, unknown> }) => (
  <ul>{Object.entries(o).map(([k, v]) => <li key={k}>{k}: {k === 'url' || (typeof v === 'string' && /^https?:\/\//.test(v)) ? <SafeUrl value={v} /> : show(v)}</li>)}</ul>
);

function SampleLine({ s }: { s: AdminSample }) {
  const groups = Object.entries(s.taxgroups ?? {}).sort((a, b) => b[1] - a[1]).slice(0, 3).map(([g, n]) => `${g} ${n}`).join(', ');
  return <li>{when(s.sampledAt)} ({s.route ?? 'no route'}): {s.articles} articles, {s.withAbstract} with abstract, {s.namesPerArticle?.toFixed(1) ?? '–'} names per article, acts {Math.round((s.actShare ?? 0) * 100)}%{groups ? `, ${groups}` : ''}</li>;
}

function RoutesSection({ d }: { d: AdminSourceDetail }) {
  const id = d.source.id;
  const client = useQueryClient();
  const refresh = () => client.invalidateQueries({ queryKey: ['admin', 'source', String(id)] });
  const [layer, setLayer] = useState('OAI');
  const [config, setConfig] = useState('{}');
  const [problem, setProblem] = useState<string | null>(null);
  const add = useMutation({ mutationFn: (cfg: unknown) => addAdminRoute(id, layer, cfg), onSuccess: () => { setProblem(null); refresh(); }, onError: (e) => setProblem(errMessage(e)) });
  const setStatus = useMutation({ mutationFn: (v: { r: number; s: 'PAUSED' | 'ACTIVE' }) => setAdminRouteStatus(id, v.r, v.s), onSuccess: refresh, onError: (e) => setProblem(errMessage(e)) });
  const submit = () => {
    let parsed: unknown;
    try { parsed = JSON.parse(config); } catch { setProblem('The config is not valid JSON.'); return; }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) { setProblem('The config must be a JSON object.'); return; }
    add.mutate(parsed);
  };
  return (
    <Section title="Routes">
      {d.routes.length === 0 ? <p className={classes.muted}>No routes.</p> : (
        <div className={classes.tableWrap}><table className={classes.table}>
          <thead><tr><th>Layer</th><th>Status</th><th>Origin</th><th>Config</th><th>Last ok</th><th>Last error</th><th /></tr></thead>
          <tbody>{d.routes.map((r) => (
            <tr key={r.id}>
              <td>{r.layer}</td><td>{r.status}</td><td>{r.origin}</td>
              <td><KeyValues o={r.config} /></td><td>{when(r.lastOk)}</td><td>{r.lastError ?? ''}</td>
              <td>
                {r.status !== 'PAUSED' && <Button size="xs" onClick={() => setStatus.mutate({ r: r.id, s: 'PAUSED' })}>Pause</Button>}
                {r.status !== 'ACTIVE' && <Button size="xs" onClick={() => setStatus.mutate({ r: r.id, s: 'ACTIVE' })}>Activate</Button>}
              </td>
            </tr>))}</tbody>
        </table></div>
      )}
      <div className={classes.actions} style={{ alignItems: 'flex-end' }}>
        <NativeSelect label="Layer" value={layer} onChange={(e) => setLayer(e.target.value)} data={['OAI', 'MAGTECH', 'EUROPEPMC']} />
        <Textarea label="Config (JSON)" value={config} onChange={(e) => setConfig(e.target.value)} autosize minRows={1} w={360} />
        <Button loading={add.isPending} onClick={submit}>Add route</Button>
      </div>
      {problem && <Alert color="red">{problem}</Alert>}
    </Section>
  );
}

function DisciplinesSection({ d, patch }: { d: AdminSourceDetail; patch: (p: AdminPatch) => void }) {
  const vocab = useDisciplines();
  const [chosen, setChosen] = useState<string[]>(d.disciplines);
  const options = (vocab.data ?? []).filter((x) => !x.replacedBy).sort((a, b) => a.sortOrder - b.sortOrder);
  return (
    <Section title="Disciplines">
      <p>Set by {d.disciplineSetBy}{d.disciplineSource ? ` (${d.disciplineSource})` : ''}.</p>
      <Checkbox.Group value={chosen} onChange={setChosen} aria-label="Disciplines">
        {options.map((o) => <Checkbox key={o.code} value={o.code} label={o.label} mb={4} />)}
      </Checkbox.Group>
      <div className={classes.actions}>
        <Button onClick={() => patch({ disciplines: chosen })}>Save override</Button>
        <Button variant="outline" onClick={() => patch({ releaseDisciplines: true })}>Release to derived</Button>
      </div>
    </Section>
  );
}

function StatusSection({ d, patch, busy }: { d: AdminSourceDetail; patch: (p: AdminPatch) => void; busy: boolean }) {
  const s = d.source;
  const [reason, setReason] = useState('');
  const [year, setYear] = useState('');
  const [query, setQuery] = useState('');
  const [successor, setSuccessor] = useState<number | null>(null);
  const found = useQuery({ queryKey: ['admin', 'successor', query], enabled: query.trim().length >= 3,
    queryFn: () => listSources({ q: query.trim(), limit: 8 }) });
  const yearNum = year.trim() === '' ? undefined : Number(year);
  const yearBad = yearNum !== undefined && (!Number.isInteger(yearNum) || yearNum < MIN_YEAR || yearNum > new Date().getUTCFullYear());
  const ceased = s.publicationStatus === 'CEASED';
  return (
    <Section title="Publication status">
      <p>{ceased ? `Ceased${d.lastIssueYear ? `, last issue ${d.lastIssueYear}` : ''}${d.ceasedReason ? `: ${reasonLabel(d.ceasedReason).toLowerCase()}` : ''}` : 'Active'}.
        {d.continuedBy && <> Continued as <Link to={`/admin/journals/${d.continuedBy.id}`}>{d.continuedBy.title}</Link>.</>}</p>
      {s.dormant && (
        <div>
          <p>Marked dormant since {when(d.dormantSince)}.</p>
          {d.dormantEvidence && <KeyValues o={d.dormantEvidence} />}
          <Button variant="outline" onClick={() => patch({ dismissDormant: true })}>Dismiss dormant</Button>
        </div>
      )}
      {s.revival && <p>Signs of life since it ceased: check the signals, then reactivate if they hold.</p>}
      {ceased ? (
        <Button loading={busy} onClick={() => patch({ publicationStatus: 'ACTIVE' })}>Reactivate</Button>
      ) : (
        <div>
          <NativeSelect label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} required w={280}
            data={[{ value: '', label: 'Choose a reason' }, ...CEASED_REASONS]} />
          <TextInput label="Last issue year" value={year} onChange={(e) => setYear(e.target.value)} inputMode="numeric"
            error={yearBad ? `A year from ${MIN_YEAR} to this year` : undefined} w={220} />
          <TextInput label="Successor (search by title)" value={query} onChange={(e) => { setQuery(e.target.value); setSuccessor(null); }} />
          {found.data?.results.map((r) => (
            <label key={r.id} style={{ display: 'block' }}>
              <input type="radio" name="successor" checked={successor === r.id} onChange={() => setSuccessor(r.id)} /> {r.title}
            </label>
          ))}
          <div className={classes.actions}>
            <ConfirmButton question="Mark this journal as ceased?" confirmLabel="Yes, cease" color="red" loading={busy}
              disabled={reason === '' || yearBad}
              onConfirm={() => patch({ publicationStatus: 'CEASED', ceasedReason: reason, lastIssueYear: yearNum, continuedBy: successor ?? undefined })}>
              Cease
            </ConfirmButton>
          </div>
        </div>
      )}
    </Section>
  );
}

export function AdminJournalPage() {
  const { id = '' } = useParams();
  const client = useQueryClient();
  const key = ['admin', 'source', id];
  const q = useQuery({ queryKey: key, queryFn: () => getAdminSource(id), retry: retryUnlessNotFound });
  const [problem, setProblem] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const patchM = useMutation({
    mutationFn: (p: AdminPatch) => patchAdminSource(Number(id), p),
    onSuccess: () => { setProblem(null); client.invalidateQueries({ queryKey: key }); client.invalidateQueries({ queryKey: ['admin', 'sources'] }); },
    onError: (e) => setProblem(errMessage(e)),
  });
  const sampleM = useMutation({ mutationFn: () => sampleAdminSource(Number(id)), onSuccess: () => { setProblem(null); setNote('A new sample is queued.'); }, onError: (e) => setProblem(errMessage(e)) });
  usePageTitle(q.data?.source.title ? `Admin: ${q.data.source.title}` : 'Admin: journal');
  if (q.isError && (q.error as { status?: number }).status === 404) return <NotFound title="No such journal">There is no journal with this id.</NotFound>;
  const d = q.data;
  const s = d?.source;
  const patch = (p: AdminPatch) => patchM.mutate(p);
  return (
    <QueryState query={q}>
      {d && s && (
        <div>
          <p><Link to="/admin/journals">All journals</Link></p>
          <h1 className={styles.pageTitle}>{s.title}</h1>
          <p>
            {[s.issns.join(', '), s.publisher, s.country].filter(Boolean).join(' · ')}<br />
            Tier {s.tier}{s.active ? '' : ' (inactive)'} · status {s.publicationStatus} · origin {s.origin}
            {s.candidateScore != null && ` · score ${s.candidateScore.toFixed(2)}`}
          </p>
          <p>{flagsOf(s).map((f) => <span key={f.t} className={f.red ? classes.flagRed : classes.flag}>{f.t}</span>)}</p>
          {s.proposal && <p>Proposal: {s.proposal.toLowerCase()}. Nothing is applied until you act below.</p>}
          <div className={classes.actions}>
            <Button loading={patchM.isPending} onClick={() => patch({ tier: 'CORE', active: true })}>Accept as CORE</Button>
            <Button loading={patchM.isPending} onClick={() => patch({ tier: 'BROAD', active: true })}>Accept as BROAD</Button>
            <ConfirmButton question="Exclude this journal?" confirmLabel="Yes, decline" color="red" onConfirm={() => patch({ tier: 'EXCLUDED' })}>Decline</ConfirmButton>
            <Button variant="outline" loading={sampleM.isPending} onClick={() => sampleM.mutate()}>Sample now</Button>
          </div>
          {problem && <Alert color="red">{problem}</Alert>}
          {note && <Alert>{note}</Alert>}

          <Section title="Signals">
            {d.signals.length === 0 ? <p className={classes.muted}>None.</p> : (
              <ul>{d.signals.map((g, i) => <li key={i}>{g.origin}, first seen {when(g.firstSeen)}, last seen {when(g.lastSeen)}<KeyValues o={g.detail} /></li>)}</ul>
            )}
            {d.registrations.length > 0 && (
              <>
                <h3>Registrations</h3>
                <ul>{d.registrations.map((r) => <li key={r.id}>{r.kind} {r.input} ({r.state}){r.userDisplayName ? `, by ${r.userDisplayName}` : ''}{r.note ? `: ${r.note}` : ''}</li>)}</ul>
              </>
            )}
          </Section>
          <Section title="Samples">
            {d.samples.length === 0 ? <p className={classes.muted}>No sample yet.</p> : <ul>{d.samples.map((x, i) => <SampleLine key={i} s={x} />)}</ul>}
          </Section>
          <RoutesSection d={d} />
          <DisciplinesSection key={d.disciplines.join()} d={d} patch={patch} />
          <StatusSection key={s.publicationStatus} d={d} patch={patch} busy={patchM.isPending} />
        </div>
      )}
    </QueryState>
  );
}
