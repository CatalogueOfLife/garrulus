import { Fragment, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { SourceRef, WorkSummary } from '../api';
import styles from './frame.module.css';

export interface MetaLineProps {
  authors: string[];
  authorCount: number;
  source?: SourceRef | null;
  volume?: string | null;
  issue?: string | null;
  pages?: string | null;
  /** Publication date, a calendar date (yyyy-mm-dd). */
  date?: string | null;
  year?: number | null;
  textLevel?: WorkSummary['textLevel'];
}

const TEXT_LEVEL: Record<string, string> = { FULLTEXT: 'Full text', ABSTRACT_ONLY: 'Abstract' };

function formatDate(date: string): string {
  const d = new Date(date);
  if (Number.isNaN(d.getTime())) return date;
  return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
}

/** Authors, journal with volume, issue and pages, date and text level in grey, empty parts dropped. */
export function MetaLine({ authors, authorCount, source, volume, issue, pages, date, year, textLevel }: MetaLineProps) {
  const parts: ReactNode[] = [];
  if (authors.length) parts.push(authors.join(', ') + (authorCount > authors.length ? ' et al.' : ''));
  const where = `${volume ?? ''}${issue ? `(${issue})` : ''}${pages ? `${volume || issue ? ': ' : ''}${pages.replace(/-/g, '–')}` : ''}`;
  if (source || where) {
    parts.push(
      <>
        {source && <Link to={`/source/${source.id}`}>{source.title}</Link>}
        {source && where ? ' ' : ''}
        {where}
      </>,
    );
  }
  const when = date ? formatDate(date) : year != null ? String(year) : '';
  if (when) parts.push(when);
  const level = textLevel ? TEXT_LEVEL[textLevel] : undefined;
  if (level) parts.push(level);
  return (
    <p className={styles.meta}>
      {parts.map((p, i) => (
        <Fragment key={i}>
          {i > 0 && ' '}
          <span className={styles.metaPart}>{p}</span>
        </Fragment>
      ))}
    </p>
  );
}
