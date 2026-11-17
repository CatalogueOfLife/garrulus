import type { ReactNode } from 'react';
import { Rule } from './Rule';
import classes from './list.module.css';

export interface FacetItem { value: string; label: ReactNode; count?: number }

/** A filter group: a small heading under a rule, then rows (label left, count right); a second click clears. */
export function FacetGroup({ title, items, current, onSelect }: {
  title: string;
  items: FacetItem[];
  current?: string;
  onSelect(value?: string): void;
}) {
  return (
    <section className={classes.facet}>
      <Rule />
      <h3 className={classes.facetTitle}>{title}</h3>
      <ul className={classes.rows} aria-label={title}>
        {items.map((it) => {
          const isCurrent = it.value === current;
          return (
            <li key={it.value}>
              <button type="button" className={classes.row} aria-current={isCurrent ? 'true' : undefined}
                onClick={() => onSelect(isCurrent ? undefined : it.value)}>
                <span className={classes.label}>{it.label}</span>
                {it.count !== undefined && <span className={classes.count}>{it.count.toLocaleString('en')}</span>}
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
