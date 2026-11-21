import type { LanguageYear } from '../api';
import { languageName } from '../languages';
import classes from '../ui/work.module.css';

/** Works per language, with a small table by year (numbers only). */
export function LanguageBreakdown({ languages }: { languages?: LanguageYear[] }) {
  if (!languages?.length) return null;
  const total = new Map<string, number>();
  for (const l of languages) total.set(l.language, (total.get(l.language) ?? 0) + l.works);
  const codes = [...total.entries()].sort((a, b) => b[1] - a[1]).map(([c]) => c);
  const years = [...new Set(languages.map((l) => l.year))].sort((a, b) => b - a);
  const cell = new Map(languages.map((l) => [`${l.language}|${l.year}`, l.works]));
  const name = (c: string) => (c === '' ? 'Undecided' : languageName(c));
  return (
    <table className={classes.langTable} aria-label="Papers per language and year">
      <thead>
        <tr>
          <th scope="col">Language</th>
          <th scope="col">Papers</th>
          {years.slice(0, 5).map((y) => <th key={y} scope="col">{y}</th>)}
        </tr>
      </thead>
      <tbody>
        {codes.map((c) => (
          <tr key={c}>
            <th scope="row">{name(c)}</th>
            <td>{total.get(c)}</td>
            {years.slice(0, 5).map((y) => <td key={y}>{cell.get(`${c}|${y}`) ?? 0}</td>)}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
