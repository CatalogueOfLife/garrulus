import { useRef, useState, type ReactNode } from 'react';
import { CloseButton, Group, Loader, Stack, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { getXr, suggestTaxa } from '../api';
import { isItalicName } from '../ui/nameStyle';
import styles from '../ui/pages.module.css';

interface Props {
  /** The label of the selected taxon, if any. */
  label?: string;
  onPick: (usageId: string, label: string) => void;
  onClear: () => void;
}

const MARKER = /^(?:subsp|ssp|var|subvar|f|forma|morph|ab)\.$/;

/** A suggestion with only its name italic: genus (and epithets) of a genus-or-below rank, the rest, authorship included, roman. */
function SuggestionName({ text, rank }: { text: string; rank?: string }) {
  const words = text.split(' ');
  if (!isItalicName(text, { rank }) && rank) return <>{text}</>;
  let end = 1;
  while (end < words.length && (/^[a-z-]+$/.test(words[end]) || MARKER.test(words[end]))) end++;
  while (end > 1 && MARKER.test(words[end - 1])) end--; // a trailing marker is not part of the name
  if (!/^[A-Z]/.test(words[0]) || (!rank && end === 1)) return <>{text}</>;
  const out: ReactNode[] = [];
  let run: string[] = [];
  const flush = (i: number) => { if (run.length) out.push(<i key={i}>{run.join(' ')}</i>); run = []; };
  words.slice(0, end).forEach((w, i) => {
    if (MARKER.test(w)) { flush(i); out.push(` ${w} `); } else run.push(w);
  });
  flush(end);
  const rest = words.slice(end).join(' ');
  return <>{out}{rest ? (end > 0 && !MARKER.test(words[end - 1]) ? ' ' : '') + rest : ''}</>;
}

export function TaxonPicker({ label, onPick, onClear }: Props) {
  const [text, setText] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const [debounced] = useDebouncedValue(text.trim(), 200);
  const xr = useQuery({ queryKey: ['xr'], queryFn: getXr, staleTime: Infinity });
  const xrKey = xr.data?.key;
  const suggest = useQuery({
    queryKey: ['suggest', xrKey, debounced],
    queryFn: () => suggestTaxa(xrKey!, debounced),
    enabled: xrKey !== undefined && debounced.length >= 2,
  });

  return (
    <Stack gap={4}>
      {label ? (
        <Group gap={4} wrap="nowrap">
          <Text size="sm">{label}</Text>
          <CloseButton size="sm" aria-label="Clear taxon" onClick={() => { onClear(); input.current?.focus(); }} />
        </Group>
      ) : null}
      <TextInput
        ref={input}
        aria-label="Taxon"
        placeholder="Find a taxon…"
        value={text}
        onChange={(e) => setText(e.currentTarget.value)}
        rightSection={suggest.isFetching ? <Loader size="xs" /> : null}
        disabled={xr.isError}
        description={xr.isError ? 'The taxon search is unavailable.' : undefined}
      />
      {debounced.length >= 2 && suggest.data?.length ? (
        <ul className={styles.suggestions} role="listbox" aria-label="Taxon suggestions">
          {suggest.data.slice(0, 8).map((s) => (
            <li key={s.usageId} role="presentation">
              <button
                type="button"
                role="option"
                aria-selected="false"
                className={styles.suggestion}
                onClick={() => {
                  onPick(s.usageId, s.match);
                  setText('');
                  // the chosen option goes away with the list; keep a keyboard user in the picker
                  input.current?.focus();
                }}
              >
                <SuggestionName text={s.suggestion} rank={s.rank} />
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      {suggest.isError ? <p className={styles.error}>Suggestions failed.</p> : null}
    </Stack>
  );
}
