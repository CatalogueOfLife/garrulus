import { useEffect, useState } from 'react';
import { Alert, Button, Pagination, Skeleton, TextInput } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { ApiError, filterFromParams, filterToParams, getTaxGroups, listSources, searchWorks, type FacetName, type WorkFilter } from '../api';
import { FollowActions } from '../components/FollowActions';
import { FilterSidebar, activeFilterCount } from '../components/FilterSidebar';
import { WorkList } from '../components/WorkList';
import { HeadRow } from '../ui/HeadRow';
import { PageHeader } from '../ui/PageHeader';
import { SideColumn } from '../ui/SideColumn';
import frame from '../ui/frame.module.css';
import classes from '../ui/list.module.css';
import { usePageTitle } from '../usePageTitle';

const FACETS: FacetName[] = ['code', 'taxgroup', 'journalType', 'hasActs'];
const PAGE_SIZE = 20;

export function SearchPage() {
  usePageTitle('Search');
  const [params, setParams] = useSearchParams();
  const [filtersOpen, setFiltersOpen] = useState(false);
  const filter = filterFromParams(params);
  const taxonLabel = params.get('taxonLabel') ?? undefined;
  const apiFilter: WorkFilter = { ...filter, limit: String(PAGE_SIZE) };
  // the field follows the URL (back, forward, links) but is not remounted, so it keeps the focus after Enter
  const [text, setText] = useState(filter.q ?? '');
  useEffect(() => setText(filter.q ?? ''), [filter.q]);

  const works = useQuery({
    queryKey: ['works', apiFilter],
    queryFn: () => searchWorks(apiFilter, FACETS),
    placeholderData: (prev) => prev,
  });
  const groups = useQuery({ queryKey: ['taxgroups'], queryFn: getTaxGroups, staleTime: Infinity });
  const sources = useQuery({ queryKey: ['sources-total'], queryFn: () => listSources({ limit: 1 }), staleTime: 3600_000 });

  const update = (patch: WorkFilter, extra?: { taxonLabel?: string | null }) => {
    const next = filterToParams({ ...filter, ...patch });
    next.delete('limit');
    const label = extra && 'taxonLabel' in extra ? extra.taxonLabel : taxonLabel;
    if (label && next.get('taxon')) next.set('taxonLabel', label);
    setParams(next);
  };

  // a Follow is offered when the taxgroup is the only filter
  const onlyGroup = Object.entries(filter).every(([k, v]) => !v || k === 'taxgroup' || k === 'offset') ? filter.taxgroup : undefined;
  const activeFilters = activeFilterCount(filter);
  const offset = Number(filter.offset ?? 0);
  const page = Math.floor(offset / PAGE_SIZE) + 1;
  const data = works.data;
  const total = sources.data?.total;
  const lede = total === undefined
    ? 'Garrulus reads journals of biodiversity science every day and lists the scientific names it finds, placed in the Catalogue of Life.'
    : `Garrulus reads ${total.toLocaleString('en')} journals of biodiversity science every day and lists the scientific names it finds, placed in the Catalogue of Life.`;

  const search = (
    <TextInput
      aria-label="Search text"
      placeholder="Search titles and abstracts"
      radius="xl"
      value={text}
      onChange={(e) => setText(e.currentTarget.value)}
      onKeyDown={(e) => {
        if (e.key === 'Enter') update({ q: e.currentTarget.value || undefined, offset: undefined });
      }}
    />
  );

  return (
    <>
      <PageHeader variant="big" searchSlot={search} />
      <main className={frame.main}>
        {/* the filters come first in the DOM (above the list on narrow screens): keyboard users can jump past them */}
        <a href="#results" className={classes.skip} onClick={(e) => {
          e.preventDefault();
          document.getElementById('results')?.focus();
        }}>
          Skip to results
        </a>
        {/* the first row: the result count, and the Follow of a lone taxonomic group where every page has its actions */}
        <HeadRow compact actions={onlyGroup
          ? <FollowActions type="TAXGROUP" target={onlyGroup} label={onlyGroup}
              feed={`/feeds/taxgroup/${encodeURIComponent(onlyGroup)}.atom`} />
          : undefined}>
          <p className={classes.count14}>
            {data && data.results.length ? `${data.total.toLocaleString('en')} works` : '\u00a0'}
          </p>
        </HeadRow>
        <div className={classes.page}>
          <div className={classes.sideWrap}>
            <SideColumn lede={lede}>
              {/* below 1024 px only the facet groups fold away behind the pill; the lede stays */}
              <Button variant="default" radius="xl" size="xs" className={classes.filtersToggle}
                aria-expanded={filtersOpen} aria-controls="search-filters" onClick={() => setFiltersOpen((o) => !o)}>
                {activeFilters ? `Filters (${activeFilters})` : 'Filters'}
              </Button>
              <div id="search-filters" className={classes.filtersWrap} data-open={filtersOpen}>
                <FilterSidebar
                  filter={filter}
                  taxonLabel={taxonLabel}
                  facets={data?.facets ?? {}}
                  taxGroups={groups.data ?? []}
                  onChange={update}
                />
              </div>
            </SideColumn>
          </div>
          <div id="results" tabIndex={-1} role="region" aria-label="Results" className={`${classes.mainCol} ${classes.results}`}>
            {/* the outline's level 2 between the wordmark (h1) and the work titles (h3) */}
            <h2 className={classes.srOnly}>Results</h2>
            {works.isError ? (
              <Alert color="red" title="Search failed">
                {works.error instanceof ApiError ? works.error.message : 'The search is unavailable. Try again later.'}
              </Alert>
            ) : !data ? (
              <Skeleton height={120} />
            ) : data.results.length === 0 ? (
              <p>No works match these filters. Remove a filter or widen the year range to see more.</p>
            ) : (
              <>
                <WorkList works={data.results} />
                {data.total > PAGE_SIZE ? (
                  <div className={classes.pager}>
                    <Pagination
                      total={Math.ceil(Math.min(data.total, 10020) / PAGE_SIZE)}
                      value={page}
                      onChange={(p) => update({ offset: p > 1 ? String((p - 1) * PAGE_SIZE) : undefined })}
                    />
                  </div>
                ) : null}
              </>
            )}
          </div>
        </div>
      </main>
    </>
  );
}
