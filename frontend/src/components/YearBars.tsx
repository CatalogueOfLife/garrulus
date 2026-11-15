import { Text } from '@mantine/core';

export interface YearCount { year: number; works: number }

/** One bar per year; the tallest bar fills the height. */
export function YearBars({ years }: { years: YearCount[] }) {
  if (years.length === 0) return <Text c="dimmed" size="sm">No papers yet.</Text>;
  const byYear = new Map(years.map((y) => [y.year, y.works]));
  const first = Math.min(...byYear.keys());
  const last = Math.max(...byYear.keys());
  const sorted: YearCount[] = [];
  for (let y = first; y <= last; y++) sorted.push({ year: y, works: byYear.get(y) ?? 0 });
  const max = Math.max(1, ...sorted.map((y) => y.works));
  const w = 12;
  const gap = 4;
  const width = sorted.length * (w + gap) - gap;
  return (
    <div>
      <svg role="img" aria-label="Papers per year" viewBox={`0 0 ${width} 100`} width="100%" height={120}
        preserveAspectRatio="none" style={{ maxWidth: Math.max(160, width * 3), display: 'block' }}>
        {sorted.map((y, i) => {
          const h = Math.round((y.works / max) * 100);
          return (
            <rect key={y.year} data-testid="year-bar" aria-label={`${y.year}: ${y.works} works`}
              x={i * (w + gap)} y={100 - h} width={w} height={h} fill="var(--mantine-color-jay-6)">
              <title>{`${y.year}: ${y.works} works`}</title>
            </rect>
          );
        })}
      </svg>
      <Text size="xs" c="dimmed">{sorted[0].year}{sorted.length > 1 ? ` – ${sorted[sorted.length - 1].year}` : ''}</Text>
    </div>
  );
}
