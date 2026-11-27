import type { LanguageYear } from '../api';
import { languageName } from '../languages';
import classes from '../ui/work.module.css';

/** The filter value of a language row: the undecided works (language '') are `none`, as in the works facet. */
const filterValue = (c: string) => (c === '' ? 'none' : c);

/**
 * Works per language, with a small table by year (numbers only). With `onSelect` the language names are buttons that
 * set the language filter (`current`, a second click clears it).
 */
export function LanguageBreakdown({ languages, current, onSelect }: {
  languages?: LanguageYear[];
  current?: string;
  onSelect?: (value?: string) => void;
}) {
  if (!languages?.length && !(onSelect && current)) return null;
  const rows = languages ?? [];
  const total = new Map<string, number>();
  for (const l of rows) total.set(l.language, (total.get(l.language) ?? 0) + l.works);
  const codes = [...total.entries()].sort((a, b) => b[1] - a[1]).map(([c]) => c);
  const years = [...new Set(rows.map((l) => l.year))].sort((a, b) => b - a);
  const cell = new Map(rows.map((l) => [`${l.language}|${l.year}`, l.works]));
  const name = (c: string) => (c === '' ? 'Undecided' : languageName(c));
  return (
    <>
      {codes.length ? (
        <table className={classes.langTable} aria-label="Papers per language and year">
          <thead>
            <tr>
              <th scope="col">Language</th>
              <th scope="col">Papers</th>
              {years.slice(0, 5).map((y) => <th key={y} scope="col">{y}</th>)}
            </tr>
          </thead>
          <tbody>
            {codes.map((c) => {
              const v = filterValue(c);
              const on = v === current;
              return (
                <tr key={c}>
                  <th scope="row">
                    {onSelect ? (
                      <button type="button" className={classes.langPick} aria-pressed={on}
                        onClick={() => onSelect(on ? undefined : v)}>
                        {name(c)}
                      </button>
                    ) : name(c)}
                  </th>
                  <td>{total.get(c)}</td>
                  {years.slice(0, 5).map((y) => <td key={y}>{cell.get(`${c}|${y}`) ?? 0}</td>)}
                </tr>
              );
            })}
          </tbody>
        </table>
      ) : null}
      {onSelect && current ? (
        <button type="button" className={classes.langClear} onClick={() => onSelect(undefined)}>
          Show papers in all languages
        </button>
      ) : null}
    </>
  );
}
