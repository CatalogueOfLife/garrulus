import { Checkbox, Stack, Text } from '@mantine/core';
import type { MentionedIn } from '../api';

const PARTS: { value: MentionedIn; label: string }[] = [
  { value: 'TITLE', label: 'Title' },
  { value: 'ABSTRACT', label: 'Abstract' },
  { value: 'BODY', label: 'Full text' },
];

/** The same hint on the filter and on the follow dialog. */
export const FULL_TEXT_HINT = 'Full text is available for some papers only.';

/**
 * Where the name must occur: checkboxes for the title, the abstract and the full text; none checked means anywhere.
 * `legend={null}` leaves the label out, for a place whose heading already names the filter.
 */
export function MentionedInFilter({ value, onChange, legend = 'Mentioned in' }: {
  value: string[];
  onChange: (v: MentionedIn[]) => void;
  legend?: string | null;
}) {
  return (
    <Checkbox.Group
      label={legend ?? undefined}
      value={value}
      onChange={(v) => onChange(PARTS.filter((p) => v.includes(p.value)).map((p) => p.value))}
    >
      <Stack gap={4} mt={4}>
        {PARTS.map((p) => <Checkbox key={p.value} value={p.value} label={p.label} />)}
        <Text size="xs" c="dimmed">{FULL_TEXT_HINT}</Text>
      </Stack>
    </Checkbox.Group>
  );
}

/** A public feed link narrowed to the chosen parts. */
export function withMentionedIn(href: string, mentionedIn?: string[]): string {
  if (!mentionedIn?.length) return href;
  return `${href}?${mentionedIn.map((m) => `mentionedIn=${encodeURIComponent(m)}`).join('&')}`;
}
