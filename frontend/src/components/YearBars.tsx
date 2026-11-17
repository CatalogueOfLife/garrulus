import { Text } from '@mantine/core';
import { useState } from 'react';

export interface YearCount { year: number; works: number }

// Drawn at the side column's width (340 px), so 11-unit text renders at 11 px instead of being scaled down.
const W = 340;
const H = 150;
const M = { left: 44, right: 8, top: 10, bottom: 28 };
const PLOT_W = W - M.left - M.right;
const PLOT_H = H - M.top - M.bottom;
const MIN_BAR = 2; // a non-zero year is always visible

/** 0 … max in at most five integer ticks with a "nice" step (1, 2, 5, 10, 20, 50 …). */
export function niceTicks(max: number): number[] {
  const m = Math.max(1, max);
  let step = 1;
  for (let mag = 1; ; mag *= 10) {
    const hit = [1, 2, 5].map((f) => f * mag).find((s) => Math.ceil(m / s) + 1 <= 5);
    if (hit) { step = hit; break; }
  }
  const ticks: number[] = [];
  for (let t = 0; t < m + step; t += step) { ticks.push(t); if (t >= m) break; }
  return ticks;
}

/** Indices of the years that get a label: all up to `all` years, else about `target`, always the first and the last. */
export function labelIndices(n: number, all = 12, target = 8): number[] {
  if (n <= all) return Array.from({ length: n }, (_, i) => i);
  const step = Math.ceil(n / target);
  const idx: number[] = [];
  for (let i = 0; i < n; i += step) idx.push(i);
  const last = n - 1;
  if (idx[idx.length - 1] !== last) {
    while (idx.length > 1 && idx[idx.length - 1] > last - Math.ceil(step / 2)) idx.pop();
    idx.push(last);
  }
  return idx;
}

const papers = (n: number) => `${n} ${n === 1 ? 'paper' : 'papers'}`;

/**
 * One bar per year with a y axis (grid, "Papers") and year labels; hover or focus shows the count at once. Bars are ink;
 * those within `range` (the selected years) carry `gr-current` and are red.
 */
export function YearBars({ years, range }: { years: YearCount[]; range?: { from?: number; to?: number } }) {
  const [active, setActive] = useState<number | null>(null);
  if (years.length === 0) return <Text c="dimmed" size="sm">No papers yet.</Text>;
  const byYear = new Map(years.map((y) => [y.year, y.works]));
  const first = Math.min(...byYear.keys());
  const last = Math.max(...byYear.keys());
  const sorted: YearCount[] = [];
  for (let y = first; y <= last; y++) sorted.push({ year: y, works: byYear.get(y) ?? 0 });
  const ticks = niceTicks(Math.max(...sorted.map((y) => y.works)));
  const top = ticks[ticks.length - 1];
  const slot = PLOT_W / sorted.length;
  const bw = Math.min(40, Math.max(2, slot * 0.7));
  const yOf = (v: number) => M.top + PLOT_H - (v / top) * PLOT_H;
  // four-digit years at 11 px need about 34 px each: eight fit the plot side by side
  const labels = new Set(labelIndices(sorted.length, 8, 6));
  const cur = active === null ? null : sorted[active];
  return (
    <div style={{ maxWidth: W }}>
      <Text size="sm" mih={22} aria-live="polite" data-testid="year-readout">
        {cur ? `${cur.year}: ${papers(cur.works)}` : ''}
      </Text>
      <svg role="group" aria-label="Papers per year" viewBox={`0 0 ${W} ${H}`} width="100%" style={{ display: 'block', height: 'auto' }}>
        {ticks.map((t) => (
          <g key={t} data-testid="y-tick">
            <line x1={M.left} x2={W - M.right} y1={yOf(t)} y2={yOf(t)}
              className="gr-axis-line" strokeOpacity={t === 0 ? 0.6 : 0.2} />
            <text x={M.left - 6} y={yOf(t)} textAnchor="end" dominantBaseline="middle" fontSize={11}
              className="gr-axis-text">{t}</text>
          </g>
        ))}
        <text transform={`translate(11 ${M.top + PLOT_H / 2}) rotate(-90)`} textAnchor="middle" fontSize={11}
          className="gr-axis-text">Papers</text>
        {sorted.map((y, i) => {
          const x = M.left + i * slot + (slot - bw) / 2;
          const h = y.works > 0 ? Math.max(MIN_BAR, Math.round((y.works / top) * PLOT_H)) : 0;
          const selected = range !== undefined && (range.from !== undefined || range.to !== undefined)
            && y.year >= (range.from ?? -Infinity) && y.year <= (range.to ?? Infinity);
          return (
            <g key={y.year}>
              <rect x={M.left + i * slot} y={M.top} width={slot} height={PLOT_H} fill="transparent"
                onMouseEnter={() => setActive(i)} onMouseLeave={() => setActive(null)} />
              <rect data-testid="year-bar" aria-label={`${y.year}: ${papers(y.works)}`} tabIndex={0}
                x={x} y={M.top + PLOT_H - h} width={bw} height={h}
                className={selected ? 'gr-bar gr-current' : 'gr-bar'}
                onMouseEnter={() => setActive(i)} onMouseLeave={() => setActive(null)}
                onFocus={() => setActive(i)} onBlur={() => setActive(null)} />
              {labels.has(i) ? (
                <text data-testid="x-label" x={x + bw / 2} y={H - 8} textAnchor="middle" fontSize={11}
                  className="gr-axis-text">{y.year}</text>
              ) : null}
            </g>
          );
        })}
      </svg>
    </div>
  );
}
