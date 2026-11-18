import type { ReactNode } from 'react';
import { Mark } from './Mark';
import styles from './emptyState.module.css';

/**
 * The large mark, a title, one sentence and an optional action: an empty list, a missing page. The title is an h2
 * inside a page; a page that is nothing but the empty state (not found) makes it its h1.
 */
export function EmptyState({ title, children, action, headingLevel = 2 }: {
  title: string; children?: ReactNode; action?: ReactNode; headingLevel?: 1 | 2;
}) {
  const Heading = headingLevel === 1 ? 'h1' : 'h2';
  return (
    <div className={styles.empty}>
      <Mark size={120} />
      <Heading className={styles.title}>{title}</Heading>
      {children ? <p className={styles.text}>{children}</p> : null}
      {action ? <div className={styles.action}>{action}</div> : null}
    </div>
  );
}
