import { useEffect, useMemo, useState } from 'react';
import { Checkbox, Group, NativeSelect, Radio, Stack, Text, TextInput } from '@mantine/core';
import type { FacetCount, FacetName, TaxGroup, WorkFilter } from '../api';
import { TaxGroupIcon } from './TaxGroupIcon';
import { TaxonPicker } from './TaxonPicker';

interface Props {
  filter: WorkFilter;
  taxonLabel?: string;
  facets: Partial<Record<FacetName, FacetCount[]>>;
  taxGroups: TaxGroup[];
  onChange: (patch: WorkFilter, extra?: { taxonLabel?: string | null }) => void;
}

const CODES = ['ZOOLOGICAL', 'BOTANICAL', 'BACTERIAL', 'VIRUS'];

function counts(list?: FacetCount[]): Map<string, number> {
  return new Map((list ?? []).map((f) => [f.value, f.count]));
}

function pretty(v: string): string {
  return v.charAt(0) + v.slice(1).toLowerCase().replace(/_/g, ' ');
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

export function FilterSidebar({ filter, taxonLabel, facets, taxGroups, onChange }: Props) {
  const codeCounts = counts(facets.code);
  const groupCounts = counts(facets.taxgroup);
  const typeCounts = counts(facets.journalType);
  const actCounts = counts(facets.hasActs);
  const groups = useMemo(() => orderGroups(taxGroups), [taxGroups]);
  const codes = Array.from(new Set([...CODES, ...codeCounts.keys()]));
  const withCount = (label: string, c?: number) => (c === undefined ? label : `${label} (${c})`);

  return (
    <Stack gap="lg" component="aside" aria-label="Filters">
      <Stack gap={4}>
        <Text fw={600} size="sm">Taxon</Text>
        <TaxonPicker
          label={filter.taxon ? (taxonLabel ?? filter.taxon) : undefined}
          onPick={(id, label) => onChange({ taxon: id, offset: undefined }, { taxonLabel: label })}
          onClear={() => onChange({ taxon: undefined, offset: undefined }, { taxonLabel: null })}
        />
      </Stack>

      <Radio.Group label="Nomenclatural code" value={filter.code ?? ''} onChange={(v) => onChange({ code: v || undefined, offset: undefined })}>
        <Stack gap={4} mt={4}>
          <Radio value="" label="Any" />
          {codes.map((c) => (
            <Radio key={c} value={c} label={withCount(pretty(c), codeCounts.get(c))} />
          ))}
        </Stack>
      </Radio.Group>

      <Radio.Group label="Taxonomic group" value={filter.taxgroup ?? ''} onChange={(v) => onChange({ taxgroup: v || undefined, offset: undefined })}>
        <Stack gap={4} mt={4}>
          <Radio value="" label="Any" />
          {groups.map(({ group, depth }) => (
            <Radio
              key={group.name}
              value={group.name}
              ml={depth * 16}
              label={
                <Group gap={6} wrap="nowrap">
                  <TaxGroupIcon group={group} />
                  <span>{withCount(group.name, groupCounts.get(group.name))}</span>
                </Group>
              }
            />
          ))}
        </Stack>
      </Radio.Group>

      <Radio.Group label="Journal type" value={filter.journalType ?? ''} onChange={(v) => onChange({ journalType: v || undefined, offset: undefined })}>
        <Stack gap={4} mt={4}>
          <Radio value="" label="Any" />
          {['TAXONOMIC', 'GENERAL'].map((t) => (
            <Radio key={t} value={t} label={withCount(pretty(t), typeCounts.get(t))} />
          ))}
        </Stack>
      </Radio.Group>

      <CommitInput label="Subfield" value={filter.subfield ?? ''} onCommit={(v) => onChange({ subfield: v || undefined, offset: undefined })} />

      <NativeSelect
        label="Journal tier"
        value={filter.tier ?? ''}
        data={[{ value: '', label: 'Any' }, { value: 'CORE', label: 'Core' }, { value: 'BROAD', label: 'Broad' }]}
        onChange={(e) => onChange({ tier: e.currentTarget.value || undefined, offset: undefined })}
      />

      <NativeSelect
        label="Open access"
        value={filter.oa ?? ''}
        data={[{ value: '', label: 'Any' }, ...['DIAMOND', 'GOLD', 'HYBRID', 'CLOSED'].map((v) => ({ value: v, label: pretty(v) }))]}
        onChange={(e) => onChange({ oa: e.currentTarget.value || undefined, offset: undefined })}
      />

      <Group grow align="flex-end">
        <CommitInput label="Year from" digits value={filter.yearFrom ?? ''} onCommit={(v) => onChange({ yearFrom: v || undefined, offset: undefined })} />
        <CommitInput label="Year to" digits value={filter.yearTo ?? ''} onCommit={(v) => onChange({ yearTo: v || undefined, offset: undefined })} />
      </Group>

      <Checkbox
        label={withCount('Has nomenclatural acts', actCounts.get('true'))}
        checked={filter.hasActs === 'true'}
        onChange={(e) => onChange({ hasActs: e.currentTarget.checked ? 'true' : undefined, offset: undefined })}
      />
    </Stack>
  );
}
