import { Alert, Box, Group, Pagination, Skeleton, Stack, Text, TextInput } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { ApiError, filterFromParams, filterToParams, getTaxGroups, searchWorks, type FacetName, type WorkFilter } from '../api';
import { FilterSidebar } from '../components/FilterSidebar';
import { WorkList } from '../components/WorkList';
import { usePageTitle } from '../usePageTitle';

const FACETS: FacetName[] = ['code', 'taxgroup', 'journalType', 'hasActs'];
const PAGE_SIZE = 20;

export function SearchPage() {
  usePageTitle('Search');
  const [params, setParams] = useSearchParams();
  const filter = filterFromParams(params);
  const taxonLabel = params.get('taxonLabel') ?? undefined;
  const apiFilter: WorkFilter = { ...filter, limit: String(PAGE_SIZE) };

  const works = useQuery({
    queryKey: ['works', apiFilter],
    queryFn: () => searchWorks(apiFilter, FACETS),
    placeholderData: (prev) => prev,
  });
  const groups = useQuery({ queryKey: ['taxgroups'], queryFn: getTaxGroups, staleTime: Infinity });

  const update = (patch: WorkFilter, extra?: { taxonLabel?: string | null }) => {
    const next = filterToParams({ ...filter, ...patch });
    next.delete('limit');
    const label = extra && 'taxonLabel' in extra ? extra.taxonLabel : taxonLabel;
    if (label && next.get('taxon')) next.set('taxonLabel', label);
    setParams(next);
  };

  const offset = Number(filter.offset ?? 0);
  const page = Math.floor(offset / PAGE_SIZE) + 1;
  const data = works.data;

  return (
    <Group align="flex-start" wrap="nowrap" gap="xl">
      <Box w={280} style={{ flex: '0 0 280px' }}>
        <FilterSidebar
          filter={filter}
          taxonLabel={taxonLabel}
          facets={data?.facets ?? {}}
          taxGroups={groups.data ?? []}
          onChange={update}
        />
      </Box>
      <Stack style={{ flex: 1, minWidth: 0 }}>
        <TextInput
          aria-label="Search text"
          placeholder="Search titles and abstracts…"
          key={filter.q ?? ''}
          defaultValue={filter.q ?? ''}
          onKeyDown={(e) => {
            if (e.key === 'Enter') update({ q: e.currentTarget.value || undefined, offset: undefined });
          }}
        />
        {works.isError ? (
          <Alert color="red" title="Search failed">
            {works.error instanceof ApiError ? works.error.message : 'The search is unavailable. Try again later.'}
          </Alert>
        ) : !data ? (
          <Skeleton height={120} />
        ) : data.results.length === 0 ? (
          <Text c="dimmed">No works match these filters. Remove a filter or widen the year range to see more.</Text>
        ) : (
          <>
            <Text size="sm" c="dimmed">{data.total.toLocaleString('en')} works</Text>
            <WorkList works={data.results} />
            {data.total > PAGE_SIZE ? (
              <Pagination
                total={Math.ceil(Math.min(data.total, 10020) / PAGE_SIZE)}
                value={page}
                onChange={(p) => update({ offset: p > 1 ? String((p - 1) * PAGE_SIZE) : undefined })}
              />
            ) : null}
          </>
        )}
      </Stack>
    </Group>
  );
}
