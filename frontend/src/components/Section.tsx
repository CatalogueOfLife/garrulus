import type { ReactNode } from 'react';
import { Rule } from '../ui/Rule';
import classes from '../ui/work.module.css';

/** A heading under a rule, then its content. */
export function Section({ title, children }: { title: string; children: ReactNode }) {
  const id = `sec-${title.replace(/\W+/g, '-')}`;
  return (
    <section aria-labelledby={id} className={classes.section}>
      <Rule />
      <h2 id={id} className={classes.sectionTitle}>{title}</h2>
      {children}
    </section>
  );
}
