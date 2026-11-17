import type { ReactNode } from 'react';
import { Mark } from './Mark';
import styles from './emptyState.module.css';

/** The large mark, a title, one sentence and an optional action: an empty list, a missing page. */
export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className={styles.empty}>
      <Mark size={120} />
      <h2 className={styles.title}>{title}</h2>
      {children ? <p className={styles.text}>{children}</p> : null}
      {action ? <div className={styles.action}>{action}</div> : null}
    </div>
  );
}
