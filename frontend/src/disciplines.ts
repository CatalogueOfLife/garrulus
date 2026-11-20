import { useQuery } from '@tanstack/react-query';
import { getDisciplines, type Discipline } from './api';

/** The discipline vocabulary of Garrulus; it changes only with a release, so it is read once an hour at most. */
export const useDisciplines = () =>
  useQuery({ queryKey: ['vocab', 'discipline'], queryFn: getDisciplines, staleTime: 60 * 60 * 1000 });

/** The code a retired discipline is replaced by (followed along a chain, at most as long as the list), else the code. */
export function resolveDiscipline(code: string, list: Discipline[] = []): string {
  let cur = code;
  for (let i = 0; i <= list.length; i++) {
    const next = list.find((d) => d.code === cur)?.replacedBy;
    if (!next) break;
    cur = next;
  }
  return cur;
}

/** The label of a discipline; a retired one shows its replacement's label, an unknown code is shown as itself. */
export function disciplineLabel(code: string, list: Discipline[] = []): string {
  const cur = resolveDiscipline(code, list);
  return list.find((d) => d.code === cur)?.label ?? cur;
}

/** Select options: "Any", the current disciplines in vocabulary order and, if it is none of them, the current value. */
export function disciplineOptions(list: Discipline[] = [], current?: string): { value: string; label: string }[] {
  const known = list.filter((d) => !d.replacedBy).sort((a, b) => a.sortOrder - b.sortOrder)
    .map((d) => ({ value: d.code, label: d.label }));
  const shown = current ? resolveDiscipline(current, list) : undefined;
  const extra = shown && !known.some((o) => o.value === shown) ? [{ value: shown, label: shown }] : [];
  return [{ value: '', label: 'Any' }, ...known, ...extra];
}
