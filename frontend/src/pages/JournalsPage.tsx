import { Alert, Anchor, Badge, Group, Loader, Pagination, SegmentedControl, Stack, Text, TextInput, Title } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { listSources, type SourceItem } from '../api';
import { usePageTitle } from '../usePageTitle';

const LIMIT = 50;

function Row({ s }: { s: SourceItem }) {
  const facts = [
    s.worksPerYear != null ? `${s.worksPerYear} papers a year` : null,
    s.nameDensity != null ? `${s.nameDensity.toFixed(1)} names per paper` : null,
    s.actRate != null && s.actRate > 0 ? `${Math.round(s.actRate * 100)}% with nomenclatural acts` : null,
  ].filter(Boolean);
  return (
    <li>
      <Group gap="xs" align="baseline">
        <Anchor component={Link} to={`/source/${s.id}`}>{s.title}</Anchor>
        <Badge size="xs" variant="light">{s.tier}</Badge>
        {s.journalType ? <Badge size="xs" variant="outline">{s.journalType}</Badge> : null}
        {s.oaModel ? <Badge size="xs" variant="dot">{s.oaModel}</Badge> : null}
      </Group>
      <Text size="xs" c="dimmed">{[s.publisher, ...facts].filter(Boolean).join(' · ')}</Text>
    </li>
  );
}

export function JournalsPage() {
  usePageTitle('Journals');
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const tier = params.get('tier') ?? '';
  const journalType = params.get('journalType') ?? '';
  const page = Math.max(1, Number(params.get('page')) || 1);
  const [text, setText] = useState(q);
  const [debounced] = useDebouncedValue(text.trim(), 300);

  const update = (changes: Record<string, string>, keepPage = false) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(changes)) (v ? next.set(k, v) : next.delete(k));
    if (!keepPage) next.delete('page');
    setParams(next, { replace: true });
  };
  useEffect(() => {
    if (debounced !== q) update({ q: debounced });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced]);

  const list = useQuery({
    queryKey: ['journals', q, tier, journalType, page],
    queryFn: () => listSources({ q, tier, journalType, limit: LIMIT, offset: (page - 1) * LIMIT }),
    placeholderData: (prev) => prev,
  });
  const pages = list.data ? Math.ceil(list.data.total / LIMIT) : 0;

  return (
    <Stack gap="md">
      <Title order={2}>Journals</Title>
      <Group align="flex-end">
        <TextInput label="Title" placeholder="Search journals" value={text} onChange={(e) => setText(e.currentTarget.value)} />
        <div>
          <Text size="sm" fw={500}>Tier</Text>
          <SegmentedControl aria-label="Tier" value={tier || 'ALL'} onChange={(v) => update({ tier: v === 'ALL' ? '' : v })}
            data={[{ value: 'ALL', label: 'All' }, { value: 'CORE', label: 'CORE' }, { value: 'BROAD', label: 'BROAD' }]} />
        </div>
        <div>
          <Text size="sm" fw={500}>Type</Text>
          <SegmentedControl aria-label="Type" value={journalType || 'ALL'} onChange={(v) => update({ journalType: v === 'ALL' ? '' : v })}
            data={[{ value: 'ALL', label: 'All' }, { value: 'TAXONOMIC', label: 'TAXONOMIC' }, { value: 'GENERAL', label: 'GENERAL' }]} />
        </div>
      </Group>
      {list.isError ? <Alert color="red">{(list.error as Error).message}</Alert>
        : !list.data ? <Loader size="sm" /> : (
          <>
            <Text size="sm" c="dimmed">{list.data.total} {list.data.total === 1 ? 'journal' : 'journals'}</Text>
            {list.data.results.length ? (
              <Stack component="ul" gap="sm" p={0} m={0} pl="md">
                {list.data.results.map((s) => <Row key={s.id} s={s} />)}
              </Stack>
            ) : <Text c="dimmed">No journals match.</Text>}
            {pages > 1 ? <Pagination total={pages} value={page} onChange={(p) => update({ page: p > 1 ? String(p) : '' }, true)} /> : null}
          </>
        )}
    </Stack>
  );
}
