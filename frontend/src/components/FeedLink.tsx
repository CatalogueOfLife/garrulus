import { Anchor } from '@mantine/core';

/** A small link with the standard feed icon to a public Atom feed. */
export function FeedLink({ href }: { href: string }) {
  return (
    <Anchor href={href} title="Atom feed" aria-label="Atom feed" display="inline-flex" style={{ alignItems: 'center' }}>
      <svg width="18" height="18" viewBox="0 0 16 16" aria-hidden="true" fill="currentColor">
        <circle cx="3" cy="13" r="1.8" />
        <path d="M1.2 6.2v2.4a6.2 6.2 0 0 1 6.2 6.2h2.4A8.6 8.6 0 0 0 1.2 6.2z" />
        <path d="M1.2 1.2v2.4a11.2 11.2 0 0 1 11.2 11.2h2.4A13.6 13.6 0 0 0 1.2 1.2z" />
      </svg>
    </Anchor>
  );
}
