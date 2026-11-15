import { Title } from '@mantine/core';
import type { ReactNode } from 'react';

export function Section({ title, children }: { title: string; children: ReactNode }) {
  const id = `sec-${title.replace(/\W+/g, '-')}`;
  return (
    <section aria-labelledby={id}>
      <Title order={4} id={id} mb="xs">{title}</Title>
      {children}
    </section>
  );
}
