import { Anchor, Group, Text } from '@mantine/core';
import { Fragment } from 'react';
import { Link } from 'react-router-dom';

export interface PathItem { id?: string | null; name?: string | null; rank?: string | null }

const RANKS = [
  'domain', 'superkingdom', 'kingdom', 'subkingdom', 'infrakingdom', 'superphylum', 'phylum', 'subphylum', 'infraphylum',
  'superclass', 'class', 'subclass', 'infraclass', 'superorder', 'order', 'suborder', 'infraorder', 'parvorder',
  'superfamily', 'family', 'subfamily', 'tribe', 'subtribe', 'genus', 'subgenus', 'section', 'series',
  'species', 'subspecies', 'variety', 'form',
];
const rankIndex = (r?: string | null) => (r ? RANKS.indexOf(r.trim().toLowerCase().replace(/[\s_-]+/g, '')) : -1);

/**
 * Items root first (top → bottom). With at least two known ranks the direction follows the ranks (first known rank
 * higher than the last one means the input is lowest-first). Otherwise the order cannot be seen, and `order` says
 * what the input is: CLB match and the work page's placement classifications (`classification[]` from the CLB match
 * endpoint) are lowest-first, hence the default; the CLB classification endpoint is root-first.
 */
export function rootFirst<T extends PathItem>(items: T[], order: 'lowest-first' | 'root-first' = 'lowest-first'): T[] {
  const known = items.map((i) => rankIndex(i.rank)).filter((i) => i >= 0);
  if (known.length >= 2) {
    return known[0] > known[known.length - 1] ? [...items].reverse() : items;
  }
  return order === 'lowest-first' ? [...items].reverse() : items;
}

export function ClassificationPath({ items, order = 'lowest-first', size = 'sm' }: {
  items: PathItem[]; order?: 'lowest-first' | 'root-first'; size?: 'xs' | 'sm';
}) {
  const path = rootFirst(items.filter((i) => i.name), order);
  if (!path.length) return null;
  return (
    <Group gap={6} data-testid="classification-path">
      {path.map((c, i) => (
        <Fragment key={c.id ?? `${c.name}-${i}`}>
          {c.id ? <Anchor component={Link} to={`/taxon/${c.id}`} size={size}>{c.name}</Anchor>
            : <Text span size={size} c="dimmed">{c.name}</Text>}
          {i < path.length - 1 ? <Text span size={size} c="dimmed" aria-hidden>›</Text> : null}
        </Fragment>
      ))}
    </Group>
  );
}
