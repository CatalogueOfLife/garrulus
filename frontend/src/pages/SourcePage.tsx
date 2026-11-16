import { Alert, Anchor, Badge, Group, Loader, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError, getSource, getTaxGroups } from '../api';
import { FeedLink } from '../components/FeedLink';
import { FollowButton } from '../components/FollowButton';
import { Section } from '../components/Section';
import { TaxGroupIcon } from '../components/TaxGroupIcon';
import { WorkList } from '../components/WorkList';
import { usePageTitle } from '../usePageTitle';

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Group gap="sm" align="baseline" wrap="nowrap">
      <Text size="sm" c="dimmed" w={130} style={{ flex: '0 0 auto' }}>{label}</Text>
      <Group gap={6}>{children}</Group>
    </Group>
  );
}

const pct = (share: number) => `${Math.round(share * 100)}%`;

export function SourcePage() {
  const { id = '' } = useParams();
  const source = useQuery({ queryKey: ['source', id], queryFn: () => getSource(id) });
  const groups = useQuery({ queryKey: ['taxgroups'], queryFn: getTaxGroups, staleTime: Infinity });
  const s = source.data;
  usePageTitle(s?.title ?? `Journal ${id}`);

  if (source.isError) {
    const notFound = source.error instanceof ApiError && source.error.status === 404;
    return <Alert color={notFound ? 'gray' : 'red'}>{notFound ? 'This journal was not found.' : (source.error as Error).message}</Alert>;
  }
  if (!s) return <Loader size="sm" />;

  const icon = (name: string) => groups.data?.find((g) => g.name === name)?.icon
    ?? `https://api.checklistbank.org/vocab/taxgroup/${encodeURIComponent(name)}/icon.svg`;
  const shares = Object.entries(s.taxgroupShares ?? {}).sort((a, b) => b[1] - a[1]);
  const shown = shares.length ? shares.map(([g, v]) => ({ name: g, share: v as number | null }))
    : s.taxgroups.map((g) => ({ name: g, share: null }));

  return (
    <Stack gap="lg">
      <div>
        <Group align="baseline" gap="sm">
          <Title order={2}>{s.title}</Title>
          <Badge variant="light">{s.tier}</Badge>
          <FollowButton type="SOURCE" target={String(s.id)} label={s.title} />
          <FeedLink href={`/feeds/source/${s.id}.atom`} />
        </Group>
        {s.publisher || s.issnL ? <Text size="sm" c="dimmed">{[s.publisher, s.issnL && `ISSN-L ${s.issnL}`].filter(Boolean).join(' · ')}</Text> : null}
      </div>

      <Section title="Taxonomic groups">
        {shown.length ? (
          <Stack gap={6} component="ul" p={0} m={0} style={{ listStyle: 'none' }}>
            {shown.map((g) => (
              <li key={g.name}>
                <Group gap="xs">
                  <TaxGroupIcon group={{ name: g.name, icon: icon(g.name) }} />
                  <Text size="sm">{g.name}</Text>
                  {g.share !== null ? <Text size="sm" c="dimmed">{pct(g.share)}</Text> : null}
                </Group>
              </li>
            ))}
          </Stack>
        ) : <Text c="dimmed">No taxonomic groups recorded.</Text>}
      </Section>

      <Section title="Categorisation">
        <Stack gap={6}>
          {s.codes.length ? <Row label="Nomenclatural codes">{s.codes.map((c) => <Badge key={c} variant="outline">{c}</Badge>)}</Row> : null}
          {s.journalType ? <Row label="Journal type"><Badge variant="outline">{s.journalType}</Badge></Row> : null}
          {s.subfields.length ? (
            <Row label="Subfields">
              {s.subfields.map((f) => <Badge key={f} variant={f === s.primarySubfield ? 'filled' : 'light'} tt="none">{f}</Badge>)}
            </Row>
          ) : null}
          {s.oaModel ? <Row label="Open access"><Badge variant="outline">{s.oaModel}</Badge></Row> : null}
          {s.country ? <Row label="Country"><Badge variant="outline">{s.country}</Badge></Row> : null}
          {s.worksPerYear != null ? <Row label="Papers per year"><Text size="sm">{Math.round(s.worksPerYear)}</Text></Row> : null}
          {s.nameDensity != null ? <Row label="Names per paper"><Text size="sm">{s.nameDensity}</Text></Row> : null}
          {s.actRate != null ? <Row label="Papers with acts"><Text size="sm">{pct(s.actRate)}</Text></Row> : null}
        </Stack>
      </Section>

      <Section title="Recent papers">
        {s.recentWorks.length ? <WorkList works={s.recentWorks} /> : <Text c="dimmed">No papers yet.</Text>}
        <Anchor component={Link} to={`/?source=${encodeURIComponent(id)}`} size="sm">All papers of this journal</Anchor>
      </Section>
    </Stack>
  );
}
