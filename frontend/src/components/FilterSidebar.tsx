import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Group, NativeSelect, Stack, Text, TextInput } from '@mantine/core';
import type { FacetCount, FacetName, TaxGroup, WorkFilter } from '../api';
import { TaxGroupIcon } from './TaxGroupIcon';
import { TaxonPicker } from './TaxonPicker';
import { MentionedInFilter } from './MentionedInFilter';
import { languageName } from '../languages';
import { disciplineOptions, resolveDiscipline, useDisciplines } from '../disciplines';
import { FacetGroup } from '../ui/FacetGroup';
import { Rule } from '../ui/Rule';
import { sentence } from '../ui/sentence';
import classes from '../ui/list.module.css';

interface Props {
  filter: WorkFilter;
  taxonLabel?: string;
  facets: Partial<Record<FacetName, FacetCount[]>>;
  taxGroups: TaxGroup[];
  onChange: (patch: WorkFilter, extra?: { taxonLabel?: string | null }) => void;
}

const CODES = ['ZOOLOGICAL', 'BOTANICAL', 'BACTERIAL', 'VIRUS'];

/** The filters the sidebar sets that are on; a year range counts once, the text search is no filter. */
export function activeFilterCount(f: WorkFilter): number {
  const on = [f.taxon, f.taxgroup, f.code, f.journalType, f.hasActs, f.subfield, f.tier, f.oa, f.yearFrom || f.yearTo, f.mentionedIn?.length, f.language?.length];
  return on.filter(Boolean).length;
}

function counts(list?: FacetCount[]): Map<string, number> {
  return new Map((list ?? []).map((f) => [f.value, f.count]));
}

/** The facet's languages with their names; the works without a decided language (facet value empty) are `none`. */
export function languageItems(list?: FacetCount[]): { value: string; label: string; count: number }[] {
  return (list ?? []).map((f) => (f.value === ''
    ? { value: 'none', label: 'Undecided', count: f.count }
    : { value: f.value, label: languageName(f.value), count: f.count }));
}

/** A text input that keeps its own state while typing and commits on blur or Enter only. */
function CommitInput({ label, value, digits, onCommit }: { label: string; value: string; digits?: boolean; onCommit: (v: string) => void }) {
  const [local, setLocal] = useState(value);
  useEffect(() => setLocal(value), [value]);
  const commit = () => {
    const v = local.trim();
    if (v !== value) onCommit(v);
  };
  return (
    <TextInput
      label={label}
      value={local}
      inputMode={digits ? 'numeric' : undefined}
      onChange={(e) => setLocal(digits ? e.currentTarget.value.replace(/\D/g, '') : e.currentTarget.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === 'Enter') commit();
      }}
    />
  );
}

/** Flatten the groups depth first, each under its primary parent: parents[0] is the primary one (declared order). */
export function orderGroups(groups: TaxGroup[]): { group: TaxGroup; depth: number }[] {
  const byName = new Map(groups.map((g) => [g.name, g]));
  const children = new Map<string, TaxGroup[]>();
  const roots: TaxGroup[] = [];
  for (const g of groups) {
    const p = g.parents.find((x) => byName.has(x));
    if (p) children.set(p, [...(children.get(p) ?? []), g]);
    else roots.push(g);
  }
  const out: { group: TaxGroup; depth: number }[] = [];
  const seen = new Set<string>();
  const walk = (g: TaxGroup, depth: number) => {
    if (seen.has(g.name)) return;
    seen.add(g.name);
    out.push({ group: g, depth });
    for (const c of children.get(g.name) ?? []) walk(c, depth + 1);
  };
  roots.forEach((r) => walk(r, 0));
  return out;
}

/** A titled block under a rule, for the controls that are not a list of values. */
function Field({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className={classes.field}>
      <Rule />
      <h3 className={classes.fieldTitle}>{title}</h3>
      {children}
    </section>
  );
}

