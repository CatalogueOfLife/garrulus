import { Alert, Anchor, Badge, Group, Loader, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { ApiError, getName, searchWorks } from '../api';
import { FeedLink } from '../components/FeedLink';
import { FollowButton } from '../components/FollowButton';
import { Section } from '../components/Section';
import { ClassificationPath } from '../components/ClassificationPath';
import { WorkList } from '../components/WorkList';
import { YearBars } from '../components/YearBars';
import { usePageTitle } from '../usePageTitle';

export function NamePage() {
  const { id = '' } = useParams();
  const name = useQuery({ queryKey: ['name', id], queryFn: () => getName(id) });
  const works = useQuery({ queryKey: ['name-works', id], queryFn: () => searchWorks({ name: id, limit: '20' }) });
  const n = name.data;
  usePageTitle(n?.name ?? `Name ${id}`);

  if (name.isError) {
    const notFound = name.error instanceof ApiError && name.error.status === 404;
    return <Alert color={notFound ? 'gray' : 'red'}>{notFound ? 'This name was not found.' : (name.error as Error).message}</Alert>;
  }
  if (!n) return <Loader size="sm" />;
  const m = n.match;
  const synonym = m?.acceptedId && m.acceptedId !== m.usageId;
  const first = n.firstAppearance;

  return (
    <Stack gap="lg">
      <Group align="baseline" gap="sm">
        <Title order={2} fs="italic">{n.name}</Title>
        {n.rank ? <Badge variant="light">{n.rank}</Badge> : null}
        <Text size="sm" c="dimmed">{n.kind}</Text>
        <FollowButton type="NAME" target={String(n.id)} label={n.name} />
        <FeedLink href={`/feeds/name/${n.id}.atom`} />
      </Group>
      <Text size="sm" c="dimmed" mt={-12}>
        A name as written in papers.{' '}
        <Anchor component={Link} to="/about#names" size="sm">What is this?</Anchor>
      </Text>

      <Section title="Match in COL">
        {m?.usageId ? (
          <div>
            <Text>
              <Anchor component={Link} to={`/taxon/${m.usageId}`} fs="italic">{m.usageName || m.usageId}</Anchor>
              {m.rank ? <Text span c="dimmed"> ({m.rank})</Text> : null}
              {m.status ? <Text span c="dimmed"> · {m.status}</Text> : null}
            </Text>
            {synonym ? (
              <Text size="sm">
                <Anchor component={Link} to={`/taxon/${m.acceptedId}`}>Go to the accepted taxon</Anchor>
              </Text>
            ) : null}
            <ClassificationPath items={m.classification} />
          </div>
        ) : <Text c="dimmed">This name is not matched to the Catalogue of Life.</Text>}
      </Section>

      <Section title="Papers per year">
        <YearBars years={n.timeline} />
      </Section>

      <Section title="First appearance">
        {first ? (
          <Text>
            <Anchor component={Link} to={`/work/${first.workId}`}>{first.title || `Work ${first.workId}`}</Anchor>
            {first.publicationDate ? <Text span c="dimmed"> · {first.publicationDate}</Text> : null}
          </Text>
        ) : <Text c="dimmed">Not seen in any paper yet.</Text>}
      </Section>

      <Section title="Papers naming this name">
        {works.isError ? <Alert color="red">{(works.error as Error).message}</Alert>
          : works.data ? (works.data.results.length ? <WorkList works={works.data.results} /> : <Text c="dimmed">No papers yet.</Text>)
          : <Loader size="sm" />}
        {works.data && works.data.total > works.data.results.length ? (
          <Anchor component={Link} to={`/?name=${encodeURIComponent(id)}`} size="sm">All {works.data.total} papers</Anchor>
        ) : null}
      </Section>
    </Stack>
  );
}
