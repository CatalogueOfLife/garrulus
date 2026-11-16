import { Alert, Anchor, Badge, Button, Group, Loader, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { getHits } from '../api';
import { useMe } from '../auth/useMe';
import { WorkList } from '../components/WorkList';
import { usePageTitle } from '../usePageTitle';

const PAGE_SIZE = 20;

export function HitsPage() {
  usePageTitle('My hits');
  const me = useMe();
  const [params, setParams] = useSearchParams();
  const subscription = params.get('subscription') ?? undefined;
  const offset = Math.max(0, Number(params.get('offset')) || 0);
  const hits = useQuery({
    queryKey: ['hits', subscription ?? null, offset],
    queryFn: () => getHits({ subscription, limit: PAGE_SIZE, offset }),
    enabled: !!me.data,
  });

  if (me.isPending) return <Loader size="sm" />;
  if (!me.data) {
    return <Text>Please <Anchor component={Link} to={`/signin?next=${encodeURIComponent('/hits' + (params.size ? `?${params}` : ''))}`}>sign in</Anchor> to see your hits.</Text>;
  }
  const go = (o: number) => {
    const p = new URLSearchParams(params);
    if (o > 0) p.set('offset', String(o)); else p.delete('offset');
    setParams(p);
  };
  const results = hits.data?.results ?? [];

  return (
    <Stack gap="md">
      <Group justify="space-between" align="baseline">
        <Title order={2}>My hits</Title>
        {subscription ? <Anchor component={Link} to="/hits" size="sm">Show all hits</Anchor> : null}
      </Group>
      {hits.isError ? <Alert color="red">{(hits.error as Error).message}</Alert>
        : !hits.data ? <Loader size="sm" />
        : results.length === 0 ? <Text c="dimmed">{offset ? 'No more hits.' : 'No hits yet. New papers about what you follow show up here.'}</Text>
        : (
          <Stack component="ul" gap="lg" p={0} m={0} style={{ listStyle: 'none' }}>
            {results.map((h) => (
              <li key={h.id}>
                <Group gap="xs" mb={4}>
                  {h.kind === 'NEW_NAMES' ? <Badge color="jay" size="sm">New names</Badge> : null}
                  <Text size="xs" c="dimmed">{h.created.slice(0, 10)}</Text>
                  {h.subscriptionLabel ? (
                    <Anchor component={Link} to={`/hits?subscription=${h.subscriptionId}`} size="xs">{h.subscriptionLabel}</Anchor>
                  ) : null}
                </Group>
                {h.work ? <WorkList works={[h.work]} /> : <Text size="sm" c="dimmed">This paper is no longer listed.</Text>}
              </li>
            ))}
          </Stack>
        )}
      <Group>
        {offset > 0 ? <Button variant="default" size="xs" onClick={() => go(Math.max(0, offset - PAGE_SIZE))}>Newer</Button> : null}
        {results.length === PAGE_SIZE ? <Button variant="default" size="xs" onClick={() => go(offset + PAGE_SIZE)}>Older</Button> : null}
      </Group>
    </Stack>
  );
}
