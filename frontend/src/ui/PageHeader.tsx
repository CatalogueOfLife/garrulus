import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { AccountMenu } from '../components/AccountMenu';
import { SiteMenu } from '../components/SiteMenu';
import { Mark } from './Mark';
import { Rule } from './Rule';
import styles from './frame.module.css';

const TAGLINE = 'Names in the literature of biodiversity science';

/**
 * Compact on every page but search: wordmark left, menu and account right, a rule under it. Big on the search page:
 * the same menu and account at the same place, beside the tagline and with no rule under them, then the display
 * wordmark, a rule, and a row of the intro text and the search field.
 */
export function PageHeader({ variant, searchSlot, intro }: {
  variant: 'big' | 'compact';
  searchSlot?: ReactNode;
  /** The big header's intro text, left of the search field. */
  intro?: ReactNode;
}) {
  if (variant === 'compact') {
    return (
      <header className={styles.header}>
        <div className={styles.compact}>
          <Link to="/" className={styles.brandLink}>
            <Mark size={38} />
            <span className={styles.wordmarkCompact}>Garrulus</span>
          </Link>
          <div className={styles.compactRight}>
            <SiteMenu />
            <AccountMenu />
          </div>
        </div>
        <Rule />
      </header>
    );
  }
  return (
    <header className={styles.header}>
      <div className={styles.topLine}>
        <span className={styles.tagline}>{TAGLINE}</span>
        <div className={styles.compactRight}>
          <SiteMenu />
          <AccountMenu />
        </div>
      </div>
      <div className={styles.brandBig}>
        <Mark size={96} />
        <h1 className={styles.wordmarkBig}>
          <Link to="/">Garrulus</Link>
        </h1>
      </div>
      <Rule />
      <div className={styles.introBar}>
        {intro ? <p className={styles.intro}>{intro}</p> : <span />}
        {searchSlot && <div className={styles.searchSlot}>{searchSlot}</div>}
      </div>
    </header>
  );
}
