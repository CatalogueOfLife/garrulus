import { Alert, Anchor, Loader, Text } from '@mantine/core';
import { useQuery, useQueries } from '@tanstack/react-query';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { getClbClassification, getClbTaxon, getTaxonStats, getWork, getXr, searchWorks } from '../api';
import type { Context } from '../api';
import { ClassificationPath } from '../components/ClassificationPath';
import { LanguageBreakdown } from '../components/LanguageBreakdown';
import { MentionedInFilter, withMentionedIn } from '../components/MentionedInFilter';
import { WorkList } from '../components/WorkList';
import { YearBars } from '../components/YearBars';
import { FollowActions } from '../components/FollowActions';
import { Section } from '../components/Section';
import { DetailHead } from '../ui/DetailHead';
import { NotFound, isNotFound } from '../ui/NotFound';
import { SciName } from '../ui/SciName';
import { SideColumn } from '../ui/SideColumn';
import { words } from '../ui/sentence';
import { StatRows } from '../ui/StatRows';
import list from '../ui/list.module.css';
import classes from '../ui/detail.module.css';
import { usePageTitle } from '../usePageTitle';

/**
 * The verbatim strings of the contexts that classify this taxon: one of their ranked names is the taxon's own name or
 * the name of one of its ancestors (case-insensitive). De-duplicated, in order of first appearance.
 */
export function relevantVerbatim(contexts: Context[], lineageNames: (string | undefined)[]): string[] {
  const lineage = new Set(lineageNames.filter((n): n is string => !!n).map((n) => n.trim().toLowerCase()));
  const kept = contexts.filter((c) => (Array.isArray(c.ranks) ? c.ranks : [])
    .some((r) => lineage.has(r.name.trim().toLowerCase())));
  return [...new Set(kept.map((c) => c.verbatim))];
}

export function TaxonPage() {
  const { id = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const mentionedIn = params.getAll('mentionedIn').filter(Boolean);
  const setMentioned = (v: string[]) => setParams((p) => { const n = new URLSearchParams(p); n.delete('mentionedIn'); v.forEach((x) => n.append('mentionedIn', x)); return n; });
  const xr = useQuery({ queryKey: ['xr'], queryFn: getXr, staleTime: Infinity });
  const key = xr.data?.key;
  const taxon = useQuery({ queryKey: ['clb-taxon', key, id], queryFn: () => getClbTaxon(key!, id), enabled: key !== undefined });
  const classification = useQuery({
    queryKey: ['clb-classification', key, id], queryFn: () => getClbClassification(key!, id), enabled: key !== undefined,
  });
  const stats = useQuery({ queryKey: ['taxon-stats', id], queryFn: () => getTaxonStats(id) });
  const recent = useQuery({
    queryKey: ['taxon-recent', id, mentionedIn], queryFn: () => searchWorks({ taxon: id, mainSubjectOnly: 'true', limit: '10', mentionedIn }),
  });
  const acts = useQuery({
    queryKey: ['taxon-acts', id, mentionedIn],
    queryFn: () => searchWorks({ taxon: id, mainSubjectOnly: 'true', hasActs: 'true', limit: '10', mentionedIn }),
  });
  // verbatim classifications used: those contexts of up to five recent works that name this taxon or an ancestor
  const recentWorks = (recent.data?.results ?? []).slice(0, 5);
  const details = useQueries({
    queries: recentWorks.map((w) => ({ queryKey: ['work', String(w.id)], queryFn: () => getWork(w.id) })),
  });
  const name = taxon.data?.name.scientificName;
  const verbatim = relevantVerbatim(details.flatMap((d) => d.data?.contexts ?? []),
    [name, ...(classification.data ?? []).map((c) => c.name)]);

  usePageTitle(name ?? `Taxon ${id}`);
  if (taxon.isError) {
    return isNotFound(taxon.error)
      ? <NotFound title="Taxon not found">This taxon is not in the current Catalogue of Life release.</NotFound>
      : <Alert color="red">{(taxon.error as Error).message}</Alert>;
  }

  const rank = taxon.data?.name.rank;
  const total = recent.data?.total;
  return (
    <article>
      {name ? (
        <DetailHead
          title={<SciName name={name} rank={rank} />}
          parts={[words(rank), words(taxon.data?.status)]}
          actions={<FollowActions type="TAXON" target={id} label={name}
            feed={withMentionedIn(`/feeds/taxon/${encodeURIComponent(id)}.atom`, mentionedIn)} />}
        />
      ) : <Loader size="sm" />}
      {classification.data?.length ? <ClassificationPath items={classification.data} order="root-first" /> : null}

      <div className={`${list.page} ${classes.body}`}>
        <div className={list.mainCol}>
          <Section title="Filter">
            <MentionedInFilter value={mentionedIn} onChange={setMentioned} />
          </Section>

          <Section title="Recent papers (main subject)">
            {recent.isError ? <Alert color="red">{(recent.error as Error).message}</Alert>
              : recent.data ? (recent.data.results.length ? <WorkList works={recent.data.results} /> : <Text c="dimmed">No papers yet.</Text>)
              : <Loader size="sm" />}
            {total !== undefined && recent.data && total > recent.data.results.length ? (
              <p className={classes.more}>
                <Link to={`/?taxon=${encodeURIComponent(id)}&mainSubjectOnly=true${mentionedIn.map((m) => `&mentionedIn=${m}`).join('')}`}>All {total} papers</Link>
              </p>
            ) : null}
          </Section>

          <Section title="New names (main subject)">
            {acts.isError ? <Alert color="red">{(acts.error as Error).message}</Alert>
              : acts.data ? (acts.data.results.length ? <WorkList works={acts.data.results} /> : <Text c="dimmed">No nomenclatural acts found.</Text>)
              : <Loader size="sm" />}
          </Section>

          <Section title="Verbatim classifications used">
            {details.some((d) => d.isLoading) || recent.isLoading || taxon.isLoading || classification.isLoading
              ? <Loader size="sm" /> : verbatim.length ? (
              <ul className={classes.plain}>
                {verbatim.map((v) => <li key={v}>{v}</li>)}
              </ul>
            ) : <Text c="dimmed">None recorded in the recent papers.</Text>}
          </Section>
        </div>

        <SideColumn lede={<>
          A taxon in the Catalogue of Life, with everything below it.{' '}
          <Anchor component={Link} to="/about#taxa" inherit>What is this?</Anchor>
        </>}>
          {stats.data ? (
            <Section title="Papers">
              <StatRows rows={[
                { label: 'All papers', value: stats.data.total },
                { label: 'Main subject', value: stats.data.mainSubjectTotal },
              ]} />
            </Section>
          ) : null}
          <Section title="Papers per year (all papers)">
            {stats.isError ? <Alert color="red">{(stats.error as Error).message}</Alert>
              : stats.data ? <YearBars years={stats.data.years} /> : <Loader size="sm" />}
          </Section>
          {stats.data?.languages?.length ? (
            <Section title="Languages">
              <LanguageBreakdown languages={stats.data.languages} />
            </Section>
          ) : null}
        </SideColumn>
      </div>
    </article>
  );
}
