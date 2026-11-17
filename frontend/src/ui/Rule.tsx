import styles from './frame.module.css';

/** A horizontal rule: 1px ink, or the soft rule colour. */
export function Rule({ soft }: { soft?: boolean }) {
  return <hr className={soft ? `${styles.rule} ${styles.soft}` : styles.rule} />;
}
