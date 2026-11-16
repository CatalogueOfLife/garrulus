import { Alert, Anchor, Badge, Group, Loader, Stack, Text, Title } from '@mantine/core';
import { useQuery, useQueries } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { getClbClassification, getClbTaxon, getTaxonStats, getWork, getXr, searchWorks } from '../api';
import type { Context } from '../api';
import { WorkList } from '../components/WorkList';
import { YearBars } from '../components/YearBars';
import { FeedLink } from '../components/FeedLink';
import { FollowButton } from '../components/FollowButton';
import { Section } from '../components/Section';
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
  const xr = useQuery({ queryKey: ['xr'], queryFn: getXr, staleTime: Infinity });
  const key = xr.data?.key;
  const taxon = useQuery({ queryKey: ['clb-taxon', key, id], queryFn: () => getClbTaxon(key!, id), enabled: key !== undefined });
  const classification = useQuery({
    queryKey: ['clb-classification', key, id], queryFn: () => getClbClassification(key!, id), enabled: key !== undefined,
  });
  const stats = useQuery({ queryKey: ['taxon-stats', id], queryFn: () => getTaxonStats(id) });
  const recent = useQuery({
    queryKey: ['taxon-recent', id], queryFn: () => searchWorks({ taxon: id, mainSubjectOnly: 'true', limit: '10' }),
  });
  const acts = useQuery({
    queryKey: ['taxon-acts', id],
    queryFn: () => searchWorks({ taxon: id, mainSubjectOnly: 'true', hasActs: 'true', limit: '10' }),
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
  if (taxon.isError) return <Alert color="red">{(taxon.error as Error).message}</Alert>;

  return (
    <Stack gap="lg">
      <div>
        {name ? (
          <Group align="baseline" gap="sm">
            <Title order={2} fs="italic">{name}</Title>
            {taxon.data?.name.rank ? <Badge variant="light">{taxon.data.name.rank}</Badge> : null}
            {taxon.data?.status ? <Text size="sm" c="dimmed">{taxon.data.status}</Text> : null}
            <FollowButton type="TAXON" target={id} label={name} />
            <FeedLink href={`/feeds/taxon/${encodeURIComponent(id)}.atom`} />
          </Group>
        ) : <Loader size="sm" />}
        {classification.data?.length ? (
          <Group gap={6} mt={4}>
            {classification.data.map((c, i) => (
              <Text key={c.id} size="sm" c="dimmed">
                <Anchor component={Link} to={`/taxon/${c.id}`} size="sm">{c.name}</Anchor>
                {i < classification.data.length - 1 ? ' ›' : ''}
              </Text>
            ))}
          </Group>
        ) : null}
        {stats.data ? (
          <Text size="sm" mt={4}>{stats.data.total} papers, {stats.data.mainSubjectTotal} with this taxon as main subject.</Text>
        ) : null}
      </div>

      <Section title="Papers per year (all papers)">
        {stats.isError ? <Alert color="red">{(stats.error as Error).message}</Alert>
          : stats.data ? <YearBars years={stats.data.years} /> : <Loader size="sm" />}
      </Section>

      <Section title="Recent papers (main subject)">
        {recent.isError ? <Alert color="red">{(recent.error as Error).message}</Alert>
          : recent.data ? (recent.data.results.length ? <WorkList works={recent.data.results} /> : <Text c="dimmed">No papers yet.</Text>)
          : <Loader size="sm" />}
        {recent.data && recent.data.total > recent.data.results.length ? (
          <Anchor component={Link} to={`/?taxon=${encodeURIComponent(id)}&mainSubjectOnly=true`} size="sm">All {recent.data.total} papers</Anchor>
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
          <Stack component="ul" gap="xs" p={0} m={0} pl="md">
            {verbatim.map((v) => <li key={v}><Text size="sm">{v}</Text></li>)}
          </Stack>
        ) : <Text c="dimmed">None recorded in the recent papers.</Text>}
      </Section>
    </Stack>
  );
}
