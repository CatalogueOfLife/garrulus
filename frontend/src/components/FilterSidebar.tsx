import { useMemo } from 'react';
import { Checkbox, Group, NativeSelect, NumberInput, Radio, Stack, Text, TextInput } from '@mantine/core';
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

/** Flatten the groups depth first, each under its first parent. */
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

      <TextInput
        label="Subfield"
        value={filter.subfield ?? ''}
        onChange={(e) => onChange({ subfield: e.currentTarget.value || undefined, offset: undefined })}
      />

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
        <NumberInput
          label="Year from"
          hideControls
          allowDecimal={false}
          value={filter.yearFrom ?? ''}
          onChange={(v) => onChange({ yearFrom: v === '' ? undefined : String(v), offset: undefined })}
        />
        <NumberInput
          label="Year to"
          hideControls
          allowDecimal={false}
          value={filter.yearTo ?? ''}
          onChange={(v) => onChange({ yearTo: v === '' ? undefined : String(v), offset: undefined })}
        />
      </Group>

      <Checkbox
        label={withCount('Has nomenclatural acts', actCounts.get('true'))}
        checked={filter.hasActs === 'true'}
        onChange={(e) => onChange({ hasActs: e.currentTarget.checked ? 'true' : undefined, offset: undefined })}
      />
    </Stack>
  );
}
