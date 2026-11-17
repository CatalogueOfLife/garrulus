import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { AccountMenu } from '../components/AccountMenu';
import { SiteMenu } from '../components/SiteMenu';
import { Mark } from './Mark';
import { Rule } from './Rule';
import styles from './frame.module.css';

const TAGLINE = 'Names in the literature of biodiversity science';

/** Big on the search page (tagline, display wordmark, nav between rules, search slot), compact elsewhere. */
export function PageHeader({ variant, searchSlot }: { variant: 'big' | 'compact'; searchSlot?: ReactNode }) {
  if (variant === 'compact') {
    return (
      <header className={styles.header}>
        <div className={styles.compact}>
          <Link to="/" className={styles.brandLink}>
            <Mark size={38} />
            <span className={styles.wordmarkCompact}>Garrulus</span>
          </Link>
          <SiteMenu />
          <AccountMenu />
        </div>
        <Rule />
      </header>
    );
  }
  return (
    <header className={styles.header}>
      <div className={styles.topLine}>
        <span>{TAGLINE}</span>
        <AccountMenu />
      </div>
      <div className={styles.brandBig}>
        <Mark size={96} />
        <h1 className={styles.wordmarkBig}>
          <Link to="/">Garrulus</Link>
        </h1>
      </div>
      <Rule />
      <div className={styles.navBar}>
        <SiteMenu />
        {searchSlot && <div className={styles.searchSlot}>{searchSlot}</div>}
      </div>
      <Rule />
    </header>
  );
}
