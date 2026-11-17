import { Alert, Anchor, Loader, Pagination, SegmentedControl, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { listSources, type SourceItem } from '../api';
import { EmptyState } from '../ui/EmptyState';
import styles from '../ui/pages.module.css';
import { usePageTitle } from '../usePageTitle';

const LIMIT = 50;

const sentence = (v?: string | null) => (v ? v.charAt(0) + v.slice(1).toLowerCase() : v);

function Row({ s }: { s: SourceItem }) {
  const facts = [
    s.nameDensity != null ? `${s.nameDensity.toFixed(1)} names per paper` : null,
    s.actRate != null && s.actRate > 0 ? `${Math.round(s.actRate * 100)}% with nomenclatural acts` : null,
  ];
  return (
    <li>
      <div className={styles.rowLine}>
        <Anchor component={Link} to={`/source/${s.id}`} className={styles.rowTitle}>{s.title}</Anchor>
        {s.worksPerYear != null ? <span className={styles.rowCount}>{s.worksPerYear} papers a year</span> : null}
      </div>
      <p className={styles.rowMeta}>{[sentence(s.tier), sentence(s.journalType), sentence(s.oaModel), s.publisher, ...facts].filter(Boolean).join(' · ')}</p>
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
    <div>
      <h1 className={styles.pageTitle}>Journals</h1>
      <div className={styles.filters}>
        <TextInput label="Title" placeholder="Search journals" value={text} onChange={(e) => setText(e.currentTarget.value)} />
        <div>
          <Text size="sm" fw={500}>Tier</Text>
          <SegmentedControl aria-label="Tier" value={tier || 'ALL'} onChange={(v) => update({ tier: v === 'ALL' ? '' : v })}
            data={[{ value: 'ALL', label: 'All' }, { value: 'CORE', label: 'Core' }, { value: 'BROAD', label: 'Broad' }]} />
        </div>
        <div>
          <Text size="sm" fw={500}>Type</Text>
          <SegmentedControl aria-label="Type" value={journalType || 'ALL'} onChange={(v) => update({ journalType: v === 'ALL' ? '' : v })}
            data={[{ value: 'ALL', label: 'All' }, { value: 'TAXONOMIC', label: 'Taxonomic' }, { value: 'GENERAL', label: 'General' }]} />
        </div>
      </div>
      {list.isError ? <Alert color="red">{(list.error as Error).message}</Alert>
        : !list.data ? <Loader size="sm" /> : (
          <>
            <p className={styles.count}>{list.data.total} {list.data.total === 1 ? 'journal' : 'journals'}</p>
            {list.data.results.length ? (
              <ul className={styles.ruled}>
                {list.data.results.map((s) => <Row key={s.id} s={s} />)}
              </ul>
            ) : <EmptyState title="No journals match.">Change or clear the filters.</EmptyState>}
            {pages > 1 ? <Pagination mt="lg" total={pages} value={page} onChange={(p) => update({ page: p > 1 ? String(p) : '' }, true)} /> : null}
          </>
        )}
    </div>
  );
}
