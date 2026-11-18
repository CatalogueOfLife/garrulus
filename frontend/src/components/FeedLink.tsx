import { ActionIcon } from '@mantine/core';

/** The feed glyph: a dot and two arcs, drawn in the text colour. */
function FeedGlyph() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"
      strokeLinecap="round" aria-hidden="true" focusable="false">
      <path d="M5 11a8 8 0 0 1 8 8" />
      <path d="M5 4a15 15 0 0 1 15 15" />
      <circle cx="6" cy="18" r="2" fill="currentColor" stroke="none" />
    </svg>
  );
}

/** A public Atom feed: a round outline button with the feed glyph, the height of the Follow pill beside it. */
export function FeedLink({ href }: { href: string }) {
  return (
    <ActionIcon component="a" href={href} variant="default" radius="xl" size="input-xs"
      aria-label="Atom feed" title="Atom feed">
      <FeedGlyph />
    </ActionIcon>
  );
}
