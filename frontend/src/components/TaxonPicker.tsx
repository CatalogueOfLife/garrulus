import { useState } from 'react';
import { CloseButton, Group, Loader, Stack, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { getXr, suggestTaxa } from '../api';
import { SciName } from '../ui/SciName';
import styles from '../ui/pages.module.css';

interface Props {
  /** The label of the selected taxon, if any. */
  label?: string;
  onPick: (usageId: string, label: string) => void;
  onClear: () => void;
}

export function TaxonPicker({ label, onPick, onClear }: Props) {
  const [text, setText] = useState('');
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
          <CloseButton size="sm" aria-label="Clear taxon" onClick={onClear} />
        </Group>
      ) : null}
      <TextInput
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
                }}
              >
                <SciName name={s.suggestion} rank={s.rank} />
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      {suggest.isError ? <p className={styles.error}>Suggestions failed.</p> : null}
    </Stack>
  );
}