export function FilterSidebar({ filter, taxonLabel, facets, taxGroups, onChange }: Props) {
  const disciplines = useDisciplines();
  const codeCounts = counts(facets.code);
  const groupCounts = counts(facets.taxgroup);
  const typeCounts = counts(facets.journalType);
  const actCounts = counts(facets.hasActs);
  const groups = useMemo(() => orderGroups(taxGroups), [taxGroups]);
  const codes = Array.from(new Set([...CODES, ...codeCounts.keys()]));
  const set = (key: keyof WorkFilter) => (v?: string) => onChange({ [key]: v, offset: undefined });

  return (
    <Stack gap={0} component="div" role="group" aria-labelledby="filters-heading">
      {/* the outline's level 2 above the facet group headings (h3) */}
      <h2 id="filters-heading" className={classes.srOnly}>Filters</h2>
      <Field title="Taxon">
        <TaxonPicker
          label={filter.taxon ? (taxonLabel ?? filter.taxon) : undefined}
          onPick={(id, label) => onChange({ taxon: id, offset: undefined }, { taxonLabel: label })}
          onClear={() => onChange({ taxon: undefined, offset: undefined }, { taxonLabel: null })}
        />
      </Field>

      <FacetGroup title="Taxonomic group" current={filter.taxgroup} onSelect={set('taxgroup')}
        items={groups.map(({ group, depth }) => ({
          value: group.name,
          count: groupCounts.get(group.name),
          label: (
            <span className={classes.label} style={{ marginLeft: depth * 16 }}>
              <TaxGroupIcon group={group} />
              <span>{group.name}</span>
            </span>
          ),
        }))} />

      <FacetGroup title="Nomenclatural code" current={filter.code} onSelect={set('code')}
        items={codes.map((c) => ({ value: c, label: sentence(c), count: codeCounts.get(c) }))} />

      <FacetGroup title="Journal type" current={filter.journalType} onSelect={set('journalType')}
        items={['TAXONOMIC', 'GENERAL'].map((t) => ({ value: t, label: sentence(t), count: typeCounts.get(t) }))} />

      {facets.language?.length || filter.language?.length ? (
        <FacetGroup title="Language" current={filter.language?.[0]}
          onSelect={(v) => onChange({ language: v ? [v] : undefined, offset: undefined })}
          items={languageItems(facets.language)} />
      ) : null}

      <Field title="Mentioned in">
        <MentionedInFilter legend="Name occurs in" value={filter.mentionedIn ?? []}
          onChange={(v) => onChange({ mentionedIn: v.length ? v : undefined, offset: undefined })} />
      </Field>

      <FacetGroup title="Acts" current={filter.hasActs === 'true' ? 'true' : undefined} onSelect={set('hasActs')}
        items={[{ value: 'true', label: 'Has nomenclatural acts', count: actCounts.get('true') }]} />

      <Field title="Discipline">
        <NativeSelect aria-label="Discipline" value={filter.subfield ? resolveDiscipline(filter.subfield, disciplines.data) : ''} onChange={(e) => onChange({ subfield: e.currentTarget.value || undefined, offset: undefined })}>
          {disciplineOptions(disciplines.data, filter.subfield).map((o) => <option key={o.value} value={o.value} title={o.value || undefined}>{o.label}</option>)}
        </NativeSelect>
        {disciplines.isError ? <Text size="sm" c="dimmed">Discipline names are not available now; codes are shown.</Text> : null}
      </Field>

      <Field title="Journal tier">
        <NativeSelect
          aria-label="Journal tier"
          value={filter.tier ?? ''}
          data={[{ value: '', label: 'Any' }, { value: 'CORE', label: 'Core' }, { value: 'BROAD', label: 'Broad' }]}
          onChange={(e) => onChange({ tier: e.currentTarget.value || undefined, offset: undefined })}
        />
      </Field>

      <Field title="Open access">
        <NativeSelect
          aria-label="Open access"
          value={filter.oa ?? ''}
          data={[{ value: '', label: 'Any' }, ...['DIAMOND', 'GOLD', 'HYBRID', 'CLOSED'].map((v) => ({ value: v, label: sentence(v) }))]}
          onChange={(e) => onChange({ oa: e.currentTarget.value || undefined, offset: undefined })}
        />
      </Field>

      <Field title="Years">
        <Group grow align="flex-end">
          <CommitInput label="Year from" digits value={filter.yearFrom ?? ''} onCommit={(v) => onChange({ yearFrom: v || undefined, offset: undefined })} />
          <CommitInput label="Year to" digits value={filter.yearTo ?? ''} onCommit={(v) => onChange({ yearTo: v || undefined, offset: undefined })} />
        </Group>
      </Field>
    </Stack>
  );
}
